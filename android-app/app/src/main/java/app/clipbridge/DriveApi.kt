package app.clipbridge

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A clip as Drive stores it. Everything meaningful is inside the encrypted [description]. */
data class DriveClip(
    val id: String,
    val size: Long,
    val createdTime: String,
    val origin: String,       // random device id (not personal data)
    val description: String?, // encrypted header
    val pinned: Boolean = false, // visible in plain appProperties: a bit, never the clip's content
)

/** 401: token expired or revoked. */
class UnauthorizedException : IOException("Google sign-in expired")
/** Worth retrying later: offline, timeouts, 429, 5xx, rate limits. */
class TransientException(msg: String) : IOException(msg)
/** Retrying won't help: Drive full, clip deleted, bad request. */
class PermanentException(msg: String, val code: Int) : IOException(msg)

/** Upload progress: [sent] of [total] ciphertext bytes. Called from the upload thread. */
fun interface Progress { fun update(sent: Long, total: Long) }

class DriveApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // big uploads stream for as long as they need
        .build()

    /** Polling must never hang the loop: the whole call is capped at 15 s. */
    private val quick = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    fun list(token: String, pageSize: Int = 20): List<DriveClip> {
        val url = "$BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("pageSize", pageSize.toString())
            .addQueryParameter("fields", "files(id,size,createdTime,description,appProperties)")
            .build()
        val body = execute(quick, get(url.toString(), token)) { it.body!!.string() }
        val arr = JSONObject(body).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val props = o.optJSONObject("appProperties")
            DriveClip(
                id = o.getString("id"),
                size = o.optString("size", "0").toLongOrNull() ?: 0L,
                createdTime = o.optString("createdTime", ""),
                origin = props?.optString("origin", "") ?: "",
                description = o.optString("description", "").ifBlank { null },
                pinned = props?.optString("pinned", "") == "1",
            )
        }
    }

    /**
     * Marks a clip pinned or not. A lightweight metadata PATCH: no re-upload of the (possibly
     * large, encrypted) content. The pin flag is visible in Drive as plain metadata — like the
     * clip's size and timestamp already are — but it never reveals the clip's actual content.
     */
    fun setPinned(token: String, id: String, pinned: Boolean) {
        val body = JSONObject().put(
            "appProperties",
            JSONObject().apply { if (pinned) put("pinned", "1") else put("pinned", JSONObject.NULL) }
        )
        val req = Request.Builder().url("$BASE/files/${checkId(id)}?fields=id")
            .header(AUTH, "Bearer $token")
            .patch(body.toString().toRequestBody(JSON))
            .build()
        execute(http, req) { }
    }

    fun email(token: String): String? {
        val body = execute(quick, get("$BASE/about?fields=user(emailAddress)", token)) { it.body!!.string() }
        return JSONObject(body).optJSONObject("user")?.optString("emailAddress")?.ifBlank { null }
    }

    /** Already-encrypted small clip. Drive only sees a random name, a generic type, our device id, ciphertext. Returns the new id. */
    fun uploadSmall(token: String, cipher: ByteArray, description: String, origin: String): String? {
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta(description, origin).toString().toRequestBody(JSON))
            .addPart(cipher.toRequestBody(OCTET))
            .build()
        val req = Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id")
            .header(AUTH, "Bearer $token").post(multipart).build()
        return execute(http, req) { idFrom(it) }
    }

    /**
     * Streams [file] through encryption straight into Drive: constant memory for any size.
     * If the connection drops, asks Drive how much arrived and sends only the rest (Google's
     * documented resumable-upload recipe), instead of starting the whole file again.
     * Returns the new clip's Drive id.
     */
    fun uploadEncryptedFile(
        token: String, file: File, key: ByteArray, prefix: ByteArray, description: String, origin: String,
        progress: Progress? = null,
    ): String? {
        val plainSize = file.length()
        val cipherSize = Crypto.cipherLength(plainSize)
        val start = Request.Builder()
            .url("$UPLOAD/files?uploadType=resumable&fields=id")
            .header(AUTH, "Bearer $token")
            .header("X-Upload-Content-Type", OCTET.toString())
            .header("X-Upload-Content-Length", cipherSize.toString())
            .post(meta(description, origin).toString().toRequestBody(JSON))
            .build()
        val session = execute(http, start) { it.header("Location") }
            ?: throw TransientException("Drive did not start the upload")

        var offset = 0L
        var attempt = 0
        while (true) {
            val from = offset
            val body = object : RequestBody() {
                override fun contentType(): MediaType = OCTET
                override fun contentLength(): Long = cipherSize - from
                override fun isOneShot() = true // we resume ourselves, from where Drive stopped
                override fun writeTo(sink: BufferedSink) {
                    val out = CountingStream(sink.outputStream(), from, cipherSize, progress)
                    Crypto.encryptFrom(key, prefix, plainSize, from, { p -> openAt(file, p) }, out)
                    out.flush()
                    if (file.length() != plainSize) throw IOException("File changed while sending")
                }
            }
            val put = Request.Builder().url(session).header(AUTH, "Bearer $token")
                .header("Content-Range", if (from < cipherSize) "bytes $from-${cipherSize - 1}/$cipherSize" else "bytes */$cipherSize")
                .put(body).build()
            try {
                return execute(http, put) { idFrom(it) }
            } catch (e: TransientException) {
                if (++attempt > 4) throw e
                Thread.sleep(2_000L shl (attempt - 1))
                // null: Drive forgot the session, so the caller starts the whole upload over.
                offset = uploadedSoFar(session, token, cipherSize) ?: throw e
            }
        }
    }

    /** Bytes Drive already has for a resumable upload (or the finished upload's size); null if unknown. */
    private fun uploadedSoFar(session: String, token: String, total: Long): Long? {
        val req = Request.Builder().url(session).header(AUTH, "Bearer $token")
            .header("Content-Range", "bytes */$total").put(ByteArray(0).toRequestBody(null)).build()
        val resp = try { quick.newCall(req).execute() } catch (e: IOException) { return null }
        resp.use {
            if (it.code == 200 || it.code == 201) return total
            if (it.code != 308) return null
            val range = it.header("Range") ?: return 0L
            return Regex("bytes=0-(\\d+)").find(range)?.groupValues?.get(1)?.toLongOrNull()?.plus(1) ?: 0L
        }
    }

    private fun openAt(file: File, plainOffset: Long): InputStream {
        val raf = java.io.RandomAccessFile(file, "r")
        raf.seek(plainOffset)
        return java.nio.channels.Channels.newInputStream(raf.channel).buffered(Crypto.CHUNK)
    }

    private fun idFrom(resp: Response): String? =
        runCatching { JSONObject(resp.body!!.string()).optString("id").ifBlank { null } }.getOrNull()

    /** Streams the (encrypted) content into [sink]. */
    fun download(token: String, id: String, sink: (InputStream) -> Unit) {
        execute(http, get("$BASE/files/${checkId(id)}?alt=media", token)) { resp -> resp.body!!.byteStream().use(sink) }
    }

    fun downloadBytes(token: String, id: String): ByteArray =
        execute(http, get("$BASE/files/${checkId(id)}?alt=media", token)) { it.body!!.bytes() }

    fun delete(token: String, id: String) {
        val req = Request.Builder().url("$BASE/files/${checkId(id)}").header(AUTH, "Bearer $token").delete().build()
        val resp = try { http.newCall(req).execute() } catch (e: IOException) {
            throw TransientException("No connection")
        }
        resp.use {
            if (it.code == 401) throw UnauthorizedException()
            if (!it.isSuccessful && it.code != 404) throw classify(it) // 404: other device already pruned it
        }
    }

    /** Keep only the newest [keep] UNPINNED clips so Drive never fills up. Pinned clips are never dropped here. */
    fun prune(token: String, keep: Int = MAX_CLIPS) {
        val items = list(token, pageSize = 100).map { PruneItem(it.id, it.createdTime, it.pinned) }
        PrunePlan.idsToDelete(items, keep).forEach { id -> runCatching { delete(token, id) } }
    }

    fun deleteAll(token: String) {
        repeat(20) { // bounded: never loops forever if Drive misbehaves
            val batch = list(token, pageSize = 100)
            if (batch.isEmpty()) return
            batch.forEach { delete(token, it.id) }
        }
    }

    private fun get(url: String, token: String) = Request.Builder().url(url).header(AUTH, "Bearer $token").build()

    private fun meta(description: String, origin: String) = JSONObject()
        .put("name", "cb-${System.currentTimeMillis()}.bin")
        .put("mimeType", OCTET.toString())
        .put("parents", JSONArray().put("appDataFolder"))
        .put("description", description)
        .put("appProperties", JSONObject().put("origin", origin).put("v", "1"))

    private inline fun <T> execute(client: OkHttpClient, req: Request, onOk: (Response) -> T): T {
        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw TransientException("No connection (${e.javaClass.simpleName})")
        }
        resp.use {
            if (it.code == 401) throw UnauthorizedException()
            if (!it.isSuccessful) throw classify(it)
            return onOk(it)
        }
    }

    private fun classify(resp: Response): IOException {
        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
        return when {
            body.contains("storageQuotaExceeded") -> PermanentException("Your Google Drive is full", resp.code)
            resp.code == 429 || resp.code >= 500 || body.contains("RateLimitExceeded") ->
                TransientException("Google Drive is busy (HTTP ${resp.code})")
            resp.code == 404 -> PermanentException("That clip no longer exists", 404)
            else -> PermanentException("Drive error ${resp.code}", resp.code)
        }
    }

    companion object {
        const val MAX_CLIPS = 10
        private val ID = Regex("[A-Za-z0-9_-]{10,200}")

        /** Drive ids are short base64url-like strings; anything else must never reach a URL. */
        fun checkId(id: String): String {
            if (!ID.matches(id)) throw PermanentException("Not a valid clip id", 400)
            return id
        }
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val AUTH = "Authorization"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}

/** Counts bytes on their way to Drive, for the progress bar. Reports at most ~4 times a second. */
private class CountingStream(
    private val out: java.io.OutputStream, private var sent: Long, private val total: Long, private val progress: Progress?,
) : java.io.FilterOutputStream(out) {
    private var lastReport = 0L
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        sent += len
        val now = System.currentTimeMillis()
        if (progress != null && (now - lastReport >= 250 || sent >= total)) { lastReport = now; progress.update(sent, total) }
    }
    override fun write(b: Int) { out.write(b); sent++ }
}
