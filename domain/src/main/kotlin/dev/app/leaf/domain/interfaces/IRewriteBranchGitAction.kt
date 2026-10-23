// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.PlanRecord
import dev.app.leaf.domain.rewrite.RebasePlan

/** Joins and rewords a local branch's commits, keeping every commit's tree (fork-only). See [BranchRewrite]. */
interface IRewriteBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, rewrite: BranchRewrite): Either<BranchRewriteResult, GitError>
}

/** Puts a rewritten branch back at the commit it was at before, while it's still where the rewrite left it. */
interface IUndoBranchRewriteGitAction {
    suspend operator fun invoke(repositoryPath: String, rewrite: BranchRewriteResult): Either<Unit, GitError>
}

/**
 * What putting [line]'s branch on the latest base would do, for "Squash branch into one commit…". Null when there's
 * nothing to put it on (no base, or the branch has the base's tip) or git can't tell (before 2.38).
 */
interface IGetOntoBaseCheckGitAction {
    suspend operator fun invoke(repositoryPath: String, line: BranchLine): Either<OntoBaseCheck?, GitError>
}

/** Replays [plan] without a working tree, to tell whether it stops and where, and what each commit's tree is. */
interface ICheckRebasePlanGitAction {
    suspend operator fun invoke(repositoryPath: String, plan: RebasePlan): Either<PlanCheck, GitError>
}

/** Runs [plan], which doesn't stop, without a working tree, as part A's rewrites run. Undone as they are. */
interface IRunRebasePlanGitAction {
    suspend operator fun invoke(repositoryPath: String, plan: RebasePlan): Either<BranchRewriteResult, GitError>
}

/**
 * Starts [plan], which stops, as `git rebase -i` in the worktree that has the branch, or in the tab's after checking
 * it out there, setting uncommitted changes aside there when [autostash] says so.
 */
interface IStartRebasePlanGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        plan: RebasePlan,
        autostash: Boolean,
    ): Either<PlanRebaseOutcome, GitError>
}

/** The record of the commit editor's plan that the rebase in [repositoryPath]'s worktree waits in, or null. */
interface IGetPlanRecordGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<PlanRecord?, GitError>
}
