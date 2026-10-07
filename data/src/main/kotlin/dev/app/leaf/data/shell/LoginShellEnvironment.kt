// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.shell

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.cli.ProcessOutcome
import dev.app.leaf.data.git.cli.ProcessRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val TAG = "LoginShellEnvironment"

private val RESOLVE_TIMEOUT = 10.seconds

/** Set while Leaf runs the login shell, so that slow shell startup files can skip what Leaf doesn't need. */
internal const val RESOLVING_VARIABLE = "LEAF_RESOLVING_SHELL_ENVIRONMENT"

/**
 * Variables that describe the shell process itself, or that would point git at a different repository than the one
 * Leaf runs it for.
 */
private val IGNORED_VARIABLES = setOf(
    "PWD",
    "OLDPWD",
    "SHLVL",
    "_",
    "TERM",
    "COLUMNS",
    "LINES",
    RESOLVING_VARIABLE,
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_COMMON_DIR",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_NAMESPACE",
)

/**
 * The environment that the user's login shell sets up, for the hooks, filters and git processes that Leaf starts.
 *
 * An app started from the macOS Finder or Dock, or from a Linux desktop launcher, inherits a minimal environment
 * without the changes made in the shell's startup files, so a hook can't find tools such as node (Gitnuro#236). Leaf
 * runs the login shell once, in the background, and adds the variables it prints to those processes. An app started
 * from a terminal already has them, so then no shell is run.
 */
@Singleton
class LoginShellEnvironment internal constructor(resolve: suspend () -> Map<String, String>) {
    @Inject
    constructor(processRunner: ProcessRunner) : this({
        resolveLoginShellEnvironment(currentOs, System.getenv(), processRunner, RESOLVE_TIMEOUT)
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val variables = scope.async(start = CoroutineStart.LAZY) {
        try {
            resolve()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            printError(TAG, "Could not read the login shell's environment", e)
            emptyMap()
        }
    }

    /** Starts running the login shell, so that the first hook doesn't have to wait for it. */
    fun prewarm() {
        variables.start()
    }

    /** Variables to add to the inherited environment of a process; empty when there is nothing to add. */
    suspend fun variables(): Map<String, String> = variables.await()

    /** [variables] for callers that can't suspend, such as JGit running a hook. */
    fun variablesBlocking(): Map<String, String> = runBlocking { variables.await() }
}

/**
 * Runs the login shell of the user to read its environment, returning the variables that differ from [inherited].
 * Returns an empty map when it doesn't apply (Windows, or started from a terminal) or when the shell fails.
 */
internal suspend fun resolveLoginShellEnvironment(
    os: OS,
    inherited: Map<String, String>,
    processRunner: ProcessRunner,
    timeout: Duration,
): Map<String, String> {
    if (os != OS.MAC && os != OS.LINUX) {
        return emptyMap()
    }

    // Terminals set TERM and desktop launchers don't, so this process already has the shell's environment
    if (inherited.containsKey("TERM")) {
        printLog(TAG, "Started from a terminal, keeping the inherited environment")
        return emptyMap()
    }

    val shell = inherited["SHELL"]?.takeIf { it.isNotBlank() } ?: if (os == OS.MAC) "/bin/zsh" else "/bin/sh"
    // Shell startup files may print to stdout (prompts, terminal escape codes), so the environment sits between markers
    val marker = UUID.randomUUID().toString()
    val script = "printf '%s' '$marker'; /usr/bin/env -0; printf '%s' '$marker'"
    val command = listOf(shell) + loginShellArguments(shell) + script
    val workingDirectory = inherited["HOME"]?.let { File(it) }?.takeIf { it.isDirectory }
    val start = TimeSource.Monotonic.markNow()

    val outcome = try {
        processRunner.run(command, workingDirectory, mapOf(RESOLVING_VARIABLE to "1"), timeout)
    } catch (e: IOException) {
        printError(TAG, "Could not start the login shell $shell: ${e.message}")
        return emptyMap()
    }

    return when (outcome) {
        ProcessOutcome.TimedOut -> {
            printError(TAG, "The login shell $shell did not finish within $timeout, keeping the inherited environment")
            emptyMap()
        }

        is ProcessOutcome.Completed -> {
            val shellVariables = parseEnvironmentOutput(outcome.stdout, marker)

            if (shellVariables == null) {
                printError(
                    TAG,
                    "The login shell $shell exited with code ${outcome.exitCode} without printing its environment: " +
                            outcome.stderr.trim().take(500)
                )
                emptyMap()
            } else {
                val added = environmentToAdd(inherited, shellVariables)
                // Only names are logged, values can hold secrets
                val pathNote = if ("PATH" in added) ", PATH among them" else ""
                printLog(TAG, "Adding ${added.size} variables from $shell$pathNote, took ${start.elapsedNow()}")
                added
            }
        }
    }
}

/** Arguments that make [shell] run a command as an interactive login shell, which reads all its startup files. */
internal fun loginShellArguments(shell: String): List<String> {
    return when (File(shell).name) {
        // csh and tcsh only accept -l as the sole argument
        "csh", "tcsh" -> listOf("-i", "-c")
        else -> listOf("-i", "-l", "-c")
    }
}

/** Reads the NUL-separated output of `env -0` printed between two [marker]s, or null when the markers are missing. */
internal fun parseEnvironmentOutput(output: String, marker: String): Map<String, String>? {
    val start = output.indexOf(marker)
    val end = if (start < 0) -1 else output.indexOf(marker, start + marker.length)

    if (end < 0) {
        return null
    }

    return output.substring(start + marker.length, end)
        .split('\u0000')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')

            if (separator <= 0) {
                null
            } else {
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
        }
        .toMap()
}

/** The variables of [shellVariables] that are worth adding to [inherited]: new or changed, and not ignored. */
internal fun environmentToAdd(
    inherited: Map<String, String>,
    shellVariables: Map<String, String>,
): Map<String, String> {
    return shellVariables.filter { (name, value) -> name !in IGNORED_VARIABLES && inherited[name] != value }
}
