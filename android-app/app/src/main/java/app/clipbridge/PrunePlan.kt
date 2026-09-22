package app.clipbridge

/** Just what pruning needs to know about a clip. */
data class PruneItem(val id: String, val createdTime: String, val pinned: Boolean)

/**
 * Which clips to delete from Drive. MUST stay identical to chrome-extension/logic.js (planPrune).
 * Keep the newest [keep] clips that are NOT pinned. Pinned clips are never deleted here.
 */
object PrunePlan {
    const val MAX_PINS = 10

    fun idsToDelete(items: List<PruneItem>, keep: Int): List<String> {
        val newestFirst = items.sortedWith(compareByDescending<PruneItem> { it.createdTime }.thenBy { it.id })
        var unpinned = 0
        val out = ArrayList<String>()
        for (c in newestFirst) {
            if (c.pinned) continue
            unpinned++
            if (unpinned > keep) out.add(c.id)
        }
        return out
    }
}
