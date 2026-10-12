// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.models.RepositoryState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which message the commit field shows: a merge's, a squash merge's, or the draft of a plain commit. */
class CommitMessageSourceTest {
    @Test
    fun `shows a merge's message while merging, even with a squash merge's message there`() {
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.MERGING, true))
        assertEquals(CommitMessageSource.MERGE, commitMessageSource(RepositoryState.MERGING_RESOLVED, false))
    }

    @Test
    fun `shows a squash merge's message while one waits to be committed`() {
        assertEquals(CommitMessageSource.SQUASH, commitMessageSource(RepositoryState.SAFE, true))
    }

    @Test
    fun `shows the draft otherwise, also before the state is known`() {
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(RepositoryState.SAFE, false))
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(RepositoryState.REBASING_MERGE, false))
        assertEquals(CommitMessageSource.NORMAL, commitMessageSource(null, false))
    }
}
