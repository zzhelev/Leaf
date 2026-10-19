// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 480.dp

/**
 * Creates a worktree (fork-only): its branch, its folder, the gitignored files to copy into it, and whether the tab
 * switches to it. It shows the commands that run.
 */
@Composable
fun NewWorktreeDialog(
    viewModel: NewWorktreeViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val startPoint = state.startPoint
    val branchFocusRequester = remember { FocusRequester() }
    val canCreate = state.request != null

    val subtitle = when {
        startPoint == null -> ""
        viewModel.start is NewWorktreeStart.Commit ->
            stringResource(Res.string.new_worktree_dialog_subtitle_commit, startPoint.label)

        else -> stringResource(Res.string.new_worktree_dialog_subtitle, startPoint.label)
    }

    // .worktreeinclude may have been edited in another app meanwhile
    val isWindowFocused = LocalWindowInfo.current.isWindowFocused
    var wasWindowFocused by remember { mutableStateOf(isWindowFocused) }

    LaunchedEffect(isWindowFocused) {
        if (isWindowFocused && !wasWindowFocused) {
            viewModel.reloadInclude()
        }

        wasWindowFocused = isWindowFocused
    }

    val create = {
        if (viewModel.create()) {
            onDismiss()
        }
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.folder),
        title = stringResource(Res.string.new_worktree_dialog_title),
        subtitle = subtitle,
        primaryActionText = stringResource(Res.string.new_worktree_dialog_create),
        isPrimaryActionEnabled = canCreate,
        onDismiss = onDismiss,
        onPrimaryActionClicked = { create() },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val error = state.error

            when {
                error != null -> DialogWarning(error.getStyledErrorText())
                startPoint == null -> DialogNote(stringResource(Res.string.worktree_dialog_checking))
                else -> {
                    val toCheckOut = startPoint.branchToCheckOut

                    if (toCheckOut != null) {
                        RadioRow(
                            isSelected = state.checkOut,
                            text = stringResource(Res.string.new_worktree_dialog_check_out, toCheckOut),
                            onClick = { viewModel.setCheckOut(true) },
                        )
                        RadioRow(
                            isSelected = !state.checkOut,
                            text = stringResource(Res.string.new_worktree_dialog_new_branch, startPoint.label),
                            onClick = { viewModel.setCheckOut(false) },
                        )
                    }

                    if (toCheckOut == null || !state.checkOut) {
                        FieldLabel(stringResource(Res.string.new_worktree_dialog_branch))

                        AdjustableOutlinedTextField(
                            value = state.branchName,
                            onValueChange = viewModel::setBranchName,
                            singleLine = true,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(branchFocusRequester)
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT) && canCreate) {
                                        create()
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )

                        LaunchedEffect(Unit) {
                            branchFocusRequester.requestFocus()
                        }

                        val name = state.branchName.trim()

                        when {
                            state.nameTaken -> FieldError(stringResource(Res.string.new_worktree_dialog_branch_taken, name))
                            state.nameRejected == true ->
                                FieldError(stringResource(Res.string.new_worktree_dialog_branch_rejected, name))
                        }
                    }

                    FolderField(
                        state = state,
                        onFolderChange = viewModel::setFolder,
                        onChooseLocation = viewModel::chooseLocation,
                        onLocationNextToRepository = viewModel::useLocationNextToRepository,
                    )

                    IncludeSection(
                        state = state,
                        onCopyFilesChange = viewModel::setCopyFiles,
                        onOpenInclude = viewModel::createInclude,
                    )

                    CheckboxText(
                        value = state.switchToNew,
                        onCheckedChange = { viewModel.setSwitchToNew(!state.switchToNew) },
                        text = stringResource(Res.string.new_worktree_dialog_switch),
                    )

                    if (canCreate) {
                        WorktreeCommands(state.steps)
                    }
                }
            }
        }
    }
}

/** The new worktree's folder, which follows the branch's name until typed, and where the repository's worktrees go. */
@Composable
private fun FolderField(
    state: NewWorktreeState,
    onFolderChange: (String) -> Unit,
    onChooseLocation: () -> Unit,
    onLocationNextToRepository: () -> Unit,
) {
    FieldLabel(stringResource(Res.string.new_worktree_dialog_folder))

    AdjustableOutlinedTextField(
        value = state.folder,
        onValueChange = onFolderChange,
        singleLine = true,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(
                onClick = onChooseLocation,
                modifier = Modifier
                    .handOnHover()
                    .size(32.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.folder_open),
                    contentDescription = stringResource(Res.string.new_worktree_dialog_location_choose),
                    tint = MaterialTheme.colors.onBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )

    when (state.folderProblem) {
        NewWorktreeFolderProblem.TAKEN -> FieldError(stringResource(Res.string.new_worktree_dialog_folder_taken))
        NewWorktreeFolderProblem.NOT_ABSOLUTE ->
            FieldError(stringResource(Res.string.new_worktree_dialog_folder_not_absolute))

        null -> {}
    }

    val location = state.location

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (location == null) {
                stringResource(Res.string.new_worktree_dialog_location_next_to)
            } else {
                stringResource(Res.string.new_worktree_dialog_location_in, abbreviateHome(location))
            },
            color = MaterialTheme.colors.onBackgroundSecondary,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.weight(1f, fill = false),
        )

        Link(stringResource(Res.string.new_worktree_dialog_location_choose), onChooseLocation)

        if (location != null) {
            Link(stringResource(Res.string.new_worktree_dialog_location_reset), onLocationNextToRepository)
        }
    }
}

/** The files that `.worktreeinclude` copies into the new worktree, or what it's for when the repository has none. */
@Composable
private fun IncludeSection(
    state: NewWorktreeState,
    onCopyFilesChange: (Boolean) -> Unit,
    onOpenInclude: () -> Unit,
) {
    when (val include = state.include) {
        WorktreeIncludeState.Loading -> DialogNote(stringResource(Res.string.new_worktree_dialog_include_loading))
        is WorktreeIncludeState.Failed -> DialogWarning(include.error.getStyledErrorText())
        WorktreeIncludeState.Missing -> {
            DialogNote(stringResource(Res.string.new_worktree_dialog_include_missing))
            Link(stringResource(Res.string.new_worktree_dialog_include_create), onOpenInclude)
        }

        is WorktreeIncludeState.Found -> {
            val files = include.include.files

            if (files.isEmpty()) {
                DialogNote(stringResource(Res.string.new_worktree_dialog_include_none))
            } else {
                CheckboxText(
                    value = state.copyFiles,
                    onCheckedChange = { onCopyFilesChange(!state.copyFiles) },
                    text = pluralStringResource(Res.plurals.new_worktree_dialog_copy, files.size, files.size, fileList(files)),
                )
            }

            Link(stringResource(Res.string.new_worktree_dialog_include_edit), onOpenInclude)
        }
    }
}

@Composable
internal fun RadioRow(isSelected: Boolean, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.handMouseClickable(onClick = onClick),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(16.dp),
        )

        Text(
            text = text,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
        )
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun FieldError(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.error,
    )
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.primary,
        modifier = Modifier.handMouseClickable(onClick = onClick),
    )
}
