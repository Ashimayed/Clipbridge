package app.clipbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the encryption key wrapped by a key that lives inside Android Keystore
 * (hardware-backed on most phones). The wrapping key can never be exported, so
 * copying the app's files off the phone does not reveal your clips' key.
 */
class KeyVault(context: Context) {
    private val sp = context.getSharedPreferences("clipbridge_vault", Context.MODE_PRIVATE)
    @Volatile private var cached: ByteArray? = null

    val hasKey: Boolean get() = cached != null || sp.contains(KEY_BLOB)
    val keyCheck: String? get() = sp.getString(KEY_CHECK, null)

    fun save(key: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val blob = c.iv + c.doFinal(key)
        sp.edit()
            .putString(KEY_BLOB, Base64.getEncoder().encodeToString(blob))
            .putString(KEY_CHECK, Crypto.keyCheck(key))
            .apply()
        cached = key.copyOf()
    }

    /** The key, or null if no passphrase was set (or the Keystore entry was wiped). */
    fun load(): ByteArray? {
        cached?.let { return it }
        val s = sp.getString(KEY_BLOB, null) ?: return null
        return try {
            val blob = Base64.getDecoder().decode(s)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob, 0, 12))
            c.doFinal(blob, 12, blob.size - 12).also { cached = it }
        } catch (e: Exception) {
            // Keystore reset (e.g. after a device restore): ask for the passphrase again.
            clear(); null
        }
    }

    fun clear() {
        cached?.fill(0)
        cached = null
        sp.edit().remove(KEY_BLOB).remove(KEY_CHECK).apply()
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "clipbridge_wrap_v1"
        const val KEY_BLOB = "wrapped_key"
        const val KEY_CHECK = "key_check"
    }
}
