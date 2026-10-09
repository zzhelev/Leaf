package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branch
import dev.app.leaf.domain.errors.RenameBranchError
import dev.app.leaf.ui.dialogs.base.SingleTextFieldDialog
import dev.app.leaf.ui.getStyledErrorText
import dev.app.leaf.viewmodels.RenameBranchDialogViewModel
import dev.app.leaf.viewmodels.RenameState
import org.jetbrains.compose.resources.painterResource

@Composable
fun RenameBranchDialog(
    viewModel: RenameBranchDialogViewModel,
    onDismiss: () -> Unit,
) {
    val branch = viewModel.branch
    var field by remember(branch) {
        val branchName = branch.simpleName

        mutableStateOf(
            TextFieldValue(
                text = branchName,
                selection = TextRange(0, branchName.count())
            )
        )
    }

    val state by viewModel.renameState.collectAsState()

    LaunchedEffect(state) {
        if (state is RenameState.Success) {
            onDismiss()
        }
    }

    SingleTextFieldDialog(
        icon = painterResource(Res.drawable.branch),
        title = "Rename branch",
        subtitle = "Set a new name to the branch \"${branch.simpleName}\"",
        value = field,
        enabled = state is RenameState.Waiting || state is RenameState.Failed,
        onValueChange = {
            field = it
        },
        primaryActionText = "Rename branch",
        // A worktree rebases or bisects the branch, or the branch was renamed already: no other name can help
        isPrimaryActionEnabled = field.text.isNotBlank() && (state as? RenameState.Failed)?.error !is RenameBranchError,
        onDismiss = onDismiss,
        onPrimaryActionClicked = {
            viewModel.renameBranch(branch, field.text)
        },
        trailingContent = (state as? RenameState.Failed)?.let { failed ->
            {
                // As wide as the field, so the message doesn't widen the dialog
                Box(modifier = Modifier.padding(top = 8.dp).width(300.dp)) {
                    DialogWarning(failed.error.getStyledErrorText())
                }
            }
        },
    )
}
