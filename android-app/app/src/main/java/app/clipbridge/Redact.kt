package app.clipbridge

/**
 * Makes a log line safe to paste into an email or a GitHub issue.
 * MUST stay identical to chrome-extension/logic.js (redact), and is tested against it.
 * Removes: email addresses, bearer tokens, URL query strings, and any long opaque string
 * (Drive file ids, access tokens, keys, hashes, ciphertext).
 */
object Redact {
    private val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val bearer = Regex("(?i)bearer[ \\t\\r\\n\\f\\u000B]+[^ \\t\\r\\n\\f\\u000B]+")
    private val query = Regex("(https?://[^ \\t\\r\\n\\f\\u000B?]+)\\?[^ \\t\\r\\n\\f\\u000B]*")
    private val opaque = Regex("[A-Za-z0-9_\\-+/=]{28,}")
    private val space = Regex("[ \\t\\r\\n\\f\\u000B]+")

    fun line(s: String, max: Int = 300): String {
        var t = email.replace(s, "<email>")
        t = bearer.replace(t, "Bearer <token>")
        t = query.replace(t, "\$1?<query>")
        t = opaque.replace(t, "<id>")
        t = space.replace(t, " ").trim(' ')
        return if (t.length > max) t.take(max) + "…" else t
    }
}
