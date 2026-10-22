// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * Deleting several of a remote's branches runs git, which can't run because of [reason]. Leaf's built-in
 * implementation (JGit) deletes one branch at a time, and can't reach SSH remotes.
 */
data class RemoteBranchesNeedGitError(val reason: SshNeedsGitError.Reason) : GitError
