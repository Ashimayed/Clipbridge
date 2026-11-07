package app.clipbridge

/**
 * The tiny bit of JSON writing ClipBridge needs, done by hand so the bytes are EXACTLY what
 * JavaScript's JSON.stringify() produces in chrome-extension/crypto.js. (Android's org.json
 * writes "/" as "\/" and escapes some characters differently, so a header sealed on the phone
 * would not be byte-identical to the same header sealed in Chrome.)
 *
 * Escaping follows ECMAScript QuoteJSONString: \b \t \n \f \r \" \\, every other control
 * character and every lone surrogate as a lowercase \u00xx / \udxxx escape, everything else as is.
 */
object Json {
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\t' -> sb.append("\\t")
                c == '\n' -> sb.append("\\n")
                c == '\u000C' -> sb.append("\\f")
                c == '\r' -> sb.append("\\r")
                c < ' ' -> sb.append(unicodeEscape(c))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                c.isSurrogate() -> sb.append(unicodeEscape(c)) // lone half of a pair
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }

    private fun unicodeEscape(c: Char) = "\\u" + c.code.toString(16).padStart(4, '0')

    /** An object with string or whole-number values, keys in the given order: {"a":"x","b":1}. */
    fun obj(vararg fields: Pair<String, Any>): String = fields.joinToString(",", "{", "}") { (k, v) ->
        quote(k) + ":" + when (v) {
            is String -> quote(v)
            is Int, is Long -> v.toString()
            else -> throw IllegalArgumentException("unsupported JSON value: ${v::class.simpleName}")
        }
    }
}
