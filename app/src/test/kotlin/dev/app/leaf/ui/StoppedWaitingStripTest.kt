// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.remote.StoppedWaitingState
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The strip under the toolbar after Stop waiting (fork-only): what it says and offers in each state, for a push and a
 * delete. Set `LEAF_RENDER_DIR` for PNGs in both themes.
 */
class StoppedWaitingStripTest {
    private val before = "8180de8".padEnd(40, '1')
    private val pushed = "7b6ee23".padEnd(40, '2')
    private val theirs = "1a2b3c4".padEnd(40, '3')
    private val push = ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", pushed, before)
    private val delete = ServerHandOff("origin", "refs/heads/feature/x", "refs/remotes/origin/feature/x", null, before)

    @Test
    fun `while it asks, it says so, and can stop`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(StoppedWaitingState.Asking(push)).use { strip ->
            assertEquals("Stopped waiting. Asking origin whether it took the push of main…", strip.message())
            assertFalse(strip.hasButton("Close"))

            strip.click("Stop checking")

            assertEquals(listOf("stop checking"), strip.calls)
        }

        Strip(StoppedWaitingState.Asking(delete)).use { strip ->
            assertEquals("Stopped waiting. Asking origin whether it deleted feature/x…", strip.message())
        }
    }

    @Test
    fun `the server took the push, or deleted the branch`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(StoppedWaitingState.Done(push)).use { strip ->
            assertEquals("origin took the push: main is at 7b6ee23.", strip.message())
            assertFalse(strip.hasButton("Fetch"))

            strip.click("Close")

            assertEquals(listOf("dismiss"), strip.calls)
        }

        Strip(StoppedWaitingState.Done(delete)).use { strip ->
            assertEquals("origin deleted feature/x.", strip.message())
        }
    }

    @Test
    fun `the server hasn't, at the commit from before, at another, or without the branch`(): Unit = runBlocking(Dispatchers.Swing) {
        val cases = mapOf(
            StoppedWaitingState.NotDone(push, before) to "origin hasn't taken the push of main: it's still at 8180de8.",
            StoppedWaitingState.NotDone(push, theirs) to "origin hasn't taken the push of main: it's at 1a2b3c4.",
            StoppedWaitingState.NotDone(push.copy(before = null), null) to "origin hasn't taken the push: it has no branch main.",
            StoppedWaitingState.NotDone(delete, before) to "feature/x is still on origin.",
            StoppedWaitingState.NotDone(delete, theirs) to "feature/x is still on origin, at 1a2b3c4 now.",
        )

        for ((state, message) in cases) {
            Strip(state).use { strip ->
                assertEquals(message, strip.message())

                strip.click("Fetch")

                assertEquals(listOf("fetch"), strip.calls)
            }
        }
    }

    @Test
    fun `nothing tells, so it offers to fetch`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(StoppedWaitingState.Unknown(push)).use { strip ->
            assertEquals("Stopped waiting. Fetch to see whether origin took the push of main.", strip.message())
            assertTrue(strip.hasButton("Fetch"))
        }

        Strip(StoppedWaitingState.Unknown(delete)).use { strip ->
            assertEquals("Stopped waiting. Fetch to see whether origin deleted feature/x.", strip.message())
        }
    }

    @Test
    fun `the server couldn't be asked, and Try again asks it again`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(StoppedWaitingState.Failed(push, GenericError("Could not resolve host: example.com\nmore detail"))).use { strip ->
            assertEquals("Couldn't ask origin about main: Could not resolve host: example.com", strip.message())

            strip.click("Try again")
            strip.click("Close")

            assertEquals(listOf("try again", "dismiss"), strip.calls)
        }
    }

    @Test
    fun `renders each state in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        val states = mapOf(
            "asking" to StoppedWaitingState.Asking(push),
            "done" to StoppedWaitingState.Done(push),
            "not-done" to StoppedWaitingState.NotDone(push, before),
            "unknown" to StoppedWaitingState.Unknown(delete),
            "failed" to StoppedWaitingState.Failed(push, GenericError("Could not resolve host: example.com")),
        )

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            for ((name, state) in states) {
                Strip(state, theme).use { strip ->
                    File(folder, "stopped-waiting-$name-${theme.name.lowercase()}.png")
                        .writeBytes(strip.scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    private class Strip(state: StoppedWaitingState, theme: Theme = Theme.Dark) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = 900, height = 48, density = Density(1f)) {
            AppTheme(theme) {
                StoppedWaitingStrip(
                    state = state,
                    onStopChecking = { calls += "stop checking" },
                    onFetch = { calls += "fetch" },
                    onTryAgain = { calls += "try again" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            scene.render()
        }

        /** The strip's text, which isn't a button's. */
        fun message(): String = nodes()
            .filter { it.config.getOrNull(SemanticsActions.OnClick) == null }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .single()

        fun hasButton(text: String): Boolean = clickables(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickables(text).first().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun clickables(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } ||
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == text })
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}
