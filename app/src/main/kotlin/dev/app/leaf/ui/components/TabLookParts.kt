// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.edit
import dev.app.leaf.app.generated.resources.palette
import dev.app.leaf.app.generated.resources.tab_color
import dev.app.leaf.app.generated.resources.tab_color_none
import dev.app.leaf.app.generated.resources.tab_menu_close
import dev.app.leaf.app.generated.resources.tab_menu_rename
import dev.app.leaf.app.generated.resources.tab_menu_rename_hint
import dev.app.leaf.app.generated.resources.tab_menu_reset_name
import dev.app.leaf.app.generated.resources.undo
import dev.app.leaf.domain.tabs.TAB_COLOR_COUNT
import dev.app.leaf.domain.tabs.TabLook
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.graphLanes
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.addContextMenu
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The stripe along the top of a tab with a colour (fork-only, upstream issue 242). */
val TAB_COLOR_STRIPE_HEIGHT = 3.dp

/** The colour of graph lane [color], from 1, in the current style, or none. */
@Composable
fun tabColor(color: Int?): Color? = color?.let { MaterialTheme.graphLanes.getOrNull(it - 1) }

/**
 * A tab's right-click menu (fork-only, upstream issue 242): rename it, give it a colour, name it after its repository
 * again, and close it. A tab without a repository ([look] null) can only be closed.
 */
fun tabContextMenuItems(
    look: TabLook?,
    onRename: () -> Unit,
    onSetColor: (Int?) -> Unit,
    onResetName: () -> Unit,
    onClose: () -> Unit,
): List<ContextMenuElement> = buildList {
    if (look != null) {
        addContextMenu(
            composableLabel = { stringResource(Res.string.tab_menu_rename) },
            icon = { painterResource(Res.drawable.edit) },
            onClick = onRename,
            hint = { stringResource(Res.string.tab_menu_rename_hint) },
        )

        add(
            ContextMenuElement.ContextCustomEntry { dismiss ->
                TabColorsRow(
                    selected = look.color,
                    onPick = { color ->
                        dismiss()
                        onSetColor(color)
                    },
                )
            }
        )

        if (look.name != null) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.tab_menu_reset_name) },
                icon = { painterResource(Res.drawable.undo) },
                onClick = onResetName,
            )
        }

        add(ContextMenuElement.ContextSeparator)
    }

    addContextMenu(
        composableLabel = { stringResource(Res.string.tab_menu_close) },
        icon = { painterResource(Res.drawable.close) },
        onClick = onClose,
    )
}

/** No colour, then the style's graph lanes, with a ring around the one that the tab has. */
@Composable
fun TabColorsRow(selected: Int?, onPick: (Int?) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // As a menu entry's icon
        Icon(
            painter = painterResource(Res.drawable.palette),
            contentDescription = null,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(16.dp),
            tint = MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.8f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val noColor = MaterialTheme.colors.onBackgroundSecondary

            ColorSwatch(
                description = stringResource(Res.string.tab_color_none),
                isSelected = selected == null,
                onClick = { onPick(null) },
                inner = Modifier
                    .border(1.5.dp, noColor, CircleShape)
                    .drawBehind {
                        val inset = size.width * 0.22f

                        drawLine(
                            color = noColor,
                            start = Offset(inset, size.height - inset),
                            end = Offset(size.width - inset, inset),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    },
            )

            MaterialTheme.graphLanes.take(TAB_COLOR_COUNT).forEachIndexed { index, lane ->
                val color = index + 1

                ColorSwatch(
                    description = stringResource(Res.string.tab_color, color),
                    isSelected = selected == color,
                    onClick = { onPick(color) },
                    inner = Modifier
                        .clip(CircleShape)
                        .background(lane),
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(description: String, isSelected: Boolean, onClick: () -> Unit, inner: Modifier) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    val ring = MaterialTheme.colors.onBackground

    Box(
        modifier = Modifier
            .size(24.dp)
            .run {
                when {
                    isSelected -> border(2.dp, ring, CircleShape)
                    isHovered -> border(2.dp, ring.copy(alpha = 0.35f), CircleShape)
                    else -> this
                }
            }
            .hoverable(hoverInteraction)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .handOnHover()
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .padding(3.dp)
            .then(inner),
    )
}

/**
 * The field that renames a tab in place, with its name selected. Enter keeps what was typed, and so does a click
 * elsewhere; Esc leaves the name as it was, and Tab does nothing. [onDone] gets the text, or null for Esc, and whether
 * a key ended it.
 */
@Composable
fun TabNameField(
    name: String,
    onDone: (typed: String?, byKey: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // The title bar's tabs (docs/fork/window-chrome.md) are drawn on the frame's colours
    textColor: Color = MaterialTheme.colors.onBackground,
) {
    var value by remember { mutableStateOf(TextFieldValue(name, selection = TextRange(0, name.length))) }
    var isDone by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun done(typed: String?, byKey: Boolean) {
        if (!isDone) {
            isDone = true
            onDone(typed, byKey)
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium, color = textColor),
        cursorBrush = SolidColor(textColor),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    done(value.text, byKey = false)
                }
            }
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        done(value.text, byKey = true)
                        true
                    }

                    event.matchesBinding(KeybindingOption.EXIT) -> {
                        done(null, byKey = true)
                        true
                    }

                    // Tab and Ctrl+Tab would move the focus on, which ends the rename: the field keeps it
                    event.key == Key.Tab -> true

                    else -> false
                }
            }
            .border(1.dp, MaterialTheme.colors.primaryVariant, RoundedCornerShape(3.dp))
            .background(MaterialTheme.colors.background, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .widthIn(min = 120.dp, max = 400.dp),
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
