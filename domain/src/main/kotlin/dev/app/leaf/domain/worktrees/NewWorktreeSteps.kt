// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.NewWorktreeContext
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.Worktree
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File

/** The branch of a new worktree. */
sealed interface NewWorktreeBranch {
    val name: String

    /** Checks out the local branch [name], which no worktree uses. */
    data class CheckOut(override val name: String) : NewWorktreeBranch

    /** Creates the branch [name] at the start point. */
    data class Create(override val name: String) : NewWorktreeBranch
}

/** Whether a new branch tracks its start point, which git decides by itself unless it's told. */
enum class NewWorktreeTracking {
    /** git's default (`branch.autoSetupMerge`): a branch made from a remote branch tracks it. */
    GIT_DEFAULT,

    /** `--track`, for a branch made from a remote branch picked in a menu. */
    TRACK,

    /** `--no-track`, for an agent's branch made from a remote base branch, which it doesn't push to. */
    NO_TRACK,
}

/**
 * A worktree to create (fork-only), as its dialog describes it.
 *
 * @param startPoint git's name for what it starts from (`main`, `origin/main`, a commit's hash). Not used when
 * checking out an existing branch.
 * @param copyFiles gitignored files to copy from the main worktree, relative to it; empty for none.
 */
data class NewWorktreeRequest(
    val mainWorktree: Worktree,
    val startPoint: String,
    val tracking: NewWorktreeTracking,
    val branch: NewWorktreeBranch,
    val folder: String,
    val copyFiles: List<String>,
    val switchTab: Boolean,
)

/**
 * The steps that create the worktree: `git worktree add`, run in the main worktree, then copying the gitignored files
 * from the main worktree, then switching the tab to it.
 */
fun NewWorktreeRequest.steps(): List<WorktreeStep> = buildList {
    val gitFolder = mainWorktree.path
    val path = pathArgument(folder, gitFolder)

    val args = when (branch) {
        is NewWorktreeBranch.CheckOut -> listOf("worktree", "add", path, branch.name)
        is NewWorktreeBranch.Create -> {
            val trackingArgs = when (tracking) {
                NewWorktreeTracking.GIT_DEFAULT -> emptyList()
                NewWorktreeTracking.TRACK -> listOf("--track")
                NewWorktreeTracking.NO_TRACK -> listOf("--no-track")
            }

            listOf("worktree", "add") + trackingArgs + listOf("-b", branch.name, path, startPoint)
        }
    }

    add(WorktreeStep.Git(gitFolder, args))

    if (copyFiles.isNotEmpty()) {
        add(WorktreeStep.CopyFiles(mainWorktree.path, folder, copyFiles))
    }

    if (switchTab) {
        add(WorktreeStep.SwitchTab(folder))
    }
}

/**
 * The folder for a new worktree on [branch]: `<repository>-<branch>`, with each `/` of the branch as `-`, next to the
 * main worktree, or in [parent] when the repository has one for its worktrees. `leaf` and `fix/auth` give
 * `../leaf-fix-auth`.
 */
fun newWorktreeFolder(mainWorktree: Worktree, parent: String?, branch: String): String {
    val main = File(mainWorktree.path)
    val name = main.name.removeSuffix(".git") + "-" + branch.trim().replace('/', '-')
    val folder = parent?.let { File(it) } ?: main.absoluteFile.parentFile ?: main

    return File(folder, name).path
}

/**
 * What a new worktree starts from, as git names it.
 *
 * @param label what the dialog says it starts from: a branch's name, or a commit's short hash.
 * @param branchToCheckOut the local branch that the worktree can check out, when it was created from one that no
 * worktree uses. Null otherwise: the worktree gets a new branch.
 * @param suggestedName the new branch's name to start with: a remote branch's name, or none.
 */
data class NewWorktreeStartPoint(
    val label: String,
    val startPoint: String,
    val tracking: NewWorktreeTracking,
    val branchToCheckOut: String? = null,
    val suggestedName: String = "",
)

private const val SHORT_HASH = 7

/**
 * Where [start] puts the new worktree in this repository. The base branch, or the tab's branch without one; a remote
 * base gets `--no-track`, as an agent's branch doesn't push to it. A remote branch picked in a menu gets `--track`.
 */
fun NewWorktreeStart.startPoint(context: NewWorktreeContext): NewWorktreeStartPoint = when (this) {
    NewWorktreeStart.Base -> {
        val base = context.baseBranch
        val current = context.currentBranch

        when {
            base != null -> {
                val name = Repository.shortenRefName(base)
                val tracking = if (base.startsWith(Constants.R_REMOTES)) {
                    NewWorktreeTracking.NO_TRACK
                } else {
                    NewWorktreeTracking.GIT_DEFAULT
                }

                NewWorktreeStartPoint(label = name, startPoint = name, tracking = tracking)
            }

            current != null -> NewWorktreeStartPoint(current.simpleName, current.simpleName, NewWorktreeTracking.GIT_DEFAULT)
            else -> {
                val commit = context.currentCommit ?: Constants.HEAD
                NewWorktreeStartPoint(commit.take(SHORT_HASH), commit, NewWorktreeTracking.GIT_DEFAULT)
            }
        }
    }

    is NewWorktreeStart.LocalBranch -> NewWorktreeStartPoint(
        label = branch.simpleName,
        startPoint = branch.simpleName,
        tracking = NewWorktreeTracking.GIT_DEFAULT,
        branchToCheckOut = branch.simpleName.takeIf { branch.name !in context.branchesInUse },
    )

    is NewWorktreeStart.RemoteBranch -> NewWorktreeStartPoint(
        label = branch.simpleNameWithRemote,
        startPoint = branch.simpleNameWithRemote,
        tracking = NewWorktreeTracking.TRACK,
        suggestedName = branch.simpleName,
    )

    is NewWorktreeStart.Commit -> NewWorktreeStartPoint(
        label = commit.hash.take(SHORT_HASH),
        startPoint = commit.hash,
        tracking = NewWorktreeTracking.GIT_DEFAULT,
    )
}
