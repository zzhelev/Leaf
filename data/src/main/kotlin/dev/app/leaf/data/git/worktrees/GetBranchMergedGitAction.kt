// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetBranchMergedGitAction
import dev.app.leaf.domain.models.BranchMerged
import java.io.File
import javax.inject.Inject

class GetBranchMergedGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetBranchMergedGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        base: String,
        branch: String,
    ): Either<BranchMerged, GitError> = either {
        val folder = File(repositoryPath)
        val count = gitCli.run(folder, listOf("rev-list", "--count", "$base..$branch")).bind().trim()
        val commitsNotInBase = count.toIntOrNull()
            ?: return@either Either.Err(GenericError("git rev-list printed '$count' for the commits of $branch"))

        val isMerged = commitsNotInBase == 0 || gitCli.mergeChangesNothing(folder, base, branch) == true

        Either.Ok(BranchMerged(isMerged, commitsNotInBase))
    }
}

/**
 * Whether merging [branch] into [base] would change nothing: the tree that `git merge-tree --write-tree` makes is
 * [base]'s. That tells a squash-merged branch from an unmerged one, which ancestry can't: its commits never reach the
 * base. The merge's tree goes into the object database, unreferenced, as git's own merges leave objects there.
 *
 * git exits with 1 when the merge has conflicts, and before 2.38 doesn't know `--write-tree`; both count as not merged.
 * Null when git couldn't run.
 */
internal suspend fun GitCli.mergeChangesNothing(folder: File, base: String, branch: String): Boolean? {
    val merge = execute(folder, listOf("merge-tree", "--write-tree", base, branch)).okOrNull() ?: return null

    if (merge.exitCode != 0) {
        return false
    }

    val baseTree = run(folder, listOf("rev-parse", "$base^{tree}")).okOrNull()?.trim() ?: return null

    return merge.stdout.lineSequence().firstOrNull()?.trim() == baseTree
}
