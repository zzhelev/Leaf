// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Checkbox
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.cleanCommitMessage
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.RewriteDialogKind
import dev.app.leaf.domain.rewrite.RewriteRequest
import dev.app.leaf.domain.rewrite.SquashMessageStart
import dev.app.leaf.domain.rewrite.shortName
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.outlinedTextFieldColors
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 520.dp

/** The commits scroll past this, so that the message has room in a laptop's window. */
private val MAX_COMMITS_HEIGHT = 168.dp

private val COMMIT_ROW_HEIGHT = 24.dp

/** The files that conflict or are in the way are named up to this many. */
private const val MAX_FILES_NAMED = 3

private val MESSAGE_MIN_HEIGHT = 120.dp

/** The message scrolls past this. */
private val MESSAGE_MAX_HEIGHT = 260.dp

/**
 * The message of a squash or a reword of a branch's commits (fork-only, step A3 of `docs/fork/squash-and-rebase.md`),
 * edited before anything is applied: the commits, newest first, then the message, focused with the caret after its
 * subject, "Start from" for a squash, and the pushed commits that a force push has to replace. ⌘⏎ confirms, as in the
 * commit field. A message that git's clean-up ([cleanup]) leaves empty can't be confirmed, nor a reword's message that
 * it leaves as the commit's own.
 *
 * Squashing the whole branch also offers "On top of the latest main" (step A4), which [checkOnto] checks as the dialog
 * opens: checked when the merge is clean, off and disabled, with the files, when it conflicts or uncommitted changes
 * are in the way, and not there when the branch has main's tip. Squash waits for the check.
 */
@Composable
fun RewriteCommitsDialog(
    request: RewriteRequest,
    cleanup: MessageCleanup,
    checkOnto: (suspend () -> OntoBaseCheck?)? = null,
    onConfirm: (message: String, onto: OntoBase?) -> Unit,
    onDismiss: () -> Unit,
) {
    val range = request.range
    val isSquash = request.kind != RewriteDialogKind.REWORD
    val count = range.commits.size
    val branch = shortName(range.line.branch)

    var start by remember { mutableStateOf(SquashMessageStart.SUBJECTS) }
    var message by remember { mutableStateOf(textWithCaretAfterSubject(request.messageStart(start))) }
    val cleaned = cleanCommitMessage(message.text, cleanup)
    // A reword that keeps the message would only make new commits, which a pushed branch then has to replace
    val isUnchanged = !isSquash && cleaned == cleanCommitMessage(range.commits.single().message, cleanup)
    var ontoCheck by remember { mutableStateOf<OntoCheckState>(if (checkOnto == null) OntoCheckState.None else OntoCheckState.Checking) }
    var isOnto by remember { mutableStateOf(false) }
    val canConfirm = cleaned.isNotBlank() && !isUnchanged && ontoCheck != OntoCheckState.Checking
    val confirm = {
        if (canConfirm) {
            val onto = (ontoCheck as? OntoCheckState.Checked)?.check?.takeIf { isOnto && it is OntoBaseCheck.Clean }?.onto
            onConfirm(message.text, onto)
            onDismiss()
        }
    }

    if (checkOnto != null) {
        LaunchedEffect(Unit) {
            val check = checkOnto()
            isOnto = check is OntoBaseCheck.Clean
            ontoCheck = if (check == null) OntoCheckState.None else OntoCheckState.Checked(check)
        }
    }

    val messageFocus = remember { FocusRequester() }

    IconBasedDialog(
        icon = painterResource(if (isSquash) Res.drawable.align_top else Res.drawable.message),
        title = when (request.kind) {
            RewriteDialogKind.SQUASH -> pluralStringResource(Res.plurals.rewrite_dialog_squash_title, count, count)
            RewriteDialogKind.SQUASH_BRANCH -> stringResource(Res.string.rewrite_dialog_squash_branch_title, branch)
            RewriteDialogKind.REWORD -> stringResource(Res.string.rewrite_dialog_reword_title, range.commits.single().shortHash)
        },
        subtitle = subtitle(request, branch),
        primaryActionText = if (isSquash) {
            pluralStringResource(Res.plurals.rewrite_dialog_squash_button, count, count)
        } else {
            stringResource(Res.string.rewrite_dialog_save_message)
        },
        isPrimaryActionEnabled = canConfirm,
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
        onDismiss = onDismiss,
        onPrimaryActionClicked = confirm,
    ) {
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isSquash) {
                CommitList(request)
            }

            Label(stringResource(Res.string.rewrite_dialog_message))

            MessageField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier
                    .focusRequester(messageFocus)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.matchesBinding(KeybindingOption.TEXT_ACCEPT)) {
                            confirm()
                            true
                        } else {
                            false
                        }
                    },
            )

            // After the field, so that it comes after the dialog's own request for the focus
            LaunchedEffect(Unit) {
                messageFocus.requestFocus()
            }

            if (isSquash) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(Res.string.rewrite_dialog_start_from), Modifier.padding(end = 10.dp))
                    StartSwitch(start) { picked ->
                        start = picked
                        message = textWithCaretAfterSubject(request.messageStart(picked))
                    }
                }
            }

            (ontoCheck as? OntoCheckState.Checked)?.let { checked ->
                OntoRow(checked.check, isChecked = isOnto, onCheckedChange = { isOnto = it })
            }

            val pushed = range.rewrittenPushed
            val upstream = range.line.upstream

            if (pushed > 0 && upstream != null) {
                DialogNotice(pluralStringResource(Res.plurals.rewrite_dialog_pushed, pushed, pushed, shortName(upstream)))
            }
        }
    }
}

