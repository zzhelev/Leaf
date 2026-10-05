// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Errors of operations that run the git CLI instead of JGit. */
sealed interface GitCliError : GitError {
    data class GitNotFound(val searchedPaths: List<String>) : GitCliError
    data class InvalidConfiguredPath(val path: String, val reason: String) : GitCliError
    data class UnsupportedVersion(val path: String, val version: String, val minimumVersion: String) : GitCliError
    data class CommandFailed(val command: String, val exitCode: Int, val stderr: String) : GitCliError
    data class TimedOut(val command: String, val timeoutSeconds: Long) : GitCliError
    data class StartFailed(val command: String, val message: String) : GitCliError
}
