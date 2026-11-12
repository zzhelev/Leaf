// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.usecases.DataToRefresh
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

/**
 * A file that an agent or a terminal added with `git add -N` (intent to add), in a tab: the Unstaged list has it as a new
 * file and Staged doesn't, Leaf's commit leaves it out and keeps it intent to add, as `git commit` does, and staging it
 * stages it as `git add` does.
 */
@DisabledOnOs(OS.WINDOWS)
class IntentToAddInTabTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val shellVariables by lazy {
        mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")
    }
    private val gitCli by lazy { testGitCli(shellVariables = shellVariables) }

    // Repositories open as Leaf opens them, as IncludedConfigRepository, whose commit leaves intent-to-add files out
    private val tab by lazy {
        TestTab(testJGit(includedConfig = GitIncludedConfig(gitCli, LoginShellEnvironment { shellVariables })), gitCli)
    }

    private val repository by lazy { File(tempDir, "repo") }

    private val intentToAdd = StatusEntry("new.txt", StatusType.ADDED, EntryType.UNSTAGED, isIntentToAdd = true)

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        // The tab's git commits as this identity: without one, git makes one up on macOS, and refuses on Linux
        globalConfig.parentFile.mkdirs()
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")

        git.initRepository(repository)
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the commit leaves out a file added with git add -N, which stays new in Unstaged`(): Unit = runBlocking {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)

        // An agent, in a terminal next to Leaf
        File(repository, "README.md").appendText("More\n")
        git.run(repository, "add", "README.md")
        File(repository, "new.txt").writeText("new\n")
        git.run(repository, "add", "-N", "new.txt")
        tab.refreshData(DataToRefresh.STATUS).join()

        val status = tab.awaitLoaded(tab.repositoryData.status) { it.unstaged.isNotEmpty() }
        assertEquals(listOf(StatusEntry("README.md", StatusType.MODIFIED, EntryType.STAGED)), status.staged)
        assertEquals(listOf(intentToAdd), status.unstaged)

        val commit = tab.doCommit("Update the README", amend = false, author = null, isMergeMessage = false).await()

        assertInstanceOf(Either.Ok::class.java, commit, commit.toString())
        assertEquals("README.md\n", git.run(repository, "show", "--format=", "--name-only", "HEAD"))
        assertEquals(" A new.txt\n", git.run(repository, "status", "--short"))

        val after = tab.awaitLoaded(tab.repositoryData.status) { it.staged.isEmpty() }
        assertEquals(listOf(intentToAdd), after.unstaged)
    }

    @Test
    fun `a commit with only a file added with git add -N is refused, as git refuses it`(): Unit = runBlocking {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)

        File(repository, "new.txt").writeText("new\n")
        git.run(repository, "add", "-N", "new.txt")
        val head = git.run(repository, "rev-parse", "HEAD")

        val commit = tab.doCommit("Nothing staged", amend = false, author = null, isMergeMessage = false).await()

        assertInstanceOf(Either.Err::class.java, commit, commit.toString())
        assertEquals(head, git.run(repository, "rev-parse", "HEAD"))
        assertEquals(" A new.txt\n", git.run(repository, "status", "--short"))
    }

    @Test
    fun `Stage all stages a file added with git add -N as git add does`(): Unit = runBlocking {
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status)

        File(repository, "new.txt").writeText("new\n")
        git.run(repository, "add", "-N", "new.txt")
        tab.refreshData(DataToRefresh.STATUS).join()
        tab.awaitLoaded(tab.repositoryData.status) { it.unstaged.isNotEmpty() }

        tab.stageAll(null).join()

        assertEquals("A  new.txt\n", git.run(repository, "status", "--short"))
        val status = tab.awaitLoaded(tab.repositoryData.status) { it.staged.isNotEmpty() }
        assertEquals(listOf(StatusEntry("new.txt", StatusType.ADDED, EntryType.STAGED)), status.staged)
        assertEquals(emptyList<StatusEntry>(), status.unstaged)
    }
}
