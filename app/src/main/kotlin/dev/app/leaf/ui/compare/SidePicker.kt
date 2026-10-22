// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.compare.RefChoice
import dev.app.leaf.domain.compare.RefChoices
import dev.app.leaf.domain.compare.RefKind
import dev.app.leaf.repositoryopen.SidePickerState
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.BelowLeftAlignedPositionProvider
import dev.app.leaf.ui.dialogs.ChoiceGroupHeader
import dev.app.leaf.ui.dialogs.PickRow
import dev.app.leaf.ui.dialogs.branchChoicesKeys
import org.jetbrains.compose.resources.stringResource

// Changing a side of a comparison (fork-only, docs/fork/compare.md, 7.1a): the side's name turns into a field, and the
// list of the tab's refs opens under the pane's header, as the clone dialog's branch list does under its field.

private val POPUP_MAX_HEIGHT = 300.dp

/**
 * A side's line while its list is open: the badge, then a field in place of the name, which keeps the focus, so that
 * what is typed filters the list and ↓, ↑ and Enter move through it ([highlighted]). Esc reaches the tab's handler first,
 * which closes the list; the field's own handles it where there is no tab. Leaving the field closes the list too.
 */
@Composable
internal fun SidePickerLine(
    letter: CompareLetter,
    picker: SidePickerState,
    highlighted: Int,
    onHighlight: (Int) -> Unit,
    onTextChange: (String) -> Unit,
    onPick: (RefChoice) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(picker.text, TextRange(picker.text.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    val windowInfo = LocalWindowInfo.current
    val rows = picker.choices.rows

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        CompareBadge(letter)

        BasicTextField(
            value = value,
            onValueChange = {
                value = it

                if (it.text != picker.text) onTextChange(it.text)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.body2.copy(
                color = MaterialTheme.colors.onBackground,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(MaterialTheme.colors.onBackground),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focus ->
                    if (focus.isFocused) {
                        hadFocus = true
                    } else if (hadFocus && windowInfo.isWindowFocused) {
                        // Somewhere else was clicked
                        onClose()
                    }
                }
                .branchChoicesKeys(
                    highlighted = highlighted,
                    rowCount = rows.size,
                    onHighlight = onHighlight,
                    onPick = { index -> rows.getOrNull(index)?.let(onPick) },
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { field ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colors.background)
                        .border(1.dp, MaterialTheme.colors.primaryVariant, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.compare_pick_side_hint),
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                        )
                    }

                    field()
                }
            },
        )
    }
}

/**
 * The list of refs under the pane's header, [width] wide: a commit for a typed hash or revision, then local branches,
 * tags and remote branches, and how many match. It doesn't take the focus, which the side's field keeps.
 */
@Composable
internal fun RefChoicesPopup(
    choices: RefChoices,
    highlighted: Int,
    width: Dp,
    onPick: (RefChoice) -> Unit,
) {
    val gap = with(LocalDensity.current) { 2.dp.roundToPx() }
    val positionProvider = remember(gap) { BelowLeftAlignedPositionProvider(gap) }

    Popup(
        properties = PopupProperties(focusable = false),
        popupPositionProvider = positionProvider,
    ) {
        Column(
            modifier = Modifier
                .dropShadow(
                    shape = RoundedCornerShape(4.dp),
                    shadow = Shadow(radius = 5.dp, spread = 6.dp, color = Color(0x20000000)),
                )
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colors.background)
                .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.2f), MaterialTheme.shapes.small)
                .width(width)
                .heightIn(max = POPUP_MAX_HEIGHT),
        ) {
            val listState = rememberLazyListState()
            val rows = choices.rows

            if (rows.isNotEmpty()) {
                LaunchedEffect(highlighted, choices) {
                    if (highlighted >= 0) listState.keepRowInView(itemIndexOf(highlighted, choices))
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp),
                ) {
                    var offset = 0

                    for (group in choices.groups) {
                        val first = offset

                        item(key = "header " + group.kind) { ChoiceGroupHeader(groupName(group.kind)) }

                        itemsIndexed(group.choices, key = { _, choice -> group.kind.name + " " + choice.side.revision }) { index, choice ->
                            PickRow(
                                name = choice.name,
                                matchStart = choice.matchStart,
                                matchLength = choice.matchLength,
                                isHighlighted = first + index == highlighted,
                                onPick = { onPick(choice) },
                            ) {
                                val detail = choice.detail

                                if (detail != null) {
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.body2,
                                        color = MaterialTheme.colors.onBackgroundSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                        }

                        offset += group.choices.size
                    }
                }

                Divider()
            }

            Text(
                text = if (rows.isEmpty()) {
                    stringResource(Res.string.compare_pick_none)
                } else {
                    stringResource(Res.string.compare_pick_count, rows.size - commitRows(choices), choices.total)
                },
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun groupName(kind: RefKind): String = stringResource(
    when (kind) {
        RefKind.COMMIT -> Res.string.compare_pick_group_commit
        RefKind.BRANCH -> Res.string.compare_pick_group_branches
        RefKind.TAG -> Res.string.compare_pick_group_tags
        RefKind.REMOTE_BRANCH -> Res.string.compare_pick_group_remote_branches
    }
)

/** The resolved commit's row, which the count of matching refs leaves out. */
private fun commitRows(choices: RefChoices): Int = choices.groups.filter { it.kind == RefKind.COMMIT }.sumOf { it.choices.size }

/** Where row [row] of [RefChoices.rows] is in the list, which has a header per group. */
internal fun itemIndexOf(row: Int, choices: RefChoices): Int {
    var rowsBefore = 0

    choices.groups.forEachIndexed { groupIndex, group ->
        if (row < rowsBefore + group.choices.size) {
            // A header per group so far, this one's too
            return rowsBefore + groupIndex + 1 + (row - rowsBefore)
        }

        rowsBefore += group.choices.size
    }

    return -1
}

/** Scrolls only when the item isn't wholly in view, as the clone dialog's list does. */
private fun LazyListState.keepRowInView(index: Int) {
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
    val isWhollyVisible = item != null &&
            item.offset >= layout.viewportStartOffset &&
            item.offset + item.size <= layout.viewportEndOffset

    if (!isWhollyVisible && index >= 0) {
        requestScrollToItem(index)
    }
}
