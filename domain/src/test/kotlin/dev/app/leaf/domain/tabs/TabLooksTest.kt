// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.tabs

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** The cache in front of the store. Real repositories and their worktrees are in `RenamingTabsTest` (data). */
class TabLooksTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a repository is read once, for every tab that asks`(): Unit = runBlocking {
        val gitDir = gitDir("app")
        val store = FakeStore(mapOf(gitDir.canonicalPath to TabLook(name = "Acme app", color = 4)))
        val looks = TabLooks(store)

        assertEquals(TabLook(name = "Acme app", color = 4), looks.lookOf(gitDir.path).first())
        assertEquals(TabLook(name = "Acme app", color = 4), looks.lookOf(gitDir.path).first())
        assertEquals(1, store.loads)
    }

    @Test
    fun `a change reaches every tab of the repository, and is saved`(): Unit = runBlocking {
        val gitDir = gitDir("app")
        val store = FakeStore()
        val looks = TabLooks(store)

        assertEquals(TabLook(), looks.lookOf(gitDir.path).first())

        val result = looks.update(gitDir.path) { it.copy(name = "Acme app") }

        assertEquals(Either.Ok(TabLook(name = "Acme app")), result)
        assertEquals(TabLook(name = "Acme app"), looks.lookOf(gitDir.path).first())
        assertEquals(mapOf(gitDir.canonicalPath to TabLook(name = "Acme app")), store.saved)

        looks.update(gitDir.path) { it.copy(color = 2) }

        assertEquals(TabLook(name = "Acme app", color = 2), looks.lookOf(gitDir.path).first())
    }

    @Test
    fun `a change that changes nothing writes nothing`(): Unit = runBlocking {
        val gitDir = gitDir("app")
        val store = FakeStore()
        val looks = TabLooks(store)

        looks.update(gitDir.path) { it }

        assertEquals(0, store.saves)
    }

    @Test
    fun `a failed save changes nothing, and says which file`(): Unit = runBlocking {
        val gitDir = gitDir("app")
        val store = FakeStore(mapOf(gitDir.canonicalPath to TabLook(color = 1)), failSaves = true)
        val looks = TabLooks(store)

        val result = looks.update(gitDir.path) { it.copy(name = "Acme app") }

        val error = assertInstanceOf(GenericError::class.java, (result as Either.Err).error)
        assertTrue(store.fileOf(gitDir.canonicalPath) in error.message, error.message)
        assertTrue("Read-only file system" in error.message, error.message)
        assertEquals(TabLook(color = 1), looks.lookOf(gitDir.path).first())
    }

    @Test
    fun `a look that can't be read is none, so the tab shows its folder's name`(): Unit = runBlocking {
        val gitDir = gitDir("app")
        val looks = TabLooks(FakeStore(failLoads = true))

        assertEquals(TabLook(), looks.lookOf(gitDir.path).first())
    }

    @Test
    fun `two repositories with the same folder name keep their own`(): Unit = runBlocking {
        val one = gitDir("one/app")
        val other = gitDir("other/app")
        val looks = TabLooks(FakeStore())

        looks.update(one.path) { it.copy(name = "Acme app") }
        looks.update(other.path) { it.copy(name = "Globex app") }

        assertEquals("Acme app", looks.lookOf(one.path).first().name)
        assertEquals("Globex app", looks.lookOf(other.path).first().name)
    }

    private fun gitDir(name: String): File = File(tempDir, "$name/.git").apply { mkdirs() }

    private class FakeStore(
        initial: Map<String, TabLook> = emptyMap(),
        private val failSaves: Boolean = false,
        private val failLoads: Boolean = false,
    ) : TabLookStore {
        val saved = initial.toMutableMap()
        var loads = 0
        var saves = 0

        override fun load(repositoryGitDir: String): TabLook {
            loads++
            if (failLoads) throw IOException("Permission denied")

            return saved[repositoryGitDir] ?: TabLook()
        }

        override fun save(repositoryGitDir: String, look: TabLook) {
            saves++
            if (failSaves) throw IOException("Read-only file system")

            saved[repositoryGitDir] = look
        }

        override fun fileOf(repositoryGitDir: String): String = "$repositoryGitDir/leaf"
    }
}
