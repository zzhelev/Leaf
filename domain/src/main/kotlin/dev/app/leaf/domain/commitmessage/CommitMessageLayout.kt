// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/**
 * Past this many characters, the commit field counts the subject's characters (fork-only). git's own documentation
 * asks for subjects under 50 characters (`git help commit`, DISCUSSION).
 */
const val SUBJECT_SOFT_LIMIT = 50

/**
 * Past this many characters, the commit field warns about the subject and tints what goes past (fork-only). Git tools
 * and servers often show only the first 72, and 72 is where git's documentation wraps a message's body.
 */
const val SUBJECT_LIMIT = 72

/** A part of the commit message's text, from [start] to [end], not included, in UTF-16 units as the field counts them. */
data class MessageSpan(val start: Int, val end: Int)

/**
 * What the commit field shows about its message (fork-only), worked out from the text and how git cleans it up
 * ([MessageCleanup]), so that the field says what the commit will hold.
 *
 * @property droppedSpans the parts git leaves out: comment lines, and with scissors everything from the scissors line on
 * @property subject the subject's line without its trailing white space: the first line that git keeps that isn't
 * blank, or null when there's none, which leaves the commit without a message
 * @property subjectLength the subject's characters (Unicode code points)
 * @property subjectOverflow what goes past [SUBJECT_LIMIT] characters of the subject, or null
 * @property blankLineAt where a line feed puts a blank line after the subject, when the next line that git keeps isn't
 * blank: git tools take the whole first paragraph as the subject. Null when the subject stands
 * alone.
 */
data class CommitMessageLayout(
    val droppedSpans: List<MessageSpan>,
    val subject: MessageSpan?,
    val subjectLength: Int,
    val subjectOverflow: MessageSpan?,
    val blankLineAt: Int?,
    /** Whether the commit would have no message, which git refuses. A verbatim message only when it's empty. */
    val isEmpty: Boolean,
)

fun commitMessageLayout(text: String, cleanup: MessageCleanup): CommitMessageLayout {
    val cut = scissorsCut(text, cleanup)
    val commentPrefix = cleanup.commentPrefixFor(text).takeIf { cleanup.mode == CleanupMode.STRIP }
    val droppedSpans = mutableListOf<MessageSpan>()

    var subject: MessageSpan? = null
    var subjectLineEnd = 0
    var runsOn = false
    var lookingPastSubject = false

    forEachLine(text.substring(0, cut)) { start, end ->
        if (commentPrefix != null && text.startsWith(commentPrefix, start)) {
            droppedSpans += MessageSpan(start, end)
            return@forEachLine
        }

        val kept = trimmedEnd(text, start, end)
        val isBlank = kept == start

        when {
            subject == null && !isBlank -> {
                subject = MessageSpan(start, kept)
                subjectLineEnd = end
                lookingPastSubject = true
            }

            lookingPastSubject -> {
                runsOn = !isBlank
                lookingPastSubject = false
            }
        }
    }

    if (cut < text.length) {
        droppedSpans += MessageSpan(cut, text.length)
    }

    val subjectSpan = subject
    val subjectLength = subjectSpan?.let { text.codePointCount(it.start, it.end) } ?: 0

    return CommitMessageLayout(
        droppedSpans = droppedSpans,
        subject = subjectSpan,
        subjectLength = subjectLength,
        subjectOverflow = subjectSpan
            ?.takeIf { subjectLength > SUBJECT_LIMIT }
            ?.let { MessageSpan(text.offsetByCodePoints(it.start, SUBJECT_LIMIT), it.end) },
        blankLineAt = subjectLineEnd.takeIf { runsOn },
        isEmpty = if (cleanup.mode == CleanupMode.VERBATIM) text.isEmpty() else subjectSpan == null,
    )
}

/** [text] with a line feed at [CommitMessageLayout.blankLineAt], which leaves a blank line after the subject. */
fun withBlankLineAfterSubject(text: String, layout: CommitMessageLayout): String {
    val at = layout.blankLineAt ?: return text

    return text.substring(0, at) + "\n" + text.substring(at)
}
