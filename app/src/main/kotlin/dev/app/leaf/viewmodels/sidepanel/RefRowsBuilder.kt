// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels.sidepanel

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RefDates
import dev.app.leaf.domain.models.RemoteInfo
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.sorting.*

/** Everything besides the refs themselves that decides how the side panel sections are sorted and grouped. */
data class RefRowsContext(
    val settings: RefPanelSettings = RefPanelSettings(),
    val dates: RefDates = RefDates(),
    val expansion: RefFolderExpansion = RefFolderExpansion(),
    /** Folders closed while searching. Every folder with matches is open during a search unless listed here. */
    val searchCollapsed: Set<String> = emptySet(),
)

fun localFolderKeyPrefix() = "${RefSection.Local.id}:"

fun remoteFolderKeyPrefix(remoteName: String) = "${RefSection.Remote.id}:$remoteName/"

fun tagFolderKeyPrefix() = "${RefSection.Tags.id}:"

/** The key of the folder holding the current branch, which is expanded by default. */
fun headFolderKey(currentBranch: Branch?): String? {
    val folder = currentBranch?.simpleName?.let(::refFolderOf) ?: return null

    return localFolderKeyPrefix() + folder
}

fun localBranchRows(
    branches: List<Branch>,
    currentBranch: Branch?,
    context: RefRowsContext,
    isSearching: Boolean,
    nowMillis: Long,
): List<RefRow<Branch>> {
    val sortState = context.settings.sortOf(RefSection.Local)
    val entries = branches.map { branch ->
        val date = when (sortState.key) {
            RefSortKey.LastCommit -> context.dates.commitTimes[branch.name]
            RefSortKey.LastCheckout -> context.dates.lastCheckoutTimes[branch.simpleName]
            else -> null
        }

        RefEntry(item = branch, key = branch.name, name = branch.simpleName, date = date)
    }

    return buildRefRows(
        refs = entries,
        sortState = sortState,
        groupByPrefix = context.settings.groupByPrefix,
        folderKeyPrefix = localFolderKeyPrefix(),
        nowMillis = nowMillis,
        headName = currentBranch?.simpleName,
        keepHeadOnTop = context.settings.keepHeadOnTop,
        isFolderExpanded = context.folderExpansion(isSearching),
    )
}

fun remoteBranchRows(
    remote: RemoteInfo,
    context: RefRowsContext,
    isSearching: Boolean,
    nowMillis: Long,
): List<RefRow<Branch>> {
    val sortState = context.settings.sortOf(RefSection.Remote)
    val entries = remote.branchesList.map { branch ->
        val date = if (sortState.key == RefSortKey.LastCommit) context.dates.commitTimes[branch.name] else null

        RefEntry(item = branch, key = branch.name, name = branch.simpleName, date = date)
    }

    return buildRefRows(
        refs = entries,
        sortState = sortState,
        groupByPrefix = context.settings.groupByPrefix,
        folderKeyPrefix = remoteFolderKeyPrefix(remote.remote.name),
        nowMillis = nowMillis,
        isFolderExpanded = context.folderExpansion(isSearching),
    )
}

fun tagRows(
    tags: List<Tag>,
    context: RefRowsContext,
    isSearching: Boolean,
    nowMillis: Long,
): List<RefRow<Tag>> {
    val sortState = context.settings.sortOf(RefSection.Tags)
    val entries = tags.map { tag ->
        val date = if (sortState.key == RefSortKey.TagDate) context.dates.tagTimes[tag.name] else null

        RefEntry(item = tag, key = tag.name, name = tag.simpleName, date = date)
    }

    return buildRefRows(
        refs = entries,
        sortState = sortState,
        groupByPrefix = context.settings.groupByPrefix,
        folderKeyPrefix = tagFolderKeyPrefix(),
        nowMillis = nowMillis,
        isFolderExpanded = context.folderExpansion(isSearching),
    )
}

private fun RefRowsContext.folderExpansion(isSearching: Boolean): (String, Boolean) -> Boolean =
    if (isSearching) {
        { key, _ -> key !in searchCollapsed }
    } else {
        { key, containsHead -> expansion.isExpanded(key, default = containsHead) }
    }
