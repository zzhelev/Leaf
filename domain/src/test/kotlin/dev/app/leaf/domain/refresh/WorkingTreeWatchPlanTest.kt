// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class WorkingTreeWatchPlanTest {
    @TempDir
    lateinit var tempDir: File

    private val root get() = File(tempDir, "root")

    private fun folder(vararg parts: String) = parts.fold(root) { file, part -> File(file, part) }.apply { mkdirs() }

    // The plan as the path of a folder watched alone, and the path and "/**" for one watched with all that is under it
    private fun plan(isLeftOut: (File) -> Boolean = { false }): List<String> =
        workingTreeWatchPlan(root, isLeftOut).map {
            it.folder.relativeTo(root).path.ifEmpty { "." } + if (it.isRecursive) "/**" else ""
        }

    @Test
    fun `a working tree without anything to leave out is one registration`() {
        folder("src", "main", "kotlin")
        folder("src", "test")
        folder("docs")

        assertEquals(listOf("./**"), plan())
    }

    @Test
    fun `a folder with an ignored folder is watched alone, and the folders next to it as wholes`() {
        folder("src", "main")
        folder("src", "test")
        folder("ui", "src", "app")
        folder("ui", "node_modules", "left-pad")
        folder("docs")

        val plan = plan { it.relativeTo(root).path == "ui/node_modules" }

        assertEquals(listOf(".", "docs/**", "src/**", "ui", "ui/src/**"), plan)
    }

    @Test
    fun `an ignored folder deep in the tree keeps its ancestors from being watched as wholes`() {
        folder("a", "b", "c", "keep")
        folder("a", "b", "c", "build")
        folder("a", "b", "other")
        folder("z")

        val plan = plan { it.name == "build" }

        assertEquals(listOf(".", "a", "a/b", "a/b/c", "a/b/c/keep/**", "a/b/other/**", "z/**"), plan)
    }

    @Test
    fun `a left out folder is never in the plan`() {
        folder(".git", "refs")
        folder("src")

        val plan = plan { it.name == ".git" }

        assertEquals(listOf(".", "src/**"), plan)
    }

    @Test
    fun `hundreds of folders need only a few registrations`() {
        for (package1 in 0 until 20) {
            for (package2 in 0 until 20) {
                folder("application", "src", "main", "p$package1", "q$package2")
            }
        }
        folder("application", "target", "classes")
        folder("ui", "node_modules", "x")
        folder("ui", "src", "app")

        val plan = plan { it.name == "target" || it.name == "node_modules" }

        assertEquals(listOf(".", "application", "application/src/**", "ui", "ui/src/**"), plan)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `links to folders aren't followed`() {
        folder("src")
        val outside = File(tempDir, "outside").apply { mkdirs() }
        // A loop: following it would never end
        Files.createSymbolicLink(File(root, "src/loop").toPath(), root.toPath())
        Files.createSymbolicLink(File(root, "other").toPath(), outside.toPath())

        assertEquals(listOf("./**"), plan())
    }
}
