// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.ColorsScheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styled
import dev.app.leaf.ui.components.FilesChangedHeader
import dev.app.leaf.ui.components.sort.SortMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.pow

private const val HINT = "Write your commit message here"

/**
 * The commit message's field with a hint in place of a label, and its title row (fork-only, upstream issue 344), rendered
 * offscreen: the hint goes when something is typed and comes back, the field and the row look like what is above them,
 * and both follow the theme, a custom one too.
 */
class CommitMessageFieldTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    // --- The hint

    @Test
    fun `an empty field shows the hint and nothing else, and typing hides it`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host()

        assertEquals(listOf(HINT), host.texts(), "No label, and the hint alone")

        host.click(100f, 40f)
        assertTrue(host.isFocused(), "A click puts the focus in the field")
        assertEquals(listOf(HINT), host.texts(), "The hint stays while the field is empty, focused or not")

        host.type("Fix the parser")

        assertEquals("Fix the parser", host.changes.last(), "What was typed reaches setCommitMessage, which saves the draft")
        assertEquals("Fix the parser", host.editableText())
        assertFalse(HINT in host.texts(), "The hint goes once there is a message")
    }

    @Test
    fun `deleting the message brings the hint back`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(message = "Fix the parser")

        assertFalse(HINT in host.texts())

        host.click(100f, 40f)
        host.type("")

        assertEquals("", host.changes.last())
        assertTrue(HINT in host.texts())
    }

    @Test
    fun `a field that can't be edited shows its text, and has no hint`(): Unit = runBlocking(Dispatchers.Swing) {
        Host(message = "Reworded", enabled = false).use { host ->
            assertEquals("Reworded", host.editableText())
            assertFalse(HINT in host.texts())
        }

        Host(message = "", enabled = false).use { host ->
            assertFalse(HINT in host.texts(), host.texts().toString())
        }
    }

    // --- Colours

    @Test
    fun `the hint is dimmer than the message and still readable, in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((theme, scheme) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
            val background = scheme.background.toArgb()

            val hint = Host(theme = theme).use { host -> host.ink(HINT, background) }
            val typed = Host(theme = theme, message = "Fix the parser").use { host -> host.ink("Fix the parser", background) }

            val hintContrast = contrast(hint, background)
            val typedContrast = contrast(typed, background)

            // 60% of the text color: 4.1:1 on white, 7.2:1 on the dark theme's field, by the numbers of the mockup
            assertTrue(hintContrast >= 3.5, "$theme: the hint has contrast $hintContrast")
            assertTrue(hintContrast < typedContrast - 1.0, "$theme: the hint $hintContrast, the message $typedContrast")
            assertTrue(typedContrast >= 7.0, "$theme: the message has contrast $typedContrast")
        }
    }

    @Test
    fun `the field has the lists' background, and no underline until it has the focus`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val cases = AccentStyle.entries.flatMap { style ->
                listOf(Triple(style, Theme.Light, lightTheme), Triple(style, Theme.Dark, darkTheme))
            }

            for ((style, theme, base) in cases) {
                // The accent follows the style: the muted blue in Tonal and Quiet's light theme
                val scheme = base.styled(style)

                Host(theme = theme, style = style).use { host ->
                    val field = host.fieldBounds()
                    val x = (field.left + 200f).toInt()
                    val last = (field.bottom - 1f).toInt()

                    val resting = host.bitmap()

                    assertEquals(scheme.background.toArgb(), resting.getColor(x, field.top.toInt() + 2), "$theme: background")
                    assertEquals(scheme.background.toArgb(), resting.getColor(x, last), "$theme: no underline at rest")
                    assertEquals(scheme.background.toArgb(), resting.getColor(x, last - 1), "$theme: no underline at rest")

                    host.click(100f, 40f)

                    assertEquals(
                        scheme.primaryVariant.toArgb(),
                        host.bitmap().getColor(x, last),
                        "$style $theme: the focused underline is the accent, as before",
                    )
                }
            }
        }

    @Test
    fun `a custom theme's colors reach the message, the hint, a message that can't be edited and the underline`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val green = Color(0xFF00FF00)
            val red = Color(0xFFFF0000)
            val custom: ColorsScheme = darkTheme.copy(
                background = Color.Black,
                onBackground = green,
                onBackgroundSecondary = red,
                primaryVariant = Color(0xFFFF00FF),
            )

            fun Int.isGreenish(min: Int) = (this shr 8 and 0xFF) >= min && (this shr 16 and 0xFF) < 40 && (this and 0xFF) < 40

            Host(theme = Theme.Custom, custom = custom).use { host ->
                val hint = host.ink(HINT, Color.Black.toArgb())

                // Green at 60%: the green channel is high but not full, red and blue stay out
                assertTrue(hint.isGreenish(120) && (hint shr 8 and 0xFF) < 230, "The hint's brightest pixel is ${hint.toString(16)}")

                host.click(100f, 40f)
                assertEquals(Color(0xFFFF00FF).toArgb(), host.bitmap().getColor((host.fieldBounds().left + 200f).toInt(), (host.fieldBounds().bottom - 1f).toInt()))
            }

            Host(theme = Theme.Custom, custom = custom, message = "Fix the parser").use { host ->
                val typed = host.ink("Fix the parser", Color.Black.toArgb())

                assertTrue(typed.isGreenish(230), "The message's brightest pixel is ${typed.toString(16)}")
            }

            Host(theme = Theme.Custom, custom = custom, message = "Reworded", enabled = false).use { host ->
                val shown = host.ink("Reworded", Color.Black.toArgb())

                // As before: the typography's own color is the text's, so the disabled color of the scheme never applied
                assertTrue(shown.isGreenish(230), "A message that can't be edited is drawn in the text color: ${shown.toString(16)}")
            }
        }

    // --- The title row

    @Test
    fun `the title row has the headers' height, surface and text position`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((theme, scheme) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
            val image = ImageComposeScene(width = 400, height = 80, density = Density(1f)) {
                AppTheme(selectedTheme = theme) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colors.surface)) {
                        FilesChangedHeader(
                            title = "Staged",
                            showSearch = false,
                            showActionForSelected = false,
                            onSearchFilterToggled = {},
                            onSearchFocused = {},
                            searchFilter = TextFieldValue(""),
                            onSearchFilterChanged = {},
                        )
                        CommitMessageTitle(
                            header = commitMessageHeader(RepositoryState.SAFE, false, isAmend = false, isAmendRebaseInteractive = false),
                            onToggleAmend = {},
                        )
                    }
                }
            }.also { scene = it }

            val bitmap = Bitmap.makeFromImage(scene!!.render())
            val staged = textNode("Staged").boundsInRoot
            val commit = textNode("Commit message").boundsInRoot

            // A row 34 dp tall centers its text 17 dp from its top: the second row starts at 34
            assertEquals(staged.top, commit.top - 34f, 0.5f, "$theme: the text's place in its row")
            assertEquals(staged.left, commit.left, 0.5f, "$theme: the text's left")
            assertEquals(staged.height, commit.height, 0.5f, "$theme: the text's size")

            for (y in listOf(1, 30, 36, 64)) {
                assertEquals(scheme.tertiarySurface.toArgb(), bitmap.getColor(300, y), "$theme: the surface at $y")
            }
            // Under both rows, the pane's own surface: the title row is no taller than the header
            assertEquals(scheme.surface.toArgb(), bitmap.getColor(300, 70), "$theme: below the rows")

            scene!!.close()
            scene = null
        }
    }

    @Test
    fun `the amending tag has the accent that the checkbox had, and read-only the muted color`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val custom = darkTheme.copy(
                tertiarySurface = Color.Black,
                secondary = Color(0xFFFFFF00),
                onBackgroundSecondary = Color(0xFFFF0000),
            )

            fun inkOfTag(header: CommitMessageHeader): Int {
                scene?.close()
                scene = ImageComposeScene(width = 400, height = 40, density = Density(1f)) {
                    AppTheme(selectedTheme = Theme.Custom, customTheme = custom) {
                        CommitMessageTitle(header = header, onToggleAmend = {})
                    }
                }.also { it.render() }

                val bounds = nodes().first { node -> node.texts().any { it.startsWith("·") } }.boundsInRoot
                val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
                var best = Color.Black.toArgb()
                var bestContrast = 0.0

                for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                    for (x in bounds.left.toInt() until bounds.right.toInt()) {
                        val c = contrast(bitmap.getColor(x, y), Color.Black.toArgb())

                        if (c > bestContrast) {
                            bestContrast = c
                            best = bitmap.getColor(x, y)
                        }
                    }
                }

                return best
            }

            val amending = inkOfTag(commitMessageHeader(RepositoryState.SAFE, false, isAmend = true, isAmendRebaseInteractive = false))
            val readOnly = inkOfTag(commitMessageHeader(RepositoryState.REBASING_INTERACTIVE, false, isAmend = false, isAmendRebaseInteractive = false))

            assertTrue((amending shr 16 and 0xFF) >= 230 && (amending shr 8 and 0xFF) >= 230 && (amending and 0xFF) < 60, "amending: ${amending.toString(16)}")
            assertTrue((readOnly shr 16 and 0xFF) >= 230 && (readOnly shr 8 and 0xFF) < 60 && (readOnly and 0xFF) < 60, "read-only: ${readOnly.toString(16)}")
        }

    @Test
    fun `a long tag is cut and the title keeps its room, and the menu button keeps its own`(): Unit =
        runBlocking(Dispatchers.Swing) {
            fun render(width: Int) {
                scene?.close()
                scene = ImageComposeScene(width = width, height = 40, density = Density(1f)) {
                    AppTheme(selectedTheme = Theme.Dark) {
                        Box(Modifier.background(MaterialTheme.colors.surface)) {
                            CommitMessageTitle(
                                header = commitMessageHeader(RepositoryState.SAFE, false, isAmend = true, isAmendRebaseInteractive = false),
                                onToggleAmend = {},
                            )
                        }
                    }
                }.also { it.render() }
            }

            render(400)
            val wide = textNode("Commit message").boundsInRoot
            val wideTag = nodes().first { node -> node.texts().any { it.startsWith("·") } }.boundsInRoot

            render(230)
            val title = textNode("Commit message").boundsInRoot
            val tag = nodes().first { node -> node.texts().any { it.startsWith("·") } }.boundsInRoot
            val menu = nodes().first { "More actions" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInRoot

            assertEquals(wide.width, title.width, 0.5f, "The title keeps its whole width")
            assertTrue(tag.width < wideTag.width - 1f, "The tag is cut: ${tag.width} against ${wideTag.width}")
            assertTrue(title.right <= tag.left + 0.5f, "The tag comes after the title: ${title.right} against ${tag.left}")
            assertTrue(tag.right <= menu.left + 0.5f, "The tag ends before the menu: ${tag.right} against ${menu.left}")
            assertTrue(menu.right <= 230f + 0.5f, "The menu is inside the row")
        }

    // --- The header's rules

    @Test
    fun `the header follows the state of the repository`() {
        // Fork-only: Add co-author and Wrap body wherever the field can be edited (canAddCoAuthor), not at a read-only stop

        // Outside an operation: Amend is the field's own flag
        for (state in listOf(RepositoryState.SAFE, RepositoryState.BISECTING, RepositoryState.APPLY)) {
            val header = commitMessageHeader(state, isAmenableRebaseInteractive = false, isAmend = true, isAmendRebaseInteractive = false)

            assertEquals(CommitMessageHeader(isMergeMessage = false, isReadOnly = false, amend = AmendItem(true), canAddCoAuthor = true, signOff = SignOffItem(false), wrapBody = WrapBodyItem(false)), header, "$state")
            assertEquals(AmendItem(false), commitMessageHeader(state, false, isAmend = false, isAmendRebaseInteractive = true).amend, "$state")
        }

        // Merging: git's message, and nothing to amend
        for (state in listOf(RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED)) {
            assertEquals(
                CommitMessageHeader(isMergeMessage = true, isReadOnly = false, amend = null, canAddCoAuthor = true, signOff = SignOffItem(false), wrapBody = WrapBodyItem(false)),
                commitMessageHeader(state, false, isAmend = true, isAmendRebaseInteractive = true),
                "$state",
            )
        }

        // Cherry-picking and reverting: nothing to amend either
        for (state in listOf(
            RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED,
            RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED,
        )) {
            assertEquals(
                CommitMessageHeader(isMergeMessage = false, isReadOnly = false, amend = null, canAddCoAuthor = true, signOff = SignOffItem(false), wrapBody = WrapBodyItem(false)),
                commitMessageHeader(state, false, isAmend = true, isAmendRebaseInteractive = true),
                "$state",
            )
        }

        // Rebasing: read-only at the stops that can't be amended, and the rebase's own flag at the others
        for (state in listOf(
            RepositoryState.REBASING, RepositoryState.REBASING_REBASING, RepositoryState.REBASING_MERGE,
            RepositoryState.REBASING_INTERACTIVE,
        )) {
            assertEquals(
                CommitMessageHeader(isMergeMessage = false, isReadOnly = true, amend = null),
                commitMessageHeader(state, isAmenableRebaseInteractive = false, isAmend = true, isAmendRebaseInteractive = true),
                "$state",
            )
            assertEquals(
                CommitMessageHeader(isMergeMessage = false, isReadOnly = false, amend = AmendItem(true), canAddCoAuthor = true, signOff = SignOffItem(false), wrapBody = WrapBodyItem(false)),
                commitMessageHeader(state, isAmenableRebaseInteractive = true, isAmend = false, isAmendRebaseInteractive = true),
                "$state",
            )
            assertEquals(
                AmendItem(false),
                commitMessageHeader(state, isAmenableRebaseInteractive = true, isAmend = true, isAmendRebaseInteractive = false).amend,
                "$state",
            )
        }
    }

    @Test
    fun `the menu has one item, checked while Amend is on, which closes the menu and toggles`() {
        var toggles = 0

        for (isOn in listOf(false, true)) {
            val item = amendMenuItems(AmendItem(isOn), "Amend last commit", { toggles++ }).single() as SortMenuItem.Option

            assertEquals("Amend last commit", item.label)
            assertEquals(isOn, item.isChecked, "checked while on")
            assertTrue(item.closesMenu)
            assertFalse(item.isDestructive)

            item.onClick()
        }

        assertEquals(2, toggles)
    }

    // --- What drives them

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

        return nodes
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun textNode(text: String) = nodes().first { text in it.texts() }

    /** The message field alone, 400 by 120 dp, on a surface of its own, with a host that keeps what is typed. */
    private inner class Host(
        message: String = "",
        private val enabled: Boolean = true,
        theme: Theme = Theme.Dark,
        custom: ColorsScheme? = null,
        style: AccentStyle = AccentStyle.DEFAULT,
    ) : AutoCloseable {
        /** What `setCommitMessage` was given, as the draft would be saved. A click that moves the caret gives it too. */
        val changes = mutableListOf<String>()

        private var value by mutableStateOf(TextFieldValue(message))

        init {
            scene = ImageComposeScene(width = 400, height = 120, density = Density(1f)) {
                AppTheme(selectedTheme = theme, customTheme = custom, style = BuiltInStyles.ofLook(style)) {
                    Box(Modifier.background(MaterialTheme.colors.surface)) {
                        Column {
                            CommitMessageTextField(
                                value = value,
                                onValueChange = {
                                    value = it
                                    changes += it.text
                                },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                            )
                        }
                    }
                }
            }.also { it.render() }
        }

        fun texts(): List<String> = nodes().flatMap { it.texts() }

        fun editableText() = nodes().first { it.config.contains(SemanticsProperties.EditableText) }
            .config[SemanticsProperties.EditableText].text

        fun isFocused() = nodes().first { it.config.contains(SemanticsProperties.EditableText) }
            .config.getOrNull(SemanticsProperties.Focused) == true

        fun fieldBounds(): Rect = nodes().first { it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot

        /** Lets animations run: the scene's clock only moves when asked, and the hint fades in. */
        suspend fun settle() {
            repeat(10) {
                scene!!.render(System.nanoTime())
                delay(30)
            }
        }

        suspend fun click(x: Float, y: Float) {
            val position = Offset(x, y)

            scene!!.sendPointerEvent(PointerEventType.Move, position)
            scene!!.sendPointerEvent(
                PointerEventType.Press,
                position,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene!!.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            settle()
        }

        /** Types as the platform's text input does: the field's own text action. */
        suspend fun type(text: String) {
            val field = nodes().first { it.config.contains(SemanticsProperties.EditableText) }

            checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }

        /** The scene's pixels once its animations (the underline's fade in) have run: its clock only moves when asked. */
        suspend fun bitmap(): Bitmap {
            settle()

            return Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
        }

        /**
         * The pixel of the hint or the message that is farthest from [background], which is the color it is drawn in. The
         * hint is a text of its own; the message is the field's, so its place is the field's, bar the underline.
         */
        suspend fun ink(text: String, background: Int): Int {
            val bounds = nodes().firstOrNull { text in it.texts() }?.boundsInRoot
                ?: fieldBounds().let { Rect(it.left, it.top, it.right, it.bottom - 4f) }
            val bitmap = bitmap()
            var best = background
            var bestContrast = 0.0

            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    val pixel = bitmap.getColor(x, y)
                    val c = contrast(pixel, background)

                    if (c > bestContrast) {
                        bestContrast = c
                        best = pixel
                    }
                }
            }

            return best
        }

        override fun close() {
            scene?.close()
            scene = null
        }
    }
}

private fun luminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = (argb shr shift and 0xFF) / 255.0

        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** The WCAG contrast ratio of two colors. */
private fun contrast(a: Int, b: Int): Double {
    val (high, low) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }

    return (high + 0.05) / (low + 0.05)
}
