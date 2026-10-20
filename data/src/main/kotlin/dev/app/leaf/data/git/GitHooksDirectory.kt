// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FS
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GitHooksDirectory"
private val REV_PARSE_TIMEOUT = 10.seconds

/** Where a repository's hooks are, for [PosixFs] and [WindowsFs]. */
fun interface HooksDirectory {
    /** The folder that [repository]'s hooks are in, or null to look for them as JGit does. */
    fun of(repository: Repository): File?
}

/**
 * Asks git where a repository's hooks are, with `git rev-parse --path-format=absolute --git-path hooks`. JGit reads
 * `core.hooksPath` from its own config, which skips the files that `includeIf` names (upstream issue 186), and takes
 * the value as it is, so `~/hooks` was a folder named `~` in the working tree. git's answer has both, a relative path
 * from the working tree's root, and the common git dir's `hooks` in a linked worktree.
 */
class GitHooksDirectory @Inject constructor(
    private val gitCli: GitCli,
) : HooksDirectory {
    override fun of(repository: Repository): File? {
        // With --git-dir, git takes the folder it runs in as the working tree's root
        val runDirectory = if (repository.isBare) repository.directory else repository.workTree
        val args = listOf(
            "--git-dir=${repository.directory.absolutePath}",
            "rev-parse",
            "--path-format=absolute",
            "--git-path",
            "hooks",
        )

        // JGit looks for a hook while it commits, and waits for the hook anyway
        return when (val result = runBlocking { gitCli.run(runDirectory, args, REV_PARSE_TIMEOUT) }) {
            is Either.Ok -> File(result.value.trimEnd('\n')).takeIf { it.isAbsolute }
            is Either.Err -> {
                printError(TAG, "Looking for hooks as JGit does, as git couldn't tell where they are: ${result.error}")
                null
            }
        }
    }
}

/**
 * The hook [hookName] in [folder], or null when there is none: missing, or not executable where this file system can
 * tell, as JGit's [FS.findHook] checks it. git skips such a hook too.
 */
internal fun FS.hookIn(folder: File, hookName: String): File? {
    val hook = File(folder, hookName)

    return hook.takeIf { it.exists() && (!supportsExecute() || canExecute(it)) }
}
