// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli

import dev.app.leaf.common.printLog
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GitCli"

private val DEFAULT_TIMEOUT = 30.seconds

/**
 * Environment for git CLI processes: output that can be parsed, no prompts that would block, and no optional locks,
 * so that refreshing the status never competes for `index.lock` with git processes started by other tools.
 */
internal val gitCliEnvironment: Map<String, String?> = mapOf(
    "LC_ALL" to "C",
    "GIT_TERMINAL_PROMPT" to "0",
    "GIT_OPTIONAL_LOCKS" to "0",
    // Inherited variables that would point git to a different repository than the working directory's
    "GIT_DIR" to null,
    "GIT_WORK_TREE" to null,
    "GIT_COMMON_DIR" to null,
    "GIT_INDEX_FILE" to null,
    "GIT_OBJECT_DIRECTORY" to null,
    "GIT_ALTERNATE_OBJECT_DIRECTORIES" to null,
    "GIT_NAMESPACE" to null,
)

/** What a git command printed, and how it exited. */
data class GitCliOutput(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs the git CLI, for the operations JGit doesn't support (such as linked worktrees). Git gets the login shell's
 * environment, as the hooks and filters it runs (`post-checkout`, `git-lfs`) need the user's PATH.
 */
@Singleton
class GitCli @Inject constructor(
    private val gitExecutableLocator: GitExecutableLocator,
    private val processRunner: ProcessRunner,
    private val appSettingsService: AppSettingsService,
    private val loginShellEnvironment: LoginShellEnvironment,
) {
    /** Runs `git <args>` in [workingDirectory], returning its stdout if it exits with code 0. */
    suspend fun run(
        workingDirectory: File,
        args: List<String>,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): Either<String, GitCliError> {
        val output = when (val result = execute(workingDirectory, args, timeout)) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        return if (output.exitCode == 0) {
            Either.Ok(output.stdout)
        } else {
            Either.Err(GitCliError.CommandFailed(describe(args), output.exitCode, output.stderr.trim()))
        }
    }

    /**
     * Runs `git <args>` like [run], but returns the output whatever the exit code, for commands whose failures are read
     * from it: `git push --porcelain` lists the refs that the remote refused, and exits with 1.
     *
     * @param environment variables added after Leaf's own, such as the askpass helper's.
     * @param input written to git's stdin, such as the patch that `git apply` reads.
     * @param onStderr receives stderr as git writes it, for progress.
     * @param onStdout receives stdout as git writes it, where git-lfs writes its progress.
     */
    suspend fun execute(
        workingDirectory: File,
        args: List<String>,
        timeout: Duration = DEFAULT_TIMEOUT,
        environment: Map<String, String?> = emptyMap(),
        input: ByteArray? = null,
        onStderr: ((String) -> Unit)? = null,
        onStdout: ((String) -> Unit)? = null,
    ): Either<GitCliOutput, GitCliError> {
        val commandDescription = describe(args)

        val configuredPath = appSettingsService.gitExecutablePath.firstOrNull()
        val executable = when (val result = gitExecutableLocator.locate(configuredPath)) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        if (!workingDirectory.isDirectory) {
            return Either.Err(GitCliError.StartFailed(commandDescription, "$workingDirectory is not a directory"))
        }

        printLog(TAG, "Running '$commandDescription' in $workingDirectory")

        // Leaf's own variables come last, so that the shell's (for example its locale) can't override them
        val processEnvironment = loginShellEnvironment.variables() + gitCliEnvironment + environment

        val outcome = try {
            processRunner.run(
                command = listOf(executable.path) + args,
                workingDirectory = workingDirectory,
                environment = processEnvironment,
                timeout = timeout,
                input = input,
                onStderr = onStderr,
                onStdout = onStdout,
            )
        } catch (e: IOException) {
            gitExecutableLocator.invalidate()
            return Either.Err(GitCliError.StartFailed(commandDescription, e.message.orEmpty()))
        }

        return when (outcome) {
            ProcessOutcome.TimedOut -> Either.Err(GitCliError.TimedOut(commandDescription, timeout.inWholeSeconds))
            is ProcessOutcome.Completed -> Either.Ok(GitCliOutput(outcome.exitCode, outcome.stdout, outcome.stderr))
        }
    }

    private fun describe(args: List<String>) = (listOf("git") + args.map(::redactUrlPassword)).joinToString(" ")
}

private val URL_PASSWORD = Regex("""(://[^/@:\s]*):[^/@\s]*@""")

/**
 * The argument with the password of a URL in it hidden, as a clone's URL may have one (`https://user:token@host/...`),
 * and the command goes into the log and into error messages.
 */
internal fun redactUrlPassword(argument: String): String = argument.replace(URL_PASSWORD, "$1:***@")
