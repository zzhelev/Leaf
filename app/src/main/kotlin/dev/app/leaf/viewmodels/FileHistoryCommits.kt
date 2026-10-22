// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.models.Commit

/** Where [commit] is in a file's [commits], or -1 when it isn't there. */
fun positionOf(commits: List<Commit>, commit: Commit?): Int =
    if (commit == null) -1 else commits.indexOfFirst { it.hash == commit.hash }

/**
 * The commit [offset] rows below [selected] in a file's [commits], which are newest first, so a negative offset is a
 * newer commit. Null past either end. With no commit selected, or one that isn't in the list, the newest.
 */
fun adjacentCommit(commits: List<Commit>, selected: Commit?, offset: Int): Commit? {
    val index = positionOf(commits, selected)

    return if (index < 0) commits.firstOrNull() else commits.getOrNull(index + offset)
}

/**
 * Two versions of the file, compared (fork-only, `docs/fork/compare.md`, 7.3): the file at [a] and at [b], the older row
 * and the newer unless swapped. [before] was selected when the comparison started, which ending it selects again.
 */
data class FileVersions(val a: Commit, val b: Commit, val before: Commit)

/** What a ⌘-click on a row of file history does. */
sealed interface VersionPick {
    /** Selects one commit, as a plain click does. */
    data class Select(val commit: Commit) : VersionPick

    data class Compare(val versions: FileVersions) : VersionPick
}

/**
 * ⌘-click on [picked], as in the log: with another commit selected, the two compare, the older first; on A or B it
 * drops that side, and the other is selected as one commit; on another row, the row replaces B. With nothing else to
 * compare it with, it selects the row.
 */
fun versionPick(commits: List<Commit>, versions: FileVersions?, selected: Commit?, picked: Commit): VersionPick = when {
    versions == null && (selected == null || selected.hash == picked.hash) -> VersionPick.Select(picked)
    versions == null -> VersionPick.Compare(inListOrder(commits, selected!!, picked, before = selected))
    picked.hash == versions.a.hash -> VersionPick.Select(versions.b)
    picked.hash == versions.b.hash -> VersionPick.Select(versions.a)
    else -> VersionPick.Compare(inListOrder(commits, versions.a, picked, before = versions.before))
}

/** The two versions with the older first: the list is newest first, so the one further down is A. */
fun inListOrder(commits: List<Commit>, first: Commit, second: Commit, before: Commit): FileVersions =
    if (positionOf(commits, first) >= positionOf(commits, second)) {
        FileVersions(a = first, b = second, before = before)
    } else {
        FileVersions(a = second, b = first, before = before)
    }

/** How many of the file's commits apart the two versions are. */
fun FileVersions.distanceIn(commits: List<Commit>): Int =
    kotlin.math.abs(positionOf(commits, a) - positionOf(commits, b))
