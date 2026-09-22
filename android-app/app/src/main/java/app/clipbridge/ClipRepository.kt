package app.clipbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.PersistableBundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

enum class SendResult { SENT, ALREADY_SYNCED }
enum class KeyMatch { MATCHES_OTHER_DEVICE, NO_CLIPS_YET, DIFFERENT_FROM_OTHER_DEVICE }

/** No passphrase set on this device yet. */
class NoKeyException : Exception("Set your passphrase in ClipBridge first")

/** Already at PrunePlan.MAX_PINS pinned clips. */
class PinLimitException : Exception("You can pin up to ${PrunePlan.MAX_PINS} clips. Unpin one first.")

/** A file copied into our cache, ready to encrypt and upload. */
data class Staged(val file: File, val name: String, val mime: String, val hash: String)

/** One clip for the UI. [header] is null when this device can't decrypt it. */
data class ClipItem(
    val id: String, val createdTime: String, val mine: Boolean,
    val header: ClipHeader?, val size: Long, val pinned: Boolean = false,
)

class ClipRepository(
    private val ctx: Context,
    private val prefs: Prefs,
    val vault: KeyVault,
    private val drive: DriveApi,
) {
    private val _history = MutableStateFlow<List<ClipItem>>(emptyList())
    val history: StateFlow<List<ClipItem>> = _history

    /** First ~200 chars of text clips, by Drive id. Decrypted, in memory only. */
    val previews = ConcurrentHashMap<String, String>()
    private val headers = ConcurrentHashMap<String, Pair<ByteArray, ClipHeader>>()
    private val undecryptableNotified = ConcurrentHashMap.newKeySet<String>()

    private val pollLock = Mutex()
    private val sendLock = Mutex()
    @Volatile private var consentNotified = false

    private fun key(): ByteArray = vault.load() ?: throw NoKeyException()

    // ================================================================ auth + retry

    private suspend fun <T> withToken(block: (String) -> T): T {
        val email = prefs.email
        val token = Auth.token(ctx, email)
        return try {
            withContext(Dispatchers.IO) { block(token) }
        } catch (e: UnauthorizedException) {
            Auth.invalidate(ctx)
            val fresh = Auth.token(ctx, email)
            withContext(Dispatchers.IO) { block(fresh) }
        }
    }

    /** Retries only failures that can fix themselves (offline, busy). 3 tries: now, +2 s, +6 s. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var wait = 2_000L
        repeat(2) {
            try { return block() } catch (e: TransientException) {
                Log.w(TAG, "retrying after: ${e.message}")
                delay(wait); wait *= 3
            }
        }
        return block()
    }

    // ================================================================ passphrase

    /** Slow on purpose (PBKDF2, ~1 s): makes guessing passphrases expensive. */
    suspend fun deriveKey(passphrase: String): ByteArray = withContext(Dispatchers.Default) {
        val email = prefs.email ?: throw IllegalStateException("Sign in first")
        Crypto.deriveKey(passphrase, email)
    }

    /** Compares a candidate key with what the other device has been using. */
    suspend fun checkAgainstDrive(candidate: ByteArray): KeyMatch {
        val clips = withToken { t -> drive.list(t) }.filter { it.description != null }
        if (clips.isEmpty()) return KeyMatch.NO_CLIPS_YET
        val opens = clips.take(3).any { runCatching { Crypto.openHeader(candidate, it.description) }.isSuccess }
        return if (opens) KeyMatch.MATCHES_OTHER_DEVICE else KeyMatch.DIFFERENT_FROM_OTHER_DEVICE
    }

    fun saveKey(key: ByteArray) {
        vault.save(key)
        headers.clear(); previews.clear(); undecryptableNotified.clear()
        DebugLog.add("Passphrase saved, key check ${Crypto.keyCheck(key)}")
    }

    // ================================================================ pinning

    /** Toggles a clip's pinned state. Pinned clips are excluded from the "keep the newest 10" pruning. */
    suspend fun setPinned(item: ClipItem, pinned: Boolean) {
        if (pinned) {
            val current = withToken { t -> drive.list(t, pageSize = 100) }.count { it.pinned }
            if (current >= PrunePlan.MAX_PINS) throw PinLimitException()
        }
        withToken { t -> drive.setPinned(t, item.id, pinned) }
        DebugLog.add("Clip ${if (pinned) "pinned" else "unpinned"}")
        ActivityClock.touch()
        runCatching { refreshHistory(withPreviews = false) }
    }

    // ================================================================ sending

    suspend fun sendText(text: String): SendResult = sendLock.withLock {
        ActivityClock.touch()
        val hash = sha256Text(text)
        if (hash == prefs.lastHash) return@withLock SendResult.ALREADY_SYNCED
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > SMALL_UPLOAD) { // huge text: stream it like a file instead of holding copies in memory
            val f = File(stagingDir(), "${System.nanoTime()}.txt").apply { writeBytes(bytes) }
            return@withLock sendStagedLocked(Staged(f, "clip.txt", "text/plain", hash), kind = "text")
        }
        val key = key()
        val prefix = Crypto.newPrefix()
        val header = ClipHeader("clip.txt", "text/plain", "text", prefs.deviceLabel, bytes.size.toLong())
        val desc = Crypto.sealHeader(key, prefix, header)
        val cipher = Crypto.encryptBytes(key, prefix, bytes)
        withRetry { withToken { t -> drive.uploadSmall(t, cipher, desc, prefs.deviceId) } }
        prefs.lastHash = hash
        afterUpload()
        SendResult.SENT
    }

    suspend fun sendStaged(s: Staged): SendResult = sendLock.withLock { sendStagedLocked(s, kind = null) }

    private suspend fun sendStagedLocked(s: Staged, kind: String?): SendResult {
        ActivityClock.touch()
        try {
            if (s.hash == prefs.lastHash) return SendResult.ALREADY_SYNCED
            val key = key()
            val k = kind ?: if (s.mime.startsWith("image/")) "image" else "file"
            val prefix = Crypto.newPrefix()
            val desc = Crypto.sealHeader(key, prefix, ClipHeader(s.name, s.mime, k, prefs.deviceLabel, s.file.length()))
            withRetry { withToken { t -> drive.uploadEncryptedFile(t, s.file, key, prefix, desc, prefs.deviceId) } }
            prefs.lastHash = s.hash
            afterUpload()
            return SendResult.SENT
        } finally {
            s.file.delete()
        }
    }

    /** The upload already succeeded: pruning/refresh problems must not report it as failed. */
    private suspend fun afterUpload() {
        DebugLog.add("Sent a clip")
        ActivityClock.touch()
        try { withToken { t -> drive.prune(t) } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        try { refreshHistory(withPreviews = false) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    /** Copies a content:// Uri into our cache (hashing on the way). Call while the Uri is still readable. */
    suspend fun stage(uri: Uri): Staged = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        ensureSpace(stagingDir(), size)
        val mime = cr.getType(uri) ?: guessMime(name)
        val out = File(stagingDir(), "${System.nanoTime()}-${safeName(name)}")
        val input = cr.openInputStream(uri) ?: throw IOException("Can't read that item")
        try {
            val hash = input.use { copyHashing(it, out) }
            Staged(out, safeName(name), mime, hash)
        } catch (e: Exception) {
            out.delete(); throw e
        }
    }

    // ================================================================ receiving

    /** One polling round, every few seconds from [SyncService]. */
    suspend fun poll() = pollLock.withLock {
        val key = vault.load() ?: throw NoKeyException()
        val clips = try {
            withToken { t -> drive.list(t, pageSize = LIST_PAGE_SIZE) }
        } catch (e: NeedsConsentException) {
            if (!consentNotified) {
                consentNotified = true
                Notifier.alert(ctx, "Sign in to ClipBridge again", "Google needs you to confirm access. Tap to open the app.")
            }
            throw e
        }
        if (consentNotified) { consentNotified = false; Notifier.clearAlert(ctx) }
        _history.value = clips.take(MAX_VISIBLE).map { toItem(it, key) }
        if (clips.isNotEmpty()) runCatching { ActivityClock.touch(parseTime(clips.first().createdTime)) }

        val seen = prefs.seenIds
        if (seen == null) { prefs.seenIds = clips.map { it.id }; return@withLock } // first run: replay nothing
        val fresh = clips.filter { it.id !in seen }
        if (fresh.isEmpty()) return@withLock
        val allSeen = fresh.map { it.id } + seen

        val newest = clips.first()
        if (newest.origin == prefs.deviceId || fresh.none { it.id == newest.id }) {
            prefs.seenIds = allSeen; return@withLock
        }
        val opened = openCached(newest, key)
        if (opened == null) {
            prefs.seenIds = allSeen
            DebugLog.add("Couldn't unlock an incoming clip: passphrase mismatch")
            if (undecryptableNotified.add(newest.id)) Notifier.alert(ctx, "A clip couldn't be unlocked",
                "Your devices seem to use different passphrases. Compare the key check codes in Settings.")
            return@withLock
        }
        try {
            apply(newest.id, opened.first, opened.second, notify = true)
            prefs.seenIds = allSeen
            DebugLog.add("Received a clip from ${opened.second.device.ifBlank { "another device" }}")
            ActivityClock.touch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientException) {
            prefs.seenIds = allSeen - newest.id // offline mid-download: try this clip again next round
            DebugLog.add("Couldn't fetch an incoming clip yet: ${e.message}")
            throw e
        } catch (e: NeedsConsentException) {
            prefs.seenIds = allSeen - newest.id
            throw e
        } catch (e: PermanentException) {
            prefs.seenIds = allSeen
            if (e.code != 404) { DebugLog.add("Couldn't apply an incoming clip: ${e.message}"); throw e }
        } catch (e: Exception) {
            prefs.seenIds = allSeen // e.g. tampered content: never retry forever
            DebugLog.add("Rejected an incoming clip: ${e.message}")
            throw e
        }
    }

    suspend fun applyItem(item: ClipItem) {
        val key = key()
        val clip = withToken { t -> drive.list(t) }.firstOrNull { it.id == item.id }
            ?: throw PermanentException("That clip no longer exists", 404)
        val (prefix, header) = openCached(clip, key) ?: throw DecryptException("Can't unlock this clip")
        apply(clip.id, prefix, header, notify = false)
    }

    private suspend fun apply(id: String, prefix: ByteArray, h: ClipHeader, notify: Boolean) {
        val key = key()
        when {
            h.kind == "text" && h.size <= MAX_CLIPBOARD_TEXT -> {
                val text = String(Crypto.decryptBytes(key, prefix, withToken { t -> drive.downloadBytes(t, id) }), Charsets.UTF_8)
                setClipboard(ClipData.newPlainText("ClipBridge", text))
                prefs.lastHash = sha256Text(text)
                previews[id] = text.take(200)
                if (notify) Notifier.incoming(ctx, h.device, text.take(200), null, null)
            }
            h.kind == "text" -> { // too big for Android's clipboard (~1 MB limit): save as a file instead
                val uri = saveToDownloads(id, prefix, h.copy(name = "clip-${System.currentTimeMillis()}.txt"))
                if (notify) Notifier.incoming(ctx, h.device,
                    "Text too long for the clipboard. Saved to Downloads/ClipBridge. Tap to open.", uri, "text/plain")
            }
            h.kind == "image" -> {
                val dir = File(ctx.filesDir, "incoming").apply { mkdirs() }
                ensureSpace(dir, h.size)
                val dest = File(dir, "${System.currentTimeMillis()}-${safeName(h.name)}")
                val md = MessageDigest.getInstance("SHA-256")
                try {
                    withToken { t ->
                        drive.download(t, id) { input ->
                            DigestOutputStream(dest.outputStream(), md).use { Crypto.decrypt(key, prefix, input, it) }
                        }
                    }
                } catch (e: Exception) { dest.delete(); throw e }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", dest)
                setClipboard(ClipData.newUri(ctx.contentResolver, "ClipBridge image", uri))
                prefs.lastHash = hex(md.digest())
                trimIncoming(dir)
                if (notify) Notifier.incoming(ctx, h.device, "Image copied. Paste it, or tap to view.", uri, h.mime)
            }
            else -> {
                val uri = saveToDownloads(id, prefix, h)
                if (notify) Notifier.incoming(ctx, h.device, "${safeName(h.name)} saved to Downloads/ClipBridge. Tap to open.", uri, h.mime)
            }
        }
    }

    suspend fun refreshHistory(withPreviews: Boolean) {
        val key = vault.load() ?: return
        val clips = withToken { t -> drive.list(t, pageSize = LIST_PAGE_SIZE) }.take(MAX_VISIBLE)
        if (withPreviews) {
            for (c in clips) {
                val o = openCached(c, key) ?: continue
                if (o.second.kind == "text" && o.second.size <= 64_000 && !previews.containsKey(c.id)) {
                    try {
                        val bytes = withToken { t -> drive.downloadBytes(t, c.id) }
                        previews[c.id] = String(Crypto.decryptBytes(key, o.first, bytes), Charsets.UTF_8).take(200)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
            }
        }
        _history.value = clips.map { toItem(it, key) }
    }

    suspend fun fetchEmail(): String? = withToken { t -> drive.email(t) }

    suspend fun deleteAllClips() {
        withToken { t -> drive.deleteAll(t) }
        previews.clear(); headers.clear(); _history.value = emptyList()
    }

    /** Leftovers from an upload that was interrupted (app killed, phone rebooted). */
    fun cleanupStaging() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        stagingDir().listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    // ================================================================ helpers

    private fun toItem(c: DriveClip, key: ByteArray) =
        ClipItem(c.id, c.createdTime, c.origin == prefs.deviceId, openCached(c, key)?.second, c.size, c.pinned)

    private fun parseTime(iso: String): Long =
        runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private fun openCached(c: DriveClip, key: ByteArray): Pair<ByteArray, ClipHeader>? {
        headers[c.id]?.let { return it }
        return try {
            Crypto.openHeader(key, c.description).also { headers[c.id] = it }
        } catch (e: Exception) { null }
    }

    private suspend fun setClipboard(clip: ClipData) = withContext(Dispatchers.Main) {
        // Hides the content from Android 13+'s on-screen "copied" preview.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private suspend fun saveToDownloads(id: String, prefix: ByteArray, h: ClipHeader): Uri {
        val key = key()
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName(h.name))
            put(MediaStore.Downloads.MIME_TYPE, h.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipBridge")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = withContext(Dispatchers.IO) { cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
            ?: throw IOException("Couldn't create the file in Downloads")
        try {
            withToken { t ->
                val out: OutputStream = cr.openOutputStream(uri) ?: throw IOException("Couldn't open Downloads file")
                out.use { o -> drive.download(t, id) { input -> Crypto.decrypt(key, prefix, input, o) } }
            }
            withContext(Dispatchers.IO) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { runCatching { cr.delete(uri, null, null) } } // never leave half a file
            throw e
        }
    }

    private fun stagingDir() = File(ctx.cacheDir, "outgoing").apply { mkdirs() }

    private fun ensureSpace(dir: File, needed: Long) {
        if (needed > 0 && dir.usableSpace < needed + 50L * 1024 * 1024) throw PermanentException("Not enough storage on this phone", 507)
    }

    private fun trimIncoming(dir: File) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(DriveApi.MAX_CLIPS)?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "ClipBridge"
        private const val SMALL_UPLOAD = 4 * 1024 * 1024
        /** Android's clipboard goes through a ~1 MB binder buffer; stay well under it. */
        const val MAX_CLIPBOARD_TEXT = 256 * 1024L
        /** The newest 10 unpinned clips, plus up to 10 pinned ones that can be much older. */
        const val MAX_VISIBLE = DriveApi.MAX_CLIPS + PrunePlan.MAX_PINS
        private const val LIST_PAGE_SIZE = MAX_VISIBLE + 20 // headroom for a burst from another device

        fun sha256Text(text: String): String =
            hex(MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)))

        fun copyHashing(input: InputStream, dest: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            DigestInputStream(input, md).use { din -> dest.outputStream().use { din.copyTo(it) } }
            return hex(md.digest())
        }

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        fun safeName(name: String): String {
            val n = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120)
            return if (n.isBlank() || n.all { it == '.' }) "clip" else n // blocks ".", ".." tricks
        }

        private fun guessMime(name: String): String =
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
