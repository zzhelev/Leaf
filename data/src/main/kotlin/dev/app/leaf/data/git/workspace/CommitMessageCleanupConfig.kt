// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.commitmessage.CleanupMode
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommitMessageCleanupGitAction
import org.eclipse.jgit.lib.Config
import javax.inject.Inject

private const val TAG = "CommitMessageCleanup"

/** git's values of `commit.cleanup`, which it compares as they are (`git commit` refuses any other). */
private val CLEANUP_MODES = mapOf(
    "default" to CleanupMode.STRIP,
    "strip" to CleanupMode.STRIP,
    "whitespace" to CleanupMode.WHITESPACE,
    "verbatim" to CleanupMode.VERBATIM,
    "scissors" to CleanupMode.SCISSORS,
)

/**
 * How git cleans up a message committed from Leaf's field (fork-only), from this config: `commit.cleanup`, and
 * `core.commentString` or `core.commentChar` (git 2.45 reads both into one setting, and takes any string). The
 * repository's config, through `IncludedConfigRepository`, has what the files that `includeIf` names set.
 *
 * - git takes the last of the two keys that the files set. JGit can't tell their order across files, so
 *   `core.commentString` wins when both are set.
 * - A value that git refuses (an unknown mode, an empty comment prefix, one with a line feed) makes `git commit` fail.
 *   Leaf uses git's default for it instead, and logs it.
 */
fun Config.messageCleanup(): MessageCleanup {
    val modeValue = getString("commit", null, "cleanup")
    val mode = when (modeValue) {
        null -> CleanupMode.STRIP
        else -> CLEANUP_MODES[modeValue] ?: CleanupMode.STRIP.also {
            printError(TAG, "Cleaning messages up with git's default, as commit.cleanup is $modeValue")
        }
    }

    val commentValue = getString("core", null, "commentString") ?: getString("core", null, "commentChar")
    val commentPrefix = when {
        commentValue == null -> MessageCleanup.DEFAULT.commentPrefix
        commentValue.equals("auto", ignoreCase = true) -> null
        commentValue.isEmpty() || commentValue.contains('\n') -> MessageCleanup.DEFAULT.commentPrefix.also {
            printError(TAG, "Taking # as the comment prefix, as git refuses the one set")
        }

        else -> commentValue
    }

    return MessageCleanup(mode, commentPrefix)
}

class GetCommitMessageCleanupGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommitMessageCleanupGitAction {
    override suspend fun invoke(repositoryPath: String): Either<MessageCleanup, GitError> =
        jgit.provide(repositoryPath) { git ->
            git.repository.config.messageCleanup()
        }
}
