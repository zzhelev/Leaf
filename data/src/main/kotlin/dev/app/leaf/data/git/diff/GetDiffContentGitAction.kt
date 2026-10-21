package dev.app.leaf.data.git.diff

import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.EntryContent
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.domain.interfaces.IGetDiffContentGitAction
import dev.app.leaf.domain.models.DiffContent
import dev.app.leaf.domain.models.DiffWhitespaceMode
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.InvalidObjectException
import javax.inject.Inject

class GetDiffContentGitAction @Inject constructor(
    private val rawFileManager: RawFileManager,
) : IGetDiffContentGitAction {
    override operator fun invoke(
        repository: Repository,
        diffEntry: DiffEntry,
        oldTreeIterator: AbstractTreeIterator?,
        newTreeIterator: AbstractTreeIterator?,
        whitespace: DiffWhitespaceMode,
    ): DiffContent {
        val outputStream = ByteArrayOutputStream() // Dummy output stream used for the diff formatter
        outputStream.use {
            val diffFormatter = DiffFormatter(outputStream).apply {
                setRepository(repository)
                pathFilter = PathFilter.create(diffEntry.filePath)
                // Fork-only: which lines count as equal, so that the edits leave out the ones that only differ in whitespace
                setDiffComparator(whitespace.comparator())
            }

            if (oldTreeIterator != null && newTreeIterator != null) {
                diffFormatter.scan(oldTreeIterator, newTreeIterator)
            }

            val fileHeader = diffFormatter.toFileHeader(diffEntry)

            val rawOld = rawFileManager.getRawContent(
                repository,
                DiffEntry.Side.OLD,
                diffEntry,
                oldTreeIterator,
                newTreeIterator
            )
            val rawNew = rawFileManager.getRawContent(
                repository,
                DiffEntry.Side.NEW,
                diffEntry,
                oldTreeIterator,
                newTreeIterator
            )

            if (rawOld == EntryContent.InvalidObjectBlob || rawNew == EntryContent.InvalidObjectBlob)
                throw InvalidObjectException("Invalid object in diff format")

            return DiffContent(fileHeader, rawOld, rawNew)
        }
    }
}

/**
 * What JGit compares lines with. [DiffWhitespaceMode.IGNORE_ALL] is `git diff -w` and
 * [DiffWhitespaceMode.IGNORE_CHANGE] is `git diff -b`: the edits that JGit finds match git's on every pair that
 * `DiffWhitespaceTest` compares.
 */
internal fun DiffWhitespaceMode.comparator(): RawTextComparator = when (this) {
    DiffWhitespaceMode.SHOW_ALL -> RawTextComparator.DEFAULT
    DiffWhitespaceMode.IGNORE_CHANGE -> RawTextComparator.WS_IGNORE_CHANGE
    DiffWhitespaceMode.IGNORE_ALL -> RawTextComparator.WS_IGNORE_ALL
}
