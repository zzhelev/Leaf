package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.extensions.backgroundIf
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.keybindings.Keybinding
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.ui.KeybindingHint
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun QuickActionsDialog(
    viewModel: QuickActionsViewModel,
    onDismiss: () -> Unit,
    onShowSignOff: () -> Unit,
    onShowClone: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onCleanUpWorktrees: () -> Unit,
) {
    val textFieldFocusRequester = remember { FocusRequester() }
    val checkForUpdates = stringResource(Res.string.quick_actions_check_for_updates)
    // Fork-only: offered while the tab's worktree list has merged or missing worktrees
    val cleanUpWorktrees = stringResource(Res.string.quick_actions_clean_up_worktrees)
    val canCleanUpWorktrees by viewModel.canCleanUpWorktrees.collectAsState()
    // Fork-only: says what the entry does now, and the shortcut that does the same
    val isBranchesPaneFolded by viewModel.isBranchesPaneFolded.collectAsState()
    val hideBranchesPane = stringResource(Res.string.quick_actions_hide_branches_pane)
    val showBranchesPane = stringResource(Res.string.quick_actions_show_branches_pane)

    // TODO Extract list of actions strings
    val items = remember(checkForUpdates, cleanUpWorktrees, canCleanUpWorktrees, isBranchesPaneFolded) {
        listOfNotNull(
            QuickAction(Res.drawable.code, "Open repository in file manager", QuickActionType.OPEN_DIR_IN_FILE_MANAGER),
            QuickAction(Res.drawable.download, "Clone new repository", QuickActionType.CLONE),
            QuickAction(Res.drawable.refresh, "Refresh repository data", QuickActionType.REFRESH),
            QuickAction(Res.drawable.sign, "Signoff config", QuickActionType.SIGN_OFF),
            QuickAction(Res.drawable.update, checkForUpdates, QuickActionType.CHECK_FOR_UPDATES),
            QuickAction(Res.drawable.delete, cleanUpWorktrees, QuickActionType.CLEAN_UP_WORKTREES)
                .takeIf { canCleanUpWorktrees },
            branchesPaneAction(isBranchesPaneFolded, hideBranchesPane, showBranchesPane),
        )
    }

    var searchFilter by remember { mutableStateOf("") }

    val filteredItems by remember(searchFilter) {
        derivedStateOf { items.filter { it.title.contains(searchFilter, ignoreCase = true) } }
    }

    var selectedIndex by remember(filteredItems) {
        mutableStateOf(0)
    }

    QuickActionView(
        onDismiss = onDismiss,
        selectedIndex = selectedIndex,
        filteredItems = filteredItems,
        onAction = { action ->
            // First: dismissing removes the top of the back stack, which afterwards is the dialog the action opened
            onDismiss()

            when (action) {
                QuickActionType.OPEN_DIR_IN_FILE_MANAGER -> viewModel.openProjectInFileExplorer()
                QuickActionType.CLONE -> onShowClone()
                QuickActionType.REFRESH -> viewModel.refreshRepository()
                QuickActionType.SIGN_OFF -> onShowSignOff()
                QuickActionType.CHECK_FOR_UPDATES -> onCheckForUpdates()
                QuickActionType.CLEAN_UP_WORKTREES -> onCleanUpWorktrees()
                QuickActionType.TOGGLE_BRANCHES_PANE -> viewModel.toggleBranchesPane()
            }
        },
        searchFilter = searchFilter,
        textFieldFocusRequester = textFieldFocusRequester,
        onChangeSelectedIndex = { selectedIndex = it },
        onChangeSearchFilter = { searchFilter = it },
    )
}

@Composable
internal fun QuickActionView(
    onDismiss: () -> Unit,
    selectedIndex: Int,
    filteredItems: List<QuickAction>,
    onAction: (QuickActionType) -> Unit,
    searchFilter: String,
    textFieldFocusRequester: FocusRequester,
    onChangeSelectedIndex: (Int) -> Unit,
    onChangeSearchFilter: (String) -> Unit,
) {
    MaterialDialog(
        onCloseRequested = onDismiss,
        background = MaterialTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier.width(680.dp)
                .height(400.dp)
                .onKeyEvent { keyEvent ->
                    if (keyEvent.matchesBinding(KeybindingOption.DOWN)) {
                        if (selectedIndex < filteredItems.count() - 1)
                            onChangeSelectedIndex(selectedIndex + 1)
                        true
                    } else if (keyEvent.matchesBinding(KeybindingOption.UP)) {
                        if (selectedIndex > 0)
                            onChangeSelectedIndex(selectedIndex - 1)
                        true
                    } else if (keyEvent.matchesBinding(KeybindingOption.SIMPLE_ACCEPT)) {
                        val item = filteredItems.getOrNull(selectedIndex)
                        if (item != null)
                            onAction(item.type)
                        true
                    } else
                        false
                }
        ) {
            AdjustableOutlinedTextField(
                value = searchFilter,
                hint = "Search for an action or press ESC to close the dialog", // TODO don't hardcode ESC here, fix keybinding toString
                onValueChange = { onChangeSearchFilter(it) },
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .focusRequester(textFieldFocusRequester)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(filteredItems) { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .backgroundIf(selectedIndex == index, MaterialTheme.colors.backgroundSelected)
                            .handMouseClickable { onAction(item.type) }
                    ) {
                        Icon(
                            painterResource(item.icon),
                            contentDescription = null,
                            modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp),
                            tint = MaterialTheme.colors.onBackground,
                        )

                        Text(
                            item.title,
                            color = MaterialTheme.colors.onBackground,
                            style = MaterialTheme.typography.body1,
                        )

                        // Fork-only: the shortcut that does the same
                        item.keybinding?.let { binding ->
                            Spacer(Modifier.weight(1f))

                            Box(Modifier.padding(horizontal = 16.dp)) { KeybindingHint(binding) }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                textFieldFocusRequester.requestFocus()
            }
        }
    }
}

data class QuickAction(
    val icon: DrawableResource,
    val title: String,
    val type: QuickActionType,
    /** The shortcut that does the same, shown at the end of the row (fork-only). */
    val keybinding: Keybinding? = null,
)

/** The entry that folds the branches pane away or shows it again, named for what it does now (fork-only). */
internal fun branchesPaneAction(isFolded: Boolean, hideTitle: String, showTitle: String) = QuickAction(
    Res.drawable.side_panel,
    if (isFolded) showTitle else hideTitle,
    QuickActionType.TOGGLE_BRANCHES_PANE,
    KeybindingOption.BRANCHES_PANE.keyBinding,
)

enum class QuickActionType {
    OPEN_DIR_IN_FILE_MANAGER,
    CLONE,
    REFRESH,
    SIGN_OFF,
    CHECK_FOR_UPDATES,
    CLEAN_UP_WORKTREES,
    TOGGLE_BRANCHES_PANE,
}