// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.theme.onBackgroundSecondary

/*
 * Fork-only: what the commit field's suggestions share, the co-authors' (CoAuthorSuggestions.kt) and the Conventional
 * Commits types' and scopes' (CommitTypeSuggestions.kt): their keys, their popup above the field, their rows.
 */

private val ROW_HEIGHT = 28.dp
private const val FADED_BACKGROUND = 0.08f

/** What a key does while suggestions show. */
internal sealed interface SuggestionKey {
    data class Highlight(val index: Int) : SuggestionKey
    data class Pick(val index: Int) : SuggestionKey
}

/**
 * The row highlighted when suggestions open: the first once something is [typed], so that Enter picks it, and none
 * before, where Enter keeps its own meaning (for a co-author's line, ending the list: [coAuthorEnter]).
 */
internal fun firstHighlighted(typed: String?): Int = if (typed.isNullOrEmpty()) -1 else 0

/**
 * The keys that suggestions take from the field, which keeps the focus: ↓ and ↑ move the highlight within [count] rows
 * ([highlighted] is -1 for none), Enter picks the highlighted one, and Tab the highlighted or the first. With a
 * modifier, or any other key, or Enter with nothing highlighted, the field gets it: Cmd or Ctrl and Enter still commits.
 * Esc goes through the tab, which closes the suggestions as a closeable view.
 */
internal fun suggestionKey(event: KeyEvent, highlighted: Int, count: Int): SuggestionKey? {
    if (event.type != KeyEventType.KeyDown || event.hasModifier()) {
        return null
    }

    return when (event.key) {
        Key.DirectionDown -> SuggestionKey.Highlight((highlighted + 1).coerceAtMost(count - 1))
        Key.DirectionUp -> SuggestionKey.Highlight((highlighted - 1).coerceAtLeast(0))
        Key.Enter, Key.NumPadEnter -> if (highlighted >= 0) SuggestionKey.Pick(highlighted) else null
        Key.Tab -> SuggestionKey.Pick(highlighted.coerceAtLeast(0))
        else -> null
    }
}

/** Enter pressed alone, which continues or ends a list of co-authors ([coAuthorEnter]). */
internal fun isPlainEnter(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && !event.hasModifier() && (event.key == Key.Enter || event.key == Key.NumPadEnter)

private fun KeyEvent.hasModifier() = isMetaPressed || isCtrlPressed || isAltPressed || isShiftPressed

/**
 * Suggestions, above the field they belong to, as the commit field sits at the bottom of the window; below it when
 * there's no room above. A popup that takes no focus, so the field keeps it and its keys ([suggestionKey]).
 */
@Composable
internal fun SuggestionsPopup(content: @Composable () -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gap) { AboveLeftAlignedPositionProvider(gap) }

    Popup(
        properties = PopupProperties(focusable = false),
        popupPositionProvider = positionProvider,
        content = content,
    )
}

/** The box of suggestions, [width] wide: the popup's content, apart from the popup, which an offscreen scene doesn't draw. */
@Composable
internal fun SuggestionsBox(width: Dp, content: @Composable ColumnScope.() -> Unit) {
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
            .padding(vertical = 4.dp),
        content = content,
    )
}

/**
 * One suggestion, highlighted when [isHighlighted]. Picked by a tap rather than a `clickable`, which would take the
 * focus from the field, as Clone's branch list does. The pointer over it highlights it.
 */
@Composable
internal fun SuggestionRow(
    key: Any,
    isHighlighted: Boolean,
    onHover: () -> Unit,
    onPick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnHover by rememberUpdatedState(onHover)

    LaunchedEffect(isHovered) {
        if (isHovered) {
            currentOnHover()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(
                if (isHighlighted) MaterialTheme.colors.onBackground.copy(alpha = FADED_BACKGROUND) else Color.Transparent
            )
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(key) { detectTapGestures { currentOnPick() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = isHighlighted
                onClick { currentOnPick(); true }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The muted line under the rows. */
@Composable
internal fun SuggestionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** [text] with the first place that [query] is in it, ignoring case, in [style]. */
internal fun withMatch(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)

    val trimmed = query.trim()
    val start = if (trimmed.isEmpty()) -1 else text.indexOf(trimmed, ignoreCase = true)

    if (start >= 0) {
        addStyle(style, start, start + trimmed.length)
    }
}

/** Places the popup over its anchor, left-aligned with it, or under it when there's no room above. */
internal class AboveLeftAlignedPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)

        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (above < 0) {
            (anchorBounds.bottom + gap).coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        } else {
            above
        }

        return IntOffset(x, y)
    }
}
