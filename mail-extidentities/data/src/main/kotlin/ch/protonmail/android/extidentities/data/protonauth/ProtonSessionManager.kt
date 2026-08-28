package ch.protonmail.android.extidentities.data.protonauth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.protonmail.android.extidentities.data.protonauth.ModulusVerifier
import ch.protonmail.android.extidentities.domain.repository.ProtonLoginStatus
import ch.protonmail.android.extidentities.domain.repository.ProtonSessionInfo
import ch.protonmail.android.extidentities.domain.repository.ProtonSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import jakarta.mail.internet.MimeMessage
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.preferencesDataStore
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Owns a dedicated Proton API session for the external-identities feature:
 * full SRP login (see [ProtonSrp]), 2FA continuation, token refresh, and the
 * Proton web API calls we need (filters, addresses). Independent from the
 * Rust mail session on purpose - the Rust SDK exposes no filters API and its
 * fork selectors cannot be exchanged from app code.
 */
private val Context.protonSessionStore: DataStore<Preferences> by preferencesDataStore(name = "proton_session")

@Singleton
class ProtonSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) : ProtonSessionRepository {

    private val store = context.protonSessionStore
    private val json = Json { ignoreUnknownKeys = true }

    data class PendingTwoFa(val uid: String, val accessToken: String, val refreshToken: String, val username: String)

    @Volatile
    private var pendingTwoFa: PendingTwoFa? = null

    override suspend fun getStoredSession(): ProtonSessionInfo? {
        val prefs = store.data.first()
        val uid = prefs[KEY_UID] ?: return null
        return ProtonSessionInfo(
            uid = uid,
            accessToken = prefs[KEY_ACCESS] ?: return null,
            refreshToken = prefs[KEY_REFRESH] ?: return null,
            username = prefs[KEY_USERNAME].orEmpty()
        )
    }

