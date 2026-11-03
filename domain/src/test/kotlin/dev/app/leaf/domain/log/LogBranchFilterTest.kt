// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which branches the log shows under a filter (fork-only, upstream issue 207). The walk itself is `FilteringLogTest`. */
class LogBranchFilterTest {
    private val main = local("main")
    private val agent = local("claude/parser-cache")
    private val spike = local("wip/spike")
    private val originMain = remote("origin", "main")
    private val originAgent = remote("origin", "claude/parser-cache")
    private val upstreamMain = remote("upstream", "main")
    private val originOther = remote("origin", "feat/blame")
    private val detached = Branch(hash = "f00", name = "HEAD", isLocal = true)

    private val locals = listOf(main, agent, spike)
    private val remotes = listOf(originMain, originAgent, upstreamMain, originOther)
    private val all = locals + remotes

    private fun local(name: String) = Branch(hash = "h-$name", name = "refs/heads/$name", isLocal = true)
    private fun remote(remote: String, name: String) =
        Branch(hash = "h-$remote-$name", name = "refs/remotes/$remote/$name", isLocal = false)

    private fun LogBranchFilter.shown(current: Branch? = main) = all.filter { shows(it, current) }

    @Test
    fun `no filter shows every branch`() {
        assertEquals(all, NO_LOG_BRANCH_FILTER.shown())
    }

    @Test
    fun `all but some leaves exactly those out`() {
        val filter = LogBranchFilter.All(setOf(agent.name, originAgent.name))

        assertEquals(listOf(main, spike, originMain, upstreamMain, originOther), filter.shown())
    }

    @Test
    fun `only some shows exactly those`() {
        val filter = LogBranchFilter.Only(setOf(agent.name, originOther.name))

        assertEquals(listOf(main, agent, originOther), filter.shown())
    }

    @Test
    fun `the checked out branch always shows, whatever the filter`() {
        assertTrue(LogBranchFilter.All(setOf(spike.name)).shows(spike, currentBranch = spike))
        assertTrue(LogBranchFilter.Only(emptySet()).shows(spike, currentBranch = spike))
        assertTrue(LogBranchFilter.Current.shows(spike, currentBranch = spike))
        assertFalse(LogBranchFilter.Only(emptySet()).shows(spike, currentBranch = main))
    }

    @Test
    fun `a detached HEAD always shows, and current shows nothing else`() {
        assertTrue(LogBranchFilter.Only(emptySet()).shows(detached, currentBranch = detached))
        assertEquals(emptyList<Branch>(), LogBranchFilter.Current.shown(current = detached))
    }

    @Test
    fun `current shows the checked out branch and the remote branches of its name`() {
        assertEquals(listOf(main, originMain, upstreamMain), LogBranchFilter.Current.shown())
        assertEquals(listOf(agent, originAgent), LogBranchFilter.Current.shown(current = agent))
    }

    @Test
    fun `without a checked out branch, an unborn one, only the chosen ones show`() {
        assertEquals(emptyList<Branch>(), LogBranchFilter.Current.shown(current = null))
        assertEquals(listOf(agent), LogBranchFilter.Only(setOf(agent.name)).shown(current = null))
    }

    @Test
    fun `names that no branch has any more are left out of what the filter says`() {
        val gone = "refs/heads/gone"

        assertEquals(LogBranchFilter.All(setOf(agent.name)), LogBranchFilter.All(setOf(agent.name, gone)).withExisting(all))
        assertEquals(LogBranchFilter.Only(emptySet()), LogBranchFilter.Only(setOf(gone)).withExisting(all))
        assertSame(LogBranchFilter.Current, LogBranchFilter.Current.withExisting(all))
        assertEquals(NO_LOG_BRANCH_FILTER, LogBranchFilter.All(setOf(gone)).withExisting(all))
    }

    @Test
    fun `a local branch stands for the remote branches of its name, a remote branch for itself`() {
        assertEquals(setOf(main.name, originMain.name, upstreamMain.name), main.withRemotesOfItsName(remotes))
        assertEquals(setOf(spike.name), spike.withRemotesOfItsName(remotes))
        assertEquals(setOf(originMain.name), originMain.withRemotesOfItsName(remotes))
    }

