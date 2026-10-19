package dev.app.leaf.ui.dialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.clone_branch_hint
import dev.app.leaf.app.generated.resources.clone_branch_title
import dev.app.leaf.app.generated.resources.clone_error_invalid_directory
import dev.app.leaf.app.generated.resources.clone_error_invalid_folder_name
import dev.app.leaf.app.generated.resources.clone_error_invalid_url
import dev.app.leaf.app.generated.resources.clone_only_this_branch
import dev.app.leaf.app.generated.resources.clone_only_this_branch_note
import dev.app.leaf.app.generated.resources.generic_save_as_default
import dev.app.leaf.app.generated.resources.search
import dev.app.leaf.domain.clone.choicesFor
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.outlinedTextFieldColors
import dev.app.leaf.theme.textButtonColors
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.components.CheckboxText
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.ScrollableColumn
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import dev.app.leaf.ui.getErrorText
import dev.app.leaf.viewmodels.BranchChoicesState
import dev.app.leaf.viewmodels.CloneUiError
import dev.app.leaf.viewmodels.CloneViewModel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
fun CloneDialog(
    cloneViewModel: CloneViewModel,
    onClose: () -> Unit,
    onOpenRepository: (File) -> Unit,
) {
    val cloneStatus = cloneViewModel.cloneState.collectAsState()
    val cloneStatusValue = cloneStatus.value
    var url by remember(cloneViewModel) { mutableStateOf(cloneViewModel.repositoryUrl.value) }
    var directory by remember(cloneViewModel) { mutableStateOf(cloneViewModel.directoryPath.value) }
    var folder by remember(cloneViewModel) { mutableStateOf(cloneViewModel.folder.value) }
    var branch by remember(cloneViewModel) { mutableStateOf(cloneViewModel.branch.value) }
    var onlyThisBranch by remember { mutableStateOf(false) }
    var cloneSubmodules by remember { mutableStateOf(true) }
    val isBranchListOpen = cloneViewModel.branchChoices.collectAsState().value !is BranchChoicesState.Closed

    // Closing the dialog closes the list, which stops git if it still waits for the remote
    DisposableEffect(cloneViewModel) {
        onDispose { cloneViewModel.closeBranchChoices() }
    }

    // Starts from the default clone directory, unless a directory is already there
    LaunchedEffect(cloneViewModel) {
        val defaultDirectory = cloneViewModel.defaultDirectory()

        if (defaultDirectory != null && directory.text.isEmpty()) {
            directory = TextFieldValue(defaultDirectory, selection = TextRange(defaultDirectory.length))
            cloneViewModel.onDirectoryPathChanged(directory)
        }
    }

    MaterialDialog(
        // Esc closes the list under the Branch field first
        onCloseRequested = { if (isBranchListOpen) cloneViewModel.closeBranchChoices() else onClose() },
        background = MaterialTheme.colors.surface,
    ) {
        Box(
            modifier = Modifier
                .width(720.dp)
                .animateContentSize()
        ) {
            when (cloneStatusValue) {
                is CloneState.Cloning -> {
                    Cloning(cloneViewModel, cloneStatusValue)
                }

                is CloneState.Cancelling -> {
                    Cancelling()
                }

                is CloneState.Completed -> {
                    // Once, not at each composition: closing twice would remove another screen too
                    LaunchedEffect(cloneStatusValue) {
                        onOpenRepository(cloneStatusValue.repoDir)
                        onClose()
                    }
                }

                is CloneState.Fail, CloneState.None -> CloneDialogView(
                    cloneViewModel = cloneViewModel,
                    onClose = onClose,
                    url,
                    directory,
                    folder,
                    cloneSubmodules,
                    onUrlChange = { url = it },
                    onDirectoryChange = { directory = it },
                    onFolderChange = { folder = it },
                    onCloneSubmodulesChange = { cloneSubmodules = it },
                    branch = branch,
                    onlyThisBranch = onlyThisBranch,
                    onBranchChange = { branch = it },
                    onOnlyThisBranchChange = { onlyThisBranch = it },
                )
            }
        }
    }
}

