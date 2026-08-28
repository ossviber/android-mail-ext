package ch.protonmail.android.extidentities.data.protonauth

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Client side of Proton's SRP-6a variant.
 *
 * Independent Kotlin implementation of the algorithm used by Proton's
 * reference clients (go-srp, MIT: https://github.com/ProtonMail/go-srp and
 * @proton/srp). Proton's variant:
 *  - 2048-bit modulus N and generator g = 2,
 *  - *little-endian* byte order for all big integers on the wire,
 *  - x = expandHash(bcrypt(password, salt||"proton") || N),
 *  - u = expandHash(le(A) || le(B)), k = expandHash(le(g) || le(N)) mod N,
 *  - M1 = expandHash(le(A) || le(B) || le(S)), M2 = expandHash(le(A) || M1 || le(S)).
 */
object Srp {

    private const val BIT_LENGTH = 2048

    data class Proofs(
        val clientProof: ByteArray,
        val clientEphemeral: ByteArray,
        val expectedServerProof: ByteArray,
    )

    /** expandHash(data) = SHA512(data||0) || SHA512(data||1) || SHA512(data||2) || SHA512(data||3) (256 bytes). */
    fun expandHash(data: ByteArray): ByteArray {
        val out = ByteArray(256)
        for (i in 0 until 4) {
            val md = MessageDigest.getInstance("SHA-512")
            md.update(data)
            md.update(i.toByte())
            val digest = md.digest()
            System.arraycopy(digest, 0, out, i * 64, 64)
        }
        return out
    }

    /** Little-endian bytes -> unsigned BigInteger (Go: big.Int.SetBytes(reversed)). */
    private fun toInt(le: ByteArray): BigInteger = BigInteger(1, le.reversedArray())

    /** BigInteger -> little-endian bytes, zero-padded to bitLength/8. */
    private fun fromInt(bitLength: Int, value: BigInteger): ByteArray {
        val out = ByteArray(bitLength / 8)
        val be = value.toByteArray() // big-endian, possibly with a leading 0 sign byte
        var start = 0
        if (be.size > 1 && be[0].toInt() == 0) start = 1
        val mag = be.copyOfRange(start, be.size)
        // mag is big-endian (mag[0] = most significant byte). Little-endian
        // output puts the least significant byte at out[0] (cf. go-srp's
        // fromInt: reversed[len(arr)-i-1] = arr[i]).
        for (idx in mag.indices) {
            out[mag.size - 1 - idx] = mag[idx]
        }
        return out
    }

    /**
     * Computes the SRP proofs for the /auth/v4 request.
     *
     * @param version  auth version from /auth/v4/info (3 or 4)
     * @param password login password (UTF-8 bytes)
     * @param saltB64  base64 salt from /auth/v4/info
     * @param modulus  base64-decoded modulus extracted from the signed modulus
     * @param serverEphemeralB64 base64 server ephemeral (B) from /auth/v4/info
     */
    fun generateProofs(
        version: Int,
        password: ByteArray,
        saltB64: String,
        modulus: ByteArray,
        serverEphemeralB64: String,
    ): Proofs {
        val x = toInt(hashPassword(version, password, saltB64, modulus))

        val n = toInt(modulus)
        val g = BigInteger.valueOf(2L)
        val nMinusOne = n.subtract(BigInteger.ONE)

        // k = expandHash(le(g) || le(N)) mod N
        val k = toInt(expandHash(fromInt(BIT_LENGTH, g) + fromInt(BIT_LENGTH, n))).mod(n)

        // B (server ephemeral), little-endian bytes from the API
        val bBytes = Base64.getDecoder().decode(serverEphemeralB64)
        val b = toInt(bBytes)
        require(b > BigInteger.ONE && b < nMinusOne) { "Server ephemeral out of bounds" }

        val random = SecureRandom()
        val lowerBound = BigInteger.valueOf((BIT_LENGTH * 2).toLong())

        var a: BigInteger
        var aBytesLE: ByteArray
        var u: BigInteger
        do {
            a = BigInteger(BIT_LENGTH, random).mod(nMinusOne)
            aBytesLE = fromInt(BIT_LENGTH, g.modPow(a, n))
            // u = expandHash(le(A) || le(B)) interpreted as a little-endian integer; must be non-zero
            u = toInt(expandHash(aBytesLE + bBytes))
        } while (a < lowerBound || a >= nMinusOne || u == BigInteger.ZERO)

        // base = (B - k * g^x) mod N
        val gPowX = g.modPow(x, n)
        val base = b.subtract(k.multiply(gPowX)).mod(n)

        // exponent = (u * x + a) mod (N - 1)
        val exponent = u.multiply(x).add(a).mod(nMinusOne)

        // S = base^exponent mod N
        val s = base.modPow(exponent, n)
        val sBytes = fromInt(BIT_LENGTH, s)

        // M1 = expandHash(le(A) || le(B) || le(S))
        val m1 = expandHash(aBytesLE + bBytes + sBytes)
        // M2 = expandHash(le(A) || M1 || le(S))
        val m2 = expandHash(aBytesLE + m1 + sBytes)

        return Proofs(
            clientProof = m1,
            clientEphemeral = aBytesLE,
            expectedServerProof = m2,
        )
    }

    /**
     * Proton's password hash for auth versions 3 and 4:
     *
     *   x = expandHash( bcrypt(password, "$2y$10$" + b64dotSlash(salt || "proton")) || N )
     *
     * The bcrypt parser only consumes the first 22 chars of the encoded salt,
     * which decode back to the first 16 bytes of (salt || "proton") — i.e. the
     * server salt itself — so the result is byte-identical to a standard bcrypt
     * over the raw 16-byte salt (see [ProtonBcrypt]).
     */
    fun hashPassword(version: Int, password: ByteArray, saltB64: String, modulus: ByteArray): ByteArray {
        require(version == 3 || version == 4) {
            "Unsupported auth version $version (only 3 and 4 are supported)"
        }
        val salt = Base64.getDecoder().decode(saltB64)
        val bcryptInput = salt + "proton".toByteArray(Charsets.UTF_8)
        val bcryptString = ProtonBcrypt.bcryptWithServerSalt(password, bcryptInput)
        return expandHash(bcryptString.toByteArray(Charsets.US_ASCII) + modulus)
    }
}
