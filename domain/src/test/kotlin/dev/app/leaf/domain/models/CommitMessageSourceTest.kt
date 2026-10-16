// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which message the commit field shows, and so where its edits are saved, in each state of the repository. */
class CommitMessageSourceTest {
    @Test
    fun `shows a merge's message while merging, even with a squash merge's message there`() {
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.MERGING, true))
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.MERGING_RESOLVED, false))
    }

    @Test
    fun `shows git's message during a cherry-pick or a revert, as git commit takes it`() {
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.CHERRY_PICKING, false))
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.CHERRY_PICKING_RESOLVED, false))
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.REVERTING, false))
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.REVERTING_RESOLVED, false))
    }

    @Test
    fun `shows the message for a rebase's edit stop while any rebase goes on`() {
        for (state in listOf(
            RepositoryState.REBASING,
            RepositoryState.REBASING_REBASING,
            RepositoryState.REBASING_MERGE,
            RepositoryState.REBASING_INTERACTIVE,
        )) {
            assertEquals(CommitMessageSource.REBASE, commitMessageSource(state, false), state.name)
        }
    }

    @Test
    fun `shows a squash merge's message while one waits to be committed`() {
        assertEquals(CommitMessageSource.SQUASH, commitMessageSource(RepositoryState.SAFE, true))
    }

    @Test
    fun `shows the draft otherwise, also before the state is known`() {
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(RepositoryState.SAFE, false))
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(RepositoryState.BISECTING, false))
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(RepositoryState.APPLY, false))
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(null, false))
    }

    @Test
    fun `the commit removes a merge's, cherry-pick's, revert's or squash merge's message itself`() {
        assertTrue(CommitMessageSource.MERGE.isMergeMessage)
        assertTrue(CommitMessageSource.SQUASH.isMergeMessage)
        // DoCommitUseCase removes these after the commit
        assertFalse(CommitMessageSource.NORMAL.isMergeMessage)
        assertFalse(CommitMessageSource.REBASE.isMergeMessage)
    }
}
