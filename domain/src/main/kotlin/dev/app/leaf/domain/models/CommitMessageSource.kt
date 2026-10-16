// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * Which message the commit field shows, and the file where its edits are saved (fork-only). The field and the git
 * action that saves its edits both go by [commitMessageSource], so that they agree in every state.
 */
enum class CommitMessageSource {
    /**
     * The draft of a plain commit, in Leaf's `LEAF_COMMIT_DRAFT`. Not git's `COMMIT_EDITMSG`, which every commit made
     * in a terminal writes.
     */
    NORMAL,

    /**
     * git's message for the commit of a merge, cherry-pick or revert in progress, in `MERGE_MSG`: the merge's, the
     * picked commit's, or "Revert …", as `git commit` takes it then.
     */
    MERGE,

    /** The message of a squash merge that waits to be committed, in `SQUASH_MSG`. */
    SQUASH,

    /**
     * The message for the commit that a rebase stopped at to edit, which Continue amends with it, in a file of Leaf's
     * in the rebase's folder. A rebase's other stops don't take one: the field shows the last commit's message.
     */
    REBASE;

    /**
     * Whether the commit removes the message's file itself: JGit's commit of a merge, cherry-pick or revert removes
     * `MERGE_MSG`, and Leaf's clean-up after a commit removes `SQUASH_MSG`. Otherwise `DoCommitUseCase` removes it.
     */
    val isMergeMessage: Boolean
        get() = this == MERGE || this == SQUASH
}

/**
 * The message that the commit field shows in [repositoryState]: git's while a merge, cherry-pick or revert goes on, the
 * one for an edit stop while a rebase goes on, a squash merge's while one waits to be committed
 * ([squashMergeInProgress]), and the draft of a plain commit otherwise.
 */
fun commitMessageSource(repositoryState: RepositoryState?, squashMergeInProgress: Boolean): CommitMessageSource = when {
    repositoryState?.hasMergeMessage == true -> CommitMessageSource.MERGE
    repositoryState?.isRebasing == true -> CommitMessageSource.REBASE
    squashMergeInProgress -> CommitMessageSource.SQUASH
    else -> CommitMessageSource.NORMAL
}

private val RepositoryState.hasMergeMessage: Boolean
    get() = isMerging || isCherryPicking || isReverting
