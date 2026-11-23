// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.editor.EditorChoice
import dev.app.leaf.domain.editor.EditorKind
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.Blame
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.TextEntry
import dev.app.leaf.ui.context_menu.lastContextMenuPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

private const val PATH = "app/src/main/kotlin/dev/app/leaf/ui/diff/DiffLineActions.kt"

/** A header's file name that opens the file's items (fork-only), and what the shortcut opens. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class FileNameMenuTest {
    @TempDir
    lateinit var dir: File

    private var scene: ImageComposeScene? = null
    private val calls = mutableListOf<String>()
    private val intellij = InstalledEditor("intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS, tool = "/bin/idea")

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun opening(choice: EditorChoice = EditorChoice.Installed(intellij)) = FileOpening(
        choice = choice,
        onOpen = { path, line -> calls += "open $path $line" },
        onChooseEditor = { path, line -> calls += "choose $path $line" },
        onShow = { path -> calls += "show $path" },
    )

    private val showLabel = when (currentOs) {
        OS.MAC -> "Show in Finder"
        OS.WINDOWS -> "Show in Explorer"
        else -> "Show in file manager"
    }

    private fun show(content: @Composable () -> Unit) {
        scene = ImageComposeScene(width = 900, height = 300, density = Density(1f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = Theme.Light) {
                content()
            }
        }.also { it.render() }
    }

    /** The name as the diff's header has it: in a selectable path, with selection turned off for the name. */
    @Composable
    private fun HeaderName(opening: FileOpening?, canOpen: Boolean = true, line: () -> Int? = { null }) {
        CompositionLocalProvider(LocalFileOpening provides opening) {
            SelectionContainer {
                Row {
                    Text("app/src/main/kotlin/dev/app/leaf/ui/diff/")
                    DisableSelection {
                        FileNameMenu(
                            name = "DiffLineActions.kt",
                            path = PATH,
                            canOpen = canOpen,
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackground,
                            fontWeight = FontWeight.Medium,
                            line = line,
                        )
                    }
                }
            }
        }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun fileMenuIcon(): SemanticsNode? = nodes().firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("File menu")
    }

    /** Presses and releases the pointer at the middle of [node], as a click does. */
    private suspend fun clickAt(node: SemanticsNode) {
        val center = node.boundsInRoot.center
        scene!!.sendPointerEvent(PointerEventType.Move, center)
        scene!!.sendPointerEvent(PointerEventType.Press, center)
        scene!!.sendPointerEvent(PointerEventType.Release, center)
        settle()
    }

    private suspend fun settle() {
        repeat(4) {
            scene!!.render()
            delay(20)
        }
    }

    private fun item(label: String): SemanticsNode = nodes().single { node ->
        node.config.contains(SemanticsActions.OnClick) &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }

    @Test
    fun `outside a tab, the name is only its name`(): Unit = runBlocking(Dispatchers.Swing) {
        show { HeaderName(opening = null) }

        assertTrue("DiffLineActions.kt" in texts())
        assertEquals(null, fileMenuIcon())
    }

    @Test
    fun `a click on the name opens the file's items, at the line in view when it opens`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var line = 12
            show { HeaderName(opening = opening(), line = { line }) }
            settle()

            val icon = requireNotNull(fileMenuIcon()) { "the ⌄ after the name" }
            line = 40
            clickAt(icon)

            assertTrue("Open in IntelliJ IDEA at line 40" in texts(), texts().toString())
            assertTrue("Open in another editor…" in texts())
            assertTrue(showLabel in texts())

            val opened = requireNotNull(lastContextMenuPosition) { "the picker of another editor opens where the menu is" }
            assertTrue(opened.y >= icon.boundsInRoot.bottom - 1, "under the name: $opened, ${icon.boundsInRoot}")

            item("Open in IntelliJ IDEA at line 40").config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(listOf("open $PATH 40"), calls)
        }

    @Test
    fun `a file that the working tree doesn't have only shows in the file manager`(): Unit = runBlocking(Dispatchers.Swing) {
        show { HeaderName(opening = opening(), canOpen = false) }

        clickAt(fileMenuIcon()!!)

        assertTrue(showLabel in texts())
        assertEquals(false, texts().any { it.startsWith("Open in") }, texts().toString())
        item(showLabel).config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("show $PATH"), calls)
    }

    @Test
    fun `blame's name opens the file's items, and the shortcut opens blame's file at the first line`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Git.init().setDirectory(dir).call().use { git ->
                File(dir, "notes").mkdirs()
                File(dir, "notes/todo.txt").writeText((1..80).joinToString("\n") { "line $it" } + "\n")
                git.add().addFilepattern("notes/todo.txt").call()
                val ident = PersonIdent("Maya Chen", "maya.chen@example.com")
                git.commit().setMessage("Start the notes").setSign(false).setAuthor(ident).setCommitter(ident).call()
                val blame = git.blame().setFilePath("notes/todo.txt").call()!!

                show {
                    CompositionLocalProvider(LocalFileOpening provides opening()) {
                        Blame("notes/todo.txt", blame, onSelectCommit = {}, onClose = {})
                    }
                }
                settle()

                assertTrue("notes/" in texts() && "todo.txt" in texts(), texts().toString())
                assertNotNull(fileMenuIcon(), "blame's name has the ⌄")

                val mac = currentOs == OS.MAC
                scene!!.sendKeyEvent(KeyEvent(Key.E, KeyEventType.KeyDown, isMetaPressed = mac, isCtrlPressed = !mac))
                scene!!.sendKeyEvent(KeyEvent(Key.E, KeyEventType.KeyUp, isMetaPressed = mac, isCtrlPressed = !mac))

                assertEquals(listOf("open notes/todo.txt 1"), calls)
            }
        }

    @Test
    fun `an editor's item shows its own icon as it is, and the box with an arrow without one`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val red = Color(0xFFE0262C)
            val icns = File(dir, "Editor.icns").apply { writeBytes(icnsOf(solidPng(red, 32))) }
            val withIcon = EditorChoice.Installed(intellij.copy(icon = icns.path))

            fun pixelOf(choice: EditorChoice): Int {
                show { Box(Modifier.size(16.dp)) { EditorMenuIcon(choice) } }
                runBlocking { settle() }
                val pixel = scene!!.render().peekPixels()!!.getColor(8, 8)
                scene!!.close()
                scene = null
                return pixel
            }

            assertEquals(red.toArgb(), pixelOf(withIcon), "drawn as it is, not tinted")
            assertNotEquals(red.toArgb(), pixelOf(EditorChoice.Installed(intellij)))
            assertNotEquals(red.toArgb(), pixelOf(EditorChoice.DefaultApp))
        }

    @Test
    fun `a menu entry draws its own icon as it is`(): Unit = runBlocking(Dispatchers.Swing) {
        val red = Color(0xFFE0262C)
        val entry = ContextMenuElement.ContextTextEntry(
            composableLabel = { "Open in IntelliJ IDEA" },
            customIcon = { Box(Modifier.fillMaxSize().background(red)) },
        )

        show { Box(Modifier.width(300.dp)) { TextEntry(showIcons = true, contextTextEntry = entry, onDismissRequest = {}) } }
        settle()

        val icon = scene!!.render().peekPixels()!!
        // The icon's box starts 8 dp in and is 16 dp wide, in the entry's 8 dp of padding
        assertEquals(red.toArgb(), icon.getColor(16, 16))
    }

    @Test
    fun `the shortcut opens the diff's file, blame's own, or nothing under the resolver and file history`() {
        assertEquals(OpenInEditorTarget.DIFF, openInEditorTarget(false, false, false, hasDiffFile = true))
        assertEquals(OpenInEditorTarget.NONE, openInEditorTarget(false, false, false, hasDiffFile = false))
        assertEquals(OpenInEditorTarget.BLAME, openInEditorTarget(false, false, isBlameExpanded = true, hasDiffFile = true))
        assertEquals(OpenInEditorTarget.NONE, openInEditorTarget(isResolverShown = true, false, true, true))
        assertEquals(OpenInEditorTarget.NONE, openInEditorTarget(false, isHistoryShown = true, true, true))
    }

    @Test
    fun `renders the name with its ⌄ in both themes when asked`(): Unit = runBlocking(Dispatchers.Swing) {
        val renderDir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            scene = ImageComposeScene(width = 1068, height = 68, density = Density(2f), coroutineContext = Dispatchers.Swing) {
                AppTheme(selectedTheme = theme) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.surface).padding(start = 16.dp),
                    ) {
                        HeaderName(opening = opening())
                    }
                }
            }
            settle()
            File(renderDir, "file-name-menu-${theme.name.lowercase()}.png")
                .writeBytes(scene!!.render().encodeToData()!!.bytes)
            scene!!.close()
            scene = null
        }
    }

    private fun solidPng(color: Color, size: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(size, size)
        surface.canvas.clear(color.toArgb())
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** An icon file with one PNG image, as macOS keeps the larger sizes. */
    private fun icnsOf(png: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("icns")
            out.writeInt(8 + 8 + png.size)
            out.writeBytes("ic11")
            out.writeInt(8 + png.size)
            out.write(png)
        }
        return bytes.toByteArray()
    }
}

private val androidx.compose.ui.geometry.Rect.center get() = Offset((left + right) / 2, (top + bottom) / 2)
