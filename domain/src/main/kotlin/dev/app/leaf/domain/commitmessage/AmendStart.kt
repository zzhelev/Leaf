// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/** The checked-out [branch] (a full ref, `HEAD` when detached) and the [commit] it is at (fork-only). */
data class AmendHead(val branch: String, val commit: String)

/**
 * What turning Amend on did (fork-only), so that ending it puts the commit field back.
 *
 * Amend fills an empty field with the last commit's message ([loadedMessage]); a field with a message of its own keeps
 * it, and then [loadedMessage] is null. Amend belongs to the branch and commit it was turned on at ([head]): another
 * branch, or another commit, ends it, as the commit it would rewrite isn't the one whose message the field shows.
 */
data class AmendStart(val loadedMessage: String?, val head: AmendHead?) {
    /**
     * What the field says once Amend ends, when [fieldText] is the message that Amend loaded: empty, as before Amend.
     * Null keeps the field as it is: it had a message of its own, or it was edited since.
     */
    fun fieldAfterEnd(fieldText: String): String? = "".takeIf { loadedMessage != null && fieldText == loadedMessage }

    /**
     * The commit draft to save for [fieldText] while Amend is on: what the field would say with Amend off, so that the
     * last commit's message, untouched, isn't the draft after a restart, which turns Amend off.
     */
    fun draftFor(fieldText: String): String = fieldAfterEnd(fieldText) ?: fieldText

    /** True when the checked-out branch or its commit is no longer [head]; nothing is known to end it while [head] is unknown. */
    fun isEndedBy(current: AmendHead?): Boolean = head != null && current != head

    /** [head] once the current branch is known, when it wasn't as Amend was turned on. */
    fun withHeadKnown(current: AmendHead?): AmendStart = if (head == null && current != null) copy(head = current) else this
}

/**
 * Turning Amend on (fork-only): an empty field gets [lastCommitMessage], the message of the commit that Amend rewrites,
 * and a field with text keeps it.
 */
fun amendStart(fieldText: String, lastCommitMessage: String?, head: AmendHead?): AmendStart = AmendStart(
    loadedMessage = lastCommitMessage?.takeIf { fieldText.isEmpty() && it.isNotEmpty() },
    head = head,
)
