// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.models.AgentMarker
import dev.app.leaf.domain.models.AgentSessionLock
import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.worktrees.parseClaudeSessionLock
import org.eclipse.jgit.lib.ObjectId
import java.io.File
import java.time.Instant
import kotlin.math.abs

/** The marker that a tool left in the worktree's git dir [gitDir], the first of [AgentMarker]'s, or null. */
internal fun agentMarkerIn(gitDir: File): AgentMarker? = AgentMarker.entries.firstOrNull { marker ->
    File(gitDir, marker.fileName).isFile
}

/**
 * The commit that the worktree whose git dir is [gitDir] started from, as Claude Code records it in `CLAUDE_BASE`.
 * Null without the file, or when it doesn't hold a commit's id.
 */
internal fun startCommitIn(gitDir: File): String? = File(gitDir, AgentMarker.CLAUDE_CODE.fileName)
    .readTextOrNull()
    ?.trim()
    ?.takeIf { ObjectId.isId(it) }

/** A process that runs, and when it started, null when the system doesn't tell. */
internal data class RunningProcess(val start: Instant?)

/** The process [pid] if it runs, from the JVM's [ProcessHandle]. */
internal fun runningProcess(pid: Long): RunningProcess? = ProcessHandle.of(pid)
    .filter { it.isAlive }
    .map { RunningProcess(it.info().startInstant().orElse(null)) }
    .orElse(null)

/**
 * The agent's session that holds a worktree's lock, whose reason is [reason], or null when the reason isn't one that a
 * session writes. Only Claude Code's is known: `claude session <name> (pid <n> start <time>)`.
 *
 * The session runs while its process does: one with that pid, which started at that time, to the second, as Claude
 * Code checks it. A process that got the pid later started at another time. When either time is unknown, the pid
 * alone decides.
 */
internal fun agentSessionLock(
    reason: String,
    process: (pid: Long) -> RunningProcess? = ::runningProcess,
): AgentSessionLock? {
    val lock = parseClaudeSessionLock(reason) ?: return null
    val running = process(lock.pid)
    val lockStart = lock.start
    val processStart = running?.start

    val isRunning = when {
        running == null -> false
        lockStart == null || processStart == null -> true
        // ps prints whole seconds, and the JVM's time may be rounded the other way
        else -> abs(processStart.epochSecond - lockStart.epochSecond) <= 1
    }

    return AgentSessionLock(AgentTool.CLAUDE_CODE, lock.session, isRunning)
}
