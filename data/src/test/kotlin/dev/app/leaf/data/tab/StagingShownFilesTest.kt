// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import org.eclipse.jgit.util.SystemReader
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Stage all with the entries that a search shows (fork-only, upstream issue 224's sibling): the header button gives the
 * use case the files the pane lists, and only those end up staged. `null` still means every file.
 */
@DisabledOnOs(OS.WINDOWS)
class StagingShownFilesTest {
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
        File(repository, "parser/Lexer.kt").writeText("lexer")
        File(repository, "ui").mkdirs()
        File(repository, "ui/View.kt").writeText("view")
        git.run(repository, "add", ".")
        git.run(repository, "commit", "-m", "Files")

        File(repository, "parser/Lexer.kt").writeText("lexer changed")
        File(repository, "parser/Parser.kt").writeText("new")
        File(repository, "ui/View.kt").writeText("view changed")
        File(repository, "ui/Other.kt").writeText("new")
    }

    @AfterEach
    fun tearDown() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `with null every file is staged, which is why the button must not pass it while a search hides files`(): Unit =
        runBlocking {
            tab.openRepository(repository.path)
            tab.awaitLoaded(tab.repositoryData.status)

            tab.stageAll(null).join()

            val staged = stagedFiles()

            assertEquals(listOf("parser/Lexer.kt", "parser/Parser.kt", "ui/Other.kt", "ui/View.kt"), staged)
        }

    @Test
    fun `with the files a search shows, only those are staged`(): Unit = runBlocking {
        tab.openRepository(repository.path)

        val shown = tab.awaitLoaded(tab.repositoryData.status).unstaged.filter { "parser" in it.filePath }

        assertEquals(listOf("parser/Lexer.kt", "parser/Parser.kt"), shown.map { it.filePath }.sorted())

        tab.stageAll(shown).join()

        assertEquals(listOf("parser/Lexer.kt", "parser/Parser.kt"), stagedFiles())

        val after = tab.awaitLoaded(tab.repositoryData.status) { status -> status.staged.isNotEmpty() }

        assertEquals(listOf("ui/Other.kt", "ui/View.kt"), after.unstaged.map { it.filePath }.sorted(), "The hidden files stay")
    }

    private fun stagedFiles(): List<String> =
        git.run(repository, "diff", "--cached", "--name-only").lines().filter { it.isNotBlank() }.sorted()
}
