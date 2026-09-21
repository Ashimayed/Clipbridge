package app.clipbridge

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls Drive every few seconds while the screen is on.
 * There is no server, so polling is the only way to learn about new clips.
 * Polling stops while the screen is off (saves battery; you can't paste then anyway).
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val screenOn = AtomicBoolean(true)
    /** Set when the network comes back, so we poll right away instead of finishing a long backoff. */
    private val wake = AtomicBoolean(false)

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wake.set(true) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> { screenOn.set(true); wake.set(true) }
                Intent.ACTION_SCREEN_OFF -> screenOn.set(false)
            }
        }
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_STATUS, Notifier.status(this), type)
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        screenOn.set(getSystemService(PowerManager::class.java).isInteractive)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground() // every startForegroundService() call must be answered, even if already running
        if (intent?.action == ACTION_STOP || !app.prefs.syncEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    private suspend fun runLoop() {
        var backoff = POLL_MS
        var keyNotified = false
        while (scope.isActive) {
            if (screenOn.get()) {
                try {
                    app.repo.poll()
                    backoff = POLL_MS
                    keyNotified = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NoKeyException) {
                    if (!keyNotified) {
                        keyNotified = true
                        Notifier.alert(this, "Finish setting up ClipBridge", "Set your passphrase to start syncing.")
                    }
                    backoff = 30_000L
                } catch (e: NeedsConsentException) {
                    backoff = 60_000L       // waits for the user to sign in again in the app
                } catch (e: Exception) {
                    Log.w(TAG, "poll failed: ${e.message}")
                    backoff = (backoff * 2).coerceAtMost(60_000L) // offline or Drive busy
                }
            }
            sleep(if (screenOn.get()) backoff else IDLE_MS)
        }
    }

    /** Sleeps in small steps so a returning network or screen can cut the wait short. */
    private suspend fun sleep(ms: Long) {
        var left = ms
        while (left > 0 && scope.isActive) {
            if (wake.getAndSet(false)) return
            val step = minOf(left, 500L)
            delay(step); left -= step
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClipBridge"
        private const val POLL_MS = 4_000L
        private const val IDLE_MS = 2_000L
        private const val ACTION_STOP = "app.clipbridge.STOP"

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java))
            }.onFailure { Log.w(TAG, "Could not start sync: ${it.message}") }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SyncService::class.java)) }
        }
    }
}
