// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CommitsOnlyOnRefTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `counts the commits after the fork point`() {
        val repository = createRepositoryWithFeature(commits = 3)
        git.run(repository, "commit", "--allow-empty", "-m", "Main moves on")

        assertEquals(3, count(repository, "refs/heads/feature"))
    }

    @Test
    fun `counts nothing for a ref that doesn't exist`() {
        val repository = git.initRepository(File(tempDir, "repo"))

        assertEquals(0, count(repository, "refs/heads/missing"))
    }

    @Test
    fun `an annotated tag reaches its commit`() {
        val repository = createRepositoryWithFeature(commits = 2)
        git.run(repository, "tag", "-a", "v1", "feature~1", "-m", "Release")

        assertEquals(1, count(repository, "refs/heads/feature"))
    }

    @Test
    fun `the stash reaches the commit it was made on`() {
        val repository = createRepositoryWithFeature(commits = 2)
        git.run(repository, "switch", "feature")
        File(repository, "README.md").appendText("Change\n")
        git.run(repository, "stash")
        git.run(repository, "switch", "main")

        assertEquals(0, count(repository, "refs/heads/feature"))
    }

    @Test
    fun `a symbolic ref to the ref reaches nothing`() {
        val repository = createRepositoryWithFeature(commits = 2)
        git.run(repository, "symbolic-ref", "refs/heads/alias", "refs/heads/feature")

        assertEquals(2, count(repository, "refs/heads/feature"))
    }

    private fun createRepositoryWithFeature(commits: Int): File {
        val repository = git.initRepository(File(tempDir, "repo"))
        git.run(repository, "switch", "-c", "feature")

        repeat(commits) { index ->
            git.run(repository, "commit", "--allow-empty", "-m", "Feature ${index + 1}")
        }

        git.run(repository, "switch", "main")

        return repository
    }

    private fun count(repository: File, refName: String) =
        Git.open(repository).use { it.repository.countCommitsOnlyOn(refName) }
}
