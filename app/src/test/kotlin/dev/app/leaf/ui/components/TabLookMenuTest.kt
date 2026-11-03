// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.tabs.TabLook
import dev.app.leaf.domain.tabs.tabNameOf
import dev.app.leaf.domain.tabs.titleWith
import dev.app.leaf.domain.worktrees.TabNames
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.graphLanes
import dev.app.leaf.ui.context_menu.ContextMenuElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ELSEWHERE = "elsewhere"

/**
 * A tab's name and colour (fork-only, upstream issue 242), rendered offscreen in the real tab bar: its right-click menu,
 * the colours in it, renaming in place with a double-click, and the stripe of a tab with a colour.
 */
class TabLookMenuTest {
    private var host: Host? = null

    @AfterEach
    fun close() {
        host?.close()
    }

    @Test
    fun `a renamed tab has Rename, the colours, Reset name and Close`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(FakeTab(folder = "app", look = TabLook(name = "Acme app", color = 4)))

        host.rightClick("Acme app")

        assertEquals(listOf("Rename tab…", "Double-click", "Reset name", "Close tab"), host.menuTexts())
        assertEquals(listOf("No colour") + (1..6).map { "Colour $it" }, host.colours())
        assertEquals(listOf("Colour 4"), host.colours(selectedOnly = true))
    }

    @Test
    fun `a tab named after its folder has no Reset name`(): Unit = runBlocking(Dispatchers.Swing) {
        val host = Host(FakeTab(folder = "app", look = TabLook()))

        host.rightClick("app")

        assertEquals(listOf("Rename tab…", "Double-click", "Close tab"), host.menuTexts())
        assertEquals(listOf("No colour"), host.colours(selectedOnly = true))
    }

    @Test
    fun `a Welcome page's tab can only be closed, and isn't renamed`(): Unit = runBlocking(Dispatchers.Swing) {
        val welcome = FakeTab(folder = null, look = null)
        val host = Host(welcome)

        host.rightClick("New tab")

        assertEquals(listOf("Close tab"), host.menuTexts())
        assertTrue(host.colours().isEmpty())

        host.escape()
        host.doubleClick("New tab")

        assertFalse(host.isRenaming())
    }

    @Test
    fun `picking a colour gives the tab that colour, and closes the menu`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val tab = FakeTab(folder = "app", look = TabLook(color = 1))
            val host = Host(tab)

            host.rightClick("app")
            host.click("Colour 3")

            assertEquals(listOf<Int?>(3), tab.colours)
            assertFalse("Close tab" in host.menuTexts(), host.menuTexts().toString())

            host.rightClick("app")
            host.click("No colour")

            assertEquals(listOf(3, null), tab.colours)
        }

    @Test
    fun `Reset name and Close tab`(): Unit = runBlocking(Dispatchers.Swing) {
        val tab = FakeTab(folder = "app", look = TabLook(name = "Acme app"))
        val host = Host(tab)

        host.rightClick("Acme app")
        host.click("Reset name")

        assertEquals(1, tab.resets)

        host.rightClick("app")
        host.click("Close tab")

        assertEquals(listOf(tab), host.closed)
    }

    @Test
    fun `a double-click renames in place, and Enter keeps what was typed`(): Unit = runBlocking(Dispatchers.Swing) {
        val tab = FakeTab(folder = "app", look = TabLook(name = "Acme app"))
        val host = Host(tab)

        host.doubleClick("Acme app")

        assertEquals("Acme app", host.fieldText())

        host.type("Globex app")
        host.press(Key.Enter)

        assertEquals(listOf("Globex app"), tab.renames)
        assertFalse(host.isRenaming())
        assertEquals("Globex app", host.tabTitles().single())
        assertEquals(1, host.focusRequests, "The tab's content takes the focus back")
    }

    @Test
    fun `Esc leaves the name as it was`(): Unit = runBlocking(Dispatchers.Swing) {
        val tab = FakeTab(folder = "app", look = TabLook(name = "Acme app"))
        val host = Host(tab)

        host.doubleClick("Acme app")
        host.type("Globex app")
        host.escape()

        assertTrue(tab.renames.isEmpty())
        assertFalse(host.isRenaming())
        assertEquals("Acme app", host.tabTitles().single())
        assertEquals(1, host.focusRequests)
    }

    @Test
    fun `Rename tab from the menu opens the field, and a click elsewhere keeps what was typed`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val tab = FakeTab(folder = "app", look = TabLook())
            val host = Host(tab)

            host.rightClick("app")
            host.click("Rename tab…")

            assertTrue(host.isRenaming())
            assertEquals("app", host.fieldText())

            host.type("Acme app")
            host.focusElsewhere()

            assertEquals(listOf("Acme app"), tab.renames)
            assertFalse(host.isRenaming())
            assertEquals(0, host.focusRequests, "What was clicked keeps the focus")
        }

    @Test
    fun `Tab and Ctrl+Tab leave the rename open, with what was typed`(): Unit = runBlocking(Dispatchers.Swing) {
        val tab = FakeTab(folder = "app", look = TabLook())
        val host = Host(tab)

        host.doubleClick("app")
        host.type("Acme app")
        host.press(Key.Tab, ctrl = true)
        host.press(Key.Tab, ctrl = true, shift = true)
        host.press(Key.Tab)

        assertTrue(host.isRenaming())
        assertEquals("Acme app", host.fieldText())
        assertTrue(tab.renames.isEmpty())

        host.press(Key.Enter)

        assertEquals(listOf("Acme app"), tab.renames)
    }

    @Test
    fun `a renamed tab shows its name alone, and Reset name brings its worktree back`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val tab = FakeTab(folder = "app", look = TabLook(), worktree = "fix-login")
            val host = Host(tab)

            assertEquals(listOf("app · fix-login"), host.tabTitles())

            host.doubleClick("app")

            // The field holds the whole title, as the renamed tab will show
            assertEquals("app", host.fieldText())
            assertFalse(host.texts().any { "fix-login" in it }, host.texts().toString())

            host.type("review")
            host.press(Key.Enter)

            assertEquals(listOf("review"), host.tabTitles())

            host.rightClick("review")
            host.click("Reset name")

            assertEquals(listOf("app · fix-login"), host.tabTitles())
        }

    @Test
    fun `a tab with a colour has a stripe along its top in that lane's colour, in every style`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED)) {
                for (theme in listOf(Theme.Light, Theme.Dark)) {
                    var lanes = emptyList<Color>()
                    var background = Color.Unspecified
                    val scene = ImageComposeScene(width = 220, height = 36, density = Density(1f)) {
                        AppTheme(theme, style = style) {
                            lanes = MaterialTheme.graphLanes
                            background = MaterialTheme.colors.background
                            Column {
                                Tab(Modifier.height(36.dp), "app", null, false, {}, {}, color = tabColor(3))
                            }
                        }
                    }

                    try {
                        val bitmap = Bitmap.makeFromImage(scene.render())

                        // Classic's grey lane isn't quite opaque
                        val stripe = lanes[2].compositeOver(background)

                        assertSameColour(stripe, bitmap.getColor(100, 1), "${style.name} $theme")
                        assertNotEquals(stripe.toArgb(), bitmap.getColor(100, 18), "${style.name} $theme")
                    } finally {
                        scene.close()
                    }
                }
            }
        }

    @Test
    fun `a tab without a colour has no stripe`(): Unit = runBlocking(Dispatchers.Swing) {
        var background = Color.Unspecified
        val scene = ImageComposeScene(width = 220, height = 36, density = Density(1f)) {
            AppTheme(Theme.Light) {
                background = MaterialTheme.colors.background
                Tab(Modifier.height(36.dp), "app", null, false, {}, {})
            }
        }

        try {
            val bitmap = Bitmap.makeFromImage(scene.render())

            assertEquals(background.toArgb(), bitmap.getColor(100, 1))
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the menu's items do what they say`() {
        var renamed = 0
        val items = tabContextMenuItems(
            look = TabLook(name = "Acme app"),
            onRename = { renamed++ },
            onSetColor = {},
            onResetName = {},
            onClose = {},
        )

        assertEquals(5, items.size)
        assertTrue(items[1] is ContextMenuElement.ContextCustomEntry)
        assertTrue(items[3] is ContextMenuElement.ContextSeparator)

        (items[0] as ContextMenuElement.ContextTextEntry).onClick()

        assertEquals(1, renamed)
    }

    private fun assertSameColour(expected: Color, argb: Int, message: String) {
        val actual = Color(argb)
        val channels = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)

        assertTrue(channels.all { (a, b) -> kotlin.math.abs(a - b) <= 2f / 255 }, "$message: $expected, drawn $actual")
    }

    /** A tab whose repository's folder is [folder], or a Welcome page's when null, as the tab view model behaves. */
    private class FakeTab(folder: String?, look: TabLook?, worktree: String? = null) : TabInformationProvider {
        private val folderName = folder
        private val names = folder?.let { TabNames(it, worktree) }
        override val tabLook = MutableStateFlow(look)
        override val tabName = MutableStateFlow(names?.titleWith(look)?.name)
        override val worktreeName = MutableStateFlow(names?.titleWith(look)?.worktree)
        override val extraInfo = MutableStateFlow(folder?.let { "/repos/$it" })

        val renames = mutableListOf<String>()
        val colours = mutableListOf<Int?>()
        var resets = 0

        override fun renameTab(typed: String) {
            renames += typed
            setName(tabNameOf(typed, folderName))
        }

        override fun resetTabName() {
            resets++
            setName(null)
        }

        override fun setTabColor(color: Int?) {
            colours += color
            tabLook.value = tabLook.value?.copy(color = color)
        }

        private fun setName(name: String?) {
            val look = tabLook.value?.copy(name = name)
            tabLook.value = look
            tabName.value = names?.titleWith(look)?.name
            worktreeName.value = names?.titleWith(look)?.worktree
        }

        override fun dispose() {}
    }

    @Test
    fun `renders the tab bar in four styles, with its menu and a rename, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        fun tabs() = arrayOf(
            FakeTab(folder = "leaf", look = TabLook()),
            FakeTab(folder = "app", look = TabLook(name = "review", color = 5), worktree = "fix-login"),
            FakeTab(folder = "app", look = TabLook(), worktree = "fix-login"),
            FakeTab(folder = "app", look = TabLook(name = "Globex app", color = 2)),
            FakeTab(folder = "dotfiles", look = TabLook(color = 1)),
        )

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED, BuiltInStyles.TEAL)) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                val name = "${style.id}-${theme.name.lowercase()}"

                Host(*tabs(), current = 3, style = style, theme = theme, width = 1100, height = 60, density = 1.5f).use {
                    it.settle()
                    it.writePng(File(folder, "tabs-$name.png"))
                }

                Host(*tabs(), current = 3, style = style, theme = theme, width = 1100, height = 300, density = 1.5f).use {
                    it.rightClick("Globex app")
                    it.writePng(File(folder, "tabs-menu-$name.png"))
                }

                Host(*tabs(), current = 3, style = style, theme = theme, width = 1100, height = 60, density = 1.5f).use {
                    it.doubleClick("Globex app")
                    it.writePng(File(folder, "tabs-renaming-$name.png"))
                }
            }
        }
    }

    private inner class Host(
        vararg fakeTabs: FakeTab,
        current: Int = 0,
        style: StyleDefinition = BuiltInStyles.TONAL,
        theme: Theme = Theme.Dark,
        width: Int = 600,
        height: Int = 400,
        density: Float = 1f,
    ) : AutoCloseable {
        private val tabs = fakeTabs.map { TabInformation(it) }
        private var renamingTab by mutableStateOf<TabInformation<FakeTab>?>(null)
        val closed = mutableListOf<FakeTab>()
        var focusRequests = 0

        private val scene = ImageComposeScene(
            width = (width * density).toInt(),
            height = (height * density).toInt(),
            density = Density(density),
            coroutineContext = Dispatchers.Swing,
        ) {
            AppTheme(theme, style = style) {
                Column(Modifier.background(MaterialTheme.colors.background).fillMaxSize()) {
                    TabsRow(
                        tabs = tabs,
                        defaultTabName = "New tab",
                        currentTab = tabs[current],
                        onTabSelected = {},
                        onTabClosed = { closed += it.data },
                        onMoveTab = { _, _ -> },
                        onAddNewTab = {},
                        renamingTab = renamingTab,
                        onRenamingTabChange = { renamingTab = it },
                        onRenamedFromKeyboard = { focusRequests++ },
                    )

                    // Somewhere else to put the focus
                    Box(Modifier.size(8.dp).width(8.dp).focusable().semantics { contentDescription = ELSEWHERE })
                }
            }
        }

        init {
            host = this
            scene.render()
        }

        fun writePng(file: File) {
            file.writeBytes(scene.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }

        fun texts(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

        /** The texts of the open menu's entries: the popups' but the tab's tooltip, its path. */
        fun menuTexts(): List<String> = scene.semanticsOwners.drop(1).flatMap { owner ->
            allNodes(owner.rootSemanticsNode).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        }.filter { !it.startsWith("/repos/") }

        fun colours(selectedOnly: Boolean = false): List<String> = nodes()
            .filter { !selectedOnly || it.config.getOrNull(SemanticsProperties.Selected) == true }
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .filter { it != ELSEWHERE }

        fun tabTitles(): List<String> = scene.semanticsOwners.first().rootSemanticsNode.let(::allNodes)
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

        fun isRenaming(): Boolean = field() != null

        fun fieldText(): String? = field()?.config?.getOrNull(SemanticsProperties.EditableText)?.text

        private fun field(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null }

        suspend fun click(label: String) {
            val node = nodes().first { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    (label in node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } ||
                        label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
            }
            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        suspend fun rightClick(title: String) {
            val at = positionOf(title)

            delay(50L)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(
                PointerEventType.Press,
                at,
                buttons = PointerButtons(isSecondaryPressed = true),
                button = PointerButton.Secondary,
            )
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Secondary)
            settle()
        }

        suspend fun doubleClick(title: String) {
            val at = positionOf(title)

            delay(350L)
            pressPrimary(at)
            delay(60L)
            pressPrimary(at)
            settle()
        }

        private fun pressPrimary(at: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(
                PointerEventType.Press,
                at,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        }

        private fun positionOf(title: String): Offset {
            val node = scene.semanticsOwners.first().rootSemanticsNode.let(::allNodes).first { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith(title) }
            }

            return node.boundsInRoot.center
        }

        suspend fun type(text: String) {
            checkNotNull(field()!!.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }

        suspend fun focusElsewhere() {
            val elsewhere = nodes().first { ELSEWHERE in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            checkNotNull(elsewhere.config[SemanticsActions.RequestFocus].action).invoke()
            settle()
        }

        suspend fun escape() = press(Key.Escape)

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        suspend fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isCtrlPressed = ctrl, isShiftPressed = shift))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isCtrlPressed = ctrl, isShiftPressed = shift))
            settle()
        }

        private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { allNodes(it.rootSemanticsNode) }

        private fun allNodes(root: SemanticsNode): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            visit(root)

            return nodes
        }

        suspend fun settle() {
            repeat(6) {
                scene.render(System.nanoTime())
                delay(30)
            }
        }

        private var isClosed = false

        override fun close() {
            if (!isClosed) {
                isClosed = true
                scene.close()
            }
        }
    }
}
