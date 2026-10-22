// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.clone_choose_branch
import dev.app.leaf.app.generated.resources.clone_choose_branch_branches
import dev.app.leaf.app.generated.resources.clone_choose_branch_cancel
import dev.app.leaf.app.generated.resources.clone_choose_branch_count
import dev.app.leaf.app.generated.resources.clone_choose_branch_default
import dev.app.leaf.app.generated.resources.clone_choose_branch_empty
import dev.app.leaf.app.generated.resources.clone_choose_branch_loading
import dev.app.leaf.app.generated.resources.clone_choose_branch_loading_unknown_host
import dev.app.leaf.app.generated.resources.clone_choose_branch_none
import dev.app.leaf.app.generated.resources.clone_choose_branch_tags
import dev.app.leaf.app.generated.resources.clone_choose_branch_try_again
import dev.app.leaf.domain.clone.BranchChoice
import dev.app.leaf.domain.clone.BranchChoices
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.BelowLeftAlignedPositionProvider
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.getErrorText
import dev.app.leaf.viewmodels.BranchChoicesState
import org.jetbrains.compose.resources.stringResource

// The Clone dialog's "Choose…": a button beside the Branch field and the list under it (layout A of 2026-10-19).
// Nothing here asks the remote: the view model does, once per click on the button.

private val POPUP_MAX_HEIGHT = 280.dp
private val ROW_HEIGHT = 28.dp
private const val FADED_BACKGROUND = 0.08f

/** The button beside the Branch field. */
@Composable
internal fun ChooseBranchButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    PrimaryButton(
        text = stringResource(Res.string.clone_choose_branch),
        modifier = modifier
            .width(96.dp)
            .height(38.dp)
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
        backgroundColor = Color.Transparent,
        textColor = MaterialTheme.colors.onBackground,
        onClick = onClick,
    )
}

/**
 * The list of branches and tags under the Branch field, [width] wide. It doesn't take the focus: the field keeps it,
 * so what is typed there filters the list ([choices] is the list for [BranchChoicesState.Loaded]) and the keys
 * (`branchChoicesKey`) move through it. [highlighted] is the row that Enter would pick, in the order of
 * `branches + tags`, or -1.
 *
 * Put it in the row of the field and the button: it opens under its parent, left-aligned with it.
 */
@Composable
internal fun BranchChoicesPopup(
    state: BranchChoicesState,
    choices: BranchChoices?,
    highlighted: Int,
    width: Dp,
    onPick: (String) -> Unit,
    onTryAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state is BranchChoicesState.Closed) {
        return
    }

    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
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
            when (state) {
                BranchChoicesState.Closed -> {}
                is BranchChoicesState.Loading -> Loading(state.host, onCancel)
                is BranchChoicesState.Failed -> Failed(state, onTryAgain)
                is BranchChoicesState.Loaded -> if (choices != null) Choices(choices, highlighted, onPick)
            }
        }
    }
}

@Composable
private fun Loading(host: String?, onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colors.primaryVariant,
        )

        Text(
            text = if (host != null) {
                stringResource(Res.string.clone_choose_branch_loading, host)
            } else {
                stringResource(Res.string.clone_choose_branch_loading_unknown_host)
            },
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )

        PopupAction(stringResource(Res.string.clone_choose_branch_cancel), onClick = onCancel)
    }
}

@Composable
private fun Failed(state: BranchChoicesState.Failed, onTryAgain: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        // git's output can be long, and the buttons below it must stay in reach
        // As the dialog shows a clone's error
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.error)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = state.error.getErrorText(),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onError,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        PopupAction(
            text = stringResource(Res.string.clone_choose_branch_try_again),
            onClick = onTryAgain,
            filled = true,
            modifier = Modifier.padding(top = 8.dp).align(Alignment.End),
        )
    }
}

