// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** [value] after Enter on a `Co-authored-by` line ([coAuthorEnter]), or null where Enter is a plain line feed. */
internal fun TextFieldValue.afterCoAuthorEnter(): TextFieldValue? {
    if (!selection.collapsed) {
        return null
    }

    val (text, caret) = coAuthorEnter(text, selection.end) ?: return null

    return TextFieldValue(text, TextRange(caret))
}

/** The co-author suggestions above the field ([SuggestionsPopup]). */
@Composable
internal fun CoAuthorSuggestionsPopup(
    suggestion: CoAuthorSuggestion,
    highlighted: Int,
    width: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (CoAuthor) -> Unit,
) {
    SuggestionsPopup {
        CoAuthorSuggestionsList(suggestion, highlighted, width, onHighlight, onPick)
    }
}

/** The popup's content: a row per person, then a note. */
@Composable
internal fun CoAuthorSuggestionsList(
    suggestion: CoAuthorSuggestion,
    highlighted: Int,
    width: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (CoAuthor) -> Unit,
) {
    SuggestionsBox(width) {
        suggestion.choices.forEachIndexed { index, coAuthor ->
            SuggestionRow(
                key = coAuthor,
                isHighlighted = index == highlighted,
                onHover = { onHighlight(index) },
                onPick = { onPick(coAuthor) },
            ) {
                CoAuthorRowContent(coAuthor, query = suggestion.line.value)
            }
        }

        val more = suggestion.total - suggestion.choices.size

        SuggestionNote(
            if (more > 0) {
                pluralStringResource(Res.plurals.co_author_suggestions_more, more, more)
            } else {
                stringResource(Res.string.co_author_suggestions_type_someone)
            }
        )
    }
}

/** One person: avatar, name and email, with what was typed in bold. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.CoAuthorRowContent(coAuthor: CoAuthor, query: String) {
    val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primaryVariant)

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
