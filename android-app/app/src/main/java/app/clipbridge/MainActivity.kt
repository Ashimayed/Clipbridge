package app.clipbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** Home: the bridge card, recent clips, and the big Send clipboard button. */
class MainActivity : ComponentActivity() {
    private val prefs get() = app.prefs
    private val repo get() = app.repo

    private lateinit var bridgeStatus: TextView
    private lateinit var bridgeDot: View
    private lateinit var bridgeEmail: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var emptyText: TextView
    private var keyCheckValue: TextView? = null  // two-pane layout only
    private var receiveSwitch: Switch? = null     // two-pane layout only

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ready() = prefs.email != null && repo.vault.hasKey

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ready()) { openSetup(); return }
        setContentView(R.layout.activity_main)
        bridgeStatus = findViewById(R.id.bridgeStatus)
        bridgeDot = findViewById(R.id.bridgeDot)
        bridgeEmail = findViewById(R.id.bridgeEmail)
        historyList = findViewById(R.id.historyList)
        emptyText = findViewById(R.id.emptyText)
        keyCheckValue = findViewById(R.id.keyCheckValue)
        receiveSwitch = findViewById(R.id.receiveSwitch)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        receiveSwitch?.setOnCheckedChangeListener { _, on ->
            if (on == prefs.syncEnabled) return@setOnCheckedChangeListener
            prefs.syncEnabled = on
            if (on) SyncService.start(this) else SyncService.stop(this)
            renderStatus()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { repo.history.collect { renderHistory(it) } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready()) { openSetup(); return }
        renderStatus()
        if (prefs.syncEnabled) SyncService.start(this) // no-op if already running
        lifecycleScope.launch {
            try {
                repo.refreshHistory(withPreviews = true)
                renderHistory(repo.history.value)
            } catch (e: NeedsConsentException) {
                bridgeStatus.text = "Sign in again"
            } catch (e: Exception) {
                bridgeStatus.text = "Offline"
                bridgeDot.setBackgroundResource(R.drawable.bg_dot_off)
            }
        }
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun renderStatus() {
        val on = prefs.syncEnabled
        bridgeStatus.text = if (on) "Syncing" else "Paused"
        bridgeDot.setBackgroundResource(if (on) R.drawable.bg_dot_live else R.drawable.bg_dot_off)
        bridgeEmail.text = prefs.email
        keyCheckValue?.text = repo.vault.keyCheck ?: ""
        receiveSwitch?.isChecked = on
    }

    private fun renderHistory(items: List<ClipItem>) {
        historyList.removeAllViews()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        // Two columns only when the list pane is genuinely wide (big tablets, desktop mode).
        val paneDp = resources.configuration.screenWidthDp - (if (keyCheckValue != null) 444 else 32)
        val columns = if (paneDp >= 620) 2 else 1
        val gap = Ui.dp(this, 8)
        items.chunked(columns).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, item ->
                val v = clipView(item)
                row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
            if (rowItems.size < columns) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            historyList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = gap })
        }
    }

    private fun clipView(item: ClipItem): View {
        val v = layoutInflater.inflate(R.layout.item_clip, historyList, false)
        val tileText = v.findViewById<TextView>(R.id.tileText)
        val tileIcon = v.findViewById<ImageView>(R.id.tileIcon)
        val title = v.findViewById<TextView>(R.id.clipTitle)
        val meta = v.findViewById<TextView>(R.id.clipMeta)
        val copy = v.findViewById<ImageButton>(R.id.clipCopy)
        fun icon(res: Int) { tileText.visibility = View.GONE; tileIcon.visibility = View.VISIBLE; tileIcon.setImageResource(res) }

        val h = item.header
        val from = when {
            item.mine -> "From this phone"
            h?.device.isNullOrBlank() -> "From another device"
            else -> "From ${h!!.device}"
        }
        val time = Ui.time(item.createdTime)
        if (h == null) {
            icon(R.drawable.ic_lock)
            title.text = "Locked clip"
            meta.text = "Sent with a different passphrase"
            copy.isEnabled = false
            copy.alpha = 0.35f
            return v
        }
        when (h.kind) {
            "text" -> {
                val preview = repo.previews[item.id]?.replace('\n', ' ')?.trim()
                title.text = preview ?: "Text"
                if (preview != null && (preview.startsWith("http://") || preview.startsWith("https://"))) icon(R.drawable.ic_link)
                meta.text = "$from, $time"
            }
            "image" -> { icon(R.drawable.ic_image); title.text = h.name; meta.text = "$from, $time" }
            else -> {
                icon(R.drawable.ic_file); title.text = h.name
                meta.text = "$from, ${Formatter.formatShortFileSize(this, h.size)}, $time"
            }
        }
        copy.contentDescription = if (h.kind == "file") "Save to Downloads" else "Copy again"
        val act = View.OnClickListener { copyFromHistory(item) }
        copy.setOnClickListener(act)
        v.setOnClickListener(act)
        return v
    }

    private fun copyFromHistory(item: ClipItem) {
        lifecycleScope.launch {
            try {
                repo.applyItem(item)
                val big = item.header?.kind == "text" && (item.header.size > ClipRepository.MAX_CLIPBOARD_TEXT)
                val msg = if (item.header?.kind == "file" || big) "Saved to Downloads/ClipBridge" else "Copied"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Couldn't get it: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
