// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.index

import dev.app.leaf.domain.worktrees.WorktreeStep

/**
 * How git wrote the index of the worktree in [workTree] that JGit can't read (fork-only). git writes these formats for
 * large repositories. JGit fails at the first entry it can't read ("Invalid path"), so nothing that reads the index
 * works in Leaf: status, staging, diffs of uncommitted files, blame, commits, checkouts, stashes, merges. git reads
 * them, and [conversionSteps] has it write the index back in the format that JGit reads.
 */
sealed interface UnsupportedIndex {
    val workTree: String

    /**
     * A split index (`git update-index --split-index`, or `core.splitIndex`): the index file holds the changes to a
     * shared index in `sharedindex.<id>`, with entries that have no path. The `link` extension.
     *
     * @param splitIndexConfigured `core.splitIndex` is true, so git would split the index again at its next write.
     */
    data class Split(override val workTree: String, val splitIndexConfigured: Boolean) : UnsupportedIndex

    /**
     * A sparse index (`index.sparse` with a cone-mode sparse checkout): a folder outside the sparse checkout is one
     * entry, `folder/`, in place of its files. The `sdir` extension.
     */
    data class Sparse(override val workTree: String) : UnsupportedIndex

    /**
     * An index extension that readers must understand, as its signature doesn't start with an uppercase letter, and
     * that JGit doesn't know. git has no command that drops it.
     */
    data class UnknownExtension(override val workTree: String, val extension: String) : UnsupportedIndex
}

/**
 * The git commands that convert the index to the format JGit reads, in the worktree. They keep the files, the staged
 * changes and the sparse checkout's patterns. Empty when git has no such command.
 */
fun UnsupportedIndex.conversionSteps(): List<WorktreeStep.Git> = when (this) {
    is UnsupportedIndex.Split -> buildList {
        // Otherwise git splits it again at its next write, and warns that the setting is on
        if (splitIndexConfigured) {
            add(WorktreeStep.Git(workTree, listOf("config", "core.splitIndex", "false")))
        }

        add(WorktreeStep.Git(workTree, listOf("update-index", "--no-split-index")))
    }

    // Keeps the patterns, and sets index.sparse to false in the worktree's config
    is UnsupportedIndex.Sparse -> listOf(
        WorktreeStep.Git(workTree, listOf("sparse-checkout", "reapply", "--no-sparse-index"))
    )

    is UnsupportedIndex.UnknownExtension -> emptyList()
}
