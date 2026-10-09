package dev.app.leaf.data.git.branches

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.config.WorktreeBaseBranchConfig
import dev.app.leaf.data.git.worktrees.worktreesUsing
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.domain.errors.RenameBranchError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IRenameBranchGitAction
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val TAG = "RenameBranchGitAction"

class RenameBranchGitAction @Inject constructor(
    private val jGitBranchMapper: JGitBranchMapper,
    private val jgit: JGit,
) : IRenameBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, oldName: String, newName: String) =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val oldRefName = if (oldName.startsWith(Constants.R_HEADS)) oldName else Constants.R_HEADS + oldName
            val oldBranch = Repository.shortenRefName(oldRefName)

            // JGit moves only this worktree's HEAD to the new name, so another worktree that has the branch checked out
            // would be left on a branch that doesn't exist. Like git, refuse while a worktree rebases the branch or
            // bisects from it, and move the HEAD of the others along.
            val worktrees = repository.worktreesUsing(oldRefName)

            for (worktree in worktrees) {
                when (worktree.use) {
                    WorktreeBranchUse.CheckedOut -> Unit
                    WorktreeBranchUse.Rebasing ->
                        raiseError(RenameBranchError.BranchRebasedInWorktree(oldBranch, worktree.path))

                    WorktreeBranchUse.Bisecting ->
                        raiseError(RenameBranchError.BranchBisectedInWorktree(oldBranch, worktree.path))
                }
            }

            val ref = git.branchRename()
                .setOldName(oldName)
                .setNewName(newName)
                .call()

            // The worktrees' base branch follows it, as git moves `branch.<name>` config along
            moveWorktreeBaseBranch(repository, oldRefName, ref.name)

            // As git's `replace_each_worktree_head_symref`, after the rename
            val currentGitDir = repository.directory.canonicalFile
            val notMoved = worktrees
                .filter { worktree -> worktree.gitDir != currentGitDir }
                .filterNot { worktree -> moveHead(worktree.gitDir, ref.name, repository.fs) }

            notMoved.firstOrNull()?.let { worktree ->
                raiseError(
                    RenameBranchError.WorktreeHeadNotMoved(
                        oldBranch = oldBranch,
                        newBranch = Repository.shortenRefName(ref.name),
                        worktreePath = worktree.path,
                    )
                )
            }

            checkNotNull(jGitBranchMapper.toDomain(ref)) { "Failed to map $ref to domain branch" }
        }
}

/**
 * Points the HEAD of the worktree whose git dir is [gitDir] to the branch [branchName]. False when it couldn't, for
 * example because another program holds HEAD's lock.
 *
 * It writes no reflog entry, which git does. With the tab's file system, JGit would write it to the wrong reflog: the
 * tab's worktree's, or the main worktree's for a tab on the main worktree (`LinkedWorktreeLogs` knows only the tab's
 * own git dir).
 */
private fun moveHead(gitDir: File, branchName: String, fs: FS): Boolean = try {
    FileRepositoryBuilder().setGitDir(gitDir).setFS(fs).setMustExist(true).build().use { worktree ->
        val update = worktree.updateRef(Constants.HEAD)
        update.disableRefLog()

        when (val result = update.link(branchName)) {
            RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE -> true
            else -> {
                printError(TAG, "Moving the HEAD of $gitDir to $branchName failed: $result")
                false
            }
        }
    }
} catch (ex: IOException) {
    printError(TAG, "Moving the HEAD of $gitDir to $branchName failed", ex)
    false
}

/**
 * Chooses [newName] as the worktrees' base branch if [oldName] was chosen. A failure is only logged: the branch is
 * already renamed, and the worktrees are compared to the automatic base until another branch is chosen.
 */
private fun moveWorktreeBaseBranch(repository: Repository, oldName: String, newName: String) {
    try {
        WorktreeBaseBranchConfig.renamed(repository, oldName, newName)
    } catch (ex: IOException) {
        printError(TAG, "Choosing $newName as the worktrees' base branch failed", ex)
    }
}
