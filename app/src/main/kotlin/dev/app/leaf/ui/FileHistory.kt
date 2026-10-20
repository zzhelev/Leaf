@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.app.leaf.extensions.backgroundIf
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.setClipboardText
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.AvatarImage
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.TooltipText
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.fileHistoryContextMenu
import dev.app.leaf.ui.diff.DiffLoadFailure
import dev.app.leaf.ui.diff.HunkSplitTextDiff
import dev.app.leaf.ui.diff.HunkUnifiedTextDiff
import dev.app.leaf.ui.getStyledErrorText
import dev.app.leaf.viewmodels.HistoryState
import dev.app.leaf.viewmodels.HistoryViewModel
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.viewmodels.positionOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@Composable
fun FileHistory(
    historyViewModel: HistoryViewModel,
    onClose: () -> Unit,
    // Fork-only: asks the user, then restores the file from the commit. Null leaves the menu without it.
    onRestoreVersion: ((Commit) -> Unit)? = null,
    // Fork-only: closes the history and shows the working tree's changes, where a restored file is
    onShowChanges: () -> Unit = {},
) {
    val historyState by historyViewModel.historyState.collectAsState()
    val canRestore by historyViewModel.canRestore.collectAsState()
    val restored by historyViewModel.restored.collectAsState()

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.matchesBinding(KeybindingOption.EXIT) && keyEvent.type == KeyEventType.KeyDown) {
                    onClose()
                    true
                } else if (keyEvent.matchesBinding(KeybindingOption.UP) && keyEvent.type == KeyEventType.KeyDown) {
                    // Fork-only: steps through the commits
                    historyViewModel.selectAdjacentCommit(-1)
                    true
                } else if (keyEvent.matchesBinding(KeybindingOption.DOWN) && keyEvent.type == KeyEventType.KeyDown) {
                    historyViewModel.selectAdjacentCommit(1)
                    true
                } else
                    false
            },
    ) {
        Header(filePath = historyState.filePath, onClose = onClose)

        restored?.let {
            RestoredVersionStrip(
                restored = it,
                onShowChanges = onShowChanges,
                onDismiss = { historyViewModel.dismissRestored() },
            )
        }

        HistoryContent(
            historyViewModel,
            historyState,
            onCommitSelected = { historyViewModel.selectCommit(it) },
            onSelectAdjacentCommit = { historyViewModel.selectAdjacentCommit(it) },
            onRestoreVersion = onRestoreVersion.takeIf { canRestore },
        )
    }
}

@Composable
private fun Header(
    filePath: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = filePath,
            style = MaterialTheme.typography.body1,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .handOnHover()
        ) {
            Image(
                painter = painterResource(Res.drawable.close),
                contentDescription = "Close history",
                colorFilter = ColorFilter.tint(MaterialTheme.colors.onBackground),
            )
        }
    }
}


@Composable
private fun HistoryContent(
    historyViewModel: HistoryViewModel,
    historyState: HistoryState,
    onCommitSelected: (Commit) -> Unit,
    onSelectAdjacentCommit: (offset: Int) -> Unit,
    onRestoreVersion: ((Commit) -> Unit)?,
) {
    val textScrollState by historyViewModel.lazyListState.collectAsState()
    val viewDiffResult by historyViewModel.viewDiffResult.collectAsState()
    val selectedCommit by historyViewModel.selectedCommit.collectAsState()

    when (historyState) {
        is HistoryState.Loaded -> HistoryContentLoaded(
            historyState = historyState,
            viewDiffResult = viewDiffResult,
            scrollState = textScrollState,
            selectedCommit = selectedCommit,
            onCommitSelected = onCommitSelected,
            onSelectAdjacentCommit = onSelectAdjacentCommit,
            onRestoreVersion = onRestoreVersion,
        )

        is HistoryState.Loading -> Box { }
        is HistoryState.Failed -> Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // It was a blank pane
            Text(
                text = historyState.error.getStyledErrorText(),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.widthIn(max = 600.dp),
            )
        }
    }
}

