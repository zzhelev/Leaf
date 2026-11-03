// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.GraphRevWalker
import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetCommitsReachableGitAction
import dev.app.leaf.domain.interfaces.IGetCurrentBranchGitAction
import dev.app.leaf.domain.interfaces.IGetLogBranchFilterGitAction
import dev.app.leaf.domain.interfaces.IGetOtherWorktreeHeadsGitAction
import dev.app.leaf.domain.interfaces.IGetRemoteBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetRemotesGitAction
import dev.app.leaf.domain.interfaces.IGetStashListGitAction
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.interfaces.IGetTagsGitAction
import dev.app.leaf.domain.interfaces.OtherWorktreeHead
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.usecases.GetLogUseCase
import dev.app.leaf.domain.usecases.GetRemotesUseCase
import dev.app.leaf.domain.usecases.LogStart
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.RepositorySelectionState
import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * The commits that the log's walk starts from under a branch filter (fork-only, upstream issue 207), with every git
 * action a stand-in. The real repositories are in `FilteringLogTest`.
 */
class FilteredLogStartTest {
    private fun hash(c: Char) = c.toString().repeat(40)

    private val main = Branch(hash('1'), "refs/heads/main", true)
    private val agent = Branch(hash('2'), "refs/heads/claude/parser-cache", true)
    private val spike = Branch(hash('3'), "refs/heads/wip/spike", true)
    private val originMain = Branch(hash('4'), "refs/remotes/origin/main", false)
    private val originBlame = Branch(hash('5'), "refs/remotes/origin/feat/blame", false)
    private val tag = Tag(hash = hash('6'), name = "refs/tags/v1", commitHash = hash('6'))

    // Stashes whose messages name no branch, as a typed message in JGit, so the commit they were made on decides
    private val stashOnMain = stash(hash('7'), base = main.hash)
    private val stashOnSpike = stash(hash('8'), base = spike.hash)

    private val rebasingAgent = OtherWorktreeHead(hash('a'), branch = agent.name)
    private val detachedAlone = OtherWorktreeHead(hash('b'))

    private fun stash(hash: String, base: String, message: String = "wip") =
        Commit(hash, message, Identity(null, null), Identity(null, null), 0, listOf(base, hash('9')))

    private class RecordingWalker : GraphRevWalker {
        val started = mutableListOf<String>()

        override suspend fun prepare(repository: String, startingCommits: List<String>): Either<Unit, Nothing> {
            started.addAll(startingCommits)

            return Either.Ok(Unit)
        }

        override fun iterator(): Iterator<Commit> = emptyList<Commit>().iterator()
        override fun close() = Unit
    }

    /** The stand-in for the reachability walk: the stash bases that it is asked about, and the history it answers from. */
    private class Reachable(val history: Set<String>) {
        val asked = mutableListOf<Pair<List<String>, List<String>>>()
    }

