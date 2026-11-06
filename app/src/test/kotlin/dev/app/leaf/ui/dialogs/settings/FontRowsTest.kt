// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.FontSettings
import dev.app.leaf.domain.models.ui.InterfaceFont
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.SystemInterfaceFont
import dev.app.leaf.viewmodels.SettingsAction
import dev.app.leaf.viewmodels.emptySettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Settings → Appearance's font rows (fork-only), rendered offscreen: the order, the labels, the line for a font that
 * isn't installed, the code font list with its filter and keys, and the ligatures switch. Set `LEAF_RENDER_DIR` for
 * PNGs of the rows and the list in both themes.
 */
class FontRowsTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()
    private val picks = mutableListOf<String?>()
    private var closes = 0

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(width: Int = 900, height: Int = 1800, theme: Theme = Theme.Light, content: @Composable () -> Unit) {
        scene?.close()
        scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            AppTheme(selectedTheme = theme) {
                Box(Modifier.background(MaterialTheme.colors.surface)) { content() }
            }
        }.also { it.render() }
    }

    private fun showAppearance(fonts: FontSettings = FontSettings(), theme: Theme = Theme.Light) = show(theme = theme) {
        Box(Modifier.width(860.dp).padding(horizontal = 16.dp)) {
            Column { AppearanceRows(emptySettingsState().copy(fonts = fonts)) { actions += it } }
        }
    }

    private suspend fun showList(current: String?, installed: List<String>, theme: Theme = Theme.Light) {
        show(width = 320, height = 420, theme = theme) {
            Box(Modifier.padding(8.dp)) {
                CodeFontList(current = current, installed = installed, onPick = { picks += it }, onClose = { closes++ })
            }
        }
        settle()
    }

    private suspend fun settle() {
        repeat(3) {
            delay(30)
            scene!!.render()
        }
    }

    @Test
    fun `the font rows come after Text size, the preview under the code font, ligatures last`(): Unit =
        runBlocking(Dispatchers.Swing) {
            showAppearance()

            val order = listOf("Text size", "Interface font", "Code font", "Ligatures in code", "Toolbar labels")
                .map { boundsOf(it).top }
            assertEquals(order.sorted(), order)

            val preview = nodes().first { "Code font preview" in it.descriptions() }.boundsInRoot
            assertTrue(boundsOf("Code font").top < preview.top && preview.bottom <= boundsOf("Ligatures in code").top)
            assertTrue("The font of everything but code" in texts())
            assertTrue("Diffs, blame, the conflict resolver, commit hashes and git commands" in texts())
            assertTrue(texts().any { it.contains("!= c.matches") } || texts().any { it.contains("c -> c.message") }, "the preview's code")
        }

    @Test
    fun `the interface font says which system font it would be`(): Unit = runBlocking(Dispatchers.Swing) {
        val sfPro = SystemInterfaceFont(".AppleSystemUIFont", "SF Pro")

        for ((choice, system, label) in listOf(
            Triple(InterfaceFont.INTER, sfPro, "Inter (default)"),
            Triple(InterfaceFont.SYSTEM, sfPro, "System font (SF Pro)"),
            Triple(InterfaceFont.SYSTEM, null, "System font"),
        )) {
            show { InterfaceFontRow(choice = choice, systemFont = system, onPick = {}) }

            assertTrue(label in texts(), "$label in ${texts()}")
        }
    }

    @Test
    fun `the code font row names the font, and says when it isn't installed`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((font, installed) in listOf(null to true, "Menlo" to true, "JetBrains Mono" to false)) {
            show { CodeFontRow(codeFont = font, isInstalled = { installed }, loadInstalled = { emptyList() }, onPick = {}) }

            assertTrue((font ?: "Noto Sans Mono (default)") in texts(), texts().toString())
            assertEquals(
                !installed,
                "$font isn't installed, so Leaf uses Noto Sans Mono." in texts(),
                texts().toString(),
            )
        }
    }

    @Test
    fun `clicking the code font opens the list, whose pick sets the font`(): Unit = runBlocking(Dispatchers.Swing) {
        var picked: String? = "unset"
        show {
            CodeFontRow(
                codeFont = null,
                isInstalled = { true },
                loadInstalled = { listOf("Menlo", "Monaco") },
                onPick = { picked = it },
            )
        }
        settle()

        assertFalse("Monaco" in texts(), "the list is closed")
        val title = boundsOf("Code font")
        click(
            nodes()
                .filter { SemanticsActions.OnClick in it.config && it.boundsInRoot.left > title.right }
                .minBy { kotlin.math.abs(it.boundsInRoot.center.y - title.bottom) }
        )
        settle()

        click(mergedNodes().first { "Monaco" in it.texts() && SemanticsActions.OnClick in it.config })
        settle()

        assertEquals("Monaco", picked)
        assertFalse("Monaco" in texts(), "a pick closes the list")
    }

    @Test
    fun `the list has the bundled font, then the installed ones, with a check on the chosen one`(): Unit =
        runBlocking(Dispatchers.Swing) {
            showList(current = "Menlo", installed = listOf("Monaco", "Menlo", "Andale Mono"))

            val names = listOf("BUNDLED WITH LEAF", "Noto Sans Mono", "INSTALLED", "Andale Mono", "Menlo", "Monaco")
            val tops = names.map { boundsOf(it).top }
            assertEquals(tops.sorted(), tops, "in this order: $names")
            assertTrue("default" in texts())
            assertEquals(listOf("Menlo"), chosenRows())

            showList(current = null, installed = listOf("Monaco", "Menlo"))
            assertEquals(listOf("Noto Sans Mono"), chosenRows())
        }

    /** The names of the rows that have the check: one row's merged node holds its name and the check's description. */
    private fun chosenRows(): List<String> = mergedNodes()
        .filter { "Chosen" in it.descriptions() }
        .map { it.texts().first() }

    @Test
    fun `typing filters the list, Enter picks the first match, and the bundled font is no name`(): Unit =
        runBlocking(Dispatchers.Swing) {
            showList(current = null, installed = listOf("Andale Mono", "Menlo", "Monaco"))

            type("men")
            settle()
            assertFalse("Monaco" in texts())
            assertFalse("Noto Sans Mono" in texts())
            press(Key.Enter)
            assertEquals(listOf<String?>("Menlo"), picks)

            type("noto")
            settle()
            press(Key.Enter)
            assertEquals(listOf("Menlo", null), picks)

            type("courier")
            settle()
            assertTrue("No font matches" in texts())
            press(Key.Enter)
            assertEquals(2, picks.size, "Enter picks nothing")
        }

    @Test
    fun `the arrows move through both groups, and Esc closes the list`(): Unit = runBlocking(Dispatchers.Swing) {
        showList(current = null, installed = listOf("Andale Mono", "Menlo"))

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(listOf<String?>("Menlo"), picks)

        press(Key.Escape)
        assertEquals(1, closes)
    }

    @Test
    fun `the ligatures switch sets the other value`(): Unit = runBlocking(Dispatchers.Swing) {
        for (isOn in listOf(false, true)) {
            showAppearance(FontSettings(codeLigatures = isOn))

            actions.clear()
            val title = boundsOf("Ligatures in code")
            val switch = nodes()
                .filter { SemanticsActions.OnClick in it.config && it.boundsInRoot.left > title.right }
                .minBy { kotlin.math.abs(it.boundsInRoot.center.y - title.bottom) }
            click(switch)

            assertEquals(listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.CodeLigatures(!isOn))), actions)
        }
    }

    @Test
    fun `a row's place in the list counts the headers`() {
        val both = codeFontRows(listOf("Menlo", "Monaco"), "")
        assertEquals(listOf(1, 3, 4), (0..2).map(both::itemIndexOf))

        val withoutBundled = codeFontRows(listOf("Menlo", "Monaco"), "men")
        assertEquals(null, withoutBundled.bundled)
        assertEquals(listOf(1), (0..0).map(withoutBundled::itemIndexOf))
        assertEquals(-1, both.itemIndexOf(-1))
    }

    @Test
    fun `renders the rows and the list in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        val dir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        dir.mkdirs()

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            val name = theme.name.lowercase()

            showAppearance(FontSettings(codeFont = "JetBrains Mono"), theme)
            settle()
            save(File(dir, "font-rows-$name.png"))

            showList(current = null, installed = dev.app.leaf.theme.SystemFonts.monospaced(), theme = theme)
            save(File(dir, "font-list-$name.png"))
        }
    }

    private fun save(file: File) {
        file.writeBytes(scene!!.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun click(node: SemanticsNode) {
        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
        scene!!.render()
    }

    private suspend fun type(text: String) {
        val field = nodes().first { SemanticsActions.SetText in it.config }
        checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
        settle()
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        scene!!.render()
    }

    private fun boundsOf(text: String) = nodes().first { text in it.texts() }.boundsInRoot

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun mergedNodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.descriptions() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
}
