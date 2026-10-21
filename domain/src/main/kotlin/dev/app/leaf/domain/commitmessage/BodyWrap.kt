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
    val cut = scissorsCut(text, cleanup)
    val lines = text.substring(0, cut).split('\n')
    val commentPrefix = cleanup.commentPrefixFor(text)

    fun isComment(line: String) = commentPrefix != null && line.startsWith(commentPrefix)
    fun isBlank(line: String) = trimmedEnd(line, 0, line.length) == 0

    val subjectStart = lines.indexOfFirst { !isComment(it) && !isBlank(it) }

    if (subjectStart < 0) {
        return text
    }

    var bodyStart = subjectStart

    while (bodyStart < lines.size && !isComment(lines[bodyStart]) && !isBlank(lines[bodyStart])) {
        bodyStart++
    }

    val bodyEnd = trailerBlockStart(lines, bodyStart, ::isComment, ::isBlank)
    val wrapped = ArrayList<String>(lines.size)

    lines.subList(0, bodyStart).forEach(wrapped::add)

    var index = bodyStart

    while (index < bodyEnd) {
        val line = lines[index]
        val listItem = LIST_ITEM.find(line)

        when {
            isBlank(line) || isComment(line) -> {
                wrapped += line
                index++
            }

            FENCE.containsMatchIn(line) -> {
                val fence = FENCE.find(line)!!.groupValues[1]
                var end = index + 1

                while (end < bodyEnd && !lines[end].trimStart().startsWith(fence)) {
                    end++
                }

                end = minOf(end + 1, bodyEnd)
                lines.subList(index, end).forEach(wrapped::add)
                index = end
            }

            listItem != null -> {
                val markerEnd = listItem.groups[3]!!.range.last + 1
                val hanging = " ".repeat(markerEnd)
                var end = index + 1

                while (end < bodyEnd && continuesListItem(lines[end], hanging, ::isComment, ::isBlank)) {
                    end++
                }

                val item = lines.subList(index, end)
                wrapped += if (item.any { it.isLongerThan(column) }) {
                    val words = item.flatMap { it.words() }.drop(1)
                    fill(words, line.substring(0, markerEnd), hanging, column, commentPrefix)
                } else {
                    item
                }
                index = end
            }

            line[0] == ' ' || line[0] == '\t' -> {
                wrapped += line
                index++
            }

            else -> {
                var end = index + 1

                while (end < bodyEnd && continuesParagraph(lines[end], ::isComment, ::isBlank)) {
                    end++
                }

                val paragraph = lines.subList(index, end)
                wrapped += if (paragraph.any { it.isLongerThan(column) }) {
                    fill(paragraph.flatMap { it.words() }, firstPrefix = "", nextPrefix = "", column, commentPrefix)
                } else {
                    paragraph
                }
                index = end
            }
        }
    }

    lines.subList(bodyEnd, lines.size).forEach(wrapped::add)

    return wrapped.joinToString("\n") + text.substring(cut)
}

/** Whether "Wrap body" would change [text]. */
fun canWrapBody(text: String, cleanup: MessageCleanup, column: Int = BODY_WRAP_COLUMN): Boolean =
    wrapBody(text, cleanup, column) != text

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
