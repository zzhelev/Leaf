// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.remote

import dev.app.leaf.domain.models.TaskProgress
import kotlin.time.Duration.Companion.seconds

/**
 * How long a server has had what git handed it before Leaf offers to stop waiting for it, on the processing screen and
 * in the dialog that deletes several branches. Sooner, "Stop waiting" would be pressed while the server still works,
 * as if it stopped something.
 */
val STOP_WAITING_AFTER = 10.seconds

/**
 * How often, and how many times, the server is asked what it did with what git had handed it before the user stopped
 * waiting: a server that was running a hook goes on, and finishes a moment later.
 */
val CHECK_AGAIN_EVERY = 2.seconds
const val CHECK_ROUNDS = 15

enum class ProcessingButton {
    /** Stops the task: git hasn't handed the server anything that it would carry out without git. */
    CANCEL,

    /** Stops waiting for a server that has what git handed it, and asks it afterwards what it did. */
    STOP_WAITING,
}

/**
 * The processing screen's button at [nowMillis] (fork-only): none for a task without [progress], which can't be
 * stopped; Cancel until git has handed the server everything; then none for [STOP_WAITING_AFTER], then Stop waiting.
 */
fun processingButton(progress: TaskProgress?, nowMillis: Long): ProcessingButton? {
    if (progress == null) return null

    val handedOverAt = progress.handedOverAt ?: return ProcessingButton.CANCEL

    return if (nowMillis - handedOverAt >= STOP_WAITING_AFTER.inWholeMilliseconds) ProcessingButton.STOP_WAITING else null
}
