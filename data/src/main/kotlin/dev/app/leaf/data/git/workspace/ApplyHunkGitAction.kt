// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HunkAction
import dev.app.leaf.domain.errors.StaleHunkError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

/** What `git apply` prints (with `LC_ALL=C`) when the index or the file doesn't have what the patch expects. */
private val STALE_TARGET_MESSAGES = listOf(
    "patch does not apply",
    "does not exist in index",
    "already exists in index",
    "already exists in working directory",
    "No such file or directory",
)

/**
 * Stages, unstages or discards the [selected][invoke] lines of a hunk, as `git add -p`, `git reset -p` and
 * `git checkout -p` do: `git apply` applies their patch ([hunkPatch]) to the index, reversed to the index, or reversed
 * to the working tree. git writes nothing when the index or the file no longer has the lines that the diff showed
 * ([StaleHunkError]).
 */
class ApplyHunkGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) {
    suspend operator fun invoke(
        repositoryPath: String,
        diffEntry: DiffEntry,
        hunk: Hunk,
        action: HunkAction,
        selected: (Line) -> Boolean,
    ): Either<Unit, GitError> = either {
        val patch = hunkPatch(diffEntry, hunk, reverse = action != HunkAction.Stage, selected)
            ?: return@either Either.Ok(Unit)

        val workTree = jgit.provide(repositoryPath) { git -> git.repository.workTree }.bind()

        val args = listOf(
            // Context has to match exactly, whatever apply.ignoreWhitespace says
            "-c", "apply.ignoreWhitespace=no",
            "apply",
            // And the lines go in as they are: apply.whitespace=fix would change them, and error would refuse them
            "--whitespace=nowarn",
        ) + when (action) {
            HunkAction.Stage -> listOf("--cached")
            HunkAction.Unstage -> listOf("--cached", "-R")
            HunkAction.Discard -> listOf("-R")
        }

        val output = gitCli.execute(workTree, args, input = patch).bind()
        val stderr = output.stderr.trim()

        when {
            output.exitCode == 0 -> Either.Ok(Unit)
            STALE_TARGET_MESSAGES.any { it in stderr } -> raiseError(StaleHunkError(action, diffEntry.filePath, stderr))
            else -> raiseError(GitCliError.CommandFailed("git ${args.joinToString(" ")}", output.exitCode, stderr))
        }
    }
}

/** Whether [line] is [selectedLine], from the same hunk: a line's type and numbers tell it from the hunk's others. */
internal fun isSameLine(line: Line, selectedLine: Line) =
    line.lineType == selectedLine.lineType &&
        line.oldLineNumber == selectedLine.oldLineNumber &&
        line.newLineNumber == selectedLine.newLineNumber
