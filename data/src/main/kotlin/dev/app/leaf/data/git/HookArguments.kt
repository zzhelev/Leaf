// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.hooks.CommitMsgHook
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * The arguments JGit passes to the hook [hookName], except that commit-msg gets the message file's absolute path when
 * JGit passes an empty one.
 *
 * JGit makes that path relative to the working tree with `Repository.stripWorkDir`, which returns `""` for a file
 * outside it. A linked worktree's git dir (`<main>/.git/worktrees/<name>`) and a submodule's (`<parent>/.git/modules/
 * <name>`) are outside it, so the hook got an empty `$1`. JGit still writes the message to `<git dir>/COMMIT_EDITMSG`
 * and reads it back after the hook, and the git CLI gives the hook that file's absolute path. In a regular repository
 * the hook still gets `.git/COMMIT_EDITMSG`. [formatPath] writes the path the way the shell that runs the hook expects.
 */
internal fun hookArguments(
    repository: Repository,
    hookName: String,
    args: Array<out String?>,
    formatPath: (File) -> String = File::getAbsolutePath,
): Array<out String?> {
    if (hookName != CommitMsgHook.NAME || args.firstOrNull() != "") {
        return args
    }

    val messageFile = File(repository.directory, Constants.COMMIT_EDITMSG)

    return arrayOf(formatPath(messageFile), *args.copyOfRange(1, args.size))
}
