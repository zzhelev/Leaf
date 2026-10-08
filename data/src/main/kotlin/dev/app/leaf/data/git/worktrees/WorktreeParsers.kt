// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.models.AheadBehind
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeStatus

private const val NUL = '\u0000'

/**
 * Parses `git worktree list --porcelain -z`: one NUL-terminated `name [value]` attribute per field, and an empty field
 * after each worktree. [Worktree.isCurrent] is left false. Attributes added by newer git versions are ignored.
 */
internal fun parseWorktreeList(output: String): List<Worktree> {
    val records = mutableListOf<List<String>>()
    var attributes = mutableListOf<String>()

    for (field in output.split(NUL)) {
        if (field.isEmpty()) {
            if (attributes.isNotEmpty()) {
                records.add(attributes)
                attributes = mutableListOf()
            }
        } else {
            attributes.add(field)
        }
    }

    if (attributes.isNotEmpty()) {
        records.add(attributes)
    }

    return records.mapNotNull { it.toWorktree() }
        .mapIndexed { index, worktree -> worktree.copy(isMain = index == 0) }
}

private fun List<String>.toWorktree(): Worktree? {
    var path: String? = null
    var headSha: String? = null
    var branch: String? = null
    var isDetached = false
    var isBare = false
    var locked: String? = null
    var prunable: String? = null

    for (attribute in this) {
        val name = attribute.substringBefore(' ')
        val value = if (' ' in attribute) attribute.substringAfter(' ') else null

        when (name) {
            "worktree" -> path = value
            "HEAD" -> headSha = value
            "branch" -> branch = value
            "detached" -> isDetached = true
            "bare" -> isBare = true
            "locked" -> locked = value.orEmpty()
            "prunable" -> prunable = value.orEmpty()
        }
    }

    return Worktree(
        path = path ?: return null,
        headSha = headSha,
        branch = branch,
        isMain = false,
        isCurrent = false,
        isDetached = isDetached,
        isBare = isBare,
        locked = locked,
        prunable = prunable,
    )
}

/**
 * Parses `git status --porcelain=v2 --branch -z`. Ordinary (`1`) and renamed (`2`) entries count as staged and/or
 * unstaged from their `XY` code, `u` entries as conflicted and `?` entries as untracked.
 */
internal fun parseWorktreeStatus(output: String): WorktreeStatus {
    var staged = 0
    var unstaged = 0
    var untracked = 0
    var conflicted = 0
    var upstream: String? = null
    var upstreamAheadBehind: AheadBehind? = null

    val fields = output.split(NUL)
    var index = 0

    while (index < fields.size) {
        val field = fields[index]

        when {
            field.startsWith("# branch.upstream ") -> upstream = field.removePrefix("# branch.upstream ")
            field.startsWith("# branch.ab ") -> upstreamAheadBehind = parseBranchAheadBehind(field)
            (field.startsWith("1 ") || field.startsWith("2 ")) && field.length >= 4 -> {
                if (field[2] != '.') staged++
                if (field[3] != '.') unstaged++

                // A renamed or copied entry is followed by a field with its original path
                if (field.startsWith("2 ")) index++
            }

            field.startsWith("u ") -> conflicted++
            field.startsWith("? ") -> untracked++
        }

        index++
    }

    return WorktreeStatus(staged, unstaged, untracked, conflicted, upstream, upstreamAheadBehind)
}

/** Parses `# branch.ab +<ahead> -<behind>`. */
private fun parseBranchAheadBehind(field: String): AheadBehind? {
    val (ahead, behind) = field.removePrefix("# branch.ab ").split(' ').takeIf { it.size == 2 } ?: return null

    return AheadBehind(
        ahead = ahead.removePrefix("+").toIntOrNull() ?: return null,
        behind = behind.removePrefix("-").toIntOrNull() ?: return null,
    )
}

/** Parses `git rev-list --left-right --count <base>...<target>`, which prints `<behind>\t<ahead>`. */
internal fun parseLeftRightCount(output: String): AheadBehind? {
    val counts = output.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }

    if (counts.size != 2) {
        return null
    }

    return AheadBehind(ahead = counts[1], behind = counts[0])
}
