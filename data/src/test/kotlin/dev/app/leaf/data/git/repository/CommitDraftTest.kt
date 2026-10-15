// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.GetPersistedCommitMessagesGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.PersistedCommitMessage
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The commit field's draft, which Leaf keeps in `LEAF_COMMIT_DRAFT`. git writes `COMMIT_EDITMSG` with every commit, from
 * a terminal or an agent, and with `git rebase --continue`, so the field must never take its draft from there.
 */
class CommitDraftTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit by lazy { testJGit() }
    private val persist by lazy { PersistCommitMessageGitAction(jgit) }
    private val read by lazy { GetPersistedCommitMessagesGitAction(jgit) }

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val gitDir by lazy { File(repository, ".git") }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a draft is saved in Leaf's own file, read back, and removed when cleared`(): Unit = runBlocking {
        // initRepository's commit wrote it
        val commitEditMsg = File(gitDir, "COMMIT_EDITMSG").readText()

        assertEquals(Either.Ok(Unit), persist(gitDir.path, "Fix the parser\n\nIt dropped the last line ✓"))

        assertEquals("Fix the parser\n\nIt dropped the last line ✓", File(gitDir, "LEAF_COMMIT_DRAFT").readText())
        assertEquals("Fix the parser\n\nIt dropped the last line ✓", messages(gitDir).commitMessage)

        assertEquals(Either.Ok(Unit), persist(gitDir.path, null))

        assertFalse(File(gitDir, "LEAF_COMMIT_DRAFT").exists())
        assertNull(messages(gitDir).commitMessage)
        // git's file is git's
        assertEquals(commitEditMsg, File(gitDir, "COMMIT_EDITMSG").readText())
    }

    @Test
    fun `a commit made in a terminal is not taken as the draft`(): Unit = runBlocking {
        File(repository, "a.txt").writeText("a\n")
        git.run(repository, "add", "a.txt")
        git.run(repository, "commit", "-m", "Add a.txt")

        assertEquals("Add a.txt\n", File(gitDir, "COMMIT_EDITMSG").readText())
        assertNull(messages(gitDir).commitMessage)

        // Nor does it replace one
        persist(gitDir.path, "My draft")
        File(repository, "b.txt").writeText("b\n")
        git.run(repository, "add", "b.txt")
        git.run(repository, "commit", "-m", "Add b.txt")

        assertEquals("My draft", messages(gitDir).commitMessage)
    }

    @Test
    fun `the message that git rebase --continue leaves is not taken as the draft`(): Unit = runBlocking {
        val file = File(repository, "README.md")
        git.run(repository, "switch", "-c", "feature")
        file.writeText("feature\n")
        git.run(repository, "commit", "-am", "Change on feature")
        git.run(repository, "switch", "main")
        file.writeText("main\n")
        git.run(repository, "commit", "-am", "Change on main")
        git.run(repository, "switch", "feature")

        git.runFailing(repository, "rebase", "main")
        file.writeText("resolved\n")
        git.run(repository, "add", "README.md")
        git.run(repository, mapOf("GIT_EDITOR" to ":"), "rebase", "--continue")

        // The commit's message, with git's comments about the rebase and its conflicts
        val commitEditMsg = File(gitDir, "COMMIT_EDITMSG").readText()
        assertTrue(commitEditMsg.startsWith("Change on feature\n"), commitEditMsg)
        assertTrue(commitEditMsg.contains("\n# Conflicts:\n"), commitEditMsg)
        assertNull(messages(gitDir).commitMessage)
    }

    @Test
    fun `a linked worktree keeps its own draft, in its own git dir`(): Unit = runBlocking {
        val agent = File(tempDir, "agent")
        git.run(repository, "worktree", "add", agent.path, "-b", "agent1")
        val agentGitDir = File(gitDir, "worktrees/agent")

        persist(gitDir.path, "Main's draft")
        persist(agentGitDir.path, "Agent's draft")

        assertEquals("Agent's draft", File(agentGitDir, "LEAF_COMMIT_DRAFT").readText())
        assertEquals("Main's draft", messages(gitDir).commitMessage)
        assertEquals("Agent's draft", messages(agentGitDir).commitMessage)
    }

    @Test
    fun `a merge in progress keeps its message in MERGE_MSG, as before`(): Unit = runBlocking {
        val file = File(repository, "README.md")
        git.run(repository, "switch", "-c", "feature")
        file.writeText("feature\n")
        git.run(repository, "commit", "-am", "Change on feature")
        git.run(repository, "switch", "main")
        file.writeText("main\n")
        git.run(repository, "commit", "-am", "Change on main")
        persist(gitDir.path, "My draft")
        git.runFailing(repository, "merge", "feature")

        persist(gitDir.path, "Merge feature, keeping main's README")

        assertEquals("Merge feature, keeping main's README", File(gitDir, "MERGE_MSG").readText())
        val messages = messages(gitDir)
        assertEquals("Merge feature, keeping main's README", messages.mergeMessage)
        // The plain draft waits for the merge to end
        assertEquals("My draft", messages.commitMessage)
    }

    private suspend fun messages(gitDir: File): PersistedCommitMessage {
        val result = read(gitDir.path)

        return (result as? Either.Ok)?.value ?: error("Reading the messages failed: $result")
    }
}
