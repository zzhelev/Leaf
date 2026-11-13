// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.discards.DiscardNotice
import dev.app.leaf.domain.discards.DiscardRecord
import dev.app.leaf.domain.discards.DiscardRestore
import dev.app.leaf.domain.discards.DiscardRestorePlan
import dev.app.leaf.domain.discards.RestoreWay
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dialogs.ConfirmableAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val PARSER = "app/src/main/kotlin/dev/app/leaf/parse/Parser.kt"

private fun record(kind: DiscardKind, paths: List<String>, deleted: List<String> = emptyList()) =
    DiscardRecord("1762250000000-0a1b", "/repo/.git", 1_762_250_000_000, kind, paths, deleted)

/**
 * The strip after a discard (fork-only, upstream issue 39): what it says for each kind of discard and after Undo, its
 * Undo and ×, and when Undo asks first. Set `LEAF_RENDER_DIR` for PNGs in three styles, light and dark.
 */
class DiscardStripTest {
    @Test
    fun `says what each kind of discard threw away, with the file's name when there is one`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val cases = listOf(
                record(DiscardKind.CHANGES, listOf(PARSER)) to "Discarded the changes to Parser.kt.",
                record(DiscardKind.STAGED_CHANGES, listOf(PARSER, "README.md")) to "Discarded the changes to 2 files.",
                record(DiscardKind.HUNK, listOf(PARSER)) to "Discarded a hunk of Parser.kt.",
                record(DiscardKind.LINE, listOf(PARSER)) to "Discarded a line of Parser.kt.",
                record(DiscardKind.DELETE, listOf("build/out.log"), listOf("build/out.log")) to "Deleted out.log.",
                record(DiscardKind.ALL, listOf("a.kt", "b.kt")) to "Discarded the changes to 2 files.",
                record(DiscardKind.ALL, listOf("a.kt", "n.txt"), listOf("n.txt")) to
                    "Discarded the changes to 1 file and deleted 1 new file.",
                record(DiscardKind.ALL, listOf("a.kt", "b.kt", "n.txt", "m.txt", "o.txt"), listOf("n.txt", "m.txt", "o.txt")) to
                    "Discarded the changes to 2 files and deleted 3 new files.",
                record(DiscardKind.ALL, listOf("n.txt"), listOf("n.txt")) to "Deleted n.txt.",
                record(DiscardKind.ALL, listOf("n.txt", "m.txt"), listOf("n.txt", "m.txt")) to "Deleted 2 new files.",
            )

