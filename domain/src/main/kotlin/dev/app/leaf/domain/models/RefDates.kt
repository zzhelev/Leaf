// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** Dates used to sort the side panel. All times are epoch milliseconds. */
data class RefDates(
    /** Committer time of the tip commit of each local and remote branch, by full ref name. */
    val commitTimes: Map<String, Long> = emptyMap(),
    /** Tagger time of annotated tags, or the commit time of lightweight tags, by full ref name. */
    val tagTimes: Map<String, Long> = emptyMap(),
    /** Latest checkout of each branch in the HEAD reflog, by short branch name. */
    val lastCheckoutTimes: Map<String, Long> = emptyMap(),
)
