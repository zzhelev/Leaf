// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.extensions.lowercaseContains
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.sorting.formatAge
import org.eclipse.jgit.lib.Constants
import java.io.File

/** Characters of a commit's hash shown for a detached worktree, as git abbreviates it. */
private const val SHORT_HASH_LENGTH = 7

/** What a worktree has checked out, as its row in the side panel says it. Branch names are short (`feature/x`). */
sealed interface WorktreeHeadLabel {
    data class OnBranch(val branch: String) : WorktreeHeadLabel
    data class Rebasing(val branch: String) : WorktreeHeadLabel
    data class Bisecting(val branch: String) : WorktreeHeadLabel
    data class Detached(val shortHash: String) : WorktreeHeadLabel

    /** The main "worktree" of a bare repository, which has no files. */
    data object Bare : WorktreeHeadLabel
}

/**
 * A worktree's row in the side panel.
 *
 * @param name the worktree's folder name, which git names it after.
 * @param ageLabel how old its checked out commit is, such as `3d`. Null when that's unknown.
 * @param idleLabel how long nothing changed in it, such as `3w`, when it shows as idle ([WorktreeInfo.idleSince]).
 */
data class WorktreeRow(
    val info: WorktreeInfo,
    val name: String,
    val head: WorktreeHeadLabel,
    val ageLabel: String?,
    val idleLabel: String? = null,
)

/** The rows of [list]'s worktrees in git's order, main one first, leaving out those that don't match [filter]. */
fun worktreeRows(list: WorktreeList, filter: String, nowMillis: Long): List<WorktreeRow> {
    return list.worktrees
        .filter { info -> info.matches(filter) }
        .map { info ->
            WorktreeRow(
                info = info,
                name = info.worktree.name,
                head = info.worktree.headLabel(),
                ageLabel = info.lastCommitTime?.let { formatAge(it, nowMillis) },
                idleLabel = info.idleSince?.let { formatAge(it, nowMillis) },
            )
        }
}

/**
 * The rows of the toolbar's worktree picker: every worktree, in git's order. Null when the repository has only one
 * worktree, so that repositories without linked worktrees don't get a picker.
 */
fun worktreePickerRows(list: WorktreeList, nowMillis: Long): List<WorktreeRow>? =
    worktreeRows(list, filter = "", nowMillis).takeIf { list.worktrees.size > 1 }

/** The worktree's folder name, which git names it after (`.claude/worktrees/x` is `x`). */
val Worktree.name: String
    get() = File(path).name

fun Worktree.headLabel(): WorktreeHeadLabel = when {
    isBare -> WorktreeHeadLabel.Bare
    branch != null -> WorktreeHeadLabel.OnBranch(branch.shortBranchName())
    rebasingBranch != null -> WorktreeHeadLabel.Rebasing(rebasingBranch.shortBranchName())
    bisectingBranch != null -> WorktreeHeadLabel.Bisecting(bisectingBranch.shortBranchName())
    else -> WorktreeHeadLabel.Detached(headSha.orEmpty().take(SHORT_HASH_LENGTH))
}

/**
 * The worktree's name as the branch list shows it next to [branchName] (`refs/heads/x`), or null when it would repeat
 * the branch: agents name a worktree's folder after the last part of its branch (`claude/x` in `.../worktrees/x`), or
 * name the branch `worktree-x`, as Claude Code's CLI and Gemini CLI do.
 */
fun Worktree.nameNextTo(branchName: String): String? {
    val lastPart = branchName.shortBranchName().substringAfterLast('/')

    return name.takeUnless { it == lastPart || "worktree-$it" == lastPart }
}

/** Whether the worktree's name, path, branches or origin's tag (`codex`) has [filter]. */
private fun WorktreeInfo.matches(filter: String): Boolean {
    return worktree.name.lowercaseContains(filter) ||
        worktree.path.lowercaseContains(filter) ||
        worktree.usedBranches.any { it.shortBranchName().lowercaseContains(filter) } ||
        origin?.label?.lowercaseContains(filter) == true
}

private fun String.shortBranchName() = removePrefix(Constants.R_HEADS)
