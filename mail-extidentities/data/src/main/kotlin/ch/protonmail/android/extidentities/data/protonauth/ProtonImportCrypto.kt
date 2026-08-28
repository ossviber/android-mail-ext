package ch.protonmail.android.extidentities.data.protonauth

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import jakarta.mail.internet.MimeMessage
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.ECDHPublicBCPGKey
import org.bouncycastle.crypto.engines.AESWrapEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator

/**
 * Bridge-style sent-copy packaging matching Proton's own stored message
 * format: a single-part message whose body is ONE armored PGP block (the
 * encryption of the original message body). This is the shape the
 * mail/v4/messages/import endpoint accepts as 'fully encrypted'.
 *
 * The PGP encryption is implemented manually (not via BouncyCastle's
 * high-level API) because BouncyCastle's ECDH key wrap is incompatible with
 * gopenpgp (Proton's server-side decryption library) for legacy Curve25519
 * (CV25519, algorithm 18) keys. This implementation follows Proton's
 * go-crypto fork bit-for-bit:
 *  - PKESK: v3 header (version + key id + algo 18), ephemeral X25519 point as
 *    a bit-length-prefixed MPI, session key block wrapped with AES-KeyWrap
 *    using MB = Hash(0x00000001 || Z || Param) per RFC 6637 / go-crypto
 *    buildKey (Param includes the OID length prefix and the key's own KDF
 *    hash/cipher ids).
 *  - SEIPD v1: new-format packet (tag 18) with version byte 0x01 outside the
 *    ciphertext; OpenPGP CFB in the go-crypto "no resync" variant over the
 *    literal packet + MDC trailer; MDC hash covers the random prefix
 *    (iv || iv[14..16]) + literal + trailer header, exactly like go-crypto's
 *    seMDCWriter.
 */
object ProtonImportCrypto {

    private val CRLF: String = "\r\n"

    private val CURVE_OID = byteArrayOf(0x2B, 0x06, 0x01, 0x04, 0x01, 0x97.toByte(), 0x55, 0x01, 0x05, 0x01)
    private val ANON_SENDER = "Anonymous Sender    ".toByteArray(Charsets.US_ASCII)

