// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.RemoteRefs
import dev.app.leaf.domain.models.remoteRefsOf
import java.io.File
import javax.inject.Inject

/**
 * Lists a remote's branches and tags with `git ls-remote --symref -- <url> HEAD <heads> <tags>`, the last two being
 * the patterns for everything under `refs/heads/` and `refs/tags/`, for the Clone dialog's "Choose…". It runs through
 * [GitCliRemoteCommand], so git's questions (credentials, host keys, passphrases) open Leaf's dialogs as a clone's do,
 * and a cancelled coroutine kills git.
 *
 * `--heads --tags` would leave out the `ref: refs/heads/main<TAB>HEAD` line that names the default branch, so the
 * patterns are given instead. They also keep the refs under `refs/pull/` and the like, which GitHub has many of, from
 * being sent. It runs in the temporary folder, which is no repository, so no repository's config
 * (`url.<base>.insteadOf`) applies.
 */
class GitCliListRemoteRefsGitAction @Inject constructor(
    private val remoteCommand: GitCliRemoteCommand,
) {
    suspend operator fun invoke(url: String): Either<RemoteRefs, GitError> {
        val result = remoteCommand.run(
            workingDirectory = File(System.getProperty("java.io.tmpdir")),
            args = listOf("ls-remote", "--symref", "--", url, "HEAD", "refs/heads/*", "refs/tags/*"),
            // The answer is the list: nothing goes to the processing screen of the tab
            onProgress = {},
        )

        val output = when (result) {
            is Either.Err -> return Either.Err(result.error)
            is Either.Ok -> result.value
        }

        if (output.exitCode != 0) {
            return Either.Err(remoteOperationError(output.exitCode, output.stderr))
        }

        return Either.Ok(parseLsRemote(output.stdout))
    }
}

/**
 * Reads `git ls-remote --symref`: `ref: <target><TAB>HEAD` names what HEAD points to, and every other line is
 * `<id><TAB><ref>`. An empty repository prints nothing.
 */
internal fun parseLsRemote(stdout: String): RemoteRefs {
    var headTarget: String? = null
    val refNames = mutableListOf<String>()

    for (line in stdout.lineSequence()) {
        val (left, name) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: continue

        if (left.startsWith("ref: ")) {
            if (name == "HEAD") headTarget = left.removePrefix("ref: ")
        } else {
            refNames += name
        }
    }

    return remoteRefsOf(headTarget, refNames)
}
