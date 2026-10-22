// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit

/**
 * The commit that [revision] names, as `git rev-parse <revision>^{commit}` resolves it (fork-only,
 * `docs/fork/compare.md`, 7.1): a hash or a part of one, a ref, `HEAD~2`. Null when it names none.
 */
interface IResolveCommitGitAction {
    suspend operator fun invoke(repositoryPath: String, revision: String): Either<Commit?, GitError>
}
