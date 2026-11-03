// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import androidx.compose.runtime.Immutable
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import dev.app.leaf.domain.log.hiding
import dev.app.leaf.domain.log.showing
import dev.app.leaf.domain.log.shownNames
import dev.app.leaf.domain.log.shows
import dev.app.leaf.domain.log.toggled
import dev.app.leaf.domain.log.withExisting
import dev.app.leaf.domain.log.withRemotesOfItsName
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.eclipse.jgit.lib.Constants

/**
 * Which branches the log shows (fork-only, upstream issue 207), as the log's header, the branch menus and the side panel
 * see it.
 *
 * A change shows at once and is saved and walked after it, one at a time: changes that come while one is walked are
 * joined, and the last one wins. Until the walk that a change asked for has ended, the change is what shows; then the
 * filter of the log that the tab has, so that a change made in another tab of the repository shows here with this tab's
 * next log.
 *
 * @param logFilter the filter of the log that the tab has loaded, null before the first one.
 * @param apply saves a filter and walks the log again from it.
 */
class LogBranchFilterController(
    private val scope: CoroutineScope,
    logFilter: Flow<LogBranchFilter?>,
    private val apply: suspend (LogBranchFilter) -> Unit,
) {
    private val requested = MutableStateFlow<LogBranchFilter?>(null)
    private val requests = Channel<LogBranchFilter>(Channel.CONFLATED)

    val filter: StateFlow<LogBranchFilter> = combine(requested, logFilter) { requested, logFilter ->
        requested ?: logFilter ?: NO_LOG_BRANCH_FILTER
    }
        .stateIn(scope, SharingStarted.Eagerly, NO_LOG_BRANCH_FILTER)

    init {
        scope.launch {
            for (filter in requests) {
                apply(filter)

                // A later change waits in the channel, and keeps showing until its own walk has ended
                requested.compareAndSet(filter, null)
            }
        }
    }

    fun set(filter: LogBranchFilter) {
        requested.value = filter
        requests.trySend(filter)
    }
}

/**
 * The filter with the branches that it is about: what the log's header shows, and what a branch's menu and box change.
 * [filter] is as saved; [effective] leaves out the names that no branch has any more.
 */
@Immutable
data class LogBranchFilterState(
    val filter: LogBranchFilter = NO_LOG_BRANCH_FILTER,
    val localBranches: List<Branch> = emptyList(),
    val remoteBranches: List<Branch> = emptyList(),
    val currentBranch: Branch? = null,
) {
    val branches: List<Branch> = localBranches + remoteBranches

    val effective: LogBranchFilter = filter.withExisting(branches)

    /** Whether the log leaves anything out, or shows only chosen branches. */
    val isOn: Boolean = effective != NO_LOG_BRANCH_FILTER

    fun shows(branch: Branch): Boolean = effective.shows(branch, currentBranch)

    /** The filter after the branch's box in the list is clicked. */
    fun toggled(branch: Branch): LogBranchFilter = filter.toggled(branch, branches, currentBranch)

    /** The filter that shows [branch] alone: a local one with the remote branches of its name. */
    fun showingOnly(branch: Branch): LogBranchFilter =
        LogBranchFilter.Only(branch.withRemotesOfItsName(remoteBranches))

    /** The filter that shows [names] alone, the selected branches' with the remote branches of their names. */
    fun showingOnly(names: Collection<String>): LogBranchFilter = LogBranchFilter.Only(
        branches.filter { it.name in names }.flatMapTo(LinkedHashSet()) { it.withRemotesOfItsName(remoteBranches) }
    )

    fun hiding(branch: Branch): LogBranchFilter =
        filter.hiding(branch.withRemotesOfItsName(remoteBranches), filter.shownNames(branches, currentBranch))

    fun showing(branch: Branch): LogBranchFilter =
        filter.showing(branch.withRemotesOfItsName(remoteBranches), filter.shownNames(branches, currentBranch))

    companion object {
        /**
         * The state from the side panel's lists: JGit lists a detached HEAD as a local branch named HEAD, and remotes
         * have `origin/HEAD`, which are no branches to choose.
         */
        fun of(
            filter: LogBranchFilter,
            localBranches: List<Branch>,
            remoteBranches: List<Branch>,
            currentBranch: Branch?,
        ) = LogBranchFilterState(
            filter = filter,
            localBranches = localBranches.filter { it.name != Constants.HEAD },
            remoteBranches = remoteBranches.filter { it.simpleName != Constants.HEAD },
            currentBranch = currentBranch,
        )
    }
}
