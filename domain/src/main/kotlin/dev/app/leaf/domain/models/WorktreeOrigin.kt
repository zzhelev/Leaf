// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * A tool that makes worktrees for its agents, which Leaf recognises (`docs/fork/agent-awareness.md`, section 2).
 *
 * @param label the tag that a worktree's row shows, short as it shares the row with the folder's name.
 * @param fullName the tool's name in a sentence ("Made by the Claude desktop app"). Product names aren't translated.
 */
enum class AgentTool(val label: String, val fullName: String) {
    CLAUDE_CODE("Claude", "Claude Code"),
    CLAUDE_DESKTOP("Claude", "the Claude desktop app"),
    CODEX("Codex", "Codex"),
    CURSOR("Cursor", "Cursor"),
    OPENCODE("OpenCode", "OpenCode"),
    CONDUCTOR("Conductor", "Conductor"),
    COPILOT("Copilot", "GitHub Copilot"),
    GEMINI("Gemini", "Gemini CLI"),
    CLINE("Cline", "Cline"),
    JUNIE("Junie", "Junie"),
}

/**
 * A file that a tool leaves in the git dir of each worktree it makes, which finds its worktrees wherever they are. In
 * the order they're looked for: the desktop app's is more specific than the CLI's.
 */
enum class AgentMarker(val fileName: String, val tool: AgentTool) {
    /** Empty. Seen on the desktop app's worktrees, not documented. */
    CLAUDE_DESKTOP("claude-desktop-worktree", AgentTool.CLAUDE_DESKTOP),

    /** The commit the worktree started from. Written by Claude Code 2.1.287 with `--worktree`, not documented. */
    CLAUDE_CODE("CLAUDE_BASE", AgentTool.CLAUDE_CODE),

    /** `{"version":1,"ownerThreadId":…}`, from Codex's source (`codex-rs/worktree/src/metadata.rs`). */
    CODEX("codex-thread.json", AgentTool.CODEX),
}

/**
 * A user's rule for labelling worktrees: the worktrees whose folder matches [pattern] get the tag [label]. See
 * [dev.app.leaf.domain.worktrees.folderPatternRegex] for the pattern's syntax.
 */
data class WorktreeLabelRule(
    val label: String,
    val pattern: String,
)

/** Who made a worktree, and how Leaf knows. */
sealed interface WorktreeOrigin {
    /** The tag that the worktree's row and its branch's badge show. */
    val label: String

    /** The worktree's git dir has [marker]'s file. */
    data class Marked(val marker: AgentMarker) : WorktreeOrigin {
        override val label: String get() = marker.tool.label
    }

    /** The worktree's folder matches one of the built-in rules of [tool]: [pattern]. */
    data class InToolFolder(val tool: AgentTool, val pattern: String) : WorktreeOrigin {
        override val label: String get() = tool.label
    }

    /** The worktree's folder matches the user's [rule]. */
    data class Labelled(val rule: WorktreeLabelRule) : WorktreeOrigin {
        override val label: String get() = rule.label
    }
}

/**
 * The lock that an agent's session holds on its worktree while it runs.
 *
 * @param session the session's name, as the tool gave it in the lock's reason.
 * @param isRunning whether the session's process still runs. Claude Code keeps the lock of a `-p` run until its own
 * sweep, after the process ended.
 */
data class AgentSessionLock(
    val tool: AgentTool,
    val session: String,
    val isRunning: Boolean,
)
