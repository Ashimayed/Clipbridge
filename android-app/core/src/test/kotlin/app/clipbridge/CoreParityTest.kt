package app.clipbridge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Android side of the parity check. Every value in protocol/test-vectors.json was produced by the
 * Chrome extension's code (tests/gen-vectors.mjs); this must reproduce each one exactly, so the
 * two apps can never drift apart without the build failing.
 */
class CoreParityTest {
    private val v = JSONObject(File(System.getProperty("vectors") ?: "../../protocol/test-vectors.json").readText())

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = Names.hex(b)
    private fun pattern(n: Int) = ByteArray(n) { ((it * 31 + 7) and 255).toByte() }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test fun formatIsCB1() = assertEquals("CB1", v.getString("format"))

    @Test fun rfc7914Pbkdf2KnownAnswer() = assertEquals(
        "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
        hex(Crypto.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1)),
    )

    @Test fun passphraseKeysAndCheckCodes() {
        for (o in v.getJSONArray("pbkdf2").objects()) {
            val key = Crypto.deriveKey(o.getString("passphrase"), o.getString("email"), o.getInt("iterations"))
            assertEquals(o.getString("key"), hex(key), o.toString())
            assertEquals(o.getString("keyCheck"), Crypto.keyCheck(key))
        }
    }

    @Test fun contentEncryptionIsByteIdentical() {
        for (o in v.getJSONArray("content").objects()) {
            val size = o.getInt("plainSize")
            val ct = Crypto.encryptBytes(unhex(o.getString("key")), unhex(o.getString("prefix")), pattern(size))
            assertEquals(o.getLong("cipherLength"), ct.size.toLong(), "length for $size")
            assertEquals(o.getLong("cipherLength"), Crypto.cipherLength(size.toLong()))
            assertEquals(o.getString("cipherSha256"), sha(ct), "ciphertext for $size")
            if (o.has("cipher")) assertEquals(o.getString("cipher"), hex(ct))
            assertContentEquals(pattern(size), Crypto.decryptBytes(unhex(o.getString("key")), unhex(o.getString("prefix")), ct))
        }
    }

    @Test fun tamperedOrTruncatedContentIsRejected() {
        val o = v.getJSONArray("content").objects().first { it.has("cipher") && it.getInt("plainSize") > 0 }
        val key = unhex(o.getString("key")); val prefix = unhex(o.getString("prefix"))
        val flipped = unhex(o.getString("cipher")).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<DecryptException> { Crypto.decryptBytes(key, prefix, flipped) }
        val cut = unhex(o.getString("cipher")).let { it.copyOf(it.size - 1) }
        assertFailsWith<DecryptException> { Crypto.decryptBytes(key, prefix, cut) }
        assertFailsWith<DecryptException> { Crypto.decryptBytes(key, ByteArray(8), unhex(o.getString("cipher"))) }
    }

    @Test fun headersAreByteIdentical() {
        for (o in v.getJSONArray("headers").objects()) {
            val h = o.getJSONObject("header")
            val header = ClipHeader(h.getString("name"), h.getString("mime"), h.getString("kind"), h.getString("device"), h.getLong("size"))
            assertEquals(o.getString("json"), Json.obj("n" to header.name, "m" to header.mime, "k" to header.kind, "d" to header.device, "s" to header.size))
            assertEquals(o.getString("description"), Crypto.sealHeader(unhex(o.getString("key")), unhex(o.getString("prefix")), header))
            val (prefix, opened) = Crypto.openHeader(unhex(o.getString("key")), o.getString("description"))
            assertEquals(o.getString("prefix"), hex(prefix))
            assertEquals(header, opened)
        }
        assertFailsWith<DecryptException> { Crypto.openHeader(ByteArray(32), v.getJSONArray("headers").getJSONObject(0).getString("description")) }
    }

    @Test fun pairingCodesMatch() {
        for (o in v.getJSONArray("pairing").objects()) {
            assertEquals(o.getString("code"), Pairing.build(unhex(o.getString("key")), o.getString("email")))
            val r = Pairing.parse(o.getString("code")) as PairingResult.Ok
            assertEquals(o.getJSONObject("parsed").getString("email"), r.email)
            assertEquals(o.getJSONObject("parsed").getString("check"), r.check)
        }
        for (o in v.getJSONArray("pairingBad").objects()) {
            when (val r = Pairing.parse(o.getString("input"))) {
                is PairingResult.Ok -> { assertTrue(o.getBoolean("ok"), o.toString()); assertEquals(o.getString("email"), r.email) }
                is PairingResult.Bad -> { assertTrue(!o.getBoolean("ok"), o.toString()); assertEquals(o.getString("reason"), r.reason) }
            }
        }
    }

    @Test fun redactionMatches() {
        for (o in v.getJSONArray("redact").objects()) assertEquals(o.getString("output"), Redact.line(o.getString("input")))
        for (o in v.getJSONArray("redactMax").objects()) assertEquals(o.getString("output"), Redact.line(o.getString("input"), o.getInt("max")))
    }

    @Test fun pollPacingMatches() {
        for (o in v.getJSONArray("poll").objects()) {
            assertEquals(o.getLong("intervalMs"), PollPolicy.intervalMs(SyncMode.fromId(o.getString("mode")), o.getLong("idleMs")), o.toString())
        }
        for (o in v.getJSONArray("jitter").objects()) {
            assertEquals(o.getLong("output"), PollPolicy.jittered(o.getLong("ms"), o.getDouble("unit")), o.toString())
        }
    }

    @Test fun pruningMatches() {
        for (o in v.getJSONArray("prune").objects()) {
            val items = o.getJSONArray("items").objects().map { PruneItem(it.getString("id"), it.getString("createdTime"), it.getBoolean("pinned")) }
            val expected = o.getJSONArray("delete").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(expected, PrunePlan.idsToDelete(items, o.getInt("keep")))
        }
    }

    @Test fun namingRulesMatch() {
        for (o in v.getJSONArray("safeName").objects()) assertEquals(o.getString("output"), Names.safeName(o.getString("input")), o.toString())
        for (o in v.getJSONArray("deviceName").objects()) assertEquals(o.getString("output"), Names.cleanDeviceName(o.getString("input")), o.toString())
        for (o in v.getJSONArray("sha256Text").objects()) assertEquals(o.getString("output"), Names.sha256Text(o.getString("input")))
    }
}
