// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitCliOutput
import dev.app.leaf.data.git.cli.askpass.AskpassAnswers
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.withAskpassServer
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.CredentialsRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Runs git commands that talk to a remote:
 * - git and ssh ask through Leaf's dialogs, with the askpass helper ([AskpassAnswers]). The user's own credential
 *   helpers, ssh config, agent and known_hosts apply as in a terminal. `core.sshCommand` too, as Leaf never sets
 *   `GIT_SSH_COMMAND`.
 * - Leaf's in-memory cache is added as git's last credential helper, when "Cache HTTP credentials in memory" is on.
 * - git's progress goes to the processing screen, which can then cancel the command: cancelling the coroutine kills
 *   git and the programs it started. There is no timeout, as a push can take long.
 */
class GitCliRemoteCommand @Inject constructor(
    private val gitCli: GitCli,
    private val askpassHelper: AskpassHelper,
    private val credentialsStateManager: CredentialsStateManager,
    private val credentialsRepository: CredentialsRepository,
    private val appSettingsService: AppSettingsService,
    private val repositoryStateRepository: RepositoryStateRepository,
) {
    /**
     * Runs `git <args>` in [workingDirectory] and returns its output whatever the exit code, except when git failed
     * after the user closed one of its questions: that's [RemoteOperationError.PromptRefused], as git's own message
     * ("terminal prompts disabled") would mislead.
     *
     * @param authenticated tells from the output whether git got past authentication, so that the SSH passphrases the
     * user typed can be kept. By default, when git succeeded.
     * @param onProgress receives git's progress, then null once git is done. By default it goes to the processing
     * screen; a clone, which has no repository yet, shows it in its own dialog.
     */
    suspend fun run(
        workingDirectory: File,
        args: List<String>,
        authenticated: (GitCliOutput) -> Boolean = { it.exitCode == 0 },
        onProgress: (TaskProgress?) -> Unit = repositoryStateRepository::updateTaskProgress,
    ): Either<GitCliOutput, GitError> {
        val helper = askpassHelper.path() ?: return Either.Err(
            GitCliError.StartFailed("git ${args.joinToString(" ")}", "Leaf's askpass helper is missing")
        )

        val answers = AskpassAnswers(credentialsStateManager, credentialsRepository)
        val cacheHelper = if (appSettingsService.cacheCredentialsInMemory.first()) {
            listOf("-c", "credential.helper=${credentialHelperCommand(helper)}")
        } else {
            emptyList()
        }

        onProgress(TaskProgress(stage = null, percent = null))
        val progress = GitProgressParser {
            onProgress(TaskProgress(it.stage, it.percent))
        }
        // git-lfs writes its progress to stdout: `git lfs fetch`'s, and the pre-push hook's, which git passes on
        val stdoutProgress = GitProgressParser {
            onProgress(TaskProgress(it.stage, it.percent))
        }

        val result = try {
            withAskpassServer(answers::answer) { serverEnvironment ->
                gitCli.execute(
                    workingDirectory = workingDirectory,
                    args = cacheHelper + args,
                    timeout = Duration.INFINITE,
                    environment = askpassEnvironment(helper) + LFS_PROGRESS + serverEnvironment,
                    onStderr = progress::accept,
                    onStdout = stdoutProgress::accept,
                )
            }
        } finally {
            // git is done, so there is nothing left to cancel
            onProgress(null)
        }

        if (result !is Either.Ok) {
            return result
        }

        val output = result.value

        if (output.exitCode != 0 && answers.refused) {
            return Either.Err(RemoteOperationError.PromptRefused(readableGitOutput(output.stderr)))
        }

        if (authenticated(output)) {
            answers.commit()
        }

        return result
    }
}

/**
 * The helper as git's askpass program and ssh's. `SSH_ASKPASS_REQUIRE=force` (OpenSSH 8.4 and later) makes ssh use it
 * even when Leaf was started from a terminal, which ssh would otherwise prompt on. git uses `GIT_ASKPASS` before
 * `core.askPass` and `SSH_ASKPASS`.
 */
private fun askpassEnvironment(helper: File) = mapOf(
    "GIT_ASKPASS" to helper.absolutePath,
    "SSH_ASKPASS" to helper.absolutePath,
    "SSH_ASKPASS_REQUIRE" to "force",
)

/**
 * git-lfs only writes its progress ("Downloading LFS objects", "Uploading LFS objects") to a terminal, unless this is
 * set. It reaches git-lfs whether git runs it as a filter, as a hook or as `git lfs fetch`. The progress goes to
 * stdout, where `git push --porcelain` writes its refs too, which `parsePushPorcelain` tells apart.
 */
private val LFS_PROGRESS = mapOf("GIT_LFS_FORCE_PROGRESS" to "1")

/**
 * The helper as a credential helper. git runs `!` helpers with the shell, Git Bash's on Windows, so the path is quoted
 * for it, with `/` in place of `\`, which Git Bash also accepts.
 */
internal fun credentialHelperCommand(helper: File): String {
    val path = if (currentOs == OS.WINDOWS) helper.absolutePath.replace('\\', '/') else helper.absolutePath
    val quoted = "'" + path.replace("'", "'\\''") + "'"

    return "!$quoted credential"
}
