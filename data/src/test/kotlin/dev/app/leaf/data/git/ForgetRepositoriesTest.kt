// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [JGit.forgetRepositoriesIn], which a clone that Leaf removes calls (fork-only). */
class ForgetRepositoriesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the repositories in the folder and below it go, the others stay`(): Unit = runBlocking {
        val clone = gitDir("clones/clone")
        val submodule = File(clone, "modules/sub").also { Git.init().setGitDir(it).setBare(true).call().close() }
        // A folder whose name starts with the clone's
        val other = gitDir("clones/clone-2")

        val cloneGit = cached(clone)
        cached(submodule)
        val otherGit = cached(other)

        jgit.forgetRepositoriesIn(File(tempDir, "clones/clone"))

        assertFalse(jgit.hasRepositoriesIn(File(tempDir, "clones/clone")))
        assertTrue(jgit.hasRepositoriesIn(File(tempDir, "clones")))
        assertSame(otherGit, cached(other))
        assertNotSame(cloneGit, cached(clone))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a folder given through a link finds the repositories in it`(): Unit = runBlocking {
        val clone = gitDir("real/clone")
        cached(clone)
        val link = File(tempDir, "link").also { it.symlinkTo(File(tempDir, "real")) }

        jgit.forgetRepositoriesIn(File(link, "clone"))

        assertFalse(jgit.hasRepositoriesIn(File(tempDir, "real")))
    }

    private fun gitDir(workTree: String): File =
        Git.init().setDirectory(File(tempDir, workTree)).call().use { it.repository.directory }

    private suspend fun cached(gitDir: File): Git = (jgit.provide(gitDir.path) { it } as Either.Ok).value
}
