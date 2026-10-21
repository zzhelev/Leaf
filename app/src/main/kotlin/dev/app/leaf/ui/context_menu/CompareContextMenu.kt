// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import org.eclipse.jgit.lib.Constants
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menus' items that start a comparison (fork-only, `docs/fork/compare.md`, F2): of a row with the selected commit,
 * and of a row, a branch or a tag with the current branch.
 */
class CompareMenuItems(
    /** The selected commit's name, when another commit is selected; null leaves "Compare with selected" out. */
    val selectedName: String?,
    val onCompareWithSelected: () -> Unit,
    /** The tab's current branch, or its detached HEAD; null leaves "Compare with … (current)" out. */
    val currentBranch: Branch?,
    val onCompareWithCurrent: () -> Unit,
)

/**
 * What the log gives its chips, which upstream's composables in between don't pass along, to compare a branch or a
 * tag with the tab's current branch (fork-only).
 */
class CompareWithCurrent(
    val currentBranch: Branch?,
    val onBranch: (Branch) -> Unit,
    val onTag: (Tag) -> Unit,
)

val LocalCompareWithCurrent = staticCompositionLocalOf<CompareWithCurrent?> { null }

/** A commit row's comparison items, in a group of their own. Nothing is added when neither applies. */
fun MutableList<ContextMenuElement>.addCompareItems(items: CompareMenuItems?) {
    if (items == null) return

    val selectedName = items.selectedName
    val currentBranch = items.currentBranch

    if (selectedName == null && currentBranch == null) return

    if (selectedName != null) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.compare_with_selected, selectedName) },
            icon = { painterResource(Res.drawable.compare_arrows) },
            onClick = items.onCompareWithSelected,
            hint = { compareClickName() },
        )
    }

    if (currentBranch != null) {
        addCompareWithCurrent(currentBranch, items.onCompareWithCurrent)
    }

    add(ContextMenuElement.ContextSeparator)
}

/** "Compare with main (current)", or with a detached HEAD. */
fun MutableList<ContextMenuElement>.addCompareWithCurrent(currentBranch: Branch, onClick: () -> Unit) {
    addContextMenu(
        composableLabel = { compareWithCurrentLabel(currentBranch) },
        icon = { painterResource(Res.drawable.compare_arrows) },
        onClick = onClick,
    )
}

@Composable
private fun compareWithCurrentLabel(currentBranch: Branch): String = if (currentBranch.name == Constants.HEAD) {
    stringResource(Res.string.compare_with_detached_head)
} else {
    stringResource(Res.string.compare_with_current, currentBranch.simpleName)
}

/**
 * Whether a click on a log row compares it with the selection: ⌘-click on macOS, where Ctrl-click is the right-click,
 * and Ctrl-click elsewhere.
 */
fun isCompareClick(isMetaPressed: Boolean, isCtrlPressed: Boolean, os: OS = currentOs): Boolean =
    if (os.isMac()) isMetaPressed else isCtrlPressed

/** How the menus and tooltips name that click. */
@Composable
fun compareClickName(os: OS = currentOs): String =
    stringResource(if (os.isMac()) Res.string.compare_click_mac else Res.string.compare_click_other)
