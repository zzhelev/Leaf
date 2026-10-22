// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.diff.DiffEntry

/**
 * The change of the file at [path] from commit [a] to commit [b] (fork-only, `docs/fork/compare.md`, 7.3), as
 * `git diff a b -- path` shows it: two versions of a file in its history. Null when both have the same version of it, or
 * neither has it.
 */
interface IGetFileDiffBetweenCommitsGitAction {
    suspend operator fun invoke(repositoryPath: String, path: String, a: String, b: String): Either<DiffEntry?, GitError>
}
