// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.signers.GitSigningConfigs
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.commitmessage.CleanupMode
import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.commitmessage.cleanCommitMessage
import dev.app.leaf.domain.commitmessage.commentPrefixFor
import dev.app.leaf.domain.commitmessage.commitMessageLayout
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/** Lines that git and Leaf may read as comments, scissors lines among them, for the random messages. */
private val MESSAGE_PIECES = listOf(
    "a", "b", "word", " ", "  ", "\t", "\r", "\n", "\n", "\n", "#", "# ", ";", "//", "/", "\u000b", "\u000c",
    "\u00a0", "é", "\u3000", "\uD83D\uDE00", "------------------------ >8 ------------------------",
)

/** Lines for the random messages of `auto`: every character that git tries, comment blocks, conflicts lists. */
private val AUTO_PIECES = listOf(
    "Subject\n", "body\n", "\n", "\n", "  \n", "# c\n", "#\n", "; s\n", "@ a\n", "! x\n", "$ d\n", "% p\n",
    "^ h\n", "& m\n", "| b\n", ": c\n", "Conflicts:\n", "\tfile.txt\n", "# ------------------------ >8 ------------------------\n",
    "trailing", " # not at the start\n",
)

/** Messages that git cleans up in each of its ways. */
private val MESSAGES = listOf(
    "Subject\n\nBody line\n# comment\n",
    "  # indented is kept\n# dropped\nSubject  \n\n\n\nBody\t\n\n",
    "; semicolon comment\n// slash comment\n#hash\nReal subject\n",
    "Subject\n# ------------------------ >8 ------------------------\nbelow the scissors\n",
    "Subject\n; ------------------------ >8 ------------------------\ncut with ;\n",
    "Subject\n// ------------------------ >8 ------------------------\ncut with //\n",
    "Subject without a line feed",
    "\n\n  \nSubject after blank lines\r\n\r\nbody\r\n",
    "Ünïcödé subject \uD83D\uDE00\u3000\n\nbody\u00a0\n",
    "# only a comment\n\n",
    "Subject\n#\n\n\n# between\n\nBody\n",
)

/**
 * Leaf's clean-up of a commit message ([cleanCommitMessage]) against git's: `git stripspace` for random messages, and
 * the commits that Leaf and `git commit` make from the same message in twin repositories, in each `commit.cleanup` mode
 * and with each kind of comment prefix (fork-only).
 */
