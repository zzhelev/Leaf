// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.FrameColors
import dev.app.leaf.theme.frameColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment

/**
 * Where the title bar's content goes between the window's buttons, macOS's at the left and Windows' at the right, and
 * which buttons Windows draws on it (docs/fork/window-chrome.md, step 8).
 */
class TitleBarInsetsTest {
    @Test
    fun `the content starts after macOS's buttons and ends before Windows' and the free strip`() {
        // macOS: its buttons at the left, nothing at the right
        assertPadding(start = 80.dp, end = TITLE_BAR_FREE_STRIP, titleBarPadding(80.dp, 0.dp))
        // Windows: its buttons at the right, and a gap of Leaf's own at the left
        assertPadding(start = TITLE_BAR_EDGE_PADDING, end = 138.dp + TITLE_BAR_FREE_STRIP, titleBarPadding(0.dp, 138.dp))
        // macOS in full screen, which hides its buttons
        assertPadding(start = TITLE_BAR_EDGE_PADDING, end = TITLE_BAR_FREE_STRIP, titleBarPadding(0.dp, 0.dp))
    }

    @Test
    fun `Windows draws its buttons for a dark bar where the frame's text is light`() {
        for (style in BuiltInStyles.ALL) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                val frame = frameOf(style, theme)
                // Leaf's frame is green on both sides, with light text; every other style's frame follows the theme
                val expected = theme == Theme.Dark || style == BuiltInStyles.LEAF

                assertEquals(expected, hasDarkBar(frame), "${style.name}, $theme")
            }
        }
    }

    @Test
    fun `with Windows' insets the content keeps clear of its buttons, and they follow the frame as it changes`(): Unit =
        runBlocking(Dispatchers.Swing) {
            assumeFalse(GraphicsEnvironment.isHeadless(), "A ComposeWindow needs a display")
            val window = ComposeWindow()

            try {
                // A density of the window's scale, as in the window, so that a point is a dp
                val windowScale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                val bar = RecordingTitleBar(leftInset = 0f, rightInset = 138f)
                var theme by mutableStateOf(Theme.Light)
                var content: Rect? = null
                val width = 1000

                ImageComposeScene(
                    width = (width * windowScale).toInt(),
                    height = (60 * windowScale).toInt(),
                    density = Density(windowScale),
                    coroutineContext = Dispatchers.Swing,
                ) {
                    AppTheme(theme) {
                        TitleBar(bar, window, isFullscreen = false) {
                            Box(Modifier.fillMaxWidth().height(10.dp).onGloballyPositioned { content = it.boundsInRoot() })
                        }
                    }
                }.use { scene ->
                    suspend fun settle() = repeat(4) {
                        scene.render(System.nanoTime())
                        delay(30)
                    }

                    settle()
                    val bounds = checkNotNull(content)
                    assertEquals(TITLE_BAR_EDGE_PADDING.value, bounds.left / windowScale, 0.5f)
                    assertEquals(width - 138f - TITLE_BAR_FREE_STRIP.value, bounds.right / windowScale, 0.5f)
                    assertEquals(listOf(false), bar.buttonsDark)

                    theme = Theme.Dark
                    settle()
                    assertEquals(listOf(false, true), bar.buttonsDark)
                }
            } finally {
                window.dispose()
            }
        }

    private fun assertPadding(start: Dp, end: Dp, padding: PaddingValues) {
        assertEquals(start, padding.calculateStartPadding(LayoutDirection.Ltr))
        assertEquals(end, padding.calculateEndPadding(LayoutDirection.Ltr))
    }

    private fun frameOf(style: StyleDefinition, theme: Theme): FrameColors {
        var frame: FrameColors? = null

        ImageComposeScene(10, 10) {
            AppTheme(theme, style = style) { frame = MaterialTheme.frameColors }
        }.use { it.render() }

        return checkNotNull(frame)
    }

    private class RecordingTitleBar(override val leftInset: Float, override val rightInset: Float) : WindowTitleBar {
        val buttonsDark = mutableListOf<Boolean>()

        override fun setHeight(height: Float) {}

        override fun setButtonsDark(dark: Boolean) {
            buttonsDark += dark
        }

        override fun forceHitTest(leafTakesIt: Boolean) {}
    }
}
