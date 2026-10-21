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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.co_author_suggestions_more
import dev.app.leaf.app.generated.resources.co_author_suggestions_type_someone
import dev.app.leaf.domain.commitmessage.CoAuthor
import dev.app.leaf.domain.commitmessage.CoAuthorLine
import dev.app.leaf.domain.commitmessage.MAX_CO_AUTHOR_CHOICES
import dev.app.leaf.domain.commitmessage.coAuthorChoices
import dev.app.leaf.domain.commitmessage.coAuthorEnter
import dev.app.leaf.domain.commitmessage.coAuthorLineAt
import dev.app.leaf.domain.commitmessage.withCoAuthor
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.AvatarImage
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val ROW_HEIGHT = 28.dp
private const val FADED_BACKGROUND = 0.08f

/**
 * The co-author suggestions for the commit field (fork-only): the `Co-authored-by` line that the caret is on, and the
 * first [MAX_CO_AUTHOR_CHOICES] of the [total] people who match it.
 */
internal data class CoAuthorSuggestion(val line: CoAuthorLine, val choices: List<CoAuthor>, val total: Int)

/** What the field suggests for [value], or null: no `Co-authored-by` line under the caret, or no one who matches it. */
internal fun coAuthorSuggestion(value: TextFieldValue, candidates: List<CoAuthor>): CoAuthorSuggestion? {
    if (!value.selection.collapsed) {
        return null
    }

    val line = coAuthorLineAt(value.text, value.selection.end) ?: return null
    val choices = coAuthorChoices(value.text, line, candidates)

    return if (choices.isEmpty()) null else CoAuthorSuggestion(line, choices.take(MAX_CO_AUTHOR_CHOICES), choices.size)
}

/** [this] with the suggestion's line naming [coAuthor], and the caret at its end. */
internal fun TextFieldValue.withCoAuthor(suggestion: CoAuthorSuggestion, coAuthor: CoAuthor): TextFieldValue {
    val (text, caret) = withCoAuthor(text, suggestion.line, coAuthor)

    return TextFieldValue(text, TextRange(caret))
}

/** What a key does while the suggestions show. */
internal sealed interface CoAuthorKey {
    data class Highlight(val index: Int) : CoAuthorKey
    data class Pick(val index: Int) : CoAuthorKey
}

/**
 * The row highlighted when the suggestions open: the first once something is typed, so that Enter picks it, and none on
 * a line that names no one yet, where Enter ends the list ([coAuthorEnter]).
 */
internal fun firstHighlighted(suggestion: CoAuthorSuggestion?): Int =
    if (suggestion == null || suggestion.line.value.isEmpty()) -1 else 0

/**
 * The keys that the suggestions take from the field, which keeps the focus: ↓ and ↑ move the highlight within [count]
 * rows ([highlighted] is -1 for none), Enter picks the highlighted one, and Tab the highlighted or the first. With a
 * modifier, or any other key, or Enter with nothing highlighted, the field gets it: Cmd or Ctrl and Enter still commits.
 * Esc goes through the tab, which closes the suggestions as a closeable view.
 */
internal fun coAuthorKey(event: KeyEvent, highlighted: Int, count: Int): CoAuthorKey? {
    if (event.type != KeyEventType.KeyDown || event.hasModifier()) {
        return null
    }

    return when (event.key) {
        Key.DirectionDown -> CoAuthorKey.Highlight((highlighted + 1).coerceAtMost(count - 1))
        Key.DirectionUp -> CoAuthorKey.Highlight((highlighted - 1).coerceAtLeast(0))
        Key.Enter, Key.NumPadEnter -> if (highlighted >= 0) CoAuthorKey.Pick(highlighted) else null
        Key.Tab -> CoAuthorKey.Pick(highlighted.coerceAtLeast(0))
        else -> null
    }
}

/** Enter pressed alone, which continues or ends a list of co-authors ([coAuthorEnter]). */
internal fun isPlainEnter(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && !event.hasModifier() && (event.key == Key.Enter || event.key == Key.NumPadEnter)

/** [value] after Enter on a `Co-authored-by` line ([coAuthorEnter]), or null where Enter is a plain line feed. */
internal fun TextFieldValue.afterCoAuthorEnter(): TextFieldValue? {
    if (!selection.collapsed) {
        return null
    }

    val (text, caret) = coAuthorEnter(text, selection.end) ?: return null

    return TextFieldValue(text, TextRange(caret))
}

private fun KeyEvent.hasModifier() = isMetaPressed || isCtrlPressed || isAltPressed || isShiftPressed

/**
 * The suggestions, above the field they belong to, as the commit field sits at the bottom of the window; below it when
 * there's no room above. A popup that takes no focus, so the field keeps it and its keys ([coAuthorKey]).
 */
@Composable
internal fun CoAuthorSuggestionsPopup(
    suggestion: CoAuthorSuggestion,
    highlighted: Int,
    width: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (CoAuthor) -> Unit,
) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gap) { AboveLeftAlignedPositionProvider(gap) }

    Popup(
        properties = PopupProperties(focusable = false),
        popupPositionProvider = positionProvider,
    ) {
        CoAuthorSuggestionsList(suggestion, highlighted, width, onHighlight, onPick)
    }
}

/** The popup's content: a row per person, then a note. Apart from the popup, which an offscreen scene doesn't draw. */
@Composable
internal fun CoAuthorSuggestionsList(
    suggestion: CoAuthorSuggestion,
    highlighted: Int,
    width: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (CoAuthor) -> Unit,
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
            .padding(vertical = 4.dp),
    ) {
        suggestion.choices.forEachIndexed { index, coAuthor ->
            CoAuthorRow(
                coAuthor = coAuthor,
                query = suggestion.line.value,
                isHighlighted = index == highlighted,
                onHover = { onHighlight(index) },
                onPick = { onPick(coAuthor) },
            )
        }

        val more = suggestion.total - suggestion.choices.size
        val note = if (more > 0) {
            pluralStringResource(Res.plurals.co_author_suggestions_more, more, more)
        } else {
            stringResource(Res.string.co_author_suggestions_type_someone)
        }

        Text(
            text = note,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * One person: avatar, name and email, with what was typed in bold. Picked by a tap rather than a `clickable`, which
 * would take the focus from the field, as Clone's branch list does.
 */
@Composable
private fun CoAuthorRow(
    coAuthor: CoAuthor,
    query: String,
    isHighlighted: Boolean,
    onHover: () -> Unit,
    onPick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnHover by rememberUpdatedState(onHover)
    val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primaryVariant)

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
            .pointerInput(coAuthor) { detectTapGestures { currentOnPick() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                selected = isHighlighted
                onClick { currentOnPick(); true }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            modifier = Modifier.size(18.dp),
            personIdent = Identity(coAuthor.name, coAuthor.email),
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = withMatch(coAuthor.name, query, matchStyle),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        Spacer(Modifier.width(8.dp))

        // The email gives way first in a narrow pane
        Text(
            text = withMatch(coAuthor.email, query, matchStyle),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** [text] with the first place that [query] is in it, ignoring case, in [style]. */
private fun withMatch(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
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