    private inner class Setup(
        val savedFilter: LogBranchFilter,
        val currentBranch: Branch? = main,
        reachable: Set<String> = setOf(main.hash),
        detachedHead: Branch? = null,
        val stashes: List<Commit> = listOf(stashOnMain, stashOnSpike),
    ) {
        val walker = RecordingWalker()
        val reachable = Reachable(reachable)
        private val tab = TabCoroutineScope()
        private val executor = UseCaseExecutor(
            InMemoryRepositoryDataRepository().apply {
                setRepositorySelectionState(RepositorySelectionState.Open("/repo/.git"))
            },
            InMemoryRepositoryStateRepository(),
            Provider { error("No refresh here") },
            tab,
        )

        val getLog = GetLogUseCase(
            getBranchesGitAction = mockk<IGetBranchesGitAction> {
                // JGit lists a detached HEAD first, as a branch named HEAD
                coEvery { this@mockk(any<String>()) } returns Either.Ok(listOfNotNull(detachedHead, main, agent, spike))
            },
            getTagsGitAction = mockk<IGetTagsGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(listOf(tag)) },
            getRemotesUseCase = GetRemotesUseCase(
                mockk<IGetRemotesGitAction> {
                    coEvery { this@mockk(any()) } returns Either.Ok(listOf(Remote("origin", "", "")))
                },
                mockk<IGetRemoteBranchesGitAction> {
                    coEvery { this@mockk(any()) } returns Either.Ok(listOf(originMain, originBlame))
                },
                executor,
            ),
            getStashListGitAction = mockk<IGetStashListGitAction> {
                coEvery { this@mockk(any()) } returns Either.Ok(stashes)
            },
            getOtherWorktreeHeadsGitAction = mockk<IGetOtherWorktreeHeadsGitAction> {
                coEvery { withBranches(any()) } returns Either.Ok(listOf(rebasingAgent, detachedAlone))
                coEvery { this@mockk(any()) } returns Either.Ok(listOf(rebasingAgent.commit, detachedAlone.commit))
            },
            getStatusGitAction = mockk<IGetStatusGitAction> { coEvery { this@mockk(any(), any()) } returns Either.Ok(Status()) },
            getCurrentBranchAction = mockk<IGetCurrentBranchGitAction> {
                coEvery { this@mockk(any<String>()) } returns Either.Ok(currentBranch)
            },
            getLogBranchFilterGitAction = mockk<IGetLogBranchFilterGitAction> {
                coEvery { this@mockk(any()) } returns Either.Ok(savedFilter)
            },
            getCommitsReachableGitAction = object : IGetCommitsReachableGitAction {
                override suspend fun invoke(repositoryPath: String, startingCommits: List<String>, commits: List<String>) =
                    Either.Ok(commits.filterTo(HashSet()) { it in this@Setup.reachable.history })
                        .also { this@Setup.reachable.asked += startingCommits to commits }
            },
            graphLogGenerator = GraphLogGenerator(Provider { walker }),
            repositoryDataRepository = InMemoryRepositoryDataRepository(),
        )

        fun load(pagination: Pagination = Pagination.None): GraphCommits = try {
            val result = runBlocking { getLog("/repo/.git", pagination) }

            assertTrue(result is Either.Ok, "The log didn't load: $result")
            (result as Either.Ok).value
        } finally {
            tab.coroutineContext.cancel()
        }

