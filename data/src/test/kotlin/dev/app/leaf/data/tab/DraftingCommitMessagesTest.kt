// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.repository.PersistCommitMessageGitAction
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.GetPersistedCommitMessagesGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.PersistCommitMessageUseCase
import dev.app.leaf.domain.models.PersistedCommitMessage
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.repositories.CompletedTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

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
    private val persistCommitMessage by lazy {
        PersistCommitMessageUseCase(PersistCommitMessageGitAction(jgit, RepositoryStateMapper()))
    }

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
    fun `a cherry-pick shows git's message, keeps its edits there, and its commit leaves the draft`(): Unit =
        runBlocking {
            featureBranchChanging("README.md")
            commitInTerminal("README.md", "Change README.md on main")
            tab.openRepository(main.path)
            persistCommitMessage(gitDir.path, "Leaf's draft")

            git.runFailing(main, "cherry-pick", "feature")
            tab.refreshData(DataToRefresh.ALL).join()

            // The picked commit's message, without the conflicts that git lists after it as comments
            val messages = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.mergeMessage != null }
            assertEquals("Change README.md on feature\n", messages.mergeMessage)
            assertTrue(File(gitDir, "MERGE_MSG").readText().contains("# Conflicts:"))

            // Upstream saved nothing while cherry-picking
            persistCommitMessage(gitDir.path, "Pick the README change")
            assertEquals("Pick the README change", messagesAsReadAfterRestart().mergeMessage)
            assertEquals("Leaf's draft", messagesAsReadAfterRestart().commitMessage)

            resolveInTerminal("README.md")
            assertTrue(File(gitDir, "CHERRY_PICK_HEAD").exists())
            val commit = tab.doCommit("Pick the README change", amend = false, author = null, isMergeMessage = true)
                .await()

            assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
            assertEquals("Pick the README change", git.run(main, "log", "-1", "--format=%B").trim())
            assertFalse(File(gitDir, "CHERRY_PICK_HEAD").exists())
            assertFalse(File(gitDir, "MERGE_MSG").exists())
            assertEquals("Leaf's draft", messagesAsReadAfterRestart().commitMessage)
        }

    @Test
    fun `a revert shows git's message, keeps its edits there, and its commit leaves the draft`(): Unit = runBlocking {
        commitInTerminal("README.md", "First change")
        File(main, "README.md").writeText("second\n")
        git.run(main, "commit", "-am", "Second change")
        val reverted = git.run(main, "rev-parse", "HEAD~1").trim()
        tab.openRepository(main.path)
        persistCommitMessage(gitDir.path, "Leaf's draft")

        git.runFailing(main, "revert", "--no-edit", "HEAD~1")
        tab.refreshData(DataToRefresh.ALL).join()

        val messages = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.mergeMessage != null }
        assertEquals("Revert \"First change\"\n\nThis reverts commit $reverted.\n", messages.mergeMessage)

        persistCommitMessage(gitDir.path, "Revert the first change")
        assertEquals("Revert the first change", File(gitDir, "MERGE_MSG").readText())
        assertEquals("Leaf's draft", messagesAsReadAfterRestart().commitMessage)

        resolveInTerminal("README.md")
        assertTrue(File(gitDir, "REVERT_HEAD").exists())
        val commit = tab.doCommit("Revert the first change", amend = false, author = null, isMergeMessage = true)
            .await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals("Revert the first change", git.run(main, "log", "-1", "--format=%B").trim())
        assertFalse(File(gitDir, "REVERT_HEAD").exists())
        assertFalse(File(gitDir, "MERGE_MSG").exists())
        assertEquals("Leaf's draft", messagesAsReadAfterRestart().commitMessage)
    }

    @Test
    fun `a merge's message is shown without the conflicts that git lists as comments`(): Unit = runBlocking {
        featureBranchChanging("README.md")
        commitInTerminal("README.md", "Change README.md on main")
        tab.openRepository(main.path)

        git.runFailing(main, "merge", "feature")
        resolveInTerminal("README.md")
        tab.refreshData(DataToRefresh.ALL).join()

        val messages = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.mergeMessage != null }
        assertEquals("Merge branch 'feature'\n", messages.mergeMessage)

        // As the Merge button commits what the field shows. JGit's commit keeps a message as it is.
        val commit = tab.doCommit(messages.mergeMessage!!, amend = false, author = null, isMergeMessage = true).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals("Merge branch 'feature'\n", git.run(main, "cat-file", "commit", "HEAD").substringAfter("\n\n"))
    }

    @Test
    fun `a rebase's edit stop has a message of its own, which the amend takes, and the draft waits`(): Unit =
        runBlocking {
            rebaseToEdit()
            tab.openRepository(main.path)
            // Typed before the rebase, for another commit
            persistCommitMessage(gitDir.path, "Leaf's draft")

            // Stops at the conflicting commit, which leaves its MERGE_MSG there for the rest of the rebase (JGit)
            assertEquals(RebaseResult.Status.STOPPED, startRebase().status)
            resolveInTerminal("README.md")
            assertEquals(RebaseResult.Status.EDIT, continueRebaseWithJGit().status)
            val stop = File(gitDir, "rebase-merge/amend").readText().trim()
            assertTrue(File(gitDir, "MERGE_MSG").readText().startsWith("Change README.md on feature"))
            tab.refreshData(DataToRefresh.ALL).join()

            // The commit's own message, as git commit --amend starts from it, not the conflicted commit's MERGE_MSG
            var messages = tab.awaitLoaded(tab.repositoryData.persistedCommitMessage) { it.rebaseEditStop == stop }
            assertEquals("Edit a.txt\n", messages.rebaseEditMessage)

            persistCommitMessage(gitDir.path, "Edit a.txt, amended")

            assertEquals("Edit a.txt, amended", File(gitDir, "rebase-merge/leaf-amend-message-$stop").readText())
            assertTrue(File(gitDir, "MERGE_MSG").readText().startsWith("Change README.md on feature"))
            messages = messagesAsReadAfterRestart()
            assertEquals(stop, messages.rebaseEditStop)
            assertEquals("Edit a.txt, amended", messages.rebaseEditMessage)
            assertEquals("Leaf's draft", messages.commitMessage)

            // Continue with Amend checked, as the status pane does
            val rebaseState = tab.awaitLoaded(tab.repositoryData.rebaseInteractiveState) {
                it is RebaseInteractiveState.ProcessingCommits && it.isCurrentStepAmenable
            }
            awaitTask {
                tab.continueRebase(
                    message = "Edit a.txt, amended",
                    isAmendRebaseInteractive = true,
                    repositoryState = RepositoryState.REBASING_INTERACTIVE,
                    rebaseInteractiveState = rebaseState,
                    onIdentityRequest = { null },
                )
            }

            // The next stop, to edit b.txt's commit, starts from that commit's message
            val nextStop = File(gitDir, "rebase-merge/amend").readText().trim()
            assertNotEquals(stop, nextStop)
            assertEquals("Edit a.txt, amended", git.run(main, "log", "-1", "--format=%B", "HEAD~1").trim())
            assertFalse(File(gitDir, "rebase-merge/leaf-amend-message-$stop").exists())
            messages = messagesAsReadAfterRestart()
            assertEquals(nextStop, messages.rebaseEditStop)
            assertEquals("Edit b.txt\n", messages.rebaseEditMessage)

            // A message typed at this stop, and left behind by a Continue elsewhere, isn't the next one's
            persistCommitMessage(gitDir.path, "Typed, then continued in a terminal")
            assertEquals(RebaseResult.Status.EDIT, continueRebaseWithJGit().status)

            assertTrue(File(gitDir, "rebase-merge/leaf-amend-message-$nextStop").exists())
            messages = messagesAsReadAfterRestart()
            assertNotEquals(nextStop, messages.rebaseEditStop)
            assertEquals("Edit c.txt\n", messages.rebaseEditMessage)

            // The rebase's folder goes, and the message with it
            assertEquals(RebaseResult.Status.OK, continueRebaseWithJGit().status)

            assertFalse(File(gitDir, "rebase-merge").exists())
            messages = messagesAsReadAfterRestart()
            assertNull(messages.rebaseEditStop)
            assertNull(messages.rebaseEditMessage)
            assertEquals("Leaf's draft", messages.commitMessage)
        }

    @Test
    fun `outside an edit stop, a rebase saves no message`(): Unit = runBlocking {
        rebaseToEdit()
        tab.openRepository(main.path)
        persistCommitMessage(gitDir.path, "Leaf's draft")
        assertEquals(RebaseResult.Status.STOPPED, startRebase().status)

        persistCommitMessage(gitDir.path, "Typed while the field can't be edited")

        assertEquals(emptyList<String>(), File(gitDir, "rebase-merge").list()!!.filter { it.startsWith("leaf-") })
        val messages = messagesAsReadAfterRestart()
        assertNull(messages.rebaseEditStop)
        assertNull(messages.rebaseEditMessage)
        assertEquals("Leaf's draft", messages.commitMessage)
        assertTrue(File(gitDir, "MERGE_MSG").readText().startsWith("Change README.md on feature"))
    }

    /** The draft as a tab opened afterwards reads it, after a restart or a worktree switch. */
    private suspend fun draftAsReadAfterRestart(): String? = messagesAsReadAfterRestart().commitMessage

    /** The messages as a tab opened afterwards reads them. */
    private suspend fun messagesAsReadAfterRestart(): PersistedCommitMessage {
        val messages = GetPersistedCommitMessagesGitAction(jgit)(gitDir.path)

        return (messages as Either.Ok).value
    }

    /**
     * A branch `feature` with four commits for an interactive rebase onto main: one whose change conflicts with
     * main's, then three to edit.
     */
    private fun rebaseToEdit() {
        featureBranchChanging("README.md")
        git.run(main, "switch", "feature")
        commitInTerminal("a.txt", "Edit a.txt")
        commitInTerminal("b.txt", "Edit b.txt")
        commitInTerminal("c.txt", "Edit c.txt")
        git.run(main, "switch", "main")
        commitInTerminal("README.md", "Change README.md on main")
        git.run(main, "switch", "feature")
    }

    /** Starts Leaf's interactive rebase of feature onto main (JGit's), marking every commit but the first to edit. */
    private fun startRebase(): RebaseResult = Git.open(main).use { jGit ->
        jGit.rebase().setUpstream("main").runInteractively(object : RebaseCommand.InteractiveHandler {
            override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {
                steps.drop(1).forEach { it.action = RebaseTodoLine.Action.EDIT }
            }

            override fun modifyCommitMessage(message: String) = message
        }).call()
    }

    private fun continueRebaseWithJGit(): RebaseResult = Git.open(main).use { jGit ->
        jGit.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
    }

    /** Starts a task that can't be joined, and waits until it ends. */
    private suspend fun awaitTask(start: () -> Unit) {
        val before = tab.repositoryState.completedTasks.value.size

        start()

        val task = withTimeout(10.seconds) { tab.repositoryState.completedTasks.first { it.size > before } }.last()
        assertInstanceOf(CompletedTask.Success::class.java, task, task.toString())
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
