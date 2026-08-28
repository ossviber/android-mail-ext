package ch.protonmail.android.extidentities.data.protonauth

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies

/**
 * Bcrypt with Proton's exact parameters: $2y$ prefix, cost 10, and the
 * server-provided salt. Uses at.favre.lib:bcrypt with the standard
 * 72-byte truncation behaviour (matches Proton's Go bcrypt fork).
 *
 * Proton feeds bcrypt a "salt string" built with its own base64 alphabet;
 * bcrypt only ever parses the first 22 chars (16 bytes) of it, so passing the
 * raw 16-byte salt to a standard implementation produces identical output.
 */
object ProtonBcrypt {

    private const val COST = 10

    /** Full Modular-Crypt-Format string, e.g. "$2y$10$<22 salt chars><31 hash chars>". */
    fun bcryptWithServerSalt(password: ByteArray, rawSalt: ByteArray): String {
        require(rawSalt.size >= 16) { "Salt must be at least 16 bytes, was ${rawSalt.size}" }
        val salt16 = rawSalt.copyOf(16)
        // IMPORTANT: the two-argument with(Version, LongPasswordStrategy) overload
        // must be used — the one-argument with(LongPasswordStrategy) hardcodes
        // version $2a$, which would silently change the SRP hash string.
        val bytes = BCrypt
            .with(
                BCrypt.Version.VERSION_2Y,
                LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2Y),
            )
            .hash(COST, salt16, password)
        return String(bytes, Charsets.US_ASCII)
    }

    /**
     * Proton "mailbox password": the key passphrase that unlocks the user's
     * OpenPGP keys. It is the last 31 chars (the base64 hash) of
     * bcrypt(password, KeySalt) — see go-proton-api salt_types.go:
     * `saltedKeyPass[len(saltedKeyPass)-31:]`.
     */
    fun mailboxPassword(password: ByteArray, keySalt: ByteArray): ByteArray {
        val mcf = bcryptWithServerSalt(password, keySalt)
        val suffix = mcf.substring(mcf.length - 31)
        return suffix.toByteArray(Charsets.US_ASCII)
    }
}
