package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.okOrNull
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
            val diffFormat = formatDiffGitAction(repositoryPath, diffType, isDisplayFullFile).okOrNull()!!
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

            ex.printStackTrace()
            ViewDiffResult.DiffNotFound(diffType)
        }
    }
}