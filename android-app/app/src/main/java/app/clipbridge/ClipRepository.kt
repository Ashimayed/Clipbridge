package app.clipbridge

import android.graphics.Bitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** A file copied into our cache, ready to encrypt and upload. [hash] is null when not needed. */
data class Staged(val file: File, val name: String, val mime: String, val hash: String?)

/** An upload big enough (5 MB+) to show progress for. */
data class Transfer(val key: Long, val name: String, val sent: Long, val total: Long) {
    val percent: Int get() = if (total <= 0) 0 else ((sent * 100) / total).toInt().coerceIn(0, 100)
}

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

    /** Clips swiped away, waiting out their few seconds of "Undo" before they are deleted. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val pendingDeletes = ConcurrentHashMap<String, Job>()

    /** What the list shows: [history] minus clips that are about to be deleted. */
    val visibleHistory: Flow<List<ClipItem>> = combine(_history, hidden) { list, gone -> list.filter { it.id !in gone } }

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers

    /** One-off messages for whichever screen is open ("Couldn't delete: ..."). */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    val thumbs = Thumbs(ctx)
    private val thumbLock = Mutex()

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
        headers.clear(); previews.clear(); undecryptableNotified.clear(); thumbs.clear()
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

    /**
     * Sends text. [force] = an explicit choice (typed in the app, shared from another app): it is
     * sent even if it matches the last clip. Without it (clipboard capture), a repeat is skipped.
     */
    suspend fun sendText(text: String, force: Boolean = false): SendResult = sendLock.withLock {
        ActivityClock.touch()
        val hash = Names.sha256Text(text)
        if (!force && hash == prefs.lastHash) return@withLock SendResult.ALREADY_SYNCED
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > SMALL_UPLOAD) { // huge text: stream it like a file instead of holding copies in memory
            val f = File(stagingDir(), "${System.nanoTime()}.txt").apply { writeBytes(bytes) }
            return@withLock sendStagedLocked(Staged(f, "clip.txt", "text/plain", hash), kind = "text", force = true)
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

    suspend fun sendStaged(s: Staged, force: Boolean = false): SendResult =
        sendLock.withLock { sendStagedLocked(s, kind = null, force = force) }

    private suspend fun sendStagedLocked(s: Staged, kind: String?, force: Boolean): SendResult {
        ActivityClock.touch()
        val size = s.file.length()
        val transfer = if (size >= PROGRESS_MIN) Transfer(System.nanoTime(), s.name, 0, Crypto.cipherLength(size)) else null
        try {
            if (!force && s.hash != null && s.hash == prefs.lastHash) return SendResult.ALREADY_SYNCED
            val key = key()
            val k = kind ?: if (s.mime.startsWith("image/")) "image" else "file"
            val prefix = Crypto.newPrefix()
            val desc = Crypto.sealHeader(key, prefix, ClipHeader(Names.safeName(s.name), s.mime, k, prefs.deviceLabel, size))
            transfer?.let { t -> _transfers.update { it + t }; Notifier.progress(ctx, t) }
            var lastNotified = 0L
            val progress = transfer?.let { t ->
                Progress { sent, total ->
                    val now = System.currentTimeMillis()
                    _transfers.update { list -> list.map { if (it.key == t.key) it.copy(sent = sent, total = total) else it } }
                    if (now - lastNotified >= 1_000) { lastNotified = now; Notifier.progress(ctx, t.copy(sent = sent, total = total)) }
                }
            }
            val id = withRetry { withToken { t -> drive.uploadEncryptedFile(t, s.file, key, prefix, desc, prefs.deviceId, progress) } }
            if (s.hash != null) prefs.lastHash = s.hash
            // Our own image: make its preview now, so nobody downloads it again just to show it.
            if (id != null && k == "image" && size <= 30_000_000L) runCatching { thumbs.store(id, s.file) }
            afterUpload()
            return SendResult.SENT
        } finally {
            transfer?.let { t -> _transfers.update { list -> list.filterNot { it.key == t.key } }; Notifier.cancelProgress(ctx, t) }
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

    /**
     * Copies a content:// Uri into our cache. Call while the Uri is still readable. [hash] only
     * matters for clipboard captures (to skip re-sending the same thing), so it is skipped otherwise.
     */
    suspend fun stage(uri: Uri, hash: Boolean = true): Staged = withContext(Dispatchers.IO) {
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
        val out = File(stagingDir(), "${System.nanoTime()}-${Names.safeName(name)}")
        val input = cr.openInputStream(uri) ?: throw IOException("Can't read that item")
        try {
            val digest = input.use { if (hash) copyHashing(it, out) else { out.outputStream().use { o -> it.copyTo(o) }; null } }
            Staged(out, Names.safeName(name), mime, digest)
        } catch (e: Exception) {
            out.delete(); throw e
        }
    }

    // ================================================================ deleting

    /**
     * Hides a clip at once and deletes it from Drive (for every device) after [UNDO_MS], unless
     * [undoDelete] is called first. Runs in [scope] so it completes even if the screen closes.
     */
    fun deleteSoon(id: String, scope: CoroutineScope) {
        DriveApi.checkId(id)
        hidden.update { it + id }
        pendingDeletes.remove(id)?.cancel()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                delay(UNDO_MS)
                withToken { t -> drive.delete(t, id) }
                DebugLog.add("Deleted a clip")
                ActivityClock.touch()
                previews.remove(id); headers.remove(id)
                _history.update { list -> list.filterNot { it.id == id } }
                thumbs.keepOnly(_history.value.map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit("Couldn't delete: ${e.message ?: "no connection"}")
            } finally {
                hidden.update { it - id }
            }
        }
        pendingDeletes[id] = job
        job.invokeOnCompletion { pendingDeletes.remove(id, job) }
        job.start()
    }

    /** true if the clip was still waiting and is now back. */
    fun undoDelete(id: String): Boolean {
        val job = pendingDeletes.remove(id) ?: return false
        job.cancel()
        hidden.update { it - id }
        return true
    }

    // ================================================================ previews

    /** The preview of an image clip, made once and cached; null for anything else. */
    suspend fun thumbnail(item: ClipItem): Bitmap? = withContext(Dispatchers.IO) {
        val h = item.header ?: return@withContext null
        if (h.kind != "image") return@withContext null
        thumbs.cached(item.id)?.let { return@withContext it }
        if (h.size > Thumbs.MAX_DOWNLOAD_FOR_PREVIEW) return@withContext null
        thumbLock.withLock {
            thumbs.cached(item.id)?.let { return@withLock it }
            val key = vault.load() ?: return@withLock null
            val (prefix, _) = headers[item.id] ?: return@withLock null
            val bytes = try {
                Crypto.decryptBytes(key, prefix, withToken { t -> drive.downloadBytes(t, item.id) })
            } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withLock null }
            thumbs.store(item.id, bytes)
        }
    }

    // ================================================================ receiving

    /**
     * One polling round, every few seconds from [SyncService].
     *
     * Everything new from another device is handled, oldest first: every file is saved, so
     * sending five files delivers five files. Only the NEWEST text or image goes on the
     * clipboard (each would overwrite the one before); older images are saved to
     * Downloads/ClipBridge instead, and older texts simply stay in the history.
     */
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

        val incoming = fresh.asReversed().filter { it.origin != prefs.deviceId }.mapNotNull { c ->
            val opened = openCached(c, key)
            if (opened == null) {
                DebugLog.add("Couldn't unlock an incoming clip: passphrase mismatch")
                if (undecryptableNotified.add(c.id)) Notifier.alert(ctx, "A clip couldn't be unlocked",
                    "Your devices seem to use different passphrases. Compare the key check codes in Settings.")
            }
            opened?.let { Triple(c, it.first, it.second) }
        }
        val forClipboard = incoming.lastOrNull { (_, _, h) -> goesOnClipboard(h) }?.first?.id

        val retry = HashSet<String>()
        var failure: Exception? = null
        for ((c, prefix, h) in incoming) {
            if (h.kind == "text" && goesOnClipboard(h) && c.id != forClipboard) continue // a newer text replaced it
            try {
                apply(c.id, prefix, h, notify = true, toClipboard = c.id == forClipboard)
                DebugLog.add("Received a ${h.kind} from ${h.device.ifBlank { "another device" }}")
                ActivityClock.touch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransientException) {
                retry += c.id // offline mid-download: try this clip again next round
                DebugLog.add("Couldn't fetch an incoming clip yet: ${e.message}")
                if (failure == null) failure = e
            } catch (e: NeedsConsentException) {
                retry += c.id
                if (failure == null) failure = e
                break
            } catch (e: PermanentException) {
                if (e.code != 404) { DebugLog.add("Couldn't apply an incoming clip: ${e.message}"); if (failure == null) failure = e }
            } catch (e: Exception) {
                DebugLog.add("Rejected an incoming clip: ${e.message}") // e.g. tampered content: never retry forever
                if (failure == null) failure = e
            }
        }
        prefs.seenIds = (fresh.map { it.id } + seen).filter { it !in retry }
        failure?.let { throw it }
    }

    private fun goesOnClipboard(h: ClipHeader) =
        (h.kind == "text" && h.size <= MAX_CLIPBOARD_TEXT) || h.kind == "image"

    suspend fun applyItem(item: ClipItem) {
        val key = key()
        val clip = withToken { t -> drive.list(t) }.firstOrNull { it.id == item.id }
            ?: throw PermanentException("That clip no longer exists", 404)
        val (prefix, header) = openCached(clip, key) ?: throw DecryptException("Can't unlock this clip")
        apply(clip.id, prefix, header, notify = false, toClipboard = true)
    }

    private suspend fun apply(id: String, prefix: ByteArray, h: ClipHeader, notify: Boolean, toClipboard: Boolean) {
        val key = key()
        when {
            h.kind == "text" && h.size <= MAX_CLIPBOARD_TEXT -> {
                val text = String(Crypto.decryptBytes(key, prefix, withToken { t -> drive.downloadBytes(t, id) }), Charsets.UTF_8)
                setClipboard(ClipData.newPlainText("ClipBridge", text))
                prefs.lastHash = Names.sha256Text(text)
                previews[id] = text.take(200)
                if (notify) Notifier.incoming(ctx, id, h.device, text.take(200), null, null, isText = true)
            }
            h.kind == "text" -> { // too big for Android's clipboard (~1 MB limit): save as a file instead
                val uri = saveToDownloads(id, prefix, h.copy(name = "clip-${System.currentTimeMillis()}.txt"))
                if (notify) Notifier.incoming(ctx, id, h.device,
                    "Text too long for the clipboard. Saved to Downloads/ClipBridge. Tap to open.", uri, "text/plain")
            }
            h.kind == "image" && toClipboard -> {
                val dir = File(ctx.filesDir, "incoming").apply { mkdirs() }
                ensureSpace(dir, h.size)
                val dest = File(dir, "${System.currentTimeMillis()}-${Names.safeName(h.name)}")
                val md = MessageDigest.getInstance("SHA-256")
                try {
                    withToken { t ->
                        drive.download(t, id) { input ->
                            DigestOutputStream(dest.outputStream(), md).use { Crypto.decrypt(key, prefix, input, it) }
                        }
                    }
                } catch (e: Exception) { dest.delete(); throw e }
                runCatching { thumbs.store(id, dest) } // the list's preview, without downloading it again
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", dest)
                setClipboard(ClipData.newUri(ctx.contentResolver, "ClipBridge image", uri))
                prefs.lastHash = Names.hex(md.digest())
                trimIncoming(dir)
                if (notify) Notifier.incoming(ctx, id, h.device, "Image copied. Paste it, or tap to view.", uri, h.mime, isText = true)
            }
            else -> { // files, and images that arrived together with a newer one
                val uri = saveToDownloads(id, prefix, h)
                if (notify) Notifier.incoming(ctx, id, h.device, "${Names.safeName(h.name)} saved to Downloads/ClipBridge. Tap to open.", uri, h.mime)
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
        withContext(Dispatchers.IO) { runCatching { thumbs.keepOnly(clips.map { it.id }) } }
    }

    suspend fun fetchEmail(): String? = withToken { t -> drive.email(t) }

    suspend fun deleteAllClips() {
        withToken { t -> drive.deleteAll(t) }
        previews.clear(); headers.clear(); _history.value = emptyList()
        withContext(Dispatchers.IO) { thumbs.clear() }
    }

    /** Sign-out: forget every decrypted copy this phone holds (previews, received images, unsent files). */
    fun forgetLocalCopies() {
        pendingDeletes.values.forEach { it.cancel() }
        previews.clear(); headers.clear(); undecryptableNotified.clear(); _history.value = emptyList()
        thumbs.clear()
        File(ctx.filesDir, "incoming").listFiles()?.forEach { it.delete() }
        stagingDir().listFiles()?.forEach { it.delete() }
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
            put(MediaStore.Downloads.DISPLAY_NAME, Names.safeName(h.name))
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
        /** Uploads from this size on show a progress bar (in the app and as a notification). */
        const val PROGRESS_MIN = 5L * 1024 * 1024
        /** How long a swiped-away clip can still be brought back. */
        const val UNDO_MS = 4_000L
        /** Android's clipboard goes through a ~1 MB binder buffer; stay well under it. */
        const val MAX_CLIPBOARD_TEXT = 256 * 1024L
        /** The newest 10 unpinned clips, plus up to 10 pinned ones that can be much older. */
        const val MAX_VISIBLE = DriveApi.MAX_CLIPS + PrunePlan.MAX_PINS
        private const val LIST_PAGE_SIZE = MAX_VISIBLE + 20 // headroom for a burst from another device

        fun copyHashing(input: InputStream, dest: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            DigestInputStream(input, md).use { din -> dest.outputStream().use { din.copyTo(it) } }
            return Names.hex(md.digest())
        }

        private fun guessMime(name: String): String =
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
