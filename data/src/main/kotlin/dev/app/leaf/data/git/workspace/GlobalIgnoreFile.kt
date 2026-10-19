// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.IgnoreError
import org.eclipse.jgit.lib.Repository
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GlobalIgnoreFile"
private val CONFIG_TIMEOUT = 10.seconds

/**
 * Finds the user's global ignore file (fork-only): `core.excludesFile` in their git config, as git reads it (`~`
 * expanded, `includeIf` followed), or else git's default, `$XDG_CONFIG_HOME/git/ignore` and `~/.config/git/ignore`
 * without it. Git takes both from the login shell's environment, as the processes Leaf runs do.
 *
 * Only the user's own config counts, `--global`: the repository's config can't point Leaf at a file to write. When
 * another config sets it as well, git reads that file instead, and the answer says so ([IgnoreTarget.problem]).
 */
class GlobalIgnoreFile @Inject constructor(
    private val gitCli: GitCli,
    private val loginShellEnvironment: LoginShellEnvironment,
) {
    suspend fun locate(repository: Repository): IgnoreTarget {
        val runDirectory = if (repository.isBare) repository.directory else repository.workTree
        val global = excludesFile(repository, runDirectory, "--global")

        // What git reads here: another config (the repository's, the system's) may set it as well
        val effective = excludesFile(repository, runDirectory)

        val file = File(global ?: defaultFile())
        val problem = when {
            effective != global && effective != null -> IgnoreError.GlobalListOverridden(effective)
            !file.isAbsolute -> IgnoreError.FileNotWritable(file.path, IgnoreError.Reason.NOT_ABSOLUTE)
            else -> IgnoreFiles.problemOf(file, followLinks = true)
        }

        return IgnoreTarget(file, problem, followLinks = true)
    }

    /** `core.excludesFile` as git reads it, with [scope] to read one config only; null when it isn't set. */
    private suspend fun excludesFile(repository: Repository, runDirectory: File, vararg scope: String): String? {
        val args = listOf("--git-dir=${repository.directory.absolutePath}", "config", *scope, "--type=path", "--get", "core.excludesFile")

        return when (val result = gitCli.execute(runDirectory, args, CONFIG_TIMEOUT)) {
            // Exit code 1 is "not set"
            is Either.Ok -> result.value.stdout.takeIf { result.value.exitCode == 0 }?.trim()?.takeIf { it.isNotEmpty() }
            is Either.Err -> {
                printError(TAG, "Reading core.excludesFile with git failed: ${result.error}")

                null
            }
        }
    }

    private suspend fun defaultFile(): String {
        val environment = loginShellEnvironment.variables()
        val home = environment["HOME"] ?: System.getenv("HOME") ?: System.getProperty("user.home")
        val xdgConfigHome = (environment["XDG_CONFIG_HOME"] ?: System.getenv("XDG_CONFIG_HOME"))?.takeIf { it.isNotEmpty() }

        return "${xdgConfigHome ?: "$home/.config"}/git/ignore"
    }
}
