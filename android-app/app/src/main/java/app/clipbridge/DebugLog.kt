package app.clipbridge

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A short in-memory log of what ClipBridge has been doing, for the "Copy debug log" button.
 * Every line goes through [Redact.line] before it is stored, so nothing that ends up here can
 * contain your passphrase, your clips, or an access token — safe to paste into an email or a
 * GitHub issue. It resets when the app process restarts; it isn't written to disk.
 */
object DebugLog {
    private const val MAX_LINES = 200
    private val lines = ConcurrentLinkedDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(line: String) {
        lines.addLast("${fmt.format(System.currentTimeMillis())}  ${Redact.line(line)}")
        while (lines.size > MAX_LINES) lines.pollFirst()
    }

    fun dump(prefs: Prefs): String {
        val header = listOf(
            "ClipBridge debug log",
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "Sync mode: ${prefs.syncMode.id}, background sync: ${if (prefs.syncEnabled) "on" else "off"}",
            "----------",
        )
        val body = if (lines.isEmpty()) listOf("(nothing logged yet this session)") else lines.toList()
        return (header + body).joinToString("\n")
    }
}
