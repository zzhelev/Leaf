// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode

/**
 * [a] (a full ref or a commit id) compared with the working tree (fork-only, `docs/fork/compare.md`, 7.2): the commits
 * of A and HEAD, as for any comparison, and the files from A's tree (all differences, `git diff A`) or from the merge
 * base of A and HEAD (since they split) to the files on disk, with the untracked files that git doesn't ignore.
 */
interface IGetWorkingTreeComparisonGitAction {
    suspend operator fun invoke(repositoryPath: String, a: String, mode: ComparisonMode): Either<Comparison, GitError>
}