@Composable
private fun CloneDialogView(
    cloneViewModel: CloneViewModel,
    onClose: () -> Unit,
    url: TextFieldValue,
    directory: TextFieldValue,
    folder: TextFieldValue,
    cloneSubmodules: Boolean,
    onUrlChange: (TextFieldValue) -> Unit,
    onDirectoryChange: (TextFieldValue) -> Unit,
    onFolderChange: (TextFieldValue) -> Unit,
    onCloneSubmodulesChange: (Boolean) -> Unit,
    branch: TextFieldValue,
    onlyThisBranch: Boolean,
    onBranchChange: (TextFieldValue) -> Unit,
    onOnlyThisBranchChange: (Boolean) -> Unit,
) {
    val error by cloneViewModel.error.collectAsState()
    val saveDirAsDefault by cloneViewModel.saveDirAsDefault.collectAsState()
    val branchChoices by cloneViewModel.branchChoices.collectAsState()
    val density = LocalDensity.current

    var dialogCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var branchRowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var branchRowWidth by remember { mutableStateOf(0) }

    // What "Choose…" lists for what the field holds: the remote was asked once, typing only filters what it sent
    val choices = remember(branchChoices, branch.text) {
        (branchChoices as? BranchChoicesState.Loaded)?.refs?.choicesFor(branch.text)
    }
    val choiceRows = remember(choices) { choices?.let { it.branches + it.tags }.orEmpty() }
    var highlighted by remember(branchChoices, branch.text) { mutableStateOf(-1) }

    val urlFocusRequester = remember { FocusRequester() }
    val directoryFocusRequester = remember { FocusRequester() }
    val folderFocusRequester = remember { FocusRequester() }
    val branchFocusRequester = remember { FocusRequester() }
    val chooseButtonFocusRequester = remember { FocusRequester() }
    val directoryButtonFocusRequester = remember { FocusRequester() }
    val cloneButtonFocusRequester = remember { FocusRequester() }
    val cancelButtonFocusRequester = remember { FocusRequester() }

    fun pickBranch(name: String) {
        onBranchChange(TextFieldValue(name, selection = TextRange(name.length)))
        cloneViewModel.pickBranch(name)
        cloneViewModel.resetStateIfError()
        branchFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .onGloballyPositioned { dialogCoordinates = it }
            .closingBranchChoicesOnPressOutside(
                isOpen = { cloneViewModel.branchChoices.value !is BranchChoicesState.Closed },
                dialog = { dialogCoordinates },
                branchRow = { branchRowCoordinates },
                onClose = cloneViewModel::closeBranchChoices,
            )
    ) {
        Text(
            "Clone a new repository",
            style = MaterialTheme.typography.h4,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            fontWeight = FontWeight.SemiBold,
        )

        TextInput(
            modifier = Modifier.padding(top = 8.dp).onFocusChanged {
                if (!it.hasFocus) {
                    onFolderChange(TextFieldValue(cloneViewModel.repoName(url.text)))
                }
            },
            title = "URL",
            value = url,
            focusRequester = urlFocusRequester,
            focusProperties = {
                previous = cancelButtonFocusRequester
                next = directoryFocusRequester
            },
            onValueChange = { repositoryUrl ->
                onUrlChange(repositoryUrl)
                cloneViewModel.onRepositoryUrlChanged(repositoryUrl)
                cloneViewModel.resetStateIfError()
            }
        )

        TextInput(
            modifier = Modifier.padding(top = 16.dp),
            title = "Directory",
            value = directory,
            focusRequester = directoryFocusRequester,
            focusProperties = {
                previous = urlFocusRequester
                next = directoryButtonFocusRequester
            },
            onValueChange = {
                onDirectoryChange(it)
                cloneViewModel.onDirectoryPathChanged(directory)
                cloneViewModel.resetStateIfError()
            },
            trailingIcon = {
                IconButton(
                    onClick = {
                        cloneViewModel.resetStateIfError()
                        val newDirectory = cloneViewModel.openDirectoryPicker()
                        if (newDirectory != null) {
                            onDirectoryChange(TextFieldValue(newDirectory, selection = TextRange(newDirectory.count())))
                            cloneViewModel.onDirectoryPathChanged(directory)
                            cloneViewModel.resetStateIfError()
                            directoryFocusRequester.requestFocus()
                        }
                    },
                    modifier = Modifier
                        .focusRequester(directoryButtonFocusRequester)
                        .focusProperties {
                            previous = directoryFocusRequester
                            next = folderFocusRequester
                        }
                        .handOnHover()
                        .size(40.dp),
                ) {
                    Icon(
                        painterResource(Res.drawable.search),
                        contentDescription = "Search",
                        tint = MaterialTheme.colors.onBackground,
                    )
                }
            }
        )
        CheckboxText(
            value = saveDirAsDefault,
            onCheckedChange = { cloneViewModel.onSaveAsDefaultChanged(!saveDirAsDefault) },
            text = stringResource(Res.string.generic_save_as_default)
        )

        TextInput(
            modifier = Modifier.padding(top = 16.dp),
            title = "Folder",
            value = folder,
            focusRequester = folderFocusRequester,
            focusProperties = {
                previous = cancelButtonFocusRequester
                next = branchFocusRequester
            },
            onValueChange = { folderName ->
                onFolderChange(folderName)
                cloneViewModel.onFolderNameChanged(folderName)
                cloneViewModel.resetStateIfError()
            }
        )

        TextInput(
            modifier = Modifier.padding(top = 16.dp).branchChoicesKeys(
                highlighted = highlighted,
                rowCount = choiceRows.size,
                onHighlight = { highlighted = it },
                onPick = { pickBranch(choiceRows[it].name) },
            ),
            title = stringResource(Res.string.clone_branch_title),
            hint = stringResource(Res.string.clone_branch_hint),
            value = branch,
            focusRequester = branchFocusRequester,
            focusProperties = {
                previous = folderFocusRequester
                next = chooseButtonFocusRequester
            },
            onValueChange = { branchName ->
                onBranchChange(branchName)
                cloneViewModel.onBranchChanged(branchName)
                cloneViewModel.resetStateIfError()
            },
            fieldRowModifier = Modifier.onGloballyPositioned {
                branchRowCoordinates = it
                branchRowWidth = it.size.width
            },
            end = {
                ChooseBranchButton(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .focusRequester(chooseButtonFocusRequester)
                        .focusProperties {
                            previous = branchFocusRequester
                            next = cloneButtonFocusRequester
                        },
                    onClick = {
                        // The one place that asks the remote: typing and opening the dialog never do
                        cloneViewModel.chooseBranch(url.text)

                        if (url.text.isBlank()) urlFocusRequester.requestFocus() else branchFocusRequester.requestFocus()
                    },
                )

                BranchChoicesPopup(
                    state = branchChoices,
                    choices = choices,
                    highlighted = highlighted,
                    width = with(density) { branchRowWidth.toDp() },
                    onPick = ::pickBranch,
                    onTryAgain = { cloneViewModel.chooseBranch(url.text) },
                    onCancel = cloneViewModel::closeBranchChoices,
                )
            },
        )

        // Needs a branch to be only that one
        val hasBranch = branch.text.isNotBlank()
        val singleBranch = onlyThisBranch && hasBranch

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .alpha(if (hasBranch) 1f else 0.5f)
                .then(
                    if (hasBranch) {
                        Modifier.handMouseClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            onOnlyThisBranchChange(!onlyThisBranch)
                        }
                    } else {
                        Modifier
                    }
                )
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Checkbox(
                checked = singleBranch,
                onCheckedChange = onOnlyThisBranchChange,
                enabled = hasBranch,
                modifier = Modifier
                    .padding(all = 8.dp)
                    .size(12.dp)
            )

            Text(
                stringResource(Res.string.clone_only_this_branch),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
            )
        }

        AnimatedVisibility(singleBranch) {
            Text(
                stringResource(Res.string.clone_only_this_branch_note),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(start = 28.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.handMouseClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                onCloneSubmodulesChange(!cloneSubmodules)
            }
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Checkbox(
                checked = cloneSubmodules,
                onCheckedChange = {
                    onCloneSubmodulesChange(it)
                },
                modifier = Modifier
                    .padding(all = 8.dp)
                    .size(12.dp)
            )

            Text(
                "Clone submodules recursively",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
            )
        }

        AnimatedVisibility(error != null) {
            // git's output can be long, and must not push the buttons out of the dialog
            ScrollableColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colors.error)
            ) {
                Text(
                    error?.toUiString().orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 8.dp),
                    color = MaterialTheme.colors.onError,
                )
            }
        }

        Row(
            modifier = Modifier
                .padding(top = 16.dp)
                .align(Alignment.End)
        ) {
            PrimaryButton(
                text = "Cancel",
                modifier = Modifier.padding(end = 8.dp)
                    .focusRequester(cancelButtonFocusRequester)
                    .focusProperties {
                        previous = cloneButtonFocusRequester
                        next = urlFocusRequester
                    },
                onClick = onClose,
                backgroundColor = Color.Transparent,
                textColor = MaterialTheme.colors.onBackground,
            )
            PrimaryButton(
                onClick = {
                    cloneViewModel.clone(directory.text, url.text, folder.text, cloneSubmodules, branch.text, singleBranch)
                },
                modifier = Modifier
                    .focusRequester(cloneButtonFocusRequester)
                    .focusProperties {
                        previous = chooseButtonFocusRequester
                        next = cancelButtonFocusRequester
                    },
                text = "Clone"
            )
        }

        LaunchedEffect(Unit) {
            urlFocusRequester.requestFocus()
        }
    }
}

