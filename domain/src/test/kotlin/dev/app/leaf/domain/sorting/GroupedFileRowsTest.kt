// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val UI = "app/src/main/kotlin/dev/app/leaf/ui"
private const val SORTING = "domain/src/main/kotlin/dev/app/leaf/domain/sorting"

/** Unstaged with a mix: tracked files changed and deleted, one with conflicts, and new files an agent wrote next to them. */
private val unstaged = listOf(
    StatusEntry("$UI/status/StatusPane.kt", StatusType.MODIFIED),
    StatusEntry("$UI/ChangedFilesList.kt", StatusType.MODIFIED),
    StatusEntry("$SORTING/FileRows.kt", StatusType.MODIFIED),
    StatusEntry("app/src/main/composeResources/values/strings.xml", StatusType.MODIFIED),
    StatusEntry("$UI/status/OldGroups.kt", StatusType.REMOVED),
    StatusEntry("docs/fork/CHANGELOG.md", StatusType.CONFLICTING),
    StatusEntry("$UI/status/UnstagedGroups.kt", StatusType.ADDED),
    StatusEntry("$SORTING/FileGroups.kt", StatusType.ADDED),
    StatusEntry("domain/src/test/kotlin/dev/app/leaf/domain/sorting/FileGroupsTest.kt", StatusType.ADDED),
    StatusEntry("notes/plan.md", StatusType.ADDED),
)

private fun rows(
    entries: List<StatusEntry> = unstaged,
    state: FilesViewState = FilesViewState(groupUntracked = true),
    collapsed: CollapsedGroups = CollapsedGroups(),
    isSearching: Boolean = false,
) = buildGroupedFileRows(
    entries.toFileItems(),
    groupOf = { it.item.fileGroup },
    state = state,
    isGroupCollapsed = { collapsed.isGroupCollapsed(it, isSearching) },
    isCollapsed = { group, path -> collapsed.isFolderCollapsed(group, path, isSearching) },
)

private fun List<FileRow<StatusEntry>>.labels() = map { row ->
    when (row) {
        is FileRow.Group -> "== ${row.group} ${row.fileCount}" + if (row.isExpanded) "" else " +"
        is FileRow.Folder -> "  ".repeat(row.depth) + "[${row.label}] ${row.fileCount}" + if (row.isExpanded) "" else " +"
        is FileRow.File -> "  ".repeat(row.depth) + row.file.fileName
    }
}

class GroupedFileRowsTest {
    @Test
    fun `an Unstaged entry's group is conflicts, changes to tracked files, or untracked`() {
        assertEquals(FileGroup.Conflicts, StatusEntry("a", StatusType.CONFLICTING).fileGroup)
        assertEquals(FileGroup.Changes, StatusEntry("a", StatusType.MODIFIED).fileGroup)
        assertEquals(FileGroup.Changes, StatusEntry("a", StatusType.REMOVED).fileGroup)
        assertEquals(FileGroup.Untracked, StatusEntry("a", StatusType.ADDED).fileGroup)
    }

    @Test
    fun `a file added with git add -N is in Changes, where git status lists it`() {
        assertEquals(FileGroup.Changes, StatusEntry("a", StatusType.ADDED, isIntentToAdd = true).fileGroup)
        assertEquals(FileGroup.Changes, StatusEntry("a", StatusType.REMOVED, isIntentToAdd = true).fileGroup)
    }

    @Test
    fun `each group gets a header with its count, then its files sorted as the list sorts`() {
        assertEquals(
            listOf(
                "== Conflicts 1",
                "CHANGELOG.md",
                "== Changes 5",
                "ChangedFilesList.kt",
                "FileRows.kt",
                "OldGroups.kt",
                "StatusPane.kt",
                "strings.xml",
                "== Untracked 4",
                "FileGroups.kt",
                "FileGroupsTest.kt",
                "plan.md",
                "UnstagedGroups.kt",
            ),
            rows().labels(),
        )
    }

    @Test
    fun `the list's sort applies inside each group`() {
        val byType = FilesViewState(sortKey = FileSortKey.ChangeType, ascending = false, groupUntracked = true)

        // Modified files before the deleted one, each kind by path
        assertEquals(
            listOf("strings.xml", "ChangedFilesList.kt", "StatusPane.kt", "FileRows.kt", "OldGroups.kt"),
            rows(state = byType).labels().dropWhile { it != "== Changes 5" }.drop(1).takeWhile { !it.startsWith("==") },
        )
    }

    @Test
    fun `a group without files has no header`() {
        val noConflicts = unstaged.filter { it.statusType != StatusType.CONFLICTING }

        assertEquals(listOf("== Changes 5", "== Untracked 4"), rows(noConflicts).labels().filter { it.startsWith("==") })

        val onlyNew = unstaged.filter { it.statusType == StatusType.ADDED }

        assertEquals(listOf("== Untracked 4"), rows(onlyNew).labels().filter { it.startsWith("==") })
        assertEquals(emptyList<String>(), rows(emptyList()).labels())
    }

