package ch.protonmail.android.extidentities.data.protonauth

import java.security.Security
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider

/**
 * Handles the signed modulus returned by /auth/v4/info.
 *
 * Proton signs the SRP modulus with a dedicated PGP key so clients can detect
 * a tampered modulus (go-srp: readClearSignedMessage). The signature check is
 * defense-in-depth on top of TLS; and it is enabled: a modulus that fails the
 * PGP signature check aborts the login. The canonicalization below matches
 * Bouncy Castle's clearsigned-text processor (dash-unescape + CRLF joining);
 * keep the two in sync if you ever change one.
 */
object ModulusVerifier {

    /** Verify the clearsigned SRP modulus against Proton's published key. */
    const val VERIFY_SIGNATURE = true

    /** Proton's published SRP modulus signing key (public data, from Proton's open-source go-srp). */
    private val MODULUS_SIGNING_KEY = """
        -----BEGIN PGP PUBLIC KEY BLOCK-----

        xjMEXAHLgxYJKwYBBAHaRw8BAQdAFurWXXwjTemqjD7CXjXVyKf0of7n9Ctm
        L8v9enkzggHNEnByb3RvbkBzcnAubW9kdWx1c8J3BBAWCgApBQJcAcuDBgsJ
        BwgDAgkQNQWFxOlRjyYEFQgKAgMWAgECGQECGwMCHgEAAPGRAP9sauJsW12U
        MnTQUZpsbJb53d0Wv55mZIIiJL2XulpWPQD/V6NglBd96lZKBmInSXX/kXat
        Sv+y0io+LR8i2+jV+AbOOARcAcuDEgorBgEEAZdVAQUBAQdAeJHUz1c9+KfE
        kSIgcBRE3WuXC4oj5a2/U3oASExGDW4DAQgHwmEEGBYIABMFAlwBy4MJEDUF
        hcTpUY8mAhsMAAD/XQD8DxNI6E78meodQI+wLsrKLeHn32iLvUqJbVDhfWSU
        WO4BAMcm1u02t4VKw++ttECPt+HUgPUq5pqQWe5Q2cW4TMsE
        =Y4Mw
        -----END PGP PUBLIC KEY BLOCK-----
    """.trimIndent()

    /**
     * Extracts and base64-decodes the SRP modulus from the clearsigned block.
     * The modulus is the single line between the armor header and the
     * signature block.
     */
    fun extractModulus(clearsigned: String): ByteArray {
        val normalized = clearsigned.replace("\r\n", "\n")
        val sigStart = normalized.indexOf("-----BEGIN PGP SIGNATURE-----")
        require(sigStart > 0) { "Invalid signed modulus: no signature block" }
        val body = normalized.substring(0, sigStart)
        val headerEnd = body.indexOf("\n\n")
        require(headerEnd >= 0) { "Invalid signed modulus: no message body" }
        val message = body.substring(headerEnd + 2).trim()
        val b64 = message.split('\n').joinToString("") { it.trim() }
        return Base64.getDecoder().decode(b64)
    }

    /**
     * Verifies the clearsigned modulus against Proton's SRP modulus key.
     * Returns true when [VERIFY_SIGNATURE] is disabled (no-op mode).
     */
    fun isModulusSignatureValid(clearsigned: String): Boolean {
        if (!VERIFY_SIGNATURE) return true
        return try {
            val normalized = clearsigned.replace("\r\n", "\n")
            val sigStart = normalized.indexOf("-----BEGIN PGP SIGNATURE-----")
            if (sigStart <= 0) return false
            val body = normalized.substring(0, sigStart)
            val headerEnd = body.indexOf("\n\n")
            if (headerEnd < 0) return false
            val message = body.substring(headerEnd + 2).trimEnd('\n', '\r')
            val sigBlock = normalized.substring(sigStart)

            val canonical = message.split('\n').joinToString("\r\n") { line ->
                if (line.startsWith("- ")) line.removePrefix("- ") else line
            }

            val pubRings = org.bouncycastle.openpgp.PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(MODULUS_SIGNING_KEY.byteInputStream()),
                JcaKeyFingerprintCalculator(),
            )

            val factory = PGPObjectFactory(
                PGPUtil.getDecoderStream(sigBlock.byteInputStream()),
                JcaKeyFingerprintCalculator(),
            )
            // BouncyCastle parses the clearsigned signature block as a
            // PGPSignatureList even when it holds a single signature.
            val signature = when (val obj = factory.nextObject()) {
                is PGPSignature -> obj
                is PGPSignatureList -> obj.get(0)
                else -> return false
            }
            // Look the key up by the signature's issuer ID instead of assuming
            // the first key in the ring is the signer.
            val pubKey: PGPPublicKey = pubRings.getPublicKey(signature.keyID) ?: return false
            // bcpg 1.78: signature verification initialisation is `init` (renamed initVerify in later versions).
            // Android ships a crippled legacy "BC" provider without Ed25519;
            // use the bundled BouncyCastle provider object explicitly.
            val bcProvider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                ?.let { it as? BouncyCastleProvider }
                ?: BouncyCastleProvider().also { Security.insertProviderAt(it, 1) }
            signature.init(JcaPGPContentVerifierBuilderProvider().setProvider(bcProvider), pubKey)
            signature.update(canonical.toByteArray(Charsets.UTF_8))
            signature.verify()
        } catch (e: Exception) {
            android.util.Log.e("ModulusVerifier", "modulus signature verification threw", e)
            false
        }
    }
}
