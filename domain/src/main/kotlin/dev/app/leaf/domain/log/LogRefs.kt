// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.log

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.sorting.naturalCompare

/** A chip on a commit's row in the log (fork-only). */
sealed interface LogRef {
    /** Identifies the chip among the row's chips. */
    val key: String

    /**
     * A local branch. [remotes] are the remote branches of the same name on the same commit, which share its chip, as
     * in IntelliJ, Git Graph and Fork: usually the branch's upstream, up to date with it.
     */
    data class LocalBranch(val branch: Branch, val remotes: List<Branch> = emptyList()) : LogRef {
        override val key: String get() = branch.name
    }

    data class RemoteBranch(val branch: Branch) : LogRef {
        override val key: String get() = branch.name
    }

    data class TagRef(val tag: Tag) : LogRef {
        override val key: String get() = tag.name
    }
}

/**
 * A commit's chips in the order its row shows them: the branch checked out in the tab's worktree (or HEAD, when it's
 * detached), local branches that another worktree uses, other local branches, remote branches, then tags. Within each
 * group, by name. IntelliJ and Git Graph also put the current branch first and tags last.
 *
 * With [mergeRemotes], a remote branch shares the chip of the local branch of the same name, when the commit has one.
 * Without it, every ref has a chip of its own, as in the list of all of them.
 */
fun logRefs(
    branches: List<Branch>,
    tags: List<Tag>,
    currentBranch: Branch?,
    isUsedByOtherWorktree: (Branch) -> Boolean,
    mergeRemotes: Boolean,
): List<LogRef> {
    val byName = Comparator<Branch> { a, b -> naturalCompare(a.name, b.name) }
    val (locals, remotes) = branches.partition { it.isLocal }

    val remotesByLocal = if (mergeRemotes) {
        locals.associateWith { local ->
            remotes.filter { it.simpleName == local.simpleName }.sortedWith(byName)
        }
    } else {
        emptyMap()
    }

    val mergedRemotes = remotesByLocal.values.flatten().toSet()

    fun localGroup(branch: Branch) = when {
        branch.isSameBranch(currentBranch) -> 0
        isUsedByOtherWorktree(branch) -> 1
        else -> 2
    }

    val localRefs = locals
        .sortedWith(compareBy<Branch> { localGroup(it) }.then(byName))
        .map { LogRef.LocalBranch(it, remotesByLocal[it].orEmpty()) }

    val remoteRefs = remotes
        .filter { it !in mergedRemotes }
        .sortedWith(byName)
        .map { LogRef.RemoteBranch(it) }

    val tagRefs = tags
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .map { LogRef.TagRef(it) }

    return localRefs + remoteRefs + tagRefs
}

/**
 * Which of a row's chips the row shows: the first [shown], the first of them cut to [cutFirstTo] when that's not null,
 * and a +N chip for the rest.
 */
data class ChipsFit(val shown: Int, val cutFirstTo: Int? = null)

/**
 * Lays out a row's chips, [widths] wide in their order. They take at most [share], next to the +N chip
 * ([overflowWidth] wide) that stands for the ones that don't fit.
 *
 * When not even the first one fits that way, the first one still shows whole, or cut down, in [firstChipRoom], next to
 * +N when there are others: it's the most important (the current branch, when the commit has it). Below [minChipWidth]
 * it would hardly show its name, and +N stands for every ref.
 */
fun fitChips(
    widths: List<Int>,
    share: Int,
    firstChipRoom: Int,
    overflowWidth: Int,
    minChipWidth: Int,
): ChipsFit {
    if (widths.sum() <= share) return ChipsFit(widths.size)

    var used = 0

    for ((index, width) in widths.withIndex()) {
        if (used + width + overflowWidth > share) {
            if (index > 0) return ChipsFit(index)
            break
        }

        used += width
    }

    val room = firstChipRoom - if (widths.size > 1) overflowWidth else 0

    return when {
        widths.first() <= room -> ChipsFit(1)
        room >= minChipWidth -> ChipsFit(1, cutFirstTo = room)
        else -> ChipsFit(0)
    }
}
