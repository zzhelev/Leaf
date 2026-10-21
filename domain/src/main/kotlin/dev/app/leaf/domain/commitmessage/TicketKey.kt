// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

import dev.app.leaf.domain.models.Commit

/** A ticket key as issue trackers such as Jira write it: an uppercase letter, uppercase letters or digits, a dash, a number. */
private const val KEY = """[A-Z][A-Z0-9]+-[1-9][0-9]*"""

/**
 * A ticket key in a branch's name (`feature/PROJ-482-login`), not inside a longer word. Lowercase keys aren't read, as
 * `hotfix-12` or `release-2` would be.
 */
private val KEY_IN_BRANCH = Regex("""(?<![A-Za-z0-9])$KEY(?![A-Za-z0-9])""")

/** The ticket key in [branch], a full ref or a short name (fork-only), or null. The first one when it has several. */
fun ticketKeyIn(branch: String): String? = KEY_IN_BRANCH.find(branch.removePrefix("refs/heads/"))?.value

/** How a repository's subjects start with their ticket key (fork-only). */
enum class TicketKeyStyle {
    /** `PROJ-482: Fix the parser`, when the history shows no other. */
    COLON,

    /** `[PROJ-482] Fix the parser`. */
    BRACKETS,

    /** `PROJ-482 Fix the parser`. */
    SPACE;

    /** What starts a subject with [key] in this style, ready for the rest of the subject. */
    fun prefix(key: String): String = when (this) {
        COLON -> "$key: "
        BRACKETS -> "[$key] "
        SPACE -> "$key "
    }
}

private val SUBJECT_STARTS = listOf(
    TicketKeyStyle.COLON to Regex("""^$KEY: """),
    TicketKeyStyle.BRACKETS to Regex("""^\[$KEY] """),
    TicketKeyStyle.SPACE to Regex("""^$KEY """),
)

/**
 * The style that most of the newest [CONVENTIONAL_SAMPLE_SIZE] subjects that start with a key use, from [commits],
 * newest first as the log has them. Merge commits don't count: git writes their subjects. On a tie, the first in
 * [TicketKeyStyle]'s order, and [TicketKeyStyle.COLON] when no subject starts with a key.
 */
fun ticketKeyStyle(commits: Iterable<Commit>): TicketKeyStyle {
    val uses = IntArray(TicketKeyStyle.entries.size)

    commits
        .asSequence()
        .filter { it.parentsHashes.size <= 1 }
        .take(CONVENTIONAL_SAMPLE_SIZE)
        .forEach { commit ->
            SUBJECT_STARTS
                .firstOrNull { (_, start) -> start.containsMatchIn(commit.shortMessage) }
                ?.let { (style, _) -> uses[style.ordinal]++ }
        }

    val most = uses.max()

    return if (most == 0) TicketKeyStyle.COLON else TicketKeyStyle.entries[uses.indexOfFirst { it == most }]
}
