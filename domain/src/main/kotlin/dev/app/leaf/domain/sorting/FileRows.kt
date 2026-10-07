// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import kotlinx.serialization.Serializable

@Serializable
enum class FileSortKey { Path, FileName, ChangeType }

@Serializable
enum class FilesViewMode { FlatList, SplitColumns, FolderTree }

const val DEFAULT_FILES_SPLIT_RATIO = 0.4f

/**
 * How the Files changed, Staged and Unstaged panes sort and show files. [ascending] means A → Z for
 * [FileSortKey.Path] and [FileSortKey.FileName], and "Added first" for [FileSortKey.ChangeType].
 */
@Serializable
data class FilesViewState(
    val sortKey: FileSortKey = FileSortKey.FileName,
    val ascending: Boolean = true,
    val viewMode: FilesViewMode = FilesViewMode.SplitColumns,
    /** Share of the row width taken by the file name column in [FilesViewMode.SplitColumns]. */
    val splitRatio: Float = DEFAULT_FILES_SPLIT_RATIO,
) {
    val isSortDefault: Boolean get() = sortKey == FileSortKey.FileName && ascending

    /** Sort and view are at their defaults. The split ratio doesn't count. */
    val isDefault: Boolean get() = isSortDefault && viewMode == FilesViewMode.SplitColumns
}

/** Change types in their "Added first" order. Conflicts block a commit, so they come first in both orders. */
enum class FileChangeKind { Conflicting, Added, Modified, Renamed, Deleted }

/**
 * A changed file to sort and group.
 *
 * @param key Unique within the list, used for row keys.
 */
data class FileItem<out T>(
    val item: T,
    val key: String,
    val path: String,
    val kind: FileChangeKind,
) {
    val fileName: String = path.substringAfterLast('/')

    /** The parent directory, or an empty string for a file at the root. */
    val directory: String = path.substringBeforeLast('/', missingDelimiterValue = "")
}

sealed interface FileRow<out T> {
    val key: String
    val depth: Int

    data class Folder(
        /** Full path of the deepest directory in a compacted chain. */
        val path: String,
        /** The directory name, or a compacted chain such as `app/src`. */
        val label: String,
        /** Files under the folder, counted recursively. */
        val fileCount: Int,
        val isExpanded: Boolean,
        override val depth: Int,
    ) : FileRow<Nothing> {
        override val key: String get() = "dir:$path"
    }

    data class File<T>(
        val file: FileItem<T>,
        override val depth: Int,
    ) : FileRow<T> {
        override val key: String get() = "file:${file.key}"
    }
}

/** A directory in [buildFileTree]. The root has an empty [name] and [path]. */
data class FileNode<out T>(
    val name: String,
    val path: String,
    val dirs: List<FileNode<T>>,
    val files: List<FileItem<T>>,
) {
    val fileCount: Int = files.size + dirs.sumOf { it.fileCount }
}

/**
 * Sorts a flat list of files. Path compares directory by directory; File name breaks ties by path. Change type uses
 * Conflicting → Added → Modified → Renamed → Deleted, or Conflicting → Modified → Added → Renamed → Deleted when not
 * [ascending], with ties by path A → Z.
 */
fun <T> sortFiles(files: List<FileItem<T>>, sortKey: FileSortKey, ascending: Boolean): List<FileItem<T>> {
    val comparator: Comparator<FileItem<T>> = when (sortKey) {
        FileSortKey.Path -> Comparator<FileItem<T>> { a, b -> comparePaths(a.path, b.path) }
            .reversedIf(!ascending)

        FileSortKey.FileName -> Comparator<FileItem<T>> { a, b ->
            val result = naturalCompare(a.fileName, b.fileName)
            if (result != 0) result else comparePaths(a.path, b.path)
        }.reversedIf(!ascending)

        FileSortKey.ChangeType -> Comparator { a, b ->
            val result = changeRank(a.kind, ascending).compareTo(changeRank(b.kind, ascending))
            if (result != 0) result else comparePaths(a.path, b.path)
        }
    }

    return files.sortedWith(comparator)
}

/** Builds the directory tree of [files]. Child order is not meaningful; [flattenTree] sorts. */
fun <T> buildFileTree(files: List<FileItem<T>>): FileNode<T> {
    val root = MutableNode<T>("", "")

    for (file in files) {
        var node = root

        if (file.directory.isNotEmpty()) {
            for (segment in file.directory.split('/')) {
                node = node.dirs.getOrPut(segment) {
                    MutableNode(segment, if (node.path.isEmpty()) segment else "${node.path}/$segment")
                }
            }
        }

        node.files.add(file)
    }

    return root.toFileNode()
}

/**
 * Merges every directory that has no files and exactly one subdirectory with that subdirectory, so `app` → `src` →
 * `main` becomes one `app/src/main` node. The root itself is never merged.
 */
