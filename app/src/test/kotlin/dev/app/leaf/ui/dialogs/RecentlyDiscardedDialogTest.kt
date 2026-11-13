// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

private const val NOW = 1_762_250_000_000L

private fun record(id: String, minutesAgo: Long, kind: DiscardKind, paths: List<String>, deleted: List<String> = emptyList()) =
    DiscardRecord(id, "/repo/.git", NOW - TimeUnit.MINUTES.toMillis(minutesAgo), kind, paths, deleted)

private val records = listOf(
    record("r1", 2, DiscardKind.CHANGES, listOf("app/src/main/kotlin/dev/app/leaf/parse/Parser.kt")),
    record("r2", 70, DiscardKind.STAGED_CHANGES, listOf("Lexer.kt", "Token.kt", "README.md", "build.gradle.kts")),
    record("r3", 60 * 26, DiscardKind.HUNK, listOf("app/src/main/kotlin/dev/app/leaf/parse/Lexer.kt")),
    record("r4", 60 * 24 * 3, DiscardKind.ALL, listOf("a.kt", "b.kt", "n.txt"), listOf("n.txt")),
    record("r5", 60 * 24 * 6, DiscardKind.DELETE, listOf("build.log"), listOf("build.log")),
)

/** "Recently discarded" (fork-only, upstream issue 39), from its records. Set `LEAF_RENDER_DIR` for PNGs. */
class RecentlyDiscardedDialogTest {
    @Test
    fun `lists each discard with what it was, where, when, and Restore`(): Unit = runBlocking(Dispatchers.Swing) {
        Dialog(records).use { dialog ->
            val texts = dialog.texts()

            assertEquals(
                listOf(
                    "Recently discarded",
                    "Parser.kt", "Changes · app/src/main/kotlin/dev/app/leaf/parse", "2 min ago", "Restore",
                    "4 files", "Staged changes · Lexer.kt, Token.kt, README.md, build.gradle.kts", "1 h ago", "Restore",
                    "Lexer.kt", "A hunk · app/src/main/kotlin/dev/app/leaf/parse", "1d ago", "Restore",
                    "3 files", "Discard all · 1 new file deleted · a.kt, b.kt, n.txt", "3d ago", "Restore",
                    "build.log", "Deleted", "6d ago", "Restore",
                    "Close",
                ),
                texts,
            )
        }
    }

    @Test
    fun `Restore restores its row's discard, and Close closes`(): Unit = runBlocking(Dispatchers.Swing) {
        Dialog(records).use { dialog ->
            dialog.clickNth("Restore", 2)
            dialog.click("Close")

            assertEquals(listOf("restore r3", "close"), dialog.calls)
        }
    }

    @Test
    fun `says when nothing was discarded, and nothing while it reads`(): Unit = runBlocking(Dispatchers.Swing) {
        Dialog(emptyList()).use { dialog ->
            assertEquals(
                listOf("Recently discarded", "Nothing was discarded in the last two weeks.", "Close"),
                dialog.texts(),
            )
        }

        Dialog(null).use { dialog ->
            assertEquals(listOf("Recently discarded", "Close"), dialog.texts())
        }
    }

    @Test
    fun `renders in three styles, light and dark, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED)) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                Dialog(records, theme, style).use { dialog ->
                    File(folder, "recently-discarded-${style.id}-${theme.name.lowercase()}.png")
                        .writeBytes(dialog.scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    private class Dialog(
        records: List<DiscardRecord>?,
        theme: Theme = Theme.Dark,
        style: StyleDefinition = BuiltInStyles.TONAL,
    ) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = 680, height = 460, density = Density(1f)) {
            AppTheme(selectedTheme = theme, style = style) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.surface).padding(24.dp)) {
                    RecentlyDiscardedContent(
                        records = records,
                        now = NOW,
                        onRestore = { calls += "restore ${it.id}" },
                        onClose = { calls += "close" },
                    )
                }
            }
        }

        init {
            scene.render()
        }

        /** The texts in the order they're laid out; a button's is its label. */
        fun texts(): List<String> = nodes().flatMap { it.leafTexts() }

        fun click(text: String) = clickNth(text, 0)

        fun clickNth(text: String, index: Int) {
            val node = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null && text in it.leafTexts() }[index]
            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}

/** The node's own texts, which a clickable node merges from its children. */
private fun SemanticsNode.leafTexts(): List<String> =
    config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
