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
        val req = Request.Builder().url("$BASE/files/$id?fields=id")
            .header(AUTH, "Bearer $token")
            .patch(body.toString().toRequestBody(JSON))
            .build()
        execute(http, req) { }
    }

    fun email(token: String): String? {
        val body = execute(quick, get("$BASE/about?fields=user(emailAddress)", token)) { it.body!!.string() }
        return JSONObject(body).optJSONObject("user")?.optString("emailAddress")?.ifBlank { null }
    }

    /** Already-encrypted small clip. Drive only sees a random name, a generic type, our device id, ciphertext. */
    fun uploadSmall(token: String, cipher: ByteArray, description: String, origin: String) {
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(meta(description, origin).toString().toRequestBody(JSON))
            .addPart(cipher.toRequestBody(OCTET))
            .build()
        val req = Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id")
            .header(AUTH, "Bearer $token").post(multipart).build()
        execute(http, req) { }
    }

    /** Streams [file] through encryption straight into Drive: constant memory for any size. */
    fun uploadEncryptedFile(token: String, file: File, key: ByteArray, prefix: ByteArray, description: String, origin: String) {
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
        val body = object : RequestBody() {
            override fun contentType(): MediaType = OCTET
            override fun contentLength(): Long = cipherSize
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { Crypto.encrypt(key, prefix, it, plainSize, sink.outputStream()) }
            }
        }
        execute(http, Request.Builder().url(session).put(body).build()) { }
    }

    /** Streams the (encrypted) content into [sink]. */
    fun download(token: String, id: String, sink: (InputStream) -> Unit) {
        execute(http, get("$BASE/files/$id?alt=media", token)) { resp -> resp.body!!.byteStream().use(sink) }
    }

    fun downloadBytes(token: String, id: String): ByteArray =
        execute(http, get("$BASE/files/$id?alt=media", token)) { it.body!!.bytes() }

    fun delete(token: String, id: String) {
        val req = Request.Builder().url("$BASE/files/$id").header(AUTH, "Bearer $token").delete().build()
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
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val AUTH = "Authorization"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
