package app.clipbridge

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Resuming an upload must send exactly the bytes a full upload would have sent from that point. */
class ResumeTest {
    @Test fun encryptFromMatchesEveryTailOfTheFullCiphertext() {
        val key = ByteArray(32) { it.toByte() }
        val prefix = ByteArray(8) { (0xA0 + it).toByte() }
        val step = Crypto.CHUNK + 16L
        for (size in listOf(0, 5, Crypto.CHUNK, Crypto.CHUNK + 3, 2 * Crypto.CHUNK + 777)) {
            val plain = ByteArray(size) { ((it * 31 + 7) and 255).toByte() }
            val full = Crypto.encryptBytes(key, prefix, plain)
            val offsets = listOf(0L, 1L, 11L, 12L, 13L, 12 + step - 1, 12 + step, 12 + step + 5, full.size - 1L, full.size.toLong())
            for (off in offsets.filter { it in 0..full.size }) {
                val out = ByteArrayOutputStream()
                Crypto.encryptFrom(key, prefix, size.toLong(), off, { p -> plain.inputStream().also { it.skip(p) } }, out)
                assertContentEquals(full.copyOfRange(off.toInt(), full.size), out.toByteArray(), "size $size offset $off")
            }
        }
    }
}
