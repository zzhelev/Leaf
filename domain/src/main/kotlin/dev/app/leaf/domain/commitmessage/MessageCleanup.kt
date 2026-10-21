// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/** How git cleans a commit message up before it commits it (`commit.cleanup`). */
enum class CleanupMode {
    /** Whitespace as [WHITESPACE] does, and the lines that start with the comment prefix left out. */
    STRIP,

    /** Trailing white space removed, blank lines at the start and the end left out, runs of them made one. */
    WHITESPACE,

    /** The message as it is. */
    VERBATIM,

    /** [WHITESPACE], after cutting the message at git's scissors line. Comment lines stay. */
    SCISSORS,
}

/**
 * What git does to a message typed in Leaf's commit field (fork-only): the repository's `commit.cleanup`, and its
 * `core.commentChar` or `core.commentString` as [commentPrefix]. git cleans a message that its editor showed with
 * [CleanupMode.STRIP] unless `commit.cleanup` says otherwise, and Leaf's field counts as that editor: Leaf commits
 * what `git commit -e -F <message>` commits when the editor changes nothing.
 *
 * [commentPrefix] is null when the setting is `auto`, which git 2.54 warns is going in git 3.0: git then picks one for
 * each message ([commentPrefixFor]).
 */
data class MessageCleanup(
    val mode: CleanupMode,
    val commentPrefix: String?,
) {
    companion object {
        /** git's default: an edited message is stripped of its lines that start with `#`. */
        val DEFAULT = MessageCleanup(CleanupMode.STRIP, "#")
    }
}

/** What follows the comment prefix and a space on git's scissors line (`cut_line` in wt-status.c). */
const val SCISSORS_LINE = "------------------------ >8 ------------------------"

/** The characters that git tries for `core.commentChar=auto`, in order (`adjust_comment_line_char` in commit.c). */
private const val AUTO_COMMENT_CHARACTERS = "#;@!$%^&|:"

/**
 * The comment prefix for [text]: the one set, or with `auto` the one git picks. Null when git can't pick one, as every
 * character it tries starts a line: git then refuses to commit, which Leaf doesn't, and nothing is a comment.
 */
fun MessageCleanup.commentPrefixFor(text: String): String? = commentPrefix ?: autoCommentPrefix(text)

/**
 * The comment character that git picks for `auto`, as measured on git 2.54: the first of [AUTO_COMMENT_CHARACTERS]
 * that starts no line of the message, `#` when the message has no `#` at all. git looks at the message after the
 * whitespace clean-up. It counts the message's first character, and the lines that start before its trailing block of
 * `#` comments, blank lines and old `Conflicts:` lists ([ignoredTrailingLength]), so such a block is stripped while
 * the same lines higher up stay.
 */
internal fun autoCommentPrefix(text: String): String? {
    val cleaned = stripSpace(text, commentPrefix = null)

    if ('#' !in cleaned) {
        return "#"
    }

    val considered = cleaned.substring(0, cleaned.length - ignoredTrailingLength(cleaned))
    val lineStarts = buildSet {
        cleaned.firstOrNull()?.let(::add)

        for (index in 0 until considered.length - 1) {
            if (considered[index] == '\n' || considered[index] == '\r') {
                add(considered[index + 1])
            }
        }
    }

    return AUTO_COMMENT_CHARACTERS.firstOrNull { it !in lineStarts }?.toString()
}

/**
 * How long the end of [text] is that git leaves out of a message when it adds a sign-off (`ignored_log_message_bytes`
 * in sequencer.c), with `#` as the comment prefix: from the scissors line on, or else the trailing run of comment lines,
 * empty lines and `Conflicts:` lists. As in git, a run that starts at the message's first line doesn't count.
 */
