// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/** Where "Wrap body" wraps a message's body: where git's documentation wraps it. */
const val BODY_WRAP_COLUMN = 72

/** A list item's first line: its indent, its marker (`-`, `*`, `+`, `1.` or `1)`) and the spaces after it. */
private val LIST_ITEM = Regex("""^( *)([-*+]|\d{1,9}[.)])( +)\S""")

/** A fenced code block's fence, as Markdown writes it. */
private val FENCE = Regex("""^[ \t]*(```|~~~)""")

/**
 * [text] with the body's paragraphs that have a line past [column] characters wrapped at it (fork-only), as "Wrap body
 * at 72 columns" in the commit field's menu does. A paragraph's lines are joined and broken again between words, so it
 * doesn't end up with a short line after each long one. A word longer than [column], such as a URL, gets a line of its
 * own and isn't broken.
 *
 * Left as they are: what comes before the body (the subject's paragraph, which git tools take as the subject whole),
 * the last paragraph when it is trailers ([isTrailerLine]), lines that start with the comment prefix, indented lines and
 * fenced code, everything from git's scissors line on, and paragraphs whose lines fit. A list item (`- `, `* `, `+ `,
 * `1. `) is a paragraph of its own whose next lines line up after its marker.
 */
fun wrapBody(text: String, cleanup: MessageCleanup, column: Int = BODY_WRAP_COLUMN): String {
    val layout = wrapLayout(text, cleanup) ?: return text
    val lines = layout.lines
    val wrapped = ArrayList<String>(lines.size)

    lines.subList(0, layout.bodyStart).forEach(wrapped::add)

    for (part in layout.parts) {
        val partLines = lines.subList(part.start, part.end)
        val isLong = partLines.any { it.isLongerThan(column) }

        wrapped += when {
            part.kind == BodyPartKind.LIST_ITEM && isLong -> {
                val words = partLines.flatMap { it.words() }.drop(1)
                fill(words, partLines.first().substring(0, part.markerEnd), " ".repeat(part.markerEnd), column, layout.commentPrefix)
            }

            part.kind == BodyPartKind.PARAGRAPH && isLong ->
                fill(partLines.flatMap { it.words() }, firstPrefix = "", nextPrefix = "", column, layout.commentPrefix)

            else -> partLines
        }
    }

    lines.subList(layout.bodyEnd, lines.size).forEach(wrapped::add)

    return wrapped.joinToString("\n") + text.substring(layout.cut)
}

/** Whether "Wrap body" would change [text]. */
fun canWrapBody(text: String, cleanup: MessageCleanup, column: Int = BODY_WRAP_COLUMN): Boolean =
    wrapBody(text, cleanup, column) != text

/** Why "Wrap body" would change nothing, which its greyed-out menu item says (fork-only). */
enum class WrapBodyBlocker {
    /** No line that git keeps is longer than the column. */
    EVERY_LINE_FITS,

    /** The long lines are words longer than the column, which it never breaks, such as URLs. */
    NO_SPACES,

    /** The subject's line is the long one, which git tools take whole. */
    LONG_SUBJECT,

    /** The long lines run on from the subject without a blank line, so they are the subject's paragraph. */
    SUBJECT_RUNS_ON,

    /** The long lines are indented or in fenced code, which it leaves as they are. */
    INDENTED,

    /** The long lines are the message's trailers. */
    TRAILERS,
}

/**
 * Why [wrapBody] would leave [text] as it is, or null when it would wrap something: the reason of its first line that is
 * longer than [column], comments and what follows git's scissors line left out.
 */
fun wrapBodyBlocker(text: String, cleanup: MessageCleanup, column: Int = BODY_WRAP_COLUMN): WrapBodyBlocker? {
    if (canWrapBody(text, cleanup, column)) return null

    val layout = wrapLayout(text, cleanup) ?: return WrapBodyBlocker.EVERY_LINE_FITS
    val lines = layout.lines
    val firstLong = (layout.subjectStart until lines.size).firstOrNull { index ->
        !layout.isComment(lines[index]) && lines[index].isLongerThan(column)
    } ?: return WrapBodyBlocker.EVERY_LINE_FITS

    return when {
        firstLong < layout.bodyStart ->
            if (layout.bodyStart - layout.subjectStart > 1) WrapBodyBlocker.SUBJECT_RUNS_ON else WrapBodyBlocker.LONG_SUBJECT

        firstLong >= layout.bodyEnd -> WrapBodyBlocker.TRAILERS

        else -> when (layout.parts.first { firstLong >= it.start && firstLong < it.end }.kind) {
            BodyPartKind.CODE, BodyPartKind.INDENTED -> WrapBodyBlocker.INDENTED
            else -> WrapBodyBlocker.NO_SPACES
        }
    }
}

