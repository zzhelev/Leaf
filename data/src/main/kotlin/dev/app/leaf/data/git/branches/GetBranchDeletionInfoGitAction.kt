// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.countCommitsOnlyOn
import dev.app.leaf.data.git.worktrees.worktreeHeads
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.interfaces.IGetBranchDeletionInfoGitAction
import dev.app.leaf.domain.models.MergedIntoBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import java.io.File
import javax.inject.Inject

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
            val merged = gitCli.mergedIntoBase(repository, File(repositoryPath), base, existing)

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
}
