// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.ignore.IgnoreKind
import dev.app.leaf.domain.ignore.IgnoreOption
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import dev.app.leaf.ui.getStyledErrorText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 480.dp

/** The most lines of the preview shown before it scrolls. */
private val PREVIEW_MAX_HEIGHT = 140.dp

/**
 * Adds ignore rules for untracked files or a folder (fork-only): what to ignore (the files, their folders, their
 * extensions, or a pattern typed), and where to write it (`.gitignore`, or the local `info/exclude`). It shows the lines
 * that it adds.
 */
@Composable
fun IgnoreDialog(
    viewModel: IgnoreViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    IgnoreDialogContent(
        request = viewModel.request,
        state = state,
        onChoice = viewModel::setChoice,
        onCustomText = viewModel::setCustomText,
        onPlace = viewModel::setPlace,
        onAdd = viewModel::add,
        onDismiss = onDismiss,
    )
}

/** The dialog for [state], which tests render without a view model. [onAdd] returns whether it added the rules. */
@Composable
internal fun IgnoreDialogContent(
    request: IgnoreRequest,
    state: IgnoreState,
    onChoice: (IgnoreChoice) -> Unit,
    onCustomText: (String) -> Unit,
    onPlace: (IgnorePlace) -> Unit,
    onAdd: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val customFocusRequester = remember { FocusRequester() }
    val isCustom = state.choice == IgnoreChoice.Custom
    val add = {
        if (onAdd()) {
            onDismiss()
        }
    }

    LaunchedEffect(isCustom) {
        if (isCustom) {
            customFocusRequester.requestFocus()
        }
    }

    IconBasedDialog(
        icon = painterResource(Res.drawable.visibility_off),
        title = stringResource(Res.string.ignore_dialog_title),
        subtitle = subtitle(request),
        primaryActionText = stringResource(Res.string.ignore_dialog_add),
        isPrimaryActionEnabled = state.canAdd,
        onDismiss = onDismiss,
        onPrimaryActionClicked = { add() },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        Column(
            modifier = Modifier.width(CONTENT_WIDTH),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { DialogWarning(it.getStyledErrorText()) }

            FieldLabel(stringResource(Res.string.ignore_dialog_what))

            for (option in state.options) {
                RadioRow(
                    isSelected = state.choice == IgnoreChoice.Way(option.kind),
                    text = option.label(),
                    onClick = { onChoice(IgnoreChoice.Way(option.kind)) },
                )

                if (option.unsupported > 0) {
                    DialogNote(
                        pluralStringResource(Res.plurals.ignore_dialog_unsupported, option.unsupported, option.unsupported)
                    )
                }
            }

            RadioRow(
                isSelected = isCustom,
                text = stringResource(Res.string.ignore_dialog_option_custom),
                onClick = { onChoice(IgnoreChoice.Custom) },
            )

            if (isCustom) {
                AdjustableOutlinedTextField(
                    value = state.customText,
                    onValueChange = onCustomText,
                    maxLines = CUSTOM_MAX_LINES,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = MaterialTheme.typography.body2.fontSize,
                        color = MaterialTheme.colors.onBackground,
                        fontFamily = monoTypography(),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(customFocusRequester)
                        .onPreviewKeyEvent { event ->
                            // Enter starts a new line in the field, so Cmd+Enter or Ctrl+Enter adds the rules
                            if (
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                (event.isMetaPressed || event.isCtrlPressed) &&
                                state.canAdd
                            ) {
                                add()
                                true
                            } else {
                                false
                            }
                        },
                )

                Text(
                    text = stringResource(Res.string.ignore_dialog_custom_hint),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            }

            FieldLabel(stringResource(Res.string.ignore_dialog_where))

            PlaceRow(
                isSelected = state.place == IgnorePlace.GITIGNORE,
                text = stringResource(Res.string.ignore_dialog_place_gitignore),
                hint = stringResource(Res.string.ignore_dialog_place_gitignore_hint),
                onClick = { onPlace(IgnorePlace.GITIGNORE) },
            )

            PlaceRow(
                isSelected = state.place == IgnorePlace.EXCLUDE,
                text = stringResource(Res.string.ignore_dialog_place_exclude),
                hint = stringResource(Res.string.ignore_dialog_place_exclude_hint),
                onClick = { onPlace(IgnorePlace.EXCLUDE) },
            )

            PlaceRow(
                isSelected = state.place == IgnorePlace.GLOBAL,
                text = stringResource(Res.string.ignore_dialog_place_global),
                hint = stringResource(Res.string.ignore_dialog_place_global_hint),
                onClick = { onPlace(IgnorePlace.GLOBAL) },
            )

            Preview(state)
        }
    }
}

private const val CUSTOM_MAX_LINES = 4

@Composable
private fun subtitle(request: IgnoreRequest): String = when (request) {
    is IgnoreRequest.Folder -> stringResource(Res.string.ignore_dialog_subtitle_folder, request.path)
    is IgnoreRequest.Files -> request.paths.singleOrNull()
        ?: pluralStringResource(Res.plurals.ignore_dialog_subtitle_files, request.paths.size, request.paths.size)
}

@Composable
private fun IgnoreOption.label(): String = when (kind) {
    IgnoreKind.FILES -> if (names.size == 1) {
        stringResource(Res.string.ignore_dialog_option_file)
    } else {
        stringResource(Res.string.ignore_dialog_option_files, names.size)
    }

    IgnoreKind.FOLDERS -> if (names.size == 1) {
        stringResource(Res.string.ignore_dialog_option_folder, names.first())
    } else {
        stringResource(Res.string.ignore_dialog_option_folders, names.size, fileList(names))
    }

    IgnoreKind.EXTENSIONS -> if (names.size == 1) {
        stringResource(Res.string.ignore_dialog_option_extension, names.first())
    } else {
        stringResource(Res.string.ignore_dialog_option_extensions, fileList(names))
    }
}

/** A radio row with a line under it that says what the place means. */
@Composable
private fun PlaceRow(isSelected: Boolean, text: String, hint: String, onClick: () -> Unit) {
    Column {
        RadioRow(isSelected = isSelected, text = text, onClick = onClick)

        Text(
            text = hint,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}

/** The lines that the dialog adds to the file of the place, or why there are none, or why it can't. */
@Composable
private fun Preview(state: IgnoreState) {
    val file = state.file

    if (file == null) {
        if (state.error == null) {
            DialogNote(stringResource(Res.string.ignore_dialog_reading))
        }

        return
    }

    val fileName = if (state.place == IgnorePlace.GITIGNORE) ".gitignore" else abbreviateHome(file.path)

    file.problem?.let {
        DialogWarning(it.getStyledErrorText())

        return
    }

    val rules = state.rules.distinct()
    val toAdd = state.toAdd

    when {
        rules.isEmpty() -> DialogNote(stringResource(Res.string.ignore_dialog_custom_empty))
        toAdd.isEmpty() -> DialogNote(stringResource(Res.string.ignore_dialog_already, fileName))
        else -> {
            FieldLabel(stringResource(Res.string.ignore_dialog_lines, fileName))

            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colors.secondarySurface),
                ) {
                    ScrollingBox(maxHeight = PREVIEW_MAX_HEIGHT) {
                        Text(
                            text = toAdd.joinToString("\n"),
                            modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                            color = MaterialTheme.colors.onBackground,
                            style = MaterialTheme.typography.body2,
                            fontFamily = monoTypography(),
                        )
                    }
                }
            }

            val already = rules.size - toAdd.size

            if (already > 0) {
                Text(
                    text = pluralStringResource(Res.plurals.ignore_dialog_already_some, already, already),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            }
        }
    }
}
