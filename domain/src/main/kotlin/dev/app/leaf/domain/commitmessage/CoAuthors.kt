// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity

/** The trailer that credits a co-author, as git hosts read it. */
const val CO_AUTHOR_KEY = "Co-authored-by"

/** At most this many people show in the suggestions at once; typing narrows them. */
const val MAX_CO_AUTHOR_CHOICES = 8

/** A `Co-authored-by` line, any case, with a name and an email in angle brackets. */
private val CO_AUTHOR_LINE = Regex("""(?im)^co-authored-by:[ \t]*(.*?)[ \t]*<([^<>\s]+)>[ \t]*$""")

/** Someone to credit with a `Co-authored-by` trailer (fork-only). */
data class CoAuthor(val name: String, val email: String) {
    /** What follows the trailer's key: `Name <email>`. */
    val trailerValue: String get() = "$name <$email>"

    /** Whether [query] is in the name or the email, ignoring case. A blank query matches everyone. */
    fun matches(query: String): Boolean = query.isBlank() || trailerValue.contains(query.trim(), ignoreCase = true)
}

/** The co-authors that [message]'s `Co-authored-by` lines name, in their order. */
fun coAuthorsIn(message: String): List<CoAuthor> = CO_AUTHOR_LINE.findAll(message)
    .map { match -> CoAuthor(match.groupValues[1], match.groupValues[2]) }
    .toList()

/**
 * The people to suggest as co-authors (fork-only): the authors of [commits], which come newest first as the log has them,
 * and the co-authors that their messages name, in that order. One per email, ignoring case, with the name it had last.
 * [you] isn't one of them, nor anyone without a name or an email. Committers aren't either: git hosts commit merges made
 * on their websites under an identity of their own.
 */
fun coAuthorCandidates(commits: Iterable<Commit>, you: Identity?): List<CoAuthor> {
    val yourEmail = you?.email?.trim()?.lowercase()
    val people = LinkedHashMap<String, CoAuthor>()

    fun add(name: String?, email: String?) {
        val trimmedName = name?.trim().orEmpty()
        val trimmedEmail = email?.trim().orEmpty()
        val key = trimmedEmail.lowercase()

        if (trimmedName.isNotEmpty() && trimmedEmail.isNotEmpty() && key != yourEmail && key !in people) {
            people[key] = CoAuthor(trimmedName, trimmedEmail)
        }
    }

    for (commit in commits) {
        add(commit.author.name, commit.author.email)

        if (commit.message.contains("o-authored-by", ignoreCase = true)) {
            coAuthorsIn(commit.message).forEach { add(it.name, it.email) }
        }
    }

    return people.values.toList()
}

/**
 * The `Co-authored-by` line that the caret is on (fork-only): it starts at [lineStart], the key ends at [keyEnd] (after
 * the colon) and the line at [lineEnd]. [value] is what follows the key, trimmed.
 */
data class CoAuthorLine(val lineStart: Int, val keyEnd: Int, val lineEnd: Int, val value: String) {
    /** The line names someone already: its value ends with the email's closing bracket. */
    val isComplete: Boolean get() = value.endsWith(">")
}

/** The `Co-authored-by` line, any case, that [caret] is on after the key's colon, or null. */
fun coAuthorLineAt(text: String, caret: Int): CoAuthorLine? {
    if (caret < 0 || caret > text.length) {
        return null
    }

    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
    val keyEnd = lineStart + CO_AUTHOR_KEY.length + 1

    if (!text.startsWith("$CO_AUTHOR_KEY:", lineStart, ignoreCase = true) || caret < keyEnd) {
        return null
    }

    return CoAuthorLine(lineStart, keyEnd, lineEnd, text.substring(keyEnd, lineEnd).trim())
}

/**
 * Whom to suggest for [line] in [text]: the [candidates] that match what the line has so far, and that no other line
 * names yet. None once the line names someone.
 */
fun coAuthorChoices(text: String, line: CoAuthorLine, candidates: List<CoAuthor>): List<CoAuthor> {
    if (line.isComplete) {
        return emptyList()
    }

    val named = coAuthorsIn(text).map { it.email.lowercase() }.toSet()

    return candidates.filter { it.email.lowercase() !in named && it.matches(line.value) }
}

/**
 * [text] with [line] naming [coAuthor], under git's spelling of the key, and where the caret goes: the line's end.
 */
fun withCoAuthor(text: String, line: CoAuthorLine, coAuthor: CoAuthor): Pair<String, Int> {
    val filled = "$CO_AUTHOR_KEY: ${coAuthor.trailerValue}"

    return text.substring(0, line.lineStart) + filled + text.substring(line.lineEnd) to line.lineStart + filled.length
}

/**
 * What Enter does on a `Co-authored-by` line, as an editor continues a list (fork-only): at the end of a line that names
 * someone, it starts the next line with the key; on a line that names no one, it takes the key out, which ends the list
 * and leaves the line empty. The new text and where the caret goes, or null where Enter is a plain line feed: elsewhere,
 * and in the middle of a line.
 */
fun coAuthorEnter(text: String, caret: Int): Pair<String, Int>? {
    val line = coAuthorLineAt(text, caret) ?: return null

    return when {
        line.value.isEmpty() -> text.removeRange(line.lineStart, line.lineEnd) to line.lineStart
        line.isComplete && text.substring(caret, line.lineEnd).isBlank() -> {
            val next = "\n$CO_AUTHOR_KEY: "
            text.substring(0, line.lineEnd) + next + text.substring(line.lineEnd) to line.lineEnd + next.length
        }

        else -> null
    }
}

/** Marks where git put the line of "Add co-author", so that the caret can go there. Never committed. */
const val CO_AUTHOR_MARKER = "LEAF-CO-AUTHOR-CARET"

/** The trailer that "Add co-author" asks git to place: a `Co-authored-by` line holding [CO_AUTHOR_MARKER]. */
const val MARKED_CO_AUTHOR_TRAILER = "$CO_AUTHOR_KEY: $CO_AUTHOR_MARKER"

/**
 * The message with an empty `Co-authored-by` line, from [marked], where git placed [MARKED_CO_AUTHOR_TRAILER], and the
 * caret at the line's end. Null when the marker isn't there, as a `trailer.*` setting of the user's can rewrite it.
 */
fun withEmptyCoAuthorLine(marked: String): Pair<String, Int>? {
    val caret = marked.indexOf(CO_AUTHOR_MARKER)

    return if (caret < 0) null else marked.removeRange(caret, caret + CO_AUTHOR_MARKER.length) to caret
}

/** [text] without its `Co-authored-by` lines that name no one, which would be committed as empty trailers. */
fun withoutEmptyCoAuthorLines(text: String): String {
    val lines = text.split('\n')
    val kept = lines.filterNot { it.trim().equals("$CO_AUTHOR_KEY:", ignoreCase = true) }

    return if (kept.size == lines.size) text else kept.joinToString("\n")
}
