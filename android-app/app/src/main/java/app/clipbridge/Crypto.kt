package app.clipbridge

import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Clip could not be decrypted: wrong passphrase, or someone tampered with it. */
class DecryptException(msg: String) : GeneralSecurityException(msg)

/** What is inside the encrypted header of every clip. Drive never sees any of it. */
data class ClipHeader(val name: String, val mime: String, val kind: String, val device: String, val size: Long)

/**
 * End-to-end encryption, format "CB1". MUST stay byte-identical to chrome-extension/crypto.js.
 *
 *  key     = PBKDF2-HMAC-SHA256(passphrase, SHA-256("ClipBridge/v1/salt/" + email), 310 000, 32 bytes)
 *  prefix  = 8 random bytes per clip
 *  header  = AES-256-GCM(key, iv = prefix || FFFFFFFF, aad = "CB1H", JSON) -> Drive "description"
 *  content = "CB1C" || prefix || chunk0 || chunk1 ...
 *  chunk i = AES-256-GCM(key, iv = prefix || BE32(i), aad = "CB1C" || BE32(i) || final, <=256 KiB)
 *
 * Chunking lets files of any size stream without loading them into memory, and the
 * index + final flag in each chunk's AAD make reordering or truncation detectable.
 */
object Crypto {
    const val ITERATIONS = 310_000
    const val CHUNK = 256 * 1024
    private const val TAG = 16
    private val MAGIC = "CB1C".toByteArray()
    private val HEADER_AAD = "CB1H".toByteArray()
    private val rng = SecureRandom()

    // ------------------------------------------------------------------ keys

    fun salt(email: String): ByteArray =
        sha256(("ClipBridge/v1/salt/" + email.trim().lowercase()).toByteArray(Charsets.UTF_8))

    /** Plain PBKDF2 over UTF-8 bytes, so it matches WebCrypto exactly on every Android version. */
    fun deriveKey(passphrase: String, email: String, iterations: Int = ITERATIONS): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val s = salt(email)
        mac.update(s)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        for (i in 1 until iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }

    /** Short code shown on both devices. Same code = same passphrase. */
    fun keyCheck(key: ByteArray): String {
        val h = sha256("ClipBridge/v1/check/".toByteArray() + key)
        val hex = h.take(4).joinToString("") { "%02X".format(it) }
        return hex.substring(0, 4) + "-" + hex.substring(4)
    }

    fun newPrefix(): ByteArray = ByteArray(8).also { rng.nextBytes(it) }

    fun cipherLength(plainSize: Long): Long {
        val chunks = if (plainSize == 0L) 1L else (plainSize + CHUNK - 1) / CHUNK
        return MAGIC.size + 8 + plainSize + chunks * TAG
    }

    // ------------------------------------------------------------------ header

    fun sealHeader(key: ByteArray, prefix: ByteArray, h: ClipHeader): String {
        val json = JSONObject().put("n", h.name).put("m", h.mime).put("k", h.kind)
            .put("d", h.device).put("s", h.size).toString().toByteArray(Charsets.UTF_8)
        val ct = gcm(Cipher.ENCRYPT_MODE, key, prefix + byteArrayOf(-1, -1, -1, -1), HEADER_AAD, json)
        return Base64.getEncoder().encodeToString(prefix + ct)
    }

    /** Returns the per-clip prefix and the header, or throws [DecryptException]. */
    fun openHeader(key: ByteArray, description: String?): Pair<ByteArray, ClipHeader> {
        if (description.isNullOrBlank()) throw DecryptException("Clip has no encrypted header")
        val raw = try { Base64.getDecoder().decode(description) } catch (e: IllegalArgumentException) {
            throw DecryptException("Malformed header")
        }
        if (raw.size < 8 + TAG) throw DecryptException("Malformed header")
        val prefix = raw.copyOfRange(0, 8)
        val plain = try {
            gcm(Cipher.DECRYPT_MODE, key, prefix + byteArrayOf(-1, -1, -1, -1), HEADER_AAD, raw.copyOfRange(8, raw.size))
        } catch (e: GeneralSecurityException) {
            throw DecryptException("Wrong passphrase, or the clip was altered")
        }
        val o = JSONObject(String(plain, Charsets.UTF_8))
        return prefix to ClipHeader(
            name = o.optString("n", "clip"), mime = o.optString("m", "application/octet-stream"),
            kind = o.optString("k", "file"), device = o.optString("d", ""), size = o.optLong("s", 0),
        )
    }

    // ------------------------------------------------------------------ content (streaming)

    fun encrypt(key: ByteArray, prefix: ByteArray, input: InputStream, plainSize: Long, out: OutputStream) {
        out.write(MAGIC); out.write(prefix)
        val chunks = if (plainSize == 0L) 1L else (plainSize + CHUNK - 1) / CHUNK
        val buf = ByteArray(CHUNK)
        var remaining = plainSize
        for (i in 0 until chunks) {
            val n = minOf(CHUNK.toLong(), remaining).toInt()
            readFully(input, buf, n)
            remaining -= n
            val last = i == chunks - 1
            out.write(gcm(Cipher.ENCRYPT_MODE, key, iv(prefix, i), chunkAad(i, last), buf, n))
        }
        if (input.read() != -1) throw IOException("File changed while sending")
    }

    fun decrypt(key: ByteArray, expectedPrefix: ByteArray, input: InputStream, out: OutputStream) {
        val head = ByteArray(12)
        try { readFully(input, head, 12) } catch (e: EOFException) { throw DecryptException("Clip is truncated") }
        if (!head.copyOfRange(0, 4).contentEquals(MAGIC)) throw DecryptException("Not a ClipBridge clip")
        if (!head.copyOfRange(4, 12).contentEquals(expectedPrefix)) throw DecryptException("Header and content don't match")
        val size = CHUNK + TAG
        var cur = ByteArray(size); var next = ByteArray(size)
        var curLen = readUpTo(input, cur, size)
        var i = 0L
        while (true) {
            if (curLen < TAG) throw DecryptException("Clip is truncated")
            val nextLen = if (curLen == size) readUpTo(input, next, size) else 0
            val last = nextLen == 0
            val plain = try {
                gcm(Cipher.DECRYPT_MODE, key, iv(expectedPrefix, i), chunkAad(i, last), cur, curLen)
            } catch (e: GeneralSecurityException) {
                throw DecryptException("Wrong passphrase, or the clip was altered")
            }
            out.write(plain)
            if (last) return
            val t = cur; cur = next; next = t; curLen = nextLen; i++
        }
    }

    fun encryptBytes(key: ByteArray, prefix: ByteArray, data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream(cipherLength(data.size.toLong()).toInt())
        encrypt(key, prefix, data.inputStream(), data.size.toLong(), bos)
        return bos.toByteArray()
    }

    fun decryptBytes(key: ByteArray, prefix: ByteArray, data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream(maxOf(0, data.size - 28))
        decrypt(key, prefix, data.inputStream(), bos)
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------ helpers

    private fun iv(prefix: ByteArray, i: Long) = prefix + be32(i)
    private fun chunkAad(i: Long, last: Boolean) = MAGIC + be32(i) + byteArrayOf(if (last) 1 else 0)
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray, len: Int = data.size): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, iv))
        c.updateAAD(aad)
        return c.doFinal(data, 0, len)
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("Unexpected end of data")
            off += r
        }
    }

    private fun readUpTo(input: InputStream, buf: ByteArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
