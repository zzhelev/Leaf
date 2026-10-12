// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.conflicts.GetConflictedFileGitAction
import dev.app.leaf.data.git.conflicts.WriteConflictResultGitAction
import dev.app.leaf.data.git.conflicts.mergeWithConflictsIn
import dev.app.leaf.data.git.conflicts.resolvingAll
import dev.app.leaf.data.git.conflicts.stages
import dev.app.leaf.data.git.conflicts.staged
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.StageEntryGitAction
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.usecases.GetConflictedFileUseCase
import dev.app.leaf.domain.usecases.MarkConflictResolvedUseCase
import dev.app.leaf.domain.usecases.SaveConflictResolutionUseCase
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The resolver's Save and Mark resolved, through a tab's use cases. */
class ResolvingConflictsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit = testJGit()
    private val tab by lazy {
        TestTab(
            jgit,
            testGitCli(shellVariables = mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1")),
        )
    }

    private val getConflictedFile by lazy {
        GetConflictedFileUseCase(GetConflictedFileGitAction(jgit), tab.useCaseExecutor)
    }
    private val save by lazy { SaveConflictResolutionUseCase(WriteConflictResultGitAction(jgit), tab.useCaseExecutor) }
    private val markResolved by lazy {
        MarkConflictResolvedUseCase(WriteConflictResultGitAction(jgit), StageEntryGitAction(jgit), tab.useCaseExecutor)
    }

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }
    private val notes by lazy { File(repository, "notes.txt") }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun closeTab() {
        tab.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `Save writes the result and leaves the conflict to resolve`(): Unit = runBlocking {
        val file = open()

        assertInstanceOf(Either.Ok::class.java, save(file, file.merge().resolvingAll(ConflictResolution.Ours)))

        assertEquals("main\n", notes.readText())
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))
    }

    @Test
    fun `Mark resolved is refused while a conflict is open, then stages the result`(): Unit = runBlocking {
        val file = open()
        val written = notes.readText()

        assertEquals(Either.Err(ConflictError.ConflictsLeft("notes.txt", 1)), markResolved(file, file.merge()))
        assertEquals(written, notes.readText())
        assertEquals(listOf(1, 2, 3), git.stages(repository, "notes.txt"))

        assertEquals(
            Either.Ok(Unit),
            markResolved(file, file.merge().resolvingAll(ConflictResolution.TheirsThenOurs)),
        )

        assertEquals("feature\nmain\n", notes.readText())
        assertEquals(listOf(0), git.stages(repository, "notes.txt"))
        assertEquals("feature\nmain\n", git.staged(repository, "notes.txt"))
        tab.awaitLoaded(tab.repositoryData.status) { status ->
            status.unstaged.none { it.statusType == StatusType.CONFLICTING } &&
                status.staged.any { it.filePath == "notes.txt" }
        }
    }

    /** Opens the repository, which has a conflict in notes.txt, in the tab, and loads the conflict. */
    private suspend fun open(): ConflictedFile {
        git.mergeWithConflictsIn(repository, "notes.txt")
        tab.openRepository(File(repository, ".git").path)
        tab.awaitLoaded(tab.repositoryData.status) { status ->
            status.unstaged.any { it.statusType == StatusType.CONFLICTING }
        }

        return assertInstanceOf(Either.Ok::class.java, getConflictedFile("notes.txt")).value as ConflictedFile
    }
}

private fun ConflictedFile.merge() = (content as ConflictContent.Text).merge