        fun start(): LogStart = try {
            runBlocking { getLog.logStart("/repo/.git") }
        } finally {
            tab.coroutineContext.cancel()
        }
    }

    @Test
    fun `without a filter, the walk starts from every ref, stash and detached HEAD, and asks nothing more`() {
        val setup = Setup(NO_LOG_BRANCH_FILTER)
        val log = setup.load()

        assertEquals(
            listOf(main.hash, agent.hash, spike.hash, stashOnMain.hash, stashOnSpike.hash, tag.commitHash) +
                listOf(originMain.hash, originBlame.hash, rebasingAgent.commit, detachedAlone.commit),
            setup.walker.started,
        )
        assertEquals(emptyList<Any>(), setup.reachable.asked)
        assertEquals(NO_LOG_BRANCH_FILTER, log.branchFilter)
    }

    @Test
    fun `only some branches start from them and the checked out one, with no tag and no lone detached HEAD`() {
        val filter = LogBranchFilter.Only(setOf(originBlame.name))
        val setup = Setup(filter)
        val log = setup.load()

        assertEquals(listOf(main.hash, originBlame.hash, stashOnMain.hash), setup.walker.started)
        assertEquals(filter, log.branchFilter)
    }

    @Test
    fun `a stash shows when the commit it was made on is in the shown history`() {
        val setup = Setup(LogBranchFilter.Only(setOf(spike.name)), reachable = setOf(main.hash, spike.hash))
        val start = setup.start()

        assertEquals(listOf(stashOnMain.hash, stashOnSpike.hash), start.stashes)
        assertEquals(listOf(main.hash, spike.hash), setup.reachable.asked.single().first)
        assertEquals(listOf(main.hash, spike.hash), setup.reachable.asked.single().second)
    }

    @Test
    fun `a stash made on a hidden branch's commit stays out`() {
        val start = Setup(LogBranchFilter.Only(emptySet())).start()

        assertEquals(listOf(stashOnMain.hash), start.stashes)
        assertEquals(listOf(main.hash, stashOnMain.hash), start.commits)
    }

    @Test
    fun `current starts from the checked out branch and the remote branches of its name`() {
        val start = Setup(LogBranchFilter.Current).start()

        assertEquals(listOf(main.hash, originMain.hash, stashOnMain.hash), start.commits)
    }

    @Test
    fun `another worktree rebasing a shown branch is started from, one detached alone isn't`() {
        val start = Setup(LogBranchFilter.Only(setOf(agent.name))).start()

        assertEquals(listOf(main.hash, agent.hash, rebasingAgent.commit, stashOnMain.hash), start.commits)
    }

    @Test
    fun `all but some leaves them out, and keeps tags, detached HEADs and stashes that aren't theirs`() {
        val setup = Setup(LogBranchFilter.All(setOf(agent.name, spike.name)))
        val start = setup.start()

        assertEquals(
            listOf(main.hash, tag.commitHash, originMain.hash, originBlame.hash, detachedAlone.commit) +
                listOf(stashOnMain.hash, stashOnSpike.hash),
            start.commits,
        )
        assertEquals(emptyList<Any>(), setup.reachable.asked)
    }

    @Test
    fun `a stash that names the branch it was made on goes with that branch, and nothing is walked`() {
        val onSpike = stash(hash('d'), base = main.hash, message = "WIP on wip/spike: 1234567 Try a second layout")
        val onAgent = stash(hash('e'), base = main.hash, message = "On claude/parser-cache: half done")
        val backup = stash(hash('f'), base = main.hash, message = "Snapshot WIP on main: 1234567 Before merge")
        val stashes = listOf(onSpike, onAgent, backup)

        val onlyAgent = Setup(LogBranchFilter.Only(setOf(agent.name)), reachable = emptySet(), stashes = stashes)
        assertEquals(listOf(onAgent.hash, backup.hash), onlyAgent.start().stashes)
        assertEquals(emptyList<Any>(), onlyAgent.reachable.asked)

        val allButSpike = Setup(LogBranchFilter.All(setOf(spike.name)), stashes = stashes)
        assertEquals(listOf(onAgent.hash, backup.hash), allButSpike.start().stashes)
    }

    @Test
    fun `a stash on a detached HEAD, or on a branch that is gone, is decided by the commit it was made on`() {
        val detachedStash = stash(hash('d'), base = main.hash, message = "WIP on (no branch): 1234567 Try")
        val goneBranch = stash(hash('e'), base = spike.hash, message = "On wip/gone: old")
        val setup = Setup(LogBranchFilter.Only(emptySet()), stashes = listOf(detachedStash, goneBranch))

        assertEquals(listOf(detachedStash.hash), setup.start().stashes)
        assertEquals(listOf(main.hash, spike.hash), setup.reachable.asked.single().second)
    }

    @Test
    fun `the checked out branch starts the walk even when it is hidden`() {
        val start = Setup(LogBranchFilter.All(setOf(spike.name)), currentBranch = spike, reachable = emptySet()).start()

        assertTrue(spike.hash in start.commits)
    }

    @Test
    fun `a detached HEAD of the tab's own worktree starts the walk, as JGit lists it as a branch named HEAD`() {
        val detached = Branch(hash('c'), Constants.HEAD, true)
        val start = Setup(
            LogBranchFilter.Only(emptySet()),
            currentBranch = detached,
            reachable = emptySet(),
            detachedHead = detached,
        ).start()

        assertEquals(listOf(detached.hash), start.commits)
    }

    @Test
    fun `loading more commits keeps the filter that the loaded log was walked from`() {
        val loadedWith = LogBranchFilter.Only(setOf(agent.name))
        val setup = Setup(savedFilter = NO_LOG_BRANCH_FILTER)
        val log = setup.load(Pagination.Paginated(GraphCommits(branchFilter = loadedWith)))

        assertEquals(listOf(main.hash, agent.hash, rebasingAgent.commit, stashOnMain.hash), setup.walker.started)
        assertEquals(loadedWith, log.branchFilter)
    }
}
