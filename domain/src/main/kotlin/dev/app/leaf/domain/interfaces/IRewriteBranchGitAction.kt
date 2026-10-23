// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.BranchRewriteResult

/** Joins and rewords a local branch's commits, keeping every commit's tree (fork-only). See [BranchRewrite]. */
interface IRewriteBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, rewrite: BranchRewrite): Either<BranchRewriteResult, GitError>
}

/** Puts a rewritten branch back at the commit it was at before, while it's still where the rewrite left it. */
interface IUndoBranchRewriteGitAction {
    suspend operator fun invoke(repositoryPath: String, rewrite: BranchRewriteResult): Either<Unit, GitError>
}
