// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldColors
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.commit_message_insert_blank_line
import dev.app.leaf.app.generated.resources.commit_message_runs_on_hint
import dev.app.leaf.app.generated.resources.commit_message_runs_on_tooltip
import dev.app.leaf.app.generated.resources.commit_message_subject_count
import dev.app.leaf.app.generated.resources.commit_message_subject_count_over
import dev.app.leaf.app.generated.resources.commit_message_subject_count_over_tooltip
import dev.app.leaf.app.generated.resources.commit_message_subject_count_tooltip
import dev.app.leaf.app.generated.resources.info
import dev.app.leaf.app.generated.resources.status_header_more_actions
import dev.app.leaf.app.generated.resources.uncommited_changes_amend_menu_item
import dev.app.leaf.app.generated.resources.uncommited_changes_message_amending
import dev.app.leaf.app.generated.resources.uncommited_changes_message_read_only
import dev.app.leaf.app.generated.resources.uncommited_changes_message_title
import dev.app.leaf.app.generated.resources.uncommited_changes_message_title_merge
import dev.app.leaf.app.generated.resources.uncommited_changes_text_input_label_message
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.commitmessage.CommitMessageLayout
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.SUBJECT_LIMIT
import dev.app.leaf.domain.commitmessage.SUBJECT_SOFT_LIMIT
import dev.app.leaf.domain.commitmessage.commitMessageLayout
import dev.app.leaf.domain.commitmessage.withBlankLineAfterSubject
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.STATUS_COMMIT_TITLE_HEIGHT
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.amberText
import androidx.compose.material.Colors
import dev.app.leaf.ui.components.HeaderMenuButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.sort.SortMenuItem
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** How opaque the placeholder is: the reporter of upstream issue 344 asked for 30 to 50% transparency. */
internal const val COMMIT_MESSAGE_PLACEHOLDER_ALPHA = 0.6f

/**
 * What the commit message's title row says and offers (fork-only, upstream issue 344), by the repository's state.
 *
 * @property isMergeMessage the field shows git's message for a merge in progress
 * @property isReadOnly a rebase stopped at a commit that can't be amended: the field shows the last message and can't
 * be edited
 * @property amend the "Amend last commit" item of the row's menu, or null where there is nothing to amend: during a
 * merge, a cherry-pick, a revert, and at the rebase's other stops
 */
internal data class CommitMessageHeader(
    val isMergeMessage: Boolean,
    val isReadOnly: Boolean,
    val amend: AmendItem?,
)

/** The row's "Amend last commit" item, [isOn] when the next commit amends the last one. */
internal data class AmendItem(val isOn: Boolean)

internal fun commitMessageHeader(
    repositoryState: RepositoryState,
    isAmenableRebaseInteractive: Boolean,
    isAmend: Boolean,
    isAmendRebaseInteractive: Boolean,
) = CommitMessageHeader(
    isMergeMessage = repositoryState.isMerging,
    isReadOnly = repositoryState.isRebasing && !isAmenableRebaseInteractive,
    amend = when {
        // A rebase has its own switch, which only an interactive rebase's amendable stops use
        repositoryState.isRebasing -> AmendItem(isAmendRebaseInteractive).takeIf { isAmenableRebaseInteractive }
        repositoryState.isMerging || repositoryState.isCherryPicking || repositoryState.isReverting -> null
        else -> AmendItem(isAmend)
    },
)

/**
 * The title row above the commit message, laid out as the Staged and Unstaged headers are ([FilesChangedHeader]):
 * [STATUS_COMMIT_TITLE_HEIGHT] tall, on the same surface, with the same text. A "⋯" menu at its end has "Amend last
 * commit", and while that is on the row says so, as the button below reads Amend.
 */
