// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.models.WorktreeLabelRule
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.models.WorktreeOrigin
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A built-in rule: the worktrees of [tool] are in folders that match [pattern]. */
data class BuiltInWorktreeRule(val tool: AgentTool, val pattern: String)

/**
 * Where the tools put their worktrees by default (`docs/fork/agent-awareness.md`, section 2), checked after the user's
 * rules and the markers. Folders that people use for worktrees of their own, such as `../worktrees/<repo>/<name>`, aren't
 * here.
 */
val BUILT_IN_WORKTREE_RULES = listOf(
    BuiltInWorktreeRule(AgentTool.CLAUDE_CODE, "<repo>/.claude/worktrees/*"),
    BuiltInWorktreeRule(AgentTool.CODEX, "~/.codex/worktrees/*/*"),
    BuiltInWorktreeRule(AgentTool.CURSOR, "~/.cursor/worktrees/*/*"),
    BuiltInWorktreeRule(AgentTool.OPENCODE, "~/.local/share/opencode/worktree/*/*"),
    BuiltInWorktreeRule(AgentTool.CONDUCTOR, "~/conductor/workspaces/*/*"),
    BuiltInWorktreeRule(AgentTool.CONDUCTOR, "<repo>/.conductor/*"),
    BuiltInWorktreeRule(AgentTool.COPILOT, "<repo>.worktrees/*"),
    BuiltInWorktreeRule(AgentTool.GEMINI, "<repo>/.gemini/worktrees/*"),
    BuiltInWorktreeRule(AgentTool.CLINE, "~/.cline/worktrees/*/*"),
    BuiltInWorktreeRule(AgentTool.JUNIE, "<repo>-junie-wt-*"),
)

/**
 * [list] with each linked worktree's [WorktreeInfo.origin]: the first of the user's [rules] that its folder matches,
 * then its marker, then the first built-in rule that its folder matches. The main worktree gets none.
 *
 * Folders are compared as git gives them and as real paths ([realPath]), so a home folder or repository reached through
 * a link matches either way.
 */
fun WorktreeList.withOrigins(
    rules: List<WorktreeLabelRule>,
    home: String = System.getProperty("user.home"),
    realPath: (String) -> String = ::realPathOf,
): WorktreeList {
    val main = mainWorktree?.path
    val places = listOf(FolderPlaces(home, main)) + FolderPlaces(realPath(home), main?.let(realPath))
    val userRules = rules
        .filter { it.label.isNotBlank() && it.pattern.isNotBlank() }
        .map { rule -> rule to places.mapNotNull { folderPatternRegex(rule.pattern, it.home, it.repo) } }
    val builtInRules = BUILT_IN_WORKTREE_RULES
        .map { rule -> rule to places.mapNotNull { folderPatternRegex(rule.pattern, it.home, it.repo) } }

    return copy(
        worktrees = worktrees.map { info ->
            val worktree = info.worktree

            if (worktree.isMain) {
                return@map info
            }

            val paths = listOf(worktree.path, realPath(worktree.path)).map(::withForwardSlashes)
            fun List<Regex>.matchAny() = any { regex -> paths.any { path -> regex.matches(path) } }

            val origin = userRules.firstOrNull { (_, regexes) -> regexes.matchAny() }
                ?.let { (rule, _) -> WorktreeOrigin.Labelled(rule) }
                ?: worktree.agentMarker?.let { WorktreeOrigin.Marked(it) }
                ?: builtInRules.firstOrNull { (_, regexes) -> regexes.matchAny() }
                    ?.let { (rule, _) -> WorktreeOrigin.InToolFolder(rule.tool, rule.pattern) }

            info.copy(origin = origin)
        }
    )
}

/** The home folder and the main worktree's folder, as given or as real paths. */
private data class FolderPlaces(val home: String, val repo: String?)

