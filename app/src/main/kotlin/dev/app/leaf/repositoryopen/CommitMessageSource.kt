// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.RepositoryState

/** Which message the commit field shows, and the file in the git dir where its edits are saved (fork-only). */
enum class CommitMessageSource {
    /** The draft of a plain commit, in `COMMIT_EDITMSG`. */
    NORMAL,

    /** The message of a merge in progress, in `MERGE_MSG`. */
    MERGE,

    /** The message of a squash merge that waits to be committed, in `SQUASH_MSG`. */
    SQUASH,
}

/**
 * The message that the commit field shows in [repositoryState]: a merge's, then a squash merge's while one waits to be
 * committed ([squashMergeInProgress]), and the draft of a plain commit otherwise.
 */
fun commitMessageSource(repositoryState: RepositoryState?, squashMergeInProgress: Boolean): CommitMessageSource = when {
    repositoryState?.isMerging == true -> CommitMessageSource.MERGE
    squashMergeInProgress -> CommitMessageSource.SQUASH
    else -> CommitMessageSource.NORMAL
}