@DisabledOnOs(OS.WINDOWS)
class CommitMessageCleanupTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }

    /** The user's config: JGit's user config, and git's global config. */
    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }

    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to File(root, "home").path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
        )
    }

    private val gitCli by lazy { testGitCli(shellVariables) }
    private val jgit by lazy {
        testJGit(includedConfig = GitIncludedConfig(gitCli, LoginShellEnvironment { shellVariables }))
    }

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.com\n[commit]\n\tgpgSign = false\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `cleans random messages up as git stripspace does`(): Unit = runBlocking {
        val random = Random(20261021)

        repeat(150) {
            val message = randomMessage(random)

            assertEquals(
                stripspace(message, commentPrefix = null),
                cleanCommitMessage(message, MessageCleanup(CleanupMode.WHITESPACE, "#")),
                "whitespace: ${message.visible()}",
            )

            for (prefix in listOf("#", ";", "//")) {
                assertEquals(
                    stripspace(message, prefix),
                    cleanCommitMessage(message, MessageCleanup(CleanupMode.STRIP, prefix)),
                    "strip with $prefix: ${message.visible()}",
                )
            }
        }
    }

    @Test
    fun `the field's empty message is the one that git stripspace empties`(): Unit = runBlocking {
        val random = Random(42)

        repeat(150) {
            val message = randomMessage(random)

            for (cleanup in listOf(MessageCleanup(CleanupMode.STRIP, "#"), MessageCleanup(CleanupMode.WHITESPACE, null))) {
                val expected = stripspace(message, cleanup.commentPrefix.takeIf { cleanup.mode == CleanupMode.STRIP })

                assertEquals(expected.isEmpty(), commitMessageLayout(message, cleanup).isEmpty, message.visible())
            }
        }
    }

    @Test
    fun `cleans up as git commit does, in each clean-up mode and with each comment prefix`(): Unit = runBlocking {
        val prefixed = listOf(null, "default", "strip", "scissors")
        val comments = listOf(null, "core.commentChar=;", "core.commentString=//", "core.commentChar=auto")
        // The comment prefix changes nothing in these two
        val combinations = prefixed.flatMap { mode -> comments.map { mode to it } } +
            listOf("whitespace" to null, "verbatim" to null)

        for ((mode, comment) in combinations) {
            val repository = configuredRepository("${mode ?: "unset"}-${comment?.substringAfter('=') ?: "unset"}", mode, comment)
            val cleanup = (GetCommitMessageCleanupGitAction(jgit)(File(repository, ".git").path) as Either.Ok).value

            for (message in MESSAGES) {
                // git cleans up a message that its editor showed (Leaf's field is that editor), except with whitespace
                // and verbatim, where its editor's own hints would stay in the message
                val edited = mode !in listOf("whitespace", "verbatim")

                assertCleansUpAsGit(repository, message, cleanup, edited, "commit.cleanup=$mode, $comment")
            }
        }
    }

    @Test
    fun `cleans random messages up as git commit does, with core commentChar auto and with the default`(): Unit =
        runBlocking {
            val random = Random(7)
            var skipped = 0

            for (comment in listOf("core.commentChar=auto", null)) {
                val repository = configuredRepository("random-${comment ?: "default"}", mode = null, comment)
                val cleanup = (GetCommitMessageCleanupGitAction(jgit)(File(repository, ".git").path) as Either.Ok).value

                repeat(80) {
                    // Pieces that start lines with every character that git tries, and whole comment blocks
                    val message = (0 until random.nextInt(1, 14)).joinToString("") { AUTO_PIECES.random(random) }

                    if (cleanup.commentPrefixFor(message) == null) {
                        // git refuses to commit when every character it tries starts a line; Leaf doesn't
                        skipped++
                        return@repeat
                    }

                    assertCleansUpAsGit(repository, message, cleanup, edited = true, "$comment")
                }
            }

            assertTrue(skipped < 10, "skipped $skipped")
        }

    @Test
    fun `commits the message that git commit commits`(): Unit = runBlocking {
        for ((mode, comment) in listOf(null to null, "scissors" to "core.commentString=//")) {
            val name = "commits-${mode ?: "unset"}"
            val leafRepository = configuredRepository("$name/leaf", mode, comment)
            val gitRepository = configuredRepository("$name/git", mode, comment)

            for ((index, message) in MESSAGES.withIndex()) {
                compareCommits(leafRepository, gitRepository, "$index", message, "commit.cleanup=$mode, $comment")
            }
        }
    }

    @Test
    fun `reads the settings that an includeIf file sets`(): Unit = runBlocking {
        val workConfig = File(root, "work.gitconfig")
        // In a config file, ; starts a comment unless it's quoted
        workConfig.writeText("[commit]\n\tcleanup = verbatim\n[core]\n\tcommentChar = \";\"\n")
        globalConfig.appendText("[includeIf \"gitdir:${File(root, "work").path}/\"]\n\tpath = ${workConfig.path}\n")
        val repository = File(root, "work/repo").apply { mkdirs() }
        git(repository, "init", "-q", "-b", "main")

        val cleanup = GetCommitMessageCleanupGitAction(jgit)(File(repository, ".git").path)

        assertEquals(Either.Ok(MessageCleanup(CleanupMode.VERBATIM, ";")), cleanup)
    }

    @Test
    fun `reads commit cleanup and the comment prefix as git does`() {
        fun cleanupOf(text: String) = Config().apply { fromText(text) }.messageCleanup()

        assertEquals(MessageCleanup.DEFAULT, cleanupOf(""))
        assertEquals(MessageCleanup(CleanupMode.STRIP, "#"), cleanupOf("[commit]\n\tcleanup = default\n"))
        assertEquals(MessageCleanup(CleanupMode.WHITESPACE, "#"), cleanupOf("[commit]\n\tcleanup = whitespace\n"))
        assertEquals(MessageCleanup(CleanupMode.VERBATIM, "#"), cleanupOf("[commit]\n\tcleanup = verbatim\n"))
        assertEquals(MessageCleanup(CleanupMode.SCISSORS, "#"), cleanupOf("[commit]\n\tcleanup = scissors\n"))
        // git compares the mode as it is, and refuses to commit with another: Leaf takes its default
        assertEquals(MessageCleanup.DEFAULT, cleanupOf("[commit]\n\tcleanup = Verbatim\n"))
        assertEquals(MessageCleanup(CleanupMode.STRIP, ";"), cleanupOf("[core]\n\tcommentChar = \";\"\n"))
        assertEquals(MessageCleanup(CleanupMode.STRIP, "//"), cleanupOf("[core]\n\tcommentString = //\n"))
        assertEquals(MessageCleanup(CleanupMode.STRIP, null), cleanupOf("[core]\n\tcommentChar = AUTO\n"))
        assertEquals(MessageCleanup(CleanupMode.STRIP, "//"), cleanupOf("[core]\n\tcommentChar = @\n\tcommentString = //\n"))
        assertEquals(MessageCleanup.DEFAULT, cleanupOf("[core]\n\tcommentChar = \n"))
        assertEquals(MessageCleanup.DEFAULT, cleanupOf("[core]\n\tcommentChar\n"))
    }

    @Test
    fun `refuses a message that git's clean-up empties, as git commit does`(): Unit = runBlocking {
        val (leafRepository, gitRepository) = twinRepositories("empty")
        stage(leafRepository, "a")
        stage(gitRepository, "a")

        val message = "# Only a comment\n\n   \n"
        val result = DoCommitGitAction(
            GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
            JGitCommitMapper(JGitIdentityMapper()),
            JGitIdentityMapper(),
            jgit,
            GitIdentities(gitCli),
            GitSigningConfigs(gitCli),
        )(File(leafRepository, ".git").path, message, false, null)

        assertInstanceOf(Either.Err::class.java, result)
        assertTrue(!gitCommit(gitRepository, message, edited = true))
        assertEquals("", git(leafRepository, "rev-list", "--all"), "Leaf made no commit")
    }

    /**
     * Stages a file named [name] in both repositories, and commits [message] with Leaf in [leafRepository] and with git
     * in [gitRepository], then compares the two commits' messages. When git refuses it, Leaf must refuse it too.
     */
    private suspend fun compareCommits(
        leafRepository: File,
        gitRepository: File,
        name: String,
        message: String,
        case: String,
    ) {
        stage(leafRepository, name)
        stage(gitRepository, name)

        val gitCommitted = gitCommit(gitRepository, message, edited = true)
        val leafResult = DoCommitGitAction(
            GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
            JGitCommitMapper(JGitIdentityMapper()),
            JGitIdentityMapper(),
            jgit,
            GitIdentities(gitCli),
            GitSigningConfigs(gitCli),
        )(File(leafRepository, ".git").path, message, false, null)

        val description = "$case: ${message.visible()}"

        if (gitCommitted) {
            assertInstanceOf(Either.Ok::class.java, leafResult, description)
            assertEquals(headMessage(gitRepository), headMessage(leafRepository), description)
        } else {
            assertInstanceOf(Either.Err::class.java, leafResult, description)
            git(leafRepository, "reset", "-q")
            git(gitRepository, "reset", "-q")
        }
    }

    /**
     * Commits [message] with git in [repository], and checks that [cleanCommitMessage] gives the commit's message, and
     * that the field counts it as empty exactly when git refuses it.
     */
    private fun assertCleansUpAsGit(
        repository: File,
        message: String,
        cleanup: MessageCleanup,
        edited: Boolean,
        case: String,
    ) {
        val description = "$case: ${message.visible()}"

        if (gitCommit(repository, message, edited, allowEmpty = true)) {
            assertEquals(headMessage(repository), cleanCommitMessage(message, cleanup), description)
            assertFalse(commitMessageLayout(message, cleanup).isEmpty, description)
        } else {
            assertEquals("", cleanCommitMessage(message, cleanup), description)
            assertTrue(commitMessageLayout(message, cleanup).isEmpty, description)
        }
    }

    /** A new repository with `commit.cleanup` set to [mode] and the comment setting [comment] (`key=value`). */
    private fun configuredRepository(name: String, mode: String?, comment: String?): File =
        File(root, name).apply {
            mkdirs()
            git(this, "init", "-q", "-b", "main")
            mode?.let { git(this, "config", "commit.cleanup", it) }
            comment?.let { git(this, "config", it.substringBefore('='), it.substringAfter('=')) }
        }

    /** Two repositories with the same first commit, one for Leaf's commits and one for git's. */
    private fun twinRepositories(name: String): Pair<File, File> {
        val repositories = listOf("leaf", "git").map { side ->
            File(root, "$name/$side").apply {
                mkdirs()
                git(this, "init", "-q", "-b", "main")
            }
        }

        return repositories[0] to repositories[1]
    }

    private fun stage(repository: File, name: String) {
        File(repository, "$name.txt").writeText("$name\n")
        git(repository, "add", "$name.txt")
    }

    /**
     * Commits [message] with git, as its editor showed it when [edited], and without staging anything when [allowEmpty].
     * False when git refused it.
     */
    private fun gitCommit(
        repository: File,
        message: String,
        edited: Boolean,
        allowEmpty: Boolean = false,
    ): Boolean = runBlocking {
        val messageFile = File(root, "message").apply { writeText(message) }
        val args = listOfNotNull(
            "commit",
            "-q",
            "--allow-empty".takeIf { allowEmpty },
            "-e".takeIf { edited },
            "-F",
            messageFile.path,
        )

        when (val result = gitCli.execute(repository, args, environment = mapOf("GIT_EDITOR" to "true"))) {
            is Either.Ok -> {
                check(result.value.exitCode == 0 || result.value.stderr.contains("empty commit message")) {
                    "git commit failed: ${result.value.stderr}"
                }

                result.value.exitCode == 0
            }

            is Either.Err -> error("git commit failed: ${result.error}")
        }
    }

    /** The message of HEAD's commit, byte for byte. */
    private fun headMessage(repository: File) = git(repository, "cat-file", "commit", "HEAD").substringAfter("\n\n")

    /** What `git stripspace` makes of [message], stripping the lines that start with [commentPrefix] if it's given. */
    private fun stripspace(message: String, commentPrefix: String?): String = runBlocking {
        val args = if (commentPrefix == null) {
            listOf("stripspace")
        } else {
            listOf("-c", "core.commentString=$commentPrefix", "stripspace", "--strip-comments")
        }

        when (val result = gitCli.execute(root, args, input = message.toByteArray())) {
            is Either.Ok -> result.value.stdout.also { check(result.value.exitCode == 0) { result.value.stderr } }
            is Either.Err -> error("git stripspace failed: ${result.error}")
        }
    }

    private fun randomMessage(random: Random): String =
        (0 until random.nextInt(0, 30)).joinToString("") { MESSAGE_PIECES.random(random) }

    private fun String.visible() = replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }
}
