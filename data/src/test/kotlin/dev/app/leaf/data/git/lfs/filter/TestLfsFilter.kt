// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.AskpassProcessRunner
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.lfs.AuthenticateLfsServerWithSshGitAction
import dev.app.leaf.data.git.lfs.DownloadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.FetchLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.GitLfsSetup
import dev.app.leaf.data.git.lfs.ProvideLfsCredentialsGitAction
import dev.app.leaf.data.git.testAppSettings
import dev.app.leaf.data.network.createHttpClient
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import io.mockk.mockk
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import java.io.File

/** How tests find a repository's LFS server, by default `lfs.url` ([lfsUrlSetting]). */
typealias TestLfsServer = (Repository) -> LfsServer?

private val lfsUrlServer: TestLfsServer = { repository -> lfsUrlSetting(repository)?.let { LfsServer(it, null) } }

/**
 * An [LfsFilter] for tests, built as Dagger builds it, around [gitCli] and [jgit]. [lfsServer] stands in for the app's
 * lookup of the LFS server: by default `lfs.url` ([lfsUrlSetting]). The credentials dialogs go through
 * [credentialsStateManager], and the credential helpers and ssh get [shellVariables].
 */
fun testLfsFilter(
    gitCli: GitCli,
    jgit: JGit,
    credentialsStateManager: CredentialsStateManager = CredentialsStateManager(),
    shellVariables: Map<String, String> = emptyMap(),
    lfsServer: TestLfsServer = lfsUrlServer,
    gitLfsSetup: GitLfsSetup = GitLfsSetup(gitCli),
): LfsFilter = LfsFilter(
    gitLfsSetup = gitLfsSetup,
    jgit = jgit,
    getLfsUrlGitAction = lfsUrlLookup(lfsServer),
    fetchLfsObjectsGitAction = testFetchLfsObjects(gitCli, credentialsStateManager, shellVariables),
    loginShellEnvironment = LoginShellEnvironment { shellVariables },
)

/**
 * An [LfsDownloadFirst] for tests, built from the same parts as [testLfsFilter]. Its progress goes to
 * [repositoryState].
 */
fun testLfsDownloadFirst(
    gitCli: GitCli,
    jgit: JGit,
    credentialsStateManager: CredentialsStateManager = CredentialsStateManager(),
    repositoryState: RepositoryStateRepository = InMemoryRepositoryStateRepository(),
    shellVariables: Map<String, String> = emptyMap(),
    lfsServer: TestLfsServer = lfsUrlServer,
    gitLfsSetup: GitLfsSetup = GitLfsSetup(gitCli),
): LfsDownloadFirst = LfsDownloadFirst(
    jgit = jgit,
    gitLfsSetup = gitLfsSetup,
    getLfsUrlGitAction = lfsUrlLookup(lfsServer),
    fetchLfsObjectsGitAction = testFetchLfsObjects(gitCli, credentialsStateManager, shellVariables),
    repositoryStateRepository = repositoryState,
)

private fun lfsUrlLookup(lfsServer: TestLfsServer) = object : IGetLfsUrlGitAction {
    override suspend fun invoke(repository: Repository, remoteName: String?, operation: OperationType) =
        lfsServer(repository)
}

private fun testFetchLfsObjects(
    gitCli: GitCli,
    credentialsStateManager: CredentialsStateManager,
    shellVariables: Map<String, String>,
): FetchLfsObjectsGitAction {
    val shellEnvironment = LoginShellEnvironment { shellVariables }
    val lfsRepository = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))
    val credentials = ProvideLfsCredentialsGitAction(
        credentialsCacheRepository = CredentialsCacheRepository(),
        credentialsStateManager = credentialsStateManager,
        credentialHelpers = CredentialHelpers(
            shellManager = ShellManager(),
            gitCredentialsManagerProvider = mockk(relaxed = true),
            loginShellEnvironment = shellEnvironment,
            gitCli = gitCli,
        ),
        appSettingsService = testAppSettings(),
    )
    val ssh = AuthenticateLfsServerWithSshGitAction(
        AskpassProcessRunner(
            ProcessRunner(),
            AskpassHelper { null },
            credentialsStateManager,
            CredentialsCacheRepository(),
            testAppSettings(),
        ),
        gitCli,
        shellEnvironment,
    )

    return FetchLfsObjectsGitAction(
        GetLfsObjectsGitAction(lfsRepository, credentials),
        ssh,
        DownloadLfsObjectGitAction(lfsRepository, credentials),
    )
}

/** `lfs.url` from the repository's config, or else from `.lfsconfig` in its working tree, as tests set it up. */
fun lfsUrlSetting(repository: Repository): String? = repository.config.getString("lfs", null, "url")
    ?: repository.workTree?.let { workTree ->
        FileBasedConfig(File(workTree, ".lfsconfig"), FS.DETECTED).apply { load() }.getString("lfs", null, "url")
    }
