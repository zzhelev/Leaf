// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories

import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.GraphRevWalker
import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetCurrentBranchGitAction
import dev.app.leaf.domain.interfaces.IGetOtherWorktreeHeadsGitAction
import dev.app.leaf.domain.interfaces.IGetRemoteBranchesGitAction
import dev.app.leaf.domain.interfaces.IGetRemotesGitAction
import dev.app.leaf.domain.interfaces.IGetStashListGitAction
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.interfaces.IGetTagsGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.usecases.GetLogUseCase
import dev.app.leaf.domain.usecases.GetRemotesUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider

/**
 * The commits that `GetLogUseCase` starts its walk from (fork-only): the refs', and the other worktrees' detached
 * HEADs, each once, after them. The real repositories are in `OtherWorktreeHeadsInLogTest`.
 */
class GetLogStartCommitsTest {
    private val branchTip = "1".repeat(40)
    private val tagTip = "2".repeat(40)
    private val agent1 = "a".repeat(40)
    private val agent2 = "b".repeat(40)

    /** Notes the commits that the walk is started from. */
    private class RecordingWalker : GraphRevWalker {
        val started = mutableListOf<String>()

        override suspend fun prepare(repository: String, startingCommits: List<String>): Either<Unit, Nothing> {
            started.addAll(startingCommits)

            return Either.Ok(Unit)
        }

        override fun iterator(): Iterator<Commit> = emptyList<Commit>().iterator()
        override fun close() = Unit
    }

    private fun startCommits(heads: Either<List<String>, *>, pagination: Pagination = Pagination.None): List<String> {
        val walker = RecordingWalker()
        val tab = TabCoroutineScope()
        val executor = UseCaseExecutor(
            InMemoryRepositoryDataRepository(),
            InMemoryRepositoryStateRepository(),
            Provider { error("No refresh here") },
            tab,
        )
        val getTags = mockk<IGetTagsGitAction> {
            coEvery { this@mockk(any()) } returns Either.Ok(
                listOf(Tag(commitHash = tagTip, hash = tagTip, name = "refs/tags/v1"))
            )
        }
        val otherHeads = mockk<IGetOtherWorktreeHeadsGitAction> {
            @Suppress("UNCHECKED_CAST")
            coEvery { this@mockk(any()) } returns (heads as Either<List<String>, GitError>)
        }
        val getStatus = mockk<IGetStatusGitAction> { coEvery { this@mockk(any(), any()) } returns Either.Ok(Status()) }
        val getBranches = mockk<IGetBranchesGitAction> {
            coEvery { this@mockk(any<String>()) } returns Either.Ok(listOf(Branch(branchTip, "refs/heads/main", true)))
        }
        val getCurrentBranch = mockk<IGetCurrentBranchGitAction> {
            coEvery { this@mockk(any<String>()) } returns Either.Ok(null)
        }
        val getStashes = mockk<IGetStashListGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(emptyList()) }
        val getRemotes = mockk<IGetRemotesGitAction> { coEvery { this@mockk(any()) } returns Either.Ok(emptyList()) }
        val getRemoteBranches = mockk<IGetRemoteBranchesGitAction> {
            coEvery { this@mockk(any()) } returns Either.Ok(emptyList())
        }

        val getLog = GetLogUseCase(
            getBranchesGitAction = getBranches,
            getTagsGitAction = getTags,
            getRemotesUseCase = GetRemotesUseCase(getRemotes, getRemoteBranches, executor),
            getStashListGitAction = getStashes,
            getOtherWorktreeHeadsGitAction = otherHeads,
            getStatusGitAction = getStatus,
            getCurrentBranchAction = getCurrentBranch,
            graphLogGenerator = GraphLogGenerator(Provider { walker }),
            repositoryDataRepository = InMemoryRepositoryDataRepository(),
        )

        try {
            val result = runBlocking { getLog("/repo/.git", pagination) }

            assertTrue(result is Either.Ok, "The log didn't load: $result")
        } finally {
            tab.coroutineContext.cancel()
        }

        return walker.started
    }

    @Test
    fun `starts from the refs, then from each other worktree's detached HEAD`() {
        assertEquals(
            listOf(branchTip, tagTip, agent1, agent2),
            startCommits(Either.Ok(listOf(agent1, agent2))),
        )
    }

    @Test
    fun `a worktree's HEAD that a ref has is started from once`() {
        assertEquals(
            listOf(branchTip, tagTip, agent1),
            startCommits(Either.Ok(listOf(branchTip, agent1, tagTip))),
        )
    }

    @Test
    fun `without any, the walk starts from the refs alone`() {
        assertEquals(listOf(branchTip, tagTip), startCommits(Either.Ok(emptyList())))
    }

    @Test
    fun `when the HEADs can't be read, the log still loads, from the refs alone`() {
        assertEquals(listOf(branchTip, tagTip), startCommits(Either.Err(GenericError("git failed"))))
    }

    @Test
    fun `loading more commits starts from them too`() {
        val loaded = GraphCommits()

        assertEquals(
            listOf(branchTip, tagTip, agent1),
            startCommits(Either.Ok(listOf(agent1)), Pagination.Paginated(loaded)),
        )
    }
}
