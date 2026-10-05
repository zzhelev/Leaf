package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.domain.interfaces.IStageHunkGitAction
import dev.app.leaf.domain.models.EntryContent
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.DiffEntry
import java.nio.ByteBuffer
import javax.inject.Inject

class StageHunkGitAction @Inject constructor(
    private val jgit: JGit,
    private val rawFileManager: RawFileManager,
    private val getLinesFromRawTextGitAction: GetLinesFromRawTextGitAction,
) : IStageHunkGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        diffEntry: DiffEntry,
        hunk: Hunk
    ) = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val dirCache = repository.lockDirCache()
        val dirCacheEditor = dirCache.editor()
        var completedWithErrors = true

        try {
            val entryContent = rawFileManager.getRawContent(
                repository = git.repository,
                side = DiffEntry.Side.OLD,
                entry = diffEntry,
                oldTreeIterator = null,
                newTreeIterator = null
            )

            if (entryContent !is EntryContent.Text)
                return@provide

            val textLines = getLinesFromRawTextGitAction(entryContent.rawText).toMutableList()

            val hunkLines = hunk.lines.filter { it.lineType != LineType.CONTEXT }

            var linesAdded = 0
            for (line in hunkLines) {
                when (line.lineType) {
                    LineType.ADDED -> {
                        textLines.add(line.oldLineNumber + linesAdded, line.text)
                        linesAdded++
                    }

                    LineType.REMOVED -> {
                        textLines.removeAt(line.oldLineNumber + linesAdded)
                        linesAdded--
                    }

                    else -> throw NotImplementedError("Line type not implemented for stage hunk")
                }
            }

            val stagedFileText = textLines.joinToString("")
            dirCacheEditor.add(
                HunkEdit(
                    diffEntry.newPath,
                    repository,
                    ByteBuffer.wrap(stagedFileText.toByteArray())
                )
            )
            dirCacheEditor.commit()

            completedWithErrors = false
        } finally {
            if (completedWithErrors)
                dirCache.unlock()
        }
    }
}
