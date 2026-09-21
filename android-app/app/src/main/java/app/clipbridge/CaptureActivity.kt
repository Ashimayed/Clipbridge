package app.clipbridge

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android 10+ only lets the app that has window focus read the clipboard.
 * This transparent activity grabs focus for a split second, reads the clipboard, and closes.
 *
 * SECURITY: this class is NOT exported, so other apps can't make it read your clipboard.
 * Only our own tile and notification (PendingIntents that run as us) can open it.
 * Other apps reach [ShareActivity] instead, which only accepts content they hand over.
 */
open class CaptureActivity : ComponentActivity() {
    /** true only in [ShareActivity]. */
    protected open val isShareEntry = false

    private var handled = false
    private val safety = Handler(Looper.getMainLooper())

    /** If focus never arrives, never leave an invisible window blocking the screen. */
    private val giveUp = Runnable { if (!handled && !isFinishing) { toast("Couldn't read the clipboard. Try again."); finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.email == null || !app.repo.vault.hasKey) {
            toast("Open ClipBridge to finish setting up")
            finish(); return
        }
        val i = intent
        val isShare = i?.action == Intent.ACTION_SEND || i?.action == Intent.ACTION_SEND_MULTIPLE
        if (isShareEntry != isShare) { finish(); return } // each entry point does exactly one job
        if (isShare) {
            handled = true
            handleShare(i!!)
            return
        }
        // Otherwise wait for onWindowFocusChanged: reading earlier returns null on Android 10+.
        safety.postDelayed(giveUp, 4_000)
    }

    override fun onDestroy() {
        safety.removeCallbacks(giveUp)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled && !isFinishing) {
            handled = true
            captureClipboard()
        }
    }

    private fun captureClipboard() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("Clipboard is empty"); finish(); return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            sendUris(listOf(uri))
        } else {
            val text = item.coerceToText(this)?.toString()
            if (text.isNullOrEmpty()) { toast("Nothing to send"); finish() } else sendText(text)
        }
    }

    private fun handleShare(i: Intent) {
        val uris: List<Uri> = if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        }
        when {
            uris.isNotEmpty() -> sendUris(uris)
            !i.getStringExtra(Intent.EXTRA_TEXT).isNullOrEmpty() -> sendText(i.getStringExtra(Intent.EXTRA_TEXT)!!)
            else -> { toast("Nothing to send"); finish() }
        }
    }

    private fun sendText(text: String) {
        toast("Sending…")
        val ca = this.app
        ca.scope.launch { report(ca, runCatching { ca.repo.sendText(text) }) }
        finish()
    }

    /** Uri read access is tied to this Activity, so copy the bytes BEFORE finishing. */
    private fun sendUris(uris: List<Uri>) {
        toast(if (uris.size == 1) "Sending…" else "Sending ${uris.size} items…")
        val ca = this.app
        lifecycleScope.launch {
            val staged = uris.mapNotNull { u -> runCatching { ca.repo.stage(u) }.getOrNull() }
            if (staged.isEmpty()) {
                toast("Couldn't read that item")
            } else {
                ca.scope.launch {
                    for (s in staged) report(ca, runCatching { ca.repo.sendStaged(s) })
                }
            }
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    companion object {
        suspend fun report(app: ClipApp, r: Result<SendResult>) = withContext(Dispatchers.Main) {
            val msg = r.fold(
                onSuccess = { if (it == SendResult.SENT) "Sent to your other devices" else "Already on your other devices" },
                onFailure = { e ->
                    if (e is NoKeyException) {
                        "Open ClipBridge and set your passphrase"
                    } else if (e is NeedsConsentException) {
                        Notifier.alert(app, "Sign in to ClipBridge again", "Tap to open the app.")
                        "Sign in again in ClipBridge"
                    } else {
                        Notifier.alert(app, "Clip not sent", e.message ?: "Check your internet connection")
                        "Couldn't send: ${e.message ?: "no connection"}"
                    }
                }
            )
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Exported entry point for Android's Share menu. It can never read the clipboard. */
class ShareActivity : CaptureActivity() {
    override val isShareEntry = true
}
