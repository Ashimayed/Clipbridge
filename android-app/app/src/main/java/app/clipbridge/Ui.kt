package app.clipbridge

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Ui {
    /** On tablets, foldables and landscape phones, keep forms at a readable width, centred. */
    fun constrainWidth(activity: Activity, view: View, maxDp: Int) {
        val screen = activity.resources.configuration.screenWidthDp
        if (screen > maxDp + 48) {
            val lp = view.layoutParams
            lp.width = (maxDp * activity.resources.displayMetrics.density).toInt()
            view.layoutParams = lp
        }
    }

    fun dp(activity: Activity, v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    /**
     * Edge to edge on every Android version (Android 15 enforces it for apps targeting it; doing
     * it everywhere keeps one look from Android 10 up). Bar icons follow light/dark mode.
     * Each screen then pads its content with [padForSystemBars].
     */
    fun edgeToEdge(activity: ComponentActivity) = activity.enableEdgeToEdge()

    /**
     * Pads [view] by the system bars and display cutout on the chosen sides, on top of its own
     * padding from the layout. [bottom] also counts the on-screen keyboard, so input fields and
     * buttons there stay visible while typing.
     */
    fun padForSystemBars(view: View, top: Boolean = true, bottom: Boolean = true, sides: Boolean = true, onApplied: ((bottomInset: Int) -> Unit)? = null) {
        val start = view.paddingLeft; val t = view.paddingTop; val end = view.paddingRight; val b = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            v.setPadding(
                start + if (sides) bars.left else 0,
                t + if (top) bars.top else 0,
                end + if (sides) bars.right else 0,
                b + if (bottom) bottomInset else 0,
            )
            onApplied?.invoke(bottomInset)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

    /** "14:05" for today; "3 Nov, 14:05" for older clips (pinned ones can be weeks old). */
    fun time(iso: String): String = runCatching {
        val at = Instant.parse(iso)
        val today = LocalDate.now(ZoneId.systemDefault())
        val day = at.atZone(ZoneId.systemDefault()).toLocalDate()
        if (day == today) timeFmt.format(at) else "${dayFmt.format(at)}, ${timeFmt.format(at)}"
    }.getOrDefault("")

    /** "PDF", "ZIP", "DOCX": what a file is at a glance; "" if it has no short extension. */
    fun extension(name: String): String {
        val m = Regex("\\.([A-Za-z0-9]{1,5})$").find(name) ?: return ""
        return m.groupValues[1].uppercase()
    }
}
