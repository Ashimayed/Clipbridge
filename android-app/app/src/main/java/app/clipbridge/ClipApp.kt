package app.clipbridge

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClipApp : Application() {
    /** Work that must outlive a short-lived Activity (e.g. an upload started from Share). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var prefs: Prefs
        private set
    lateinit var repo: ClipRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = ClipRepository(this, prefs, KeyVault(this), DriveApi())
        Notifier.createChannels(this)
        scope.launch { runCatching { repo.cleanupStaging() } }
    }
}

val Context.app: ClipApp get() = applicationContext as ClipApp

/** Every channel is LOW or MIN importance: no sound, no vibration, no pop-up. */
object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_INCOMING = "incoming"
    private const val CH_ALERTS = "alerts"
    private const val CH_TRANSFERS = "transfers"
    const val ID_STATUS = 1
    private const val ID_INCOMING = 2
    private const val ID_ALERT = 3
    private const val ID_FILE_BASE = 1000      // one notification per received file
    private const val ID_PROGRESS_BASE = 5000  // one per running upload

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, importance: Int, desc: String) =
            NotificationChannel(id, name, importance).apply {
                description = desc
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        nm.createNotificationChannels(
            listOf(
                ch(CH_STATUS, "Sync running", NotificationManager.IMPORTANCE_MIN,
                    "Required by Android while ClipBridge syncs in the background"),
                ch(CH_INCOMING, "Incoming clips", NotificationManager.IMPORTANCE_LOW,
                    "Silent notice that something from another device is ready to paste"),
                ch(CH_ALERTS, "Problems", NotificationManager.IMPORTANCE_LOW,
                    "Sign-in needed or a clip failed to send"),
                ch(CH_TRANSFERS, "Sending files", NotificationManager.IMPORTANCE_LOW,
                    "Progress of large files (5 MB and up) on their way to your other devices"),
            )
        )
    }

    private fun activityIntent(ctx: Context, cls: Class<*>, req: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, req, Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun status(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("ClipBridge is syncing")
            .setContentText("Copied something? Tap Send clipboard.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(activityIntent(ctx, MainActivity::class.java, 10))
            .addAction(0, "Send clipboard", activityIntent(ctx, CaptureActivity::class.java, 11))
            .build()

    /**
     * A silent notice that something arrived. [isText] = it went on the clipboard (one shared
     * notice that each new clip replaces); files each get their own, so every one can be opened.
     * On the lock screen only "New clip" shows, never the content.
     */
    fun incoming(ctx: Context, clipId: String, from: String, preview: String, openUri: Uri?, mime: String?, isText: Boolean = false) {
        val nid = if (isText) ID_INCOMING else ID_FILE_BASE + (clipId.hashCode() and 0x7fffffff) % 1000
        val tap = if (openUri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(openUri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, nid, view,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else activityIntent(ctx, MainActivity::class.java, 21)
        val title = if (from.isBlank()) "Clip ready" else "From $from"
        val public = NotificationCompat.Builder(ctx, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("New clip from ClipBridge")
            .build()
        post(ctx, nid,
            NotificationCompat.Builder(ctx, CH_INCOMING)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(preview)
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
                .setSilent(true)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setTimeoutAfter(if (isText) 5 * 60 * 1000L else 60 * 60 * 1000L)
                .setContentIntent(tap)
                .build())
    }

    /** Ongoing, silent progress for a big upload. Updated about once a second at most. */
    fun progress(ctx: Context, t: Transfer) {
        post(ctx, ID_PROGRESS_BASE + (t.key.hashCode() and 0x7fffffff) % 1000,
            NotificationCompat.Builder(ctx, CH_TRANSFERS)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Sending ${t.name}")
                .setContentText("${t.percent}%")
                .setProgress(100, t.percent, t.sent == 0L)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(activityIntent(ctx, MainActivity::class.java, 22))
                .build())
    }

    fun cancelProgress(ctx: Context, t: Transfer) =
        NotificationManagerCompat.from(ctx).cancel(ID_PROGRESS_BASE + (t.key.hashCode() and 0x7fffffff) % 1000)

    fun alert(ctx: Context, title: String, text: String) {
        post(ctx, ID_ALERT,
            NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(activityIntent(ctx, MainActivity::class.java, 30))
                .build())
    }

    fun clearAlert(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_ALERT)

    @SuppressLint("MissingPermission") // checked just below
    private fun post(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (nm.areNotificationsEnabled()) runCatching { nm.notify(id, n) }
    }
}
