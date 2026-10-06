package dev.app.leaf.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.compose.rememberInTab
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.extensions.parentDirectoryPath
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.repositoryopen.CommitChangesAction
import dev.app.leaf.repositoryopen.CommitChangesState
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.*
import dev.app.leaf.ui.components.sort.FilesSortMenuButton
import dev.app.leaf.ui.context_menu.committedChangesEntriesContextMenuItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry

@Composable
fun CommitChanges(
    viewModel: RepositoryOpenViewModel,
    commitChangesState: CommitChangesState,
    onBlame: (String) -> Unit,
    onHistory: (String) -> Unit,
) {
    val diffSelected by viewModel
        .diffSelected
        .filterIsInstance<DiffSelected.CommitedChanges>()
        .collectAsState(null)

    CommitChangesView(
        diffSelected = diffSelected,
        commitChangesState = commitChangesState,
        onBlame = onBlame,
        onHistory = onHistory,
        onOpenFileInFolder = { viewModel.openFileInFolder(it) },
        onDiffSelected = {
            viewModel.onAction(CommitChangesAction.SelectEntry(it))
        },
        onSearchFilterToggled = { visible ->
            viewModel.onAction(CommitChangesAction.SearchFilterToggle(visible))
        },
        onSearchFocused = {
            viewModel.onAction(CommitChangesAction.AddSearchToCloseables)
        },
        onSearchFilterChanged = { filter ->
            viewModel.onAction(CommitChangesAction.SearchFilterChanged(filter))
        },
        onDirectoryClicked = { viewModel.onAction(CommitChangesAction.TreeDirectoryToggle(it)) },
        onViewStateChanged = { viewModel.onAction(CommitChangesAction.ViewStateChanged(it)) },
    )
}

@Composable
private fun CommitChangesView(
    commitChangesState: CommitChangesState,
    diffSelected: DiffSelected.CommitedChanges?,
    onBlame: (String) -> Unit,
    onHistory: (String) -> Unit,
    onOpenFileInFolder: (String) -> Unit,
    onDiffSelected: (DiffEntry) -> Unit,
    onSearchFilterToggled: (Boolean) -> Unit,
    onSearchFocused: () -> Unit,
    onSearchFilterChanged: (TextFieldValue) -> Unit,
    onDirectoryClicked: (path: String) -> Unit,
    onViewStateChanged: (FilesViewState) -> Unit,
) {
    val tabFocusRequester = LocalTabFocusRequester.current
    val commit = commitChangesState.commit

    LaunchedEffect(commitChangesState.showSearch) {
        if (!commitChangesState.showSearch) {
            tabFocusRequester.requestFocus()
        }
    }

    val showSearch = commitChangesState.showSearch
    val viewState = commitChangesState.viewState

    // TODO Is this needed?
    var searchFilter by remember(commitChangesState.searchFilter, showSearch, commitChangesState) {
        mutableStateOf(commitChangesState.searchFilter)
    }

    val changesListScroll = rememberInTab("commitChangesChangesScroll", commitChangesState.commit) {
        LazyListState()
    }

    val textScroll = rememberInTab("commitChangesTextScroll", commitChangesState.commit) {
        ScrollState(0)
    }

    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .fillMaxWidth()
                .weight(1f, fill = true)
                .background(MaterialTheme.colors.background)
        ) {
            FilesChangedHeader(
                title = "Files changed",
                showAsTree = false,
                showSearch = showSearch,
                onAlternateShowAsTree = null,
                sortAction = {
                    FilesSortMenuButton(viewState = viewState, onViewStateChange = onViewStateChanged)
                },
                searchFilter = searchFilter,
                onSearchFocused = onSearchFocused,
                onSearchFilterToggled = onSearchFilterToggled,
                onSearchFilterChanged = onSearchFilterChanged,
                showActionForSelected = false,
            )

            if (commitChangesState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            ChangedFilesList(
                rows = commitChangesState.rows,
                viewState = viewState,
                selectedEntries = diffSelected?.items?.map { it.diffEntry }.orEmpty(),
                listState = changesListScroll,
                resetKey = commit,
                onFileClick = onDiffSelected,
                onFolderToggle = onDirectoryClicked,
                onSplitRatioChange = { onViewStateChanged(viewState.copy(splitRatio = it)) },
                onGenerateContextMenu = { diffEntry ->
                    committedChangesEntriesContextMenuItems(
                        diffEntry,
                        onBlame = { onBlame(diffEntry.filePath) },
                        onHistory = { onHistory(diffEntry.filePath) },
                        onOpenFileInFolder = { onOpenFileInFolder(diffEntry.parentDirectoryPath) },
                    )
                },
            )
        }

        MessageAuthorFooter(
            commit,
            textScroll,
        )
    }
}

@Composable
private fun MessageAuthorFooter(
    commit: Commit,
    textScroll: ScrollState,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.background),
    ) {
        SelectionContainer {
            Text(
                text = commit.message,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(8.dp)
                    .verticalScroll(textScroll),
            )
        }

        CommitFooter(
            shortHash = commit.shortHash,
            hash = commit.hash,
            date = commit.date,
            author = commit.author,
        )
    }
}

@Composable
fun CommitFooter(
    shortHash: String,
    hash: String,
    date: Long,
    author: Identity,
) {
    var copied by remember(hash) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(MaterialTheme.colors.tertiarySurface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .size(40.dp),
            personIdent = author,
        )

        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            TooltipText(
                text = author.name.orEmpty(),
                maxLines = 1,
                style = MaterialTheme.typography.body2,
                tooltipTitle = author.email.orEmpty(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shortHash,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.handMouseClickable {
                        scope.launch {
                            clipboard.setText(AnnotatedString(hash))
                            copied = true
                            delay(2000) // 2s
                            copied = false
                        }
                    }
                )

                if (copied) {
                    Text(
                        text = "Copied!",
                        color = MaterialTheme.colors.primaryVariant,
                        maxLines = 1,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }


                Spacer(modifier = Modifier.weight(1f, fill = true))

                val smartDate = date.toSmartSystemString(
                    allowRelative = true,
                    showTime = true,
                )

                val smartDateTooltip = date.toSmartSystemString(
                    allowRelative = false,
                    showTime = true,
                )

                TooltipText(
                    text = smartDate,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.body2,
                    tooltipTitle = smartDateTooltip,
                )
            }
        }
    }
}
