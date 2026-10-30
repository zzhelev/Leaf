// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.interfaces.ICloneRepositoryGitAction
import dev.app.leaf.domain.interfaces.IListRemoteRefsGitAction
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.RemoteRefs
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.system.OpenFilePickerUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Choose…" in the Clone dialog (fork-only): the server is asked once per click, and never otherwise. And the folders
 * that the dialog creates for a clone, which go again, while empty, when the clone doesn't complete.
 */
class CloneViewModelTest {
    private val refs = RemoteRefs("main", listOf("feature/x", "main"), listOf("v1"))

    /** Records each request and answers when the test says so; notes the ones that were cancelled. */
    private class FakeList : IListRemoteRefsGitAction {
        val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cancelled = AtomicInteger()
        var answers = ArrayDeque<CompletableDeferred<Either<RemoteRefs, GitError>>>()

        fun answerNext(): CompletableDeferred<Either<RemoteRefs, GitError>> =
            CompletableDeferred<Either<RemoteRefs, GitError>>().also { answers.addLast(it) }

        override suspend fun invoke(url: String): Either<RemoteRefs, GitError> {
            urls += url
            val answer = answers.removeFirst()

            try {
                return answer.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
        }
    }

    private val list = FakeList()
    private val clones = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())

    /** What the clone action sends for the clone's folder. */
    private var cloneStates: (File) -> Flow<CloneState> = { emptyFlow() }

    private val viewModel = CloneViewModel(
        cloneRepositoryGitAction = object : ICloneRepositoryGitAction {
            override fun invoke(
                directory: File,
                url: String,
                cloneSubmodules: Boolean,
                branch: String?,
                singleBranch: Boolean,
            ): Flow<CloneState> {
                clones += url to singleBranch
                return cloneStates(directory)
            }
        },
        listRemoteRefsGitAction = list,
        openFilePickerUseCase = OpenFilePickerUseCase(ShellManager()),
        appSettings = AppSettingsService(
            mockk<AppSettingsRepository> { every { cloneDefaultDirectory } returns flowOf(null) }
        ),
    )

    @AfterEach
    fun tearDown() = viewModel.onClear()

    private suspend fun awaitState(condition: (BranchChoicesState) -> Boolean) =
        withTimeout(5_000) { viewModel.branchChoices.first(condition) }

    @Test
    fun `nothing asks the server until the button is clicked`(): Unit = runBlocking {
        viewModel.onRepositoryUrlChanged(TextFieldValue("https://example.com/r.git"))
        viewModel.onFolderNameChanged(TextFieldValue("r"))
        viewModel.onBranchChanged(TextFieldValue("fea"))
        viewModel.onBranchChanged(TextFieldValue("feature"))
        delay(100)

        assertEquals(emptyList<String>(), list.urls)
        assertEquals(BranchChoicesState.Closed, viewModel.branchChoices.value)
    }

    @Test
    fun `a click asks once, shows the host while it waits, then the list`(): Unit = runBlocking {
        val answer = list.answerNext()

        viewModel.chooseBranch("https://github.com/zzhelev/Leaf.git")

        assertEquals(BranchChoicesState.Loading("github.com"), awaitState { it is BranchChoicesState.Loading })
        assertEquals(listOf("https://github.com/zzhelev/Leaf.git"), list.urls.toList())

        answer.complete(Either.Ok(refs))

        assertEquals(BranchChoicesState.Loaded(refs), awaitState { it is BranchChoicesState.Loaded })
        // Typing now filters what was loaded, and asks nothing
        viewModel.onBranchChanged(TextFieldValue("feat"))
        delay(100)
        assertEquals(1, list.urls.size)
    }

    @Test
    fun `the URL is asked as it is, without the spaces around it`(): Unit = runBlocking {
        list.answerNext().complete(Either.Ok(refs))

        viewModel.chooseBranch("  https://example.com/r.git ")
        awaitState { it is BranchChoicesState.Loaded }

        assertEquals(listOf("https://example.com/r.git"), list.urls.toList())
    }

