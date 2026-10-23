// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components.sort

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.sorting.RefPanelSettings
import dev.app.leaf.domain.sorting.RefSection
import dev.app.leaf.domain.sorting.RefSortKey
import dev.app.leaf.domain.sorting.RefSortState
import org.jetbrains.compose.resources.stringResource

/** The sort button of a side panel section header. Group by prefix applies to every section at once. */
@Composable
fun RefSortMenuButton(
    section: RefSection,
    sortState: RefSortState,
    settings: RefPanelSettings,
    onSortChange: (RefSortState) -> Unit,
    onKeepHeadOnTopToggle: () -> Unit,
    onGroupByPrefixToggle: () -> Unit,
) {
    val title = stringResource(
        when (section) {
            RefSection.Local -> Res.string.sort_menu_title_local_branches
            RefSection.Remote -> Res.string.sort_menu_title_remote_branches
            RefSection.Tags -> Res.string.sort_menu_title_tags
        }
    )

    val (ascendingLabel, descendingLabel) = if (sortState.key.isDate) {
        stringResource(Res.string.sort_order_newest_first) to stringResource(Res.string.sort_order_oldest_first)
    } else {
        stringResource(Res.string.sort_order_a_to_z) to stringResource(Res.string.sort_order_z_to_a)
    }

    val items = buildList {
        add(SortMenuItem.Title(title))

        for (key in section.sortKeys) {
            add(
                SortMenuItem.Option(
                    label = refSortKeyLabel(key),
                    isChecked = sortState.key == key,
                    closesMenu = true,
                    onClick = { onSortChange(RefSortState(key, ascending = true)) },
                )
            )
        }

        add(SortMenuItem.Divider)
        add(SortMenuItem.Option(ascendingLabel, sortState.ascending) { onSortChange(sortState.copy(ascending = true)) })
        add(SortMenuItem.Option(descendingLabel, !sortState.ascending) { onSortChange(sortState.copy(ascending = false)) })

        if (section == RefSection.Local) {
            add(SortMenuItem.Divider)
            add(
                SortMenuItem.Option(
                    label = stringResource(Res.string.sort_keep_head_on_top),
                    isChecked = settings.keepHeadOnTop,
                    onClick = onKeepHeadOnTopToggle,
                )
            )
        }

        add(SortMenuItem.Divider)
        add(
            SortMenuItem.Option(
                label = stringResource(Res.string.sort_group_by_prefix),
                hint = stringResource(Res.string.sort_group_by_prefix_hint),
                isChecked = settings.groupByPrefix,
                onClick = onGroupByPrefixToggle,
            )
        )
    }

    SortMenuButton(
        isActive = !sortState.isDefault || settings.groupByPrefix,
        activeLabel = if (sortState.isDefault) null else refSortKeyShortLabel(sortState.key),
        showFolderIcon = settings.groupByPrefix,
        menuItems = items,
    )
}

@Composable
private fun refSortKeyLabel(key: RefSortKey): String = stringResource(
    when (key) {
        RefSortKey.Name -> Res.string.sort_key_name
        RefSortKey.LastCommit -> Res.string.sort_key_last_commit
        RefSortKey.LastCheckout -> Res.string.sort_key_last_checkout
        RefSortKey.TagDate -> Res.string.sort_key_tag_date
    }
)

@Composable
private fun refSortKeyShortLabel(key: RefSortKey): String = when (key) {
    RefSortKey.LastCheckout -> stringResource(Res.string.sort_key_last_checkout_short)
    else -> refSortKeyLabel(key)
}
