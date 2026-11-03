// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.amended_files_failed
import dev.app.leaf.app.generated.resources.amended_files_title
import dev.app.leaf.app.generated.resources.amended_files_try_again
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.extensions.parentDirectoryPath
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.icon
import dev.app.leaf.extensions.iconColor
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.FilesGroupHeader
import dev.app.leaf.ui.FilesSection
import dev.app.leaf.ui.context_menu.committedChangesEntriesContextMenuItems
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.compose.resources.stringResource

/**
 * [group] as the rows that follow Staged's own (fork-only, upstream issue 260): the files that the amended commit
 * changes. A click opens that commit's diff of the file, [selected] being the one that is open.
 */
internal fun amendedFilesSection(
    group: AmendedGroup,
    selected: DiffEntry?,
    onAction: (StatusAction) -> Unit,
    onBlameFile: (String) -> Unit,
    onHistoryFile: (String) -> Unit,
): FilesSection<DiffEntry> = FilesSection(
    header = { AmendedGroupHeader(group, onToggle = { onAction(StatusAction.ToggleAmendedGroup) }) },
    rows = if (group.isCollapsed) emptyList() else group.rows,
    selected = selected,
    fileIcon = { it.icon },
    fileIconColor = { it.iconColor },
    onFileClick = { onAction(StatusAction.OpenAmendedFile(it)) },
    onFolderToggle = { onAction(StatusAction.AmendedDirectoryToggle(it)) },
    onGenerateContextMenu = { entry ->
        committedChangesEntriesContextMenuItems(
            entry,
            onBlame = { onBlameFile(entry.filePath) },
            onHistory = { onHistoryFile(entry.filePath) },
            onOpenFileInFolder = { onAction(StatusAction.OpenInFolder(entry.parentDirectoryPath)) },
        )
    },
    message = if (!group.isCollapsed && group.error != null) {
        { AmendedFilesFailed(onRetry = { onAction(StatusAction.RetryAmendedFiles) }) }
    } else {
        null
    },
)

/** `[chevron] In the last commit ........ [count]`: a click closes the group or opens it. */
@Composable
internal fun AmendedGroupHeader(group: AmendedGroup, onToggle: () -> Unit) {
    FilesGroupHeader(
        title = stringResource(Res.string.amended_files_title),
        count = group.count.takeIf { group.showsCount },
        isCollapsed = group.isCollapsed,
        onToggle = onToggle,
    )
}

/** The group's rows when the commit's files couldn't be read, with the one thing to do about it. */
@Composable
private fun AmendedFilesFailed(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MaterialTheme.linesHeight.fileHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.amended_files_failed),
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )

        Text(
            text = stringResource(Res.string.amended_files_try_again),
            modifier = Modifier
                .padding(start = 12.dp)
                .handMouseClickable { onRetry() },
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.primaryVariant,
        )
    }
}
