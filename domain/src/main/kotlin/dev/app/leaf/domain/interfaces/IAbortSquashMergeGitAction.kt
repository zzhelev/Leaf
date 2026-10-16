// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

/**
 * Undoes a squash merge that waits to be committed (fork-only): `git reset --merge`, then puts back the changes that
 * `merge.autoStash` set aside, as `git merge --abort` does for a merge.
 */
interface IAbortSquashMergeGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<Unit, GitError>
}
