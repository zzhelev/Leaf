package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.*

sealed interface LogSearch {
    data object NotSearching : LogSearch
    data class SearchResults(
        val commits: List<GraphCommit>,
        val index: Int,
        val totalCount: Int = commits.count(),
        /** False while the search field is empty (fork-only): nothing is searched for, so no row is a non-match. */
        val hasTerm: Boolean = true,
    ) : LogSearch {
        /** The commits whose rows are matches (fork-only), or null while nothing is searched for. */
        val matchingCommits: List<GraphCommit>? get() = commits.takeIf { hasTerm }

        /** The match that the counter points at (fork-only), whose row gets the outline. [index] starts at 1. */
        val focusedCommit: GraphCommit? get() = if (hasTerm) commits.getOrNull(index - 1) else null
    }
}


