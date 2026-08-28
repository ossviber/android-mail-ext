/*
 * Copyright (c) 2025 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.protonmail.android.extidentities.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.protonmail.android.mailcommon.data.mapper.safeData
import ch.protonmail.android.mailcommon.domain.model.PreferencesError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.proton.core.crypto.common.keystore.KeyStoreCrypto
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * DataStore-backed persistence for external identities and their Keystore-sealed
 * SMTP passwords. [observeAll] is a live flow: every write emits a new list, so the
 * identity list screen and the composer's sender picker stay up to date.
 *
 * Write failures are never swallowed: they propagate so the repository maps them to
 * [ch.protonmail.android.extidentities.domain.ExternalIdentitiesError.StorageFailure]
 * and the UI can show a real error.
 */
@Suppress("TooManyFunctions")
class ExternalIdentitiesLocalDataSourceImpl @Inject constructor(
    private val dataStoreProvider: ExternalIdentitiesDataStoreProvider,
    @ApplicationContext private val context: Context,
    private val keyStoreCrypto: KeyStoreCrypto
) : ExternalIdentitiesLocalDataSource {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dataStore get() = dataStoreProvider.dataStore

    private val migrationLock = Mutex()
    private var migrationAttempted = false

    override fun observeAll(): Flow<List<ExternalIdentityEntity>> =
        dataStore.safeData
            .onStart {
                runLegacyImportOnce()
                migrateLegacyServerConfigs()
            }
            .map { prefsOrError ->
                prefsOrError.fold(
                    ifLeft = { error: PreferencesError ->
                        Timber.e("ext-identities: failed to read identities store: $error")
                        emptyList()
                    },
                    ifRight = { prefs -> prefs[identitiesKey]?.let(::decode).orEmpty() }
                )
            }

    private fun decode(encoded: String): List<ExternalIdentityEntity> =
        runCatching { json.decodeFromString<List<ExternalIdentityEntity>>(encoded) }
            .onFailure { Timber.e(it, "ext-identities: failed to decode identities store") }
            .getOrDefault(emptyList())

    /**
     * Legacy identities stored inline SMTP settings (smtpHost/port/security/auth).
     * On first read we synthesize a stored server configuration from those fields
     * (named after the host), point the identity at it and rewrite the store.
     */
    private suspend fun migrateLegacyServerConfigs() {
        runCatching {
            val entities = readAll()
            if (entities.isEmpty()) return
            val needsMigration = entities.any { it.smtpServerConfigId <= 0L }
            if (!needsMigration) return
            var configs = getServerConfigs()
            val migrated = entities.map { entity ->
                if (entity.smtpServerConfigId > 0L) {
                    entity
                } else {
                    val host = entity.smtpHost ?: ""
                    val port = entity.smtpPort ?: 587
                    val configId = configs
                        .firstOrNull { it.host == host && it.port == port }
                        ?.id
                        ?: run {
                            val assigned = upsertServerConfig(
                                SmtpServerConfigEntity(
                                    id = NEW_ID,
                                    name = host,
                                    host = host,
                                    port = port,
                                    security = entity.smtpSecurity,
                                    authType = entity.smtpAuthType
                                )
                            )
                            configs = getServerConfigs()
                            assigned.id
                        }
                    entity.copy(smtpServerConfigId = configId)
                }
            }
            dataStore.edit { prefs ->
                prefs[identitiesKey] = json.encodeToString(migrated.sortedBy { it.sortOrder })
            }
        }.onFailure { Timber.e(it, "ext-identities: server config migration failed") }
    }

    override suspend fun getAll(): List<ExternalIdentityEntity> {
        runLegacyImportOnce()
        migrateLegacyServerConfigs()
        return readAll()
    }

    override suspend fun findById(id: Long): ExternalIdentityEntity? =
        getAll().find { it.id == id }

    override suspend fun upsert(
        entity: ExternalIdentityEntity,
        password: CharArray?
    ): ExternalIdentityEntity {
        runLegacyImportOnce()
        val assigned = if (entity.id == NEW_ID) {
            val nextId = (readAll().maxOfOrNull { it.id } ?: 0L) + 1L
            entity.copy(id = nextId)
        } else {
            entity
        }

        if (assigned.smtpAuthType != SmtpAuthTypeDto.None && password != null && password.isNotEmpty()) {
            sealPassword(assigned.id, password)
        }

        dataStore.edit { prefs ->
            val current = prefs[identitiesKey]?.let(::decode).orEmpty()
            val updated = (current.filterNot { it.id == assigned.id } + assigned)
                .sortedBy { it.sortOrder }
            prefs[identitiesKey] = json.encodeToString(updated)
        }
        return assigned
    }

    override suspend fun delete(id: Long) {
        dataStore.edit { prefs ->
            val current = prefs[identitiesKey]?.let(::decode).orEmpty()
            prefs[identitiesKey] = json.encodeToString(current.filterNot { it.id == id })
            prefs.remove(passwordKey(id))
        }
    }

    override suspend fun setSentLabel(id: Long, labelId: String?, labelName: String?) {
        dataStore.edit { prefs ->
            val current = prefs[identitiesKey]?.let(::decode).orEmpty()
            val updated = current.map { entity ->
                if (entity.id == id) entity.copy(sentLabelId = labelId, sentLabelName = labelName) else entity
            }
            prefs[identitiesKey] = json.encodeToString(updated)
        }
    }

    override suspend fun setSentFilterId(id: Long, filterId: String?) {
        dataStore.edit { prefs ->
            val current = prefs[identitiesKey]?.let(::decode).orEmpty()
            val updated = current.map { entity ->
                if (entity.id == id) entity.copy(sentFilterId = filterId) else entity
            }
            prefs[identitiesKey] = json.encodeToString(updated)
        }
    }

    override suspend fun getSealedPassword(id: Long): String? = try {
        dataStore.data.first()[passwordKey(id)]
    } catch (e: Exception) {
        Timber.e(e, "ext-identities: failed to read sealed password")
        null
    }

    override suspend fun hasPassword(id: Long): Boolean = getSealedPassword(id) != null

    private suspend fun readAll(): List<ExternalIdentityEntity> = try {
        dataStore.data.first()[identitiesKey]?.let(::decode).orEmpty()
    } catch (e: Exception) {
        Timber.e(e, "ext-identities: failed to read identities store")
        emptyList()
    }

    private suspend fun sealPassword(id: Long, plain: CharArray) {
        // Encrypt outside the DataStore transaction so a Keystore failure surfaces
        // before anything is persisted.
        val sealed = keyStoreCrypto.encrypt(plain.concatToString())
        dataStore.edit { prefs -> prefs[passwordKey(id)] = sealed }
    }

    /**
     * One-time import of identities persisted by the earlier file-based
     * implementation (identities.json / secrets.json in filesDir), so no data
     * saved while testing that build is lost.
     */
    private suspend fun runLegacyImportOnce() {
        if (migrationAttempted) return
        migrationLock.withLock {
            if (migrationAttempted) return
            migrationAttempted = true
            runCatching { importLegacyFilesIfNeeded() }
                .onFailure { Timber.e(it, "ext-identities: legacy identities import failed") }
        }
    }

    private suspend fun importLegacyFilesIfNeeded() {
        val identitiesFile = File(context.filesDir, LEGACY_IDENTITIES_FILE)
        val secretsFile = File(context.filesDir, LEGACY_SECRETS_FILE)
        if (!identitiesFile.exists() && !secretsFile.exists()) return

        val alreadyPopulated = dataStore.data.first()[identitiesKey] != null
        if (alreadyPopulated) {
            retireLegacyFiles(identitiesFile, secretsFile)
            return
        }

        val entities = if (identitiesFile.exists()) {
            runCatching {
                json.decodeFromString<List<ExternalIdentityEntity>>(
                    identitiesFile.readBytes().toString(Charsets.UTF_8)
                )
            }.onFailure { Timber.e(it, "ext-identities: failed to decode legacy identities") }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val secrets = if (secretsFile.exists()) {
            runCatching {
                json.decodeFromString<Map<String, String>>(
                    secretsFile.readBytes().toString(Charsets.UTF_8)
                )
            }.onFailure { Timber.e(it, "ext-identities: failed to decode legacy secrets") }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        dataStore.edit { prefs ->
            if (entities.isNotEmpty()) {
                prefs[identitiesKey] = json.encodeToString(entities.sortedBy { it.sortOrder })
            }
            secrets.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
        retireLegacyFiles(identitiesFile, secretsFile)
        Timber.i("ext-identities: imported %d legacy identities from files", entities.size)
    }

    private fun retireLegacyFiles(vararg files: File) {
        files.forEach { file ->
            if (file.exists() && !file.renameTo(File(file.parentFile, file.name + LEGACY_SUFFIX))) {
                Timber.w("ext-identities: could not retire legacy file %s", file.name)
            }
        }
    }

    override fun observeServerConfigs(): Flow<List<SmtpServerConfigEntity>> =
        dataStore.safeData.map { prefsOrError ->
            prefsOrError.fold(
                ifLeft = { error ->
                    Timber.e("ext-identities: failed to read server configs store: $error")
                    emptyList()
                },
                ifRight = { prefs -> prefs[serverConfigsKey]?.let(::decodeConfigs).orEmpty() }
            )
        }

    override suspend fun getServerConfigs(): List<SmtpServerConfigEntity> = try {
        dataStore.data.first()[serverConfigsKey]?.let(::decodeConfigs).orEmpty()
    } catch (e: Exception) {
        Timber.e(e, "ext-identities: failed to read server configs store")
        emptyList()
    }

    override suspend fun upsertServerConfig(entity: SmtpServerConfigEntity): SmtpServerConfigEntity {
        val assigned = if (entity.id == NEW_ID) {
            val nextId = (getServerConfigs().maxOfOrNull { it.id } ?: 0L) + 1L
            entity.copy(id = nextId)
        } else {
            entity
        }
        dataStore.edit { prefs ->
            val current = prefs[serverConfigsKey]?.let(::decodeConfigs).orEmpty()
            prefs[serverConfigsKey] = json.encodeToString(
                current.filterNot { it.id == assigned.id } + assigned
            )
        }
        return assigned
    }

    override suspend fun deleteServerConfig(id: Long) {
        dataStore.edit { prefs ->
            val current = prefs[serverConfigsKey]?.let(::decodeConfigs).orEmpty()
            prefs[serverConfigsKey] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    private fun decodeConfigs(encoded: String): List<SmtpServerConfigEntity> =
        runCatching { json.decodeFromString<List<SmtpServerConfigEntity>>(encoded) }
            .onFailure { Timber.e(it, "ext-identities: failed to decode server configs store") }
            .getOrDefault(emptyList())

    private fun passwordKey(id: Long) = stringPreferencesKey("smtpPassword/$id")

    companion object {
        const val NEW_ID = 0L

        private const val IDENTITIES_PREF = "externalIdentitiesJson"
        private const val LEGACY_IDENTITIES_FILE = "identities.json"
        private const val LEGACY_SECRETS_FILE = "secrets.json"
        private const val LEGACY_SUFFIX = ".imported"

        private val identitiesKey get() = stringPreferencesKey(IDENTITIES_PREF)
        private val serverConfigsKey get() = stringPreferencesKey("smtpServerConfigsJson")
    }
}