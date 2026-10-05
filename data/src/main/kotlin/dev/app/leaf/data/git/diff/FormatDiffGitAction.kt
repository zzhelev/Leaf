package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryContent
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IFormatDiffGitAction
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import java.io.InvalidObjectException
import javax.inject.Inject

class FormatDiffGitAction @Inject constructor(
    private val formatHunksGitAction: FormatHunksGitAction,
    private val getDiffContentGitAction: GetDiffContentGitAction,
    private val canGenerateTextDiffGitAction: CanGenerateTextDiffGitAction,
    private val getDiffEntryFromDiffTypeGitAction: GetDiffEntryFromDiffTypeGitAction,
    private val getSubmodulesGitAction: GetSubmodulesGitAction,
    private val textDiffFromDiffLinesGitAction: TextDiffFromDiffLinesGitAction,
    private val jgit: JGit,
) : IFormatDiffGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        diffType: DiffType,
        isDisplayFullFile: Boolean,
    ) = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val submodules = getSubmodulesGitAction(repositoryPath).bind()

        val diffEntry = getDiffEntryFromDiffTypeGitAction(git, diffType)

        var diffResult: DiffResult
        val submoduleStatus = submodules[diffEntry.filePath]

        if (submoduleStatus != null) {
            diffResult = DiffResult.Submodule(diffEntry, submoduleStatus)
        } else {
            val oldTree: DirCacheIterator?
            val newTree: FileTreeIterator?

            if (diffType is DiffType.UncommittedDiff && diffType.entryType == EntryType.UNSTAGED) {
                oldTree = DirCacheIterator(repository.readDirCache())
                newTree = FileTreeIterator(repository)
            } else {
                oldTree = null
                newTree = null
            }

            val diffContent = getDiffContentGitAction(repository, diffEntry, oldTree, newTree)
            val fileHeader = diffContent.fileHeader

            val rawOld = diffContent.rawOld
            val rawNew = diffContent.rawNew

            if (rawOld == EntryContent.InvalidObjectBlob || rawNew == EntryContent.InvalidObjectBlob) {
                throw InvalidObjectException("Invalid object in diff format")
            } else if (rawOld == EntryContent.Submodule || rawNew == EntryContent.Submodule) {
                diffResult = DiffResult.Submodule(diffEntry, null)
            } else {
                diffResult = DiffResult.Text(diffEntry, emptyList())

                // If we can, generate text diff (if one of the files has never been a binary file)
                val hasGeneratedTextDiff = canGenerateTextDiffGitAction(rawOld, rawNew) { oldRawText, newRawText ->
                    val hunks = formatHunksGitAction(fileHeader, oldRawText, newRawText, isDisplayFullFile)
                    val hunksDiffed = diffHunksParts(hunks)
                    diffResult =
                        DiffResult.Text(
                            diffEntry,
                            hunksDiffed,
                        )
                }

                if (!hasGeneratedTextDiff) {
                    diffResult = DiffResult.NonText(diffEntry, rawOld, rawNew)
                }
            }
        }

        diffResult
    }

    private suspend fun diffHunksParts(hunks: List<Hunk>): List<Hunk> = withContext(Dispatchers.Default) {
        val newHunksList = MutableList<Hunk>(hunks.count(), { Hunk("", emptyList()) })

        hunks.mapIndexed { index, hunk ->
            launch {
                val newHunk = textDiffFromDiffLinesGitAction(hunk.lines)

                synchronized(newHunksList) {
                    newHunksList[index] = hunk.copy(lines = newHunk)
                }
            }
        }.joinAll()

        newHunksList
    }
}