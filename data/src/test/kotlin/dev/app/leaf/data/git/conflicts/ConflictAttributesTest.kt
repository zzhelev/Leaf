// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CoreConfig.EolStreamType
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The attributes that tell how to merge a conflicted file and how to write it. */
class ConflictAttributesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `without attributes, markers are 7 long, the file merges as text and is written as it is`() {
        File(repository, "notes.txt").writeText("notes\n")

        assertEquals(
            ConflictFileAttributes(markerSize = 7, mergesAsBinary = false, EolStreamType.DIRECT),
            attributes("notes.txt"),
        )
    }

    @Test
    fun `conflict-marker-size sets the markers' length, and a value that isn't a positive number is ignored`() {
        File(repository, ".gitattributes").writeText("*.md conflict-marker-size=32\n*.txt conflict-marker-size=0\n")
        files("notes.txt")

        assertEquals(32, attributes("README.md").markerSize)
        assertEquals(7, attributes("notes.txt").markerSize)
    }

    @Test
    fun `binary, -merge and merge=binary merge as binary`() {
        File(repository, ".gitattributes")
            .writeText("*.png binary\n*.raw -merge\n*.dat merge=binary\n*.txt merge=union\n")
        files("image.png", "dump.raw", "table.dat", "notes.txt")

        assertEquals(true, attributes("image.png").mergesAsBinary)
        assertEquals(true, attributes("dump.raw").mergesAsBinary)
        assertEquals(true, attributes("table.dat").mergesAsBinary)
        assertEquals(false, attributes("notes.txt").mergesAsBinary)
    }

    @Test
    fun `line endings are converted as a checkout converts them`() {
        File(repository, ".gitattributes").writeText("*.bat text eol=crlf\n")
        git.run(repository, "config", "core.autocrlf", "true")
        files("run.bat")

        assertEquals(EolStreamType.TEXT_CRLF, attributes("run.bat").checkoutLineEndings)
        assertEquals(EolStreamType.AUTO_CRLF, attributes("README.md").checkoutLineEndings)
    }

    @Test
    fun `info attributes apply, and a file that the working tree doesn't have is found in the index`() {
        File(repository, ".git/info/attributes")
            .apply { parentFile.mkdirs() }
            .writeText("*.md conflict-marker-size=12\n")
        File(repository, "README.md").delete()

        assertEquals(12, attributes("README.md").markerSize)
    }

    private fun files(vararg paths: String) {
        for (path in paths) {
            File(repository, path).writeText("content\n")
        }
    }

    private fun attributes(path: String) = Git.open(repository).use { conflictFileAttributes(it.repository, path) }
}
