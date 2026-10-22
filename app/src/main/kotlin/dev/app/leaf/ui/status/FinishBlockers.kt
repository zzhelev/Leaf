// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.StatusType

/**
 * Why the button that commits, merges or continues is disabled (fork-only). The status pane enables a button when
 * nothing blocks it, and shows the first blocker next to it ([finishHint]).
 */
sealed interface FinishBlocker {
    /** [files] still have conflicts: git refuses to commit or continue with them. */
    data class Conflicts(val files: Int) : FinishBlocker

    /**
     * A rebase's Continue only: [files] have unstaged changes to tracked files, which `git rebase --continue` refuses
     * (git 2.54, both backends). New (untracked) files don't count.
     */
    data class UnstagedChanges(val files: Int) : FinishBlocker

    data object NothingStaged : FinishBlocker

    /** No message. [commentsOnly] when there is text, but only comment lines, which git leaves out (fork-only). */
    data class EmptyMessage(val commentsOnly: Boolean = false) : FinishBlocker

    /**
     * The message is what Tab started it with, unedited (fork-only, [isUneditedStart]): the commit template, which git
     * refuses unchanged, or the branch's ticket [key] alone. [isTemplate] when the start has the template.
     */
    data class UneditedStart(val key: String?, val isTemplate: Boolean) : FinishBlocker
}

private fun StatusState.conflicts(): FinishBlocker.Conflicts? =
    unstaged.count { it.statusType == StatusType.CONFLICTING }
        .takeIf { it > 0 }
        ?.let { FinishBlocker.Conflicts(it) }

/**
 * Unstaged changes to files git tracks: modified and deleted ones. [StatusType.ADDED] in the Unstaged list is an
 * untracked file, and [StatusType.CONFLICTING] is [conflicts].
 */
private fun StatusState.unstagedChanges(): FinishBlocker.UnstagedChanges? =
    unstaged.count { it.statusType == StatusType.MODIFIED || it.statusType == StatusType.REMOVED }
        .takeIf { it > 0 }
        ?.let { FinishBlocker.UnstagedChanges(it) }

/**
 * The message would leave the commit without one once git cleans it up: blank, or only comment lines (fork-only). git
 * refuses that commit, and JGit would make it. A verbatim message (`commit.cleanup=verbatim`) only when it's empty.
 */
private fun StatusState.emptyMessage(): FinishBlocker.EmptyMessage? =
    FinishBlocker.EmptyMessage(commentsOnly = commitMessage.text.isNotBlank()).takeIf { messageLayout.isEmpty }

private fun StatusState.nothingStaged(): FinishBlocker.NothingStaged? =
    FinishBlocker.NothingStaged.takeIf { !hasStagedFiles }

private fun StatusState.uneditedStart(): FinishBlocker.UneditedStart? = messageStart
    ?.takeIf { isStartUnedited }
    ?.let { FinishBlocker.UneditedStart(it.key, isTemplate = it.template != null) }

/** An interactive rebase stopped at a commit that the user can amend. */
val StatusState.isAmenableRebaseInteractive: Boolean
    get() = repositoryState.isRebasing &&
            rebaseInteractiveState is RebaseInteractiveState.ProcessingCommits &&
            rebaseInteractiveState.isCurrentStepAmenable

/**
 * Whether Commit works outside an operation: a message, staged files, and no conflicts (a squash merge's). Fork-only: not
 * the commit template or the branch's ticket key as Tab wrote them ([StatusState.isStartUnedited]).
 */
val StatusState.canCommit: Boolean
    get() = !messageLayout.isEmpty && !isStartUnedited && hasStagedFiles && haveConflictsBeenSolved

/** Whether Amend works: a message, and a commit to amend. */
val StatusState.canAmend: Boolean
    get() = !messageLayout.isEmpty && hasPreviousCommits

