// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.sparse

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.testGitCliRemoteCommand
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testGitSettings
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import java.io.File

/**
 * A [SparseCheckoutGit] as Dagger builds it, around [gitCli] and [jgit], for the git actions that ask it: with the built
 * askpass [helper], git runs the commands through Leaf's filter and dialogs; without it, as in the app, it runs them
 * without. The tests of repositories that aren't sparse never reach git through it, so they can leave the defaults.
 */
fun testSparseCheckoutGit(
    jgit: JGit,
    gitCli: GitCli = testGitCli(),
    repositoryState: RepositoryStateRepository = InMemoryRepositoryStateRepository(),
    helper: File? = builtAskpassHelper(),
    gitExecutablePath: String? = null,
): SparseCheckoutGit = SparseCheckoutGit(
    gitCli = gitCli,
    remoteCommand = testGitCliRemoteCommand(gitCli, jgit, repositoryState, helper = helper),
    gitExecutableLocator = GitExecutableLocator(ProcessRunner()),
    appSettingsService = testGitSettings(gitExecutablePath),
)
