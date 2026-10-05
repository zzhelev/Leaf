package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ILoadAuthorGitAction
import javax.inject.Inject

class GetAuthorUseCase @Inject constructor(
    private val loadAuthorGitAction: ILoadAuthorGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke() = useCaseExecutor.execute() { repositoryPath ->
        loadAuthorGitAction(repositoryPath)
    }
}