    @Test
    fun `a local path has no host to name`(): Unit = runBlocking {
        list.answerNext()

        viewModel.chooseBranch("/tmp/leaf-clone-demo/source")

        assertEquals(BranchChoicesState.Loading(null), awaitState { it is BranchChoicesState.Loading })
    }

    @Test
    fun `without a URL it says so, as Clone does, and asks nothing`(): Unit = runBlocking {
        viewModel.chooseBranch("   ")
        delay(100)

        assertEquals(CloneUiError.EmptyUrl, viewModel.error.value)
        assertEquals(emptyList<String>(), list.urls)
        assertEquals(BranchChoicesState.Closed, viewModel.branchChoices.value)
    }

    @Test
    fun `a failure is kept, to be shown in the list with Try again`(): Unit = runBlocking {
        val failure = RemoteOperationError.ConnectionFailed("Could not resolve host")
        list.answerNext().complete(Either.Err(failure))
        list.answerNext().complete(Either.Ok(refs))

        viewModel.chooseBranch("https://example.com/r.git")
        assertEquals(BranchChoicesState.Failed(failure), awaitState { it is BranchChoicesState.Failed })

        // Try again is another click
        viewModel.chooseBranch("https://example.com/r.git")
        assertEquals(BranchChoicesState.Loaded(refs), awaitState { it is BranchChoicesState.Loaded })
        assertEquals(2, list.urls.size)
    }

    @Test
    fun `a second click stops the first request`(): Unit = runBlocking {
        list.answerNext()
        list.answerNext().complete(Either.Ok(refs))

        viewModel.chooseBranch("https://example.com/a.git")
        awaitState { it is BranchChoicesState.Loading }
        withTimeout(5_000) { while (list.urls.isEmpty()) delay(10) }
        viewModel.chooseBranch("https://example.com/b.git")

        assertEquals(BranchChoicesState.Loaded(refs), awaitState { it is BranchChoicesState.Loaded })
        withTimeout(5_000) { while (list.cancelled.get() == 0) delay(10) }
        assertEquals(1, list.cancelled.get())
    }

    @Test
    fun `closing the list while it waits stops the request`(): Unit = runBlocking {
        list.answerNext()

        viewModel.chooseBranch("https://example.com/r.git")
        withTimeout(5_000) { while (list.urls.isEmpty()) delay(10) }
        viewModel.closeBranchChoices()

        withTimeout(5_000) { while (list.cancelled.get() == 0) delay(10) }
        assertEquals(BranchChoicesState.Closed, viewModel.branchChoices.value)
    }

    @Test
    fun `picking puts the name in the field, the caret at its end, and closes the list`(): Unit = runBlocking {
        list.answerNext().complete(Either.Ok(refs))
        viewModel.chooseBranch("https://example.com/r.git")
        awaitState { it is BranchChoicesState.Loaded }

        viewModel.pickBranch("feature/x")

        assertEquals(TextFieldValue("feature/x", TextRange(9)), viewModel.branch.value)
        assertEquals(BranchChoicesState.Closed, viewModel.branchChoices.value)
    }

    @Test
    fun `a new URL closes the list, which was of the old one, and the same text doesn't`(): Unit = runBlocking {
        viewModel.onRepositoryUrlChanged(TextFieldValue("https://example.com/a.git"))
        list.answerNext().complete(Either.Ok(refs))
        viewModel.chooseBranch("https://example.com/a.git")
        awaitState { it is BranchChoicesState.Loaded }

        // Only the caret moved
        viewModel.onRepositoryUrlChanged(TextFieldValue("https://example.com/a.git", TextRange(3)))
        assertTrue(viewModel.branchChoices.value is BranchChoicesState.Loaded)

        viewModel.onRepositoryUrlChanged(TextFieldValue("https://example.com/b.git"))
        assertEquals(BranchChoicesState.Closed, viewModel.branchChoices.value)
    }

