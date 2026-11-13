// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.RenamedFrom
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The status that the tab's lists show pairs Staged's moves (fork-only, `docs/fork/status-renames.md`), and only that
 * one: the refresh runs the detection after the status.
 */
@DisabledOnOs(OS.WINDOWS)
class StagedRenamesInTabTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val tab by lazy {
        TestTab(
            testJGit(),
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }

    private val repository by lazy { File(tempDir, "repo") }

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config").apply { mkdirs() }, originalReader))

        git.initRepository(repository)
        File(repository, "parser").mkdirs()
        File(repository, "parser/Lexer.kt").writeText((1..20).joinToString("") { "lexer line $it\n" })
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a staged move is one row in the status that the lists show`(): Unit = runBlocking {
        git.run(repository, "mv", "parser/Lexer.kt", "parser/Tokens.kt")

        tab.openRepository(repository.path)
        val status = tab.awaitLoaded(tab.repositoryData.status) { it.staged.isNotEmpty() }

        assertEquals(
            listOf(
                StatusEntry(
                    "parser/Tokens.kt",
                    StatusType.RENAMED,
                    EntryType.STAGED,
                    renamedFrom = RenamedFrom("parser/Lexer.kt", 100),
                ),
            ),
            status.staged,
        )
    }

    @Test
    fun `unstaging it in the tab brings back a deleted file and an untracked one`(): Unit = runBlocking {
        git.run(repository, "mv", "parser/Lexer.kt", "parser/Tokens.kt")
        tab.openRepository(repository.path)
        tab.awaitLoaded(tab.repositoryData.status) { it.staged.isNotEmpty() }

        git.run(repository, "restore", "--staged", "parser/Lexer.kt", "parser/Tokens.kt")
        tab.refreshData(DataToRefresh.STATUS).join()
        val status = tab.awaitLoaded(tab.repositoryData.status) { it.staged.isEmpty() }

        assertEquals(
            listOf(
                StatusEntry("parser/Lexer.kt", StatusType.REMOVED, EntryType.UNSTAGED),
                StatusEntry("parser/Tokens.kt", StatusType.ADDED, EntryType.UNSTAGED),
            ),
            status.unstaged,
        )
    }
}