fun <T> compactChains(node: FileNode<T>): FileNode<T> = node.copy(dirs = node.dirs.map(::compactDirectory))

/**
 * The visible rows of a tree: in each directory, subfolders first by name, then files by [sortKey]. The order setting
 * applies to folders for Path and File name, and folders stay A → Z for Change type. Rows under a folder for which
 * [isCollapsed] returns true are left out.
 */
fun <T> flattenTree(
    root: FileNode<T>,
    sortKey: FileSortKey,
    ascending: Boolean,
    isCollapsed: (path: String) -> Boolean,
): List<FileRow<T>> {
    val folderComparator = Comparator<FileNode<T>> { a, b -> naturalCompare(a.name, b.name) }
        .reversedIf(!ascending && sortKey != FileSortKey.ChangeType)
    val rows = mutableListOf<FileRow<T>>()

    fun addRows(node: FileNode<T>, depth: Int) {
        for (dir in node.dirs.sortedWith(folderComparator)) {
            val isExpanded = !isCollapsed(dir.path)

            rows.add(FileRow.Folder(dir.path, dir.name, dir.fileCount, isExpanded, depth))

            if (isExpanded) addRows(dir, depth + 1)
        }

        sortFiles(node.files, sortKey, ascending).mapTo(rows) { FileRow.File(it, depth) }
    }

    addRows(root, 0)

    return rows
}

/**
 * The folders closed in one folder tree, by full path. A search opens every folder that has matches, so folders closed
 * during a search are kept apart in [searchCollapsed], to be dropped when the search ends.
 */
data class CollapsedFolders(
    val collapsed: Set<String> = emptySet(),
    val searchCollapsed: Set<String> = emptySet(),
) {
    fun isCollapsed(path: String, isSearching: Boolean): Boolean =
        path in (if (isSearching) searchCollapsed else collapsed)

    fun toggled(path: String, isSearching: Boolean): CollapsedFolders = if (isSearching) {
        copy(searchCollapsed = searchCollapsed.toggled(path))
    } else {
        copy(collapsed = collapsed.toggled(path))
    }

    fun withoutSearch(): CollapsedFolders = copy(searchCollapsed = emptySet())

    private fun Set<String>.toggled(path: String) = if (path in this) this - path else this + path
}

/** The rows for [state]: a sorted flat list, or the compacted folder tree. */
fun <T> buildFileRows(
    files: List<FileItem<T>>,
    state: FilesViewState,
    isCollapsed: (path: String) -> Boolean,
): List<FileRow<T>> = when (state.viewMode) {
    FilesViewMode.FolderTree -> flattenTree(
        root = compactChains(buildFileTree(files)),
        sortKey = state.sortKey,
        ascending = state.ascending,
        isCollapsed = isCollapsed,
    )

    FilesViewMode.FlatList, FilesViewMode.SplitColumns -> sortFiles(files, state.sortKey, state.ascending)
        .map { FileRow.File(it, depth = 0) }
}

private fun changeRank(kind: FileChangeKind, addedFirst: Boolean): Int = when {
    addedFirst -> kind.ordinal
    kind == FileChangeKind.Modified -> FileChangeKind.Added.ordinal
    kind == FileChangeKind.Added -> FileChangeKind.Modified.ordinal
    else -> kind.ordinal
}

/** Compares paths directory by directory with [naturalCompare], so `a/x` sorts before `a-b/x`. */
private fun comparePaths(a: String, b: String): Int {
    var startA = 0
    var startB = 0

    while (true) {
        val endA = a.indexOf('/', startA).let { if (it == -1) a.length else it }
        val endB = b.indexOf('/', startB).let { if (it == -1) b.length else it }
        val result = naturalCompare(a.substring(startA, endA), b.substring(startB, endB))

        if (result != 0) return result

        val aDone = endA == a.length
        val bDone = endB == b.length

        when {
            aDone && bDone -> return 0
            aDone -> return -1
            bDone -> return 1
        }

        startA = endA + 1
        startB = endB + 1
    }
}

private fun <T> compactDirectory(dir: FileNode<T>): FileNode<T> {
    var current = dir
    var name = dir.name

    while (current.files.isEmpty() && current.dirs.size == 1) {
        current = current.dirs.single()
        name = "$name/${current.name}"
    }

    return current.copy(name = name, dirs = current.dirs.map(::compactDirectory))
}

private fun <T> Comparator<T>.reversedIf(condition: Boolean): Comparator<T> = if (condition) reversed() else this

private class MutableNode<T>(val name: String, val path: String) {
    val dirs = LinkedHashMap<String, MutableNode<T>>()
    val files = mutableListOf<FileItem<T>>()

    fun toFileNode(): FileNode<T> = FileNode(name, path, dirs.values.map { it.toFileNode() }, files.toList())
}
