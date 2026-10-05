package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.interfaces.IDiscardUnstagedHunkLineGitAction
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.DiffEntry
import java.io.File
import javax.inject.Inject

class DiscardUnstagedHunkLineGitAction @Inject constructor(
    private val jgit: JGit,
    private val getLinesFromTextGitAction: GetLinesFromTextGitAction,
) : IDiscardUnstagedHunkLineGitAction {
    override suspend operator fun invoke(repositoryPath: String, diffEntry: DiffEntry, hunk: Hunk, line: Line) =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            try {
                val file = File(repository.workTree, diffEntry.filePath)
                val content = file.readText(Charsets.UTF_8)
                val textLines = getLinesFromTextGitAction(content).toMutableList()

                if (line.lineType == LineType.ADDED) {
                    textLines.removeAt(line.newLineNumber)
                } else if (line.lineType == LineType.REMOVED) {
                    val previousContextLine = hunk.lines
                        .takeWhile { it != line }
                        .lastOrNull { it.lineType == LineType.CONTEXT }

                    if (previousContextLine != null) {
                        textLines.add(previousContextLine.newLineNumber + 1, line.text)
                    } else {
                        val previousAddedLine = hunk.lines
                            .takeWhile { it != line }
                            .lastOrNull { it.lineType == LineType.ADDED }

                            if (previousAddedLine != null) {
                                textLines.add(previousAddedLine.newLineNumber + 1, line.text)
                            } else {
                                textLines.add(0, line.text)
                            }
                    }
                }

                val resultText = textLines.joinToString("")
                file.writeText(resultText, Charsets.UTF_8)

            } catch (ex: Exception) {
                throw Exception(
                    "Discard hunk line failed. Check if the file still exists and has the write permissions set",
                    ex
                )
            }
        }
}
