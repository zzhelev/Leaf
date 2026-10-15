// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenuElement
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What a changed line of an uncommitted diff offers: the buttons shown while the pointer is over it, and its menu, in
 * the same order. Upstream's line had a button to stage or unstage it, and "Discard line" in its menu only.
 */
enum class LineAction(val label: StringResource, val icon: DrawableResource) {
    STAGE(Res.string.diff_line_stage, Res.drawable.add),
    UNSTAGE(Res.string.diff_line_unstage, Res.drawable.remove),
    DISCARD(Res.string.diff_line_discard, Res.drawable.undo),
}

/** The actions that a changed line of [diffType] has while the repository is in a safe state, as for hunks. */
fun lineActionsOf(diffType: DiffType): List<LineAction> =
    if (diffType is DiffType.UncommittedDiff && diffType.statusType == StatusType.MODIFIED) {
        when (diffType.entryType) {
            EntryType.STAGED -> listOf(LineAction.UNSTAGE)
            EntryType.UNSTAGED -> listOf(LineAction.STAGE, LineAction.DISCARD)
        }
    } else {
        emptyList()
    }

/**
 * The actions on [line]: none for a context line, nor while a merge, rebase, cherry-pick or revert goes on
 * ([canUseLineActions] false), when the hunk header has no buttons either.
 */
fun lineActions(line: Line, diffType: DiffType, canUseLineActions: Boolean): List<LineAction> =
    if (canUseLineActions && line.lineType != LineType.CONTEXT) lineActionsOf(diffType) else emptyList()

/** Where a line's text starts: after its buttons. Upstream's one button fitted the text's 16 dp padding. */
fun lineTextStart(diffType: DiffType): Dp =
    if (lineActionsOf(diffType).size > 1) TWO_BUTTONS_TEXT_START else 16.dp

private val TWO_BUTTONS_TEXT_START = 40.dp

/** The line's buttons, which [DiffLineText] shows while the pointer is over the line. */
@Composable
fun LineActionButtons(actions: List<LineAction>, onAction: (LineAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (action in actions) {
            val label = stringResource(action.label)

            DelayedTooltip(label) {
                Icon(
                    painterResource(action.icon),
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier
                        .handMouseClickable { onAction(action) }
                        .size(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(action.color()),
                )
            }
        }
    }
}

@Composable
private fun LineAction.color(): Color = when (this) {
    LineAction.STAGE -> MaterialTheme.colors.primary
    LineAction.UNSTAGE, LineAction.DISCARD -> MaterialTheme.colors.error
}

/** The line's menu: the same actions as its buttons. */
fun lineActionMenuItems(actions: List<LineAction>, onAction: (LineAction) -> Unit): List<ContextMenuElement> =
    actions.map { action ->
        ContextMenuElement.ContextTextEntry(
            composableLabel = { stringResource(action.label) },
            icon = { painterResource(action.icon) },
            onClick = { onAction(action) },
        )
    }