    @Test
    fun `a closed group is its header with the count, and the others stay open`() {
        val collapsed = CollapsedGroups().groupToggled(FileGroup.Untracked, isSearching = false)

        val labels = rows(collapsed = collapsed).labels()

        assertEquals("== Untracked 4 +", labels.last())
        assertEquals(listOf("== Conflicts 1", "== Changes 5", "== Untracked 4 +"), labels.filter { it.startsWith("==") })
        assertEquals(9, labels.size)
    }

    @Test
    fun `in the tree each group has its own folders, so a folder can be in two groups with rows of their own`() {
        val tree = FilesViewState(viewMode = FilesViewMode.FolderTree, groupUntracked = true)

        assertEquals(
            listOf(
                "== Conflicts 1",
                "[docs/fork] 1",
                "  CHANGELOG.md",
                "== Changes 5",
                "[app/src/main] 4",
                "  [composeResources/values] 1",
                "    strings.xml",
                "  [kotlin/dev/app/leaf/ui] 3",
                "    [status] 2",
                "      OldGroups.kt",
                "      StatusPane.kt",
                "    ChangedFilesList.kt",
                "[$SORTING] 1",
                "  FileRows.kt",
                "== Untracked 4",
                "[app/src/main/kotlin/dev/app/leaf/ui/status] 1",
                "  UnstagedGroups.kt",
                "[domain/src] 2",
                "  [main/kotlin/dev/app/leaf/domain/sorting] 1",
                "    FileGroups.kt",
                "  [test/kotlin/dev/app/leaf/domain/sorting] 1",
                "    FileGroupsTest.kt",
                "[notes] 1",
                "  plan.md",
            ),
            rows(state = tree).labels(),
        )

        val folders = rows(state = tree).filterIsInstance<FileRow.Folder>()
        val keys = rows(state = tree).map { it.key }

        assertEquals(keys.distinct(), keys, "the list needs every row's key to be its own")
        assertTrue(folders.all { it.group != null }, folders.toString())
        assertEquals(
            setOf(FileGroup.Changes),
            folders.filter { it.path == "app/src/main" }.map { it.group }.toSet(),
        )
    }

    @Test
    fun `a folder closes in its own group only`() {
        val tree = FilesViewState(viewMode = FilesViewMode.FolderTree, groupUntracked = true)
        val entries = listOf(
            StatusEntry("src/Old.kt", StatusType.MODIFIED),
            StatusEntry("src/New.kt", StatusType.ADDED),
        )
        val collapsed = CollapsedGroups().folderToggled(FileGroup.Untracked, "src", isSearching = false)

        assertEquals(
            listOf("== Changes 1", "[src] 1", "  Old.kt", "== Untracked 1", "[src] 1 +"),
            rows(entries, tree, collapsed).labels(),
        )
        assertEquals(
            listOf("dir:Changes:src", "dir:Untracked:src"),
            rows(entries, tree, collapsed).filterIsInstance<FileRow.Folder>().map { it.key },
        )
    }

    @Test
    fun `what closes during a search is kept apart and dropped when the search ends`() {
        val closed = CollapsedGroups()
            .groupToggled(FileGroup.Changes, isSearching = false)
            .folderToggled(FileGroup.Untracked, "notes", isSearching = false)
            .groupToggled(FileGroup.Untracked, isSearching = true)
            .folderToggled(FileGroup.Changes, "src", isSearching = true)

        // A search opens what was closed before it
        assertEquals(false, closed.isGroupCollapsed(FileGroup.Changes, isSearching = true))
        assertEquals(false, closed.isFolderCollapsed(FileGroup.Untracked, "notes", isSearching = true))
        assertEquals(true, closed.isGroupCollapsed(FileGroup.Untracked, isSearching = true))
        assertEquals(true, closed.isFolderCollapsed(FileGroup.Changes, "src", isSearching = true))

        val after = closed.withoutSearch()

        assertEquals(true, after.isGroupCollapsed(FileGroup.Changes, isSearching = false))
        assertEquals(true, after.isFolderCollapsed(FileGroup.Untracked, "notes", isSearching = false))
        assertEquals(false, after.isGroupCollapsed(FileGroup.Untracked, isSearching = true))
        assertEquals(false, after.isFolderCollapsed(FileGroup.Changes, "src", isSearching = true))
    }

    @Test
    fun `a toggle twice opens again`() {
        val once = CollapsedGroups().groupToggled(FileGroup.Untracked, isSearching = false)

        assertEquals(CollapsedGroups(), once.groupToggled(FileGroup.Untracked, isSearching = false))
    }
}
