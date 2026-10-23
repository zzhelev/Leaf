// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit

/** The subjects that `git commit --fixup`, `--squash` and `--fixup=amend:` give, which name another commit. */
private val AUTOSQUASH_PREFIXES = listOf("fixup! ", "squash! ", "amend! ")

/** What a squash's message starts from in its dialog ("Start from"). */
enum class SquashMessageStart {
    /** The first message that says something, then the other commits that do, as a list of their subjects. */
    SUBJECTS,

    /** Every commit's message, oldest first, as `git rebase -i`'s squash puts them together. */
    ALL,

    /** The oldest commit's message, which a fixup keeps. */
    OLDEST,
}

/**
 * The message that a squash of [commits] (oldest first) starts from (fork-only). For [SquashMessageStart.SUBJECTS], the
 * first commit whose subject says something ([saysNothing]) leads with its whole message, and the others that say
 * something follow as `- <subject>`. When none does, the oldest leads alone.
 */
fun squashMessageStart(commits: List<Commit>, start: SquashMessageStart): String {
    require(commits.isNotEmpty()) { "A squash has commits" }

    return when (start) {
        SquashMessageStart.OLDEST -> messageOf(commits.first())
        SquashMessageStart.ALL -> commits.joinToString("\n\n") { messageOf(it) }
        SquashMessageStart.SUBJECTS -> {
            val saying = commits.filterNot { saysNothing(it.shortMessage) }
            val lead = saying.firstOrNull() ?: commits.first()
            val others = saying.drop(1).joinToString("\n") { "- " + it.shortMessage.trim() }

            if (others.isEmpty()) messageOf(lead) else messageOf(lead) + "\n\n" + others
        }
    }
}

/**
 * Whether a commit's [subject] says nothing of its own: empty, `wip` in any case, or one that `git commit --fixup`,
 * `--squash` or `--fixup=amend:` made, which names another commit.
 */
fun saysNothing(subject: String): Boolean {
    val text = subject.trim()

    return text.isEmpty() || text.equals("wip", ignoreCase = true) || AUTOSQUASH_PREFIXES.any { text.startsWith(it) }
}

/**
 * The commit of [older] (oldest first) that [commit]'s subject names, as `git rebase --autosquash` finds it (the
 * sequencer's `todo_list_rearrange_squash`), or null when it names none of them or isn't such a subject. Past `fixup! `,
 * `squash! ` and `amend! `, repeated too, it is the oldest commit with that subject, else the commit whose hash starts
 * with it, else the oldest whose subject starts with it.
 */
fun autosquashTarget(commit: Commit, older: List<Commit>): Commit? {
    val named = namedSubject(gitSubject(commit.message)) ?: return null

    return older.firstOrNull { gitSubject(it.message) == named }
        ?: older.firstOrNull { isHashPrefix(named) && it.hash.startsWith(named, ignoreCase = true) }
        ?: older.firstOrNull { gitSubject(it.message).startsWith(named) }
}

/** Whether [commit]'s subject names [candidate] by its whole subject, the first way `git rebase --autosquash` matches. */
fun namesBySubject(commit: Commit, candidate: Commit): Boolean =
    namedSubject(gitSubject(commit.message)) == gitSubject(candidate.message)

/** What follows the autosquash prefixes of [subject], or null when it has none. */
private fun namedSubject(subject: String): String? {
    var rest = subject.takeIf { s -> AUTOSQUASH_PREFIXES.any { s.startsWith(it) } } ?: return null

    while (true) {
        rest = rest.trimStart()
        rest = AUTOSQUASH_PREFIXES.firstOrNull { rest.startsWith(it) }?.let { rest.removePrefix(it) } ?: return rest
    }
}

/** Whether [text] can be an abbreviated hash, which git reads from 4 hex digits on. */
private fun isHashPrefix(text: String) = text.length in 4..64 && text.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

/** A message's subject as git's `%s` gives it: the first paragraph's lines, without their trailing white space, joined. */
private fun gitSubject(message: String): String = message.replace("\r\n", "\n")
    .trimStart('\n')
    .lineSequence()
    .map { it.trimEnd() }
    .takeWhile { it.isNotEmpty() }
    .joinToString(" ")

private fun messageOf(commit: Commit) = commit.message.replace("\r\n", "\n").trim()