/**
 * The regular expression for a folder pattern of a worktree label rule, matched against a worktree's whole path with
 * `/` separators, ignoring case. Null when the pattern is blank, or names `<repo>` and there's no main worktree.
 * - `~/` at the start is the home folder, [home];
 * - `<repo>` is the main worktree's folder, [repo], and `<repo-name>` its name;
 * - `*` stands for one folder name or part of one, `?` for one character, `**` for any number of folders;
 * - a pattern that starts with `**` matches anywhere: `**` + `/aider-x` is any folder named `aider-x`;
 * - a pattern that doesn't start with `/`, `~/`, `<repo`, `**` or a drive (`C:`) is in the main worktree's folder:
 *   `.agents/x` is `<repo>/.agents/x`.
 *
 * Everything else, the placeholders' paths included, matches as it is.
 */
fun folderPatternRegex(pattern: String, home: String, repo: String?): Regex? {
    val trimmed = withForwardSlashes(pattern.trim()).trimEnd('/')

    if (trimmed.isEmpty()) {
        return null
    }

    val isAbsolute = trimmed.startsWith("/") || trimmed == "~" || trimmed.startsWith("~/") ||
        trimmed.startsWith("<repo") || trimmed.startsWith("**") || DRIVE.containsMatchIn(trimmed)
    val full = if (isAbsolute) trimmed else "$REPO/$trimmed"

    val regex = StringBuilder()
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) {
            regex.append(Regex.escape(literal.toString()))
            literal.clear()
        }
    }

    var index = 0

    if (full == "~" || full.startsWith("~/")) {
        literal.append(withForwardSlashes(home).trimEnd('/'))
        index = 1
    }

    while (index < full.length) {
        when {
            full.startsWith(REPO_NAME, index) -> {
                literal.append(File(repo ?: return null).name)
                index += REPO_NAME.length
            }

            full.startsWith(REPO, index) -> {
                literal.append(withForwardSlashes(repo ?: return null).trimEnd('/'))
                index += REPO.length
            }

            full.startsWith("**/", index) -> {
                flush()
                regex.append("(?:[^/]*/)*")
                index += 3
            }

            full.startsWith("**", index) -> {
                flush()
                regex.append(".*")
                index += 2
            }

            full[index] == '*' -> {
                flush()
                regex.append("[^/]*")
                index++
            }

            full[index] == '?' -> {
                flush()
                regex.append("[^/]")
                index++
            }

            else -> {
                literal.append(full[index])
                index++
            }
        }
    }

    flush()

    return Regex(regex.toString(), RegexOption.IGNORE_CASE)
}

private const val REPO = "<repo>"
private const val REPO_NAME = "<repo-name>"
private val DRIVE = Regex("^[A-Za-z]:/")

private fun withForwardSlashes(path: String) = path.replace('\\', '/')

private fun realPathOf(path: String): String = try {
    File(path).canonicalPath
} catch (ex: IOException) {
    path
}

/** What Claude Code writes as the reason of the lock its session holds: `claude session <name> (pid <n> start <time>)`. */
private val CLAUDE_SESSION_LOCK = Regex("""^claude session (.+) \(pid (\d+) start (.+)\)$""")

/**
 * The lock reason of a Claude Code session.
 *
 * @param start when the process started, as `LC_ALL=C TZ=UTC ps -o lstart=` prints it, which is how Claude Code tells
 * its process from another one that got the same pid later. Null when it can't be read.
 */
data class ClaudeSessionLockReason(val session: String, val pid: Long, val start: Instant?)

/** Reads [reason] as the lock of a Claude Code session, or null when it isn't one. */
fun parseClaudeSessionLock(reason: String): ClaudeSessionLockReason? {
    val match = CLAUDE_SESSION_LOCK.matchEntire(reason.trim()) ?: return null
    val (session, pid, start) = match.destructured

    return ClaudeSessionLockReason(session, pid.toLongOrNull() ?: return null, parsePsStartTime(start))
}

/** `ps -o lstart=` in the C locale: `Wed Oct 14 07:17:40 2026`, with the day padded with a space (`Oct  4`). */
private val PS_START_TIME = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)

/** Reads a time that `ps -o lstart=` printed in UTC, or null. */
internal fun parsePsStartTime(text: String): Instant? = try {
    LocalDateTime.parse(text.trim().replace(Regex("\\s+"), " "), PS_START_TIME).toInstant(ZoneOffset.UTC)
} catch (ex: DateTimeParseException) {
    null
}
