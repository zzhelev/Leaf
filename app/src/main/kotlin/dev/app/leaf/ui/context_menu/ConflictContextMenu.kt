// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.arrow_back
import dev.app.leaf.app.generated.resources.arrow_forward
import dev.app.leaf.domain.models.ConflictSide
import org.jetbrains.compose.resources.painterResource

/** What a conflicted file's menu does (fork-only). */
class ConflictMenuActions(
    val onOpenInExternalMergeTool: () -> Unit,
    val onUseSide: (ConflictSide) -> Unit,
)

internal fun MutableList<ContextMenuElement>.addConflictSideItems(
    ours: @Composable () -> String,
    theirs: @Composable () -> String,
    onUseSide: (ConflictSide) -> Unit,
) {
    // Left and right, where Meld and most merge tools show ours and theirs
    addContextMenu(
        composableLabel = ours,
        icon = { painterResource(Res.drawable.arrow_back) },
        onClick = { onUseSide(ConflictSide.OURS) },
    )

    addContextMenu(
        composableLabel = theirs,
        icon = { painterResource(Res.drawable.arrow_forward) },
        onClick = { onUseSide(ConflictSide.THEIRS) },
    )
}
