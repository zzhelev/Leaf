// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.testAppSettings
import dev.app.leaf.data.git.testGitSettings
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import java.io.File

/**
 * [GitCliOperations] as Dagger builds them, around [gitCli], with the built askpass helper ([helper]), so that git runs
 * them. Without the helper, or with a git that isn't there ([gitExecutablePath]), JGit does.
 */
fun testGitCliOperations(
    jgit: JGit,
    gitCli: GitCli,
    credentialsStateManager: CredentialsStateManager = CredentialsStateManager(),
    repositoryState: RepositoryStateRepository = InMemoryRepositoryStateRepository(),
    helper: File? = builtAskpassHelper(),
    gitExecutablePath: String? = null,
): GitCliOperations {
    val askpassHelper = AskpassHelper { helper }

    return GitCliOperations(
        gitCli = gitCli,
        remoteCommand = GitCliRemoteCommand(
            gitCli = gitCli,
            askpassHelper = askpassHelper,
            credentialsStateManager = credentialsStateManager,
            credentialsRepository = CredentialsCacheRepository(),
            appSettingsService = testAppSettings(),
            repositoryStateRepository = repositoryState,
        ),
        gitExecutableLocator = GitExecutableLocator(ProcessRunner()),
        askpassHelper = askpassHelper,
        appSettingsService = testGitSettings(gitExecutablePath),
        jgit = jgit,
        autostashes = Autostashes(gitCli, repositoryState),
    )
}
