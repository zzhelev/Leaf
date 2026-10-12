package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.canOpenInMergeTool
import dev.app.leaf.domain.models.hasConflicts
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun statusEntryContextMenuItems(
    statusEntry: StatusEntry,
    entryType: EntryType,
    onReset: () -> Unit,
    onDelete: () -> Unit = {},
    onBlame: () -> Unit,
    onHistory: () -> Unit,
    onCopyFilePath: (relative: Boolean) -> Unit,
    onOpenFileInFolder: () -> Unit,
    /** Fork-only: for a conflicted file, its merge tool and "Use ours" and "Use theirs". */
    conflictActions: ConflictMenuActions? = null,
): List<ContextMenuElement> {
    return mutableListOf<ContextMenuElement>().apply {
        if (conflictActions != null && statusEntry.conflict != null) {
            if (statusEntry.canOpenInMergeTool) {
                addContextMenu(
                    composableLabel = {
                        stringResource(Res.string.status_entries_context_menu_open_in_external_merge_tool)
                    },
                    icon = { painterResource(Res.drawable.merge) },
                    onClick = conflictActions.onOpenInExternalMergeTool,
                )
            }

            addConflictSideItems(
                ours = { stringResource(Res.string.status_entries_context_menu_use_ours) },
                theirs = { stringResource(Res.string.status_entries_context_menu_use_theirs) },
                onUseSide = conflictActions.onUseSide,
            )

            add(ContextMenuElement.ContextSeparator)
        }

        if (statusEntry.statusType != StatusType.ADDED) {
            // Fork-only: not for a file with conflicts, which "Use ours" and "Use theirs" resolve
            if (!statusEntry.hasConflicts) {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.status_entries_context_menu_discard_file_changes) },
                    icon = { painterResource(Res.drawable.undo) },
                    onClick = onReset,
                )
            }

            if (statusEntry.statusType != StatusType.REMOVED) {
                addContextMenu(
                    composableLabel = { stringResource(Res.string.status_entries_context_menu_blame_file) },
                    icon = { painterResource(Res.drawable.blame) },
                    onClick = onBlame,
                )

                addContextMenu(
                    composableLabel = { stringResource(Res.string.status_entries_context_menu_file_history) },
                    icon = { painterResource(Res.drawable.history) },
                    onClick = onHistory,
                )
            }
        }

        if (
            entryType == EntryType.UNSTAGED &&
            statusEntry.statusType != StatusType.REMOVED
        ) {
            addContextMenu(
                composableLabel = { stringResource(Res.string.status_entries_context_menu_delete_file) },
                icon = { painterResource(Res.drawable.delete) },
                onClick = onDelete,
            )
        }

        addContextMenu(
            composableLabel = { stringResource(Res.string.status_entries_context_menu_open_file_in_folder) },
            icon = { painterResource(Res.drawable.folder_open) },
            onClick = onOpenFileInFolder,
        )


        add(ContextMenuElement.ContextSeparator)

        addContextMenu(
            composableLabel = {
                stringResource(Res.string.status_entries_context_menu_copy_file_absolute_path)
            },
            icon = { painterResource(Res.drawable.copy) },
            onClick = { onCopyFilePath(false) },
        )

        addContextMenu(
            composableLabel = {
                stringResource(Res.string.status_entries_context_menu_copy_file_relative_path)
            },
            icon = { painterResource(Res.drawable.copy_all) },
            onClick = { onCopyFilePath(true) },
        )
    }
}

fun statusEntriesContextMenuItems(
    selectedEntriesCount: Int,
    entryType: EntryType,
    /** Fork-only: null leaves it out, when every selected file has conflicts. */
    onDiscard: (() -> Unit)?,
    onStageSelected: () -> Unit,
    onUnstageSelected: () -> Unit,
    onCopyFilesPath: (relative: Boolean) -> Unit,
    /** Fork-only: "Use ours" and "Use theirs" for all of them, when they all have conflicts. */
    onUseConflictSide: ((ConflictSide) -> Unit)? = null,
): List<ContextMenuElement> {
    return mutableListOf<ContextMenuElement>().apply {
        if (onUseConflictSide != null) {
            addConflictSideItems(
                ours = {
                    stringResource(Res.string.status_entries_context_menu_use_ours_multiple_files, selectedEntriesCount)
                },
                theirs = {
                    stringResource(Res.string.status_entries_context_menu_use_theirs_multiple_files, selectedEntriesCount)
                },
                onUseSide = onUseConflictSide,
            )

            add(ContextMenuElement.ContextSeparator)
        }

        when (entryType) {
            EntryType.STAGED -> addContextMenu(
                composableLabel = {
                    stringResource(
                        Res.string.status_entries_context_menu_unstage_multiple_files,
                        selectedEntriesCount,
                    )
                },
                icon = { painterResource(Res.drawable.remove_done) },
                onClick = onUnstageSelected,
            )

            EntryType.UNSTAGED -> addContextMenu(
                composableLabel = {
                    stringResource(
                        Res.string.status_entries_context_menu_stage_multiple_files,
                        selectedEntriesCount,
                    )
                },
                icon = { painterResource(Res.drawable.done) },
                onClick = onStageSelected,
            )
        }

        if (onDiscard != null) {
            addContextMenu(
                composableLabel = {
                    stringResource(
                        Res.string.status_entries_context_menu_discard_multiple_files,
                        selectedEntriesCount
                    )
                },
                icon = { painterResource(Res.drawable.undo) },
                onClick = onDiscard,
            )
        }

        add(ContextMenuElement.ContextSeparator)

        addContextMenu(
            composableLabel = {
                stringResource(Res.string.status_entries_context_menu_copy_files_absolute_paths)
            },
            icon = { painterResource(Res.drawable.copy) },
            onClick = { onCopyFilesPath(false) },
        )

        addContextMenu(
            composableLabel = {
                stringResource(Res.string.status_entries_context_menu_copy_files_relative_paths)
            },
            icon = { painterResource(Res.drawable.copy_all) },
            onClick = { onCopyFilesPath(true) },
        )
    }
}
