package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import dev.app.leaf.domain.interfaces.IFormatDiffGitAction
import dev.app.leaf.domain.interfaces.IGenerateSplitHunkFromDiffResultGitAction
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class GetDiffUseCase @Inject constructor(
    private val formatDiffGitAction: IFormatDiffGitAction,
    private val generateSplitHunkFromDiffResultGitAction: IGenerateSplitHunkFromDiffResultGitAction,
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    suspend operator fun invoke(diffType: DiffType, diffViewType: DiffTextViewType, isDisplayFullFile: Boolean): ViewDiffResult {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return ViewDiffResult.None

        return try {
            val diffFormat = when (val result = formatDiffGitAction(repositoryPath, diffType, isDisplayFullFile)) {
                is Either.Ok -> result.value
                is Either.Err -> {
                    logDiffError(diffType, result.error)
                    return ViewDiffResult.DiffNotFound(diffType)
                }
            }
            val diffEntry = diffFormat.diffEntry
            if (
                diffViewType == DiffTextViewType.Split &&
                diffFormat is DiffResult.Text &&
                diffEntry.changeType != DiffEntry.ChangeType.ADD &&
                diffEntry.changeType != DiffEntry.ChangeType.DELETE
            ) {
                val splitHunkList = generateSplitHunkFromDiffResultGitAction(diffFormat)
                ViewDiffResult.Loaded(
                    diffType,
                    DiffResult.TextSplit(diffEntry, splitHunkList)
                )
            } else {
                ViewDiffResult.Loaded(diffType, diffFormat)
            }

        } catch (ex: Exception) {
            printError(TAG, ex.message.orEmpty(), ex)
            ViewDiffResult.DiffNotFound(diffType)
        }
    }

    /**
     * A file with no changes left on the diff's side, as after discarding its last hunk, has no diff entry: that's
     * expected, so it gets a log line without a stack trace.
     */
    private fun logDiffError(diffType: DiffType, error: GitError) {
        val exception = (error as? GenericError)?.exception
        val description = (error as? GenericError)?.message.orEmpty().ifEmpty { error.toString() }

        if (exception is MissingDiffEntryException) {
            printLog(TAG, "No diff to show for ${diffType.filePath}: $description")
        } else {
            printError(TAG, "Could not load the diff of ${diffType.filePath}: $description", exception)
        }
    }
}
