package app.clipbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** One line of the history list: a section heading, or a clip. */
sealed class Row {
    data class Section(val title: String, val note: String) : Row()
    data class Clip(val item: ClipItem, val preview: String?) : Row()
}

/** Pinned clips first under their own heading, then the recent ones. */
fun buildRows(items: List<ClipItem>, previews: Map<String, String>): List<Row> {
    val pinned = items.filter { it.pinned }
    val recent = items.filterNot { it.pinned }
    val rows = ArrayList<Row>(items.size + 2)
    if (pinned.isNotEmpty()) {
        rows += Row.Section("Pinned", "Never removed")
        pinned.forEach { rows += Row.Clip(it, previews[it.id]) }
    }
    if (recent.isNotEmpty()) {
        rows += Row.Section("Recent", if (pinned.isEmpty()) "Last 10 kept. Pin to keep more" else "Last 10 kept")
        recent.forEach { rows += Row.Clip(it, previews[it.id]) }
    }
    return rows
}

class ClipAdapter(
    private val ctx: Context,
    private val onOpen: (ClipItem) -> Unit,
    private val onPin: (ClipItem, ImageButton) -> Unit,
    private val onDelete: (ClipItem) -> Unit,
    private val loadThumb: (ClipItem, ImageView) -> Unit,
) : ListAdapter<Row, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int) = if (getItem(position) is Row.Section) SECTION else CLIP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == SECTION) SectionHolder(inf.inflate(R.layout.item_section, parent, false))
        else ClipHolder(inf.inflate(R.layout.item_clip, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Section -> (holder as SectionHolder).bind(row)
            is Row.Clip -> (holder as ClipHolder).bind(row)
        }
    }

    fun clipAt(position: Int): ClipItem? = (currentList.getOrNull(position) as? Row.Clip)?.item

    class SectionHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val title = v.findViewById<TextView>(R.id.sectionTitle)
        private val note = v.findViewById<TextView>(R.id.sectionNote)
        fun bind(r: Row.Section) { title.text = r.title; note.text = r.note }
    }

    inner class ClipHolder(private val v: View) : RecyclerView.ViewHolder(v) {
        private val tileText = v.findViewById<TextView>(R.id.tileText)
        private val tileIcon = v.findViewById<ImageView>(R.id.tileIcon)
        private val tileImage = v.findViewById<ImageView>(R.id.tileImage)
        private val title = v.findViewById<TextView>(R.id.clipTitle)
        private val meta = v.findViewById<TextView>(R.id.clipMeta)
        private val copy = v.findViewById<ImageButton>(R.id.clipCopy)
        private val pin = v.findViewById<ImageButton>(R.id.clipPin)
        private var deleteActionId = View.NO_ID

        private fun showText(t: String) {
            tileText.visibility = View.VISIBLE; tileText.text = t
            tileText.textSize = if (t.length > 2) 11f else 16f
            tileIcon.visibility = View.GONE; tileImage.visibility = View.GONE
        }
        private fun showIcon(res: Int) {
            tileText.visibility = View.GONE; tileImage.visibility = View.GONE
            tileIcon.visibility = View.VISIBLE; tileIcon.setImageResource(res)
        }

        fun bind(r: Row.Clip) {
            val item = r.item
            val h = item.header
            tileImage.setImageDrawable(null); tileImage.tag = null

            pin.contentDescription = if (item.pinned) "Unpin" else "Pin this clip"
            pin.setColorFilter(ContextCompat.getColor(ctx, if (item.pinned) R.color.cobalt else R.color.ink2))
            pin.isEnabled = h != null
            pin.alpha = if (h != null) 1f else 0.35f
            pin.setOnClickListener { onPin(item, pin) }

            // Swiping is the quick way to delete; TalkBack and switch users get the same as an action.
            if (deleteActionId != View.NO_ID) ViewCompat.removeAccessibilityAction(v, deleteActionId)
            deleteActionId = ViewCompat.addAccessibilityAction(v, "Delete from all devices") { _, _ -> onDelete(item); true }

            val from = when {
                item.mine -> "From this phone"
                h?.device.isNullOrBlank() -> "From another device"
                else -> "From ${h!!.device}"
            }
            val time = Ui.time(item.createdTime)
            if (h == null) {
                showIcon(R.drawable.ic_lock)
                title.text = "Locked clip"
                meta.text = "Sent with a different passphrase. Swipe to delete"
                copy.isEnabled = false; copy.alpha = 0.35f
                v.setOnClickListener(null)
                return
            }
            copy.isEnabled = true; copy.alpha = 1f
            when (h.kind) {
                "text" -> {
                    val preview = r.preview?.replace('\n', ' ')?.trim()
                    title.text = preview ?: "Text"
                    if (preview != null && (preview.startsWith("http://") || preview.startsWith("https://"))) showIcon(R.drawable.ic_link)
                    else showText("Aa")
                    meta.text = "$from, $time"
                }
                "image" -> {
                    showIcon(R.drawable.ic_image)
                    title.text = h.name
                    meta.text = "$from, ${Formatter.formatShortFileSize(ctx, h.size)}, $time"
                    tileImage.tag = item.id
                    loadThumb(item, tileImage)
                }
                else -> {
                    val ext = Ui.extension(h.name)
                    if (ext.isNotEmpty()) showText(ext) else showIcon(R.drawable.ic_file)
                    title.text = h.name
                    meta.text = "$from, ${Formatter.formatShortFileSize(ctx, h.size)}, $time"
                }
            }
            copy.setImageResource(if (h.kind == "file") R.drawable.ic_save_down else R.drawable.ic_copy)
            copy.contentDescription = if (h.kind == "file") "Save to Downloads" else "Copy again"
            val act = View.OnClickListener { onOpen(item) }
            copy.setOnClickListener(act)
            v.setOnClickListener(act)
        }
    }

    /** Swipe a clip left or right to delete it; a red strip with a bin shows underneath. */
    fun swipeToDelete(): ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.swipe_delete) }
        private val bin = ContextCompat.getDrawable(ctx, R.drawable.ic_trash)!!.mutate().apply { setTint(0xFFFFFFFF.toInt()) }
        private val radius = 16 * ctx.resources.displayMetrics.density

        override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder) =
            if (vh is ClipHolder) super.getSwipeDirs(rv, vh) else 0

        override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val item = clipAt(vh.bindingAdapterPosition)
            if (item != null) onDelete(item) else notifyItemChanged(vh.bindingAdapterPosition)
        }

        override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.4f

        override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dX: Float, dY: Float, state: Int, active: Boolean) {
            val v = vh.itemView
            if (dX != 0f) {
                c.drawRoundRect(RectF(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat()), radius, radius, paint)
                val size = bin.intrinsicWidth
                val top = v.top + (v.height - size) / 2
                val inset = (24 * ctx.resources.displayMetrics.density).toInt()
                val left = if (dX > 0) v.left + inset else v.right - inset - size
                bin.setBounds(left, top, left + size, top + size)
                bin.draw(c)
            }
            super.onChildDraw(c, rv, vh, dX, dY, state, active)
        }
    })

    companion object {
        private const val SECTION = 0
        private const val CLIP = 1
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = when {
                a is Row.Section && b is Row.Section -> a.title == b.title
                a is Row.Clip && b is Row.Clip -> a.item.id == b.item.id
                else -> false
            }
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
