package app.clipbridge

import android.app.Activity
import android.view.View
import java.time.Instant
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

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    fun time(iso: String): String = runCatching { timeFmt.format(Instant.parse(iso)) }.getOrDefault("")
}
