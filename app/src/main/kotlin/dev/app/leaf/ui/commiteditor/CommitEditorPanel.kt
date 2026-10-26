// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.commiteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.SquashMessageStart
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.repositoryopen.CommitEditorAction
import dev.app.leaf.repositoryopen.CommitEditorState
import dev.app.leaf.repositoryopen.PlanFile
import dev.app.leaf.theme.addFile
import dev.app.leaf.theme.modifyFile
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.amberText
import dev.app.leaf.ui.dialogs.MessageField
import dev.app.leaf.ui.dialogs.StartSwitch
import dev.app.leaf.ui.dialogs.textWithCaretAfterSubject
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.compose.resources.stringResource

/**
 * The commit editor's panel (fork-only, B5 of `docs/fork/commit-editor.md`), in the right pane: for the selected row,
 * the commit it becomes after the rebase ("Commit 3 of 6"), the commits joined into it, Start from for a group with
 * squashes, its message, and the files of the selected commit, with the stop's files marked. "Dropped" for a dropped
 * row. The message takes the focus, with the caret after the subject, when a row turns Reword or joins another.
 */
@Composable
fun CommitEditorPanel(
    state: CommitEditorState,
    onAction: (CommitEditorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val row = state.selectedRow
    val group = state.selectedGroup
    val stop = state.check?.stop ?: state.lastCheck?.stop
    val conflictFiles = (stop as? PlanStop.Conflict)?.takeIf { it.commit == row.commit.hash }?.files.orEmpty().toSet()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        if (group == null) {
            SectionTitle(stringResource(Res.string.commit_editor_panel_dropped))
        } else {
            val index = state.plan.groups.indexOf(group)

            SectionTitle(stringResource(Res.string.commit_editor_panel_commit, index + 1, state.plan.groups.size))

            if (group.members.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (commit in group.commits) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = commit.shortHash,
                                fontFamily = monoTypography(),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colors.onBackgroundSecondary,
                            )

                            Text(
                                text = commit.shortMessage,
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            val target = group.target.commit.hash

            if (state.hasMessageStart(group)) {
                // The switch's three choices take the pane's width, so its label goes above them
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.rewrite_dialog_start_from),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackgroundSecondary,
                    )

                    StartSwitch(
                        start = state.starts[target] ?: SquashMessageStart.SUBJECTS,
                        onPick = { onAction(CommitEditorAction.SetMessageStart(it)) },
                    )
                }
            }

            GroupMessage(
                groupKey = target,
                version = state.messageVersion,
                message = state.messageOf(group),
                focusRequests = state.focusRequests,
                onMessageChange = { onAction(CommitEditorAction.SetMessage(it)) },
                onStart = { onAction(CommitEditorAction.Start) },
            )

            if (state.isMessageChanged(group)) {
                Text(
                    text = stringResource(Res.string.commit_editor_panel_put_back),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primaryVariant,
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                        .handMouseClickable { onAction(CommitEditorAction.ResetMessage) },
                )
            }
        }

        SectionTitle(stringResource(Res.string.commit_editor_panel_files, row.commit.shortHash))

        when (val files = state.files[row.commit.hash]) {
            null -> Unit
            emptyList<PlanFile>() -> Text(
                text = stringResource(Res.string.commit_editor_panel_no_files),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(horizontal = 14.dp),
            )

            else -> for (file in files) {
                FileRow(file, isConflict = file.path in conflictFiles)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
    )
}

/**
 * The message of the selected row's group. It keeps its own text and caret while it's typed in, and takes the plan's
 * message when another group is shown, or anything but typing changed the plan ([version]: Start from, Put the message
 * back, a row that joins). The plan's state can come a frame after a key, so the field doesn't compare with it.
 */
@Composable
private fun GroupMessage(
    groupKey: String,
    version: Int,
    message: String,
    focusRequests: Int,
    onMessageChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember(groupKey, version) { mutableStateOf(textWithCaretAfterSubject(message.trimEnd())) }

    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            value = textWithCaretAfterSubject(value.text)
            runCatching { focusRequester.requestFocus() }
        }
    }

    MessageField(
        value = value,
        onValueChange = { changed: TextFieldValue ->
            val textChanged = changed.text != value.text

            value = changed

            if (textChanged) onMessageChange(changed.text)
        },
        modifier = Modifier
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val command = if (currentOs.isMac()) event.isMetaPressed else event.isCtrlPressed

                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && command) {
                    onStart()
                    true
                } else {
                    false
                }
            },
    )
}

@Composable
private fun FileRow(file: PlanFile, isConflict: Boolean) {
    val name = file.path.substringAfterLast('/')
    val folder = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = file.change.icon(),
            contentDescription = null,
            tint = file.change.color(),
            modifier = Modifier.size(14.dp),
        )

        Text(
            text = name,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
        )

        Text(
            text = folder,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (isConflict) {
            Text(
                text = stringResource(Res.string.commit_editor_panel_conflicts),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.amberText,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colors.amberText.copy(alpha = 0.15f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

private fun DiffEntry.ChangeType.icon(): ImageVector = when (this) {
    DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> Icons.Default.Add
    DiffEntry.ChangeType.DELETE -> Icons.Default.Delete
    DiffEntry.ChangeType.RENAME -> Icons.Default.ArrowForward
    DiffEntry.ChangeType.MODIFY -> Icons.Default.Edit
}

@Composable
private fun DiffEntry.ChangeType.color(): Color = when (this) {
    DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> MaterialTheme.colors.addFile
    DiffEntry.ChangeType.DELETE -> MaterialTheme.colors.error
    DiffEntry.ChangeType.RENAME, DiffEntry.ChangeType.MODIFY -> MaterialTheme.colors.modifyFile
}
