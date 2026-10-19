// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import dev.app.leaf.domain.sorting.naturalCompare

/**
 * The branches and tags that a remote has, as short names in natural order (`feature/x`, `v1.0`), and the branch that
 * its HEAD points to. What the Clone dialog's "Choose…" lists.
 */
data class RemoteRefs(
    /** The remote's default branch, one of [branches], or null when HEAD points to none (an empty repository). */
    val defaultBranch: String?,
    val branches: List<String>,
    val tags: List<String>,
)

private const val HEADS = "refs/heads/"
private const val TAGS = "refs/tags/"
private const val PEELED = "^{}"

/**
 * Sorts out the full ref names that a remote advertises: [headTarget] is the ref that its HEAD points to
 * (`refs/heads/main`), if it says. Anything but branches and tags is left out, and so are the `^{}` lines that name
 * what an annotated tag points to, as the tag has a line of its own.
 */
fun remoteRefsOf(headTarget: String?, refNames: Iterable<String>): RemoteRefs {
    val branches = mutableSetOf<String>()
    val tags = mutableSetOf<String>()

    for (name in refNames) {
        when {
            name.endsWith(PEELED) -> {}
            name.startsWith(HEADS) -> branches += name.removePrefix(HEADS)
            name.startsWith(TAGS) -> tags += name.removePrefix(TAGS)
        }
    }

    val defaultBranch = headTarget?.takeIf { it.startsWith(HEADS) }?.removePrefix(HEADS)?.takeIf { it in branches }

    return RemoteRefs(
        defaultBranch = defaultBranch,
        branches = branches.sortedWith(::naturalCompare),
        tags = tags.sortedWith(::naturalCompare),
    )
}
