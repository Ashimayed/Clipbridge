package app.clipbridge

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * A pass-through activity: it opens Google Play Services' own full-screen code scanner, waits
 * for a result, and hands the decoded text back to whoever started us. It has no UI of its own.
 *
 * Deliberately NOT built on CameraX: the Play Services scanner runs the camera in its own
 * process and only returns the decoded text, so ClipBridge never requests the CAMERA permission
 * and never sees a frame of video. See https://developers.google.com/ml-kit/code-scanner
 */
class ScanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> finishWith(barcode.rawValue ?: barcode.displayValue) }
            .addOnCanceledListener { setResult(RESULT_CANCELED); finish() }
            .addOnFailureListener { e ->
                DebugLog.add("QR scan unavailable: ${e.message}")
                Toast.makeText(this, "Couldn't open the scanner: ${e.message}", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED); finish()
            }
    }

    private fun finishWith(raw: String?) {
        setResult(if (raw != null) RESULT_OK else RESULT_CANCELED, Intent().putExtra(EXTRA_RAW, raw))
        finish()
    }

    companion object {
        const val EXTRA_RAW = "raw"
    }
}
