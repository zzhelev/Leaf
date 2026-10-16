// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.repository.PersistCommitMessageGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.GetPersistedCommitMessagesGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.PersistCommitMessageUseCase
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The commit field's draft in a tab, while an agent commits in a terminal next to Leaf. The field fills from the draft
 * when it's blank, so the draft must be only what was typed in Leaf: it used to be git's COMMIT_EDITMSG, and a blank
 * field took the agent's last message, which the user's next commit then used.
 */
class DraftingCommitMessagesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val tab by lazy {
        TestTab(
            jgit,
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }
    private val persistCommitMessage by lazy { PersistCommitMessageUseCase(PersistCommitMessageGitAction(jgit)) }

    private val main by lazy { File(tempDir, "main") }
    private val gitDir by lazy { File(main, ".git") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        // The tab's git commits as this identity: without one, git makes one up on macOS, and refuses on Linux
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        git.initRepository(main)
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the draft is what was typed in Leaf, whatever git commits in a terminal`(): Unit = runBlocking {
        tab.openRepository(main.path)

        // The repository's first commit left its message in COMMIT_EDITMSG
        assertNull(tab.awaitLoaded(tab.repositoryData.persistedCommitMessage).commitMessage)

        persistCommitMessage(gitDir.path, "Leaf's draft")
        commitInTerminal("a.txt", "An agent's commit")
        // As the file watcher does after a commit. Nothing else refreshes, so this refresh is the next one.
        tab.refreshData(DataToRefresh.STATUS).join()

        assertEquals("Leaf's draft", tab.awaitLoaded(tab.repositoryData.persistedCommitMessage).commitMessage)

        // Leaf's commit of the draft ends it
        File(main, "b.txt").writeText("b\n")
        git.run(main, "add", "b.txt")
        val commit = tab.doCommit("Leaf's draft", amend = false, author = null, isMergeMessage = false).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.commitMessage == null }
        assertFalse(File(gitDir, "LEAF_COMMIT_DRAFT").exists())

        commitInTerminal("c.txt", "Another agent's commit")
        tab.refreshData(DataToRefresh.STATUS).join()

        assertNull(tab.awaitLoaded(tab.repositoryData.persistedCommitMessage).commitMessage)
    }

    @Test
    fun `a merge's commit leaves the draft, which the merge kept out of view`(): Unit = runBlocking {
        featureBranchChanging("a.txt")
        tab.openRepository(main.path)
        persistCommitMessage(gitDir.path, "Leaf's draft")

        // As "Merge into main…" does. The field shows MERGE_MSG until the commit.
        git.run(main, "merge", "--no-ff", "--no-commit", "feature")
        val commit = tab.doCommit("Merge branch 'feature'", amend = false, author = null, isMergeMessage = true).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals(2, git.run(main, "log", "-1", "--format=%P").trim().split(" ").size, "the merge's commit")
        assertFalse(File(gitDir, "MERGE_MSG").exists())
        assertEquals("Leaf's draft", draftAsReadAfterRestart())
    }

    @Test
    fun `a squash merge's commit leaves the draft too`(): Unit = runBlocking {
        featureBranchChanging("a.txt")
        tab.openRepository(main.path)
        persistCommitMessage(gitDir.path, "Leaf's draft")

        // As "Squash-merge into main…" does. git records no state for it, so the repository is SAFE all along.
        git.run(main, "merge", "--squash", "feature")
        val commit = tab.doCommit("Squashed feature", amend = false, author = null, isMergeMessage = true).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertFalse(File(gitDir, "SQUASH_MSG").exists())
        assertEquals("Leaf's draft", draftAsReadAfterRestart())
    }

    @Test
    fun `a cherry-pick's commit takes the draft, which the field shows meanwhile`(): Unit = runBlocking {
        featureBranchChanging("README.md")
        commitInTerminal("README.md", "Change README.md on main")
        tab.openRepository(main.path)
        persistCommitMessage(gitDir.path, "Leaf's draft")

        git.runFailing(main, "cherry-pick", "feature")
        resolveInTerminal("README.md")
        assertTrue(File(gitDir, "CHERRY_PICK_HEAD").exists())
        val commit = tab.doCommit("Leaf's draft", amend = false, author = null, isMergeMessage = false).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertFalse(File(gitDir, "CHERRY_PICK_HEAD").exists())
        assertNull(draftAsReadAfterRestart())
    }

    @Test
    fun `a revert's commit takes the draft, which the field shows meanwhile`(): Unit = runBlocking {
        commitInTerminal("README.md", "First change")
        File(main, "README.md").writeText("second\n")
        git.run(main, "commit", "-am", "Second change")
        tab.openRepository(main.path)
        persistCommitMessage(gitDir.path, "Leaf's draft")

        git.runFailing(main, "revert", "--no-edit", "HEAD~1")
        resolveInTerminal("README.md")
        assertTrue(File(gitDir, "REVERT_HEAD").exists())
        val commit = tab.doCommit("Leaf's draft", amend = false, author = null, isMergeMessage = false).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertFalse(File(gitDir, "REVERT_HEAD").exists())
        assertNull(draftAsReadAfterRestart())
    }

    /** The draft as a tab opened afterwards reads it, after a restart or a worktree switch. */
    private suspend fun draftAsReadAfterRestart(): String? {
        val messages = GetPersistedCommitMessagesGitAction(jgit)(gitDir.path)

        return (messages as Either.Ok).value.commitMessage
    }

    /** Commits a change to [fileName] on a new branch `feature`, and switches back. */
    private fun featureBranchChanging(fileName: String) {
        git.run(main, "switch", "-c", "feature")
        File(main, fileName).writeText("feature\n")
        git.run(main, "add", fileName)
        git.run(main, "commit", "-m", "Change $fileName on feature")
        git.run(main, "switch", "-")
    }

    private fun resolveInTerminal(fileName: String) {
        File(main, fileName).writeText("resolved\n")
        git.run(main, "add", fileName)
    }

    private fun commitInTerminal(fileName: String, message: String) {
        File(main, fileName).writeText("$fileName\n")
        git.run(main, "add", fileName)
        git.run(main, "commit", "-m", message)

        assertEquals("$message\n", File(gitDir, "COMMIT_EDITMSG").readText())
    }
}
