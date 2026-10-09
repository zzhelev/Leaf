// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.sort

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.check
import dev.app.leaf.app.generated.resources.folder
import dev.app.leaf.app.generated.resources.sort
import dev.app.leaf.app.generated.resources.sort_menu_tooltip
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.isDark
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val REOPEN_GUARD_MS = 300

sealed interface SortMenuItem {
    data class Title(val text: String) : SortMenuItem

    data class Option(
        val label: String,
        val isChecked: Boolean,
        val hint: String? = null,
        /** Whether choosing the option closes the menu. Toggles and order changes keep it open. */
        val closesMenu: Boolean = false,
        val onClick: () -> Unit,
    ) : SortMenuItem

    data object Divider : SortMenuItem

    /** A line of secondary text that can't be chosen, such as where to find more options. */
    data class Note(val text: String) : SortMenuItem
}

/**
 * The "Sort and group" button of a section header, with its menu. Muted by default; accent-colored when [isActive],
 * with an optional [activeLabel] (for example "Last commit") and a folder icon when [showFolderIcon]. Its click never
 * reaches the parent, so it doesn't expand or collapse the section. Other header menus pass their own [icon] and
 * [tooltip], such as the Worktrees section's base branch.
 */
@Composable
fun SortMenuButton(
    isActive: Boolean,
    activeLabel: String?,
    showFolderIcon: Boolean,
    menuItems: List<SortMenuItem>,
    modifier: Modifier = Modifier,
    icon: DrawableResource = Res.drawable.sort,
    tooltip: String = stringResource(Res.string.sort_menu_tooltip),
) {
    var showMenu by remember { mutableStateOf(false) }
    // A press on the button while the menu is open first dismisses the menu as an outside click; this keeps the
    // button's own click from opening it again right away
    var dismissedAt by remember { mutableStateOf(0L) }
    val accent = MaterialTheme.colors.primaryVariant

    Box(modifier = modifier) {
        DelayedTooltip(tooltip) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                            showMenu = !showMenu
                        }
                    }
                    .handOnHover()
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive && activeLabel != null) {
                    Text(
                        text = activeLabel,
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .hiddenWhenTruncated()
                            .padding(end = 4.dp),
                    )
                }

                if (isActive && showFolderIcon) {
                    Icon(
                        painter = painterResource(Res.drawable.folder),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(13.dp),
                    )
                }

                Icon(
                    painter = painterResource(icon),
                    contentDescription = tooltip,
                    tint = if (isActive) accent else MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (showMenu) {
            SortMenuPopup(
                items = menuItems,
                onDismissRequest = {
                    showMenu = false
                    dismissedAt = System.currentTimeMillis()
                },
            )
        }
    }
}

/** Shows the content in full or not at all, so a narrow header drops the label instead of cutting it. */
internal fun Modifier.hiddenWhenTruncated() = layout { measurable, constraints ->
    if (measurable.maxIntrinsicWidth(constraints.maxHeight) > constraints.maxWidth) {
        layout(0, 0) {}
    } else {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * A popup anchored below its parent, right-aligned with it, unless a [positionProvider] places it. Arrow keys move
 * between options; Esc closes it.
 */
@Composable
internal fun SortMenuPopup(
    items: List<SortMenuItem>,
    onDismissRequest: () -> Unit,
    positionProvider: PopupPositionProvider? = null,
) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val belowRightAligned = remember(gap) { BelowRightAlignedPositionProvider(gap) }

    Popup(
        properties = PopupProperties(focusable = true),
        popupPositionProvider = positionProvider ?: belowRightAligned,
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
                .width(IntrinsicSize.Max)
                .widthIn(min = 220.dp)
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
            for (item in items) {
                when (item) {
                    is SortMenuItem.Title -> MenuTitle(item.text)
                    is SortMenuItem.Option -> MenuOption(
                        option = item,
                        onClick = {
                            item.onClick()

                            if (item.closesMenu) {
                                onDismissRequest()
                            }
                        },
                    )

                    SortMenuItem.Divider -> MenuDivider()
                    is SortMenuItem.Note -> MenuNote(item.text)
                }
            }
        }
    }
}

@Composable
private fun MenuTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun MenuOption(
    option: SortMenuItem.Option,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) MaterialTheme.colors.onBackground.copy(alpha = 0.08f) else Color.Transparent)
            .onPreviewKeyEvent { keyEvent ->
                val isActivationKey = keyEvent.key == Key.Enter ||
                        keyEvent.key == Key.NumPadEnter ||
                        keyEvent.key == Key.Spacebar

                if (isActivationKey) {
                    // Handle the key here so the click handler doesn't run it a second time on key up
                    if (keyEvent.type == KeyEventType.KeyDown) onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = if (option.hint == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 8.dp, top = if (option.hint == null) 0.dp else 2.dp)
                .size(14.dp),
        ) {
            if (option.isChecked) {
                Icon(
                    painter = painterResource(Res.drawable.check),
                    contentDescription = null,
                    tint = MaterialTheme.colors.primaryVariant,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Column {
            Text(
                text = option.label,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (option.hint != null) {
                Text(
                    text = option.hint,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MenuNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colors.onBackground.copy(alpha = 0.12f))
    )
}

private class BelowRightAlignedPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)

        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height > windowSize.height) {
            // Not enough room below: open above the button, or stick to the bottom of the window
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        } else {
            below
        }

        return IntOffset(x, y)
    }
}
