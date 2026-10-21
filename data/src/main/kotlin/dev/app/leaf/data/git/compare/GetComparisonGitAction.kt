// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.diff.diffWithRenames
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.compare.comparisonRelation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.extensions.filePath
import dev.app.leaf.domain.interfaces.IGetComparisonGitAction
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
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
 * Compares two commits or branches (fork-only), as `git log A..B`, `git log B..A` and `git diff A...B` or `git diff A B`
 * do. It only reads, so JGit does it.
 */
class GetComparisonGitAction @Inject constructor(
    private val jgit: JGit,
    private val commitMapper: JGitCommitMapper,
    private val partialClone: PartialCloneFetch,
) : IGetComparisonGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        a: String,
        b: String,
        mode: ComparisonMode,
    ): Either<Comparison, GitError> = withContext(Dispatchers.IO) {
        partialClone.retryingMissing(repositoryPath) { compare(repositoryPath, a, b, mode) }
    }

    private suspend fun compare(
        repositoryPath: String,
        a: String,
        b: String,
        mode: ComparisonMode,
    ): Either<Comparison, GitError> = withContext(Dispatchers.IO) {
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val aId = repository.resolveCommit(a).bind()
            val bId = repository.resolveCommit(b).bind()
            val mergeBase = repository.mergeBase(aId, bId)
            val (onlyInB, countOnlyInB) = repository.commitsOnlyIn(bId, aId, MAX_COMPARED_COMMITS)
            val (onlyInA, countOnlyInA) = repository.commitsOnlyIn(aId, bId, MAX_COMPARED_COMMITS)

            val oldTree = when (mode) {
                ComparisonMode.SINCE_SPLIT -> mergeBase?.tree
                ComparisonMode.ALL -> repository.treeOf(aId)
            }

            val changes = if (oldTree == null) {
                emptyList()
            } else {
                // The entries alone: formatting each file's diff, which nothing reads, reads all its blobs. Files that
                // moved are renames, as in a commit's list
                repository.diffWithRenames(
                    oldTree = repository.treeParser(oldTree),
                    newTree = repository.treeParser(repository.treeOf(bId)),
                ).sortedBy { it.filePath }
            }

            Comparison(
                a = a,
                b = b,
                mode = mode,
                relation = comparisonRelation(countOnlyInA, countOnlyInB, hasMergeBase = mergeBase != null),
                mergeBase = mergeBase?.name,
                commitsOnlyInB = onlyInB.map { commitMapper.toDomain(it) },
                countOnlyInB = countOnlyInB,
                commitsOnlyInA = onlyInA.map { commitMapper.toDomain(it) },
                countOnlyInA = countOnlyInA,
                changes = changes,
            )
        }
    }
}

private fun Repository.resolveCommit(revision: String): Either<ObjectId, GitError> {
    val id = resolve("$revision^{commit}")

    return if (id == null) {
        Either.Err(GenericError("${Repository.shortenRefName(revision)} doesn't exist."))
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

private fun Repository.treeOf(commit: ObjectId): RevTree = RevWalk(this).use { it.parseCommit(commit).tree }

private fun Repository.treeParser(tree: RevTree) = CanonicalTreeParser().also { parser ->
    newObjectReader().use { reader -> parser.reset(reader, tree.id) }
}