/**
 * What stops the primary button of the commit field, or null when it works: Merge, a cherry-pick's or a revert's
 * Commit, a rebase's Continue, or Commit/Amend outside an operation.
 *
 * - Merge: conflicts, and a message that git's clean-up leaves empty. git finishes a merge with unstaged or untracked
 *   files around, and with nothing staged (the merge commit still has two parents).
 * - Cherry-pick and revert: conflicts, an empty message, and nothing staged, as JGit refuses an empty commit there
 *   (`DoCommitGitAction` allows one only for a merge or an amend).
 * - Rebase Continue: conflicts and unstaged changes to tracked files, as git refuses them. JGit's continue would go
 *   on with them, but a rebase behaves the same whichever of them started it.
 * - Outside an operation: Commit needs a message, staged files and no conflicts, and Amend a message and a commit.
 *   Fork-only: Commit also refuses the commit template unedited, as git does, and the ticket key alone ([uneditedStart]).
 *
 * A message counts as empty when nothing is left of it once git cleans it up (fork-only, [emptyMessage]): blank, or only
 * comment lines.
 *
 * - Fork-only: Continue of a cherry-pick of several commits that git stopped ([cherryPickSequenceBlocker]).
 *
 * The first one in the order of the lists above is the one returned.
 */
fun StatusState.finishBlocker(): FinishBlocker? = when {
    cherryPickSequence != null -> cherryPickSequenceBlocker()
    repositoryState.isMerging -> conflicts() ?: emptyMessage()
    repositoryState.isRebasing -> rebaseBlocker()
    repositoryState.isCherryPicking || repositoryState.isReverting ->
        conflicts() ?: emptyMessage() ?: nothingStaged()

    else -> commitBlocker()
}

/**
 * Whether `git cherry-pick --continue` commits the commit that git stopped at (fork-only): it stopped at its conflicts
 * (`CHERRY_PICK_HEAD`), and the cherry-pick doesn't stage the commits as one (`--no-commit`). Otherwise Continue only
 * picks the commits left, as after local changes were in the way.
 */
val StatusState.continueCommitsStoppedCommit: Boolean
    get() = cherryPickSequence?.noCommit == false && repositoryState.isCherryPicking

/**
 * What stops Continue of a cherry-pick of several commits (fork-only), as git 2.54 refuses it: conflicts, and an empty
 * message when Continue commits the stopped commit, which git aborts. git goes on with unstaged changes, and stops at a
 * later commit that changes the same files, changing nothing.
 */
private fun StatusState.cherryPickSequenceBlocker(): FinishBlocker? =
    conflicts() ?: emptyMessage()?.takeIf { continueCommitsStoppedCommit }

/** What stops a rebase's Continue. A Skip button, shown instead when [showsContinue] is false, is never blocked. */
fun StatusState.rebaseBlocker(): FinishBlocker? = conflicts() ?: unstagedChanges()

/**
 * Whether a rebase offers Continue rather than Skip: something is staged, something blocks it (Continue is shown
 * disabled, with its reason), or an amendable stop has an amended message. Only untracked files left, or nothing at
 * all, mean the commit being applied came out empty: Skip is the action then. JGit's continue does nothing there
 * (`NOTHING_TO_COMMIT`) and git's apply backend refuses it, and Skip loses no work, as nothing tracked is staged or
 * changed.
 */
fun StatusState.showsContinue(): Boolean = when {
    // Fork-only: a cherry-pick of several commits offers Skip where the stopped commit came out empty, as git then says
    // "The previous cherry-pick is now empty" and refuses to continue
    cherryPickSequence != null -> !continueCommitsStoppedCommit || hasStagedFiles || cherryPickSequenceBlocker() != null
    else -> hasStagedFiles ||
            rebaseBlocker() != null ||
            (isAmenableRebaseInteractive && isAmendRebaseInteractive && !messageLayout.isEmpty)
}

private fun StatusState.commitBlocker(): FinishBlocker? = when {
    canCommit || (isAmend && canAmend) -> null
    // From here on, neither works. Both need a message, and canCommit needs the rest.
    else -> conflicts() ?: emptyMessage() ?: uneditedStart() ?: nothingStaged()
}

/**
 * What the commit field says about its disabled primary button, or null for no hint. It is [finishBlocker], except
 * that outside an operation nothing is said while the user hasn't started: no message and nothing staged is how the
 * pane rests most of the time.
 */
fun StatusState.finishHint(): FinishBlocker? {
    val blocker = finishBlocker() ?: return null
    val inOperation = repositoryState.isMerging || repositoryState.isRebasing ||
            repositoryState.isCherryPicking || repositoryState.isReverting || cherryPickSequence != null
    val resting = !isAmend && commitMessage.text.isEmpty() && !hasStagedFiles

    return blocker.takeUnless { !inOperation && resting && it !is FinishBlocker.Conflicts }
}
