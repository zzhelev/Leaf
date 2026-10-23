// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IUndoBranchRewriteGitAction
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

/**
 * Undoes a [RewriteBranchGitAction] (fork-only): the branch goes back to its old tip with one `git update-ref` that
 * expects the new one, so a branch that moved since is refused and stays. Both tips have the same tree, so the working
 * tree and the index stay as they are, as after the rewrite, but after a squash onto the base, where a checked-out
 * branch's files go back with it ([moveBranchWithFiles]). The new commits stay in the reflog, as the old ones did.
 */
class UndoBranchRewriteGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : IUndoBranchRewriteGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        rewrite: BranchRewriteResult,
    ): Either<Unit, GitError> = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val place = branchPlace(repository, rewrite.branch)

        if (repository.exactRef(rewrite.branch)?.objectId?.name != rewrite.newTip) {
            raiseError(RewriteBranchError.BranchMoved(Repository.shortenRefName(rewrite.branch)))
        }

        val treeChanges = RevWalk(repository).use { walk ->
            val oldTree = walk.parseCommit(ObjectId.fromString(rewrite.oldTip)).tree
            val newTree = walk.parseCommit(ObjectId.fromString(rewrite.newTip)).tree

            oldTree != newTree
        }

        val message = "undo ${rewrite.kind.reflogName}: back to ${rewrite.oldTip.take(7)}"

        // After a squash onto the base, the files go back too
        if (treeChanges) {
            moveBranchWithFiles(gitCli, place, rewrite.branch, rewrite.newTip, rewrite.oldTip, message)
        } else {
            moveBranch(gitCli, place, rewrite.branch, rewrite.newTip, rewrite.oldTip, message)
        }
    }
}
