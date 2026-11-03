// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.log

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IGetCommitsReachableGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

private const val TAG = "GetCommitsReachableGitAction"

/**
 * How far past the oldest commit looked for the walk goes, in commit time, before it takes the others as out of reach.
 * Commit times go back in a history, mostly: a clock that was wrong makes a parent newer than its child. A day covers
 * that and keeps the walk from reading the whole history when one of the commits isn't in it.
 */
private const val CLOCK_SKEW_SECONDS = 24 * 60 * 60

/**
 * How many commits the walk reads at most. A stash made years ago walks back that far: on a repository of 199,587
 * commits, one made on a commit of 2017 took 0.9 s, each time the log was read. This is about 0.2 s there, and a commit
 * further back counts as out of the shown history.
 */
internal const val MAX_COMMITS_WALKED = 50_000

/**
 * The tab's log asks the same question at each refresh while no ref moves, as do its searches, and the answer can't
 * change: it keeps the last one. It's bound per tab.
 */
class GetCommitsReachableGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommitsReachableGitAction {
    private data class Question(val repositoryPath: String, val startingCommits: List<String>, val commits: List<String>)

    @Volatile
    private var lastAnswer: Pair<Question, Set<String>>? = null

    override suspend operator fun invoke(
        repositoryPath: String,
        startingCommits: List<String>,
        commits: List<String>,
    ) = jgit.provide(repositoryPath) { git ->
        val question = Question(repositoryPath, startingCommits, commits)
        lastAnswer?.takeIf { it.first == question }?.let { return@provide it.second }

        withContext(Dispatchers.IO) {
            reachableCommits(currentCoroutineContext(), git.repository, startingCommits, commits)
        }.also { lastAnswer = question to it }
    }
}

/**
 * Which of [commits] the history of [startingCommits] has, newest commit first, stopping once it has found them all,
 * passed the oldest of them by [CLOCK_SKEW_SECONDS], or read [maxCommits]. A stash is usually made on a recent commit,
 * so that is a short walk. It blocks, so it runs on `Dispatchers.IO`.
 */
internal fun reachableCommits(
    context: CoroutineContext,
    repository: Repository,
    startingCommits: List<String>,
    commits: List<String>,
    maxCommits: Int = MAX_COMMITS_WALKED,
): Set<String> = RevWalk(repository).use { walk ->
    val wanted = commits.mapNotNullTo(HashSet()) { hash -> walk.parseOrNull(hash)?.let { it.name to it.commitTime } }
    if (wanted.isEmpty()) return@use emptySet()

    val oldest = wanted.minOf { it.second } - CLOCK_SKEW_SECONDS
    val left = wanted.mapTo(HashSet()) { it.first }
    val found = HashSet<String>()

    walk.sort(RevSort.COMMIT_TIME_DESC)

    for (hash in startingCommits) {
        walk.parseOrNull(hash)?.let { walk.markStart(it) }
    }

    var walked = 0

    while (left.isNotEmpty() && walked++ < maxCommits) {
        val commit = walk.next() ?: break

        context.ensureActive()

        if (left.remove(commit.name)) found += commit.name
        if (commit.commitTime < oldest) break
    }

    found
}

private fun RevWalk.parseOrNull(hash: String): RevCommit? = try {
    parseCommit(ObjectId.fromString(hash))
} catch (e: Exception) {
    printError(TAG, "Could not read commit $hash: ${e.message}", e)
    null
}
