// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.tabs

import dev.app.leaf.domain.worktrees.TabNames

/** How many colours a tab can have: the style's graph lanes, which every style sets. */
const val TAB_COLOR_COUNT = 6

/** The longest name that a tab keeps, in characters (code points). */
const val MAX_TAB_NAME_LENGTH = 100

/**
 * What the user set for a repository's tabs (fork-only, upstream issue 242): a [name] that they show in place of the
 * repository's folder, and a [color], the number of one of the style's graph lanes, from 1 to [TAB_COLOR_COUNT], so
 * that it follows the style. Every tab of the repository shows them, whichever worktree it has.
 */
data class TabLook(val name: String? = null, val color: Int? = null)

/**
 * The name to keep for [typed]: on one line, with runs of white space as one space, trimmed, and at most
 * [MAX_TAB_NAME_LENGTH] characters. None when that leaves nothing, or the repository's [folderName], which the tab
 * shows anyway.
 */
fun tabNameOf(typed: String?, folderName: String? = null): String? {
    if (typed == null) return null

    val oneLine = buildString {
        for (char in typed) {
            append(if (char.isISOControl() || char == LINE_SEPARATOR || char == PARAGRAPH_SEPARATOR) ' ' else char)
        }
    }

    val name = oneLine
        .replace(WHITE_SPACE, " ")
        .trim()
        .takeCodePoints(MAX_TAB_NAME_LENGTH)
        .trimEnd()

    return name.takeIf { it.isNotEmpty() && it != folderName }
}

/** [value] when it's the number of a tab colour, or else none. */
fun tabColorOf(value: Int?): Int? = value?.takeIf { it in 1..TAB_COLOR_COUNT }

/** The tab's title: the name that the user gave the repository, or else its folder's. */
fun TabNames.titleWith(look: TabLook?): String = look?.name ?: repository

private const val LINE_SEPARATOR = ' '
private const val PARAGRAPH_SEPARATOR = ' '
private val WHITE_SPACE = Regex("\\s+")

private fun String.takeCodePoints(count: Int): String {
    if (codePointCount(0, length) <= count) return this

    return substring(0, offsetByCodePoints(0, count))
}
