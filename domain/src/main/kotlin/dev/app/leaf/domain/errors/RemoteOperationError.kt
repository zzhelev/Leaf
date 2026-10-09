// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * A push, fetch, pull, clone or submodule update run with the git CLI that failed. [output] is what git printed to
 * stderr, which often has the server's own message, so it's always shown below the explanation.
 */
sealed interface RemoteOperationError : GitError {
    val output: String

    /** The remote refused some refs, as `git push --porcelain` reports them. */
    data class RefsRejected(val refs: List<RejectedRef>, override val output: String) : RemoteOperationError

    /** The user closed a dialog for a question from git or ssh, so git stopped. */
    data class PromptRefused(override val output: String) : RemoteOperationError

    /** The server didn't accept the credentials or the SSH key. */
    data class AuthenticationFailed(override val output: String) : RemoteOperationError

    /** The server accepted the user but refused access to the repository, or the repository doesn't exist. */
    data class AccessDenied(override val output: String) : RemoteOperationError

    /** The SSH server's host key differs from the one in known_hosts. */
    data class HostKeyChanged(override val output: String) : RemoteOperationError

    /** ssh couldn't verify the server's host key, for example because the user didn't trust it. */
    data class HostKeyNotVerified(override val output: String) : RemoteOperationError

    /** The server couldn't be reached: unknown host, connection refused, timeout. */
    data class ConnectionFailed(override val output: String) : RemoteOperationError

    /** The server's TLS certificate couldn't be verified. */
    data class CertificateProblem(override val output: String) : RemoteOperationError

    /** Any other failure, explained by git's [output] alone. */
    data class Failed(val exitCode: Int, override val output: String) : RemoteOperationError
}

/** Fetching some remotes failed. The others were fetched. */
data class FetchRemotesError(val failures: List<RemoteFetchFailure>) : GitError

data class RemoteFetchFailure(val remote: String, val error: RemoteOperationError)

/**
 * The repository was cloned into [directory] and checked out, but its submodules couldn't be cloned, as [error] says.
 * The repository is kept, as with `git clone --recurse-submodules`.
 */
data class CloneSubmodulesError(val directory: String, val error: GitError) : GitError

/** git-lfs couldn't download the LFS files of what was pulled or cloned, as [error] says. Nothing was checked out. */
data class LfsDownloadError(val error: GitError) : GitError

/** A ref that the remote refused: `!  <source>:<destination>  [rejected] (<reason>)` in `git push --porcelain`. */
data class RejectedRef(val destination: String, val reason: RejectReason, val detail: String)

enum class RejectReason {
    /** The remote has commits that the local branch doesn't: fetch or pull first. */
    FETCH_FIRST,

    /** The local branch isn't a descendant of the remote one. */
    NON_FAST_FORWARD,

    /** Force with lease: the remote branch moved since it was last fetched. */
    STALE_INFO,

    /** The remote refused it itself, for example with a `pre-receive` hook ([RejectedRef.detail] says why). */
    REMOTE_REJECTED,

    OTHER,
}