    fun encryptionKeyFromArmored(armored: String): PGPPublicKey {
        val rings = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(armored.byteInputStream()),
            JcaKeyFingerprintCalculator()
        )
        val iterator = rings.keyRings
        while (iterator.hasNext()) {
            val keyIterator = iterator.next().publicKeys
            while (keyIterator.hasNext()) {
                val key = keyIterator.next()
                if (key.isEncryptionKey) return key
            }
        }
        error("no encryption key found in armored public key")
    }

    fun encrypt(publicKey: PGPPublicKey, data: ByteArray): ByteArray {
        val ecdh = publicKey.publicKeyPacket.key as ECDHPublicBCPGKey
        val kdfHashId = ecdh.hashAlgorithm.toInt() and 0xff
        val kdfCipherId = ecdh.symmetricKeyAlgorithm.toInt() and 0xff
        val recipientU = recipientPoint(publicKey)
        val fingerprint = publicKey.fingerprint

        val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        // ---- literal packet (old format, tag 11) ----
        val lit = ByteArrayOutputStream()
        lit.write(0x62); lit.write(0x00)
        val t = System.currentTimeMillis() / 1000
        lit.write((t ushr 24).toInt() and 0xff); lit.write((t ushr 16).toInt() and 0xff)
        lit.write((t ushr 8).toInt() and 0xff); lit.write(t.toInt() and 0xff)
        lit.write(data)
        val literalPacket = oldFormatPacket(11, lit.toByteArray())

        // ---- random CFB prefix (iv), hashed by the MDC ----
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }

        // ---- MDC: SHA1(iv || iv[14..16] || literalPacket || D3 14) ----
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(iv)
        sha1.update(iv, 14, 2)
        sha1.update(literalPacket)
        sha1.update(byteArrayOf(0xD3.toByte(), 0x14))
        val mdc = ByteArrayOutputStream()
        mdc.write(0xD3); mdc.write(0x14)
        mdc.write(sha1.digest())
        val seipdPlain = concat(literalPacket, mdc.toByteArray())

        // ---- PKESK (old format, tag 1) ----
        val pkeskBody = buildPkeskBody(publicKey, ecdh, kdfHashId, kdfCipherId, recipientU, fingerprint, sessionKey)
        val pkesk = oldFormatPacket(1, pkeskBody)

        // ---- SEIPD v1 (new format, tag 18): version byte outside encryption ----
        val seipdBody = ocfbEncrypt(sessionKey, iv, seipdPlain)
        val seipdFull = ByteArrayOutputStream()
        seipdFull.write(0x01)
        seipdFull.write(seipdBody)
        val seipd = newFormatPacket(18, seipdFull.toByteArray())

        return concat(pkesk, seipd)
    }

    fun encryptToAddressKey(publicKeyArmored: String, data: ByteArray): ByteArray {
        val key = encryptionKeyFromArmored(publicKeyArmored)
        return encrypt(key, data)
    }

    /** Armors the raw PGP message bytes (-----BEGIN PGP MESSAGE-----). */
    fun armor(rawPgp: ByteArray): String {
        val armorOut = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(armorOut)
        armored.write(rawPgp)
        armored.close()
        return armorOut.toString("UTF-8")
    }

    /**
     * Builds the importable package as Proton's own stored sent message,
     * following go-proton-api's per-part encryption (tryEncrypt):
     *  - root headers are kept plaintext;
     *  - the text/html body part holds one armored PGP message (the encrypted
     *    body) - exactly what the current single-part flow produces;
     *  - every attachment part holds the base64 of the binary PGP encryption
     *    of the attachment bytes, with its original Content-Disposition.
     * The mail/v4/messages/import endpoint splits the decrypted parts: body
     * becomes the message body, attachment parts become real attachments.
     */
    fun buildImportPackage(mime: MimeMessage, publicKeyArmored: String): ByteArray {
        val key = encryptionKeyFromArmored(publicKeyArmored)

        val bodyBytes: ByteArray = extractHtmlBody(mime)
        val normalized = String(bodyBytes, Charsets.ISO_8859_1)
            .replace("\r\n", "\n").replace("\n", "\r\n")
            .toByteArray(Charsets.ISO_8859_1)
        val armoredBody = armor(encrypt(key, normalized))
        val attachments = extractAttachments(mime)

        val out = ByteArrayOutputStream()
        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        fun header(name: String): String? = mime.getHeader(name)?.firstOrNull()
        header("Date")?.let {
            // Proton's import server fails to parse the "(GMT+02:00)" zone-name
            // comment that JavaMail appends; a bare RFC 1123 date works.
            val clean = it.replace(Regex("\\s+\\([^)]*\\)$"), "")
            write("Date: " + clean + CRLF)
        }
        header("From")?.let { write("From: " + it + CRLF) }
        header("To")?.let { write("To: " + it + CRLF) }
        header("Cc")?.let { write("Cc: " + it + CRLF) }
        header("Subject")?.let { write("Subject: " + it + CRLF) }
        header("Message-Id")?.let { write("Message-Id: " + it + CRLF) }
        write("Mime-Version: 1.0" + CRLF)

        if (attachments.isEmpty()) {
            write("Content-Type: text/html; charset=utf-8" + CRLF)
            write(CRLF)
            write(armoredBody)
            return out.toByteArray()
        }

        val boundary = "----=_ext_" + System.currentTimeMillis()
        write("Content-Type: multipart/mixed; boundary=\"" + boundary + "\"" + CRLF)
        write(CRLF)

        // body part
        write("--" + boundary + CRLF)
        write("Content-Type: text/html; charset=utf-8" + CRLF)
        write(CRLF)
        write(armoredBody)
        write(CRLF)

        // attachment parts: base64 of the binary PGP encryption
        attachments.forEach { attachment ->
            val encrypted = encrypt(key, attachment.bytes)
            val b64 = java.util.Base64.getEncoder().encodeToString(encrypted)
            write(CRLF + "--" + boundary + CRLF)
            write("Content-Type: " + (attachment.mimeType ?: "application/octet-stream") +
                "; name=\"" + attachment.fileName + "\"" + CRLF)
            write("Content-Disposition: attachment; filename=\"" + attachment.fileName + "\"" + CRLF)
            write("Content-Transfer-Encoding: base64" + CRLF)
            write(CRLF)
            write(b64)
            write(CRLF)
        }
        write("--" + boundary + "--" + CRLF)
        return out.toByteArray()
    }

    private fun extractHtmlBody(mime: MimeMessage): ByteArray = try {
        val content = mime.content
        if (content is jakarta.mail.Multipart) {
            findHtml(content)
        } else {
            null
        } ?: mime.inputStream.readBytes()
    } catch (e: Exception) {
        ByteArrayOutputStream().also { out -> mime.writeTo(out) }.toByteArray()
    }

    private fun findHtml(mp: jakarta.mail.Multipart): ByteArray? {
        for (i in 0 until mp.count) {
            val part = mp.getBodyPart(i)
            if (part.isMimeType("multipart/*")) {
                findHtml(part.content as jakarta.mail.Multipart)?.let { return it }
            } else if (part.isMimeType("text/html")) {
                return part.inputStream.readBytes()
            }
        }
        return null
    }

    /** Collects attachment parts (non-text leaves of the MIME tree). */
    private fun extractAttachments(mime: MimeMessage): List<ImportAttachment> {
        val result = mutableListOf<ImportAttachment>()
        val content = runCatching { mime.content }.getOrNull()
        if (content is jakarta.mail.Multipart) {
            fun walk(mp: jakarta.mail.Multipart) {
                for (i in 0 until mp.count) {
                    val part = mp.getBodyPart(i)
                    if (part.isMimeType("multipart/*")) {
                        runCatching { walk(part.content as jakarta.mail.Multipart) }
                    } else if (!part.isMimeType("text/html") && !part.isMimeType("text/plain")) {
                        val bytes = runCatching { part.inputStream.readBytes() }.getOrNull()
                        if (bytes != null && bytes.isNotEmpty()) {
                            result += ImportAttachment(
                                fileName = part.fileName ?: "attachment.bin",
                                mimeType = part.contentType.substringBefore(";").trim().ifBlank { null },
                                bytes = bytes
                            )
                        }
                    }
                }
            }
            runCatching { walk(content) }
        }
        return result
    }

    private data class ImportAttachment(
        val fileName: String,
        val mimeType: String?,
        val bytes: ByteArray
    )

    // ------------------------------------------------------------------
    // Manual gopenpgp-compatible PGP encryption (go-crypto semantics)
    // ------------------------------------------------------------------

    private fun buildPkeskBody(
        pub: PGPPublicKey,
        ecdh: ECDHPublicBCPGKey,
        kdfHashId: Int,
        kdfCipherId: Int,
        recipientU: ByteArray,
        fingerprint: ByteArray,
        sessionKey: ByteArray
    ): ByteArray {
        // ephemeral X25519
        val ephPriv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ephPub = ByteArray(32)
        X25519.scalarMultBase(ephPriv, 0, ephPub, 0)
        val z = ByteArray(32)
        X25519.scalarMult(ephPriv, 0, recipientU, 0, z, 0)

        // Param = OID_len || OID || {18, 3, 1, hashId, cipherId} || "Anonymous Sender    " || fingerprint
        val param = ByteArrayOutputStream()
        param.write(CURVE_OID.size)
        param.write(CURVE_OID)
        param.write(byteArrayOf(0x12, 0x03, 0x01, kdfHashId.toByte(), kdfCipherId.toByte()))
        param.write(ANON_SENDER)
        param.write(fingerprint)

        // MB = Hash(0x00000001 || Z || Param); wrap key = first KeySize bytes
        val md = MessageDigest.getInstance(hashName(kdfHashId))
        md.update(byteArrayOf(0, 0, 0, 1))
        md.update(z)
        md.update(param.toByteArray())
        val mb = md.digest()
        val wrapKey = mb.copyOf(cipherKeySize(kdfCipherId))

        // m = symmAlgId(9=AES-256) || sessionKey || checksum || padding to 40
        val m = ByteArray(40)
        m[0] = 9
        System.arraycopy(sessionKey, 0, m, 1, 32)
        var sum = 0
        for (i in 0 until 32) sum = (sum + (sessionKey[i].toInt() and 0xff)) and 0xffff
        m[33] = (sum ushr 8).toByte()
        m[34] = (sum and 0xff).toByte()
        for (i in 35 until 40) m[i] = 5

        val wrap = AESWrapEngine()
        wrap.init(true, KeyParameter(wrapKey))
        val wrapped = wrap.wrap(m, 0, m.size)

        // body = version(3) || keyid(8) || algo(18) || MPI(vsG) || OID(wrapped)
        val body = ByteArrayOutputStream()
        body.write(3)
        val keyId = pub.keyID
        for (i in 7 downTo 0) body.write(((keyId ushr (i * 8)) and 0xff).toInt())
        body.write(18)
        val ephEncoded = ByteArray(33)
        ephEncoded[0] = 0x40
        System.arraycopy(ephPub, 0, ephEncoded, 1, 32)
        val bitLen = 8 * 32 + 7 // 0x40 -> bits.Len8 = 7 => 263
        body.write((bitLen ushr 8) and 0xff)
        body.write(bitLen and 0xff)
        body.write(ephEncoded)
        body.write(wrapped.size)
        body.write(wrapped)
        return body.toByteArray()
    }

    private fun hashName(id: Int): String = when (id) {
        8 -> "SHA-256"
        9 -> "SHA-384"
        10 -> "SHA-512"
        11 -> "SHA-224"
        12 -> "SHA3-256"
        14 -> "SHA3-512"
        else -> error("unsupported kdf hash id " + id)
    }

    private fun cipherKeySize(id: Int): Int = when (id) {
        7 -> 16
        8 -> 24
        9 -> 32
        else -> error("unsupported kdf cipher id " + id)
    }

    /**
     * go-crypto OCFB "no resync" variant (Proton fork, used for SEIPD with MDC):
     * prefix = (iv ^ E(0)) || (iv[14..16] ^ E(c0..15)); the feedback register
     * then continues from [c16, c17, E(c0..15)[2..15]] WITHOUT resetting.
     */
    private fun ocfbEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        val ks = SecretKeySpec(key, "AES")
        ecb.init(Cipher.ENCRYPT_MODE, ks)
        val block = 16
        val e0 = ecb.doFinal(ByteArray(block)) // E(0)
        val c0 = ByteArray(block) // c0..15 = iv ^ E(0)
        for (i in 0 until block) c0[i] = (iv[i].toInt() xor e0[i].toInt()).toByte()
        val e1 = ecb.doFinal(c0) // E(c0..15)
        val prefix = ByteArray(block + 2)
        System.arraycopy(c0, 0, prefix, 0, block)
        prefix[block] = (e1[0].toInt() xor iv[block - 2].toInt()).toByte()
        prefix[block + 1] = (e1[1].toInt() xor iv[block - 1].toInt()).toByte()

        val out = ByteArray(block + 2 + plain.size)
        System.arraycopy(prefix, 0, out, 0, block + 2)

        val fre = ByteArray(block)
        fre[0] = prefix[block] // c16
        fre[1] = prefix[block + 1] // c17
        System.arraycopy(e1, 2, fre, 2, block - 2) // e1[2..15]
        var outUsed = 2
        var freLocal = fre
        for (i in plain.indices) {
            if (outUsed == block) {
                freLocal = ecb.doFinal(freLocal)
                outUsed = 0
            }
            val c = (freLocal[outUsed].toInt() xor plain[i].toInt()).toByte()
            freLocal[outUsed] = c
            out[block + 2 + i] = c
            outUsed++
        }
        return out
    }

    /**
     * Old-format packet header: 0x80 | (tag << 2) | lengthType.
     * lengthType 1 = 2-byte length (<= 65535), lengthType 2 = 4-byte length.
     */
    private fun oldFormatPacket(tag: Int, body: ByteArray): ByteArray {
        val p = ByteArrayOutputStream()
        val len = body.size
        if (len <= 0xFFFF) {
            p.write(0x80 or (tag shl 2) or 1)
            p.write((len ushr 8) and 0xff)
            p.write(len and 0xff)
        } else {
            p.write(0x80 or (tag shl 2) or 2)
            p.write((len ushr 24) and 0xff)
            p.write((len ushr 16) and 0xff)
            p.write((len ushr 8) and 0xff)
            p.write(len and 0xff)
        }
        p.write(body)
        return p.toByteArray()
    }

    /**
     * New-format packet header: 0xC0 | tag, then RFC 4880 §4.2.3.4 length.
     *  - len < 192: one octet
     *  - 192 <= len <= 8383: two octets (192 + ((len-192)>>8), (len-192)&0xff)
     *  - larger: five octets (0xFF, then 4-byte big-endian)
     */
    private fun newFormatPacket(tag: Int, body: ByteArray): ByteArray {
        val p = ByteArrayOutputStream()
        p.write(0xC0 or tag)
        val len = body.size
        when {
            len < 192 -> p.write(len)
            len <= 8383 -> {
                p.write(192 + ((len - 192) ushr 8))
                p.write((len - 192) and 0xff)
            }
            else -> {
                p.write(0xFF)
                p.write((len ushr 24) and 0xff)
                p.write((len ushr 16) and 0xff)
                p.write((len ushr 8) and 0xff)
                p.write(len and 0xff)
            }
        }
        p.write(body)
        return p.toByteArray()
    }

    private fun recipientPoint(key: PGPPublicKey): ByteArray {
        val ecdh = key.publicKeyPacket.key as ECDHPublicBCPGKey
        val b: BigInteger = ecdh.encodedPoint
        val p = b.toByteArray()
        val out = ByteArray(32)
        var src = 0
        if (p.size > 32) src = p.size - 32
        System.arraycopy(p, src, out, 0, minOf(32, p.size))
        if (out[0].toInt() == 0x40) {
            val clean = ByteArray(32)
            System.arraycopy(out, 1, clean, 0, 31)
            return clean
        }
        return out
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }
}