// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.editor.DEFAULT_APP_EDITOR
import dev.app.leaf.domain.editor.EditorKind
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.repositoryopen.EditorPickerState
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The picker of "Open in another editor…" (fork-only): the editors, the default app, and whether to keep the choice.
 * Set `LEAF_RENDER_DIR` and pass `--rerun` for PNGs of it in both themes.
 */
class EditorPickerTest {
    private var scene: ImageComposeScene? = null
    private val picks = mutableListOf<Pair<String, Boolean>>()
    private var closes = 0

    private val editors = listOf(
        InstalledEditor("intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS, tool = null),
        InstalledEditor("vscode", "Visual Studio Code", EditorKind.VS_CODE, tool = null),
        InstalledEditor("zed", "Zed", EditorKind.ZED, tool = null),
    )

    private fun state(defaultEditor: String? = "intellij-idea", repositoryName: String? = "leaf") = EditorPickerState(
        path = "app/src/main/kotlin/dev/app/leaf/ui/diff/DiffLineActions.kt",
        line = null,
        editors = editors,
        defaultEditor = defaultEditor,
        repositoryEditor = null,
        repositoryName = repositoryName,
        isAtMenu = true,
    )

    @AfterEach
    fun close() {
        scene?.close()
    }

    private suspend fun show(state: EditorPickerState, theme: Theme = Theme.Light) {
        scene?.close()
        scene = ImageComposeScene(width = 360, height = 420, density = Density(1f)) {
            AppTheme(selectedTheme = theme) {
                Box(Modifier.background(MaterialTheme.colors.surface).padding(16.dp)) {
                    EditorPickerList(state, onPick = { id, keep -> picks += id to keep }, onClose = { closes++ })
                }
            }
        }
        settle()
    }

    private suspend fun settle() {
        repeat(4) {
            scene!!.render()
            delay(20)
        }
    }

    @Test
    fun `it lists the editors, the default app for the file's type, and marks the default`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(state())

            val rows = rowTexts()
            assertEquals(
                listOf(listOf("IntelliJ IDEA", "Default"), listOf("Visual Studio Code"), listOf("Zed"), listOf("Default app for .kt files")),
                rows,
            )
            assertTrue("Use for leaf from now on" in texts(), texts().toString())
        }

    @Test
    fun `with no default yet, the first pick is kept as it, which the box says`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state(defaultEditor = null))

        assertTrue("Use from now on" in texts(), texts().toString())
        click(rowNode("Zed"))

        assertEquals(listOf("zed" to true), picks)
    }

    @Test
    fun `with a default, a pick isn't kept unless the box is checked`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state())

        click(rowNode("Visual Studio Code"))
        click(nodes().first { "Use for leaf from now on" in it.texts() && SemanticsActions.OnClick in it.config })
        click(rowNode("Default app for .kt files"))

        assertEquals(listOf("vscode" to false, DEFAULT_APP_EDITOR to true), picks)
    }

    @Test
    fun `the arrows move through the rows, Enter picks, and Esc closes`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state())

        press(Key.Enter)
        assertEquals(emptyList<Pair<String, Boolean>>(), picks, "nothing highlighted yet")

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(listOf("vscode" to false), picks)

        press(Key.Escape)
        assertEquals(1, closes)
    }

    @Test
    fun `renders in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        val dir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            show(state(), theme)
            File(dir, "editor-picker-${theme.name.lowercase()}.png")
                .writeBytes(scene!!.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private suspend fun click(node: SemanticsNode) {
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        settle()
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private suspend fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        settle()
    }

    private fun rowNode(name: String) = mergedNodes().first { it.texts().firstOrNull() == name && SemanticsActions.OnClick in it.config }

    private fun rowTexts() = mergedNodes()
        .filter { SemanticsProperties.Selected in it.config }
        .map { it.texts() }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun mergedNodes(): List<SemanticsNode> = nodes()

    private fun texts(): List<String> = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(false) }
        .flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
