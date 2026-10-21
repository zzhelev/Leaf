// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.commitmessage.TrailerIfExists
import dev.app.leaf.domain.commitmessage.appendTrailer
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IAddTrailerGitAction
import java.io.File
import javax.inject.Inject

private const val TAG = "AddTrailerGitAction"

/**
 * Adds a trailer with `git interpret-trailers` (fork-only), which knows where git puts it: with the message's trailers
 * when its last paragraph is a trailer block, before its comment lines at the end, or after a blank line. It reads the
 * repository's `trailer.*` settings and comment character. `--if-exists` says when an equal trailer leaves it out.
 * Without a usable git, [appendTrailer] does the simple cases.
 */
class AddTrailerGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IAddTrailerGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        message: String,
        trailer: String,
        ifExists: TrailerIfExists,
    ): Either<String, GitError> {
        val gitDir = File(repositoryPath)
        val args = listOf(
            "--git-dir=${gitDir.absolutePath}",
            "interpret-trailers",
            "--if-exists",
            ifExists.gitValue,
            "--trailer",
            trailer,
        )

        val output = when (val result = gitCli.execute(gitDir, args, input = message.toByteArray())) {
            is Either.Ok -> result.value.takeIf { it.exitCode == 0 }
            is Either.Err -> null
        }

        if (output == null) {
            printError(TAG, "Adding the trailer without git, which couldn't run interpret-trailers")
        }

        return Either.Ok(output?.stdout ?: appendTrailer(message, trailer, ifExists))
    }
}
