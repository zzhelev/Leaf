// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.remote_operations.HandleTransportGitAction
import dev.app.leaf.data.git.remote_operations.ListRemoteRefsGitAction
import dev.app.leaf.data.git.remote_operations.NoSshSessionFactory
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.RemoteRefs
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The Clone dialog's "Choose…" asks a remote for its branches and tags with `git ls-remote` (and with JGit when git
 * isn't used), once per request, from repositories on disk (`file://`). Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliListRemoteRefsGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var helper: File
    private lateinit var git: TestGitCli
    private lateinit var remote: TestRemoteCommand
    private lateinit var source: File

    @BeforeEach
    fun setUp() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "test.gitconfig")
        globalConfig.writeText("")
        git = TestGitCli(globalConfig)
        remote = TestRemoteCommand(helper, globalConfig)

        source = git.initRepository(File(tempDir, "source"))
        git.run(source, "tag", "v0.9")
        git.run(source, "tag", "-a", "v1.0", "-m", "Version 1.0")

        for (name in listOf("feature/clone-branch", "release/1.2", "release/1.10", "docs")) {
            git.run(source, "branch", name, "main")
        }
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lists the branches and tags, and the default branch`(): Unit = runBlocking {
        val refs = list("file://${source.absolutePath}")

        assertEquals(
            RemoteRefs(
                defaultBranch = "main",
                branches = listOf("docs", "feature/clone-branch", "main", "release/1.2", "release/1.10"),
                tags = listOf("v0.9", "v1.0"),
            ),
            refs,
        )
        // The answer is the list: nothing went to the processing screen
        assertEquals(emptyList<Any?>(), remote.progress)
    }

    @Test
    fun `refs that aren't branches or tags are left out`(): Unit = runBlocking {
        val head = git.run(source, "rev-parse", "HEAD").trim()
        for (ref in listOf("refs/pull/1/head", "refs/notes/commits", "refs/changes/01/1/1", "refs/stash")) {
            git.run(source, "update-ref", ref, head)
        }

        val refs = list("file://${source.absolutePath}")

        assertEquals(listOf("v0.9", "v1.0"), refs.tags)
        assertEquals(5, refs.branches.size)
    }

    @Test
    fun `an empty repository has nothing, and no default branch`(): Unit = runBlocking {
        val empty = File(tempDir, "empty.git")
        git.run(tempDir, "init", "--bare", empty.path)

        assertEquals(RemoteRefs(null, emptyList(), emptyList()), list("file://${empty.absolutePath}"))
    }

    @Test
    fun `a HEAD that points to a branch that isn't there leaves the default branch out`(): Unit = runBlocking {
        val odd = File(tempDir, "odd.git")
        git.run(tempDir, "clone", "--bare", source.path, odd.path)
        git.run(odd, "symbolic-ref", "HEAD", "refs/heads/gone")

        val refs = list("file://${odd.absolutePath}")

        assertEquals(null, refs.defaultBranch)
        assertTrue("main" in refs.branches)
    }

    @Test
    fun `a repository that doesn't exist is access denied`(): Unit = runBlocking {
        val result = GitCliListRemoteRefsGitAction(remote.command)("file://${File(tempDir, "nothing").absolutePath}")

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, (result as Either.Err).error)
    }

    @Test
    fun `a URL that looks like an option is a URL, and runs nothing`(): Unit = runBlocking {
        val marker = File(tempDir, "ran")

        val result = GitCliListRemoteRefsGitAction(remote.command)("--upload-pack=touch ${marker.path}")

        assertTrue(result is Either.Err)
        assertFalse(marker.exists())
    }

    @Test
    fun `thousands of tags are listed in natural order`(): Unit = runBlocking {
        val head = git.run(source, "rev-parse", "HEAD").trim()
        val updates = (1..3000).joinToString("") { "create refs/tags/build-$it $head\n" }
        val update = remote.gitCli.execute(source, listOf("update-ref", "--stdin"), input = updates.toByteArray())
        assertEquals(0, (update as Either.Ok).value.exitCode)

        val refs = list("file://${source.absolutePath}")

        assertEquals(3002, refs.tags.size)
        assertEquals(listOf("build-1", "build-2", "build-3"), refs.tags.take(3))
        assertEquals("build-3000", refs.tags[2999])
    }

    @Test
    fun `JGit lists the same branches and tags`(): Unit = runBlocking {
        val url = "file://${source.absolutePath}"

        val withGit = list(url)
        val withJGit = (jgitList()(url) as Either.Ok).value

        // The server says what HEAD points to, which JGit reads from the advertisement as git does
        assertEquals(withGit, withJGit)
        assertEquals("main", withJGit.defaultBranch)
    }

    @Test
    fun `JGit says a repository that doesn't exist fails`(): Unit = runBlocking {
        val result = jgitList()("file://${File(tempDir, "nothing").absolutePath}")

        assertTrue(result is Either.Err)
    }

    @Test
    fun `the list that the app asks for comes from git or from JGit as the setting says`(): Unit = runBlocking {
        val url = "file://${source.absolutePath}"

        for (withGit in listOf(true, false)) {
            val refs = (selectingList(withGit)(url) as Either.Ok).value

            assertEquals(listOf("v0.9", "v1.0"), refs.tags) { "withGit=$withGit" }
            assertEquals(5, refs.branches.size) { "withGit=$withGit" }
            // Only git was asked for the default branch, and its run shows in the progress that git reported
            if (withGit) assertEquals("main", refs.defaultBranch)
        }
    }

    @Test
    fun `the backend that lists is the setting's, which a rule that only git reads shows`(): Unit = runBlocking {
        // url.<base>.insteadOf is in git's global config; JGit reads its own config, which is isolated here
        File(tempDir, "test.gitconfig").writeText("[url \"file://${source.absolutePath}\"]\n\tinsteadOf = leaf-test:\n")

        val withGit = selectingList(withGit = true)("leaf-test:")
        val withJGit = selectingList(withGit = false)("leaf-test:")

        assertEquals("main", (withGit as Either.Ok).value.defaultBranch)
        assertTrue(withJGit is Either.Err) { "$withJGit" }
    }

    @Test
    fun `parses what git prints`() {
        val refs = parseLsRemote(
            "ref: refs/heads/dev\tHEAD\n" +
                "1111111111111111111111111111111111111111\tHEAD\n" +
                "1111111111111111111111111111111111111111\trefs/heads/dev\n" +
                "2222222222222222222222222222222222222222\trefs/heads/main\n" +
                "3333333333333333333333333333333333333333\trefs/tags/v1\n" +
                "4444444444444444444444444444444444444444\trefs/tags/v1^{}\n",
        )

        assertEquals(RemoteRefs("dev", listOf("dev", "main"), listOf("v1")), refs)
        assertEquals(RemoteRefs(null, emptyList(), emptyList()), parseLsRemote(""))
    }

    private suspend fun list(url: String): RemoteRefs =
        (GitCliListRemoteRefsGitAction(remote.command)(url) as Either.Ok).value

    private fun jgitList() = ListRemoteRefsGitAction(
        HandleTransportGitAction(
            noSshSessionFactory = NoSshSessionFactory { null },
            httpCredentialsProvider = object : HttpCredentialsFactory {
                override fun create(git: Git?) = error("No HTTPS in this test")
            },
            jgit = jgit,
        )
    )

    private fun selectingList(withGit: Boolean) = SelectingListRemoteRefsGitAction(
        backend = RemoteOperationsBackend(
            appSettingsService = AppSettingsService(
                mockk<AppSettingsRepository> {
                    every { remoteOperationsWithGit } returns flowOf(withGit)
                    every { gitExecutablePath } returns flowOf(null)
                }
            ),
            gitExecutableLocator = GitExecutableLocator(ProcessRunner()),
            askpassHelper = AskpassHelper { helper },
            gitCli = remote.gitCli,
            jgit = jgit,
        ),
        gitCliListRemoteRefsGitAction = GitCliListRemoteRefsGitAction(remote.command),
        jGitListRemoteRefsGitAction = jgitList(),
    )
}
