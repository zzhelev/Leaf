package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.exceptions.UncommittedChangesDetectedException
import dev.app.leaf.domain.interfaces.IRebaseBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.ObjectId
import javax.inject.Inject

class RebaseBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : IRebaseBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch) = jgit.provide(repositoryPath) { git ->
        val rebaseBranch: ObjectId =
            git.repository.resolve(branch.name) ?: throw Exception("Branch ${branch.name} not found")

        val rebaseResult = git.rebase()
            .setOperation(RebaseCommand.Operation.BEGIN)
            .setUpstream(rebaseBranch)
            .call()

        rebaseHasStopped(rebaseResult)
    }
}

/**
 * Whether the rebase stopped at conflicts, which the user then resolves (fork-only). A rebase that didn't happen
 * throws: JGit returns CONFLICTS when local files stop the checkout, and FAILED when they stop a commit, and in both
 * cases puts the branch back as it was. Before, CONFLICTS counted as a rebase with conflicts, and FAILED and ABORTED
 * as one that completed.
 */
internal fun rebaseHasStopped(result: RebaseResult): Boolean = when (result.status) {
    RebaseResult.Status.STOPPED, RebaseResult.Status.STASH_APPLY_CONFLICTS -> true

    RebaseResult.Status.UNCOMMITTED_CHANGES ->
        throw UncommittedChangesDetectedException("Rebase failed, the repository contains uncommitted changes.")

    RebaseResult.Status.CONFLICTS -> throw RebaseWouldOverwriteException(result.conflicts.orEmpty())
    RebaseResult.Status.FAILED -> throw RebaseWouldOverwriteException(result.failingPaths.orEmpty().keys)
    RebaseResult.Status.ABORTED -> throw Exception("The rebase was aborted, and your branch is as it was.")

    else -> if (result.status.isSuccessful) false else throw Exception("Rebase failed: ${result.status}")
}

/** A rebase that didn't happen, as it would overwrite the local changes to [paths] (fork-only). */
class RebaseWouldOverwriteException(paths: Collection<String>) : Exception(
    "The rebase didn't happen, as it would overwrite your changes to ${paths.sorted().joinToString(", ")}. " +
        "Commit or stash them, then rebase again."
)
