package app.clipbridge

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException

/** The user must see Google's consent/account screen. Only an Activity can show it. */
class NeedsConsentException(val pendingIntent: PendingIntent?) : Exception("Google sign-in required")

object Auth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    private val lock = Mutex()
    @Volatile private var cached: String? = null
    @Volatile private var cachedAt = 0L
    private const val TOKEN_TTL_MS = 45 * 60 * 1000L // Google tokens last ~60 min

    fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
        if (email != null && email.contains('@')) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    /** Returns a valid access token, or throws [NeedsConsentException]. Safe to call from a Service. */
    suspend fun token(context: Context, email: String?): String {
        lock.withLock {
            val now = System.currentTimeMillis()
            val c = cached
            if (c != null && now - cachedAt < TOKEN_TTL_MS) return c
            val result = Identity.getAuthorizationClient(context).authorize(request(email)).await()
            return accept(result)
        }
    }

    /** Used after the consent screen returns to the Activity. */
    fun accept(result: AuthorizationResult): String {
        if (result.hasResolution()) throw NeedsConsentException(result.pendingIntent)
        val t = result.accessToken ?: throw IOException("Google returned no access token")
        cached = t
        cachedAt = System.currentTimeMillis()
        return t
    }

    /** Call after an HTTP 401: drops the token locally and in Google Play services. */
    suspend fun invalidate(context: Context) {
        val t = cached ?: return
        cached = null
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, t) } }
    }

    fun forget() { cached = null }
}
