package app.clipbridge

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

sealed class PairingResult {
    data class Ok(val key: ByteArray, val check: String, val email: String) : PairingResult()
    data class Bad(val reason: String) : PairingResult()
}

/**
 * The QR code Chrome shows so the phone doesn't need the passphrase typed in.
 * MUST stay identical to chrome-extension/logic.js (buildPairing / parsePairing).
 *
 *   clipbridge://pair?v=1&k=<32-byte key, base64url>&c=<key check code>&e=<google account email>
 *
 * The key is what the passphrase turns into (see Crypto.deriveKey). It is useless without
 * access to the same Google account's Drive, but it is still a secret: Chrome only shows the
 * code on request and hides it after 60 seconds.
 */
object Pairing {
    private const val PREFIX = "clipbridge://pair?"
    private const val MAX_LEN = 400

    fun build(key: ByteArray, email: String): String {
        require(key.size == 32) { "key must be 32 bytes" }
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val e = URLEncoder.encode(email.trim().lowercase(), "UTF-8").replace("*", "%2A") // same escaping as logic.js
        return "${PREFIX}v=1&k=$k&c=${Crypto.keyCheck(key)}&e=$e"
    }

    fun parse(raw: String?): PairingResult {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_LEN) return PairingResult.Bad("That isn't a ClipBridge pairing code.")
        if (!s.startsWith(PREFIX)) return PairingResult.Bad("That isn't a ClipBridge pairing code.")
        val params = HashMap<String, String>()
        for (part in s.substring(PREFIX.length).split('&')) {
            val i = part.indexOf('=')
            if (i <= 0) return PairingResult.Bad("The pairing code is damaged.")
            val name = part.substring(0, i)
            val value = try { URLDecoder.decode(part.substring(i + 1), "UTF-8") } catch (e: IllegalArgumentException) {
                return PairingResult.Bad("The pairing code is damaged.")
            }
            if (params.put(name, value) != null) return PairingResult.Bad("The pairing code is damaged.")
        }
        if (params["v"] != "1") return PairingResult.Bad("This code is from a newer version. Update ClipBridge.")
        val key = try { Base64.getUrlDecoder().decode(params["k"].orEmpty()) } catch (e: IllegalArgumentException) {
            return PairingResult.Bad("The pairing code is damaged.")
        }
        if (key.size != 32) return PairingResult.Bad("The pairing code is damaged.")
        val check = params["c"].orEmpty().uppercase()
        if (check != Crypto.keyCheck(key)) return PairingResult.Bad("The pairing code is damaged. Try scanning again.")
        val email = params["e"].orEmpty().trim().lowercase()
        if (!email.contains('@') || email.length !in 3..254) return PairingResult.Bad("The pairing code is damaged.")
        return PairingResult.Ok(key, check, email)
    }
}
