// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.log

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IFindCommitsGitAction
import dev.app.leaf.domain.log.matchesLogSearch
import dev.app.leaf.domain.models.FoundCommits
import dev.app.leaf.domain.models.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.MutableObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

private const val TAG = "FindCommitsGitAction"

/**
 * Walks the history for the log's search (fork-only, upstream issue 277), in process: a probe on a repository of 199,582
 * commits took 1.1 to 1.4 s whatever the term, where `git log` took 1.5 to 2.6 s and can't match a hash's middle or
 * a name and a message in one pass.
 */
class FindCommitsGitAction @Inject constructor(
    private val jgit: JGit,
) : IFindCommitsGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        startingCommits: List<String>,
        stashes: Set<String>,
        lowercaseTerm: String,
        maxHits: Int,
        maxCommits: Int,
    ): Either<FoundCommits, GitError> = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            walkForHits(currentCoroutineContext(), git.repository, startingCommits, stashes, maxHits, maxCommits) {
                matchesLogSearch(
                    lowercaseTerm,
                    hash = it.name,
                    message = it.fullMessage,
                    authorName = it.authorIdent?.name,
                    committerName = it.committerIdent?.name,
                )
            }
        }
    }
}

/**
 * The commits of the walk for which [matches] is true, as [FindCommitsGitAction] finds them. It blocks, so it runs on
 * `Dispatchers.IO`.
 *
 * The walk sorts and skips as the log's does (`JGitGraphRevWalker`, `GraphLogGenerator`): the hits come in the order of
 * the log's rows, a stash's other parents (its index and its untracked files, which the log leaves out) are no hits, and
 * a hit's depth counts the commits that the log has. Reading the first commit of a topological walk reads the whole
 * history, so a walk that is cancelled early still takes its second or so on big repositories. It checks [context] for
 * cancellation at each commit after that.
 */
internal fun walkForHits(
    context: CoroutineContext,
    repository: Repository,
    startingCommits: List<String>,
    stashes: Set<String>,
    maxHits: Int,
    maxCommits: Int,
    matches: (RevCommit) -> Boolean,
): FoundCommits {
    val hits = ArrayList<SearchHit>()
    val commitsToSkip = HashSet<String>()
    var reachedHitLimit = false

    RevWalk(repository).use { walk ->
        walk.sort(RevSort.TOPO, true)
        walk.sort(RevSort.COMMIT_TIME_DESC, true)

        for (hash in startingCommits) {
            try {
                walk.markStart(walk.lookupCommit(MutableObjectId().apply { fromString(hash) }))
            } catch (e: Exception) {
                printError(TAG, "Could not mark commit $hash as start: ${e.message}", e)
            }
        }

        var depth = 0

        while (depth < maxCommits) {
            val commit = walk.next() ?: break

            context.ensureActive()

            if (commitsToSkip.remove(commit.name)) continue

            if (commit.name in stashes) {
                commit.parents.drop(1).forEach { commitsToSkip += it.name }
            }

            if (matches(commit)) {
                hits += SearchHit(commit.name, depth)

                if (hits.size >= maxHits) {
                    reachedHitLimit = true
                    break
                }
            }

            depth++
        }
    }

    return FoundCommits(hits, reachedHitLimit)
}
