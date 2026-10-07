// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun file(path: String, kind: FileChangeKind = FileChangeKind.Modified) =
    FileItem(item = path, key = path, path = path, kind = kind)

private fun List<FileItem<String>>.paths() = map { it.path }

private fun List<FileRow<String>>.labels() = map { row ->
    when (row) {
        is FileRow.Folder -> "  ".repeat(row.depth) + "[${row.label}] ${row.fileCount}" + if (row.isExpanded) "" else " +"
        is FileRow.File -> "  ".repeat(row.depth) + row.file.fileName
    }
}

private const val P = "app/src/main/java/com/ifco/myifco/count/android"

class FileRowsTest {
    private val files = listOf(
        file("app/src/androidTest/java/com/ifco/myifco/count/android/ui/settings/MemoryManagementScreenLaunchTest.kt"),
        file("$P/base/platform/SharedStorageSpace.kt"),
        file("$P/screen/settings/memory/MemoryManagementFragment.kt"),
        file("$P/screen/settings/memory/MemoryManagementModel.kt"),
        file("$P/services/housekeeping/MediaSpaceWatcher.kt"),
        file("app/src/main/res/values/strings.xml"),
        file("app/src/test/java/com/ifco/myifco/count/android/screen/settings/memory/MemoryManagementModelTest.kt", FileChangeKind.Added),
    )

    @Test
    fun `sorts a flat list by path, directory by directory`() {
        val list = listOf(file("a-b/x.txt"), file("a/z.txt"), file("a/b/c.txt"), file("README.md"))

        assertEquals(listOf("a/b/c.txt", "a/z.txt", "a-b/x.txt", "README.md"), sortFiles(list, FileSortKey.Path, true).paths())
        assertEquals(
            listOf("README.md", "a-b/x.txt", "a/z.txt", "a/b/c.txt"),
            sortFiles(list, FileSortKey.Path, false).paths(),
        )
    }

    @Test
    fun `sorts a flat list by file name with ties by path`() {
        val list = listOf(file("b/Main.kt"), file("a/Main.kt"), file("z/App.kt"), file("y/file10.txt"), file("y/file9.txt"))

        assertEquals(
            listOf("z/App.kt", "y/file9.txt", "y/file10.txt", "a/Main.kt", "b/Main.kt"),
            sortFiles(list, FileSortKey.FileName, true).paths(),
        )
        assertEquals(
            listOf("b/Main.kt", "a/Main.kt", "y/file10.txt", "y/file9.txt", "z/App.kt"),
            sortFiles(list, FileSortKey.FileName, false).paths(),
        )
    }

    @Test
    fun `sorts by change type with ties by path`() {
        val list = listOf(
            file("d2.txt", FileChangeKind.Deleted),
            file("m2.txt", FileChangeKind.Modified),
            file("r.txt", FileChangeKind.Renamed),
            file("a2.txt", FileChangeKind.Added),
            file("m1.txt", FileChangeKind.Modified),
            file("a1.txt", FileChangeKind.Added),
            file("d1.txt", FileChangeKind.Deleted),
        )

        assertEquals(
            listOf("a1.txt", "a2.txt", "m1.txt", "m2.txt", "r.txt", "d1.txt", "d2.txt"),
            sortFiles(list, FileSortKey.ChangeType, ascending = true).paths(),
        )
        assertEquals(
            listOf("m1.txt", "m2.txt", "a1.txt", "a2.txt", "r.txt", "d1.txt", "d2.txt"),
            sortFiles(list, FileSortKey.ChangeType, ascending = false).paths(),
        )
    }

    @Test
    fun `conflicts come first in both change type orders and sort by name otherwise`() {
        val list = listOf(
            file("a.txt", FileChangeKind.Added),
            file("z.txt", FileChangeKind.Conflicting),
            file("m.txt", FileChangeKind.Modified),
            file("c.txt", FileChangeKind.Conflicting),
        )

        assertEquals(
            listOf("c.txt", "z.txt", "a.txt", "m.txt"),
            sortFiles(list, FileSortKey.ChangeType, ascending = true).paths(),
        )
        assertEquals(
            listOf("c.txt", "z.txt", "m.txt", "a.txt"),
            sortFiles(list, FileSortKey.ChangeType, ascending = false).paths(),
        )
        assertEquals(
            listOf("a.txt", "c.txt", "m.txt", "z.txt"),
            sortFiles(list, FileSortKey.FileName, ascending = true).paths(),
        )
    }

    @Test
    fun `compacts single-child directory chains`() {
        val tree = compactChains(buildFileTree(files))

        val app = tree.dirs.single()

        assertEquals("app/src", app.name)
        assertEquals("app/src", app.path)

        val main = app.dirs.single { it.name == "main" }
        val java = main.dirs.single { it.name.startsWith("java") }

        assertEquals("java/com/ifco/myifco/count/android", java.name)
        assertEquals(P, java.path)
    }

