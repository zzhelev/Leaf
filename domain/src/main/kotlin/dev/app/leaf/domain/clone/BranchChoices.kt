// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.clone

import dev.app.leaf.domain.models.RemoteRefs
import org.eclipse.jgit.transport.URIish

/** One row of the Clone dialog's "Choose…" list: a branch or a tag, with the part that the typed text matched. */
data class BranchChoice(
    val name: String,
    val isTag: Boolean,
    val isDefault: Boolean,
    /** Where the typed text is in [name], for the list to mark it. [matchLength] is 0 when nothing was typed. */
    val matchStart: Int,
    val matchLength: Int,
)

/**
 * What "Choose…" lists for the text in the Branch field: the branches (the default one first), then the tags, each in
 * the order [RemoteRefs] has them. [total] counts every branch and tag, matching or not.
 */
data class BranchChoices(
    val branches: List<BranchChoice>,
    val tags: List<BranchChoice>,
    val total: Int,
) {
    val matching: Int get() = branches.size + tags.size
}

/**
 * Filters by [text], trimmed and without regard to case, anywhere in the name. This only reads the list that the
 * remote sent: typing never asks the server again.
 */
fun RemoteRefs.choicesFor(text: String): BranchChoices {
    val typed = text.trim()

    fun choice(name: String, isTag: Boolean): BranchChoice? {
        val start = if (typed.isEmpty()) 0 else name.indexOf(typed, ignoreCase = true)

        return if (start < 0) {
            null
        } else {
            BranchChoice(name, isTag, isDefault = !isTag && name == defaultBranch, start, typed.length)
        }
    }

    val matchingBranches = branches.mapNotNull { choice(it, isTag = false) }

    return BranchChoices(
        branches = matchingBranches.sortedByDescending { it.isDefault },
        tags = tags.mapNotNull { choice(it, isTag = true) },
        total = branches.size + tags.size,
    )
}

/** The host of a remote's [url], for "Asking github.com…": null for a local path, which has none. */
fun remoteHostOf(url: String): String? =
    try {
        URIish(url.trim()).host?.takeIf { it.isNotBlank() }
    } catch (e: java.net.URISyntaxException) {
        null
    }
