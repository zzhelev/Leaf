// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Copying a version of a conflicted file out, for "Open in the default app" on a choice card. */
class WriteConflictVersionGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val write = WriteConflictVersionGitAction(testJGit())
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val folder by lazy { File(tempDir, "tmp") }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a version is written under its id and the file's name, which keeps its extension`(): Unit = runBlocking {
        git.mergeContents(repository, "assets/logo.png" to { "\u0000png $it" })
        val theirs = git.run(repository, "rev-parse", ":3:assets/logo.png").trim()

        val written = written(theirs, "assets/logo.png")

        assertEquals("${theirs.take(20)}_logo.png", written.name)
        assertEquals("\u0000png feature", written.readText())

        // Again, over the copy that an app may have open
        assertEquals("\u0000png feature", written(theirs, "assets/logo.png").readText())
        assertEquals(listOf(written.name), folder.list()?.toList())
    }

    @Test
    fun `a Git LFS pointer is written as its object once it's downloaded, and as the pointer before`(): Unit =
        runBlocking {
            val oid = "a".repeat(64)
            val pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:$oid\nsize 4\n"
            git.mergeContents(repository, "photo.png" to { side -> if (side == "main") pointer else "$side\n" })
            val ours = git.run(repository, "rev-parse", ":2:photo.png").trim()

            assertEquals(pointer, written(ours, "photo.png").readText())

            File(repository, ".git/lfs/objects/aa/aa/$oid").apply { parentFile.mkdirs() }.writeText("main")

            assertEquals("main", written(ours, "photo.png").readText())
        }

    private suspend fun written(objectId: String, path: String): File {
        val result = write(File(repository, ".git").path, objectId, path, folder.path)

        return File(assertInstanceOf(Either.Ok::class.java, result).value as String)
    }
}
