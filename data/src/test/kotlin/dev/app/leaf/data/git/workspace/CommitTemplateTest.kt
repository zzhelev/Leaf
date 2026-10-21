// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.commitmessage.CleanupMode
import dev.app.leaf.domain.commitmessage.CommitTemplate
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.cleanCommitMessage
import dev.app.leaf.domain.commitmessage.isUneditedStart
import dev.app.leaf.domain.commitmessage.messageStart
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Reading `commit.template` as `git commit` does, and refusing it unedited as git does (fork-only). */
@DisabledOnOs(OS.WINDOWS)
class CommitTemplateTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val home by lazy { File(tempDir, "home").apply { mkdirs() } }
    private val action by lazy { GetCommitTemplateGitAction(testJGit()) }

    /** An editor that writes the message in `LEAF_TEST_MESSAGE` over git's, or copies git's to `LEAF_TEST_SHOWN`. */
    private val editor by lazy {
        File(tempDir, "editor.sh").apply {
            writeText(
                """
                #!/bin/sh
                if [ -n "${'$'}LEAF_TEST_SHOWN" ]; then cp "${'$'}1" "${'$'}LEAF_TEST_SHOWN"; exit 1; fi
                cp "${'$'}LEAF_TEST_MESSAGE" "${'$'}1"
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
    }

    @BeforeEach
    fun isolate() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restore() {
        SystemReader.setInstance(originalReader)
    }

    private fun templateOf(gitDir: File = File(repository, ".git")) =
        runBlocking { (action(gitDir.path) as Either.Ok).value }

    /** What `git commit` puts in its editor, with [template] as `commit.template`. */
    private fun shownByGit(template: String): String {
        val shown = File(tempDir, "shown.txt")
        File(repository, "change-${System.nanoTime()}.txt").writeText("change\n")
        git.run(repository, "add", ".")

        runCatching {
            git.run(
                repository,
                mapOf("GIT_EDITOR" to editor.path, "LEAF_TEST_SHOWN" to shown.path, "HOME" to home.path),
                "-c", "commit.template=$template", "commit",
            )
        }

        return shown.readText()
    }

    /** Whether `git commit` commits [message] typed over [template] in [cleanup]'s mode. */
    private fun gitCommits(template: String, message: String, cleanup: CleanupMode): Boolean {
        val templateFile = File(tempDir, "template.txt").apply { writeText(template) }
        val messageFile = File(tempDir, "message.txt").apply { writeText(message) }
        File(repository, "change-${System.nanoTime()}.txt").writeText("change\n")
        git.run(repository, "add", ".")

        return runCatching {
            git.run(
                repository,
                mapOf("GIT_EDITOR" to editor.path, "LEAF_TEST_MESSAGE" to messageFile.path),
                "-c", "commit.template=${templateFile.path}",
                "-c", "commit.cleanup=${cleanup.name.lowercase()}",
                "commit",
            )
        }.isSuccess
    }

    @Test
    fun `none when commit template isn't set`() {
        assertNull(templateOf())
    }

    @Test
    fun `an absolute path, as git reads it`() {
        val file = File(tempDir, "templates/team.txt").apply {
            parentFile.mkdirs()
            writeText("Summary\n\n# Why is this change needed?\n")
        }
        git.run(repository, "config", "commit.template", file.path)

        val template = templateOf()

        assertEquals(CommitTemplate(file.path, file.readText()), template)
        assertTrue(shownByGit(file.path).startsWith(template!!.text))
    }

    @Test
    fun `a relative path is from the working tree's root, also in a linked worktree`() {
        File(repository, "tools").mkdirs()
        File(repository, "tools/template.txt").writeText("Main worktree\n")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Add the template")
        git.run(repository, "config", "commit.template", "tools/template.txt")

        assertEquals("Main worktree\n", templateOf()?.text)
        assertTrue(shownByGit("tools/template.txt").startsWith("Main worktree\n"))

        val linked = File(tempDir, "linked")
        git.run(repository, "worktree", "add", "-b", "other", linked.path)
        File(linked, "tools/template.txt").writeText("Linked worktree\n")

        val gitDir = File(repository, ".git/worktrees/linked")
        assertEquals("Linked worktree\n", templateOf(gitDir)?.text)
    }

    @Test
    fun `a path in the home folder`() {
        File(home, "template.txt").writeText("From home\n")

        assertEquals("From home\n", commitTemplateAt("~/template.txt", repository, home)?.text)
        assertTrue(shownByGit("~/template.txt").startsWith("From home\n"))
    }

    @Test
    fun `none for a file that isn't there, or a folder`() {
        git.run(repository, "config", "commit.template", "missing.txt")
        assertNull(templateOf())

        assertNull(commitTemplateAt(tempDir.path, repository, home))
        assertNull(commitTemplateAt("~other/template.txt", repository, home))
    }

    @Test
    fun `refuses the template unedited where git does`() {
        val cases = listOf(
            Triple("\n# Why?\n", "\n# Why?\n", CleanupMode.STRIP),
            Triple("Summary\n\n# Why?\n", "Summary\n\n# Why?\n", CleanupMode.STRIP),
            Triple("Summary\n\n# Why?\n", "Summary\n\nBecause.\n# Why?\n", CleanupMode.STRIP),
            Triple("Summary\n\n# Why?\n", "Fix the parser\n", CleanupMode.STRIP),
            Triple("Summary\n\nDetails\n", "Summary\n\nDetails\n\n\n", CleanupMode.STRIP),
            Triple("Summary\n", "Summary\n\n# a comment added\n", CleanupMode.STRIP),
            Triple("Summary\n", "Summary extended\n", CleanupMode.STRIP),
            Triple("Summary\n", "Summary\nmore\n", CleanupMode.STRIP),
            Triple("Summary\n", "Summary\n\nSigned-off-by: Ann <ann@example.com>\n", CleanupMode.STRIP),
            Triple("Summary\n# keep\n", "Summary\n# keep\n", CleanupMode.WHITESPACE),
            Triple("Summary\n# keep\n", "Summary\n", CleanupMode.WHITESPACE),
            Triple("Summary\n", "Summary\n", CleanupMode.VERBATIM),
            Triple("Summary\n", "Summary\n", CleanupMode.SCISSORS),
        )

        for ((template, message, mode) in cases) {
            val cleanup = MessageCleanup(mode, "#")
            val start = messageStart(null, null, CommitTemplate("t", template), cleanup)
            val empty = mode != CleanupMode.VERBATIM && cleanCommitMessage(message, cleanup).isBlank()
            val leafRefuses = empty || isUneditedStart(message, start, cleanup)

            assertEquals(!gitCommits(template, message, mode), leafRefuses, "$mode: $template -> $message")
        }
    }
}
