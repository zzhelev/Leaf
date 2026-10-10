// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import dev.app.leaf.domain.interfaces.IFormatDiffGitAction
import dev.app.leaf.domain.interfaces.IGenerateSplitHunkFromDiffResultGitAction
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.InvalidObjectException
import java.io.PrintStream

class GetDiffUseCaseTest {
    private val formatDiff = mockk<IFormatDiffGitAction>()
    private val repositoryData = mockk<RepositoryDataRepository> {
        every { repositoryPath } returns REPOSITORY_PATH
    }
    private val getDiff = GetDiffUseCase(formatDiff, mockk<IGenerateSplitHunkFromDiffResultGitAction>(), repositoryData)

    private val diffType = DiffType.UncommittedDiff(StatusEntry("src/a.txt", StatusType.MODIFIED), EntryType.UNSTAGED)

    @Test
    fun `a file with no changes left has no diff, and gets one log line without a stack trace`(): Unit = runBlocking {
        coEvery { formatDiff(REPOSITORY_PATH, diffType, false) } returns Either.Err(
            GenericError("Diff entry not found", MissingDiffEntryException("Diff entry not found"))
        )

        val (result, output) = capturingOutput { getDiff(diffType, DiffTextViewType.Unified, false) }

        assertEquals(ViewDiffResult.DiffNotFound(diffType), result)
        assertEquals(
            listOf("[LOG] GetDiffUseCase - No diff to show for src/a.txt: Diff entry not found"),
            output.logLines,
        )
        assertFalse(output.stderr.contains("Exception"), output.stderr)
    }

    @Test
    fun `another error has no diff either, and is logged as an error`(): Unit = runBlocking {
        coEvery { formatDiff(REPOSITORY_PATH, diffType, false) } returns Either.Err(
            GenericError("Invalid object in diff format", InvalidObjectException("Invalid object in diff format"))
        )

        val (result, output) = capturingOutput { getDiff(diffType, DiffTextViewType.Unified, false) }

        assertEquals(ViewDiffResult.DiffNotFound(diffType), result)
        assertEquals(
            listOf("[ERROR] GetDiffUseCase - Could not load the diff of src/a.txt: Invalid object in diff format"),
            output.logLines,
        )
    }

    @Test
    fun `an exception has no diff, and its stack trace isn't printed again`(): Unit = runBlocking {
        coEvery { formatDiff(REPOSITORY_PATH, diffType, false) } throws IllegalStateException("format failed")

        val (result, output) = capturingOutput { getDiff(diffType, DiffTextViewType.Unified, false) }

        assertEquals(ViewDiffResult.DiffNotFound(diffType), result)
        assertEquals(listOf("[ERROR] GetDiffUseCase - format failed"), output.logLines)
        assertFalse(output.stderr.contains("IllegalStateException"), output.stderr)
    }

    @Test
    fun `a diff the format action made is loaded`(): Unit = runBlocking {
        val text = DiffResult.Text(modifiedEntry("src/a.txt"), emptyList())
        coEvery { formatDiff(REPOSITORY_PATH, diffType, false) } returns Either.Ok(text)

        val result = getDiff(diffType, DiffTextViewType.Unified, false)

        assertEquals(ViewDiffResult.Loaded(diffType, text), result)
    }

    private class Output(val stdout: String, val stderr: String) {
        /** What `printLog` and `printError` print. Unconfigured log4j may print its own warning too. */
        val logLines = stdout.lines().filter { it.startsWith("[LOG]") || it.startsWith("[ERROR]") }
    }

    private inline fun <T> capturingOutput(block: () -> T): Pair<T, Output> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(stdout, true))
        System.setErr(PrintStream(stderr, true))

        val result = try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        return result to Output(stdout.toString(), stderr.toString())
    }

    private fun modifiedEntry(path: String) = object : DiffEntry() {
        init {
            changeType = ChangeType.MODIFY
            oldPath = path
            newPath = path
        }
    }

    private companion object {
        const val REPOSITORY_PATH = "/repo/.git"
    }
}