    @Test
    fun `hiding adds to all but, takes from only, and turns current into only`() {
        val names = setOf(agent.name, originAgent.name)
        val shownNow = LogBranchFilter.Current.shownNames(all, agent)

        assertEquals(LogBranchFilter.All(names), NO_LOG_BRANCH_FILTER.hiding(names, emptySet()))
        assertEquals(
            LogBranchFilter.Only(setOf(main.name)),
            LogBranchFilter.Only(setOf(main.name, agent.name)).hiding(names, emptySet()),
        )
        assertEquals(LogBranchFilter.Only(emptySet()), LogBranchFilter.Current.hiding(names, shownNow))
    }

    @Test
    fun `showing takes from all but, adds to only, and turns current into only`() {
        val names = setOf(spike.name)
        val shownNow = LogBranchFilter.Current.shownNames(all, main)

        assertEquals(
            LogBranchFilter.All(setOf(agent.name)),
            LogBranchFilter.All(setOf(agent.name, spike.name)).showing(names, emptySet()),
        )
        assertEquals(LogBranchFilter.Only(setOf(main.name, spike.name)), LogBranchFilter.Only(setOf(main.name)).showing(names, emptySet()))
        assertEquals(
            LogBranchFilter.Only(setOf(main.name, originMain.name, upstreamMain.name, spike.name)),
            LogBranchFilter.Current.showing(names, shownNow),
        )
    }

    @Test
    fun `a box toggles its own branch alone, and the checked out one not at all`() {
        assertEquals(LogBranchFilter.All(setOf(agent.name)), NO_LOG_BRANCH_FILTER.toggled(agent, all, main))
        assertEquals(NO_LOG_BRANCH_FILTER, LogBranchFilter.All(setOf(agent.name)).toggled(agent, all, main))
        assertEquals(LogBranchFilter.Only(setOf(originOther.name)), LogBranchFilter.Only(emptySet()).toggled(originOther, all, main))
        assertEquals(NO_LOG_BRANCH_FILTER, NO_LOG_BRANCH_FILTER.toggled(main, all, main))
        assertEquals(LogBranchFilter.Current, LogBranchFilter.Current.toggled(main, all, main))
    }

    @Test
    fun `every box clicked twice gives the filter back`() {
        val filters = listOf(
            NO_LOG_BRANCH_FILTER,
            LogBranchFilter.All(setOf(agent.name)),
            LogBranchFilter.Only(setOf(main.name, originOther.name)),
        )

        for (filter in filters) {
            for (branch in all - main) {
                val twice = filter.toggled(branch, all, main).toggled(branch, all, main)

                assertEquals(filter, twice, "$filter, ${branch.name}")
                assertTrue(filter.toggled(branch, all, main).shows(branch, main) != filter.shows(branch, main))
            }
        }
    }

    @Test
    fun `another worktree's detached HEAD shows while it rebases a shown branch, or under all but some`() {
        val shown = setOf(main.name)

        assertTrue(LogBranchFilter.Only(shown).showsWorktreeHead(main.name, shown))
        assertFalse(LogBranchFilter.Only(shown).showsWorktreeHead(agent.name, shown))
        assertFalse(LogBranchFilter.Only(shown).showsWorktreeHead(null, shown))
        assertFalse(LogBranchFilter.Current.showsWorktreeHead(null, shown))
        assertTrue(LogBranchFilter.All(setOf(agent.name)).showsWorktreeHead(null, shown))
        assertFalse(LogBranchFilter.All(setOf(agent.name)).showsWorktreeHead(agent.name, shown))
    }

    @Test
    fun `a stash's message names the branch it was made on`() {
        assertEquals("main", stashBranchOf("WIP on main: 1a2b3c4 Fix the parser"))
        assertEquals("claude/parser-cache", stashBranchOf("On claude/parser-cache: half done\n\nMore"))
        assertEquals("main", stashBranchOf("Snapshot WIP on main: 1a2b3c4 Before merge"))
        assertEquals(null, stashBranchOf("WIP on (no branch): 1a2b3c4 Detached"))
        assertEquals(null, stashBranchOf("autostash"))
        assertEquals(null, stashBranchOf("wip on main"))
        assertEquals(null, stashBranchOf("Some text: On main: not at the start"))
    }
}
