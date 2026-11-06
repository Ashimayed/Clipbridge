package app.clipbridge

import java.security.MessageDigest

/**
 * Small text rules both apps must apply identically.
 * MUST stay identical to chrome-extension/logic.js (safeName, cleanDeviceName, sha256Text),
 * and is checked against protocol/test-vectors.json.
 */
object Names {
    const val MAX_DEVICE_NAME = 40

    private val unsafeFileChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val controlChars = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")
    /** Exactly what JavaScript's \\s and String.trim() treat as white space (Java's \\s and trim() differ). */
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
    private val spaces = Regex("[$WS]+")
    private val edges = Regex("^[$WS]+|[$WS]+\\z") // \z, not $: Java's $ also matches before a final line break

    /** JavaScript's String.trim(). */
    fun jsTrim(s: String): String = s.replace(edges, "")

    /** A file name that is safe to save anywhere: no path separators, no ".." tricks, at most 120 chars. */
    fun safeName(name: String): String {
        val n = jsTrim(name.replace(unsafeFileChars, "_")).take(120)
        return if (n.isEmpty() || n.all { it == '.' }) "clip" else n
    }

    /**
     * A device name typed by the user ("Work laptop"): control characters removed, runs of
     * whitespace collapsed, trimmed, at most [MAX_DEVICE_NAME] characters. Empty means "use the default".
     * It travels inside the encrypted header only, so Drive never sees it.
     */
    fun cleanDeviceName(raw: String): String {
        val t = jsTrim(raw.replace(controlChars, " ").replace(spaces, " "))
        if (t.length <= MAX_DEVICE_NAME) return t
        // Never cut a surrogate pair in half.
        val end = if (t[MAX_DEVICE_NAME - 1].isHighSurrogate()) MAX_DEVICE_NAME - 1 else MAX_DEVICE_NAME
        return jsTrim(t.substring(0, end))
    }

    /** SHA-256 of text with Windows line endings normalized, so the same text hashes the same everywhere. */
    fun sha256Text(text: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)))

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
