// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.FontSettings
import dev.app.leaf.domain.models.ui.InterfaceFont
import dev.app.leaf.domain.models.ui.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FontMgr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Which fonts count as code fonts, and what AppTheme provides for each setting (fork-only). */
@OptIn(ExperimentalTextApi::class)
class AppFontsTest {
    private fun bundledTypeface(file: String) =
        FontMgr.default.makeFromData(Data.makeFromBytes(File("src/main/composeResources/font/$file").readBytes()))!!

    @Test
    fun `the bundled code font is monospaced, Inter isn't`() {
        assertTrue(isMonospaced(bundledTypeface("NotoSansMono-Regular.ttf")))
        assertFalse(isMonospaced(bundledTypeface("Inter_18pt-Regular.ttf")))
    }

    @Test
    fun `the installed fonts leave out the system's hidden ones, and the monospaced ones are among them`() {
        val families = SystemFonts.families()
        val monospaced = SystemFonts.monospaced()

        assertTrue(families.none { it.startsWith(".") }, families.toString())
        assertTrue(families.containsAll(monospaced))
        monospaced.forEach { assertTrue(SystemFonts.isInstalled(it.uppercase()), it) }
        assertFalse(SystemFonts.isInstalled("No Such Font 4f1c"))
    }

    @Test
    fun `a code font that isn't installed draws with the bundled font, as the bundled choice does`() {
        assertNull(codeFontFamily(null))
        assertNull(codeFontFamily("No Such Font 4f1c"))
    }

    @Test
    fun `an installed code font is loaded by its name, in each weight and style`() {
        val name = SystemFonts.monospaced().firstOrNull()
        assumeTrue(name != null, "no monospaced font is installed")

        val fonts = (codeFontFamily(name) as FontListFontFamily).fonts

        assertEquals(8, fonts.size)
        assertTrue(fonts.all { it is SystemFont && it.identity == name })
    }

    @Test
    fun `Inter is no family of its own, and the system font is the system's when there is one`() {
        assertNull(interfaceFontFamily(InterfaceFont.INTER))

        val system = SystemFonts.interfaceFont
        val family = interfaceFontFamily(InterfaceFont.SYSTEM)

        if (system == null) {
            assertNull(family)
        } else {
            assertTrue((family as FontListFontFamily).fonts.all { it is SystemFont && it.identity == system.family })
        }
    }

    @Test
    fun `AppTheme gives code its features, none with ligatures and ligatures off without them`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (ligatures in listOf(false, true)) {
                var code: CodeFont? = null
                ImageComposeScene(width = 100, height = 40, density = Density(1f)) {
                    AppTheme(selectedTheme = Theme.Light, fonts = FontSettings(codeLigatures = ligatures)) {
                        code = codeFont()
                        AppTheme(selectedTheme = Theme.Dark) {
                            // An AppTheme inside another follows the outer one's fonts
                            assertEquals(code?.featureSettings, codeFont().featureSettings)
                        }
                    }
                }.let { scene ->
                    try {
                        scene.render()
                    } finally {
                        scene.close()
                    }
                }

                assertEquals(if (ligatures) null else NO_LIGATURES, code!!.featureSettings)
                assertTrue((code!!.family as FontListFontFamily).fonts.none { it is SystemFont }, "the bundled font")
            }
        }

    @Test
    fun `with a font that has ligatures, the switch changes how != and the arrow are drawn, and not with the bundled font`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val font = listOf("JetBrains Mono", "Fira Code", "Cascadia Code").firstOrNull(SystemFonts::isInstalled)
            assumeTrue(font != null, "no font with ligatures is installed")

            fun draw(codeFont: String?, ligatures: Boolean) = render(FontSettings(codeFont = codeFont, codeLigatures = ligatures)) {
                Text("a != b -> c >= d", style = MaterialTheme.typography.body2.inCodeFont())
            }

            assertNotEquals(draw(font, ligatures = false), draw(font, ligatures = true))
            assertEquals(draw(null, ligatures = false), draw(null, ligatures = true))
            assertNotEquals(draw(null, ligatures = false), draw(font, ligatures = false), "the chosen font is drawn")
        }

    @Test
    fun `the system interface font draws other letters than Inter`(): Unit = runBlocking(Dispatchers.Swing) {
        assumeTrue(SystemFonts.interfaceFont != null, "no system interface font here")

        fun draw(choice: InterfaceFont) = render(FontSettings(interfaceFont = choice)) {
            Text("Settings: Interface font", style = MaterialTheme.typography.body1)
        }

        assertNotEquals(draw(InterfaceFont.INTER), draw(InterfaceFont.SYSTEM))
    }

    private fun render(fonts: FontSettings, content: @androidx.compose.runtime.Composable () -> Unit): List<Byte> =
        ImageComposeScene(width = 300, height = 40, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light, fonts = fonts) {
                Box(Modifier.background(Color.White).padding(4.dp)) { content() }
            }
        }.let { scene ->
            try {
                scene.render()
                scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes.toList()
            } finally {
                scene.close()
            }
        }
}
