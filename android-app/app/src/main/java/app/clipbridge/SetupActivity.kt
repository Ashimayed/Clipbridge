package app.clipbridge

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

/** Step 1: sign in with Google. Step 2: set the passphrase. Also used for "Change passphrase". */
class SetupActivity : ComponentActivity() {
    private val prefs get() = app.prefs
    private val repo get() = app.repo
    private val changing get() = intent?.getBooleanExtra(EXTRA_CHANGE, false) == true

    private lateinit var stepWelcome: View
    private lateinit var stepPass: View
    private lateinit var btnSignIn: Button
    private lateinit var welcomeNote: TextView
    private lateinit var pass1: EditText
    private lateinit var pass2: EditText
    private lateinit var passHint: TextView
    private lateinit var passStatus: TextView
    private lateinit var btnSave: Button
    private lateinit var warnCard: View
    private lateinit var keyCheckCard: View
    private lateinit var keyCheckCode: TextView
    private lateinit var btnScanQr: Button
    private var pendingKey: ByteArray? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        when (val r = Pairing.parse(res.data?.getStringExtra(ScanActivity.EXTRA_RAW))) {
            is PairingResult.Bad -> passStatus.text = r.reason
            is PairingResult.Ok -> {
                val signedIn = prefs.email?.trim()?.lowercase()
                if (signedIn != null && signedIn != r.email) {
                    passStatus.text = "That code is for ${r.email}, but you're signed in as ${prefs.email}."
                    return@registerForActivityResult
                }
                Toast.makeText(this, "Paired. Key check ${r.check}.", Toast.LENGTH_LONG).show()
                commit(r.key)
            }
        }
    }

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode != RESULT_OK) { signInFailed("Sign-in was cancelled."); return@registerForActivityResult }
            lifecycleScope.launch {
                try {
                    Auth.accept(Identity.getAuthorizationClient(this@SetupActivity).getAuthorizationResultFromIntent(res.data))
                    onAuthorized()
                } catch (e: Exception) { signInFailed("Sign-in failed: ${e.message}") }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        Ui.constrainWidth(this, findViewById<LinearLayout>(R.id.column), 520)
        stepWelcome = findViewById(R.id.stepWelcome)
        stepPass = findViewById(R.id.stepPass)
        btnSignIn = findViewById(R.id.btnSignIn)
        welcomeNote = findViewById(R.id.welcomeNote)
        pass1 = findViewById(R.id.pass1)
        pass2 = findViewById(R.id.pass2)
        passHint = findViewById(R.id.passHint)
        passStatus = findViewById(R.id.passStatus)
        btnSave = findViewById(R.id.btnSavePass)
        warnCard = findViewById(R.id.warnCard)
        keyCheckCard = findViewById(R.id.keyCheckCard)
        keyCheckCode = findViewById(R.id.keyCheckCode)
        btnScanQr = findViewById(R.id.btnScanQr)

        btnSignIn.setOnClickListener { signIn() }
        btnSave.setOnClickListener { save() }
        btnScanQr.setOnClickListener { scanLauncher.launch(Intent(this, ScanActivity::class.java)) }
        findViewById<Button>(R.id.btnUseAnyway).setOnClickListener { pendingKey?.let { commit(it) } }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (changing) finish() else { Auth.forget(); prefs.clearAccount(); showWelcome() }
        }
        if (changing) findViewById<TextView>(R.id.stepLabel).text = "Change passphrase"

        if (prefs.email != null) showPass() else showWelcome()
    }

    // ---------------------------------------------------------------- step 1

    private fun showWelcome() { stepWelcome.visibility = View.VISIBLE; stepPass.visibility = View.GONE }

    private fun signIn() {
        btnSignIn.isEnabled = false
        welcomeNote.text = "Opening Google sign-in…"
        lifecycleScope.launch {
            try {
                Auth.token(this@SetupActivity, prefs.email)
                onAuthorized()
            } catch (e: NeedsConsentException) {
                val pi = e.pendingIntent
                if (pi == null) signInFailed("Google sign-in isn't available. Update Google Play services.")
                else consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                signInFailed("Sign-in failed: ${e.message}. Check the Google Cloud setup in the README.")
            }
        }
    }

    private suspend fun onAuthorized() {
        val email = repo.fetchEmail() ?: throw IllegalStateException("Google didn't share the account email")
        prefs.email = email
        prefs.syncEnabled = true
        showPass()
    }

    private fun signInFailed(msg: String) {
        btnSignIn.isEnabled = true
        welcomeNote.text = msg
        welcomeNote.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    // ---------------------------------------------------------------- step 2

    private fun showPass() {
        stepWelcome.visibility = View.GONE
        stepPass.visibility = View.VISIBLE
        pass1.requestFocus()
    }

    private fun save() {
        val a = pass1.text.toString()
        val b = pass2.text.toString()
        warnCard.visibility = View.GONE
        when {
            a.length < MIN_LEN -> { passHint.setTextColor(ContextCompat.getColor(this, R.color.danger)); return }
            a != b -> { passStatus.text = "The two passphrases don't match."; return }
        }
        passHint.setTextColor(ContextCompat.getColor(this, R.color.ink2))
        btnSave.isEnabled = false
        passStatus.text = "Checking…"
        lifecycleScope.launch {
            try {
                val key = repo.deriveKey(a)
                keyCheckCode.text = Crypto.keyCheck(key)
                keyCheckCard.visibility = View.VISIBLE
                val match = try { repo.checkAgainstDrive(key) } catch (e: Exception) { null } // offline: can't compare
                if (match == KeyMatch.DIFFERENT_FROM_OTHER_DEVICE) {
                    pendingKey = key
                    warnCard.visibility = View.VISIBLE
                    passStatus.text = ""
                    btnSave.isEnabled = true
                } else {
                    if (match == null) passStatus.text = "Couldn't reach Drive to compare. Check the key check codes match."
                    commit(key)
                }
            } catch (e: Exception) {
                passStatus.text = "Couldn't save: ${e.message}"
                btnSave.isEnabled = true
            }
        }
    }

    private fun commit(key: ByteArray) {
        repo.saveKey(key)
        prefs.seenIds = null // start fresh: never replay clips that arrived before this key
        pass1.text.clear(); pass2.text.clear()
        Notifier.clearAlert(this)
        prefs.syncEnabled = true
        SyncService.start(this)
        if (changing) finish()
        else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    companion object {
        const val EXTRA_CHANGE = "change_passphrase"
        private const val MIN_LEN = 10
    }
}