@Composable
fun HistoryContentLoaded(
    historyState: HistoryState.Loaded,
    viewDiffResult: ViewDiffResult?,
    scrollState: LazyListState,
    selectedCommit: Commit?,
    onCommitSelected: (Commit) -> Unit,
    onSelectAdjacentCommit: (offset: Int) -> Unit,
    onRestoreVersion: ((Commit) -> Unit)? = null,
) {
    // Fork-only: the selected commit's row, kept in view when ↑ and ↓ select another
    val commitsListState = rememberLazyListState()
    val selectedPosition = positionOf(historyState.commits, selectedCommit)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    LaunchedEffect(selectedPosition) {
        commitsListState.keepInView(selectedPosition)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
    ) {
        ScrollableLazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .background(MaterialTheme.colors.surface),
            state = commitsListState,
        ) {
            itemsIndexed(historyState.commits) { index, commit ->
                // Fork-only: a menu on the rows (Gitnuro#146, #149)
                ContextMenu(
                    items = {
                        fileHistoryContextMenu(
                            onRestoreVersion = onRestoreVersion?.let { restore -> { restore(commit) } },
                            onCopyCommitHash = { scope.launch { clipboard.setClipboardText(commit.hash) } },
                            onCopyCommitMessage = { scope.launch { clipboard.setClipboardText(commit.message.trim()) } },
                        )
                    }
                ) {
                    HistoryCommit(
                        commit = commit,
                        isSelected = index == selectedPosition,
                        onCommitSelected = { onCommitSelected(commit) }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (selectedCommit != null && selectedPosition >= 0) {
                FileHistoryCommitStrip(
                    commit = selectedCommit,
                    position = selectedPosition,
                    count = historyState.commits.count(),
                    onSelectAdjacentCommit = onSelectAdjacentCommit,
                    onShowInList = { scope.launch { commitsListState.showInMiddle(selectedPosition) } },
                )
            }

            // Fork-only: says why the diff isn't there, as a file that a partial clone couldn't download
            if (viewDiffResult is ViewDiffResult.Failed) {
                DiffLoadFailure(viewDiffResult.error, onTryAgain = { selectedCommit?.let(onCommitSelected) })
            }

            if (
                viewDiffResult != null &&
                viewDiffResult is ViewDiffResult.Loaded
            ) {
                when (val diffResult = viewDiffResult.diffResult) {
                    is DiffResult.Text -> {
                        HunkUnifiedTextDiff(
                            diffType = viewDiffResult.diffType,
                            scrollState = scrollState,
                            diffResult = diffResult,
                            canUseHunkActions = false,
                            onUnstageHunk = { _, _ -> },
                            onStageHunk = { _, _ -> },
                            onResetHunk = { _, _ -> },
                            onUnStageLine = { _, _, _ -> },
                            onDiscardLine = { _, _, _ -> },
                        )
                    }

                    is DiffResult.TextSplit -> {
                        HunkSplitTextDiff(
                            diffType = viewDiffResult.diffType,
                            scrollState = scrollState,
                            diffResult = diffResult,
                            canUseHunkActions = false,
                            onUnstageHunk = { _, _ -> },
                            onStageHunk = { _, _ -> },
                            onResetHunk = { _, _ -> },
                            onUnStageLine = { _, _, _ -> },
                            onDiscardLine = { _, _, _ -> },
                        )
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colors.background)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                )
            }
        }
    }
}

@Composable
fun HistoryCommit(
    commit: Commit,
    isSelected: Boolean,
    onCommitSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .backgroundIf(isSelected, MaterialTheme.colors.backgroundSelected)
            .handMouseClickable { onCommitSelected() }
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .size(40.dp),
            personIdent = commit.author,
        )

        Column {
            // Fork-only: the whole message, as the row has only its subject
            DelayedTooltip(text = remember(commit.message) { commit.message.forTooltip().ifBlank { null } }) {
                Text(
                    text = commit.shortMessage,
                    maxLines = 1,
                    style = MaterialTheme.typography.body1,
                )
            }

            Row {
                Text(
                    text = commit.shortHash,
                    maxLines = 1,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))

                val date = commit.date.toSmartSystemString()

                TooltipText(
                    text = date,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.body2,
                    tooltipTitle = date
                )
            }
        }
    }
}
