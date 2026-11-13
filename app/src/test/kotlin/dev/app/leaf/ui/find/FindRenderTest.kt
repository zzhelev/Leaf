// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package dev.app.leaf.ui.find

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import java.io.File

/** PNGs of the find strip in the diff, to look at: set `LEAF_RENDER_DIR` (fork-only, upstream issues 275 and 312). */
class FindRenderTest {
    @Test
    fun `renders the strip, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC)) for (theme in listOf(Theme.Light, Theme.Dark)) {
            for ((name, diff) in listOf("unified" to hunksDiff, "split" to split(hunksDiff))) {
                for (width in listOf(534, 900)) {
                    DiffFindScene(diff, width = width, height = 360, theme = theme, style = style).use { scene ->
                        scene.settle()
                        scene.root.requestFocus()
                        scene.settle()
                        scene.press(command(Key.F))
                        scene.type("Find", "parser")
                        scene.press(plain(Key.Enter))
                        scene.write(File(folder, "find-$name-${style.id}-${theme.name.lowercase()}-$width.png"))

                        scene.press(goToLineKey)
                        scene.type("Line", "8")
                        scene.press(plain(Key.Enter))
                        scene.write(File(folder, "goto-$name-${style.id}-${theme.name.lowercase()}-$width.png"))
                    }
                }
            }
        }
    }

    private fun FindScene.write(file: File) {
        file.writeBytes(image().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
