// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import dev.app.leaf.data.git.cli.ProcessOutcome
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.remote.askpassEnvironment
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.repositories.CredentialsRepository
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

/**
 * Runs OpenSSH's programs, ssh and ssh-keygen, so that what they ask through `SSH_ASKPASS` gets Leaf's dialogs, as with
 * the git commands of [dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand]: host keys, passphrases (kept per key
 * file for the session), security key PINs.
 */
class AskpassProcessRunner @Inject constructor(
    private val processRunner: ProcessRunner,
    private val askpassHelper: AskpassHelper,
    private val credentialsStateManager: CredentialsStateManager,
    private val credentialsRepository: CredentialsRepository,
) {
    /**
     * Answers for one command. Running the command again with the same answers makes its passphrase prompts retries,
     * which drop a kept passphrase and ask the user.
     *
     * @param passphraseKeyPath see [AskpassAnswers].
     */
    fun answers(passphraseKeyPath: String? = null) =
        AskpassAnswers(credentialsStateManager, credentialsRepository, passphraseKeyPath)

    /**
     * Runs [command] with [environment] and the variables of the askpass helper, whose requests [answers] answers.
     * Without the helper (a build that lacks it), the command runs without them, and can't ask anything.
     *
     * @throws java.io.IOException if the program can't be started.
     */
    suspend fun run(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String>,
        timeout: Duration,
        answers: AskpassAnswers,
    ): ProcessOutcome {
        val helper = askpassHelper.path()
            ?: return processRunner.run(command, workingDirectory, environment, timeout)

        return withAskpassServer(answers::answer) { serverEnvironment ->
            processRunner.run(
                command,
                workingDirectory,
                environment + askpassEnvironment(helper) + serverEnvironment,
                timeout,
            )
        }
    }
}
