// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.compare

import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.domain.sorting.NaturalOrder
import org.eclipse.jgit.lib.Constants

/** What a row of the list that picks a comparison's side is (`docs/fork/compare.md`, 7.1), in the list's order. */
enum class RefKind { COMMIT, BRANCH, TAG, REMOTE_BRANCH }

/**
 * A row of the list that picks a comparison's side: the side it would be, how the list names it, and where the typed
 * text is in [name], for the row to mark ([matchLength] is 0 when nothing was typed). [detail] is a commit's subject.
 */
data class RefChoice(
    val side: CompareSide,
    val name: String,
    val kind: RefKind,
    val matchStart: Int,
    val matchLength: Int,
    val detail: String? = null,
)

/** The list's rows, in groups by kind, and how many refs there are, matching or not. */
data class RefChoices(
    val groups: List<RefChoiceGroup>,
    val total: Int,
) {
    val rows: List<RefChoice> get() = groups.flatMap { it.choices }
}

data class RefChoiceGroup(val kind: RefKind, val choices: List<RefChoice>)

/**
 * The refs that match [text] (trimmed, without regard to case, anywhere in the short name), as the clone dialog's list
 * filters a remote's: local branches, then tags, newest version first, then remote branches, from the tab's own data,
 * so nothing is asked of a remote. [resolved] is what git made of the text as a revision (a hash, `HEAD~2`): a row of
 * its own on top, unless the text is a listed ref's name, which its own row has.
 */
fun refChoicesFor(
    text: String,
    branches: List<Branch>,
    remoteBranches: List<Branch>,
    tags: List<Tag>,
    resolved: Commit?,
): RefChoices {
    val typed = text.trim()

    fun choice(side: CompareSide, kind: RefKind): RefChoice? {
        val name = side.name
        val start = if (typed.isEmpty()) 0 else name.indexOf(typed, ignoreCase = true)

        return if (start < 0) null else RefChoice(side, name, kind, start, typed.length)
    }

    // A detached HEAD is in the branch list, by the name HEAD: it isn't a branch to follow
    val branchSides = branches
        .filter { it.name != Constants.HEAD }
        .map { CompareSide.Branch(it.name, it.hash) }
        .sortedWith(compareBy(NaturalOrder) { it.name })
    val tagSides = tags
        .map { CompareSide.Commit(it.commitHash, tag = it.name) }
        .sortedWith(compareBy(NaturalOrder) { it.name })
        .reversed()
    val remoteSides = remoteBranches
        .map { CompareSide.Branch(it.name, it.hash) }
        .sortedWith(compareBy(NaturalOrder) { it.name })

    val listedNames = (branchSides + tagSides + remoteSides).map { it.name }.toSet()
    val commit = resolved
        ?.takeIf { typed.isNotEmpty() && typed !in listedNames }
        ?.let { RefChoice(CompareSide.Commit(it.hash), it.shortHash, RefKind.COMMIT, 0, 0, detail = it.shortMessage) }

    val groups = listOf(
        RefChoiceGroup(RefKind.COMMIT, listOfNotNull(commit)),
        RefChoiceGroup(RefKind.BRANCH, branchSides.mapNotNull { choice(it, RefKind.BRANCH) }),
        RefChoiceGroup(RefKind.TAG, tagSides.mapNotNull { choice(it, RefKind.TAG) }),
        RefChoiceGroup(RefKind.REMOTE_BRANCH, remoteSides.mapNotNull { choice(it, RefKind.REMOTE_BRANCH) }),
    ).filter { it.choices.isNotEmpty() }

    return RefChoices(groups, total = branchSides.size + tagSides.size + remoteSides.size)
}

/**
 * Whether [text] could be a revision that git resolves to a commit (a hash, `HEAD~2`, `main^`), so that it's worth
 * asking: something typed, on one line, without spaces.
 */
fun looksLikeRevision(text: String): Boolean {
    val typed = text.trim()

    return typed.isNotEmpty() && typed.none { it.isWhitespace() }
}