    @Test
    fun `starting a clone closes the list`(): Unit = runBlocking {
        list.answerNext().complete(Either.Ok(refs))
        viewModel.chooseBranch("https://example.com/r.git")
        awaitState { it is BranchChoicesState.Loaded }

        viewModel.clone(temp.path, "https://example.com/r.git", "r", cloneSubmodules = false, branch = "feature/x")

        assertEquals(BranchChoicesState.Closed, viewModel.branchChoices.value)
        withTimeout(5_000) { while (clones.isEmpty()) delay(10) }
        assertFalse(clones.isEmpty())
    }

    @TempDir
    lateinit var temp: File

    private val failure = RemoteOperationError.Failed(128, "fatal: video.bin: smudge filter lfs failed")

    private suspend fun awaitGone(folder: File) = withTimeout(5_000) { while (folder.exists()) delay(10) }

    @Test
    fun `a clone that fails removes the folders that the dialog created for it, and only those`(): Unit =
        runBlocking {
            val existing = File(temp, "existing").apply { mkdir() }
            cloneStates = { flowOf(CloneState.Cloning("Receiving objects", 10, 100), CloneState.Fail(failure)) }

            viewModel.clone(File(existing, "new/deeper").path, "/tmp/r", "r", cloneSubmodules = false)
            // The folders are made before the clone starts, and removed after its failure
            assertTrue(withTimeout(5_000) { viewModel.error.first { it != null } } is CloneUiError.CloneError)

            awaitGone(File(existing, "new"))
            assertTrue(existing.isDirectory)
        }

    @Test
    fun `a cancelled clone removes them too`(): Unit = runBlocking {
        cloneStates = { flow { emit(CloneState.Cloning("Receiving objects", 10, 100)); awaitCancellation() } }

        viewModel.clone(File(temp, "new/deeper").path, "/tmp/r", "r", cloneSubmodules = false)
        withTimeout(5_000) { viewModel.cloneState.first { it is CloneState.Cloning } }
        assertTrue(File(temp, "new/deeper").isDirectory)

        viewModel.cancelClone()

        awaitGone(File(temp, "new"))
        assertTrue(temp.isDirectory)
    }

    @Test
    fun `a completed clone keeps them`(): Unit = runBlocking {
        // No clone folder in them, so that only the outcome keeps them
        cloneStates = { flowOf(CloneState.Completed(it)) }

        viewModel.clone(File(temp, "new/deeper").path, "/tmp/r", "r", cloneSubmodules = false)
        withTimeout(5_000) { viewModel.cloneState.first { it is CloneState.Completed } }
        delay(200)

        assertTrue(File(temp, "new/deeper").isDirectory)
    }

    @Test
    fun `a folder that isn't empty stays, with the folders above it`(): Unit = runBlocking {
        // As a clone whose submodules failed is kept
        cloneStates = { folder ->
            flow {
                File(folder, "README.md").apply { parentFile.mkdirs() }.writeText("kept")
                emit(CloneState.Fail(failure))
            }
        }

        viewModel.clone(File(temp, "new/deeper").path, "/tmp/r", "r", cloneSubmodules = false)
        withTimeout(5_000) { viewModel.error.first { it != null } }
        delay(200)

        assertTrue(File(temp, "new/deeper/r/README.md").isFile)
    }

    @Test
    fun `a folder that was there before stays, even empty`(): Unit = runBlocking {
        val chosen = File(temp, "chosen").apply { mkdir() }
        cloneStates = { flowOf(CloneState.Fail(failure)) }

        viewModel.clone(chosen.path, "/tmp/r", "r", cloneSubmodules = false)
        withTimeout(5_000) { viewModel.error.first { it != null } }
        delay(200)

        assertTrue(chosen.isDirectory)
    }
}
