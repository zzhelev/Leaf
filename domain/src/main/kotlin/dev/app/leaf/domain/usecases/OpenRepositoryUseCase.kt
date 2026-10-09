package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IOpenRepositoryGitAction
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.FailureSeverity
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.worktrees.isLinkedWorktreeGitDir
import javax.inject.Inject

class OpenRepositoryUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val openRepositoryGitAction: IOpenRepositoryGitAction,
    private val refreshDataUseCase: RefreshDataUseCase,
    private val observeRepositoryToRefreshUseCase: ObserveRepositoryToRefreshUseCase,
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val appStateManager: AppStateManager,
) {
    suspend operator fun invoke(directory: String) {
        val repositoryPathResult = openRepositoryGitAction(directory)

        when (repositoryPathResult) {
            is Either.Err ->  {
                repositoryDataRepository.setRepositorySelectionState(RepositorySelectionState.None)
                repositoryStateRepository.addCompletedTaskFailed(
                    TaskType.RepositoryOpen,
                    repositoryPathResult.error,
                    FailureSeverity.HIGH,
                )
            }
            is Either.Ok -> {
                val gitDir = repositoryPathResult.value
                repositoryDataRepository.setRepositorySelectionState(RepositorySelectionState.Open(gitDir))

                // A linked worktree isn't a recent repository: its repository is, and agents' worktrees come and go
                val worktree = getWorktreeUseCase().okOrNull()
                if (worktree != null && !isLinkedWorktreeGitDir(gitDir)) {
                    appStateManager.repositoryTabChanged(worktree)
                }

                refreshDataUseCase(DataToRefresh.ALL)
                observeRepositoryToRefreshUseCase()
            }
        }
    }
}