// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.git.cli.askpass.ASKPASS_HELPER_NAME
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
    jgit: JGit = testJGit(),
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

    val partialClone = PartialCloneFetch(jgit, command)
}

/**
 * A PATH without git-lfs, as macOS has it until Homebrew installs one: a `git-lfs` in [folder] that fails as git does
 * without it, before `/usr/bin` and `/bin`. Linux installs the real one there, as CI's runner has it.
 */
fun pathWithoutGitLfs(folder: File): String {
    File(folder, "git-lfs").writeExecutable("#!/bin/sh\necho \"git: 'lfs' is not a git command.\" >&2\nexit 1\n")

    return "${folder.absolutePath}:/usr/bin:/bin"
}

/**
 * A [PartialCloneFetch] for the git actions that use one, with [jgit], their own. It only runs git in a partial clone,
 * through the built askpass helper; without it, the fetch fails to start.
 */
fun testPartialCloneFetch(jgit: JGit, globalConfig: File = File("build/no-global.gitconfig")): PartialCloneFetch =
    TestRemoteCommand(File("../app/src/main/resources", ASKPASS_HELPER_NAME), globalConfig, jgit = jgit).partialClone

/**
 * A [RemoteOperationsBackend] for tests, around [gitCli] and [jgit]: the git CLI runs remote operations when
 * [remoteOperationsWithGit] is on and [helper] is there, as in the app with its default settings.
 */
fun testRemoteOperationsBackend(
    jgit: JGit,
    gitCli: GitCli,
    helper: File?,
    remoteOperationsWithGit: Boolean = true,
): RemoteOperationsBackend = RemoteOperationsBackend(
    appSettingsService = AppSettingsService(
        mockk<AppSettingsRepository> {
            every { this@mockk.remoteOperationsWithGit } returns flowOf(remoteOperationsWithGit)
            every { gitExecutablePath } returns flowOf(null)
        }
    ),
    gitExecutableLocator = GitExecutableLocator(ProcessRunner()),
    askpassHelper = AskpassHelper { helper },
    gitCli = gitCli,
    jgit = jgit,
)
