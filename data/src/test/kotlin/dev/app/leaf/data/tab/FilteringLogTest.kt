// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.RenameBranchGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import dev.app.leaf.domain.models.FoundCommits
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The log under a branch filter (fork-only, upstream issue 207), in real repositories: `git rev-list` over the refs that
 * the filter shows is the oracle for what the log has. A repository of `main`, an agent's branch, a spike, a tag, and an
 * `origin` that has `main` one commit ahead and a branch of its own.
 */
@DisabledOnOs(OS.WINDOWS)
class FilteringLogTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val gitCli by lazy {
        testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1"))
    }
    private val tabs = mutableListOf<TestTab>()
    private val tab by lazy { newTab() }

    private val main by lazy { File(tempDir, "main") }

    private val agent = "refs/heads/claude/parser-cache"
    private val spike = "refs/heads/wip/spike"
    private val originMain = "refs/remotes/origin/main"
    private val originBlame = "refs/remotes/origin/feat/blame"

    @BeforeEach
    fun isolateGit() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n[init]\n\tdefaultBranch = main\n")
    }

    @AfterEach
    fun tearDown() {
        tabs.forEach { it.close() }
        SystemReader.setInstance(originalReader)
    }

    private fun newTab() = TestTab(jgit, gitCli).also { tabs += it }

    private fun createHistory() {
        git.initRepository(main)
        commit(main, "Main second")
        git.run(main, "tag", "v1")

        git.run(main, "switch", "-c", "claude/parser-cache")
        commit(main, "Agent cache one")
        commit(main, "Agent cache two")

        git.run(main, "switch", "-c", "wip/spike", "main~1")
        commit(main, "Spike try")

        git.run(main, "switch", "-c", "feat/blame", "main")
        commit(main, "Blame age column")
        git.run(main, "remote", "add", "origin", File(tempDir, "nowhere").path)
        git.run(main, "update-ref", originBlame, "HEAD")
        git.run(main, "switch", "main")
        git.run(main, "branch", "-D", "feat/blame")

        val ahead = git.run(main, "commit-tree", "-p", "main", "-m", "Remote main ahead", "main^{tree}").trim()
        git.run(main, "update-ref", originMain, ahead)
    }

    private fun commit(directory: File, message: String) {
        git.run(directory, "commit", "--allow-empty", "-m", message)
    }

    private fun hashOf(revision: String, directory: File = main) = git.run(directory, "rev-parse", revision).trim()

    /** What `git rev-list` gives for [revisions]: the oracle for the log. */
    private fun revList(vararg revisions: String, directory: File = main): Set<String> =
        git.run(directory, "rev-list", *revisions).lines().filter { it.isNotBlank() }.toSet()

    private suspend fun open(tab: TestTab = this.tab, directory: File = main) {
        tab.openRepository(directory.path)
        tab.awaitLoaded(tab.repositoryData.log)
    }

    /** Sets [filter] in [tab] and gives the hashes of the log that it walks. */
    private suspend fun filtered(filter: LogBranchFilter, tab: TestTab = this.tab): Set<String> {
        val result = tab.setLogBranchFilter(filter)
        assertTrue(result is Either.Ok, "The filter wasn't set: $result")

        val log = tab.awaitLoaded(tab.repositoryData.log) { it.branchFilter == filter }

        return log.keys.toSet()
    }

    private suspend fun search(term: String): FoundCommits {
        val result = tab.searchLog(term)
        assertTrue(result is Either.Ok, "The search failed: $result")

        return (result as Either.Ok).value
    }

    @Test
    fun `only the chosen branches are in the log, with the checked out one`(): Unit = runBlocking {
        createHistory()
        open()

        assertEquals(revList("main", agent), filtered(LogBranchFilter.Only(setOf(agent))))
        assertEquals(revList("main", originBlame), filtered(LogBranchFilter.Only(setOf(originBlame))))
        assertEquals(revList("main"), filtered(LogBranchFilter.Only(emptySet())))
    }

    @Test
    fun `current is the checked out branch and the remote branches of its name`(): Unit = runBlocking {
        createHistory()
        open()

        assertEquals(revList("main", originMain), filtered(LogBranchFilter.Current))
    }

    @Test
    fun `all but some leaves their commits out, and tags still start the walk`(): Unit = runBlocking {
        createHistory()
        open()

        val log = filtered(LogBranchFilter.All(setOf(agent, spike)))

        assertEquals(revList("main", originMain, originBlame, "v1"), log)
        assertFalse(hashOf("wip/spike") in log)
    }

    @Test
    fun `no filter is the whole log again`(): Unit = runBlocking {
        createHistory()
        open()
        filtered(LogBranchFilter.Only(emptySet()))

        assertEquals(revList("--branches", "--remotes", "--tags"), filtered(NO_LOG_BRANCH_FILTER))
    }

    @Test
    fun `a stash shows when it was made on a shown branch, and not one made on a hidden branch`(): Unit = runBlocking {
        createHistory()
        File(main, "README.md").appendText("on main\n")
        git.run(main, "stash", "push", "-m", "on main")
        val stashOnMain = hashOf("stash@{0}")

        git.run(main, "switch", "wip/spike")
        File(main, "README.md").appendText("on the spike\n")
        git.run(main, "stash", "push", "-m", "on the spike")
        val stashOnSpike = hashOf("stash@{0}")
        git.run(main, "switch", "main")
        open()

        val onlyMain = filtered(LogBranchFilter.Only(emptySet()))

        assertTrue(stashOnMain in onlyMain)
        assertFalse(stashOnSpike in onlyMain)
        assertFalse(hashOf("wip/spike") in onlyMain, "The stash would have brought the spike's commit")
        assertEquals(revList("main") + stashOnMain, onlyMain)

        val withSpike = filtered(LogBranchFilter.Only(setOf(spike)))

        assertTrue(stashOnSpike in withSpike)
        assertTrue(stashOnMain in withSpike)
    }

    @Test
    fun `a stash whose message names no branch shows when the commit it was made on is shown`(): Unit = runBlocking {
        createHistory()

        // As JGit stores a typed message, with no branch in it
        fun typedStash(message: String): String {
            File(main, "README.md").appendText("$message\n")
            val stash = git.run(main, "stash", "create").trim()
            git.run(main, "stash", "store", "-m", message, stash)
            git.run(main, "checkout", "--", "README.md")

            return stash
        }

        val onMain = typedStash("typed on main")
        git.run(main, "switch", "wip/spike")
        val onSpike = typedStash("typed on the spike")
        git.run(main, "switch", "main")
        open()

        val onlyMain = filtered(LogBranchFilter.Only(emptySet()))

        assertTrue(onMain in onlyMain)
        assertFalse(onSpike in onlyMain)
        assertTrue(onSpike in filtered(LogBranchFilter.Only(setOf(spike))))
    }

    @Test
    fun `another worktree's rebase shows while it rebases a shown branch, a lone detached HEAD doesn't`(): Unit =
        runBlocking {
            createHistory()
            val rebasing = File(tempDir, "rebasing")
            git.run(main, "worktree", "add", rebasing.path, "claude/parser-cache")
            // Replays the agent's commits onto the first commit, and stops after the first one, which no ref has
            git.run(
                rebasing,
                mapOf("GIT_SEQUENCE_EDITOR" to "sed -i.bak -e 1s/^pick/edit/"),
                "rebase", "-i", "--onto", "main~1", "main",
            )
            val replayed = hashOf("HEAD", rebasing)
            assertEquals("", git.run(main, "for-each-ref", "--contains", replayed))

            val lone = File(tempDir, "lone")
            git.run(main, "worktree", "add", "--detach", lone.path, "main")
            commit(lone, "Lone work")
            val loneWork = hashOf("HEAD", lone)
            open()

            val withAgent = filtered(LogBranchFilter.Only(setOf(agent)))
            assertTrue(replayed in withAgent)
            assertFalse(loneWork in withAgent)

            val onlyMain = filtered(LogBranchFilter.Only(emptySet()))
            assertFalse(replayed in onlyMain)
            assertFalse(loneWork in onlyMain)

            val allButSpike = filtered(LogBranchFilter.All(setOf(spike)))
            assertTrue(replayed in allButSpike)
            assertTrue(loneWork in allButSpike)
        }

    @Test
    fun `the search finds only what the filter shows`(): Unit = runBlocking {
        createHistory()
        open()

        assertEquals(listOf(hashOf("wip/spike")), search("spike try").hits.map { it.hash })

        filtered(LogBranchFilter.Only(setOf(agent)))

        assertEquals(emptyList<String>(), search("spike try").hits.map { it.hash })
        assertEquals(
            listOf(hashOf("claude/parser-cache"), hashOf("claude/parser-cache~1")),
            search("agent cache").hits.map { it.hash },
        )
    }

    @Test
    fun `the filter is the repository's, another tab reads it, and a linked worktree's current is its own branch`(): Unit =
        runBlocking {
            createHistory()
            open()
            filtered(LogBranchFilter.Current)

            assertTrue(File(main, ".git/leaf").readText().contains("branches = current"))

            val linked = File(tempDir, "agent-worktree")
            git.run(main, "worktree", "add", linked.path, "claude/parser-cache")
            val linkedTab = newTab()
            open(linkedTab, linked)

            val log = linkedTab.awaitLoaded(linkedTab.repositoryData.log)

            assertEquals(LogBranchFilter.Current, log.branchFilter)
            assertEquals(revList("claude/parser-cache", directory = linked), log.keys.toSet())
        }

    @Test
    fun `renaming a branch renames it in the filter`(): Unit = runBlocking {
        createHistory()
        open()
        filtered(LogBranchFilter.Only(setOf(spike)))

        val renamed = RenameBranchGitAction(JGitBranchMapper(), jgit)(File(main, ".git").path, spike, "wip/spike2")
        assertTrue(renamed is Either.Ok, "The branch wasn't renamed: $renamed")

        val reopened = newTab()
        open(reopened)

        assertEquals(
            LogBranchFilter.Only(setOf("refs/heads/wip/spike2")),
            reopened.awaitLoaded(reopened.repositoryData.log).branchFilter,
        )
    }
}
