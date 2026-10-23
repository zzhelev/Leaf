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

private fun messageOf(commit: Commit) = commit.message.replace("\r\n", "\n").trim()
