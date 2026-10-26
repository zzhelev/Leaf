package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.DataRefreshRunner
import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.RebaseConstants
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.WorktreesRefreshRunner
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.*
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.dataOrNull
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.RebaseCommand
import java.io.File
import javax.inject.Inject

class RefreshDataUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getBranchesGitAction: IGetBranchesGitAction,
    private val getCurrentBranchGitAction: IGetCurrentBranchGitAction,
    private val repositoryDataRepository: RepositoryDataRepository,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val getStashListGitAction: IGetStashListGitAction,
    private val loadAuthorGitAction: ILoadAuthorGitAction,
    private val getStatusGitAction: IGetStatusGitAction,
    private val getRemotesUseCase: GetRemotesUseCase,
    private val getSubmodulesGitAction: IGetSubmodulesGitAction,
    private val getTagsGitAction: IGetTagsGitAction,
    private val getRefDatesGitAction: IGetRefDatesGitAction,
    private val getRepositoryState: IGetRepositoryStateGitAction,
    private val getPersistedCommitMessagesGitAction: IGetPersistedCommitMessagesGitAction,
    private val getTrackingBranchUseCase: GetTrackingBranchUseCase,
    private val getLogUseCase: GetLogUseCase,
    private val getWorktreesInfoUseCase: GetWorktreesInfoUseCase,
    private val getBranchesAheadGitAction: IGetBranchesAheadGitAction,
    private val getBranchLinesGitAction: IGetBranchLinesGitAction,
    private val getCherryPickSequenceGitAction: IGetCherryPickSequenceGitAction,
    private val getRebaseStopGitAction: IGetRebaseStopGitAction,
    private val reportSkippedRebaseCommitsUseCase: ReportSkippedRebaseCommitsUseCase,
    private val worktreesRefreshRunner: WorktreesRefreshRunner,
    private val dataRefreshRunner: DataRefreshRunner,
    private val scope: TabCoroutineScope,
) {
    operator fun invoke(vararg dataToRefresh: DataToRefresh) = scope.launch {
        // Fork-only: one refresh at a time, with what the calls made meanwhile asked for. The worktrees have their own.
        if (dataToRefresh.any { it != DataToRefresh.WORKTREES }) {
            dataRefreshRunner.run(dataToRefresh.toSet()) { refresh(it) }
        }

        // Runs git in every worktree, so it comes after everything else
        if (refreshesWorktrees(dataToRefresh.toList())) {
            refreshWorktrees()
        }
    }

    private suspend fun refresh(dataToRefresh: Set<DataToRefresh>) {
        val isRefreshAll = dataToRefresh.contains(DataToRefresh.ALL)

        repositoryStateRepository.refreshTriggered(dataToRefresh.toList())

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.BRANCHES)) {
            refreshBranches()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.STASHES)) {
            refreshStashes()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.STATUS)) {
            refreshStatus()
            refreshCommitMessages()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.GIT_CONFIG)) {
            refreshGitConfig()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.REMOTES)) {
            refreshRemotes()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.SUBMODULES)) {
            refreshSubmodules()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.TAGS)) {
            refreshTags()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.REPO_STATE)) {
            refreshRepositoryState()
        }

        if (isRefreshAll || dataToRefresh.contains(DataToRefresh.LOG)) {
            refreshLog()
        }

        // Only used to sort the side panel, so it comes last and never delays the log
        val refsChanged = dataToRefresh.any {
            it == DataToRefresh.BRANCHES || it == DataToRefresh.REMOTES || it == DataToRefresh.TAGS
        }

        if (isRefreshAll || refsChanged) {
            refreshRefDates()
        }

        // Fork-only: the branch menus' cherry-pick item, which counts against HEAD, so a commit changes it too. Only
        // the menus read it, so it comes last.
        if (isRefreshAll || refsChanged || dataToRefresh.contains(DataToRefresh.LOG)) {
            refreshBranchesAhead()
            refreshBranchLines()
        }
    }

    private suspend fun refreshCommitMessages() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updatePersistedCommitMessages(repositoryPath) {
                getPersistedCommitMessagesGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshBranches() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updateLocalBranches(repositoryPath) {
                getBranchesGitAction(repositoryPath).mapOk { branches ->
                    branches.map { branch ->
                        val remoteBranch = getTrackingBranchUseCase(branch).okOrNull()
                        branch.copy(trackingBranch = remoteBranch)
                    }
                }
            }

            repositoryDataRepository.updateCurrentBranch(repositoryPath) {
                getCurrentBranchGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshStashes() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updateStashes(repositoryPath) { getStashListGitAction(repositoryPath) }
        }
    }

    private suspend fun refreshLog() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updateLog(repositoryPath) {
                getLogUseCase(repositoryPath, pagination = Pagination.None)
            }
        }
    }

    private suspend fun refreshStatus() {
        useCaseExecutor.executeWithoutResult() { repositoryPath ->
            repositoryDataRepository.updateStatus(repositoryPath) {
                getStatusGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshGitConfig() {
        useCaseExecutor.executeWithoutResult() { repositoryPath ->
            repositoryDataRepository.updateAuthor(repositoryPath) {
                loadAuthorGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshRemotes() {
        useCaseExecutor.executeWithoutResult() { repositoryPath ->
            repositoryDataRepository.updateRemotes(repositoryPath) { getRemotesUseCase() }
        }
    }

    private suspend fun refreshSubmodules() {
        useCaseExecutor.executeWithoutResult() { repositoryPath ->
            repositoryDataRepository.updateSubmodules(repositoryPath) {
                getSubmodulesGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshTags() {
        useCaseExecutor.executeWithoutResult() { repositoryPath ->
            repositoryDataRepository.updateTags(repositoryPath) {
                getTagsGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshWorktrees() {
        worktreesRefreshRunner.run {
            useCaseExecutor.executeWithoutResult { repositoryPath ->
                repositoryDataRepository.updateWorktrees(repositoryPath) { getWorktreesInfoUseCase() }
            }
        }
    }

    private suspend fun refreshRefDates() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updateRefDates(repositoryPath) {
                getRefDatesGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshBranchesAhead() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updateBranchesAhead(repositoryPath) {
                getBranchesAheadGitAction(repositoryPath)
            }
        }
    }

    /** Fork-only: every local branch's own commits, which the menus that squash and reword them read. */
    private suspend fun refreshBranchLines() {
        useCaseExecutor.executeWithoutResult { repositoryPath ->
            repositoryDataRepository.updateBranchLines(repositoryPath) {
                getBranchLinesGitAction(repositoryPath)
            }
        }
    }

    private suspend fun refreshRepositoryState() {
        useCaseExecutor.executeWithoutResult() { repositoryPath ->
            repositoryDataRepository.updateRepositoryState(repositoryPath) {
                getRepositoryState(repositoryPath)
            }

            // Fork-only: git's sequencer, which JGit's repository state doesn't tell
            repositoryDataRepository.updateCherryPickSequence(repositoryPath) {
                getCherryPickSequenceGitAction(repositoryPath)
            }

            // Fork-only: the commit that a rebase stopped at, which its Skip names, and the commits that Skip left out of
            // one that has ended
            repositoryDataRepository.updateRebaseStop(repositoryPath) {
                getRebaseStopGitAction(repositoryPath)
            }

            val repositoryState = repositoryDataRepository.repositoryState.value.dataOrNull()

            if (repositoryState != null && !repositoryState.isRebasing) {
                reportSkippedRebaseCommitsUseCase(repositoryPath)
            }

            val state = (repositoryDataRepository.repositoryState.value as? DataState.Loaded<RepositoryState>)?.data

            if (state == RepositoryState.REBASING_INTERACTIVE) {
                // Fork-only: upstream's list waited here for a rebase whose todo hadn't started yet (no `done`). Leaf no
                // longer starts one, as the commit editor hands git its todo, so a rebase in that state (`git rebase -i`
                // in a terminal, with its editor open) is processing commits too
                repositoryDataRepository.updateRebaseInteractiveState(repositoryPath) {
                    val rebaseMergeDir = File(repositoryPath, RebaseConstants.REBASE_MERGE)

                    Either.Ok(
                        if (rebaseMergeDir.exists()) {
                            RebaseInteractiveState.ProcessingCommits(getRebaseAmendCommitId(repositoryPath))
                        } else {
                            RebaseInteractiveState.None
                        }
                    )
                }
            } else {
                repositoryDataRepository.updateRebaseInteractiveState(repositoryPath) {
                    Either.Ok(RebaseInteractiveState.None)
                }
            }
        }
    }

    private fun getRebaseAmendCommitId(repository: String): String? {
        val amendFile = File(repository, "${RebaseCommand.REBASE_MERGE}/${RebaseConstants.AMEND}")

        return if (!amendFile.exists()) {
            null
        } else {
            amendFile.readText().removeSuffix("\n").removeSuffix("\r\n")
        }
    }
}

/**
 * Whether refreshing [dataToRefresh] refreshes the worktrees too: their branches change with BRANCHES, their last
 * commits and how they compare to the base with LOG, and the tab's worktree's changes with STATUS. WORKTREES refreshes
 * them alone, for changes in the other worktrees or after another base branch is chosen.
 */
internal fun refreshesWorktrees(dataToRefresh: List<DataToRefresh>): Boolean = dataToRefresh.any {
    it == DataToRefresh.ALL || it == DataToRefresh.BRANCHES || it == DataToRefresh.LOG ||
        it == DataToRefresh.STATUS || it == DataToRefresh.WORKTREES
}

enum class DataToRefresh {
    ALL,
    BRANCHES,
    GIT_CONFIG,
    LOG,
    REMOTES,
    REPO_STATE,
    STASHES,
    STATUS,
    SUBMODULES,
    TAGS,
    WORKTREES,
}