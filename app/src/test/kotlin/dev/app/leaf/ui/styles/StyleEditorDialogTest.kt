// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.ui.ImageComposeScene
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
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The style editor (fork-only), rendered from a state and clicked. */
class StyleEditorDialogTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<StyleEditorAction>()
    private var closed = 0
    private val replaced = mutableListOf<StyleEditorStart>()
    private var discardAsked: String? = null
    private var deleteAsked: Pair<String, () -> Unit>? = null

    private val host = StyleEditorHost(
        close = { closed++ },
        replaceWith = { replaced += it },
        confirmDelete = { name, onConfirm -> deleteAsked = name to onConfirm },
        confirmDiscard = { name, _ -> discardAsked = name },
    )

    private val solarizedBlue = 0xFF268BD2.toInt()
    private val teal = BuiltInStyles.TONAL.copy(id = "teal", name = "Teal")

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(state: StyleEditorState, message: StyleEditorMessage? = null) {
        scene?.close()
        scene = ImageComposeScene(width = 1100, height = 720, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                StyleEditorContent(state, message, onAction = { actions += it }, host = host, pickExportPath = { "/tmp/$it" })
            }
        }.also {
            it.render()
            it.render()
        }
    }

    @Test
    fun `a style of the user's shows its name, its look and its seeds`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.editing(teal))

        assertTrue("Teal" in editableTexts(), "${editableTexts()}")
        for (text in listOf("A style of yours", "BUTTONS LOOK", "COLOURS", "Background", "Accent", "Danger", "Warning", "Success", "Advanced", "PREVIEW")) {
            assertTrue(text in texts(), "$text in ${texts()}")
        }
        assertTrue(StyleColorHex.format(BuiltInStyles.TONAL.light.seeds.accent) in texts())
        assertEquals("Tonal", selectedLook())
    }

    @Test
    fun `a new style says what it started from`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.startingFrom(BuiltInStyles.CLASSIC, "", "Classic copy", "Classic"))

        assertTrue("A new style, from Classic" in texts(), "${texts()}")
        assertEquals("Classic", selectedLook())
    }

    @Test
    fun `the look switch picks a look`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.editing(teal))

        click(mergedNodes().first { it.config.getOrNull(SemanticsProperties.Selected) == false && "Quiet" in it.texts() })
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.SetLook(AccentStyle.QUIET)), actions)
    }

    @Test
    fun `a seed's swatch opens the palette, and a colour picked in it sets the seed`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.editing(teal))

        click(described("Accent"))
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.OpenPicker(ColourTarget.Seed(StyleSeed.ACCENT))), actions)

        actions.clear()
        show(StyleEditorState.editing(teal).withPicker(ColourTarget.Seed(StyleSeed.ACCENT)))

        assertTrue("Hex" in texts(), "${texts()}")

        val pick = PALETTE[4][9]
        click(described(StyleColorHex.format(pick)))
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.SetSeed(StyleSeed.ACCENT, pick)), actions)
    }

    @Test
    fun `each background swatch opens its own side's palette`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.editing(teal))

        click(described("Background · Dark"))
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.OpenPicker(ColourTarget.Seed(StyleSeed.BACKGROUND, isLight = false))), actions)
    }

    @Test
    fun `the editor explains nothing, with no notes on what Leaf moved, no warnings and no contrast in the palette`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // A copy of Solarized, where Leaf moves colours on both sides and fills buttons darker than the accent, with
            // pale text set by hand, which reads on nothing, and its palette open
            show(
                StyleEditorState.startingFrom(BuiltInStyles.SOLARIZED, "", "Solarized copy", "Solarized")
                    .withSeed(StyleSeed.ACCENT, solarizedBlue)
                    .withAdvancedOpen(true)
                    .withColourSetByHand(StyleSlot.TEXT, isLight = true, 0xFFCCCCCC.toInt())
                    .withPicker(ColourTarget.Slot(StyleSlot.TEXT, isLight = true))
            )

            assertTrue("Use the worked-out colour" in texts(), "${texts()}")
            assertTrue(texts().none { ":1" in it }, "${texts().filter { ":1" in it }}")
            assertTrue(texts().none { it.startsWith("Light:") || it.startsWith("Dark:") }, "${texts()}")
        }

    @Test
    fun `Advanced opens the table of every colour, with a dot where one is set by hand`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.editing(teal))
        assertFalse("Hunk headers and boxes" in texts())

        clickText("Advanced")
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.SetAdvancedOpen(true)), actions)

        val byHand = StyleEditorState.editing(teal)
            .withAdvancedOpen(true)
            .withColourSetByHand(StyleSlot.TEXT, isLight = true, 0xFFCCCCCC.toInt())
        show(byHand)

        for (text in listOf("SURFACES", "TEXT", "FILES", "DIFF", "GRAPH LANES", "BUTTONS AND NOTICES", "Hunk headers and boxes", "Lane 6", "Warning strip's button")) {
            assertTrue(text in texts(), "$text in ${texts()}")
        }
        assertEquals(1, nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Set by hand") == true })

        // A row near the top of the table, which the column shows without scrolling
        actions.clear()
        click(described("Panels Dark"))
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.OpenPicker(ColourTarget.Slot(StyleSlot.PANELS, isLight = false))), actions)
    }

    @Test
    fun `a colour set by hand can be worked out again from its palette`(): Unit = runBlocking(Dispatchers.Swing) {
        show(
            StyleEditorState.editing(teal)
                .withAdvancedOpen(true)
                .withColourSetByHand(StyleSlot.TEXT, isLight = true, 0xFFCCCCCC.toInt())
                .withPicker(ColourTarget.Slot(StyleSlot.TEXT, isLight = true))
        )

        clickText("Use the worked-out colour")
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.UseWorkedOutColour(StyleSlot.TEXT, isLight = true)), actions)
    }

    @Test
    fun `Save is offered once something changed and the style has a name, and Revert once it differs`(): Unit =
        runBlocking(Dispatchers.Swing) {
            show(StyleEditorState.editing(teal))
            clickText("Save")
            clickText("Revert changes")
            assertEquals(emptyList<StyleEditorAction>(), actions, "Nothing to save or revert")

            show(StyleEditorState.editing(teal).renamed("Deep teal"))
            clickText("Revert changes")
            clickText("Save")
            assertEquals(listOf(StyleEditorAction.Revert, StyleEditorAction.Save), actions)

            actions.clear()
            show(StyleEditorState.editing(teal).renamed("  "))
            clickText("Save")
            assertEquals(emptyList<StyleEditorAction>(), actions, "No name")

            // A new style can be saved as it is
            show(StyleEditorState.startingFrom(teal, "", "Teal copy", "Teal"))
            clickText("Save")
            assertEquals(listOf<StyleEditorAction>(StyleEditorAction.Save), actions)
        }

    @Test
    fun `Cancel closes at once without changes, and asks first with them`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.editing(teal))
        clickText("Cancel")
        assertEquals(1, closed)

        show(StyleEditorState.editing(teal).withSeed(StyleSeed.WARNING, 0xFFE0A03A.toInt()))
        clickText("Cancel")
        assertEquals(1, closed)
        assertEquals("Teal", discardAsked)
    }

    @Test
    fun `the menu duplicates the style as the editor shows it, exports it, and deletes it once confirmed`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val changed = StyleEditorState.editing(teal).withSeed(StyleSeed.ACCENT, solarizedBlue)
            show(changed)

            click(described("Style actions"))
            clickText("Duplicate")
            assertEquals(listOf<StyleEditorStart>(StyleEditorStart.From(changed.draft, "Teal copy", "Teal")), replaced)

            click(described("Style actions"))
            clickText("Export…")
            assertEquals(listOf<StyleEditorAction>(StyleEditorAction.Export("/tmp/Teal.json")), actions)

            actions.clear()
            click(described("Style actions"))
            clickText("Delete style…")
            assertEquals("Teal", deleteAsked?.first)
            assertEquals(emptyList<StyleEditorAction>(), actions)
            deleteAsked!!.second()
            assertEquals(listOf<StyleEditorAction>(StyleEditorAction.Delete), actions)
        }

    @Test
    fun `a new style's menu has no Delete`(): Unit = runBlocking(Dispatchers.Swing) {
        show(StyleEditorState.startingFrom(teal, "", "Teal copy", "Teal"))

        click(described("Style actions"))
        assertTrue("Duplicate" in texts())
        assertFalse("Delete style…" in texts())
    }

    @Test
    fun `the previews are drawn in the draft's light and dark colours`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = StyleEditorState.editing(teal)
            .withSeed(StyleSeed.BACKGROUND, 0xFFFDF6E3.toInt(), isLight = true)
            .withSeed(StyleSeed.BACKGROUND, 0xFF002B36.toInt(), isLight = false)
        show(state)
        val bitmap = Bitmap.makeFromImage(scene!!.render())
        val resolved = ResolvedDraft(state.draft)

        assertTrue(described("Light").boundsInRoot.top < described("Dark").boundsInRoot.top, "Light above dark")

        for ((side, isLight) in listOf("Light" to true, "Dark" to false)) {
            val preview = described(side).boundsInRoot
            // Right of the lanes, under the restored strip: the background
            val color = bitmap.getColor((preview.right - 10).toInt(), (preview.bottom - 6).toInt())
            assertEquals(resolved.side(isLight).palette[StyleSlot.BACKGROUND].toArgb(), color, side)
        }
    }

    @Test
    fun `an export's outcome stays in the footer until dismissed`(): Unit = runBlocking(Dispatchers.Swing) {
        val home = System.getProperty("user.home")
        show(StyleEditorState.editing(teal), StyleEditorMessage.Exported("$home/Teal.json"))

        assertTrue("Exported Teal to ~/Teal.json" in texts(), "${texts()}")
        clickText("Dismiss")
        assertEquals(listOf<StyleEditorAction>(StyleEditorAction.DismissMessage), actions)
    }

    // --- Helpers

    private fun selectedLook(): String? = mergedNodes()
        .firstOrNull { it.config.getOrNull(SemanticsProperties.Selected) == true && it.texts().any { t -> t in setOf("Classic", "Tonal", "Quiet") } }
        ?.texts()?.single()

    private fun described(description: String): SemanticsNode =
        nodes().last { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

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

    private fun mergedNodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun editableTexts(): List<String> =
        nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