@Composable
private fun CloneUiError.toUiString(): String {
    return when (this) {
        is CloneUiError.CloneError -> this.error.getErrorText()
        CloneUiError.EmptyDirectory -> stringResource(Res.string.clone_error_invalid_directory)
        CloneUiError.EmptyFolderName -> stringResource(Res.string.clone_error_invalid_folder_name)
        CloneUiError.EmptyUrl -> stringResource(Res.string.clone_error_invalid_url)
    }
}

@Composable
private fun Cloning(cloneViewModel: CloneViewModel, cloneStateValue: CloneState.Cloning) {
    Box(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        val progress = remember(cloneStateValue) {
            val total = cloneStateValue.total

            if (total == 0) // Prevent division by 0
                -1f
            else
                cloneStateValue.progress / total.toFloat()
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Text(cloneStateValue.taskName, color = MaterialTheme.colors.onBackground)

            if (progress >= 0f)
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 16.dp),
                    progress = progress,
                    color = MaterialTheme.colors.primaryVariant,
                )
            else // Show indeterminate if we do not know the total (aka total == 0 or progress == -1)
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colors.primaryVariant,
                )

        }

        TextButton(
            modifier = Modifier
                .padding(end = 8.dp)
                .align(Alignment.BottomEnd),
            colors = textButtonColors(),
            onClick = {
                cloneViewModel.cancelClone()
            }
        ) {
            Text(
                text = "Cancel",
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body1,
            )
        }
    }
}

