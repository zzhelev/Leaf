// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.check
import dev.app.leaf.app.generated.resources.expand_more
import dev.app.leaf.app.generated.resources.menu_worktree_tooltip
import dev.app.leaf.app.generated.resources.side_pane_worktrees_title
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.ignoreKeyEvents
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.isDark
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Height of the toolbar's buttons, which the picker matches. */
private val PICKER_HEIGHT = 56.dp

private val PICKER_LIST_WIDTH = 340.dp

/** Height of a worktree in the list, which has two lines, as in the side panel. */
private val PICKER_ROW_HEIGHT = 46.dp

/** A click on the picker right after a click outside closed its list doesn't open it again. */
private const val REOPEN_GUARD_MS = 300

/**
 * The repository's worktrees, for the toolbar's picker (fork-only). [baseBranch] is what they're compared to, for the
 * tooltips.
 */
data class WorktreePickerState(val rows: List<WorktreeRow>, val baseBranch: String?)

/**
 * The toolbar's worktree picker (fork-only): the worktree that the tab shows, with what it has checked out, and a list
 * of the repository's worktrees that switches the tab to the one picked.
 */
@Composable
fun WorktreePicker(
    state: WorktreePickerState,
    onSwitchTo: (Worktree) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.rows.firstOrNull { it.info.worktree.isCurrent } ?: return
    var showList by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableLongStateOf(0L) }

    Box(modifier = modifier) {
        InstantTooltip(
            text = stringResource(Res.string.menu_worktree_tooltip),
            enabled = !showList,
        ) {
            Row(
                modifier = Modifier
                    .height(PICKER_HEIGHT)
                    .widthIn(min = 120.dp, max = 240.dp)
                    .ignoreKeyEvents()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colors.surface)
                    .handMouseClickable {
                        if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                            showList = !showList
                        }
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorktreeIcon(current.info.worktree, modifier = Modifier.padding(end = 8.dp))

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.body2,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colors.onBackground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = current.head.text(),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    painter = painterResource(Res.drawable.expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp),
                )
            }
        }

        if (showList) {
            WorktreePickerList(
                state = state,
                onSwitchTo = onSwitchTo,
                onDismissRequest = {
                    showList = false
                    dismissedAt = System.currentTimeMillis()
                },
            )
        }
    }
}

/** The list of the picker, below it. Arrow keys move between worktrees, Enter switches to one, and Esc closes it. */
@Composable
private fun WorktreePickerList(
    state: WorktreePickerState,
    onSwitchTo: (Worktree) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gap) { BelowLeftAlignedPositionProvider(gap) }

    Popup(
        properties = PopupProperties(focusable = true),
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
    ) {
        val focusRequester = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Column(
            modifier = Modifier
                .dropShadow(
                    shape = RoundedCornerShape(4.dp),
                    shadow = Shadow(radius = 5.dp, spread = 6.dp, color = Color(0x20000000)),
                )
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colors.background)
                .run {
                    if (MaterialTheme.colors.isDark) {
                        border(2.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), MaterialTheme.shapes.small)
                    } else {
                        this
                    }
                }
                .width(PICKER_LIST_WIDTH)
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    when {
                        keyEvent.type != KeyEventType.KeyDown -> false
                        keyEvent.matchesBinding(KeybindingOption.EXIT) -> {
                            onDismissRequest()
                            true
                        }

                        keyEvent.matchesBinding(KeybindingOption.DOWN) -> focusManager.moveFocus(FocusDirection.Next)
                        keyEvent.matchesBinding(KeybindingOption.UP) -> focusManager.moveFocus(FocusDirection.Previous)
                        else -> false
                    }
                },
        ) {
            Text(
                text = stringResource(Res.string.side_pane_worktrees_title),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            )

            for (row in state.rows) {
                DelayedTooltip(worktreeTooltip(row.info, state.baseBranch)) {
                    WorktreePickerRow(
                        row = row,
                        onClick = {
                            onSwitchTo(row.info.worktree)
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}

/**
 * A worktree in the picker's list. The tab's has a check mark, and closes the list. One the tab can't switch to (its
 * folder is missing, or it's a bare repository's) is greyed out and does nothing.
 */
@Composable
private fun WorktreePickerRow(row: WorktreeRow, onClick: () -> Unit) {
    val worktree = row.info.worktree
    val isEnabled = worktree.canSwitchTo || worktree.isCurrent
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PICKER_ROW_HEIGHT)
            .background(if (isFocused) MaterialTheme.colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .onPreviewKeyEvent { keyEvent ->
                val isActivationKey = keyEvent.key == Key.Enter ||
                        keyEvent.key == Key.NumPadEnter ||
                        keyEvent.key == Key.Spacebar

                if (isEnabled && isActivationKey) {
                    // Handle the key here so the click handler doesn't run it a second time on key up
                    if (keyEvent.type == KeyEventType.KeyDown) onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                enabled = isEnabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ) { onClick() }
            .alpha(if (isEnabled) 1f else PRUNABLE_ALPHA)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .size(14.dp),
        ) {
            if (worktree.isCurrent) {
                Icon(
                    painter = painterResource(Res.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colors.primaryVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        WorktreeIcon(worktree, modifier = Modifier.padding(end = 8.dp))

        WorktreeRowLines(row, modifier = Modifier.weight(1f))
    }
}

/** Places a popup below its anchor, left-aligned with it, or above it when there's no room below. */
internal class BelowLeftAlignedPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)

        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height > windowSize.height) {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        } else {
            below
        }

        return IntOffset(x, y)
    }
}
