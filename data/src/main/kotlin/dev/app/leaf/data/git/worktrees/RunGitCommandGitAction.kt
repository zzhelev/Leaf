// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

class RunGitCommandGitAction @Inject constructor(
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
) : IRunGitCommandGitAction {
    // No time limit: commands such as `git worktree add` run hooks and LFS filters, which can take long. Those that may
    // check out or stage files go through GitCliRemoteCommand, for Leaf's LFS filter where git-lfs isn't set up, its
    // dialogs and Cancel. The others don't, such as `git worktree remove`, which Cancel would stop halfway
    override suspend operator fun invoke(folder: String, args: List<String>): Either<String, GitError> {
        return if (checksOutOrStages(args)) {
            remoteCommand.runLocal(File(folder), args)
        } else {
            gitCli.run(File(folder), args, timeout = Duration.INFINITE)
        }
    }
}

/** git's commands that may check out or stage files, and so run git's filters, LFS's included. */
private val CHECKOUT_COMMANDS = setOf(
    "add", "am", "checkout", "checkout-index", "cherry-pick", "commit", "merge", "mergetool", "pull", "read-tree",
    "rebase", "reset", "restore", "revert", "stash", "switch",
)

/** git's options before the command that take the next argument as their value. */
private val OPTIONS_WITH_VALUE = setOf("-c", "-C")

/**
 * Whether `git <args>` may check out or stage files (fork-only): one of [CHECKOUT_COMMANDS], `git worktree add`, or a
 * `git sparse-checkout` that changes what the working tree has. git's options before the command, such as `-c` and
 * `--literal-pathspecs`, are skipped.
 */
internal fun checksOutOrStages(args: List<String>): Boolean {
    var i = 0

    while (i < args.size && args[i].startsWith("-")) {
        i += if (args[i] in OPTIONS_WITH_VALUE) 2 else 1
    }

    val command = args.getOrNull(i) ?: return false
    val subcommand = args.getOrNull(i + 1)

    return when (command) {
        "worktree" -> subcommand == "add"
        "sparse-checkout" -> subcommand != "list"
        else -> command in CHECKOUT_COMMANDS
    }
}
