// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetMergedIntoBaseGitAction
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.worktrees.BranchReflogEntry
import dev.app.leaf.domain.worktrees.commitsOfItsOwn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The most commits of the branch's own that are looked for in it, newest first. Only commits that a reset or a rebase
 * left behind are looked for in vain, and each such look walks back to where the branch and the commit met.
 */
private const val MAX_OWN_COMMITS_CHECKED = 50

class GetMergedIntoBaseGitAction @Inject constructor(
    private val gitCli: GitCli,
    private val jgit: JGit,
) : IGetMergedIntoBaseGitAction {
    /** Whether merging a branch's commit into a base's would change nothing, by (base, branch): that can't change. */
    private val changesInBase = ConcurrentHashMap<Pair<ObjectId, ObjectId>, Boolean>()

    /** Whether a commit is in a branch's, by (commit, branch's commit): that can't change either. */
    private val commitInBranch = ConcurrentHashMap<Pair<ObjectId, ObjectId>, Boolean>()

    override suspend operator fun invoke(
        repositoryPath: String,
        base: String,
        branch: String,
        commitsNotInBase: Int,
        startCommit: String?,
    ): Either<MergedIntoBase?, GitError> = either {
        if (commitsNotInBase > 0) {
            val inBase = changesInBase(repositoryPath, base, branch).bind()

            Either.Ok(MergedIntoBase.CHANGES.takeIf { inBase })
        } else {
            val hadWork = jgit.provide(repositoryPath) { git ->
                withContext(Dispatchers.IO) { git.repository.hadWorkOfItsOwn(branch, startCommit) }
            }.bind()

            Either.Ok(MergedIntoBase.COMMITS.takeIf { hadWork })
        }
    }

    /** Whether merging [branch] into [base] would change nothing, as after a squash merge. False when git can't tell. */
    private suspend fun changesInBase(repositoryPath: String, base: String, branch: String): Either<Boolean, GitError> =
        either {
            val ids = jgit.provide(repositoryPath) { git ->
                val repository = git.repository
                listOf(repository.exactRef(base)?.objectId, repository.exactRef(branch)?.objectId)
            }.bind()

            val baseId = ids[0] ?: return@either Either.Ok(false)
            val branchId = ids[1] ?: return@either Either.Ok(false)
            val key = baseId to branchId

            changesInBase[key]?.let { return@either Either.Ok(it) }

            // Ids rather than names: the answer is kept for these commits
            val inBase = gitCli.mergeChangesNothing(File(repositoryPath), baseId.name, branchId.name)
                ?: return@either Either.Ok(false)

            changesInBase[key] = inBase

            Either.Ok(inBase)
        }

    /**
     * Whether [branch], which has no commits that the base doesn't, had work of its own that it still has, which is then
     * in the base: a commit that its reflog says it got as its own ([commitsOfItsOwn]) is in it. Without a reflog, only
     * the tool's record of where the worktree started ([startCommit]) tells that the branch moved, which a fresh
     * worktree's doesn't.
     */
    private fun Repository.hadWorkOfItsOwn(branch: String, startCommit: String?): Boolean {
        val tipId = exactRef(branch)?.objectId ?: return false
        val entries = refDatabase.getReflogReader(branch)?.reverseEntries.orEmpty()

        if (entries.isEmpty()) {
            return startCommit != null && ObjectId.isId(startCommit) && ObjectId.fromString(startCommit) != tipId
        }

        val ownCommits = commitsOfItsOwn(entries.map { BranchReflogEntry(it.newId.name, it.comment) })
            .distinct()
            .take(MAX_OWN_COMMITS_CHECKED)
            .map(ObjectId::fromString)

        return RevWalk(this).use { walk ->
            val tip = walk.parseCommit(tipId)

            ownCommits.any { id -> walk.isIn(id, tip) }
        }
    }

    private fun RevWalk.isIn(id: ObjectId, tip: RevCommit): Boolean {
        val key = id to tip.toObjectId()

        commitInBranch[key]?.let { return it }

        val isIn = try {
            isMergedInto(parseCommit(id), tip)
        } catch (ex: MissingObjectException) {
            false
        } catch (ex: IncorrectObjectTypeException) {
            false
        }

        commitInBranch[key] = isIn

        return isIn
    }
}
