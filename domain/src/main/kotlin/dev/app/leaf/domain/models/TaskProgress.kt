// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * The progress of the foreground task, as git reports it: [stage] such as "Writing objects", and its [percent]. Both
 * are null until git reports its first stage.
 *
 * [handOff] is what git has handed the server, once it has (fork-only): stopping git then no longer stops the server,
 * which has had it since [handedOverAt] (milliseconds since the epoch). [stage] is then the server's own, or null.
 */
data class TaskProgress(
    val stage: String?,
    val percent: Int?,
    val handOff: ServerHandOff? = null,
    val handedOverAt: Long? = null,
)
