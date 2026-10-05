package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.*

sealed interface LogSearch {
    data object NotSearching : LogSearch
    data class SearchResults(
        val commits: List<GraphCommit>,
        val index: Int,
        val totalCount: Int = commits.count(),
    ) : LogSearch
}


