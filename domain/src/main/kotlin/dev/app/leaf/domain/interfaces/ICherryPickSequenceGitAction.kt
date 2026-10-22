// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/** What to do with a cherry-pick of several commits that git stopped (`CherryPickSequence`). */
enum class CherryPickSequenceCommand(val option: String) {
    /** Commits the stopped commit as resolved, with the message in `MERGE_MSG`, then picks the rest. */
    CONTINUE("--continue"),

    /** Leaves the stopped commit out, and picks the rest. */
    SKIP("--skip"),

    /** Puts the branch back where it was before the cherry-pick, the commits it already picked included. */
    ABORT("--abort"),
}

interface ICherryPickSequenceGitAction {
    /**
     * Runs `git cherry-pick <option>` in the worktree whose git dir is [repositoryPath], and returns whether it stopped
     * again, at a later commit's conflicts.
     */
    suspend operator fun invoke(repositoryPath: String, command: CherryPickSequenceCommand): Either<Boolean, GitError>
}
