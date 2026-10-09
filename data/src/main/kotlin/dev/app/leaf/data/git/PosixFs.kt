// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.shell.LoginShellEnvironment
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import org.eclipse.jgit.util.ProcessResult
import java.io.File
import java.io.OutputStream

/**
 * JGit's file system for macOS and Linux, except that what JGit runs in a shell gets the environment of the user's
 * login shell ([LoginShellEnvironment]). That covers hooks, clean and smudge filters, and diff and merge tools. JGit
 * sets its own variables, such as `GIT_DIR` for hooks, after [runInShell], so they still win.
 *
 * Also gives commit-msg its message file in linked worktrees and submodules, where JGit passes an empty path
 * ([hookArguments]), and keeps a linked worktree's HEAD reflog in its own git dir ([linkedWorktreeLogs]).
 */
class PosixFs : FS_POSIX {
    private val loginShellEnvironment: LoginShellEnvironment
    private val linkedWorktreeLogs: LinkedWorktreeLogs?

    constructor(
        loginShellEnvironment: LoginShellEnvironment,
        linkedWorktreeLogs: LinkedWorktreeLogs? = null,
    ) : super() {
        this.loginShellEnvironment = loginShellEnvironment
        this.linkedWorktreeLogs = linkedWorktreeLogs
    }

    private constructor(source: PosixFs) : super(source) {
        loginShellEnvironment = source.loginShellEnvironment
        linkedWorktreeLogs = source.linkedWorktreeLogs
    }

    override fun newInstance(): FS = PosixFs(this)

    override fun resolve(dir: File?, name: String): File =
        linkedWorktreeLogs?.resolve(dir, name) ?: super.resolve(dir, name)

    override fun runInShell(cmd: String, args: Array<out String>): ProcessBuilder {
        return super.runInShell(cmd, args).apply {
            environment().putAll(loginShellEnvironment.variablesBlocking())
        }
    }

    override fun runHookIfPresent(
        repository: Repository,
        hookName: String,
        args: Array<out String?>,
        outRedirect: OutputStream?,
        errRedirect: OutputStream?,
        stdinArgs: String?,
    ): ProcessResult {
        val hookArgs = hookArguments(repository, hookName, args)

        return super.runHookIfPresent(repository, hookName, hookArgs, outRedirect, errRedirect, stdinArgs)
    }
}
