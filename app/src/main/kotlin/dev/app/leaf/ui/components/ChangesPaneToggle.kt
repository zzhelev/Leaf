// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.changes_pane_held_comparison
import dev.app.leaf.app.generated.resources.changes_pane_held_operation
import dev.app.leaf.app.generated.resources.changes_pane_hide
import dev.app.leaf.app.generated.resources.changes_pane_operation_cherry_pick
import dev.app.leaf.app.generated.resources.changes_pane_operation_merge
import dev.app.leaf.app.generated.resources.changes_pane_operation_rebase
import dev.app.leaf.app.generated.resources.changes_pane_operation_revert
import dev.app.leaf.app.generated.resources.changes_pane_operation_squash
import dev.app.leaf.app.generated.resources.changes_pane_show
import dev.app.leaf.app.generated.resources.side_panel_right
import dev.app.leaf.app.generated.resources.side_panel_right_open
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.ignoreKeyEvents
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.ChangesPaneNeed
import dev.app.leaf.ui.ChangesPaneState
import dev.app.leaf.ui.KeybindingHint
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltipPosition
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The bottom bar's button that folds the changes pane away and shows it again (fork-only). Its icon is a panel on the
 * right, filled while the pane shows and empty while it's folded. It stays where it is either way, under the pane's
 * column, so one click brings the pane back. While something needs the pane ([ChangesPaneState.isHeld]) the pane shows
 * whatever the user chose, the icon has an amber dot, and a click does nothing: the tooltip says why.
 */
@Composable
fun ChangesPaneToggle(
    state: ChangesPaneState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val binding = KeybindingOption.CHANGES_PANE.keyBinding
    val label = changesPaneToggleLabel(state)

    InstantTooltip(
        text = label,
        trailingContent = if (state.isHeld) null else binding?.let { { KeybindingHint(it) } },
        position = InstantTooltipPosition.TOP,
    ) {
        Box(
            modifier = modifier
                .size(26.dp)
                .ignoreKeyEvents()
                .clip(RoundedCornerShape(4.dp))
                .then(if (state.isHeld) Modifier else Modifier.handMouseClickable { onToggle() }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (state.isShown) Res.drawable.side_panel_right_open else Res.drawable.side_panel_right),
                contentDescription = label,
                tint = MaterialTheme.colors.onSurface,
                modifier = Modifier.size(20.dp),
            )

            if (state.isHeld) {
                // The ring is the bar's own colour, so that the dot stands apart from the icon's edge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(11.dp)
                        .background(MaterialTheme.colors.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(MaterialTheme.styleColors.warningIcon, CircleShape),
                    )
                }
            }
        }
    }
}

/** What the button's tooltip and description say: the action, or why the pane can't be folded now. */
@Composable
internal fun changesPaneToggleLabel(state: ChangesPaneState): String = when (val need = state.need) {
    is ChangesPaneNeed.OperationInProgress -> stringResource(Res.string.changes_pane_held_operation, operationName(need.operation))
    ChangesPaneNeed.Comparison -> stringResource(Res.string.changes_pane_held_comparison)
    null -> stringResource(if (state.isFolded) Res.string.changes_pane_show else Res.string.changes_pane_hide)
}

@Composable
private fun operationName(operation: Operation): String = stringResource(
    when (operation) {
        Operation.MERGE -> Res.string.changes_pane_operation_merge
        Operation.REBASE -> Res.string.changes_pane_operation_rebase
        Operation.CHERRY_PICK -> Res.string.changes_pane_operation_cherry_pick
        Operation.REVERT -> Res.string.changes_pane_operation_revert
        Operation.SQUASH -> Res.string.changes_pane_operation_squash
    }
)
