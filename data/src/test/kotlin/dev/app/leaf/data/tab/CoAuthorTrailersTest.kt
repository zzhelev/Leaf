// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.AddTrailerGitAction
import dev.app.leaf.domain.commitmessage.CoAuthor
import dev.app.leaf.domain.commitmessage.MARKED_CO_AUTHOR_TRAILER
import dev.app.leaf.domain.commitmessage.TrailerIfExists
import dev.app.leaf.domain.commitmessage.coAuthorLineAt
import dev.app.leaf.domain.commitmessage.withCoAuthor
import dev.app.leaf.domain.commitmessage.withEmptyCoAuthorLine
import dev.app.leaf.domain.commitmessage.appendTrailer
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val MAYA = "Co-authored-by: Maya Rodriguez <maya@acme.dev>"
private const val SIGN_OFF = "Signed-off-by: Leaf Test <test@example.invalid>"

/** Messages whose trailers git places in each of its ways, comment lines and an earlier sign-off among them. */
private val MESSAGES = listOf(
    "Fix the parser",
    "Fix the parser\n\nBody text.",
    "Fix the parser\n\nBody text.\n\n$MAYA",
    "Fix the parser\n\nBody text.\n\n$MAYA\n\n# A comment that git leaves out\n",
    "Fix the parser\n\nRefs: 123\nnot a trailer\n",
    "Fix the parser\n\n$SIGN_OFF",
    "Fix the parser\n\n$SIGN_OFF\n$MAYA\n",
    "Fix the parser\n# A comment right after the subject\n\nBody.",
)

/**
 * Co-author and sign-off trailers in a tab (fork-only): `AddTrailerGitAction` places them as `git interpret-trailers`
 * does, with the repository's comment character, and Leaf's sign-off joins the message's trailers, as
 * `git commit -s` puts it, where it used to start a paragraph of its own and leave a `Co-authored-by` line out of the
 * trailer block.
 */
@DisabledOnOs(OS.WINDOWS)
class CoAuthorTrailersTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val shellVariables by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val jgit by lazy { testJGit() }
    private val tab by lazy { TestTab(jgit, testGitCli(shellVariables)) }

    private val leaf by lazy { File(tempDir, "leaf") }
    private val twin by lazy { File(tempDir, "twin") }

    @BeforeEach
    fun createRepositories() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        // The tab's git commits, and signs off, as this identity: without one, git makes one up on macOS, and refuses on
        // Linux
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n[commit]\n\tgpgSign = false\n")

        git.initRepository(leaf)
        git.initRepository(twin)
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the sign-off joins the message's trailers, as git commit -s puts it`(): Unit = runBlocking {
        File(leaf, ".git/leaf").writeText("[signoff]\n\tenabled = true\n")
        tab.openRepository(leaf.path)

        for ((index, message) in MESSAGES.withIndex()) {
            stage(leaf, "$index")
            stage(twin, "$index")

            val commit = tab.doCommit(message, amend = false, author = null, isMergeMessage = false).await()
            assertInstanceOf(Either.Ok::class.java, commit, commit.toString())

            // As its editor showed the message, which Leaf's field stands for: git strips the comment lines
            val messageFile = File(tempDir, "message").apply { writeText(message) }
            git.run(twin, mapOf("GIT_EDITOR" to "true"), "commit", "-q", "-s", "-e", "-F", messageFile.path)

            assertEquals(headMessage(twin), headMessage(leaf), message)
        }
    }

    @Test
    fun `adds a trailer as git interpret-trailers does, before comments in the repository's comment character`(): Unit =
        runBlocking {
            git.run(leaf, "config", "core.commentChar", ";")
            val action = AddTrailerGitAction(testGitCli(shellVariables))
            val message = "Fix the parser\n\nBody.\n\n$SIGN_OFF\n\n; A comment\n# Not a comment here\n"

            val added = action(File(leaf, ".git").path, message, MAYA)

            assertEquals(
                Either.Ok("Fix the parser\n\nBody.\n\n$SIGN_OFF\n\n; A comment\n# Not a comment here\n\n$MAYA\n"),
                added,
                "# isn't a comment here, so the trailers go after it",
            )

            git.run(leaf, "config", "core.commentChar", "#")
            assertEquals(
                Either.Ok("Fix the parser\n\nBody.\n\n$SIGN_OFF\n\n; A comment\n\n$MAYA\n# Not a comment here\n"),
                action(File(leaf, ".git").path, message, MAYA),
            )
        }

    @Test
    fun `adds a co-author once, and a sign-off unless it is the last trailer`(): Unit = runBlocking {
        val action = AddTrailerGitAction(testGitCli(shellVariables))
        val message = "Fix the parser\n\n$MAYA\n$SIGN_OFF\n"
        val gitDir = File(leaf, ".git").path

        assertEquals(Either.Ok(message), action(gitDir, message, MAYA))
        assertEquals(Either.Ok(message), action(gitDir, message, SIGN_OFF, TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR))
        assertEquals(
            Either.Ok("Fix the parser\n\n$SIGN_OFF\n$MAYA\n$SIGN_OFF\n"),
            action(gitDir, "Fix the parser\n\n$SIGN_OFF\n$MAYA\n", SIGN_OFF, TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR),
        )
    }

    @Test
    fun `Add co-author's empty line is where git puts the co-author, with the caret at its end`(): Unit = runBlocking {
        val action = AddTrailerGitAction(testGitCli(shellVariables))
        val gitDir = File(leaf, ".git").path

        for (message in MESSAGES.map { it.replace(MAYA, "Co-authored-by: Marco Bianchi <marco@acme.dev>") } + "") {
            val marked = (action(gitDir, message, MARKED_CO_AUTHOR_TRAILER) as Either.Ok).value
            val (text, caret) = checkNotNull(withEmptyCoAuthorLine(marked)) { message }
            val line = checkNotNull(coAuthorLineAt(text, caret)) { "No line at the caret: $text" }

            assertEquals("", line.value, message)
            assertEquals(line.lineEnd, caret, message)

            // Picking Maya there gives what git gives when it adds her itself
            val (picked, _) = withCoAuthor(text, line, CoAuthor("Maya Rodriguez", "maya@acme.dev"))
            assertEquals((action(gitDir, message, MAYA) as Either.Ok).value, picked, message)
        }
    }

    @Test
    fun `without a usable git, adds the trailer the simple way`(): Unit = runBlocking {
        val withoutGit = AddTrailerGitAction(
            testGitCli(shellVariables, configuredPath = File(tempDir, "missing/git").path)
        )

        for (message in MESSAGES) {
            assertEquals(
                Either.Ok(appendTrailer(message, MAYA)),
                withoutGit(File(leaf, ".git").path, message, MAYA),
                message,
            )
            assertEquals(
                Either.Ok(appendTrailer(message, SIGN_OFF, TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR)),
                withoutGit(File(leaf, ".git").path, message, SIGN_OFF, TrailerIfExists.ADD_IF_DIFFERENT_NEIGHBOR),
                message,
            )
        }
    }

    private fun stage(repository: File, name: String) {
        File(repository, "$name.txt").writeText("$name\n")
        git.run(repository, "add", "$name.txt")
    }

    /** The message of HEAD's commit, byte for byte. */
    private fun headMessage(repository: File) = git.run(repository, "cat-file", "commit", "HEAD").substringAfter("\n\n")
}