    override suspend fun getProtonAddresses(): List<String> {
        val raw = store.data.first()[KEY_ADDRESSES] ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    override suspend fun signOut() {
        store.edit { prefs ->
            listOf(KEY_UID, KEY_ACCESS, KEY_REFRESH, KEY_USERNAME, KEY_ADDRESSES).forEach { prefs.remove(it) }
        }
        pendingTwoFa = null
    }

    /** Continues a login paused at the 2FA step. */
    override suspend fun continueLogin(totp: String): ProtonLoginStatus {
        val pending = pendingTwoFa ?: return ProtonLoginStatus.Error("no pending 2FA login")
        val (code, body) = apiCall(
            method = "POST",
            path = "core/v4/auth/2fa",
            uid = pending.uid,
            bearer = pending.accessToken,
            body = JSONObject().put("TwoFactorCode", totp)
        )
        if (code != 200) {
            pendingTwoFa = null
            return ProtonLoginStatus.Error("2FA failed: " + errorDetail(code, body))
        }
        pendingTwoFa = null
        return finishLogin(
            uid = JSONObject(body).optString("Uid").ifEmpty { pending.uid },
            accessToken = JSONObject(body).optString("AccessToken").ifEmpty { pending.accessToken },
            refreshToken = JSONObject(body).optString("RefreshToken").ifEmpty { pending.refreshToken },
            username = pending.username
        )
    }

    override suspend fun login(username: String, password: String, totp: String?): ProtonLoginStatus = withContext(Dispatchers.IO) {
        try {
            // 1. anonymous session (auth endpoints require one)
            var result = apiCall("POST", "auth/v4/sessions", body = JSONObject())
            var code = result.first
            var body = result.second
            requireOk(code, body)
            var payload = JSONObject(body)
            val anonUid = payload.optString("UID")
            val anonAccess = payload.optString("AccessToken")

            // 2. auth info
            result = apiCall(
                "POST",
                "core/v4/auth/info",
                uid = anonUid,
                bearer = anonAccess,
                body = JSONObject()
                    .put("Username", username.trim())
                    .put("Intent", "Proton")
            )
            requireOk(result.first, result.second)
            payload = JSONObject(result.second)
            val version = payload.optInt("Version", 4)
            val modulusB64 = payload.optString("Modulus")
            val serverEphemeral = payload.optString("ServerEphemeral")
            val salt = payload.optString("Salt")
            val srpSession = payload.optString("SRPSession")
            if (serverEphemeral.isEmpty()) {
                return@withContext ProtonLoginStatus.Error("auth info incomplete: " + result.second.take(140))
            }

            // 3. SRP proofs (modulus signature check omitted; transport is TLS)
            val modulus = if (modulusB64.isNotEmpty()) {
                if (!ModulusVerifier.isModulusSignatureValid(modulusB64)) {
                    return@withContext ProtonLoginStatus.Error("modulus signature verification failed")
                }
                ModulusVerifier.extractModulus(modulusB64)
            } else {
                hexToBytes(RFC5054_MODULUS_HEX).reversedArray()
            }
            val passwordBytes = password.toByteArray(Charsets.UTF_8)
            val proofs = Srp.generateProofs(version, passwordBytes, salt, modulus, serverEphemeral)
            passwordBytes.fill(0)

            // 4. authenticate
            result = apiCall(
                "POST",
                "core/v4/auth",
                uid = anonUid,
                bearer = anonAccess,
                body = JSONObject()
                    .put("Username", username.trim())
                    .put("ClientEphemeral", Base64.getEncoder().encodeToString(proofs.clientEphemeral))
                    .put("ClientProof", Base64.getEncoder().encodeToString(proofs.clientProof))
                    .put("SRPSession", srpSession)
            )
            requireOk(result.first, result.second)
            payload = JSONObject(result.second)

            val actualProof = Base64.getDecoder().decode(payload.optString("ServerProof"))
            if (!proofs.expectedServerProof.contentEquals(actualProof)) {
                return@withContext ProtonLoginStatus.Error("server proof mismatch - possible MITM")
            }

            val uid = payload.optString("UID")
            val access = payload.optString("AccessToken")
            val refresh = payload.optString("RefreshToken")
            val twoFaEnabled = payload.optJSONObject("TwoFA")?.optInt("TOTP", 0) == 1

            if (twoFaEnabled) {
                if (totp.isNullOrBlank()) {
                    pendingTwoFa = PendingTwoFa(uid, access, refresh, username.trim())
                    return@withContext ProtonLoginStatus.NeedsTotp
                }
                val tfaResult = apiCall(
                    "POST",
                    "core/v4/auth/2fa",
                    uid = uid,
                    bearer = access,
                    body = JSONObject().put("TwoFactorCode", totp)
                )
                if (tfaResult.first != 200) {
                    return@withContext ProtonLoginStatus.Error("2FA failed: " + errorDetail(tfaResult.first, tfaResult.second))
                }
                val tfaJson = JSONObject(tfaResult.second)
                return@withContext finishLogin(
                    uid = tfaJson.optString("Uid").ifEmpty { uid },
                    accessToken = tfaJson.optString("AccessToken").ifEmpty { access },
                    refreshToken = tfaJson.optString("RefreshToken").ifEmpty { refresh },
                    username = username.trim()
                )
            }
            finishLogin(uid, access, refresh, username.trim())
        } catch (e: Exception) {
            ProtonLoginStatus.Error(e.message ?: "login failed")
        }
    }

    private suspend fun finishLogin(uid: String, accessToken: String, refreshToken: String, username: String): ProtonLoginStatus {
        store.edit { prefs ->
            prefs[KEY_UID] = uid
            prefs[KEY_ACCESS] = accessToken
            prefs[KEY_REFRESH] = refreshToken
            prefs[KEY_USERNAME] = username
        }
        val addresses = fetchAndStoreAddresses(uid, accessToken)
        return ProtonLoginStatus.Success(username, addresses)
    }

    /** GET /core/v4/addresses - the Proton addresses protected from filing. */
    private suspend fun fetchAndStoreAddresses(uid: String, accessToken: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = apiCall("GET", "core/v4/addresses", uid = uid, bearer = accessToken)
            if (code != 200) return@withContext emptyList()
            val addresses = JSONObject(body)
                .optJSONArray("Addresses")
                ?.let { array -> (0 until array.length()).map { array.getJSONObject(it).optString("Email") } }
                .orEmpty()
                .filter { it.isNotBlank() }
            store.edit { prefs ->
                prefs[KEY_ADDRESSES] = json.encodeToString(addresses)
            }
            addresses
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves the SERVER label id for an exact label name via the REST API.
     * The Rust SDK only exposes local DB ids for labels, which the import
     * endpoint rejects - the server id is required there.
     */
    suspend fun findServerLabelIdByName(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val session = getStoredSession() ?: return@withContext null
            val endpoint = URL("https://mail." + host() + "/api/core/v4/labels?Type=1")
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.protonmail.v1+json")
            connection.setRequestProperty("x-pm-appversion", APP_VERSION)
            connection.setRequestProperty("x-pm-apiversion", API_VERSION)
            connection.setRequestProperty("Cookie", "Session-Id=" + session.uid)
            connection.setRequestProperty("x-pm-uid", session.uid)
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code != 200) return@withContext null
            val array = JSONObject(body).optJSONArray("Labels") ?: return@withContext null
            (0 until array.length()).map { array.getJSONObject(it) }
                .firstOrNull { it.optString("Name") == name }
                ?.optString("ID")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.w(e, "ext-identities: server label lookup failed")
            null
        }
    }