/** What a run of the body's lines is, which says whether [wrapBody] may wrap it. */
private enum class BodyPartKind { BLANK_OR_COMMENT, CODE, LIST_ITEM, INDENTED, PARAGRAPH }

/** Lines [start] to [end] (exclusive) of the body; a list item's text starts at [markerEnd]. */
private class BodyPart(val kind: BodyPartKind, val start: Int, val end: Int, val markerEnd: Int = 0)

/**
 * A message's lines up to git's scissors line ([cut]) as [wrapBody] reads them: the subject's paragraph from
 * [subjectStart], the body from [bodyStart] to the trailers at [bodyEnd], and the body's [parts].
 */
private class WrapLayout(
    val lines: List<String>,
    val cut: Int,
    val commentPrefix: String?,
    val subjectStart: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val parts: List<BodyPart>,
) {
    fun isComment(line: String) = commentPrefix != null && line.startsWith(commentPrefix)
}

/** [text]'s layout, or null when it has no line that git keeps. */
private fun wrapLayout(text: String, cleanup: MessageCleanup): WrapLayout? {
    val cut = scissorsCut(text, cleanup)
    val lines = text.substring(0, cut).split('\n')
    val commentPrefix = cleanup.commentPrefixFor(text)

    fun isComment(line: String) = commentPrefix != null && line.startsWith(commentPrefix)
    fun isBlank(line: String) = trimmedEnd(line, 0, line.length) == 0

    val subjectStart = lines.indexOfFirst { !isComment(it) && !isBlank(it) }

    if (subjectStart < 0) {
        return null
    }

    var bodyStart = subjectStart

    while (bodyStart < lines.size && !isComment(lines[bodyStart]) && !isBlank(lines[bodyStart])) {
        bodyStart++
    }

    val bodyEnd = trailerBlockStart(lines, bodyStart, ::isComment, ::isBlank)
    val parts = mutableListOf<BodyPart>()
    var index = bodyStart

    while (index < bodyEnd) {
        val line = lines[index]
        val listItem = LIST_ITEM.find(line)

        when {
            isBlank(line) || isComment(line) -> {
                parts += BodyPart(BodyPartKind.BLANK_OR_COMMENT, index, index + 1)
                index++
            }

            FENCE.containsMatchIn(line) -> {
                val fence = FENCE.find(line)!!.groupValues[1]
                var end = index + 1

                while (end < bodyEnd && !lines[end].trimStart().startsWith(fence)) {
                    end++
                }

                end = minOf(end + 1, bodyEnd)
                parts += BodyPart(BodyPartKind.CODE, index, end)
                index = end
            }

            listItem != null -> {
                val markerEnd = listItem.groups[3]!!.range.last + 1
                val hanging = " ".repeat(markerEnd)
                var end = index + 1

                while (end < bodyEnd && continuesListItem(lines[end], hanging, ::isComment, ::isBlank)) {
                    end++
                }

                parts += BodyPart(BodyPartKind.LIST_ITEM, index, end, markerEnd)
                index = end
            }

            line[0] == ' ' || line[0] == '\t' -> {
                parts += BodyPart(BodyPartKind.INDENTED, index, index + 1)
                index++
            }

            else -> {
                var end = index + 1

                while (end < bodyEnd && continuesParagraph(lines[end], ::isComment, ::isBlank)) {
                    end++
                }

                parts += BodyPart(BodyPartKind.PARAGRAPH, index, end)
                index = end
            }
        }
    }

    return WrapLayout(lines, cut, commentPrefix, subjectStart, bodyStart, bodyEnd, parts)
}

/**
 * Where an offset of [before] is in [after], which differs from it only in white space, as [wrapBody] leaves it: before
 * the same character that isn't white space, or after the last one when the offset is past them all.
 */
