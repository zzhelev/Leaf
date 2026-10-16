// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.AutostashPutBack
import dev.app.leaf.domain.models.AutostashPutBackAfter

/**
 * Puts back the changes that `merge.autoStash` set aside (`MERGE_AUTOSTASH`), as `git commit` and `git merge --abort`
 * do, after Leaf's commit or abort (fork-only). When they don't apply, they go to the stash list as "autostash", and
 * the tab shows an `AutostashNotice`.
 */
interface IPutBackMergeAutostashGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        after: AutostashPutBackAfter,
    ): Either<AutostashPutBack, GitError>
}
