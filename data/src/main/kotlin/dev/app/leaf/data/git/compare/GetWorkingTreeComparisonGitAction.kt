// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.compare.comparisonRelation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.interfaces.IGetWorkingTreeComparisonGitAction
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.MAX_COMPARED_COMMITS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

/**
 * A commit or a branch compared with the working tree (fork-only, `docs/fork/compare.md`, 7.2): the commits as
 * [GetComparisonGitAction] lists them, with HEAD as B, and the files from A's tree, or the merge base's, to the files on
 * disk ([workingTreeChanges]).
 */
class GetWorkingTreeComparisonGitAction @Inject constructor(
    private val jgit: JGit,
    private val commitMapper: JGitCommitMapper,
    private val partialClone: PartialCloneFetch,
) : IGetWorkingTreeComparisonGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        a: String,
        mode: ComparisonMode,
    ): Either<Comparison, GitError> = withContext(Dispatchers.IO) {
        partialClone.retryingMissing(repositoryPath) { compare(repositoryPath, a, mode) }
    }

    private suspend fun compare(repositoryPath: String, a: String, mode: ComparisonMode) = withContext(Dispatchers.IO) {
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val aId = repository.resolveCommit(a).bind()
            val headId = repository.resolve("${Constants.HEAD}^{commit}")
                ?: Either.Err(GenericError("The working tree has no commit to compare from yet.")).bind()
            val mergeBase = repository.mergeBase(aId, headId)
            val (onlyInB, countOnlyInB) = repository.commitsOnlyIn(headId, aId, MAX_COMPARED_COMMITS)
            val (onlyInA, countOnlyInA) = repository.commitsOnlyIn(aId, headId, MAX_COMPARED_COMMITS)

            val oldTree = when (mode) {
                ComparisonMode.SINCE_SPLIT -> mergeBase?.tree
                ComparisonMode.ALL -> repository.treeOf(aId)
            }
            val changes = oldTree?.let { repository.workingTreeChanges(it) }

            Comparison(
                a = a,
                b = Constants.HEAD,
                mode = mode,
                relation = comparisonRelation(countOnlyInA, countOnlyInB, hasMergeBase = mergeBase != null),
                mergeBase = mergeBase?.name,
                commitsOnlyInB = onlyInB.map { commitMapper.toDomain(it) },
                countOnlyInB = countOnlyInB,
                commitsOnlyInA = onlyInA.map { commitMapper.toDomain(it) },
                countOnlyInA = countOnlyInA,
                changes = changes?.changes.orEmpty().sortedBy { it.filePath },
                untrackedPaths = changes?.untracked.orEmpty(),
            )
        }
    }
}
