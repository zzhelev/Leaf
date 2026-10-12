// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetPrunableWorktreesGitAction
import java.io.File
import javax.inject.Inject

class GetPrunableWorktreesGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetPrunableWorktreesGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<List<String>, GitError> = either {
        val args = listOf("worktree", "prune", "--dry-run", "-v")
        val output = gitCli.execute(File(repositoryPath), args).bind()

        if (output.exitCode != 0) {
            return@either Either.Err(
                GitCliError.CommandFailed("git " + args.joinToString(" "), output.exitCode, output.stderr.trim())
            )
        }

        Either.Ok(parsePruneLines(output.stdout + "\n" + output.stderr))
    }
}

/** The lines in which `git worktree prune -v` names a worktree that it removes (git writes them to stderr). */
internal fun parsePruneLines(output: String): List<String> = output
    .lines()
    .map { it.trim() }
    .filter { it.startsWith("Removing ") }
