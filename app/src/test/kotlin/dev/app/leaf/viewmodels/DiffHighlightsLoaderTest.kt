// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

class DiffHighlightsLoaderTest {
    /** Colours each text with a result of its own, once [gate] lets it, and notes what it was asked and what stopped. */
    private class GatedAction : IHighlightTextAction {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var gate = CompletableDeferred(Unit)

        override suspend fun invoke(scopeName: String, text: String): HighlightedText {
            asked += text
            try {
                gate.await()
            } catch (ex: kotlinx.coroutines.CancellationException) {
                cancelled += text
                throw ex
            }
            return HighlightedText.Builder().build(1)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val action = GatedAction()
    private val loader = DiffHighlightsLoader(scope, HighlightTextUseCase(action))

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun entry(path: String) = object : DiffEntry() {
        init {
            changeType = ChangeType.MODIFY
            oldPath = path
            newPath = path
        }
    }

    /** A diff of [path] from [old] to [new], read anew: other instances each time. */
    private fun diff(old: String, new: String, path: String = "Main.kt"): ViewDiffResult.Loaded {
        val hunk = Hunk("@@ -1 +1 @@", listOf(Line(new, 0, 0, LineType.ADDED)), RawText(old.toByteArray()), RawText(new.toByteArray()))
        val entry = entry(path)
        return ViewDiffResult.Loaded(DiffType.CommitDiff(entry), DiffResult.Text(entry, listOf(hunk)))
    }

    private val ViewDiffResult.Loaded.hunk get() = (diffResult as DiffResult.Text).hunks.single()

    private fun HighlightedVersions.coloured(diff: ViewDiffResult.Loaded) =
        listOfNotNull(versionOf(diff.hunk.oldText)?.highlighted, versionOf(diff.hunk.newText)?.highlighted)

    private fun awaitColoured(diff: ViewDiffResult.Loaded): HighlightedVersions = runBlocking {
        withTimeout(5.seconds) { loader.versions.first { it.coloured(diff).size == 2 } }
    }

    @Test
    fun `a diff's versions are coloured in the background`() {
        action.gate = CompletableDeferred()
        val first = diff("val a = 1\n", "val a = 2\n")

        loader.show(first)

        // The text shows at once, its colours follow
        assertEquals(emptyList<HighlightedText>(), loader.versions.value.coloured(first))
        assertNotNull(loader.versions.value.versionOf(first.hunk.oldText))
        action.gate.complete(Unit)
        awaitColoured(first)
        assertEquals(listOf("val a = 1\n", "val a = 2\n"), action.asked.sorted())
    }

    @Test
    fun `a diff loaded again keeps its versions' colours at once, and the diff before stays until the next`() {
        val first = diff("val a = 1\n", "val a = 2\n")
        loader.show(first)
        val coloured = awaitColoured(first)

        val again = diff("val a = 1\n", "val a = 2\n")
        loader.show(again)

        // In the same call, before the view gets it: nothing to flash plain
        val versions = loader.versions.value
        assertEquals(2, versions.coloured(again).size)
        assertSame(coloured.versionOf(first.hunk.oldText)!!.highlighted, versions.versionOf(again.hunk.oldText)!!.highlighted)
        assertEquals(2, action.asked.size)
        // The view may still draw the diff before
        assertEquals(2, versions.coloured(first).size)

        loader.show(diff("val b = 1\n", "val b = 2\n"))
        assertNull(loader.versions.value.versionOf(first.hunk.oldText))
        assertNotNull(loader.versions.value.versionOf(again.hunk.oldText))
    }

    @Test
    fun `a version of another file, or with other bytes, is coloured again`() {
        val first = diff("val a = 1\n", "val a = 2\n")
        loader.show(first)
        awaitColoured(first)

        val renamed = diff("val a = 1\n", "val a = 2\n", path = "Other.kt")
        loader.show(renamed)
        assertEquals(emptyList<HighlightedText>(), loader.versions.value.coloured(renamed))
        awaitColoured(renamed)

        // The new version changed, the old one didn't
        val edited = diff("val a = 1\n", "val a = 3\n", path = "Other.kt")
        loader.show(edited)
        assertNotNull(loader.versions.value.versionOf(edited.hunk.oldText)!!.highlighted)
        assertNull(loader.versions.value.versionOf(edited.hunk.newText)!!.highlighted)
        awaitColoured(edited)
        assertEquals(listOf("val a = 1\n", "val a = 1\n", "val a = 2\n", "val a = 2\n", "val a = 3\n"), action.asked.sorted())
    }

    @Test
    fun `the next diff stops the colouring of the one before`(): Unit = runBlocking {
        action.gate = CompletableDeferred()
        val first = diff("val a = 1\n", "val a = 2\n")
        loader.show(first)
        withTimeout(5.seconds) { while (action.asked.size < 2) kotlinx.coroutines.delay(10) }

        val next = diff("val b = 1\n", "val b = 2\n")
        loader.show(next)
        withTimeout(5.seconds) { while (action.cancelled.size < 2) kotlinx.coroutines.delay(10) }
        action.gate.complete(Unit)
        awaitColoured(next)

        assertEquals(listOf("val a = 1\n", "val a = 2\n"), action.cancelled.sorted())
        assertEquals(emptyList<HighlightedText>(), loader.versions.value.coloured(first))
    }

    @Test
    fun `a diff without text keeps the one before, and a file that nothing names asks nothing`() {
        val first = diff("val a = 1\n", "val a = 2\n")
        loader.show(first)
        awaitColoured(first)

        loader.show(ViewDiffResult.DiffNotFound(null))
        assertEquals(2, loader.versions.value.coloured(first).size)

        val plain = diff("a\n", "b\n", path = "LICENSE")
        loader.show(plain)
        loader.show(diff("a\n", "b\n", path = "LICENSE"))
        assertTrue(action.asked.none { it == "a\n" || it == "b\n" }, "${action.asked}")
    }
}
