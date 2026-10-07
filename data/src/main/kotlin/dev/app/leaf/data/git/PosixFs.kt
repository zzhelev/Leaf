// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.shell.LoginShellEnvironment
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX

/**
 * JGit's file system for macOS and Linux, except that what JGit runs in a shell gets the environment of the user's
 * login shell ([LoginShellEnvironment]). That covers hooks, clean and smudge filters, and diff and merge tools. JGit
 * sets its own variables, such as `GIT_DIR` for hooks, after [runInShell], so they still win.
 */
class PosixFs : FS_POSIX {
    private val loginShellEnvironment: LoginShellEnvironment

    constructor(loginShellEnvironment: LoginShellEnvironment) : super() {
        this.loginShellEnvironment = loginShellEnvironment
    }

    private constructor(source: PosixFs) : super(source) {
        loginShellEnvironment = source.loginShellEnvironment
    }

    override fun newInstance(): FS = PosixFs(this)

    override fun runInShell(cmd: String, args: Array<out String>): ProcessBuilder {
        return super.runInShell(cmd, args).apply {
            environment().putAll(loginShellEnvironment.variablesBlocking())
        }
    }
}
