// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.data.highlighting.TextMateHighlighter
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.conflicts.editStartLines
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.interfaces.IGetConflictedFileGitAction
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.DownloadLfsVersionUseCase
import dev.app.leaf.domain.usecases.GetConflictedFileUseCase
import dev.app.leaf.domain.usecases.GetRenamedFilesUseCase
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.domain.usecases.MarkConflictResolvedUseCase
import dev.app.leaf.domain.usecases.OpenConflictVersionUseCase
import dev.app.leaf.domain.usecases.OpenFileInExternalAppUseCase
import dev.app.leaf.domain.usecases.OpenMergeToolUseCase
import dev.app.leaf.domain.usecases.OpenPathInSystemUseCase
import dev.app.leaf.domain.usecases.ResolveConflictUseCase
import dev.app.leaf.domain.usecases.ResolveRenamedFileUseCase
import dev.app.leaf.domain.usecases.SaveConflictResolutionUseCase
import dev.app.leaf.domain.usecases.StatusStageUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)

/** A merge whose second part starts in a comment that the first part opens, then a conflict. */
private fun merge() = TextMerge(
    base = text("/* Loads", "   the config.", "*/", "val x = 1"),
    ours = text("/* Loads", "   the config.", "*/", "val x = 2"),
    theirs = text("/* Loads", "   the config.", "*/", "val x = 3"),
    chunks = listOf(
        Chunk.Unchanged(lines(0, 2), lines(0, 2), lines(0, 2)),
        Chunk.Unchanged(lines(2, 3), lines(2, 3), lines(2, 3)),
        Chunk.Conflict(lines(3, 4), lines(3, 4), lines(3, 4)),
    ),
)

/** The conflict resolver's view model colours the versions, the lines typed and the field (fork-only, step 6). */
class ConflictResolverColouringTest {
    /** The real highlighter, noting the texts that it was asked to colour. */
    private class RecordingHighlighter : IHighlightTextAction {
        private val real = TextMateHighlighter()
        val texts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun invoke(scopeName: String, text: String): HighlightedText? {
            texts += text
            return real(scopeName, text)
        }
    }

    private val tabScope = TabCoroutineScope()
    private val repositoryDataRepository = mockk<RepositoryDataRepository> {
        every { repositoryPath } returns "/repo/.git"
        every { status } returns MutableStateFlow(DataState.Loading)
    }
    private val useCaseExecutor = UseCaseExecutor(
        repositoryDataRepository,
        InMemoryRepositoryStateRepository(),
        Provider { error("Nothing is refreshed") },
        tabScope,
    )
    private val files = mutableMapOf<String, ConflictedFile>()
    private val getConflictedFile = object : IGetConflictedFileGitAction {
        override suspend fun invoke(repositoryPath: String, path: String): Either<ConflictedFile, GitError> =
            Either.Ok(files.getValue(path))
    }
    private val settings = AppSettingsService(
        mockk<AppSettingsRepository> {
            every { conflictResolverLayout } returns MutableStateFlow(null)
            every { conflictResolverWrapLines } returns MutableStateFlow(null)
        }
    )
    private val highlighter = RecordingHighlighter()
    private val viewModel = ConflictResolverViewModel(
        repositoryDataRepository,
        GetConflictedFileUseCase(getConflictedFile, useCaseExecutor),
        SaveConflictResolutionUseCase(mockk(), useCaseExecutor),
        MarkConflictResolvedUseCase(mockk(), mockk(), useCaseExecutor),
        StatusStageUseCase(mockk(), useCaseExecutor),
        ResolveConflictUseCase(mockk(), useCaseExecutor),
        ResolveRenamedFileUseCase(mockk(), useCaseExecutor),
        GetRenamedFilesUseCase(mockk(), useCaseExecutor),
        OpenMergeToolUseCase(mockk(), useCaseExecutor),
        OpenConflictVersionUseCase(
            mockk(),
            OpenFileInExternalAppUseCase(OpenPathInSystemUseCase(ShellManager())),
            TempFilesManager(AppFilesManager()),
            useCaseExecutor,
        ),
        DownloadLfsVersionUseCase(mockk(), useCaseExecutor),
        settings,
        ConflictResolverSession(),
        HighlightTextUseCase(highlighter),
    )

    @AfterEach
    fun tearDown() {
        viewModel.onClear()
        tabScope.cancel()
    }

    private fun file(path: String, merge: TextMerge = merge()) = ConflictedFile(
        path = path,
        base = ConflictStage("1".repeat(40), 0b1000000110100100),
        ours = ConflictStage("2".repeat(40), 0b1000000110100100),
        theirs = ConflictStage("3".repeat(40), 0b1000000110100100),
        conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
        sides = null,
        content = ConflictContent.Text(merge, WorkingFileCheck(WorkingFileState.MATCHES, 1, "id")),
    ).also { files[path] = it }

