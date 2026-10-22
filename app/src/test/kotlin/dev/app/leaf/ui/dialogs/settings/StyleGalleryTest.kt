// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleLibraryError
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.domain.styles.UnreadableStyle
import dev.app.leaf.domain.styles.UnreadableStyleFile
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.styles.resolveStyleSide
import dev.app.leaf.ui.styles.StyleEditorStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Settings → Appearance → Style's gallery (fork-only), rendered offscreen and clicked. */
class StyleGalleryTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<StyleGalleryAction>()
    private val exportAsked = mutableListOf<String>()
    private var exportPath: String? = "/tmp/out/Teal.json"
    private var importPath: String? = "/tmp/in/Solarized.json"
    private var pendingDelete: Pair<StyleDefinition, () -> Unit>? = null
    private val opened = mutableListOf<StyleEditorStart>()

    private val teal = StyleDefinition(
        id = "teal",
        name = "Teal",
        look = AccentStyle.TONAL,
        light = StyleSide(
            BuiltInStyles.TONAL.light.seeds
                .with(StyleSeed.BACKGROUND, 0xFFF0FAF9.toInt())
                .with(StyleSeed.ACCENT, 0xFF007475.toInt()),
        ),
        dark = StyleSide(BuiltInStyles.TONAL.dark.seeds, mapOf(StyleSlot.BACKGROUND to 0xFF002B36.toInt())),
    )

    private val host = StyleGalleryHost(
        pickExportPath = { name -> exportAsked += name; exportPath },
        pickImportPath = { importPath },
        confirmDelete = { style, onConfirm -> pendingDelete = style to onConfirm },
        openEditor = { opened += it },
    )

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(state: StyleGalleryState) {
        scene?.close()
        scene = ImageComposeScene(width = 800, height = 520, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Box(Modifier.width(768.dp)) {
                    StyleGallery(state, host, onAction = { actions += it })
                }
            }
        }.also { it.render() }
    }

    private fun state(selectedId: String = "tonal", notice: StyleNotice? = null, unreadable: List<UnreadableStyleFile> = emptyList()) =
        StyleGalleryState(BuiltInStyles.ALL + teal, selectedId, unreadable, notice)

    @Test
    fun `every style has a card with its name, the built-in ones say so, and Import comes last`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(state())

            for (name in listOf("Tonal", "Classic", "Quiet", "Teal", "+ New style", "starts from Tonal", "Import style…", "a .json file")) {
                assertTrue(name in texts(), "$name in ${texts()}")
            }
            assertEquals(3, texts().count { it == "built in" })
            assertTrue(boundsOf("Import style…").top > boundsOf("Tonal").top, "Import is on the second row")
            assertTrue(boundsOf("Import style…").left > boundsOf("+ New style").left, "after New")
        }

    @Test
    fun `the selected style's card is the one marked selected, and a click selects another`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(state(selectedId = "teal"))

            val selected = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            val notSelected = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) == false }
            assertEquals(1, selected.size)
            assertEquals(3, notSelected.size)

            // The preview of the fourth card, Teal's, is the selected one
            assertTrue(selected.single().boundsInRoot.left > boundsOf("Quiet").right, "${selected.single().boundsInRoot}")

            click(notSelected.first())
            assertEquals(listOf<StyleGalleryAction>(StyleGalleryAction.Select(BuiltInStyles.TONAL)), actions)
        }

    @Test
    fun `a card shows its style's light side on the left and its dark side on the right`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(state())
            val card = nodes().filter { it.config.getOrNull(SemanticsProperties.Selected) != null }[3].boundsInRoot
            val bitmap = Bitmap.makeFromImage(scene!!.render())
            val light = resolveStyleSide(teal, isLight = true).palette
            val dark = resolveStyleSide(teal, isLight = false).palette

            // The card's bounds are the preview's, inside the ring. Below the text lines, inside the margin: the background
            val y = (card.top + 55).toInt()
            assertEquals(light[StyleSlot.BACKGROUND].toArgb(), bitmap.getColor((card.left + 4).toInt(), y))
            assertEquals(0xFF002B36.toInt(), bitmap.getColor((card.center.x + 4).toInt(), y))

            // The strip along the top, and the main action along the bottom
            assertEquals(light[StyleSlot.WARNING_STRIP_FILL].toArgb(), bitmap.getColor((card.left + 20).toInt(), (card.top + 5).toInt()))
            assertEquals(dark[StyleSlot.MAIN_ACTION_FILL].toArgb(), bitmap.getColor((card.center.x + 30).toInt(), (card.top + 83).toInt()))
        }

    @Test
    fun `a built-in style's menu duplicates and exports it, and has no Edit or Delete`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state())

        openMenu(0)
        assertTrue("Duplicate" in texts() && "Export…" in texts(), "${texts()}")
        assertFalse("Delete…" in texts())
        assertFalse("Edit…" in texts())

        clickText("Duplicate")
        assertEquals(listOf<StyleEditorStart>(StyleEditorStart.From(BuiltInStyles.TONAL, "Tonal copy", "Tonal")), opened)
        assertEquals(emptyList<StyleGalleryAction>(), actions, "Nothing is saved until the editor saves it")
    }

    @Test
    fun `a user's style is edited from its menu, and a new style starts from the selected one`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(state(selectedId = "classic"))

            openMenu(3)
            assertEquals("Edit…", nodes().last { "Edit…" in it.texts() }.texts().single())
            clickText("Edit…")

            clickText("+ New style")
            assertTrue("starts from Classic" in texts(), "${texts()}")

            assertEquals(
                listOf(StyleEditorStart.Edit(teal), StyleEditorStart.From(BuiltInStyles.CLASSIC, "New style", "Classic")),
                opened,
            )
        }

    @Test
    fun `Export asks where, with the style's name, and does nothing when that's cancelled`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(state())

            openMenu(3)
            clickText("Export…")
            assertEquals(listOf("Teal.json"), exportAsked)
            assertEquals(listOf<StyleGalleryAction>(StyleGalleryAction.Export(teal, "/tmp/out/Teal.json")), actions)

            exportPath = null
            openMenu(3)
            clickText("Export…")
            assertEquals(1, actions.size)
        }

    @Test
    fun `a user's style can be deleted, once confirmed`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state())

        openMenu(3)
        clickText("Delete…")

        assertEquals(teal, pendingDelete?.first)
        assertEquals(emptyList<StyleGalleryAction>(), actions, "Nothing before the confirmation")

        pendingDelete!!.second()
        assertEquals(listOf<StyleGalleryAction>(StyleGalleryAction.Delete(teal)), actions)
    }

    @Test
    fun `Import asks for a file, and does nothing when that's cancelled`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state())

        clickText("Import style…")
        assertEquals(listOf<StyleGalleryAction>(StyleGalleryAction.Import("/tmp/in/Solarized.json")), actions)

        importPath = null
        clickText("Import style…")
        assertEquals(1, actions.size)
    }

    @Test
    fun `files that couldn't be read are named under the gallery`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state(unreadable = listOf(
            UnreadableStyleFile("broken.json", UnreadableStyle.NOT_JSON, "x"),
            UnreadableStyleFile("tonal.json", UnreadableStyle.BUILT_IN_ID, "tonal"),
        )))

        assertTrue("Not read from the styles folder: broken.json, tonal.json" in texts(), "${texts()}")
    }

    @Test
    fun `an import or export's outcome stays under the gallery until dismissed`(): Unit = runBlocking(Dispatchers.Swing) {
        val home = System.getProperty("user.home")
        val cases = listOf(
            StyleNotice.Imported("Solarized") to "Imported Solarized.",
            StyleNotice.Exported("Teal", "$home/Desktop/Teal.json") to "Exported Teal to ~/Desktop/Teal.json",
            StyleNotice.Failed(StyleLibraryError.NotAStyle("theme.json", UnreadableStyle.NOT_A_STYLE, "x")) to
                "Not imported: theme.json isn't a Leaf style.",
            StyleNotice.Failed(StyleLibraryError.CantWrite("/tmp/x/Teal.json", "Permission denied")) to
                "Couldn't write /tmp/x/Teal.json: Permission denied",
        )

        for ((notice, text) in cases) {
            actions.clear()
            show(state(notice = notice))

            assertTrue(text in texts(), "$text in ${texts()}")
            clickText("Dismiss")
            assertEquals(listOf<StyleGalleryAction>(StyleGalleryAction.DismissNotice), actions)
        }
    }

    @Test
    fun `an exported file is named after the style, without what file systems refuse`() {
        assertEquals("Teal.json", exportFileName("Teal"))
        assertEquals("Solarized Dark.json", exportFileName("Solarized Dark"))
        assertEquals("a-b-c.json", exportFileName("a/b:c"))
        assertEquals("style.json", exportFileName(" / "))
    }

    // --- Helpers

    private fun openMenu(card: Int) {
        val buttons = nodes().filter { "Style actions" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        click(buttons[card])
    }

    private fun boundsOf(text: String) = nodes().first { text in it.texts() }.boundsInRoot

    private fun clickText(text: String) = click(nodes().last { text in it.texts() })

    private fun click(node: SemanticsNode) {
        val scene = scene!!
        val position = Offset(node.boundsInWindow.center.x, node.boundsInWindow.center.y)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        scene.render()
        scene.render()
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