fun offsetAfterWrap(before: String, after: String, offset: Int): Int {
    val charactersBefore = (0 until offset.coerceIn(0, before.length)).count { !before[it].isWhitespace() }
    var seen = 0

    for (index in after.indices) {
        if (!after[index].isWhitespace()) {
            if (seen == charactersBefore) {
                return index
            }

            seen++
        }
    }

    return after.length
}

/**
 * Where the message's trailer block starts: its last paragraph, after [bodyStart], when that is all trailers, a line
 * that starts with white space continuing the one before (git's folded trailers). [lines]' size when there is none.
 * Unlike git, which also takes a paragraph that is mostly trailers, every line must be one.
 */
private fun trailerBlockStart(
    lines: List<String>,
    bodyStart: Int,
    isComment: (String) -> Boolean,
    isBlank: (String) -> Boolean,
): Int {
    var last = lines.lastIndex

    while (last >= bodyStart && (isComment(lines[last]) || isBlank(lines[last]))) {
        last--
    }

    if (last < bodyStart) {
        return lines.size
    }

    var first = last

    while (first > bodyStart && !isComment(lines[first - 1]) && !isBlank(lines[first - 1])) {
        first--
    }

    val paragraph = lines.subList(first, last + 1)
    val isTrailers = isTrailerLine(paragraph.first()) &&
        paragraph.all { isTrailerLine(it) || it.startsWith(" ") || it.startsWith("\t") }

    return if (isTrailers) first else lines.size
}

private fun continuesParagraph(line: String, isComment: (String) -> Boolean, isBlank: (String) -> Boolean): Boolean =
    !isBlank(line) && !isComment(line) && line[0] != ' ' && line[0] != '\t' &&
        !FENCE.containsMatchIn(line) && !LIST_ITEM.containsMatchIn(line)

/** A line that goes on with a list item: lined up after its marker, or not indented at all (Markdown's lazy lines). */
private fun continuesListItem(
    line: String,
    hanging: String,
    isComment: (String) -> Boolean,
    isBlank: (String) -> Boolean,
): Boolean {
    if (isBlank(line) || isComment(line) || FENCE.containsMatchIn(line) || LIST_ITEM.containsMatchIn(line)) {
        return false
    }

    val indent = line.length - line.trimStart(' ', '\t').length

    return indent == 0 || (indent == hanging.length && line.startsWith(hanging))
}

/** Whether the line, without its trailing white space, has more than [column] characters (code points). */
private fun String.isLongerThan(column: Int): Boolean {
    val end = trimmedEnd(this, 0, length)

    return codePointCount(0, end) > column
}

private fun String.words(): List<String> = split(' ', '\t').filter { it.isNotEmpty() }

/**
 * Whether a line may start with [word]: not when it would make the line something else, a comment that git leaves out
 * (`Fixes #123` would lose `#123`), a list item or a fence.
 */
private fun canStartLine(word: String, commentPrefix: String?, nextPrefix: String): Boolean {
    val line = nextPrefix + word

    return !(commentPrefix != null && line.startsWith(commentPrefix)) &&
        !LIST_ITEM.containsMatchIn("$word x") && !FENCE.containsMatchIn(word)
}

/**
 * [words] in lines of at most [column] characters where they fit, the first after [firstPrefix], the others after
 * [nextPrefix]. A word that can't start a line ([canStartLine]) stays on the line before, past [column].
 */
private fun fill(
    words: List<String>,
    firstPrefix: String,
    nextPrefix: String,
    column: Int,
    commentPrefix: String?,
): List<String> {
    val lines = mutableListOf<String>()
    val line = StringBuilder(firstPrefix)
    var length = firstPrefix.codePointCount(0, firstPrefix.length)
    var hasWord = false

    for (word in words) {
        val wordLength = word.codePointCount(0, word.length)

        when {
            !hasWord -> {
                line.append(word)
                length += wordLength
                hasWord = true
            }

            length + 1 + wordLength <= column || !canStartLine(word, commentPrefix, nextPrefix) -> {
                line.append(' ').append(word)
                length += 1 + wordLength
            }

            else -> {
                lines += line.toString()
                line.setLength(0)
                line.append(nextPrefix).append(word)
                length = nextPrefix.length + wordLength
            }
        }
    }

    lines += line.toString()

    return lines
}