    @Test
    fun `counts files recursively`() {
        val tree = compactChains(buildFileTree(files))
        val app = tree.dirs.single()

        assertEquals(7, tree.fileCount)
        assertEquals(7, app.fileCount)
        assertEquals(5, app.dirs.single { it.name == "main" }.fileCount)
    }

    @Test
    fun `flattens the tree with subfolders before files`() {
        val tree = compactChains(
            buildFileTree(listOf(file("src/b.kt"), file("src/a.kt"), file("src/util/x.kt"), file("build.gradle"), file("docs/z.md")))
        )

        assertEquals(
            listOf(
                "[docs] 1",
                "  z.md",
                "[src] 3",
                "  [util] 1",
                "    x.kt",
                "  a.kt",
                "  b.kt",
                "build.gradle",
            ),
            flattenTree(tree, FileSortKey.FileName, ascending = true, isCollapsed = { false }).labels(),
        )
    }

    @Test
    fun `builds the folder tree from the design example`() {
        val rows = buildFileRows(files, FilesViewState(viewMode = FilesViewMode.FolderTree), isCollapsed = { false })

        assertEquals(
            listOf(
                "[app/src] 7",
                "  [androidTest/java/com/ifco/myifco/count/android/ui/settings] 1",
                "    MemoryManagementScreenLaunchTest.kt",
                "  [main] 5",
                "    [java/com/ifco/myifco/count/android] 4",
                "      [base/platform] 1",
                "        SharedStorageSpace.kt",
                "      [screen/settings/memory] 2",
                "        MemoryManagementFragment.kt",
                "        MemoryManagementModel.kt",
                "      [services/housekeeping] 1",
                "        MediaSpaceWatcher.kt",
                "    [res/values] 1",
                "      strings.xml",
                "  [test/java/com/ifco/myifco/count/android/screen/settings/memory] 1",
                "    MemoryManagementModelTest.kt",
            ),
            rows.labels(),
        )
    }

    @Test
    fun `leaves out the contents of collapsed folders`() {
        val rows = buildFileRows(
            files,
            FilesViewState(viewMode = FilesViewMode.FolderTree),
            isCollapsed = { it == "app/src/main" },
        )

        assertEquals(
            listOf(
                "[app/src] 7",
                "  [androidTest/java/com/ifco/myifco/count/android/ui/settings] 1",
                "    MemoryManagementScreenLaunchTest.kt",
                "  [main] 5 +",
                "  [test/java/com/ifco/myifco/count/android/screen/settings/memory] 1",
                "    MemoryManagementModelTest.kt",
            ),
            rows.labels(),
        )
    }

    @Test
    fun `the order setting reverses folders for name sorts but not for change type`() {
        val tree = buildFileTree(listOf(file("a/1.txt"), file("b/2.txt", FileChangeKind.Added)))

        assertEquals(
            listOf("[b] 1", "  2.txt", "[a] 1", "  1.txt"),
            flattenTree(tree, FileSortKey.Path, ascending = false, isCollapsed = { false }).labels(),
        )
        assertEquals(
            listOf("[a] 1", "  1.txt", "[b] 1", "  2.txt"),
            flattenTree(tree, FileSortKey.ChangeType, ascending = false, isCollapsed = { false }).labels(),
        )
    }

    @Test
    fun `sorts files inside a folder by change type`() {
        val tree = buildFileTree(
            listOf(file("src/c.kt", FileChangeKind.Deleted), file("src/b.kt"), file("src/a.kt", FileChangeKind.Added))
        )

        assertEquals(
            listOf("[src] 3", "  a.kt", "  b.kt", "  c.kt"),
            flattenTree(tree, FileSortKey.ChangeType, ascending = true, isCollapsed = { false }).labels(),
        )
        assertEquals(
            listOf("[src] 3", "  b.kt", "  a.kt", "  c.kt"),
            flattenTree(tree, FileSortKey.ChangeType, ascending = false, isCollapsed = { false }).labels(),
        )
    }

    @Test
    fun `flat and split views list every file at depth zero`() {
        for (mode in listOf(FilesViewMode.FlatList, FilesViewMode.SplitColumns)) {
            val rows = buildFileRows(files, FilesViewState(viewMode = mode), isCollapsed = { true })

            assertEquals(files.size, rows.size)
            assertEquals(
                listOf(
                    "MediaSpaceWatcher.kt",
                    "MemoryManagementFragment.kt",
                    "MemoryManagementModel.kt",
                    "MemoryManagementModelTest.kt",
                    "MemoryManagementScreenLaunchTest.kt",
                    "SharedStorageSpace.kt",
                    "strings.xml",
                ),
                rows.labels(),
            )
        }
    }

    @Test
    fun `splits a path into file name and directory`() {
        val nested = file("app/src/Main.kt")
        val root = file("README.md")

        assertEquals("Main.kt", nested.fileName)
        assertEquals("app/src", nested.directory)
        assertEquals("README.md", root.fileName)
        assertEquals("", root.directory)
    }
}
