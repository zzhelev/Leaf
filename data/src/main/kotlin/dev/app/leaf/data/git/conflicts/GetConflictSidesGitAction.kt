// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetConflictSidesGitAction
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import javax.inject.Inject

private const val ABBREVIATED_LENGTH = 7

/** The branch that git's own merge message names: `Merge branch 'x'`, `Merge remote-tracking branch 'origin/x'`. */
private val MERGE_MESSAGE_BRANCH = Regex("""^Merge (?:remote-tracking )?branch '([^']+)'""")

/**
 * Reads what ours and theirs are from the files that git and JGit leave in the worktree's git dir while an operation is
 * stopped: `MERGE_HEAD`, `CHERRY_PICK_HEAD`, `REVERT_HEAD`, and for a rebase `REBASE_HEAD` (or JGit's
 * `rebase-merge/stopped-sha`) with the `onto` and `head-name` files of `rebase-merge/` or `rebase-apply/`.
 */
class GetConflictSidesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetConflictSidesGitAction {
    override suspend fun invoke(repositoryPath: String): Either<ConflictSides, GitError> =
        withContext(Dispatchers.IO) {
            jgit.provide(repositoryPath) { git -> conflictSides(git.repository) }
        }
}

internal fun conflictSides(repository: Repository): ConflictSides = RevWalk(repository).use { walk ->
    fun commit(id: ObjectId?, branch: String?): ConflictCommit? {
        id ?: return null
        val commit = walk.parseCommit(id)

        return ConflictCommit(branch, commit.name.take(ABBREVIATED_LENGTH), commit.shortMessage)
    }

    val head = repository.resolve(Constants.HEAD)
    val headBranch = repository.fullBranch
        ?.takeIf { it.startsWith(Constants.R_HEADS) }
        ?.let { Repository.shortenRefName(it) }
    val ours = commit(head, headBranch)

    when (repository.repositoryState) {
        RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED -> {
            val mergeHead = repository.readMergeHeads()?.firstOrNull()
            val branch = mergeHead?.let { repository.branchAt(it, except = headBranch) }
                ?: repository.readMergeCommitMsg()?.let { MERGE_MESSAGE_BRANCH.find(it)?.groupValues?.get(1) }

            ConflictSides(ConflictOperation.MERGE, ours, commit(mergeHead, branch))
        }

        RepositoryState.REBASING,
        RepositoryState.REBASING_REBASING,
        RepositoryState.REBASING_MERGE,
        RepositoryState.REBASING_INTERACTIVE -> {
            val rebaseDir = listOf("rebase-merge", "rebase-apply")
                .map { File(repository.directory, it) }
                .firstOrNull { it.isDirectory }
            val onto = rebaseDir?.readLine("onto")?.let { repository.resolve(it) }
            val rebasedBranch = rebaseDir?.readLine("head-name")
                ?.takeIf { it.startsWith(Constants.R_HEADS) }
                ?.let { Repository.shortenRefName(it) }
            val replayed = (File(repository.directory, "REBASE_HEAD").readLineOrNull()
                ?: rebaseDir?.readLine("stopped-sha"))
                ?.let { repository.resolve(it) }

            ConflictSides(
                ConflictOperation.REBASE,
                ours = commit(head, onto?.let { repository.branchAt(it, except = rebasedBranch) }),
                theirs = commit(replayed, rebasedBranch),
            )
        }

        RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED ->
            ConflictSides(ConflictOperation.CHERRY_PICK, ours, commit(repository.readCherryPickHead(), branch = null))

        RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED ->
            ConflictSides(ConflictOperation.REVERT, ours, commit(repository.readRevertHead(), branch = null))

        else -> ConflictSides(ConflictOperation.OTHER, ours, theirs = null)
    }
}

/**
 * The short name of a branch that points at [commit], other than [except]: a local one first, then a remote-tracking
 * one, each in name order. Null when none does.
 */
private fun Repository.branchAt(commit: ObjectId, except: String?): String? =
    listOf(Constants.R_HEADS, Constants.R_REMOTES).firstNotNullOfOrNull { prefix ->
        refDatabase.getRefsByPrefix(prefix)
            .filter { !it.isSymbolic && it.objectId == commit }
            .map { Repository.shortenRefName(it.name) }
            .filter { it != except }
            .minOrNull()
    }

private fun File.readLine(name: String): String? = File(this, name).readLineOrNull()

private fun File.readLineOrNull(): String? =
    takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
