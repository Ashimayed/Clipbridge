package app.clipbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Instant

/**
 * Inflates the real screens with sample clips, checks their structure, and saves screenshots to
 * app/build/screenshots so the design can be reviewed without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class ScreensTest {
    private val now = Instant.now().toString()
    private val lastWeek = Instant.now().minusSeconds(8 * 86_400).toString()

    private val items = listOf(
        ClipItem("idPinnedText0001", lastWeek, false, ClipHeader("clip.txt", "text/plain", "text", "Work laptop", 40), 80, pinned = true),
        ClipItem("idRecentImage001", now, false, ClipHeader("Holiday photo.jpg", "image/jpeg", "image", "Chrome on Windows", 2_400_000), 2_400_100),
        ClipItem("idRecentFile0001", now, true, ClipHeader("Quarterly report.pdf", "application/pdf", "file", "Pixel 9", 18_300_000), 18_300_100),
        ClipItem("idRecentText0002", now, false, ClipHeader("clip.txt", "text/plain", "text", "Work laptop", 30), 60),
        ClipItem("idRecentLocked01", now, false, null, 99),
    )
    private val previews = mapOf("idPinnedText0001" to "Wi-Fi password for the office: purple-couch-77", "idRecentText0002" to "https://example.com/shared/link")

    @Test fun pinnedClipsComeFirstUnderTheirOwnHeading() {
        val rows = buildRows(items, previews)
        assertEquals(Row.Section("Pinned", "Never removed"), rows[0])
        assertEquals("idPinnedText0001", (rows[1] as Row.Clip).item.id)
        assertEquals("Recent", (rows[2] as Row.Section).title)
        assertEquals(4, rows.drop(3).count { it is Row.Clip })
        assertTrue(buildRows(items.filterNot { it.pinned }, previews).first() == Row.Section("Recent", "Last 10 kept. Pin to keep more"))
        assertTrue(buildRows(emptyList(), previews).isEmpty())
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port") fun phoneHome() = renderHome("home-phone-light")

    @Test @Config(qualifiers = "w411dp-h891dp-port-night") fun phoneHomeDark() = renderHome("home-phone-dark")

    @Test @Config(qualifiers = "w1000dp-h700dp-land") fun tabletHome() = renderHome("home-tablet-light")

    @Test @Config(qualifiers = "w411dp-h891dp-port") fun settingsScreen() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_settings, FrameLayout(activity), false)
        assertNotNull(root.findViewById<View>(R.id.rowDeviceName))
        root.findViewById<TextView>(R.id.rowDeviceNameSub).text = "Work phone"
        root.findViewById<TextView>(R.id.versionText).text = "ClipBridge 2.2.0"
        save(root, activity, "settings-phone-light", 411, 1500)
    }

    private fun renderHome(name: String) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_main, FrameLayout(activity), false)
        val wide = root.findViewById<View?>(R.id.bottomBar) == null
        root.findViewById<TextView>(R.id.bridgeEmail).text = "someone@example.com"

        // A big upload in progress.
        val transfers = root.findViewById<LinearLayout>(R.id.transferList)
        transfers.visibility = View.VISIBLE
        val row = LayoutInflater.from(activity).inflate(R.layout.transfer_row, transfers, false)
        row.findViewById<TextView>(R.id.transferName).text = "Sending Holiday video.mp4"
        row.findViewById<TextView>(R.id.transferPct).text = "42%"
        row.findViewById<ProgressBar>(R.id.transferBar).progress = 42
        transfers.addView(row)

        val list = root.findViewById<RecyclerView>(R.id.clipList)
        val adapter = ClipAdapter(activity, {}, { _, _ -> }, {}, { _, iv: ImageView ->
            // Stand-in for a decrypted preview: a two-tone "photo".
            val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(Color.rgb(90, 140, 200)); drawRect(0f, 40f, 64f, 64f, android.graphics.Paint().apply { color = Color.rgb(60, 120, 70) }) }
            iv.setImageBitmap(b); iv.visibility = View.VISIBLE
        })
        list.layoutManager = GridLayoutManager(activity, 1)
        list.adapter = adapter
        adapter.submitList(buildRows(items, previews))
        ShadowLooper.idleMainLooper()

        val (w, h) = if (wide) 1000 to 700 else 411 to 1300
        save(root, activity, name, w, h)
        assertEquals(7, adapter.itemCount) // 2 headings + 5 clips
        val first = list.findViewHolderForAdapterPosition(1)!!.itemView
        assertEquals("Wi-Fi password for the office: purple-couch-77", first.findViewById<TextView>(R.id.clipTitle).text.toString())
        val file = list.findViewHolderForAdapterPosition(4)!!.itemView
        assertEquals("PDF", file.findViewById<TextView>(R.id.tileText).text.toString())
        val image = list.findViewHolderForAdapterPosition(3)!!.itemView
        assertEquals(View.VISIBLE, image.findViewById<ImageView>(R.id.tileImage).visibility)
    }

    private fun save(root: View, activity: ComponentActivity, name: String, widthDp: Int, heightDp: Int) {
        val d = activity.resources.displayMetrics.density
        val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        ShadowLooper.idleMainLooper()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("screenshots") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
