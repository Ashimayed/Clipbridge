package app.clipbridge

import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val prefs get() = app.prefs
    private val repo get() = app.repo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Ui.constrainWidth(this, findViewById<LinearLayout>(R.id.column), 560)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.rowChangePass).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_CHANGE, true))
        }

        val receive = findViewById<Switch>(R.id.rowReceiveSwitch)
        receive.isChecked = prefs.syncEnabled
        receive.setOnCheckedChangeListener { _, on ->
            prefs.syncEnabled = on
            if (on) SyncService.start(this) else SyncService.stop(this)
        }
        findViewById<LinearLayout>(R.id.rowReceive).setOnClickListener { receive.toggle() }

        findViewById<LinearLayout>(R.id.rowBattery).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        findViewById<LinearLayout>(R.id.rowTile).setOnClickListener { addTile() }

        findViewById<LinearLayout>(R.id.rowDeleteAll).setOnClickListener {
            confirm("Delete all clips?", "They're removed from your Google Drive for every device. This can't be undone.", "Delete all") {
                lifecycleScope.launch {
                    val msg = try { repo.deleteAllClips(); "All clips deleted" } catch (e: Exception) { "Couldn't delete: ${e.message}" }
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<LinearLayout>(R.id.rowSignOut).setOnClickListener {
            confirm("Sign out?", "This phone stops syncing and forgets its passphrase. Your other devices aren't affected.", "Sign out") {
                SyncService.stop(this)
                Auth.forget()
                repo.vault.clear()
                prefs.clearAccount()
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.rowKeyValue).text = repo.vault.keyCheck ?: "Not set"
        findViewById<TextView>(R.id.rowEmailTitle).text = prefs.email ?: ""
        val allowed = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        findViewById<TextView>(R.id.rowBatterySub).text =
            if (allowed) "Allowed. Clips keep arriving" else "Not allowed yet. Tap to allow"
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, SendTileService::class.java), getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_notif), mainExecutor
            ) { }
        } else {
            Toast.makeText(this, "Swipe down twice, tap the pencil, and drag in Send clipboard.", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirm(title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
