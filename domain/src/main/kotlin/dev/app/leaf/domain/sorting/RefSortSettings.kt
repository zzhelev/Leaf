// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import kotlinx.serialization.Serializable

@Serializable
enum class RefSortKey {
    Name,
    LastCommit,
    LastCheckout,
    TagDate;

    val isDate: Boolean get() = this != Name
}

/**
 * How one side panel section is sorted. [ascending] means A → Z for [RefSortKey.Name] and newest first for the date
 * keys, so the default order of every key is `ascending = true`.
 */
@Serializable
data class RefSortState(
    val key: RefSortKey = RefSortKey.Name,
    val ascending: Boolean = true,
) {
    val isDefault: Boolean get() = key == RefSortKey.Name && ascending
}

enum class RefSection(
    /** Prefix of the folder expansion keys, for example `local:feature`. */
    val id: String,
    val sortKeys: List<RefSortKey>,
) {
    Local("local", listOf(RefSortKey.Name, RefSortKey.LastCommit, RefSortKey.LastCheckout)),
    Remote("remote", listOf(RefSortKey.Name, RefSortKey.LastCommit)),
    Tags("tags", listOf(RefSortKey.Name, RefSortKey.TagDate)),
}

/** Side panel sort settings. Each section is sorted on its own; [groupByPrefix] applies to all of them. */
@Serializable
data class RefPanelSettings(
    val local: RefSortState = RefSortState(),
    val remote: RefSortState = RefSortState(),
    val tags: RefSortState = RefSortState(),
    val keepHeadOnTop: Boolean = true,
    val groupByPrefix: Boolean = false,
) {
    fun sortOf(section: RefSection): RefSortState {
        val state = when (section) {
            RefSection.Local -> local
            RefSection.Remote -> remote
            RefSection.Tags -> tags
        }

        // A key that doesn't apply to the section (for example from a hand-edited settings file) falls back to Name
        return if (state.key in section.sortKeys) state else RefSortState()
    }

    fun withSort(section: RefSection, state: RefSortState): RefPanelSettings = when (section) {
        RefSection.Local -> copy(local = state)
        RefSection.Remote -> copy(remote = state)
        RefSection.Tags -> copy(tags = state)
    }
}

/**
 * Folder rows the user opened or closed, by key (`local:feature`, `remote:origin/feature`, `tags:qa`). Only folders
 * whose state differs from the default are listed, so a folder returns to its default when toggled back.
 */
data class RefFolderExpansion(
    val expanded: Set<String> = emptySet(),
    val collapsed: Set<String> = emptySet(),
) {
    fun isExpanded(key: String, default: Boolean): Boolean = when (key) {
        in expanded -> true
        in collapsed -> false
        else -> default
    }

    fun toggled(key: String, default: Boolean): RefFolderExpansion {
        val expand = !isExpanded(key, default)
        val newExpanded = expanded - key
        val newCollapsed = collapsed - key

        return when {
            expand == default -> RefFolderExpansion(newExpanded, newCollapsed)
            expand -> RefFolderExpansion(newExpanded + key, newCollapsed)
            else -> RefFolderExpansion(newExpanded, newCollapsed + key)
        }
    }
}