/** The list: the default branch first, then the branches, then the tags, and under it how many of them match. */
@Composable
private fun ColumnScope.Choices(choices: BranchChoices, highlighted: Int, onPick: (String) -> Unit) {
    val listState = rememberLazyListState()
    val rows = remember(choices) { choices.branches + choices.tags }

    if (rows.isEmpty()) {
        Text(
            text = stringResource(
                if (choices.total == 0) Res.string.clone_choose_branch_empty else Res.string.clone_choose_branch_none,
            ),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )

        return
    }

    LaunchedEffect(highlighted, choices) {
        if (highlighted >= 0) {
            listState.keepInView(itemIndexOf(highlighted, choices))
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp),
    ) {
        if (choices.branches.isNotEmpty()) {
            item(key = "branches-header") { ChoiceGroupHeader(stringResource(Res.string.clone_choose_branch_branches)) }
        }

        itemsIndexed(choices.branches, key = { _, choice -> "branch " + choice.name }) { index, choice ->
            ChoiceRow(choice, isHighlighted = index == highlighted, onPick = { onPick(choice.name) })
        }

        if (choices.tags.isNotEmpty()) {
            item(key = "tags-header") { ChoiceGroupHeader(stringResource(Res.string.clone_choose_branch_tags)) }
        }

        itemsIndexed(choices.tags, key = { _, choice -> "tag " + choice.name }) { index, choice ->
            ChoiceRow(
                choice,
                isHighlighted = choices.branches.size + index == highlighted,
                onPick = { onPick(choice.name) },
            )
        }
    }

    Divider()

    Text(
        text = stringResource(Res.string.clone_choose_branch_count, choices.matching, choices.total),
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** A group's name over its rows, as the clone dialog's list and the comparison's list of refs show them. */
@Composable
internal fun ChoiceGroupHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** A branch or a tag, with what was typed in bold, and the default branch marked. */
@Composable
private fun ChoiceRow(choice: BranchChoice, isHighlighted: Boolean, onPick: () -> Unit) {
    PickRow(
        name = choice.name,
        matchStart = choice.matchStart,
        matchLength = choice.matchLength,
        isHighlighted = isHighlighted,
        onPick = onPick,
    ) {
        if (choice.isDefault) {
            Spacer(Modifier.width(8.dp))

            Text(
                text = stringResource(Res.string.clone_choose_branch_default),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * A row of a list that a field keeps the focus for, with [name] and what was typed in bold, then [trailing]. It's
 * picked by a tap, not a `clickable`, which would take the focus from the field.
 */
@Composable
internal fun PickRow(
    name: String,
    matchStart: Int,
    matchLength: Int,
    isHighlighted: Boolean,
    onPick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val currentOnPick by rememberUpdatedState(onPick)
    val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primaryVariant)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(
                if (isHighlighted || isHovered) {
                    MaterialTheme.colors.onBackground.copy(alpha = FADED_BACKGROUND)
                } else {
                    Color.Transparent
                }
            )
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(name) { detectTapGestures { currentOnPick() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = isHighlighted
                onClick { currentOnPick(); true }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                append(name)

                if (matchLength > 0) {
                    addStyle(matchStyle, matchStart, matchStart + matchLength)
                }
            },
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        trailing()
    }
}

/** Cancel and Try again: a tap, as in the rows, so the Branch field keeps the focus. */
@Composable
private fun PopupAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (filled) MaterialTheme.colors.primary else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) { detectTapGestures { currentOnClick() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { currentOnClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = if (filled) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Where a row of [BranchChoices.branches] `+` [BranchChoices.tags] is in the list, which has a header per group. */
internal fun itemIndexOf(row: Int, choices: BranchChoices): Int {
    val branchesHeader = if (choices.branches.isNotEmpty()) 1 else 0

    return if (row < choices.branches.size) {
        branchesHeader + row
    } else {
        branchesHeader + choices.branches.size + 1 + (row - choices.branches.size)
    }
}

/** Scrolls only when the item isn't wholly in view, so that moving through the rows doesn't jump. */
private fun LazyListState.keepInView(index: Int) {
    val layout = layoutInfo
    val item = layout.visibleItemsInfo.firstOrNull { it.index == index }

    val isWhollyVisible = item != null &&
            item.offset >= layout.viewportStartOffset &&
            item.offset + item.size <= layout.viewportEndOffset

    if (!isWhollyVisible) {
        // Applied at the next layout: this runs from an effect, which can be inside one in an offscreen scene
        requestScrollToItem(index)
    }
}

/**
 * What a key does to the list while it shows rows, in the Branch field's `onPreviewKeyEvent`: the arrows move the
 * highlight, Enter picks the highlighted row. Enter picks nothing while no row was moved to, as what was typed may
 * be the whole name. Returns the new highlight, and whether the key was used.
 */
internal fun branchChoicesKey(
    key: Key,
    highlighted: Int,
    rowCount: Int,
): BranchChoicesKeyResult {
    if (rowCount == 0) {
        return BranchChoicesKeyResult(highlighted, used = false)
    }

    return when (key) {
        Key.DirectionDown ->
            BranchChoicesKeyResult((highlighted + 1).coerceAtMost(rowCount - 1), used = true)

        Key.DirectionUp ->
            BranchChoicesKeyResult((highlighted - 1).coerceAtLeast(0), used = true)

        Key.Enter, Key.NumPadEnter ->
            (highlighted in 0 until rowCount).let { BranchChoicesKeyResult(highlighted, used = it, picks = it) }

        else -> BranchChoicesKeyResult(highlighted, used = false)
    }
}

internal data class BranchChoicesKeyResult(val highlighted: Int, val used: Boolean, val picks: Boolean = false)

/** The Branch field's keys for the list ([branchChoicesKey]): [onPick] gets the index of the row that Enter picks. */
internal fun Modifier.branchChoicesKeys(
    highlighted: Int,
    rowCount: Int,
    onHighlight: (Int) -> Unit,
    onPick: (Int) -> Unit,
): Modifier = onPreviewKeyEvent { keyEvent ->
    if (keyEvent.type != KeyEventType.KeyDown) {
        return@onPreviewKeyEvent false
    }

    val result = branchChoicesKey(keyEvent.key, highlighted, rowCount)

    if (result.picks) onPick(highlighted) else onHighlight(result.highlighted)

    result.used
}

/**
 * A press anywhere in the dialog ([dialog]'s layout) but on the Branch field and its button ([branchRow]) closes the
 * list while it's [isOpen], which stops git if it still waits. A non-focusable popup gets no outside-press events, so
 * this looks at the presses first, without consuming them. The list is a layer of its own, so presses on it don't come
 * here. Put it on the layout that [dialog] gives, after its `onGloballyPositioned`.
 */
internal fun Modifier.closingBranchChoicesOnPressOutside(
    isOpen: () -> Boolean,
    dialog: () -> LayoutCoordinates?,
    branchRow: () -> LayoutCoordinates?,
    onClose: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)

            if (event.type != PointerEventType.Press || !isOpen()) {
                continue
            }

            val dialogLayout = dialog()
            val rowLayout = branchRow()
            val isOnBranchRow = dialogLayout != null && dialogLayout.isAttached && rowLayout != null && rowLayout.isAttached &&
                    rowLayout.boundsInWindow().contains(dialogLayout.localToWindow(event.changes.first().position))

            if (!isOnBranchRow) {
                onClose()
            }
        }
    }
}
