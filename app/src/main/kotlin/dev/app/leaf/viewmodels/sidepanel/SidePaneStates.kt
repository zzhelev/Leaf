package dev.app.leaf.viewmodels.sidepanel

import dev.app.leaf.common.flows.combine
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.sorting.RefRow
import dev.app.leaf.domain.sorting.RefSection
import dev.app.leaf.domain.sorting.RefSortState
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.domain.worktrees.worktreeRows
import dev.app.leaf.ui.UiDataState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class SubmodulesState(val isLoading: Boolean, val submodules: List<Pair<String, Submodule>>, val isExpanded: Boolean)

data class TagsState(
    val tags: List<Tag>,
    val isExpanded: Boolean,
    val rows: List<RefRow<Tag>> = emptyList(),
    val sortState: RefSortState = RefSortState(),
)

data class StashesState(val stashes: List<Commit>, val isExpanded: Boolean)


data class BranchesState(
    val isLoading: Boolean,
    val branches: List<Branch>,
    val isExpanded: Boolean,
    val currentBranch: Branch?,
    val rows: List<RefRow<Branch>> = emptyList(),
    val sortState: RefSortState = RefSortState(),
)

fun combineBranchesState(
    branches: Flow<UiDataState<List<Branch>>>,
    currentBranch: Flow<UiDataState<Branch?>>,
    isExpandedBranches: MutableStateFlow<Boolean>,
    filter: MutableStateFlow<String>,
    rowsContext: Flow<RefRowsContext>,
): Flow<BranchesState> {
    return combine(
        branches,
        currentBranch,
        isExpandedBranches,
        filter,
        rowsContext,
    ) { branches, currentBranch, isExpanded, filter, rowsContext ->
        val branchesFiltered = branches.data
            .orEmpty()
            .filter { it.name.lowercaseContains(filter) }

        BranchesState(
            isLoading = branches.isLoading || currentBranch.isLoading,
            branches = branchesFiltered,
            isExpanded = isExpanded,
            currentBranch = currentBranch.data,
            rows = localBranchRows(
                branches = branchesFiltered,
                currentBranch = currentBranch.data,
                context = rowsContext,
                isSearching = filter.isNotBlank(),
                nowMillis = System.currentTimeMillis(),
            ),
            sortState = rowsContext.settings.sortOf(RefSection.Local),
        )
    }
}


data class RemoteView(
    val remoteInfo: RemoteInfo,
    val isExpanded: Boolean,
    val rows: List<RefRow<Branch>> = emptyList(),
)

data class RemotesState(
    val remotes: List<RemoteView> = emptyList(),
    val isExpanded: Boolean = false,
    val currentBranch: Branch? = null,
    val sortState: RefSortState = RefSortState(),
)

fun combineRemotesState(
    remotes: StateFlow<UiDataState<List<RemoteInfo>>>,
    isExpandedRemotes: MutableStateFlow<Boolean>,
    filter: MutableStateFlow<String>,
    currentBranch: Flow<UiDataState<Branch?>>,
    remotesContracted: MutableStateFlow<Set<Remote>>,
    rowsContext: Flow<RefRowsContext>,
): Flow<RemotesState> {
    return combine(
        remotes,
        isExpandedRemotes,
        filter,
        currentBranch,
        remotesContracted,
        rowsContext,
    ) { remotes, isExpanded, filter, currentBranch, remotesContracted, rowsContext ->
        val nowMillis = System.currentTimeMillis()
        val remotesFiltered = remotes.data.orEmpty().map { remoteInfo ->
            val newRemoteInfo = remoteInfo.copy(
                branchesList = remoteInfo.branchesList.filter { branch ->
                    branch.simpleName.lowercaseContains(filter)
                }
            )

            RemoteView(
                newRemoteInfo,
                isExpanded = !remotesContracted.contains(newRemoteInfo.remote),
                rows = remoteBranchRows(newRemoteInfo, rowsContext, isSearching = filter.isNotBlank(), nowMillis),
            )
        }

        RemotesState(
            remotesFiltered,
            isExpanded,
            currentBranch.data,
            sortState = rowsContext.settings.sortOf(RefSection.Remote),
        )
    }
}

/**
 * The Worktrees section (fork-only).
 *
 * @param baseBranch the branch that the worktrees are compared to (`refs/heads/main`), null when there's none.
 * @param error why the worktrees couldn't be listed, for example because git couldn't run. Null otherwise.
 */
data class WorktreesState(
    val rows: List<WorktreeRow> = emptyList(),
    val isExpanded: Boolean = true,
    val baseBranch: String? = null,
    val error: AppError? = null,
)

fun combineWorktreesState(
    worktrees: Flow<UiDataState<WorktreeList>>,
    isExpandedWorktrees: Flow<Boolean>,
    filter: Flow<String>,
): Flow<WorktreesState> {
    return combine(worktrees, isExpandedWorktrees, filter) { worktrees, isExpanded, filter ->
        WorktreesState(
            rows = worktrees.data?.let { worktreeRows(it, filter, System.currentTimeMillis()) }.orEmpty(),
            isExpanded = isExpanded,
            baseBranch = worktrees.data?.baseBranch,
            error = worktrees.error,
        )
    }
}
