// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rebase

import dev.app.leaf.common.TabScope
import javax.inject.Inject

private const val SHORT_HASH_LENGTH = 7

/**
 * The commit that a rebase stopped at (fork-only): `REBASE_HEAD`, which git writes for both its backends, or JGit's
 * `rebase-merge/stopped-sha`.
 *
 * @param branch the branch being rebased (`head-name`), short, or null for a detached HEAD.
 * @param isEditStop the rebase stopped there to edit the commit (`rebase-merge/amend`), which it already applied: Skip
 * isn't offered there.
 */
data class RebaseStop(
    val commit: String,
    val subject: String,
    val branch: String?,
    val isEditStop: Boolean,
) {
    val shortHash: String get() = commit.take(SHORT_HASH_LENGTH)
}

/**
 * A commit that Skip left out of a rebase while it had changes: its resolved conflicts and its own changes.
 *
 * @param branch the branch being rebased, short, or null for a detached HEAD.
 */
data class SkippedRebaseCommit(val commit: String, val subject: String, val branch: String?) {
    val shortHash: String get() = commit.take(SHORT_HASH_LENGTH)
}

/**
 * The commits that Skip left out of a rebase that has ended (fork-only), which the tab says until dismissed: they're no
 * longer on [branch], but still in the repository, and can be cherry-picked back.
 */
data class SkippedCommitsNotice(val branch: String?, val commits: List<SkippedRebaseCommit>)

/**
 * The commits that Skip left out of each worktree's rebase, by git dir, until the rebase ends (fork-only). Kept in memory
 * for the tab: a restart in the middle of a rebase forgets them, and the reflog still has them.
 */
@TabScope
class SkippedRebaseCommits @Inject constructor() {
    private val byGitDir = mutableMapOf<String, List<SkippedRebaseCommit>>()

    @Synchronized
    fun add(gitDir: String, commit: SkippedRebaseCommit) {
        byGitDir[gitDir] = byGitDir[gitDir].orEmpty() + commit
    }

    /** The commits skipped in [gitDir]'s rebase, which this forgets. */
    @Synchronized
    fun take(gitDir: String): List<SkippedRebaseCommit> = byGitDir.remove(gitDir).orEmpty()
}
