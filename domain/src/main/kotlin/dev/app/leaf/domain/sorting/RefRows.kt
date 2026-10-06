// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

/**
 * A ref to sort and group.
 *
 * @param key Unique and stable, for example the full ref name.
 * @param name What sorting and grouping use, and what the row shows outside a folder, for example `feature/x`.
 * @param date Epoch millis for the active date sort, or null when unknown (such as a branch never checked out).
 */
data class RefEntry<out T>(
    val item: T,
    val key: String,
    val name: String,
    val date: Long?,
)

sealed interface RefRow<out T> {
    val key: String

    data class Folder(
        override val key: String,
        val label: String,
        val count: Int,
        val isExpanded: Boolean,
    ) : RefRow<Nothing>

    data class Item<T>(
        val entry: RefEntry<T>,
        /** [RefEntry.name] without the folder prefix when the row is inside a folder. */
        val displayName: String,
        val depth: Int,
        /** Compact age such as `3d`, only while a date sort is active. */
        val ageLabel: String?,
    ) : RefRow<T> {
        override val key: String get() = entry.key
    }
}

/**
 * Sorts by [state]. Ties are broken by name, A → Z, and refs without a date go last in both orders. With
 * [keepHeadOnTop], the ref named [headName] comes first.
 */
fun <T> sortRefs(
    refs: List<RefEntry<T>>,
    state: RefSortState,
    headName: String?,
    keepHeadOnTop: Boolean,
): List<RefEntry<T>> {
    val sorted = refs.sortedWith(compareByNameOrDate(state, { it.name }, { it.date }))

    if (!keepHeadOnTop || headName == null) return sorted

    val headIndex = sorted.indexOfFirst { it.name == headName }

    if (headIndex <= 0) return sorted

    return buildList(sorted.size) {
        add(sorted[headIndex])
        sorted.forEachIndexed { index, entry -> if (index != headIndex) add(entry) }
    }
}

/** The text before the first `/` of a ref name, or null when it has no prefix (`develop`, `3.3.0`). */
fun refFolderOf(name: String): String? {
    val index = name.indexOf('/')

    return if (index > 0 && index < name.lastIndex) name.substring(0, index) else null
}

/**
 * Groups [sortedRefs] into folders by [refFolderOf]. Folders come first, ordered by [sortState] (by folder name, or by
 * their newest ref for date sorts), then refs without a prefix. Refs inside a folder keep their order and lose the
 * prefix in [RefRow.Item.displayName].
 *
 * @param folderKeyPrefix Prepended to the folder name to build its key, for example `local:` or `remote:origin/`.
 * @param headFolder The folder of the current branch, passed to [isFolderExpanded] as its default state.
 */
fun <T> groupRefsByPrefix(
    sortedRefs: List<RefEntry<T>>,
    sortState: RefSortState,
    folderKeyPrefix: String,
    headFolder: String?,
    isFolderExpanded: (key: String, containsHead: Boolean) -> Boolean,
    nowMillis: Long,
): List<RefRow<T>> {
    val folders = LinkedHashMap<String, MutableList<RefEntry<T>>>()
    val topLevel = mutableListOf<RefEntry<T>>()

    for (entry in sortedRefs) {
        val folder = refFolderOf(entry.name)

        if (folder == null) {
            topLevel.add(entry)
        } else {
            folders.getOrPut(folder) { mutableListOf() }.add(entry)
        }
    }

    // A folder's date is its newest ref
    val folderDates = folders.mapValues { (_, entries) -> entries.mapNotNull { it.date }.maxOrNull() }
    val folderOrder = folders.entries.sortedWith(
        compareByNameOrDate(sortState, nameOf = { it.key }, dateOf = { folderDates[it.key] })
    )

    val rows = mutableListOf<RefRow<T>>()

    for ((folder, entries) in folderOrder) {
        val key = folderKeyPrefix + folder
        val isExpanded = isFolderExpanded(key, folder == headFolder)

        rows.add(RefRow.Folder(key = key, label = folder, count = entries.size, isExpanded = isExpanded))

        if (isExpanded) {
            entries.mapTo(rows) { entry ->
                RefRow.Item(
                    entry = entry,
                    displayName = entry.name.substring(folder.length + 1),
                    depth = 1,
                    ageLabel = ageLabelOf(entry, sortState, nowMillis),
                )
            }
        }
    }

    topLevel.mapTo(rows) { entry -> topLevelItem(entry, sortState, nowMillis) }

    return rows
}

/**
 * The rows of one section: [sortRefs], then [groupRefsByPrefix] when [groupByPrefix] is on. A current branch kept on
 * top stays outside any folder, with its full name.
 */
fun <T> buildRefRows(
    refs: List<RefEntry<T>>,
    sortState: RefSortState,
    groupByPrefix: Boolean,
    folderKeyPrefix: String,
    nowMillis: Long,
    headName: String? = null,
    keepHeadOnTop: Boolean = false,
    isFolderExpanded: (key: String, containsHead: Boolean) -> Boolean = { _, containsHead -> containsHead },
): List<RefRow<T>> {
    val sorted = sortRefs(refs, sortState, headName, keepHeadOnTop)

    if (!groupByPrefix) {
        return sorted.map { entry -> topLevelItem(entry, sortState, nowMillis) }
    }

    val pinned = sorted.firstOrNull()?.takeIf { keepHeadOnTop && headName != null && it.name == headName }
    val grouped = groupRefsByPrefix(
        sortedRefs = if (pinned != null) sorted.drop(1) else sorted,
        sortState = sortState,
        folderKeyPrefix = folderKeyPrefix,
        headFolder = headName?.let(::refFolderOf),
        isFolderExpanded = isFolderExpanded,
        nowMillis = nowMillis,
    )

    return if (pinned != null) listOf(topLevelItem(pinned, sortState, nowMillis)) + grouped else grouped
}

private fun <T> topLevelItem(entry: RefEntry<T>, sortState: RefSortState, nowMillis: Long) = RefRow.Item(
    entry = entry,
    displayName = entry.name,
    depth = 0,
    ageLabel = ageLabelOf(entry, sortState, nowMillis),
)

private fun ageLabelOf(entry: RefEntry<*>, sortState: RefSortState, nowMillis: Long): String? {
    if (!sortState.key.isDate) return null

    return entry.date?.let { formatAge(it, nowMillis) }
}

/** Name sort, or date sort with undated items last and ties by name A → Z. */
private fun <E> compareByNameOrDate(
    state: RefSortState,
    nameOf: (E) -> String,
    dateOf: (E) -> Long?,
): Comparator<E> {
    if (!state.key.isDate) {
        val byName = Comparator<E> { a, b -> naturalCompare(nameOf(a), nameOf(b)) }

        return if (state.ascending) byName else byName.reversed()
    }

    return Comparator { a, b ->
        val dateA = dateOf(a)
        val dateB = dateOf(b)

        when {
            dateA == dateB -> naturalCompare(nameOf(a), nameOf(b))
            dateA == null -> 1
            dateB == null -> -1
            state.ascending -> dateB.compareTo(dateA)
            else -> dateA.compareTo(dateB)
        }
    }
}
