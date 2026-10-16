package dev.app.leaf.domain.models

import androidx.compose.runtime.Immutable

@Immutable
data class PersistedCommitMessage(
    val commitMessage: String?,
    val mergeMessage: String?,
    val squashMessage: String?,
    /** Fork-only: the commit that a rebase stopped at to edit, which [rebaseEditMessage] is for. */
    val rebaseEditStop: String? = null,
    /**
     * Fork-only: the commit field's message for that commit, which Continue amends it with: the one saved for it, or
     * else the commit's own.
     */
    val rebaseEditMessage: String? = null,
)