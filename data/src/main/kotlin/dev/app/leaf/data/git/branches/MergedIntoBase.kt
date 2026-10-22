// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.worktrees.mergeChangesNothing
import dev.app.leaf.domain.models.MergedIntoBase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

private const val TAG = "MergedIntoBase"

/** `git merge-tree` runs at most this many at a time: a branch that isn't merged by ancestry may be squash-merged. */
private const val MAX_PARALLEL_MERGE_CHECKS = 4

/**
 * How each of [branches] (full names and their tips) got into [base], by its commits (JGit, in this process), or else
 * by its changes (`git merge-tree`, run in [folder], which tells a squash merge), for the "Delete branches" dialog
 * (fork-only, upstream issue 116). Without a base, or when git can't tell, a branch isn't merged, and isn't in the map.
 */
internal suspend fun GitCli.mergedIntoBase(
    repository: Repository,
    folder: File,
    base: String?,
    branches: List<Pair<String, ObjectId>>,
): Map<String, MergedIntoBase> {
    val baseTip = base?.let { repository.exactRef(it)?.objectId } ?: return emptyMap()

    val byCommits = mutableMapOf<String, MergedIntoBase>()
    val rest = mutableListOf<String>()

    RevWalk(repository).use { walk ->
        val baseCommit = try {
            walk.parseCommit(baseTip)
        } catch (e: Exception) {
            printError(TAG, "Could not read the base $base: ${e.message}")
            return emptyMap()
        }

        for ((name, tip) in branches) {
            val isMerged = try {
                walk.isMergedInto(walk.parseCommit(tip), baseCommit)
            } catch (e: Exception) {
                printError(TAG, "Could not tell whether $name is merged into $base: ${e.message}")
                false
            }

            if (isMerged) byCommits[name] = MergedIntoBase.COMMITS else rest += name
        }
    }

    val semaphore = Semaphore(MAX_PARALLEL_MERGE_CHECKS)
    val byChanges = coroutineScope {
        rest.map { name ->
            async { semaphore.withPermit { name to mergeChangesNothing(folder, base, name) } }
        }.awaitAll()
    }
        .filter { (_, changesNothing) -> changesNothing == true }
        .associate { (name, _) -> name to MergedIntoBase.CHANGES }

    return byCommits + byChanges
}
