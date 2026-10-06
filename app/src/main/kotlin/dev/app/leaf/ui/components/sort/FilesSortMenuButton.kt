// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.sort

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.sorting.FileSortKey
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import org.jetbrains.compose.resources.stringResource

/**
 * The sort and view button of the Files changed pane. Muted at the defaults (split columns, file name, A → Z),
 * accent-colored otherwise, with the sort name when the sort isn't the default and a folder icon in tree view.
 */
@Composable
fun FilesSortMenuButton(
    viewState: FilesViewState,
    onViewStateChange: (FilesViewState) -> Unit,
) {
    val (ascendingLabel, descendingLabel) = if (viewState.sortKey == FileSortKey.ChangeType) {
        stringResource(Res.string.files_changed_sort_added_first) to
                stringResource(Res.string.files_changed_sort_modified_first)
    } else {
        stringResource(Res.string.sort_order_a_to_z) to stringResource(Res.string.sort_order_z_to_a)
    }

    val items = buildList {
        add(SortMenuItem.Title(stringResource(Res.string.files_changed_sort_menu_title)))

        for (key in FileSortKey.entries) {
            add(
                SortMenuItem.Option(
                    label = fileSortKeyLabel(key),
                    isChecked = viewState.sortKey == key,
                    closesMenu = true,
                    onClick = { onViewStateChange(viewState.copy(sortKey = key, ascending = true)) },
                )
            )
        }

        add(SortMenuItem.Divider)
        add(SortMenuItem.Option(ascendingLabel, viewState.ascending) {
            onViewStateChange(viewState.copy(ascending = true))
        })
        add(SortMenuItem.Option(descendingLabel, !viewState.ascending) {
            onViewStateChange(viewState.copy(ascending = false))
        })

        add(SortMenuItem.Divider)
        add(SortMenuItem.Title(stringResource(Res.string.files_changed_show_as)))

        for (mode in FilesViewMode.entries) {
            val (label, hint) = when (mode) {
                FilesViewMode.FlatList -> Res.string.files_changed_view_flat_list to
                        Res.string.files_changed_view_flat_list_hint

                FilesViewMode.SplitColumns -> Res.string.files_changed_view_split_columns to
                        Res.string.files_changed_view_split_columns_hint

                FilesViewMode.FolderTree -> Res.string.files_changed_view_folder_tree to
                        Res.string.files_changed_view_folder_tree_hint
            }

            add(
                SortMenuItem.Option(
                    label = stringResource(label),
                    hint = stringResource(hint),
                    isChecked = viewState.viewMode == mode,
                    onClick = { onViewStateChange(viewState.copy(viewMode = mode)) },
                )
            )
        }
    }

    SortMenuButton(
        isActive = !viewState.isDefault,
        activeLabel = if (viewState.isSortDefault) null else fileSortKeyLabel(viewState.sortKey),
        showFolderIcon = viewState.viewMode == FilesViewMode.FolderTree,
        menuItems = items,
    )
}

@Composable
private fun fileSortKeyLabel(key: FileSortKey): String = stringResource(
    when (key) {
        FileSortKey.Path -> Res.string.files_changed_sort_path
        FileSortKey.FileName -> Res.string.files_changed_sort_file_name
        FileSortKey.ChangeType -> Res.string.files_changed_sort_change_type
    }
)