    override suspend fun findFilterIdByName(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val session = getStoredSession() ?: return@withContext null
            val endpoint = URL("https://mail." + host() + "/api/mail/v4/filters")
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.protonmail.v1+json")
            connection.setRequestProperty("x-pm-appversion", APP_VERSION)
            connection.setRequestProperty("x-pm-apiversion", API_VERSION)
            connection.setRequestProperty("Cookie", "Session-Id=" + session.uid)
            connection.setRequestProperty("x-pm-uid", session.uid)
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code != 200) return@withContext null
            val array = JSONObject(body).optJSONArray("Filters") ?: return@withContext null
            (0 until array.length()).map { array.getJSONObject(it) }
                .firstOrNull { it.optString("Name") == name }
                ?.optString("ID")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.w(e, "ext-identities: filter lookup failed")
            null
        }
    }

    override suspend fun createFilter(name: String, sieve: String): String? = withContext(Dispatchers.IO) {
        try {
            val session = getStoredSession() ?: return@withContext null
            val payload = JSONObject()
                .put("Name", name)
                .put("Sieve", sieve)
                .put("Version", 2)
                .toString()
            val endpoint = URL("https://mail." + host() + "/api/mail/v4/filters")
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/vnd.protonmail.v1+json")
            connection.setRequestProperty("x-pm-appversion", APP_VERSION)
            connection.setRequestProperty("x-pm-apiversion", API_VERSION)
            connection.setRequestProperty("Cookie", "Session-Id=" + session.uid)
            connection.setRequestProperty("x-pm-uid", session.uid)
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                Timber.w("ext-identities: createFilter failed: " + code + " " + body.take(120))
                return@withContext null
            }
            JSONObject(body).optJSONObject("Filter")?.optString("ID")?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.w(e, "ext-identities: createFilter crashed")
            null
        }
    }

    override suspend fun setFilterEnabled(filterId: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = getStoredSession() ?: return@withContext false
            val payload = JSONObject().put("Enabled", enabled).toString()
            val endpoint = URL("https://mail." + host() + "/api/mail/v4/filters/" + filterId)
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/vnd.protonmail.v1+json")
            connection.setRequestProperty("x-pm-appversion", APP_VERSION)
            connection.setRequestProperty("x-pm-apiversion", API_VERSION)
            connection.setRequestProperty("Cookie", "Session-Id=" + session.uid)
            connection.setRequestProperty("x-pm-uid", session.uid)
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Timber.w(e, "ext-identities: setFilterEnabled crashed")
            false
        }
    }

    /**
     * Bridge-style sent-copy import: encrypts the RFC822 message to the
     * account address public key and uploads it into the internal Sent folder
     * via POST mail/v4/messages/import using the dedicated session.
     */
    suspend fun importSentCopyToSent(
        mimeMessage: MimeMessage,
        sentLabelId: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val session = getStoredSession()
                ?: return@withContext false to "no Proton session - sign in first"
            val (addrCode, addrBody) = apiCall("GET", "core/v4/addresses", uid = session.uid, bearer = session.accessToken)
            if (addrCode != 200) {
                return@withContext false to "addresses fetch " + addrCode + ": " + addrBody.take(120)
            }
            var addressId: String? = null
            var publicKeyArmored: String? = null
            val addresses = JSONObject(addrBody).optJSONArray("Addresses")
            if (addresses != null) {
                for (i in 0 until addresses.length()) {
                    val address = addresses.getJSONObject(i)
                    val keys = address.optJSONArray("Keys")
                    val key = keys?.optJSONObject(0)?.optString("PublicKey")
                    if (!key.isNullOrEmpty() && addressId == null) {
                        addressId = address.optString("ID")
                        publicKeyArmored = key
                        break
                    }
                }
            }
            if (addressId == null || publicKeyArmored == null) {
                return@withContext false to "no address with public key found"
            }
            runCatching {
                java.io.File(context.filesDir, "address_pubkey.asc").writeText(publicKeyArmored)
                val key = ProtonImportCrypto.encryptionKeyFromArmored(publicKeyArmored)
                Timber.d("ext-identities: import address=" + addressId + " keyAlgo=" + key.algorithm +
                    " keyId=" + java.lang.Long.toHexString(key.keyID))
            }
            val importPackage = ProtonImportCrypto.buildImportPackage(mimeMessage, publicKeyArmored)
            runCatching {
                val dump = java.io.File(context.filesDir, "last_import.eml")
                dump.writeBytes(importPackage)
            }
            Timber.d("ext-identities: import package head=" + String(importPackage, Charsets.UTF_8).take(600))
            fun postImport(labelId: String?): Pair<Int, String> {
                val metadata = JSONObject().put(
                    "0",
                    JSONObject()
                        .put("AddressID", addressId)
                        .put("LabelIDs", org.json.JSONArray(listOf("7") + listOfNotNull(labelId?.takeIf { it.isNotBlank() })))
                        .put("Unread", 0)
                        .put("Flags", 2)
                ).toString()
                val boundary = "----sentcopy" + System.currentTimeMillis()
                val endpoint = URL("https://mail." + host() + "/api/mail/v4/messages/import")
                val connection = endpoint.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                connection.setRequestProperty("Accept", "application/vnd.protonmail.v1+json")
                connection.setRequestProperty("x-pm-appversion", "android-mail@5.0.0")
                connection.setRequestProperty("x-pm-apiversion", "4")
                connection.setRequestProperty("Cookie", "Session-Id=" + session.uid)
                connection.setRequestProperty("x-pm-uid", session.uid)
                connection.setRequestProperty("Authorization", "Bearer " + session.accessToken)
                connection.outputStream.use { output ->
                    val CRLF = "\r\n"
                    val DASH = "--" + boundary + CRLF
                    output.write(DASH.toByteArray(Charsets.US_ASCII))
                    output.write(("Content-Disposition: form-data; name=\"0\"; filename=\"0.eml\"" + CRLF).toByteArray(Charsets.US_ASCII))
                    output.write(("Content-Type: message/rfc822" + CRLF + CRLF).toByteArray(Charsets.US_ASCII))
                    output.write(importPackage)
                    output.write(CRLF.toByteArray(Charsets.US_ASCII))
                    output.write((CRLF + "--" + boundary + CRLF).toByteArray(Charsets.US_ASCII))
                    output.write(("Content-Disposition: form-data; name=\"Metadata\"" + CRLF).toByteArray(Charsets.US_ASCII))
                    output.write(("Content-Type: application/json" + CRLF + CRLF).toByteArray(Charsets.US_ASCII))
                    output.write(metadata.toByteArray(Charsets.US_ASCII))
                    output.write((CRLF + "--" + boundary + "--" + CRLF).toByteArray(Charsets.US_ASCII))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                return code to body
            }

            var (code, body) = postImport(sentLabelId)
            var innerCode = runCatching { JSONObject(body).optJSONArray("Responses")?.optJSONObject(0)?.optJSONObject("Response")?.optInt("Code") }.getOrNull()
            // Self-heal: a stale or invalid stored label id must not lose the copy -
            // retry once without the label so the message still lands in Sent.
            if (innerCode == 36011 && !sentLabelId.isNullOrBlank()) {
                Timber.w("ext-identities: import rejected label " + sentLabelId + " - retrying without it")
                val retry = postImport(null)
                code = retry.first
                body = retry.second
                innerCode = runCatching { JSONObject(body).optJSONArray("Responses")?.optJSONObject(0)?.optJSONObject("Response")?.optInt("Code") }.getOrNull()
            }
            val ok = code in 200..299 && innerCode == 1000
            Timber.i("ext-identities: sent copy import ok=" + ok + " detail=" + code + ": " + body.take(160))
            ok to (code.toString() + ": " + body.take(200))
        } catch (e: Exception) {
            Timber.w(e, "ext-identities: sent copy import crashed")
            false to ("io: " + e.message.orEmpty())
        }
    }

    private suspend fun refreshSession(session: ProtonSessionInfo): ProtonSessionInfo? = withContext(Dispatchers.IO) {
        try {
            val state = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val body = JSONObject()
                .put("UID", session.uid)
                .put("RefreshToken", session.refreshToken)
                .put("ResponseType", "token")
                .put("GrantType", "refresh_token")
                .put("RedirectURI", "https://protonmail.ch")
                .put("State", Base64.getEncoder().encodeToString(state))
                .put("AccessToken", session.accessToken)
            val (code, bodyText) = apiCall("POST", "auth/v4/refresh", uid = session.uid, body = body)
            if (code != 200) return@withContext null
            val json = JSONObject(bodyText)
            val refreshed = session.copy(
                accessToken = json.optString("AccessToken").ifEmpty { session.accessToken },
                refreshToken = json.optString("RefreshToken").ifEmpty { session.refreshToken }
            )
            store.edit { prefs ->
                prefs[KEY_ACCESS] = refreshed.accessToken
                prefs[KEY_REFRESH] = refreshed.refreshToken
            }
            refreshed
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun apiCall(
        method: String,
        path: String,
        uid: String? = null,
        bearer: String? = null,
        body: JSONObject? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = URL("https://mail." + host() + "/api/" + path)
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.protonmail.v1+json")
            connection.setRequestProperty("x-pm-appversion", "android-mail@5.0.0")
            connection.setRequestProperty("x-pm-apiversion", "4")
            if (uid != null) connection.setRequestProperty("x-pm-uid", uid)
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            Timber.d("auto-auth: " + method + " " + endpoint + " -> " + code + " " + text.take(400))
            code to text
        } catch (e: Exception) {
            0 to ("io: " + e.message.orEmpty())
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun requireOk(code: Int, body: String) {
        if (code !in 200..299) {
            throw IllegalStateException(errorDetail(code, body))
        }
    }

    private fun errorDetail(code: Int, body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val message = json?.optString("Error").orEmpty()
        return message.ifEmpty { "HTTP " + code }
    }

    private fun host(): String = "proton.me"

    // Proton SRP v4 uses the well-known RFC 5054 2048-bit group; the server
    // omits the Modulus field in auth/info for these accounts.
    private val RFC5054_MODULUS_HEX =
        "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE48E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B297BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9AFD5138FE8376435B9FC61D2FC0EB06E315369DEF3CAFB939277AB1F12A8617A47BBBDBA51DF499AC4C80BEEEA9614B19CC4D5F4F5F556E27CBDE51C6A94BE4607A291558903BA0D0F84380B655BB9A22E8DCDF028A7CEC67F0D08134B1C8B97989149B609E0BE3BAB63D47548381DBC5B1FC764E3F4B53DD9DA1158BFD3E2B9C8CF56EDF019539349627DB2FD53D24B7"

    private companion object {
        const val HOST = "proton.me"
        const val APP_VERSION = "android-mail@5.0.0"
        const val API_VERSION = "4"
        const val CONNECT_TIMEOUT_MS = 10000
        const val READ_TIMEOUT_MS = 20000
        val KEY_UID = stringPreferencesKey("proton_uid")
        val KEY_ACCESS = stringPreferencesKey("proton_access")
        val KEY_REFRESH = stringPreferencesKey("proton_refresh")
        val KEY_USERNAME = stringPreferencesKey("proton_username")
        val KEY_ADDRESSES = stringPreferencesKey("proton_addresses")
    }
}