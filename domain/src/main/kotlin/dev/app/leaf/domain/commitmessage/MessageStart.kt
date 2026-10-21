// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/** The file that git's `commit.template` names (fork-only): [path] as Leaf found it, and its [text]. */
data class CommitTemplate(val path: String, val text: String)

/**
 * What an empty commit field offers to start from (fork-only), which Tab writes into it: the branch's ticket key in the
 * repository's style ([keyPrefix]), then the commit template's text. [caret] is where typing goes on: the end of the
 * first line.
 */
data class MessageStart(
    val text: String,
    val caret: Int,
    val key: String?,
    val template: CommitTemplate?,
)

/**
 * The start for [key] (written as [keyPrefix]) and [template], or null when there is neither. A template whose first
 * line is a comment gets an empty line before it, where the subject goes: the key, or what is typed. A key goes on the
 * template's first line, in front of what it has there.
 */
fun messageStart(
    key: String?,
    keyPrefix: String?,
    template: CommitTemplate?,
    cleanup: MessageCleanup,
): MessageStart? {
    val templateText = template?.text.orEmpty().let { text ->
        val commentPrefix = cleanup.commentPrefixFor(text)

        if (commentPrefix != null && text.startsWith(commentPrefix)) "\n$text" else text
    }
    val prefix = keyPrefix?.takeIf { key != null }.orEmpty()
    val text = prefix + templateText

    if (text.isEmpty()) {
        return null
    }

    val firstLineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }

    return MessageStart(text, firstLineEnd, key.takeIf { prefix.isNotEmpty() }, template)
}

/**
 * Whether committing [message] would commit [start] as Tab wrote it, which the commit field refuses (fork-only): the
 * template as it is, which git refuses too ("Aborting commit; you did not edit the message", `template_untouched` in
 * builtin/commit.c), or the key and the template with nothing written after them. git's test is that the cleaned
 * message starts with the cleaned template and has nothing after it but white space and `Signed-off-by:` lines
 * (`rest_is_empty`). A template that cleans up to nothing leaves that to the empty message's test, and so does the
 * verbatim mode, where git doesn't look.
 */
fun isUneditedStart(message: String, start: MessageStart?, cleanup: MessageCleanup): Boolean {
    if (start == null || cleanup.mode == CleanupMode.VERBATIM) {
        return false
    }

    val cleaned = cleanCommitMessage(message, cleanup)

    if (cleaned.isBlank()) {
        return false
    }

    val template = start.template?.text?.let { cleanTemplate(it, cleanup) }.orEmpty()
    val untouchedTemplate = template.isNotEmpty() && cleaned.startsWith(template) &&
        cleaned.substring(template.length).lines().all { it.isBlank() || it.startsWith(SIGN_OFF_KEY) }

    return untouchedTemplate || cleaned == cleanCommitMessage(start.text, cleanup)
}

/** What git's `rest_is_empty` skips after a template: its own sign-off lines (`sign_off_header`). */
private const val SIGN_OFF_KEY = "Signed-off-by: "

/** git cleans a template up with `strbuf_stripspace`, leaving comment lines out only in the strip mode. */
private fun cleanTemplate(text: String, cleanup: MessageCleanup): String = when (cleanup.mode) {
    CleanupMode.STRIP -> cleanCommitMessage(text, cleanup)
    else -> cleanCommitMessage(text, cleanup.copy(mode = CleanupMode.WHITESPACE))
}
