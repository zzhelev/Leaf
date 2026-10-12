// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICheckBranchNameGitAction {
    /** Whether git accepts [name] as a branch's name, as `git check-ref-format --branch` checks it. */
    suspend operator fun invoke(repositoryPath: String, name: String): Either<Boolean, GitError>
}
