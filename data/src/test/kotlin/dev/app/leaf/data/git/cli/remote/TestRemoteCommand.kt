// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.Collections

/**
 * A [GitCliRemoteCommand] for tests, with the built askpass [helper], and git kept away from the developer's global and
 * system config (an empty [globalConfig]). [shellVariables] stand for what the login shell adds, such as a PATH
 * without git-lfs.
 */
class TestRemoteCommand(
    helper: File,
    globalConfig: File,
    cacheCredentials: Boolean = true,
    shellVariables: Map<String, String> = emptyMap(),
) {
    val credentialsStateManager = CredentialsStateManager()
    val credentialsCache = CredentialsCacheRepository()
    val stateRepository = InMemoryRepositoryStateRepository()

    /** Every progress that the command reported, as the processing screen may miss some. */
    val progress: MutableList<TaskProgress?> = Collections.synchronizedList(mutableListOf())

    val gitCli = testGitCli(
        shellVariables = mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.absolutePath,
            "GIT_CONFIG_NOSYSTEM" to "1",
        ) + shellVariables,
    )

    val command = GitCliRemoteCommand(
        gitCli = gitCli,
        askpassHelper = AskpassHelper { helper },
        credentialsStateManager = credentialsStateManager,
        credentialsRepository = credentialsCache,
        appSettingsService = AppSettingsService(
            mockk<AppSettingsRepository> {
                every { cacheCredentialsInMemory } returns flowOf(cacheCredentials)
            }
        ),
        repositoryStateRepository = object : RepositoryStateRepository by stateRepository {
            override fun updateTaskProgress(progress: TaskProgress?) {
                this@TestRemoteCommand.progress.add(progress)
                stateRepository.updateTaskProgress(progress)
            }
        },
    )

    val gitLfsFetch = GitLfsFetch(gitCli, command)
}
