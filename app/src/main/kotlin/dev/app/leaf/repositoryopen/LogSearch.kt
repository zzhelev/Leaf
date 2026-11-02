package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.SearchHit

sealed interface LogSearch {
    data object NotSearching : LogSearch

    /**
     * What the log's search found (fork-only: [hits], not commits, as a hit may be older than the commits that the log
     * has loaded, upstream issue 277). [index] starts at 1, and 0 means no result.
     *
     * [isSearching] says that the walk of the whole history is still running, so more may come. [isCapped] says that
     * it stopped at the limit for hits. The counter reads both as a "+".
     */
    data class SearchResults(
        val hits: SearchHits,
        val index: Int,
        /** False while the search field is empty (fork-only): nothing is searched for, so no row is a non-match. */
        val hasTerm: Boolean = true,
        val isSearching: Boolean = false,
        val isCapped: Boolean = false,
    ) : LogSearch {
        val totalCount: Int get() = hits.size

        /** The hashes of the commits whose rows are matches (fork-only), or null while nothing is searched for. */
        val matchingHashes: Set<String>? get() = hits.hashes.takeIf { hasTerm }

        /** The match that the counter points at (fork-only), whose row gets the outline. */
        val focusedHit: SearchHit? get() = if (hasTerm) hits.list.getOrNull(index - 1) else null

        /** The counter's number of results: "12", "12+" while more may come or past the limit, "…" for none yet. */
        val totalText: String
            get() = when {
                totalCount == 0 && isSearching -> "…"
                isSearching || isCapped -> "$totalCount+"
                else -> "$totalCount"
            }

        companion object {
            /** An empty field, which searches for nothing. */
            val NoTerm = SearchResults(SearchHits.None, index = 0, hasTerm = false)
        }
    }
}

/**
 * The hits of a search, in the log's order, with what the rows and the clicks ask of them (fork-only). It is shared by
 * the copies of a result that only change the counter's index, so a step through up to 10,000 hits makes no new set.
 */
class SearchHits(val list: List<SearchHit>) {
    val size: Int get() = list.size

    /** The hits' hashes, for a row to tell whether it is a match. */
    val hashes: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { list.mapTo(HashSet(list.size * 2)) { it.hash } }

    private val places: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<String, Int>(list.size * 2).also { places -> list.forEachIndexed { i, hit -> places.putIfAbsent(hit.hash, i) } }
    }

    /** The 1-based number of the hit for [hash], as the counter reads them, or 0 when it isn't a hit. */
    fun numberOf(hash: String): Int = places[hash]?.plus(1) ?: 0

    fun without(hash: String): SearchHits = SearchHits(list.filter { it.hash != hash })

    override fun equals(other: Any?): Boolean = other is SearchHits && other.list == list
    override fun hashCode(): Int = list.hashCode()

    companion object {
        val None = SearchHits(emptyList())
    }
}
