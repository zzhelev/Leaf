package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.ui.editor.FileOpening
import dev.app.leaf.ui.editor.addFileOpeningItems
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun committedChangesEntriesContextMenuItems(
    diffEntry: DiffEntry,
    onBlame: () -> Unit,
    onHistory: () -> Unit,
    onOpenFileInFolder: () -> Unit,
    /** Fork-only: "Open in …", "Open in another editor…" and "Show in Finder", which replaces "Open file in folder". */
    fileOpening: FileOpening? = null,
): List<ContextMenuElement> {
    return mutableListOf<ContextMenuElement>().apply {
        // Fork-only: the file as it is now in the working tree, which a deleted file isn't
        addFileOpeningItems(
            opening = fileOpening,
            path = diffEntry.filePath,
            canOpen = diffEntry.changeType != DiffEntry.ChangeType.DELETE,
            onShowWithoutOpening = onOpenFileInFolder,
        )

        // Blame reads the file as it is now, which a deleted file doesn't have. The condition before, "not added or
        // not deleted", was always true.
        if (diffEntry.changeType != DiffEntry.ChangeType.DELETE) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.committed_changes_context_menu_blame_file) },
                icon = { painterResource(Res.drawable.blame) },
                onClick = onBlame,
            )
        }

        addContextMenu(
            composableLabel = { stringResource(Res.string.committed_changes_context_menu_file_history) },
            icon = { painterResource(Res.drawable.history) },
            onClick = onHistory,
        )
    }
}