            for ((record, text) in cases) {
                Strip(DiscardNotice(record)).use { strip ->
                    assertEquals(text, strip.message(), record.toString())
                    assertTrue(strip.hasButton("Undo"))
                }
            }
        }

    @Test
    fun `after Undo, it says what is back, and has no Undo`(): Unit = runBlocking(Dispatchers.Swing) {
        val one = record(DiscardKind.HUNK, listOf(PARSER))
        val three = record(DiscardKind.CHANGES, listOf("a.kt", "b.kt", "c.kt"))
        val cases = listOf(
            DiscardNotice(one, DiscardRestore(one, listOf(PARSER), emptyList())) to "Parser.kt is back.",
            DiscardNotice(three, DiscardRestore(three, three.paths, emptyList())) to "The 3 files are back.",
            DiscardNotice(three, DiscardRestore(three, listOf("a.kt", "c.kt"), listOf("b.kt"))) to
                "2 of the 3 files are back.",
        )

        for ((notice, text) in cases) {
            Strip(notice).use { strip ->
                assertEquals(text, strip.message())
                assertFalse(strip.hasButton("Undo"))
                assertTrue(strip.hasButton("Dismiss"))
            }
        }
    }

    @Test
    fun `Undo and × call back`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(DiscardNotice(record(DiscardKind.CHANGES, listOf(PARSER)))).use { strip ->
            strip.click("Undo")
            strip.click("Dismiss")

            assertEquals(listOf("undo", "dismiss"), strip.calls)
        }
    }

    @Test
    fun `Undo follows the text, and × is at the right edge`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(DiscardNotice(record(DiscardKind.CHANGES, listOf(PARSER))), width = 1600).use { strip ->
            val text = strip.bounds { it.textOf() == strip.message() && !it.isClickable() }
            val undo = strip.bounds { it.isClickable() && it.textOf() == "Undo" }
            val close = strip.bounds { it.isClickable() && it.contentDescription() == "Dismiss" }

            assertTrue(undo.left - text.right < 24f) { "Undo at ${undo.left}, text ends at ${text.right}" }
            assertTrue(close.left - undo.right > 200f)
            assertEquals(1600f - 8f, close.right, 1f)
        }
    }

    @Test
    fun `Undo asks first only when some files changed since and others can come back`(): Unit = runBlocking {
        val record = record(DiscardKind.CHANGES, listOf("a.kt", "b.kt"))

        fun plan(vararg ways: RestoreWay) = DiscardRestorePlan(record, record.paths.zip(ways).toMap())

        for ((plan, asks) in listOf(
            plan(RestoreWay.AS_IT_WAS, RestoreWay.MERGED) to false,
            plan(RestoreWay.AS_IT_WAS, RestoreWay.CHANGED_SINCE) to true,
            plan(RestoreWay.ALREADY_BACK, RestoreWay.CHANGED_SINCE) to true,
            // Nothing can come back: the task's error dialog names the files
            plan(RestoreWay.CHANGED_SINCE, RestoreWay.CHANGED_SINCE) to false,
            // The record is gone: so does the task's
            null to false,
        )) {
            val asked = mutableListOf<ConfirmableAction>()
            val restored = mutableListOf<DiscardRecord>()
            var confirm: (() -> Unit)? = null

            undoDiscard(
                record = record,
                plan = { plan },
                restore = { restored += it },
                onConfirmAction = { action, onConfirm ->
                    asked += action
                    confirm = onConfirm
                },
            )

            if (asks) {
                assertEquals(
                    listOf(ConfirmableAction.RestoreDiscardPartly(plan!!.restorable, plan.changedSince)),
                    asked,
                    plan.toString(),
                )
                assertEquals(emptyList<DiscardRecord>(), restored, "Nothing runs before the dialog confirms")

                confirm!!()
            } else {
                assertEquals(emptyList<ConfirmableAction>(), asked, plan.toString())
            }

            assertEquals(listOf(record), restored, plan.toString())
        }
    }

    @Test
    fun `renders each state in three styles, light and dark, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        val notices = listOf(
            DiscardNotice(record(DiscardKind.CHANGES, listOf(PARSER))),
            DiscardNotice(record(DiscardKind.HUNK, listOf(PARSER))),
            DiscardNotice(record(DiscardKind.ALL, listOf("a.kt", "b.kt", "n.txt"), listOf("n.txt"))),
            DiscardNotice(record(DiscardKind.DELETE, listOf("build.log"), listOf("build.log"))),
            record(DiscardKind.CHANGES, listOf(PARSER)).let { DiscardNotice(it, DiscardRestore(it, it.paths, emptyList())) },
        )

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED)) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                val scene = ImageComposeScene(width = 1100, height = 300, density = Density(1f)) {
                    AppTheme(selectedTheme = theme, style = style) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                            for (notice in notices) {
                                DiscardStrip(notice, onUndo = {}, onDismiss = {})
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }

                File(folder, "discard-strip-${style.id}-${theme.name.lowercase()}.png")
                    .writeBytes(scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }
        }
    }

    private class Strip(
        notice: DiscardNotice,
        width: Int = 1000,
        theme: Theme = Theme.Dark,
        style: StyleDefinition = BuiltInStyles.TONAL,
    ) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = width, height = 52, density = Density(1f)) {
            AppTheme(selectedTheme = theme, style = style) {
                StripContent(notice)
            }
        }

        @Composable
        private fun StripContent(notice: DiscardNotice) {
            DiscardStrip(notice = notice, onUndo = { calls += "undo" }, onDismiss = { calls += "dismiss" })
        }

        init {
            scene.render()
        }

        /** The strip's text, which isn't a button's. */
        fun message(): String = nodes()
            .filter { !it.isClickable() }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .single()

        fun hasButton(text: String): Boolean = clickables(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickables(text).first().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun bounds(predicate: (SemanticsNode) -> Boolean): Rect = nodes().first(predicate).boundsInRoot

        private fun clickables(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.isClickable() && (node.textOf() == text || node.contentDescription() == text)
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

private fun SemanticsNode.isClickable() = config.getOrNull(SemanticsActions.OnClick) != null

private fun SemanticsNode.textOf(): String? = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

private fun SemanticsNode.contentDescription(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")
