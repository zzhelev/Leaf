// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IMergeBranchGitAction
import dev.app.leaf.domain.models.Branch
import javax.inject.Inject

/**
 * Merges [Branch] into what is checked out with `git merge` (fork-only), with git's own message ("Merge branch
 * 'feature'", "Merge remote-tracking branch 'origin/feature'") and its hooks. Returns whether it stopped at conflicts.
 * `--ff` and `--no-ff` follow Leaf's "Fast-forward merge" setting, over the user's `merge.ff`, as JGit's merge did.
 */
class GitCliMergeBranchGitAction @Inject constructor(
    private val operations: GitCliOperations,
) : IMergeBranchGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        branch: Branch,
        fastForward: Boolean,
    ): Either<Boolean, GitError> = either {
        val branchArgument = operations.branchArgument(repositoryPath, branch.name).bind()
        val fastForwardOption = if (fastForward) "--ff" else "--no-ff"

        operations.run(repositoryPath, listOf("merge", fastForwardOption, "--no-edit", branchArgument))
    }
}
