package app.clipbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home: the bridge card, a box to send text or files, the clip history (pinned first), and the
 * big Send clipboard button. Swipe a clip sideways to delete it (with a few seconds to undo).
 */
class MainActivity : ComponentActivity() {
    private val prefs get() = app.prefs
    private val repo get() = app.repo

    private lateinit var bridgeStatus: TextView
    private lateinit var bridgeDot: View
    private lateinit var bridgeEmail: TextView
    private lateinit var clipList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var searchBox: EditText
    private lateinit var composerText: EditText
    private lateinit var btnSendText: ImageButton
    private lateinit var transferList: LinearLayout
    private lateinit var undoBar: View
    private lateinit var undoText: TextView
    private lateinit var adapter: ClipAdapter
    private var keyCheckValue: TextView? = null  // two-pane layout only
    private var receiveSwitch: Switch? = null     // two-pane layout only
    private var allItems: List<ClipItem> = emptyList()
    private var undoJob: Job? = null
    private var lastDeleted: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android's photo picker: no storage permission needed, and only what you pick is shared. */
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { sendUris(it) }

    /** Any kind of file, from any app that provides files (Files, Drive, Downloads...). */
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sendUris(it) }

    private fun ready() = prefs.email != null && repo.vault.hasKey

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.edgeToEdge(this)
        super.onCreate(savedInstanceState)
        if (!ready()) { openSetup(); return }
        setContentView(R.layout.activity_main)
        bridgeStatus = findViewById(R.id.bridgeStatus)
        bridgeDot = findViewById(R.id.bridgeDot)
        bridgeEmail = findViewById(R.id.bridgeEmail)
        clipList = findViewById(R.id.clipList)
        emptyText = findViewById(R.id.emptyText)
        searchBox = findViewById(R.id.searchBox)
        composerText = findViewById(R.id.composerText)
        btnSendText = findViewById(R.id.btnSendText)
        transferList = findViewById(R.id.transferList)
        undoBar = findViewById(R.id.undoBar)
        undoText = findViewById(R.id.undoText)
        keyCheckValue = findViewById(R.id.keyCheckValue)
        receiveSwitch = findViewById(R.id.receiveSwitch)
        setupInsets()

        adapter = ClipAdapter(this, ::copyFromHistory, ::togglePin, ::deleteWithUndo, ::loadThumb)
        clipList.adapter = adapter
        clipList.itemAnimator?.changeDuration = 0 // no flicker when a row updates in place
        setColumns()
        adapter.swipeToDelete().attachToRecyclerView(clipList)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSend).setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener { showAttachMenu(it) }
        btnSendText.setOnClickListener { sendTyped() }
        composerText.addTextChangedListener(watcher { btnSendText.isEnabled = composerText.text.isNotBlank() })
        findViewById<Button>(R.id.undoBtn).setOnClickListener { undoDelete() }
        receiveSwitch?.setOnCheckedChangeListener { _, on ->
            if (on == prefs.syncEnabled) return@setOnCheckedChangeListener
            prefs.syncEnabled = on
            if (on) SyncService.start(this) else SyncService.stop(this)
            renderStatus()
        }
        searchBox.addTextChangedListener(watcher { applyFilter() })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repo.visibleHistory.collect { allItems = it; applyFilter() } }
                launch { repo.transfers.collect { renderTransfers(it) } }
                launch { repo.events.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready()) { openSetup(); return }
        ActivityClock.touch() // opening the app means you're likely about to paste something
        renderStatus()
        if (prefs.syncEnabled) SyncService.start(this) // no-op if already running
        lifecycleScope.launch {
            try {
                repo.refreshHistory(withPreviews = true)
                applyFilter()
            } catch (e: NeedsConsentException) {
                bridgeStatus.text = "Sign in again"
            } catch (e: Exception) {
                bridgeStatus.text = "Offline"
                bridgeDot.setBackgroundResource(R.drawable.bg_dot_off)
            }
        }
    }

    // ================================================================ layout

    /**
     * Edge to edge: the content draws behind the status and navigation bars, so pad it by them.
     * On phones the bottom bar (composer + Send clipboard) also rides up with the keyboard, and the
     * list leaves room for it so the last clip is never hidden behind it.
     */
    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        val bottomBar = findViewById<View?>(R.id.bottomBar)
        val scroll = findViewById<NestedScrollView>(R.id.scroll)
        if (bottomBar == null) { // two-pane layout
            Ui.padForSystemBars(root) { bottom -> placeUndo(bottom) }
            return
        }
        Ui.padForSystemBars(root, bottom = false)
        Ui.padForSystemBars(bottomBar, top = false, sides = false)
        bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bottom - top)
                placeUndo(bottom - top)
            }
        }
    }

    private fun placeUndo(fromBottom: Int) {
        val lp = undoBar.layoutParams as ViewGroup.MarginLayoutParams
        lp.bottomMargin = fromBottom + Ui.dp(this, 8)
        undoBar.layoutParams = lp
    }

    /** Two columns only when the list pane is genuinely wide (big tablets, desktop mode). */
    private fun setColumns() {
        val paneDp = resources.configuration.screenWidthDp - (if (keyCheckValue != null) 444 else 32)
        val columns = if (paneDp >= 620) 2 else 1
        clipList.layoutManager = GridLayoutManager(this, columns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (adapter.getItemViewType(position) == 0) columns else 1
            }
        }
        if (columns > 1) {
            val gap = Ui.dp(this, 4)
            clipList.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(out: android.graphics.Rect, v: View, parent: RecyclerView, state: RecyclerView.State) {
                    out.left = gap; out.right = gap
                }
            })
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

    // ================================================================ list

    /** Filters the already-decrypted, already-fetched list by whatever's in the search box. Nothing is re-fetched. */
    private fun applyFilter() {
        val q = searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) allItems else allItems.filter { item ->
            val h = item.header
            val haystack = buildString {
                if (h == null) { append("locked clip") } else {
                    append(h.name).append(' ').append(h.device).append(' ').append(h.kind)
                    if (h.kind == "text") repo.previews[item.id]?.let { append(' ').append(it) }
                }
            }.lowercase()
            haystack.contains(q)
        }
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (q.isNotEmpty()) "Nothing matches that search."
            else "Nothing yet. Copy something on your computer, type something below, or attach a file."
        adapter.submitList(buildRows(filtered, repo.previews))
    }

    private fun loadThumb(item: ClipItem, into: ImageView) {
        repo.thumbs.peek(item.id)?.let { show(into, it); return }
        lifecycleScope.launch {
            val bmp = repo.thumbnail(item) ?: return@launch
            if (into.tag == item.id) show(into, bmp) // the row may have been reused meanwhile
        }
    }

    private fun show(into: ImageView, bmp: android.graphics.Bitmap) {
        into.setImageBitmap(bmp)
        into.visibility = View.VISIBLE
    }

    private fun togglePin(item: ClipItem, pin: ImageButton) {
        pin.isEnabled = false
        lifecycleScope.launch {
            try { repo.setPinned(item, !item.pinned) }
            catch (e: PinLimitException) { toast(e.message) }
            catch (e: Exception) { toast("Couldn't update: ${e.message}") }
            finally { pin.isEnabled = true }
        }
    }

    private fun copyFromHistory(item: ClipItem) {
        lifecycleScope.launch {
            try {
                repo.applyItem(item)
                val big = item.header?.kind == "text" && (item.header.size > ClipRepository.MAX_CLIPBOARD_TEXT)
                toast(if (item.header?.kind == "file" || big) "Saved to Downloads/ClipBridge" else "Copied")
            } catch (e: Exception) {
                toast("Couldn't get it: ${e.message}")
            }
        }
    }

    // ================================================================ deleting (with undo)

    private fun deleteWithUndo(item: ClipItem) {
        lastDeleted = item.id // an earlier swipe still pending simply goes ahead
        repo.deleteSoon(item.id, app.scope) // app scope: it still happens if you leave this screen
        undoText.text = if (item.pinned) "Pinned clip deleted" else "Clip deleted"
        undoBar.visibility = View.VISIBLE
        undoBar.announceForAccessibility("Clip deleted. Undo available.")
        undoJob?.cancel()
        undoJob = lifecycleScope.launch {
            delay(ClipRepository.UNDO_MS)
            undoBar.visibility = View.GONE
            lastDeleted = null
        }
    }

    private fun undoDelete() {
        val id = lastDeleted ?: return
        undoJob?.cancel()
        undoBar.visibility = View.GONE
        lastDeleted = null
        if (!repo.undoDelete(id)) toast("Too late, it's already deleted")
    }

    // ================================================================ sending

    private fun showAttachMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Photos and videos")
            menu.add(0, 2, 1, "Files")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    else -> pickFiles.launch(arrayOf("*/*"))
                }
                true
            }
        }.show()
    }

    private fun sendTyped() {
        val text = composerText.text?.toString().orEmpty()
        if (text.isBlank()) return
        btnSendText.isEnabled = false
        val ca = app
        lifecycleScope.launch {
            val r = runCatching { ca.repo.sendText(text, force = true) } // typed on purpose: always send it
            if (r.isSuccess) composerText.setText("")
            else btnSendText.isEnabled = true
            CaptureActivity.report(ca, r)
        }
    }

    /** Picked items: copied into the app's cache while we may still read them, then sent in the background. */
    private fun sendUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        toast(if (uris.size == 1) "Sending…" else "Sending ${uris.size} items…")
        val ca = app
        lifecycleScope.launch {
            val staged = uris.mapNotNull { u -> runCatching { ca.repo.stage(u, hash = false) }.getOrNull() }
            if (staged.isEmpty()) { toast("Couldn't read that item"); return@launch }
            ca.scope.launch { CaptureActivity.sendAll(ca, staged, force = true) }
        }
    }

    private fun renderTransfers(list: List<Transfer>) {
        transferList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        while (transferList.childCount > list.size) transferList.removeViewAt(transferList.childCount - 1)
        while (transferList.childCount < list.size) {
            transferList.addView(LayoutInflater.from(this).inflate(R.layout.transfer_row, transferList, false))
        }
        list.forEachIndexed { i, t ->
            val row = transferList.getChildAt(i)
            row.findViewById<TextView>(R.id.transferName).text = "Sending ${t.name}"
            row.findViewById<TextView>(R.id.transferPct).text = "${t.percent}%"
            row.findViewById<ProgressBar>(R.id.transferBar).progress = t.percent
            row.contentDescription = "Sending ${t.name}, ${t.percent} percent"
        }
    }

    private fun toast(msg: String?) = Toast.makeText(this, msg ?: "Something went wrong", Toast.LENGTH_SHORT).show()

    private fun watcher(after: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { after() }
    }

    companion object {
        /** The photo picker's own cap; the files picker has none. */
        private const val MAX_PICK = 20
    }
}