private fun ignoredTrailingLength(text: String): Int {
    val cutoff = scissorsLineStart(text, "#")
    var blockStart = 0
    var inConflicts = false
    var lineStart = 0

    while (lineStart < cutoff) {
        val lineFeed = text.indexOf('\n', lineStart)
        val nextLine = if (lineFeed < 0) text.length else lineFeed + 1

        when {
            text.startsWith("#", lineStart) || text[lineStart] == '\n' -> if (blockStart == 0) blockStart = lineStart
            text.startsWith("Conflicts:\n", lineStart) -> {
                inConflicts = true
                if (blockStart == 0) blockStart = lineStart
            }

            inConflicts && text[lineStart] == '\t' -> Unit
            blockStart != 0 -> {
                blockStart = 0
                inConflicts = false
            }
        }

        lineStart = nextLine
    }

    return if (blockStart != 0) text.length - blockStart else text.length - cutoff
}

/**
 * [text] as git commits it after [cleanup]: `cleanup_message` in git's sequencer.c, with `strbuf_stripspace` from
 * strbuf.c. Every line of a cleaned message ends with a line feed, as in git's commits. An empty result means that git
 * refuses the commit ("Aborting commit due to empty commit message"), except with [CleanupMode.VERBATIM].
 */
fun cleanCommitMessage(text: String, cleanup: MessageCleanup): String = when (cleanup.mode) {
    CleanupMode.VERBATIM -> text
    CleanupMode.WHITESPACE -> stripSpace(text, commentPrefix = null)
    CleanupMode.STRIP -> stripSpace(text, cleanup.commentPrefixFor(text))
    CleanupMode.SCISSORS -> stripSpace(text.substring(0, scissorsCut(text, cleanup)), commentPrefix = null)
}

/**
 * Where git cuts [text] in [CleanupMode.SCISSORS] (`wt_status_locate_end`): at the start of the first line that is the
 * comment prefix, a space and [SCISSORS_LINE], followed by a line feed. [text]'s length when there is none, or when the
 * mode isn't scissors.
 */
internal fun scissorsCut(text: String, cleanup: MessageCleanup): Int {
    if (cleanup.mode != CleanupMode.SCISSORS) {
        return text.length
    }

    val prefix = cleanup.commentPrefixFor(text) ?: return text.length

    return scissorsLineStart(text, prefix)
}

/** Where the first scissors line with [prefix] starts in [text] (`wt_status_locate_end`), or [text]'s length. */
private fun scissorsLineStart(text: String, prefix: String): Int {
    val line = "$prefix $SCISSORS_LINE\n"

    if (text.startsWith(line)) {
        return 0
    }

    val found = text.indexOf("\n$line")

    return if (found >= 0) found + 1 else text.length
}

/**
 * git's `strbuf_stripspace`: each line loses its trailing white space, lines that start with [commentPrefix] are left
 * out, blank lines at the start and the end go, and a run of them becomes one. A comment line is left out without
 * counting as blank, so blank lines on both sides of it make one.
 */
private fun stripSpace(text: String, commentPrefix: String?): String {
    val result = StringBuilder()
    var blankLines = 0

    forEachLine(text) { start, end ->
        if (commentPrefix != null && text.startsWith(commentPrefix, start)) {
            return@forEachLine
        }

        val kept = trimmedEnd(text, start, end)

        if (kept > start) {
            if (blankLines > 0 && result.isNotEmpty()) {
                result.append('\n')
            }

            blankLines = 0
            result.append(text, start, kept).append('\n')
        } else {
            blankLines++
        }
    }

    return result.toString()
}

/** Calls [action] with the start and the end of each line of [text], the end before its line feed. */
internal inline fun forEachLine(text: String, action: (start: Int, end: Int) -> Unit) {
    var start = 0

    while (start < text.length) {
        val lineFeed = text.indexOf('\n', start)
        val end = if (lineFeed < 0) text.length else lineFeed

        action(start, end)
        start = end + 1
    }
}

/**
 * Where the line from [start] to [end] ends without its trailing white space: what git's `isspace` takes as white
 * space, which is only the space, the tab, the line feed and the carriage return.
 */
internal fun trimmedEnd(text: String, start: Int, end: Int): Int {
    var trimmed = end

    while (trimmed > start && text[trimmed - 1].isGitSpace()) {
        trimmed--
    }

    return trimmed
}

private fun Char.isGitSpace() = this == ' ' || this == '\t' || this == '\n' || this == '\r'
