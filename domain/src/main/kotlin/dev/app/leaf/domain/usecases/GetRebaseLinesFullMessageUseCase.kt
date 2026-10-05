package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IGetCommitFromRebaseLineGitAction
import dev.app.leaf.domain.models.RebaseLine
import javax.inject.Inject

class GetRebaseLinesFullMessageUseCase @Inject constructor(
    private val getCommitFromRebaseLineGitAction: IGetCommitFromRebaseLineGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(lines: List<RebaseLine>): Either<List<RebaseLine>, AppError> {
        return useCaseExecutor.execute { repositoryPath ->
            val result = lines.mapNotNull { line ->
                val commit = getCommitFromRebaseLineGitAction(repositoryPath, line.commit, line.shortMessage).bind() ?: return@mapNotNull null
                val fullMessage = commit.message
                line.copy(commit = commit.hash, fullMessage = fullMessage)
            }

            Either.Ok(result)
        }
    }
}