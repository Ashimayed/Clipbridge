package app.clipbridge

/**
 * How often to ask Google Drive for new clips.
 * MUST stay identical to chrome-extension/logic.js (pollIntervalMs), and is tested against it.
 *
 * Why this exists: there is no server to push updates, so every device asks Drive. Each ask
 * counts against the Google Cloud project's quota (a list call is 100 units), so asking every
 * 3 seconds forever would not scale to many users. Instead we ask fast while things are
 * happening and slowly when nothing has happened for a while.
 */
enum class SyncMode(val id: String, val label: String, val hint: String) {
    INSTANT("instant", "Instant", "Checks every 3 seconds, always. Fastest, but uses the most battery and Google quota."),
    BALANCED("balanced", "Balanced", "Fast while you're active, slower when idle. Recommended."),
    SAVER("saver", "Battery saver", "Slowest when idle. A clip can take up to 45 seconds to arrive.");

    companion object {
        fun fromId(id: String?): SyncMode = values().firstOrNull { it.id == id } ?: BALANCED
    }
}

object PollPolicy {
    /** [idleMs] = time since anything happened (a clip sent or received, screen on, app opened). */
    fun intervalMs(mode: SyncMode, idleMs: Long): Long {
        val idle = if (idleMs < 0) 0L else idleMs
        return when (mode) {
            SyncMode.INSTANT -> 3_000L
            SyncMode.BALANCED -> when {
                idle < 30_000L -> 3_000L
                idle < 120_000L -> 6_000L
                idle < 600_000L -> 12_000L
                else -> 20_000L
            }
            SyncMode.SAVER -> when {
                idle < 30_000L -> 4_000L
                idle < 120_000L -> 15_000L
                else -> 45_000L
            }
        }
    }

    /** +/-10% so many devices never line up and hit Drive at the same second. [unit] is in 0..1. */
    fun jittered(ms: Long, unit: Double): Long {
        val u = if (unit < 0.0) 0.0 else if (unit > 0.999999) 0.999999 else unit
        return (ms * (0.9 + 0.2 * u)).toLong()
    }
}

/** "Something happened" clock. Fresh activity makes polling fast again. */
object ActivityClock {
    @Volatile private var last = System.currentTimeMillis()

    /** [at] lets a clip's own timestamp count as activity (so a second device notices a busy session). */
    fun touch(at: Long = System.currentTimeMillis()) {
        val now = System.currentTimeMillis()
        val t = if (at > now) now else at // never trust a timestamp from the future
        if (t > last) last = t
    }

    fun idleMs(now: Long = System.currentTimeMillis()): Long = if (now > last) now - last else 0L
}
