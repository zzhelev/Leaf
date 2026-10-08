package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IDeleteTagGitAction
import dev.app.leaf.domain.models.Tag
import javax.inject.Inject

class DeleteTagUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val deleteTagGitAction: IDeleteTagGitAction,
) {
    /** Returns the error to the caller, which is the delete dialog, so it can offer to delete with [force]. */
    suspend operator fun invoke(tag: Tag, force: Boolean): Either<Unit, AppError> = useCaseExecutor.execute(
        dataToRefresh = arrayOf(DataToRefresh.TAGS, DataToRefresh.LOG),
    ) { repositoryPath ->
        deleteTagGitAction(repositoryPath, tag, force)
    }
}