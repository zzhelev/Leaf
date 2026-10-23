// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.compare_badge_a
import dev.app.leaf.app.generated.resources.compare_badge_b
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.repositoryopen.LogAction
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.compare.CompareBadge
import dev.app.leaf.ui.compare.CompareLetter
import dev.app.leaf.ui.context_menu.compareClickName
import org.jetbrains.compose.resources.stringResource

/**
 * What the log's rows need of a comparison (fork-only, `docs/fork/compare.md`, step 5): the commits of A and B, which
 * the rows mark, and the selection's name for "Compare with selected".
 */
data class LogComparison(
    val aCommit: String? = null,
    val bCommit: String? = null,
    val selectedName: String? = null,
    /** B is the working tree (7.2), which the uncommitted changes' row stands for, rather than a commit's. */
    val workingTreeIsB: Boolean = false,
) {
    val isComparing: Boolean get() = aCommit != null

    /** A when the row is A's commit, also when B is the same commit; B when it's B's. */
    fun letterOf(hash: String): CompareLetter? = when (hash) {
        aCommit -> CompareLetter.A
        bCommit -> CompareLetter.B
        else -> null
    }
}

/**
 * A click on a commit row: Shift-click selects the rows from the selected commit to it, ⌘-click (Ctrl-click elsewhere)
 * compares it, a plain click selects it. Shift wins, as in the side panel's branches.
 */
internal fun commitRowClick(
    commit: Commit,
    isStash: Boolean,
    isCompareClick: Boolean,
    isRangeClick: Boolean = false,
): LogAction = when {
    isRangeClick -> LogAction.CommitRangeExtended(commit, isStash)
    isCompareClick -> LogAction.CommitPicked(commit, isStash)
    else -> LogAction.CommitSelected(commit, isStash)
}

/**
 * A click on the uncommitted changes' row: ⌘-click compares the working tree with the selection, or makes it B while
 * comparing (7.2); a plain click selects the row, which ends a comparison.
 */
internal fun uncommittedRowClick(isCompareClick: Boolean): LogAction =
    if (isCompareClick) LogAction.UncommittedChangesPicked else LogAction.UncommittedChangesSelected

/** Given by `Log`, so that upstream's composables between it and the rows don't change. */
val LocalLogComparison = compositionLocalOf { LogComparison() }

/**
 * The A or B badge at the start of a row's message, whose tooltip says how to leave the row out. It starts 8 dp in, clear
 * of the column's divider, and takes the place of the chips' start padding.
 */
@Composable
internal fun CompareRowBadge(letter: CompareLetter) {
    val text = stringResource(
        if (letter == CompareLetter.A) Res.string.compare_badge_a else Res.string.compare_badge_b,
        compareClickName(),
    )

    DelayedTooltip(text) {
        CompareBadge(letter, Modifier.padding(start = 8.dp, end = 6.dp))
    }
}
