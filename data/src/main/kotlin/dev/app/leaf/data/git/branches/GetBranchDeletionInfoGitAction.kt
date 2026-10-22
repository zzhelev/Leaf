// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.countCommitsOnlyOn
import dev.app.leaf.data.git.worktrees.mergeChangesNothing
import dev.app.leaf.data.git.worktrees.worktreeHeads
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.interfaces.IGetBranchDeletionInfoGitAction
import dev.app.leaf.domain.models.MergedIntoBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import javax.inject.Inject

private const val TAG = "GetBranchDeletionInfoGitAction"

/** `git merge-tree` runs at most this many at a time: a branch that isn't merged by ancestry may be squash-merged. */
private const val MAX_PARALLEL_MERGE_CHECKS = 4

class GetBranchDeletionInfoGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : IGetBranchDeletionInfoGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        base: String?,
        branches: List<String>,
    ) = jgit.provide(repositoryPath) { git ->
        withContext(Dispatchers.IO) {
            val repository = git.repository
            val existing = branches.distinct().mapNotNull { name ->
                repository.exactRef(name)?.takeIf { name.startsWith(Constants.R_HEADS) }?.let { name to it.objectId }
            }

            val heads = repository.worktreeHeads()
            val currentGitDir = repository.directory.canonicalFile
            val refusals = existing.associate { (name, _) ->
                name to heads.firstNotNullOfOrNull { head ->
                    head.useOf(name)?.let { use ->
                        BranchKept.UsedByWorktree(head.path, use, isHere = head.gitDir == currentGitDir)
                    }
                }
            }

            // The branches that confirming can delete: what's left of the refs is what keeps commits reachable
            val deletable = existing.map { it.first }.filter { refusals.getValue(it) == null }.toSet()
            val merged = mergedIntoBase(repository, File(repositoryPath), base, existing)

            existing.map { (name, tip) ->
                val commitsOnlyHere = when {
                    refusals.getValue(name) != null -> 0
                    // The base stays, so a branch in it has no commit of its own, unless the base goes too
                    merged[name] == MergedIntoBase.COMMITS && base !in deletable -> 0
                    else -> repository.countCommitsOnlyOn(name, alsoIgnoring = deletable)
                }

                BranchDeletionInfo(
                    branch = BranchToDelete(name, tip.name),
                    refusal = refusals.getValue(name),
                    merged = merged[name],
                    commitsOnlyHere = commitsOnlyHere,
                )
            }
        }
    }

    /**
     * How each branch got into [base], by its commits (JGit, in this process), or else by its changes
     * (`git merge-tree`, which tells a squash merge). Without a base, or when git can't tell, a branch isn't merged.
     */
    private suspend fun mergedIntoBase(
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
                async { semaphore.withPermit { name to gitCli.mergeChangesNothing(folder, base, name) } }
            }.awaitAll()
        }
            .filter { (_, changesNothing) -> changesNothing == true }
            .associate { (name, _) -> name to MergedIntoBase.CHANGES }

        return byCommits + byChanges
    }
}
