package dev.app.leaf.domain.models

import androidx.compose.runtime.Immutable
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import java.util.*

@Immutable
data class GraphCommits(
    val commits: LinkedHashMap<String, GraphCommit> = LinkedHashMap(),
    val maxLane: Int = 0,
    // Fork-only: the branches that the log was walked from (upstream issue 207)
    val branchFilter: LogBranchFilter = NO_LOG_BRANCH_FILTER,
): SequencedMap<String, GraphCommit> by commits
