package app.clipbridge

import android.content.Context
import android.os.Build
import java.util.UUID

/** Small persisted state. All access is cheap; SharedPreferences caches in memory. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clipbridge", Context.MODE_PRIVATE)

    /** Random id that marks clips created on this phone, so we never "receive" our own clips. */
    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** What this phone is called on your other devices: your own name for it, or the model. */
    val deviceLabel: String
        get() = deviceName.ifEmpty { defaultDeviceLabel }

    /** e.g. "Google Pixel 9". Some makers repeat their name in the model; don't say it twice. */
    val defaultDeviceLabel: String
        get() {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").trim().ifEmpty { "Android phone" }
        }

    /** The name you gave this phone ("" = use the model). Cleaned exactly like Chrome cleans its own. */
    var deviceName: String
        get() = Names.cleanDeviceName(sp.getString(KEY_DEVICE_NAME, "") ?: "")
        set(v) = sp.edit().putString(KEY_DEVICE_NAME, Names.cleanDeviceName(v)).apply()

    var email: String?
        get() = sp.getString(KEY_EMAIL, null)
        set(v) = sp.edit().putString(KEY_EMAIL, v).apply()

    var syncEnabled: Boolean
        get() = sp.getBoolean(KEY_SYNC, false)
        set(v) = sp.edit().putBoolean(KEY_SYNC, v).apply()

    /**
     * Drive ids of clips already handled (newest first, capped). Ids are exact, unlike timestamps:
     * two clips created in the same millisecond can't be missed or applied twice.
     * null = never polled yet (first run: mark everything as seen, replay nothing).
     */
    var seenIds: List<String>?
        get() = sp.getString(KEY_SEEN, null)?.split(',')?.filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_SEEN, v?.take(60)?.joinToString(",")).apply()

    /** SHA-256 of the last thing we uploaded or put on the clipboard. Stops echo loops. */
    var lastHash: String?
        get() = sp.getString(KEY_LAST_HASH, null)
        set(v) = sp.edit().putString(KEY_LAST_HASH, v).apply()

    /** How eagerly to check Drive for new clips. See PollPolicy.kt. */
    var syncMode: SyncMode
        get() = SyncMode.fromId(sp.getString(KEY_SYNC_MODE, null))
        set(v) = sp.edit().putString(KEY_SYNC_MODE, v.id).apply()

    fun clearAccount() {
        sp.edit().remove(KEY_EMAIL).remove(KEY_SEEN).remove(KEY_LAST_HASH)
            .putBoolean(KEY_SYNC, false).apply()
        // syncMode and deviceName are device preferences, not account data: they survive sign-out on purpose.
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_EMAIL = "email"
        const val KEY_SYNC = "sync_enabled"
        const val KEY_SEEN = "seen_ids"
        const val KEY_LAST_HASH = "last_hash"
        const val KEY_SYNC_MODE = "sync_mode"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
