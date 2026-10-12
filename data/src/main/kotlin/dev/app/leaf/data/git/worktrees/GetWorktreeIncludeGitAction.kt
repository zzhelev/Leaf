// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetWorktreeIncludeGitAction
import dev.app.leaf.domain.models.WorktreeInclude
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/** The file that lists the gitignored files to copy into new worktrees, which several agent tools read. */
const val WORKTREE_INCLUDE_FILE = ".worktreeinclude"

/** Listing untracked files walks every untracked folder, such as node_modules, so it gets more time than usual. */
private val LIST_TIMEOUT = 2.minutes

class GetWorktreeIncludeGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetWorktreeIncludeGitAction {
    override suspend operator fun invoke(mainWorktreePath: String): Either<WorktreeInclude?, GitError> = either {
        val main = File(mainWorktreePath)
        val include = File(main, WORKTREE_INCLUDE_FILE)

        if (!include.isFile) {
            return@either Either.Ok(null)
        }

        // The untracked files that the file's patterns match, and nothing else: no .gitignore applies here. A nested
        // repository or worktree (.claude/worktrees/x) is listed as its folder, with a final /, and never entered.
        val listArgs = listOf("ls-files", "-z", "--others", "--ignored", "--exclude-from=${include.absolutePath}")
        val matched = gitCli.run(main, listArgs, LIST_TIMEOUT).bind()
            .split('\u0000')
            .filter { it.isNotEmpty() && !it.endsWith("/") }

        if (matched.isEmpty()) {
            return@either Either.Ok(WorktreeInclude(include.path, emptyList()))
        }

        // Of those, the ones that git ignores, which a new worktree doesn't get
        val checkArgs = listOf("check-ignore", "--stdin", "-z")
        val input = matched.joinToString("\u0000", postfix = "\u0000").toByteArray()
        val ignored = gitCli.execute(main, checkArgs, LIST_TIMEOUT, input = input).bind()

        // 1: none of them is ignored
        if (ignored.exitCode != 0 && ignored.exitCode != 1) {
            return@either Either.Err(
                GitCliError.CommandFailed("git " + checkArgs.joinToString(" "), ignored.exitCode, ignored.stderr.trim())
            )
        }

        val files = ignored.stdout.split('\u0000').filter { it.isNotEmpty() }.sorted()

        Either.Ok(WorktreeInclude(include.path, files))
    }
}
