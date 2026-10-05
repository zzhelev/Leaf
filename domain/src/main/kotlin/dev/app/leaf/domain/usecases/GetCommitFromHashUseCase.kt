package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetCommitFromHashGitAction
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class GetCommitFromHashUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getCommitFromHashGitAction: IGetCommitFromHashGitAction,
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    suspend operator fun invoke(commitHash: String) = useCaseExecutor.execute { repositoryPath ->
        val logDataState = repositoryDataRepository.log.value
        val commit = (logDataState as? DataState.Loaded)?.data[commitHash]?.commit

        if (commit != null) {
            return@execute Either.Ok(commit)
        }

        getCommitFromHashGitAction(repositoryPath, commitHash)
    }
}