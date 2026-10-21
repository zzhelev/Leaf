// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
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
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.ConventionalChoice
import dev.app.leaf.domain.commitmessage.ConventionalCommitsMode
import dev.app.leaf.domain.commitmessage.ConventionalHistory
import dev.app.leaf.domain.commitmessage.ConventionalQuery
import dev.app.leaf.domain.commitmessage.MAX_COMMIT_TYPE_CHOICES
import dev.app.leaf.domain.commitmessage.commitScopeChoices
import dev.app.leaf.domain.commitmessage.commitTypeChoices
import dev.app.leaf.domain.commitmessage.conventionalQueryAt
import dev.app.leaf.domain.commitmessage.withConventionalChoice
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Conventional Commits suggestions for the commit field (fork-only): what the caret is typing at the subject's start
 * ([ConventionalQuery]), and the first [MAX_COMMIT_TYPE_CHOICES] of the [total] types or scopes that match it.
 */
internal data class CommitTypeSuggestion(
    val query: ConventionalQuery,
    val choices: List<ConventionalChoice>,
    val total: Int,
    val history: ConventionalHistory,
    val mode: ConventionalCommitsMode,
    /** The worktree's commitlint config, which narrowed the choices, or null. */
    val rules: CommitlintRules? = null,
) {
    /** What was typed of the type or the scope, which decides whether a row is highlighted at first. */
    val typed: String
        get() = when (query) {
            is ConventionalQuery.Type -> query.typed
            is ConventionalQuery.Scope -> query.typed
        }
}

/**
 * What the field suggests for [value], or null: not while [mode] is off, nor with [ConventionalCommitsMode.AUTOMATIC] in
 * a repository whose recent subjects don't use the format ([ConventionalHistory.usesConventionalCommits]) and that has
 * no commitlint config ([rules]); not where the caret isn't typing a type or a scope; not when nothing matches. [rules]
 * leave out what the config doesn't allow. The ticket key of the branch's name ([branchKey]) is the first scope.
 */
internal fun commitTypeSuggestion(
    value: TextFieldValue,
    history: ConventionalHistory,
    mode: ConventionalCommitsMode,
    rules: CommitlintRules? = null,
    branchKey: String? = null,
): CommitTypeSuggestion? {
    if (!mode.suggestsIn(history, rules) || !value.selection.collapsed) {
        return null
    }

    val query = conventionalQueryAt(value.text, value.selection.end) ?: return null
    val choices = when (query) {
        is ConventionalQuery.Type -> commitTypeChoices(query.typed, history, rules)
        is ConventionalQuery.Scope -> commitScopeChoices(query.type, query.typed, history, rules, branchKey)
    }

    return if (choices.isEmpty()) {
        null
    } else {
        CommitTypeSuggestion(query, choices.take(MAX_COMMIT_TYPE_CHOICES), choices.size, history, mode, rules)
    }
}

/** [this] with the type or the scope that the suggestion completes set to [choice], and the caret after its `: `. */
internal fun TextFieldValue.withConventionalChoice(
    suggestion: CommitTypeSuggestion,
    choice: ConventionalChoice,
): TextFieldValue {
    val (text, caret) = withConventionalChoice(text, suggestion.query, choice)

    return TextFieldValue(text, TextRange(caret))
}

/** The type and scope suggestions above the field ([SuggestionsPopup]). */
@Composable
internal fun CommitTypeSuggestionsPopup(
    suggestion: CommitTypeSuggestion,
    highlighted: Int,
    width: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (ConventionalChoice) -> Unit,
) {
    SuggestionsPopup {
        CommitTypeSuggestionsList(suggestion, highlighted, width, onHighlight, onPick)
    }
}

/**
 * The popup's content: a row per type or scope, with how often the history used it, then a note. The note says where
 * the list comes from: the commitlint config that lists the types or the scopes, or, for types, how many of the newest
 * subjects use one (not when the setting asks for the list always). For scopes from the history, it says how to mark a
 * breaking change.
 */
@Composable
internal fun CommitTypeSuggestionsList(
    suggestion: CommitTypeSuggestion,
    highlighted: Int,
    width: Dp,
    onHighlight: (Int) -> Unit,
    onPick: (ConventionalChoice) -> Unit,
) {
    SuggestionsBox(width) {
        suggestion.choices.forEachIndexed { index, choice ->
            SuggestionRow(
                key = choice.name,
                isHighlighted = index == highlighted,
                onHover = { onHighlight(index) },
                onPick = { onPick(choice) },
            ) {
                CommitTypeRowContent(choice, suggestion.typed, isType = suggestion.query is ConventionalQuery.Type)
            }
        }

        val more = suggestion.total - suggestion.choices.size
        val history = suggestion.history
        val rules = suggestion.rules
        val isScope = suggestion.query is ConventionalQuery.Scope
        val note = when {
            more > 0 -> pluralStringResource(Res.plurals.commit_type_suggestions_more, more, more)
            isScope && rules?.scopes != null -> stringResource(Res.string.commit_type_suggestions_scopes_from, rules.file)
            isScope -> stringResource(Res.string.commit_type_suggestions_breaking)
            rules?.types != null -> stringResource(Res.string.commit_type_suggestions_types_from, rules.file)
            suggestion.mode != ConventionalCommitsMode.AUTOMATIC -> null
            history.usesConventionalCommits -> stringResource(
                Res.string.commit_type_suggestions_history,
                history.conventionalSubjects,
                history.sampled,
            )

            rules != null -> stringResource(Res.string.commit_type_suggestions_config, rules.file)
            else -> null
        }

        if (note != null) {
            SuggestionNote(note)
        }
    }
}

/**
 * A type or a scope in the monospace font, in a column of its own, a type's description when it's a usual one, and its
 * uses at the row's end, where the description gives way first.
 */
@Composable
private fun RowScope.CommitTypeRowContent(choice: ConventionalChoice, typed: String, isType: Boolean) {
    val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primaryVariant)
    val description = when {
        isType -> commitTypeDescription(choice.name)
        choice.isFromBranch -> stringResource(Res.string.commit_type_suggestions_from_branch)
        else -> null
    }

    Text(
        text = withMatch(choice.name, typed, matchStyle),
        fontFamily = monoTypography(),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = 72.dp, max = 160.dp),
    )

    Spacer(Modifier.width(8.dp))

    Text(
        text = description.orEmpty(),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )

    if (choice.uses > 0) {
        Spacer(Modifier.width(8.dp))

        Text(
            text = choice.uses.toString(),
            style = MaterialTheme.typography.caption.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
        )
    }
}

/** What a usual type is for, or null for a type that only the repository's history has. */
@Composable
private fun commitTypeDescription(type: String): String? = when (type) {
    "feat" -> stringResource(Res.string.commit_type_feat)
    "fix" -> stringResource(Res.string.commit_type_fix)
    "docs" -> stringResource(Res.string.commit_type_docs)
    "style" -> stringResource(Res.string.commit_type_style)
    "refactor" -> stringResource(Res.string.commit_type_refactor)
    "perf" -> stringResource(Res.string.commit_type_perf)
    "test" -> stringResource(Res.string.commit_type_test)
    "build" -> stringResource(Res.string.commit_type_build)
    "ci" -> stringResource(Res.string.commit_type_ci)
    "chore" -> stringResource(Res.string.commit_type_chore)
    "revert" -> stringResource(Res.string.commit_type_revert)
    else -> null
}
