package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.cli.remote.blobIds
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryContent
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IFormatDiffGitAction
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffWhitespaceMode
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
    private val partialClone: PartialCloneFetch,
) : IFormatDiffGitAction {
    // Fork-only: in a partial clone, git downloads the versions that JGit doesn't find, and the diff runs again
    override suspend operator fun invoke(
        repositoryPath: String,
        diffType: DiffType,
        isDisplayFullFile: Boolean,
        whitespace: DiffWhitespaceMode,
    ) = partialClone.retryingMissing(repositoryPath, alsoNeeded = { diffType.blobIds() }) {
        format(repositoryPath, diffType, isDisplayFullFile, whitespace)
    }

    private suspend fun format(
        repositoryPath: String,
        diffType: DiffType,
        isDisplayFullFile: Boolean,
        whitespace: DiffWhitespaceMode,
    ) = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        // Fork-only: JGit lists submodules from the index, which it can't read when git wrote it split or sparse. A
        // commit's diff doesn't need it, so it goes on without them.
        val submodulesResult = getSubmodulesGitAction(repositoryPath)
        val unreadableIndex = (submodulesResult as? Either.Err)?.error is UnsupportedIndexError
        val submodules = if (unreadableIndex && diffType is DiffType.CommitDiff) emptyMap() else submodulesResult.bind()

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

            val diffContent = getDiffContentGitAction(repository, diffEntry, oldTree, newTree, whitespace)
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
                    val hunks = formatHunksGitAction(fileHeader, oldRawText, newRawText, isDisplayFullFile, whitespace)
                    val hunksDiffed = diffHunksParts(hunks)
                    // Fork-only: no hunk, though the versions differ: only whitespace changed
                    val onlyWhitespaceChanged = whitespace.ignoresWhitespace &&
                            hunks.isEmpty() &&
                            !oldRawText.rawContent.contentEquals(newRawText.rawContent)
                    diffResult =
                        DiffResult.Text(
                            diffEntry,
                            hunksDiffed,
                            onlyWhitespaceChanged,
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
        val newHunksList = hunks.toMutableList()

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