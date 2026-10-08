// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

/**
 * A line of git's progress: `Writing objects:  45% (9/20)`, or the server's `remote: Compressing objects:  50% (1/2)`.
 */
data class GitProgress(val stage: String, val percent: Int)

private val PROGRESS_LINE = Regex("""^(?:remote: )?([^:]+): +(\d{1,3})% \(\d+/\d+\)""")

/**
 * Reads progress from git's stderr as it's written (`--progress`). git rewrites a stage's line with `\r` while it runs,
 * and ends it with `\n` once it's done.
 */
class GitProgressParser(private val onProgress: (GitProgress) -> Unit) {
    private val line = StringBuilder()

    fun accept(text: String) {
        for (char in text) {
            if (char == '\r' || char == '\n') {
                parseLine(line.toString())
                line.clear()
            } else {
                line.append(char)
            }
        }
    }

    private fun parseLine(text: String) {
        val match = PROGRESS_LINE.find(text) ?: return
        val percent = match.groupValues[2].toInt().coerceAtMost(100)

        onProgress(GitProgress(match.groupValues[1].trim(), percent))
    }
}

/**
 * git's stderr as a terminal would show it at the end: each line as last rewritten, without the progress, trailing
 * spaces (the server's lines have some) or empty lines at the ends. ssh ends its lines with `\r\n`, which isn't a
 * rewrite.
 */
fun readableGitOutput(stderr: String): String {
    return stderr
        .replace("\r\n", "\n")
        .split('\n')
        .map { it.substringAfterLast('\r').trimEnd() }
        .filterNot { isProgressNoise(it) }
        .joinToString("\n")
        .trim('\n')
}

private val PROGRESS_NOISE = listOf(
    Regex("""^(?:remote: )?Enumerating objects: \d+, done\.$"""),
    Regex("""^(?:remote: )?Total \d+ \(delta \d+\)"""),
)

private fun isProgressNoise(line: String) =
    PROGRESS_LINE.containsMatchIn(line) || PROGRESS_NOISE.any { it.containsMatchIn(line) }
