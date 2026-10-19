// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.domain.errors.RemoteOperationError

// What git, ssh, curl and the common servers print, in English as Leaf runs git with LC_ALL=C. The order matters: a
// changed host key also prints "Host key verification failed.".
private val HOST_KEY_CHANGED = listOf(
    "REMOTE HOST IDENTIFICATION HAS CHANGED",
    "has changed and you have requested strict checking",
)
private val HOST_KEY_NOT_VERIFIED = listOf("Host key verification failed.")
private val CERTIFICATE_PROBLEM = listOf(
    "SSL certificate problem",
    "server certificate verification failed",
    "certificate verify failed",
)
private val AUTHENTICATION_FAILED = listOf(
    "Permission denied (",
    "Permission denied, please try again",
    "Too many authentication failures",
    "Authentication failed for",
    "Invalid username or password",
    "HTTP Basic: Access denied",
    "could not read Username",
    "could not read Password",
)
private val ACCESS_DENIED = listOf(
    "ERROR: Permission to",
    "Repository not found",
    "repository not found",
    "does not appear to be a git repository",
    "The requested URL returned error: 403",
    "The requested URL returned error: 404",
)
private val CONNECTION_FAILED = listOf(
    "Could not resolve hostname",
    "Could not resolve host",
    "Connection refused",
    "Connection timed out",
    "Operation timed out",
    "Network is unreachable",
    "No route to host",
    "Failed to connect to",
    "Connection reset by peer",
    "Connection closed by",
)

/** git prints `Remote branch X not found in upstream origin`, JGit `Remote branch 'X' not found in upstream origin`. */
private val BRANCH_NOT_FOUND = Regex("Remote branch '?(.+?)'? not found in upstream")

/** A clone's message about a branch or tag that the remote doesn't have, from git's or JGit's output. */
fun branchNotFound(output: String): RemoteOperationError.BranchNotFound? {
    val branch = BRANCH_NOT_FOUND.find(output)?.groupValues?.get(1) ?: return null

    return RemoteOperationError.BranchNotFound(branch, output)
}

/** Tells from git's stderr why a command that talks to a remote failed. */
fun remoteOperationError(exitCode: Int, stderr: String): RemoteOperationError {
    val output = readableGitOutput(stderr)
    // Only a clone of a chosen branch says it, and it got as far as the remote's refs
    val missingBranch = branchNotFound(output)

    fun mentions(patterns: List<String>) = patterns.any { output.contains(it) }

    return when {
        missingBranch != null -> missingBranch
        mentions(HOST_KEY_CHANGED) -> RemoteOperationError.HostKeyChanged(output)
        mentions(HOST_KEY_NOT_VERIFIED) -> RemoteOperationError.HostKeyNotVerified(output)
        mentions(CERTIFICATE_PROBLEM) -> RemoteOperationError.CertificateProblem(output)
        mentions(AUTHENTICATION_FAILED) -> RemoteOperationError.AuthenticationFailed(output)
        mentions(ACCESS_DENIED) -> RemoteOperationError.AccessDenied(output)
        mentions(CONNECTION_FAILED) -> RemoteOperationError.ConnectionFailed(output)
        else -> RemoteOperationError.Failed(exitCode, output)
    }
}
