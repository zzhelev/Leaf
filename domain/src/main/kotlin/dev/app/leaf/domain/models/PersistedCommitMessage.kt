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
    /**
     * Fork-only: the commit that a cherry-pick or revert stopped at (`CHERRY_PICK_HEAD`, `REVERT_HEAD`), which
     * [mergeMessage] is for. git writes the next commit's message there when it goes on with several commits and stops
     * again, so the field starts over with it, as at a rebase's next stop.
     */
    val mergeMessageStop: String? = null,
)