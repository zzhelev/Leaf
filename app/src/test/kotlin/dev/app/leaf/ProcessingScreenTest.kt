// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.remote.STOP_WAITING_AFTER
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The processing screen's button (fork-only): Cancel until git has handed the server a push, none for a while after,
 * then Stop waiting, which a later frame brings without a new progress. Set `LEAF_RENDER_DIR` for PNGs in both themes.
 */
class ProcessingScreenTest {
    private val handOff = ServerHandOff("origin", "refs/heads/main", "refs/remotes/origin/main", "a".repeat(40), null)
    private val handedOverAt = 1_000_000L

    @Test
    fun `a task without progress has no button`(): Unit = runBlocking(Dispatchers.Swing) {
        Screen(progress = null).use { screen ->
            assertFalse(screen.hasButton("Cancel"))
            assertFalse(screen.hasButton("Stop waiting"))
        }
    }

    @Test
    fun `while git still uploads, Cancel stops it`(): Unit = runBlocking(Dispatchers.Swing) {
        Screen(TaskProgress("Writing objects", 45)).use { screen ->
            assertTrue("Writing objects 45%" in screen.texts(), screen.texts().toString())

            screen.click("Cancel")

            assertEquals(1, screen.cancels)
        }
    }

    @Test
    fun `once the server has the push, there is no button, and it says what Leaf waits for`(): Unit = runBlocking(Dispatchers.Swing) {
        Screen(TaskProgress(null, null, handOff, handedOverAt), now = handedOverAt + 1_000).use { screen ->
            assertTrue("Waiting for origin" in screen.texts(), screen.texts().toString())
            assertFalse(screen.hasButton("Cancel"))
            assertFalse(screen.hasButton("Stop waiting"))
        }
    }

    @Test
    fun `the server's own progress shows after the hand-off`(): Unit = runBlocking(Dispatchers.Swing) {
        Screen(TaskProgress("Resolving deltas", 50, handOff, handedOverAt), now = handedOverAt).use { screen ->
            assertTrue("Resolving deltas 50%" in screen.texts(), screen.texts().toString())
        }
    }

    @Test
    fun `ten seconds after the hand-off, Stop waiting comes, without a new progress`(): Unit = runBlocking(Dispatchers.Swing) {
        val progress = TaskProgress(null, null, handOff, handedOverAt)

        Screen(progress, now = handedOverAt + STOP_WAITING_AFTER.inWholeMilliseconds - 200).use { screen ->
            assertFalse(screen.hasButton("Stop waiting"))

            screen.now = handedOverAt + STOP_WAITING_AFTER.inWholeMilliseconds
            delay(600)
            screen.scene.render()

            assertTrue(screen.hasButton("Stop waiting"), screen.texts().toString())
            screen.click("Stop waiting")
            assertEquals(1, screen.cancels)
        }
    }

    @Test
    fun `the progress stays where it was while the button is away`(): Unit = runBlocking(Dispatchers.Swing) {
        val withButton = Screen(TaskProgress("Writing objects", null)).use { it.progressBar() }
        val without = Screen(TaskProgress("Resolving deltas", null, handOff, handedOverAt), now = handedOverAt).use { it.progressBar() }

        assertEquals(withButton, without)
    }

    @Test
    fun `renders each state in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        val states = mapOf(
            "cancel" to (TaskProgress("Writing objects", 45) to 0L),
            "waiting" to (TaskProgress(null, null, handOff, handedOverAt) to handedOverAt),
            "stop-waiting" to (TaskProgress(null, null, handOff, handedOverAt) to handedOverAt + 20_000),
        )

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            for ((name, state) in states) {
                Screen(state.first, now = state.second, theme = theme).use { screen ->
                    File(folder, "processing-$name-${theme.name.lowercase()}.png")
                        .writeBytes(screen.scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    private class Screen(progress: TaskProgress?, var now: Long = 0L, theme: Theme = Theme.Dark) : AutoCloseable {
        var cancels = 0

        val scene = ImageComposeScene(width = 640, height = 400, density = Density(1f)) {
            AppTheme(theme) {
                ProcessingScreen(
                    processingTask = TaskType.Push,
                    progress = progress,
                    onCancelOnGoingTask = { cancels++ },
                    clock = { now },
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun hasButton(text: String): Boolean = clickables(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickables(text).first().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun progressBar(): Rect = nodes().single { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }.boundsInRoot

        private fun clickables(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
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