@Composable
internal fun CommitMessageTitle(
    header: CommitMessageHeader,
    onToggleAmend: () -> Unit,
    modifier: Modifier = Modifier,
    /** The subject's length, shown at the row's end when it is long (fork-only, [subjectCount]). */
    subjectCount: SubjectCount? = null,
) {
    val title = if (header.isMergeMessage) {
        stringResource(Res.string.uncommited_changes_message_title_merge)
    } else {
        stringResource(Res.string.uncommited_changes_message_title)
    }
    val tag = when {
        header.isReadOnly -> stringResource(Res.string.uncommited_changes_message_read_only)
        header.amend?.isOn == true -> stringResource(Res.string.uncommited_changes_message_amending)
        else -> null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_COMMIT_TITLE_HEIGHT.dp)
            .background(MaterialTheme.colors.tertiarySurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                text = title,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Left,
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (tag != null) {
                Text(
                    // The title keeps its room, and the tag gets what is left
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    text = "· $tag",
                    // The accent that the Amend checkbox had when it was ticked
                    color = if (header.isReadOnly) MaterialTheme.colors.onBackgroundSecondary else MaterialTheme.colors.secondary,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (subjectCount != null) {
            SubjectCountText(
                subjectCount,
                // The menu button has room of its own around its icon
                modifier = Modifier.padding(start = 4.dp, end = if (header.amend == null) 16.dp else 0.dp),
            )
        }

        header.amend?.let { amend ->
            HeaderMenuButton(
                items = amendMenuItems(amend, stringResource(Res.string.uncommited_changes_amend_menu_item), onToggleAmend),
                tooltip = stringResource(Res.string.status_header_more_actions),
            )
        }
    }
}

/** The row's menu: "Amend last commit", checked while it is on. It closes the menu, as there is nothing else to pick. */
internal fun amendMenuItems(amend: AmendItem, label: String, onToggleAmend: () -> Unit): List<SortMenuItem> = listOf(
    SortMenuItem.Option(
        label = label,
        isChecked = amend.isOn,
        closesMenu = true,
        onClick = onToggleAmend,
    ),
)

/**
 * The commit message's field: no label, but a hint while it is empty, as dim as the reporter of upstream issue 344 asked.
 * It has the lists' background and no underline until it has the focus. A field that can't be edited has no hint.
 */
@Composable
internal fun CommitMessageTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** How git cleans the message up, which the field shows: comment lines dimmed (fork-only). */
    cleanup: MessageCleanup = MessageCleanup.DEFAULT,
    /** The subject's tint past [SUBJECT_LIMIT] and the ruler, as the settings say (fork-only). */
    hints: CommitMessageHints = CommitMessageHints(),
) {
    val ruler = hints.ruler
    // Fork-only: a ruler counts columns, which only a monospace font lines up with
    val textStyle = if (ruler != null) {
        MaterialTheme.typography.body2.copy(fontFamily = monoTypography())
    } else {
        MaterialTheme.typography.body1
    }
    val styling = CommitMessageStyling(
        cleanup = cleanup,
        tintsOverflow = hints.subjectLength,
        droppedColor = MaterialTheme.colors.onBackground.copy(alpha = COMMIT_MESSAGE_DROPPED_ALPHA),
        overflowBackground = MaterialTheme.colors.conflictFile.copy(alpha = COMMIT_MESSAGE_OVERFLOW_ALPHA),
    )

    TextField(
        modifier = modifier.then(
            if (ruler != null) Modifier.columnRuler(ruler, textStyle, MaterialTheme.colors.onBackground) else Modifier
        ),
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        placeholder = if (enabled) {
            {
                Text(
                    text = stringResource(Res.string.uncommited_changes_text_input_label_message),
                    style = textStyle,
                    // The typography has a color of its own, which the field's placeholder color wouldn't override
                    color = MaterialTheme.colors.onBackground.copy(alpha = COMMIT_MESSAGE_PLACEHOLDER_ALPHA),
                )
            }
        } else {
            null
        },
        // A read-only field shows the last commit's message, which isn't committed again
        visualTransformation = if (enabled) styling else VisualTransformation.None,
        colors = commitMessageFieldColors(),
        textStyle = textStyle,
    )
}

/** How strong comment lines are, which git leaves out of the commit (fork-only). */
internal const val COMMIT_MESSAGE_DROPPED_ALPHA = 0.5f

/** How strong the tint of a subject's characters past [SUBJECT_LIMIT] is (fork-only). */
internal const val COMMIT_MESSAGE_OVERFLOW_ALPHA = 0.34f

/**
 * The field's text as git will commit it (fork-only): what git leaves out ([CommitMessageLayout.droppedSpans]) dimmed
 * and in italics, and with [tintsOverflow] the subject's characters past [SUBJECT_LIMIT] tinted. The layout comes from
 * the text that the field shows, so the styles always fit it. Equal by its values, so the field keeps its text layout
 * while they don't change.
 */
internal data class CommitMessageStyling(
    val cleanup: MessageCleanup,
    val tintsOverflow: Boolean,
    val droppedColor: Color,
    val overflowBackground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val layout = commitMessageLayout(text.text, cleanup)
        val overflow = layout.subjectOverflow.takeIf { tintsOverflow }

        if (layout.droppedSpans.isEmpty() && overflow == null) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val styled = AnnotatedString.Builder(text).apply {
            for (span in layout.droppedSpans) {
                addStyle(SpanStyle(color = droppedColor, fontStyle = FontStyle.Italic), span.start, span.end)
            }

            if (overflow != null) {
                addStyle(SpanStyle(background = overflowBackground), overflow.start, overflow.end)
            }
        }.toAnnotatedString()

        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * A vertical line at [column] of the field's text (fork-only), as JetBrains' and SourceTree's commit fields have. It
 * shows only while the column fits in the field: the text wraps at the field's width, so a narrower field has no column
 * there. The field's text starts [TEXT_FIELD_PADDING] in, as Material's field without a label has it.
 */
private fun Modifier.columnRuler(column: Int, textStyle: TextStyle, color: Color): Modifier = composed {
    val measurer = rememberTextMeasurer()
    val columnsWidth = remember(column, textStyle, measurer) {
        measurer.measure("0".repeat(column), textStyle, softWrap = false).size.width
    }

    drawWithContent {
        drawContent()

        val padding = TEXT_FIELD_PADDING.dp.toPx()
        val x = padding + columnsWidth

        if (x <= size.width - padding) {
            drawLine(
                color = color.copy(alpha = COMMIT_MESSAGE_RULER_ALPHA),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/** Where Material's text field without a label starts its text (`TextFieldPadding`). */
private const val TEXT_FIELD_PADDING = 16

private const val COMMIT_MESSAGE_RULER_ALPHA = 0.2f

/**
 * The amber of a count past [SUBJECT_LIMIT]: the conflict color, and on a light background a darker one than
 * [amberText], as the title row's background is darker than the lists' (4.9:1 there, where [amberText] holds 4.1:1).
 */
private val Colors.subjectOverColor: Color
    @Composable
    get() = if (isLight) SUBJECT_OVER_ON_LIGHT else conflictFile

private val SUBJECT_OVER_ON_LIGHT = Color(0xFF8A5C00)

/** The subject's length that the title row shows (fork-only). [isOver] past [SUBJECT_LIMIT]. */
internal data class SubjectCount(val length: Int) {
    val isOver: Boolean get() = length > SUBJECT_LIMIT
}

/** The count to show: a subject past [SUBJECT_SOFT_LIMIT] characters, while the setting is on. */
internal fun subjectCount(layout: CommitMessageLayout, hints: CommitMessageHints): SubjectCount? =
    SubjectCount(layout.subjectLength).takeIf { hints.subjectLength && layout.subjectLength > SUBJECT_SOFT_LIMIT }

/** The count, muted up to [SUBJECT_LIMIT] and amber past it, with what it means in its tooltip. */
@Composable
private fun SubjectCountText(count: SubjectCount, modifier: Modifier = Modifier) {
    val tooltip = if (count.isOver) {
        stringResource(Res.string.commit_message_subject_count_over_tooltip, SUBJECT_LIMIT)
    } else {
        stringResource(Res.string.commit_message_subject_count_tooltip, SUBJECT_SOFT_LIMIT, SUBJECT_LIMIT)
    }

    DelayedTooltip(tooltip, modifier = modifier) {
        Text(
            text = if (count.isOver) {
                stringResource(Res.string.commit_message_subject_count_over, count.length, SUBJECT_LIMIT)
            } else {
                stringResource(Res.string.commit_message_subject_count, count.length)
            },
            color = if (count.isOver) MaterialTheme.colors.subjectOverColor else MaterialTheme.colors.onBackgroundSecondary,
            // Tabular digits, so that the count doesn't shift as it grows
            style = MaterialTheme.typography.body2.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/**
 * Says that the subject runs on into the next line (fork-only), which git tools would read as one long
 * subject, with a link that adds the blank line after it. Short enough for one line in the pane's default width.
 */
@Composable
internal fun SubjectRunsOnHint(onInsertBlankLine: () -> Unit, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colors.primaryVariant
    val text = buildAnnotatedString {
        append(stringResource(Res.string.commit_message_runs_on_hint))
        append(' ')
        withLink(
            LinkAnnotation.Clickable(
                tag = INSERT_BLANK_LINE_TAG,
                styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
                linkInteractionListener = { onInsertBlankLine() },
            )
        ) {
            append(stringResource(Res.string.commit_message_insert_blank_line))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(Res.drawable.info),
            contentDescription = null,
            tint = MaterialTheme.colors.conflictFile,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )

        // One line at the pane's default width, so that the message keeps its room; the tooltip says why
        DelayedTooltip(stringResource(Res.string.commit_message_runs_on_tooltip), Modifier.padding(start = 6.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
            )
        }
    }
}

internal const val INSERT_BLANK_LINE_TAG = "insertBlankLine"

/**
 * [value] with a blank line after its subject ([withBlankLineAfterSubject]), the selection moved with the text after it
 * (fork-only). A caret at the subject's end stays there.
 */
internal fun TextFieldValue.withBlankLineAfterSubject(cleanup: MessageCleanup): TextFieldValue {
    val layout = commitMessageLayout(text, cleanup)
    val at = layout.blankLineAt ?: return this

    fun moved(offset: Int) = if (offset > at) offset + 1 else offset

    return TextFieldValue(
        text = withBlankLineAfterSubject(text, layout),
        selection = TextRange(moved(selection.start), moved(selection.end)),
    )
}

@Composable
private fun commitMessageFieldColors(): TextFieldColors = TextFieldDefaults.textFieldColors(
    textColor = MaterialTheme.colors.onBackground,
    disabledTextColor = MaterialTheme.colors.onBackgroundSecondary,
    backgroundColor = MaterialTheme.colors.background,
    cursorColor = MaterialTheme.colors.primaryVariant,
    focusedIndicatorColor = MaterialTheme.colors.primaryVariant,
    // Without them the field looks like text inside an input box, where the lists above are plain
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
