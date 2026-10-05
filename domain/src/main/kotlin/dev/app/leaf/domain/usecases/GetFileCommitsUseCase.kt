package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetFileCommitsAction
import javax.inject.Inject

class GetFileCommitsUseCase @Inject constructor(
    private val getFileCommitsAction: IGetFileCommitsAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(filePath: String) = useCaseExecutor.execute(
    ) { repositoryPath ->
        getFileCommitsAction(repositoryPath, filePath)
    }
}