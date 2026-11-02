// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.ui.status.AmendedFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(name: String, message: String = "Subject of $name") = Commit(
    hash = name.padEnd(40, '0'),
    message = message,
    committer = someone,
    author = someone,
    date = 1_760_000_000,
    parentsHashes = emptyList(),
)

private class ListedFile(type: ChangeType, path: String) : DiffEntry() {
    init {
        changeType = type
        oldPath = path
        newPath = path
    }
}

private fun modified(path: String): DiffEntry = ListedFile(ChangeType.MODIFY, path)

/**
 * The files that the Staged pane's group lists while Amend is on (fork-only, upstream issue 260): read when the amended
 * commit changes, and not when anything else does, such as a status refresh, which sends the same commit again.
 */
class AmendedFilesControllerTest {
    // Unconfined: the collector starts and runs on the thread that sets a flow, so a test reads what it left
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val target = MutableStateFlow<Commit?>(null)
    private val loads = AtomicInteger()
    private var answer: suspend (Commit) -> Either<List<DiffEntry>, dev.app.leaf.domain.errors.AppError> =
        { Either.Ok(listOf(modified("src/${it.hash.take(2)}.kt"))) }

    private val controller = AmendedFilesController(
        scope = scope,
        target = target,
        load = { commit ->
            loads.incrementAndGet()
            answer(commit)
        },
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private val files: AmendedFiles? get() = controller.files.value

    private fun eventually(message: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(3_000) {
                while (!condition()) delay(5)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out: $message")
        }
    }

    private fun quiet() = runBlocking { delay(120) }

    @Test
    fun `nothing is amended, so there are no files`() {
        quiet()

        assertNull(files)
        assertEquals(0, loads.get())
    }

    @Test
    fun `the amended commit's files are read when Amend turns on`() {
        val head = commit("a1")

        target.value = head
        eventually("the files") { files?.isLoading == false }

        assertEquals(head, files?.commit)
        assertEquals(listOf("src/a1.kt"), files?.changes?.map { it.newPath })
        assertNull(files?.error)
        assertEquals(1, loads.get())
    }

    @Test
    fun `turning Amend off takes the files away`() {
        target.value = commit("a1")
        eventually("the files") { files?.isLoading == false }

        target.value = null
        eventually("no files") { files == null }
    }

    @Test
    fun `a refresh that sends the same commit again reads nothing`() {
        target.value = commit("a1")
        eventually("the files") { files?.isLoading == false }
        val shown = files

        // A status refresh gives the same HEAD as a new object, and the same again
        target.value = commit("a1").copy(message = "Subject of a1")
        target.value = commit("a1", message = "Another object, the same commit")
        quiet()

        assertEquals(1, loads.get())
        assertSame(shown, files)
    }

    @Test
    fun `another commit reads its files, and the first one's stay while they load`() {
        target.value = commit("a1")
        eventually("the first files") { files?.isLoading == false }
        val firstFiles = files!!.changes

        val gate = CompletableDeferred<Unit>()
        answer = { commit ->
            gate.await()
            Either.Ok(listOf(modified("src/${commit.hash.take(2)}.kt")))
        }

        target.value = commit("b2")
        eventually("loading the second") { files?.commit?.hash == commit("b2").hash }

        assertTrue(files!!.isLoading)
        assertEquals(firstFiles, files!!.changes)

        gate.complete(Unit)
        eventually("the second files") { files?.isLoading == false }

        assertEquals(listOf("src/b2.kt"), files!!.changes.map { it.newPath })
        assertEquals(2, loads.get())
    }

    @Test
    fun `the last commit wins when the head moves while a read is running`() {
        val firstGate = CompletableDeferred<Unit>()
        answer = { commit ->
            if (commit.hash == commit("a1").hash) firstGate.await()
            Either.Ok(listOf(modified("src/${commit.hash.take(2)}.kt")))
        }

        target.value = commit("a1")
        target.value = commit("b2")
        eventually("the second files") { files?.commit?.hash == commit("b2").hash && files?.isLoading == false }

        firstGate.complete(Unit)
        quiet()

        assertEquals(listOf("src/b2.kt"), files!!.changes.map { it.newPath })
        assertFalse(files!!.isLoading)
    }

    @Test
    fun `a failure keeps the commit and says so, and Try again reads it again`() {
        var fails = true
        answer = { commit ->
            if (fails) Either.Err(GenericError("no objects")) else Either.Ok(listOf(modified("src/${commit.hash.take(2)}.kt")))
        }

        target.value = commit("a1")
        eventually("the failure") { files?.error != null }

        assertEquals(commit("a1"), files!!.commit)
        assertTrue(files!!.changes.isEmpty())
        assertFalse(files!!.isLoading)

        fails = false
        controller.retry()
        eventually("the files") { files?.error == null && files?.isLoading == false }

        assertNotNull(files)
        assertEquals(listOf("src/a1.kt"), files!!.changes.map { it.newPath })
        assertEquals(2, loads.get())
    }

    @Test
    fun `turning Amend off and on again reads again, with nothing left over`() {
        target.value = commit("a1")
        eventually("the files") { files?.isLoading == false }
        target.value = null
        eventually("no files") { files == null }

        val gate = CompletableDeferred<Unit>()
        answer = { commit ->
            gate.await()
            Either.Ok(listOf(modified("src/${commit.hash.take(2)}.kt")))
        }
        target.value = commit("a1")
        eventually("loading") { files?.isLoading == true }

        assertTrue(files!!.changes.isEmpty())
        gate.complete(Unit)
        eventually("the files") { files?.isLoading == false }
        assertEquals(2, loads.get())
    }
}
