// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What the list that picks a comparison's side makes of typed text, against `git rev-parse <text>^{commit}`. */
class ResolveCommitGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val action = ResolveCommitGitAction(testJGit(), JGitCommitMapper(JGitIdentityMapper()))
    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir by lazy { File(repo, ".git").path }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        repo.mkdirs()
        git.run(repo, "init", "-q")

        for (n in 1..3) {
            File(repo, "file.txt").writeText("$n\n")
            git.run(repo, "add", "-A")
            git.run(repo, "commit", "-q", "-m", "Commit $n")
        }

        git.run(repo, "tag", "-a", "v1", "-m", "Release", "HEAD~1")
    }

    @AfterEach
    fun restoreReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `hashes, parts of them, refs and revisions resolve as git resolves them`(): Unit = runBlocking {
        val head = git.run(repo, "rev-parse", "HEAD").trim()

        for (revision in listOf("HEAD", "HEAD~2", "main^", head, head.take(7), "v1", "refs/heads/main")) {
            val expected = git.run(repo, "rev-parse", "--verify", "-q", "$revision^{commit}").trim()
            val commit = (action(gitDir, revision) as Either.Ok).value

            assertEquals(expected, commit!!.hash, revision)
        }

        assertEquals("Commit 2", (action(gitDir, "v1") as Either.Ok).value!!.message.trim())
    }

    @Test
    fun `what names no commit is nothing, not an error`(): Unit = runBlocking {
        val tree = git.run(repo, "rev-parse", "HEAD^{tree}").trim()

        for (text in listOf("nope", "v1.", "HEAD~9", "a..b", "HEAD:file.txt", tree)) {
            assertNull((action(gitDir, text) as Either.Ok).value, text)
        }
    }
}
