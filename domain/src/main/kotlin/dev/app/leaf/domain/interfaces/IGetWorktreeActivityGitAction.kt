// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetWorktreeActivityGitAction {
    /**
     * When something last changed in the worktree at [worktreePath], in milliseconds since the epoch, which tells
     * whether an agent may still be working in it: its index, HEAD or HEAD's reflog, or a file that `git status` lists.
     * Null when none of them has a time.
     *
     * With [notBefore], the files aren't looked at when the git dir changed at that time or later: the result is then
     * the git dir's time, which only tells that the worktree changed since [notBefore].
     */
    suspend operator fun invoke(worktreePath: String, notBefore: Long? = null): Either<Long?, GitError>
}
