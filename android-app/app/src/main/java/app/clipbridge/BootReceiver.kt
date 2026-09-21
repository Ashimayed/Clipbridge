package app.clipbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts syncing after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.app.prefs
        if (prefs.syncEnabled && prefs.email != null) SyncService.start(context)
    }
}
