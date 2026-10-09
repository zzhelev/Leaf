// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.cli.remote.RemoteOperationsBackend
import dev.app.leaf.domain.errors.SshNeedsGitError
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import javax.inject.Inject

/**
 * JGit's SSH, which fails before it connects: Leaf reaches SSH remotes only with the git CLI and the system's ssh. The
 * error says why JGit ran, as [RemoteOperationsBackend] decided ([whyNotGitCli]), and so what makes git run.
 */
class NoSshSessionFactory(
    private val whyNotGitCli: suspend () -> SshNeedsGitError.Reason?,
) : SshSessionFactory() {
    @Inject
    constructor(backend: RemoteOperationsBackend) : this(backend::whyNotGitCli)

    override fun getSession(
        uri: URIish,
        credentialsProvider: CredentialsProvider?,
        fs: FS?,
        tms: Int,
    ): RemoteSession {
        // When nothing stops the git CLI, JGit ran for an LFS push that git wouldn't upload
        val reason = runBlocking { whyNotGitCli() } ?: SshNeedsGitError.Reason.LfsPushWithoutGitLfs

        throw SshNeedsGitException(uri, SshNeedsGitError(reason))
    }

    override fun getType(): String = "none"
}

/**
 * Thrown by [NoSshSessionFactory]. JGit wraps it, and `JGit.provide` turns it back into its [error]. Its message is
 * for the places that only show a message, such as JGit's fetch of several remotes.
 */
class SshNeedsGitException(uri: URIish, val error: SshNeedsGitError) : TransportException(uri, error.describe())

/** The error in English, for where only a message can be shown. The app's own text is in `Errors.kt`. */
internal fun SshNeedsGitError.describe(): String = "Leaf reaches SSH remotes with git: " + when (reason) {
    SshNeedsGitError.Reason.SettingOff -> "turn on \"Use git for remote operations\" in Settings"
    SshNeedsGitError.Reason.GitNotFound -> "install git 2.36 or later, or set its path in Settings"
    SshNeedsGitError.Reason.HelperMissing -> "this build of Leaf lacks its askpass helper, which git needs"
    SshNeedsGitError.Reason.LfsPushWithoutGitLfs ->
        "to push LFS files, git needs git-lfs. Install it, or run \"git lfs install\" in the repository if it's installed"
}

/** The [SshNeedsGitError] that failed this operation, wherever JGit wrapped the [SshNeedsGitException]. */
internal fun Throwable.sshNeedsGitError(): SshNeedsGitError? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SshNeedsGitException>()
        .firstOrNull()
        ?.error
}
