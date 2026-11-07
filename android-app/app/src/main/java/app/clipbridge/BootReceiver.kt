package app.clipbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts syncing after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Only the system's own broadcasts count; anything else is ignored.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.app.prefs
        if (prefs.syncEnabled && prefs.email != null) SyncService.start(context)
    }
}