@Composable
private fun Cancelling() {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colors.primaryVariant,
        )

        Text(
            text = "Cancelling clone operation...",
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun TextInput(
    modifier: Modifier = Modifier,
    title: String,
    hint: String = "",
    value: TextFieldValue,
    enabled: Boolean = true,
    focusRequester: FocusRequester,
    focusProperties: FocusProperties.() -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    textFieldShape: Shape = RoundedCornerShape(4.dp),
    trailingIcon: @Composable (() -> Unit)? = null,
    // Fork-only: something beside the field (the Branch field's "Choose…"), in a row of its own with [fieldRowModifier]
    fieldRowModifier: Modifier = Modifier,
    end: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.body1,
            modifier = Modifier
                .padding(bottom = 8.dp),
        )

        val field: @Composable (Modifier) -> Unit = { fieldModifier ->
            AdjustableOutlinedTextField(
                value = value,
                hint = hint,
                modifier = fieldModifier
                    .focusRequester(focusRequester)
                    .focusProperties(focusProperties),
                enabled = enabled,
                onValueChange = onValueChange,
                colors = outlinedTextFieldColors(),
                singleLine = true,
                shape = textFieldShape,
                trailingIcon = trailingIcon,
            )
        }

        if (end == null) {
            field(Modifier)
        } else {
            Row(modifier = fieldRowModifier, verticalAlignment = Alignment.CenterVertically) {
                field(Modifier.weight(1f))
                end()
            }
        }
    }
}