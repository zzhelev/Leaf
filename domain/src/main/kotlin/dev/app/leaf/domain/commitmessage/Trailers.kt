// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/** A trailer's line: a key of letters, digits and dashes, a colon, and a value (git's `<token>: <value>`). */
private val TRAILER_LINE = Regex("""[A-Za-z0-9][A-Za-z0-9-]*:[ \t]*\S.*""")

/** When an equal trailer is there already, git's `--if-exists`: what leaves the trailer out. */
enum class TrailerIfExists(val gitValue: String) {
    /** An equal trailer anywhere in the trailer block, as for a co-author, who is credited once. */
    ADD_IF_DIFFERENT("addIfDifferent"),

    /** An equal trailer as the block's last line, as `git commit -s` does for its sign-off. */
    ADD_IF_DIFFERENT_NEIGHBOR("addIfDifferentNeighbor"),
}

/** Whether [line] is a trailer, such as `Signed-off-by: Ann Lee <ann@example.com>`. */
fun isTrailerLine(line: String): Boolean = TRAILER_LINE.matches(line)

/**
 * [message] with [trailer] added at its end, for when git can't run `git interpret-trailers`, which Leaf asks first
 * (fork-only): into the last paragraph when every line of it is a trailer and it isn't the subject's, else after a blank
 * line. A trailer that the last paragraph has already isn't added again, as [ifExists] says. Unlike git, it doesn't
 * look past comment lines at the end, nor at the other forms that git counts as trailers.
 */
fun appendTrailer(
    message: String,
    trailer: String,
    ifExists: TrailerIfExists = TrailerIfExists.ADD_IF_DIFFERENT,
): String {
    val body = message.trimEnd()

    if (body.isEmpty()) {
        return "$trailer\n"
    }

    val paragraphStart = body.lastIndexOf("\n\n")
    val lastParagraph = if (paragraphStart < 0) null else body.substring(paragraphStart + 2).lines()

    return when {
        lastParagraph == null || !lastParagraph.all(::isTrailerLine) -> "$body\n\n$trailer\n"
        ifExists == TrailerIfExists.ADD_IF_DIFFERENT && trailer in lastParagraph -> "$body\n"
        ifExists == TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR && trailer == lastParagraph.last() -> "$body\n"
        else -> "$body\n$trailer\n"
    }
}