@Composable
private fun subtitle(request: RewriteRequest, branch: String): String {
    val range = request.range
    val base = range.line.base
    val forkPoint = range.commits.first().parentsHashes.firstOrNull()

    return if (request.kind == RewriteDialogKind.SQUASH_BRANCH && base != null && forkPoint != null) {
        val count = range.commits.size

        pluralStringResource(Res.plurals.rewrite_dialog_since_base, count, count, shortName(base), forkPoint.take(7))
    } else {
        stringResource(Res.string.rewrite_dialog_on_branch, branch)
    }
}

/** The commits that the squash joins, newest first, as the log shows them. */
@Composable
private fun CommitList(request: RewriteRequest) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(4.dp)),
    ) {
        ScrollingBox(maxHeight = MAX_COMMITS_HEIGHT, modifier = Modifier.padding(vertical = 4.dp)) {
            for (commit in request.range.commits.asReversed()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(COMMIT_ROW_HEIGHT)
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = commit.shortHash,
                        fontFamily = monoTypography(),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                        maxLines = 1,
                    )

                    Text(
                        text = commit.shortMessage,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The message, as the dialogs' outlined fields look, but as tall as a few lines and its text at the top: the shared field
 * centres one line in its border.
 */
@Composable
internal fun MessageField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = outlinedTextFieldColors()
    val borderColor by colors.indicatorColor(enabled = true, isError = false, interactionSource = interactionSource)
    val shape = RoundedCornerShape(4.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
        cursorBrush = SolidColor(colors.cursorColor(isError = false).value),
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MESSAGE_MIN_HEIGHT, max = MESSAGE_MAX_HEIGHT),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colors.background, shape)
                    .border(2.dp, borderColor, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                innerTextField()
            }
        },
    )
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackgroundSecondary,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Where the dialog's check of "On top of the latest main" is. */
private sealed interface OntoCheckState {
    /** Not offered: a squash of part of the branch, a reword, or nothing to put it on. */
    data object None : OntoCheckState

    data object Checking : OntoCheckState

    data class Checked(val check: OntoBaseCheck) : OntoCheckState
}

/**
 * "On top of the latest main · 55991ae, 2 new commits", and under it, when it can't be checked, the files that
 * conflict with main or that uncommitted changes have.
 */
@Composable
private fun OntoRow(check: OntoBaseCheck, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val base = shortName(check.onto.base)
    val isPossible = check is OntoBaseCheck.Clean

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .toggleable(value = isChecked, enabled = isPossible, role = Role.Checkbox, onValueChange = onCheckedChange)
                .then(if (isPossible) Modifier.handOnHover() else Modifier),
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = null,
                enabled = isPossible,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(12.dp),
            )

            Text(
                text = stringResource(Res.string.rewrite_dialog_onto, base),
                style = MaterialTheme.typography.body2,
                color = if (isPossible) MaterialTheme.colors.onBackground else MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
            )

            Text(
                text = " · " + pluralStringResource(
                    Res.plurals.rewrite_dialog_onto_new_commits,
                    check.newCommits,
                    check.onto.commit.take(7),
                    check.newCommits,
                ),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val why = when (check) {
            is OntoBaseCheck.Clean -> null
            is OntoBaseCheck.Conflicts -> stringResource(Res.string.rewrite_dialog_onto_conflicts, base, fileNames(check.files))
            is OntoBaseCheck.ChangesInTheWay -> stringResource(Res.string.rewrite_dialog_onto_in_the_way, fileNames(check.files))
        }

        if (why != null) {
            Text(
                text = why,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}

/** The files' names, the first [MAX_FILES_NAMED], then how many more. */
@Composable
private fun fileNames(files: List<String>): String {
    val names = files.take(MAX_FILES_NAMED).joinToString(", ") { it.substringAfterLast('/') }
    val more = files.size - MAX_FILES_NAMED

    return if (more > 0) pluralStringResource(Res.plurals.rewrite_dialog_and_more_files, more, names, more) else names
}

/** "Start from": the subjects as a list, all the messages, or the oldest one's. */
@Composable
internal fun StartSwitch(start: SquashMessageStart, onPick: (SquashMessageStart) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colors.onBackgroundSecondary.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
    ) {
        for (option in SquashMessageStart.entries) {
            val isSelected = option == start

            Text(
                text = when (option) {
                    SquashMessageStart.SUBJECTS -> stringResource(Res.string.rewrite_dialog_start_subjects)
                    SquashMessageStart.ALL -> stringResource(Res.string.rewrite_dialog_start_all)
                    SquashMessageStart.OLDEST -> stringResource(Res.string.rewrite_dialog_start_oldest)
                },
                style = MaterialTheme.typography.caption,
                color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                modifier = Modifier
                    .semantics {
                        role = Role.Tab
                        selected = isSelected
                    }
                    .background(if (isSelected) MaterialTheme.colors.primary else Color.Transparent)
                    .handMouseClickable { if (!isSelected) onPick(option) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** [text] with the caret at the end of its first line, ready to type after the subject. */
internal fun textWithCaretAfterSubject(text: String): TextFieldValue {
    val subjectEnd = text.indexOf('\n').takeIf { it >= 0 } ?: text.length

    return TextFieldValue(text, selection = TextRange(subjectEnd))
}
