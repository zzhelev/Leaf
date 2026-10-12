// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import org.jetbrains.compose.resources.stringResource

private const val MAX_SUBJECT_LENGTH = 40

/**
 * What [side] is, as the banner, the dialogs and the diff pane name it, as few people remember which is ours: a branch
 * (`main`), a commit (`a1b2c3d "Fix parser"`), or what the operation makes of it, such as `main (onto)` in a rebase.
 * Null when git doesn't record that side.
 */
@Composable
fun ConflictSides.label(side: ConflictSide): String? {
    val commit = when (side) {
        ConflictSide.OURS -> ours
        ConflictSide.THEIRS -> theirs
    }

    return when (operation) {
        ConflictOperation.MERGE -> commit?.branchOrCommit()
        ConflictOperation.REBASE -> when (side) {
            ConflictSide.OURS -> commit?.let { stringResource(Res.string.conflict_side_onto, it.branch ?: it.hash) }
            ConflictSide.THEIRS -> commit?.commitFromBranch()
        }

        ConflictOperation.CHERRY_PICK -> when (side) {
            ConflictSide.OURS -> commit?.branchOrCommit()
            ConflictSide.THEIRS -> commit?.commitText()
        }

        ConflictOperation.REVERT -> when (side) {
            ConflictSide.OURS -> commit?.branchOrCommit()
            ConflictSide.THEIRS -> commit?.let { stringResource(Res.string.conflict_side_revert, it.commitText()) }
        }

        ConflictOperation.OTHER -> when (side) {
            ConflictSide.OURS -> commit?.branchOrCommit()
            ConflictSide.THEIRS -> stringResource(Res.string.conflict_side_applied_changes)
        }
    }
}

/** "Ours: main · Theirs: feature", or null when either side is unknown. */
@Composable
fun ConflictSides.summary(): String? {
    val ours = label(ConflictSide.OURS) ?: return null
    val theirs = label(ConflictSide.THEIRS) ?: return null

    return stringResource(Res.string.conflict_sides, ours, theirs)
}

/** "ours (main)", or "ours" alone when it's unknown. */
@Composable
fun ConflictSide.named(sides: ConflictSides?): String {
    val label = sides?.label(this)

    return when (this) {
        ConflictSide.OURS -> label?.let { stringResource(Res.string.conflict_side_ours_named, it) }
            ?: stringResource(Res.string.conflict_side_ours)

        ConflictSide.THEIRS -> label?.let { stringResource(Res.string.conflict_side_theirs_named, it) }
            ?: stringResource(Res.string.conflict_side_theirs)
    }
}

val ConflictSide.other: ConflictSide
    get() = when (this) {
        ConflictSide.OURS -> ConflictSide.THEIRS
        ConflictSide.THEIRS -> ConflictSide.OURS
    }

@Composable
private fun ConflictCommit.branchOrCommit(): String = branch ?: commitText()

@Composable
private fun ConflictCommit.commitText(): String =
    stringResource(Res.string.conflict_side_commit, hash, subject.shortened())

@Composable
private fun ConflictCommit.commitFromBranch(): String =
    branch?.let { stringResource(Res.string.conflict_side_commit_from_branch, hash, subject.shortened(), it) }
        ?: commitText()

private fun String.shortened(): String =
    if (length <= MAX_SUBJECT_LENGTH) this else take(MAX_SUBJECT_LENGTH - 1).trimEnd() + "…"