    private fun awaitState(check: (ConflictResolverState) -> Boolean): ConflictResolverState =
        runBlocking { withTimeout(10.seconds) { viewModel.state.first(check) } }

    private val ConflictResolverState.merge get() = (file as ResolverFile.Loaded).merge!!

    /** The state once [path] is loaded and its three versions are coloured. */
    private fun open(path: String): ConflictResolverState {
        file(path)
        viewModel.open(path) {}
        return awaitColoured(path)
    }

    private fun awaitColoured(path: String) = awaitState { state ->
        val loaded = state.file as? ResolverFile.Loaded
        loaded?.path == path && loaded.merge!!.let { merge ->
            listOf(merge.base, merge.ours, merge.theirs).all { state.syntax.versionOf(it)?.highlighted != null }
        }
    }

    @Test
    fun `a file's three versions are coloured when it loads, and another file's replace them`() {
        val first = open("src/First.kt")
        assertEquals(3, first.syntax.versions.size)
        assertEquals(SyntaxCategory.COMMENT, first.syntax.spansOf(first.merge.ours, 1).single().category)

        file("src/Second.kt")
        viewModel.selectFile("src/Second.kt")
        val second = awaitColoured("src/Second.kt")

        assertEquals(3, second.syntax.versions.size)
        assertNull(second.syntax.versionOf(first.merge.ours))
    }

    @Test
    fun `editing starts in the rows' colours, and the field is coloured again after the lines before it`() {
        val state = open("src/Demo.kt")

        viewModel.startEdit(1)
        val edit = viewModel.state.value.edit!!
        // In the comment that the part before it opens: the rows' colours, so it doesn't flash plain
        assertEquals("*/", edit.syntax!!.text)
        assertEquals(state.syntax.spansOf(state.merge.base, 2), edit.syntax.highlighted.spansOf(0))
        assertEquals(listOf(state.merge.base to 2), state.merge.editStartLines(1))
        assertEquals(SyntaxCategory.COMMENT, edit.syntax.highlighted.spansOf(0).single().category)

        // Typed: coloured after ours' lines before it, so still in the comment, though alone it would be code
        val typed = "   val y */"
        viewModel.typeInEdit(TextFieldValue(typed, TextRange(typed.length)))
        val coloured = awaitState { it.edit?.syntax?.text == typed }.edit!!.syntax!!
        assertTrue("/* Loads\n   the config.\n$typed" in highlighter.texts, "${highlighter.texts}")
        assertEquals(listOf(SyntaxCategory.COMMENT), coloured.highlighted.spansOf(0).map { it.category })
    }

    @Test
    fun `lines typed keep the field's colours in the rows, at once, and aren't coloured again`() {
        open("src/Demo.kt")
        viewModel.startEdit(1)
        val typed = "   val y */"
        viewModel.typeInEdit(TextFieldValue(typed, TextRange(typed.length)))
        val field = awaitState { it.edit?.syntax?.text == typed }.edit!!.syntax!!
        val asked = highlighter.texts.size

        viewModel.finishEdit()

        // In the same state as the merge that has them
        val state = viewModel.state.value
        val lines = (state.merge.chunks[1] as Chunk.Unchanged).edited!!
        assertSame(field.highlighted, state.syntax.versionOf(lines.text)?.highlighted)
        assertNull(state.edit)

        // Undo and Redo keep them, and nothing is coloured again
        viewModel.undo()
        viewModel.redo()
        Thread.sleep(300)
        assertEquals(asked, highlighter.texts.size, "${highlighter.texts.drop(asked)}")
        assertNotNull(viewModel.state.value.syntax.versionOf(lines.text))
    }

    @Test
    fun `lines typed faster than the field is coloured are coloured again once they're in the rows`() {
        open("src/Demo.kt")
        viewModel.startEdit(1)
        val typed = "   val y */"
        viewModel.typeInEdit(TextFieldValue(typed, TextRange(typed.length)))
        // Finished before the field's colours came: the rows start from what the field had
        viewModel.finishEdit()

        awaitState { state ->
            val lines = (state.merge.chunks[1] as? Chunk.Unchanged)?.edited ?: return@awaitState false
            state.syntax.versionOf(lines.text)?.highlighted?.spansOf(0)?.map { it.category } == listOf(SyntaxCategory.COMMENT)
        }
        assertTrue("/* Loads\n   the config.\n$typed\n" in highlighter.texts, "${highlighter.texts}")
    }
}
