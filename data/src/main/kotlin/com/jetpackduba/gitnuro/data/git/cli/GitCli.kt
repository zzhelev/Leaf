package com.jetpackduba.gitnuro.data.git.cli

import com.jetpackduba.gitnuro.common.printLog
import com.jetpackduba.gitnuro.domain.errors.Either
import com.jetpackduba.gitnuro.domain.errors.GitCliError
import com.jetpackduba.gitnuro.domain.services.AppSettingsService
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

/** Runs the git CLI, for the operations JGit doesn't support (such as linked worktrees). */
@Singleton
class GitCli @Inject constructor(
    private val gitExecutableLocator: GitExecutableLocator,
    private val processRunner: ProcessRunner,
    private val appSettingsService: AppSettingsService,
) {
    /** Runs `git <args>` in [workingDirectory], returning its stdout if it exits with code 0. */
    suspend fun run(
        workingDirectory: File,
        args: List<String>,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): Either<String, GitCliError> {
        val commandDescription = (listOf("git") + args).joinToString(" ")

        val configuredPath = appSettingsService.gitExecutablePath.firstOrNull()
        val executable = when (val result = gitExecutableLocator.locate(configuredPath)) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        if (!workingDirectory.isDirectory) {
            return Either.Err(GitCliError.StartFailed(commandDescription, "$workingDirectory is not a directory"))
        }

        printLog(TAG, "Running '$commandDescription' in $workingDirectory")

        val outcome = try {
            processRunner.run(listOf(executable.path) + args, workingDirectory, gitCliEnvironment, timeout)
        } catch (e: IOException) {
            gitExecutableLocator.invalidate()
            return Either.Err(GitCliError.StartFailed(commandDescription, e.message.orEmpty()))
        }

        return when (outcome) {
            ProcessOutcome.TimedOut -> Either.Err(GitCliError.TimedOut(commandDescription, timeout.inWholeSeconds))
            is ProcessOutcome.Completed -> if (outcome.exitCode == 0) {
                Either.Ok(outcome.stdout)
            } else {
                Either.Err(GitCliError.CommandFailed(commandDescription, outcome.exitCode, outcome.stderr.trim()))
            }
        }
    }
}
