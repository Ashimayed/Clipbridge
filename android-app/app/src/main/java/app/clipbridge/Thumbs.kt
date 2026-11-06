package app.clipbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer

/**
 * Small previews of image clips for the history list (longest side 160 px, WebP).
 *
 * Bandwidth first: a preview is made ONCE per clip, from data this phone already has whenever it
 * can (an image it just sent, or one it just received), and only otherwise from a one-time
 * download of images up to 3 MB. Previews live in app-private, never-backed-up storage, and are
 * deleted with the clip, on sign-out, on a passphrase change and on "Delete all clips".
 */
class Thumbs(ctx: Context) {
    private val dir = File(ctx.noBackupFilesDir, "thumbs").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    private fun file(id: String) = File(dir, DriveApi.checkId(id) + ".webp")

    /** From memory or disk; null if there is none yet. Call off the main thread. */
    fun cached(id: String): Bitmap? {
        memory.get(id)?.let { return it }
        val f = runCatching { file(id) }.getOrNull() ?: return null
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { memory.put(id, it) }
    }

    fun peek(id: String): Bitmap? = memory.get(id)

    /** Makes and stores the preview of [id] from its decrypted image bytes or file. */
    fun store(id: String, source: ImageDecoder.Source): Bitmap? {
        val bmp = decodeSmall(source) ?: return null
        runCatching {
            val f = file(id)
            val tmp = File(dir, f.name + ".tmp")
            tmp.outputStream().use { bmp.compress(webp, 70, it) }
            tmp.renameTo(f)
        }
        memory.put(id, bmp)
        return bmp
    }

    fun store(id: String, imageFile: File) = store(id, ImageDecoder.createSource(imageFile))
    fun store(id: String, bytes: ByteArray) = store(id, ImageDecoder.createSource(ByteBuffer.wrap(bytes)))

    /** Forgets previews of clips that are gone. */
    fun keepOnly(ids: Collection<String>) {
        val keep = ids.mapNotNull { runCatching { file(it).name }.getOrNull() }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        memory.snapshot().keys.filter { it !in ids }.forEach { memory.remove(it) }
    }

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun decodeSmall(source: ImageDecoder.Source): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            // Refuse absurd sizes outright (a "decompression bomb" would exhaust memory).
            if (w <= 0 || h <= 0 || w.toLong() * h > 200_000_000L) throw IllegalArgumentException("image too large")
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
            decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // needed to compress it afterwards
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }.getOrNull()

    companion object {
        private const val MAX_SIDE = 160
        /** WEBP_LOSSY arrived in Android 11; on Android 10 plain WEBP is the same lossy format. */
        @Suppress("DEPRECATION")
        private val webp = if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        /** Images larger than this are never downloaded just to show a preview. */
        const val MAX_DOWNLOAD_FOR_PREVIEW = 3_000_000L
    }
}
