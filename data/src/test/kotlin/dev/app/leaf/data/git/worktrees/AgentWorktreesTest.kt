// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.models.AgentSessionLock
import dev.app.leaf.domain.models.AgentTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The lock reason that Claude Code 2.1.287 wrote in a probe run. */
private const val PROBE_REASON = "claude session probe-wt (pid 48472 start Wed Oct 14 07:17:40 2026)"
private val PROBE_START = Instant.parse("2026-10-14T07:17:40Z")

class AgentWorktreesTest {
    @Test
    fun `a Claude Code session runs while a process with its pid runs, started at the time its lock says`() {
        fun lock(process: RunningProcess?) = agentSessionLock(PROBE_REASON) { pid ->
            assertEquals(48472L, pid)
            process
        }

        assertEquals(AgentSessionLock(AgentTool.CLAUDE_CODE, "probe-wt", isRunning = true), lock(RunningProcess(PROBE_START)))
        // The JVM's time can be a fraction of a second off ps's
        assertEquals(true, lock(RunningProcess(PROBE_START.plusMillis(900)))?.isRunning)
        assertEquals(true, lock(RunningProcess(PROBE_START.minusMillis(900)))?.isRunning)
        // Another process that got the pid later
        assertEquals(false, lock(RunningProcess(PROBE_START.plusSeconds(3600)))?.isRunning)
        assertEquals(false, lock(null)?.isRunning)
        // Without the process's start time, the pid decides
        assertEquals(true, lock(RunningProcess(start = null))?.isRunning)
    }

    @Test
    fun `a lock that a session didn't write isn't a session's`() {
        assertNull(agentSessionLock("agent running") { error("not asked") })
        assertNull(agentSessionLock("") { error("not asked") })
    }

    @Test
    fun `a start time that can't be read leaves the pid to decide`() {
        val lock = agentSessionLock("claude session x (pid 12 start sometime)") { RunningProcess(PROBE_START) }

        assertEquals(true, lock?.isRunning)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the JVM's start time of a process is the one that ps prints, as Claude Code reads it`() {
        // Claude Code runs `LC_ALL=C TZ=UTC ps -o lstart= -p <pid>`
        val pid = ProcessHandle.current().pid()
        val process = ProcessBuilder("ps", "-o", "lstart=", "-p", pid.toString())
            .apply { environment().putAll(mapOf("LC_ALL" to "C", "TZ" to "UTC")) }
            .redirectErrorStream(true)
            .start()
        val start = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(10, TimeUnit.SECONDS)

        val running = agentSessionLock("claude session leaf-test (pid $pid start $start)")
        val reused = agentSessionLock("claude session leaf-test (pid $pid start Wed Oct 14 07:17:40 2026)")

        assertNotNull(running)
        assertEquals(true, running?.isRunning, "ps printed '$start'")
        assertEquals(false, reused?.isRunning)
    }
}
