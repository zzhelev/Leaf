// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.diff.diffWithRenames
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.interfaces.IGetBranchComparisonGitAction
import dev.app.leaf.domain.models.BranchComparison
import dev.app.leaf.domain.models.MAX_COMPARED_COMMITS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import javax.inject.Inject

/**
 * Compares a branch with the base (fork-only), as `git log base..branch` and `git diff base...branch` do. It only
 * reads, so JGit does it.
 */
class GetBranchComparisonGitAction @Inject constructor(
    private val jgit: JGit,
    private val commitMapper: JGitCommitMapper,
    private val partialClone: PartialCloneFetch,
) : IGetBranchComparisonGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        base: String,
        branch: String,
    ): Either<BranchComparison, GitError> = withContext(Dispatchers.IO) {
        partialClone.retryingMissing(repositoryPath) { compare(repositoryPath, base, branch) }
    }

    private suspend fun compare(
        repositoryPath: String,
        base: String,
        branch: String,
    ): Either<BranchComparison, GitError> = withContext(Dispatchers.IO) {
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val baseId = repository.resolveCommit(base).bind()
            val branchId = repository.resolveCommit(branch).bind()
            val mergeBase = repository.mergeBase(baseId, branchId)
            val (commits, ahead) = repository.commitsOnlyIn(branchId, baseId, MAX_COMPARED_COMMITS)
            val behind = repository.commitsOnlyIn(baseId, branchId, limit = 0).second

            val changes = if (mergeBase == null) {
                emptyList()
            } else {
                // The entries alone: formatting each file's diff, which nothing reads, reads all its blobs. Files that
                // the branch moved are renames, as in a commit's list
                repository.diffWithRenames(
                    oldTree = repository.treeParser(mergeBase.tree),
                    newTree = repository.treeParser(RevWalk(repository).use { it.parseCommit(branchId).tree }),
                ).sortedBy { it.filePath }
            }

            BranchComparison(
                base = base,
                branch = branch,
                mergeBase = mergeBase?.name,
                commits = commits.map { commitMapper.toDomain(it) },
                ahead = ahead,
                behind = behind,
                changes = changes,
            )
        }
    }
}

private fun Repository.resolveCommit(ref: String): Either<ObjectId, GitError> {
    val id = resolve("$ref^{commit}")

    return if (id == null) {
        Either.Err(GenericError("${Repository.shortenRefName(ref)} doesn't exist."))
    } else {
        Either.Ok(id)
    }
}

/**
 * The merge base that `git diff a...b` compares from, or null when the two have no commit in common. With several (a
 * criss-cross merge), the first that JGit finds, as git takes one too.
 */
private fun Repository.mergeBase(a: ObjectId, b: ObjectId): RevCommit? = RevWalk(this).use { walk ->
    walk.revFilter = RevFilter.MERGE_BASE
    walk.markStart(walk.parseCommit(a))
    walk.markStart(walk.parseCommit(b))
    walk.next()
}

/**
 * The commits that [tip] has and [other] doesn't, oldest first, as many as [limit] (the newest ones), and how many
 * there are in all.
 */
private fun Repository.commitsOnlyIn(tip: ObjectId, other: ObjectId, limit: Int): Pair<List<RevCommit>, Int> =
    RevWalk(this).use { walk ->
        walk.sort(RevSort.TOPO)
        walk.markStart(walk.parseCommit(tip))
        walk.markUninteresting(walk.parseCommit(other))

        val newestFirst = mutableListOf<RevCommit>()
        var count = 0

        for (commit in walk) {
            count++

            if (newestFirst.size < limit) {
                newestFirst.add(commit)
            }
        }

        newestFirst.asReversed().toList() to count
    }

private fun Repository.treeParser(tree: RevTree) = CanonicalTreeParser().also { parser ->
    newObjectReader().use { reader -> parser.reset(reader, tree.id) }
}
