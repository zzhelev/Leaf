// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.merge.MergeMessageFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The conflicts that git lists as comments in MERGE_MSG, which `git commit` strips and JGit's commit kept. git is the
 * reference: what the commit field shows must be what `git commit` commits from the same MERGE_MSG.
 */
class ConflictsHintTest {
    @TempDir
    lateinit var tempDir: File

    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    private enum class Operation { MERGE, CHERRY_PICK, REVERT }

    @Test
    fun `gives what git commit commits after a merge, cherry-pick or revert stopped at conflicts`() {
        val settings = listOf(
            emptyList(),
            listOf("-c", "core.commentChar=;"),
            // git puts its scissors line before the conflicts, and drops everything from it
            listOf("-c", "commit.cleanup=scissors"),
        )

        for (operation in Operation.entries) {
            for (config in settings) {
                val (mergeMessage, committed) = stopAtConflictsAndCommit(operation, config)
                val case = "$operation with $config"

                assertTrue(mergeMessage.contains("Conflicts:\n"), "$case: $mergeMessage")
                assertEquals(committed, withoutConflictsHint(mergeMessage), case)
            }
        }
    }

    @Test
    fun `leaves JGit's footers after the conflicts that it lists before them`() {
        val message = "Fix the parser\n\nIt dropped the last line.\n\nSigned-off-by: Someone <someone@example.com>\n"
        val formatted = MergeMessageFormatter().formatWithConflicts(message, listOf("a.txt", "src/b.txt"), '#')

        assertTrue(formatted.contains("\n# Conflicts:\n#\ta.txt\n#\tsrc/b.txt\n\nSigned-off-by:"), formatted)
        assertEquals(message, withoutConflictsHint(formatted))
    }

    @Test
    fun `gives an empty message for a squash merge's, which has only the conflicts`() {
        assertEquals("", withoutConflictsHint("\n# Conflicts:\n#\tREADME.md\n"))
    }

    @Test
    fun `keeps the user's own lines, even ones that look like comments`() {
        val messages = listOf(
            "Fix #12\n\n# Not a comment to Leaf\n",
            "Merge feature\n\n# Conflicts:\n",
            "Merge feature\n\n# Conflicts:\n#\tREADME.md\nand more\n",
            "Merge feature\n# Conflicts:\n#\tREADME.md\n",
            "Fix Conflicts:\nFix\tREADME.md\n",
            "Merge feature\n\n// Conflicts:\n#\tREADME.md\n",
        )

        for (message in messages) {
            assertEquals(message, withoutConflictsHint(message), message)
        }
    }

    @Test
    fun `takes any comment string, as core_commentString sets`() {
        val message = "Merge branch 'feature'\n\n// Conflicts:\n//\tf\n"

        assertEquals("Merge branch 'feature'\n", withoutConflictsHint(message))
    }

    /** MERGE_MSG when [operation] stops at conflicts with [config], and the message that `git commit` makes of it. */
    private fun stopAtConflictsAndCommit(operation: Operation, config: List<String>): Pair<String, String> {
        val repository = git.initRepository(File(tempDir, "${operation.name}-${config.hashCode()}"))
        val file = File(repository, "README.md")
        val gitDir = File(repository, ".git")

        fun commit(content: String, message: String) {
            file.writeText(content)
            git.run(repository, "commit", "-am", message)
        }

        git.run(repository, "switch", "-c", "feature")
        val trailer = "Signed-off-by: Someone <someone@example.com>"
        commit("feature\n", "Change README.md on feature\n\nWith a body.\n\n$trailer")
        git.run(repository, "switch", "main")
        commit("main\n", "Change README.md on main")

        when (operation) {
            Operation.MERGE -> git.runFailing(repository, *config.toTypedArray(), "merge", "feature")
            Operation.CHERRY_PICK -> git.runFailing(repository, *config.toTypedArray(), "cherry-pick", "feature")
            Operation.REVERT -> {
                commit("main again\n", "Change README.md on main again")
                git.runFailing(repository, *config.toTypedArray(), "revert", "--no-edit", "HEAD~1")
            }
        }

        val mergeMessage = File(gitDir, "MERGE_MSG").readText()
        file.writeText("resolved\n")
        git.run(repository, "add", "README.md")
        // As a terminal does: git opens the editor, and cleans the message up as config says
        git.run(repository, mapOf("GIT_EDITOR" to ":"), *config.toTypedArray(), "commit")

        val commit = git.run(repository, "cat-file", "commit", "HEAD")

        return mergeMessage to commit.substringAfter("\n\n")
    }
}
