// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * A remote operation for an SSH remote ran with Leaf's built-in implementation (JGit), which can't reach SSH remotes:
 * Leaf reaches them only with the git CLI and the system's ssh. JGit ran because of [reason].
 */
data class SshNeedsGitError(val reason: Reason) : GitError {
    /** Why an operation runs with JGit rather than the git CLI. */
    enum class Reason {
        /** "Use git for remote operations" is off. */
        SettingOff,

        /** No usable git was found: it's missing, or older than Leaf needs. */
        GitNotFound,

        /** This build of Leaf has no askpass helper, without which git can't ask the user anything. */
        HelperMissing,

        /**
         * A push of a repository that uses LFS, whose LFS files git wouldn't upload: git-lfs isn't installed, or the
         * repository's `pre-push` hook doesn't run it. Leaf's built-in LFS client uploads them only during JGit's push.
         */
        LfsPushWithoutGitLfs,
    }
}
