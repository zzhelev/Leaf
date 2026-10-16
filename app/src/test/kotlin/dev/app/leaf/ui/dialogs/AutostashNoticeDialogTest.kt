// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.AutostashNotice
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the notice says when git couldn't put back the changes that it set aside (fork-only). */
class AutostashNoticeDialogTest {
    @Test
    fun `lists the files where putting the changes back after the commit left conflicts`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val notice = AutostashNotice(
                AutostashOperation.MERGE,
                AutostashPutBackAfter.COMMIT,
                listOf("src/a.txt", "b.txt"),
            )

            NoticeScene(notice).use { scene ->
                assertEquals(
                    "git set your uncommitted changes aside before the merge (merge.autoStash). Putting them back " +
                        "after the commit left conflicts in:\n\nsrc/a.txt\nb.txt\n\nThey're also saved in the stash " +
                        "list as autostash. Drop that stash once the conflicts are resolved.",
                    scene.text.text,
                )
                assertEquals(
                    listOf("merge.autoStash", "src/a.txt", "b.txt", "autostash"),
                    scene.monospaceParts(),
                )
                assertEquals("Your changes are in the stash", scene.texts().first())
            }
        }

    @Test
    fun `says that putting them back was refused, and where they are`(): Unit = runBlocking(Dispatchers.Swing) {
        NoticeScene(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.ABORT, emptyList())).use { scene ->
            assertEquals(
                "git set your uncommitted changes aside before the merge (merge.autoStash). Putting them back after " +
                    "aborting the merge didn't work, as other changes are in the way. They're saved in the stash " +
                    "list as autostash: apply it once those changes are committed or stashed.",
                scene.text.text,
            )
            assertEquals(listOf("merge.autoStash", "autostash"), scene.monospaceParts())
        }
    }

    @Test
    fun `names the rebase and when it was put back`(): Unit = runBlocking(Dispatchers.Swing) {
        val texts = listOf(AutostashPutBackAfter.OPERATION, AutostashPutBackAfter.ABORT).map { after ->
            NoticeScene(AutostashNotice(AutostashOperation.REBASE, after, listOf("a.txt"))).use { it.text.text }
        }

        assertEquals(
            listOf(
                "git set your uncommitted changes aside before the rebase (rebase.autoStash). Putting them back " +
                    "after the rebase left conflicts in:",
                "git set your uncommitted changes aside before the rebase (rebase.autoStash). Putting them back " +
                    "after aborting the rebase left conflicts in:",
            ),
            texts.map { it.substringBefore("\n") },
        )
        assertEquals(
            "git set your uncommitted changes aside before the merge (merge.autoStash). Putting them back after the " +
                "merge left conflicts in:",
            NoticeScene(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.OPERATION, listOf("a.txt")))
                .use { it.text.text.substringBefore("\n") },
        )
    }

    @Test
    fun `lists ten files, and counts the others`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = (1..12).map { "f$it.txt" }

        NoticeScene(AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.COMMIT, files)).use { scene ->
            val lines = scene.text.text.lines()

            assertEquals(files.take(10) + "and 2 more files", lines.subList(2, 13))
        }
    }

    @Test
    fun `OK closes it`(): Unit = runBlocking(Dispatchers.Swing) {
        val notice = AutostashNotice(AutostashOperation.MERGE, AutostashPutBackAfter.COMMIT, emptyList())

        NoticeScene(notice).use { scene ->
            scene.click("OK")

            assertEquals(1, scene.accepted)
        }
    }

    private class NoticeScene(notice: AutostashNotice) : AutoCloseable {
        var accepted = 0
        lateinit var text: AnnotatedString

        private val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            AppTheme {
                text = autostashNoticeText(notice)
                AutostashNoticeContent(notice, onAccept = { accepted++ })
            }
        }

        init {
            scene.render()
        }

        /** The parts of [text] in the monospace font, in order. */
        fun monospaceParts(): List<String> = text.spanStyles
            .filter { it.item.fontFamily != null }
            .sortedBy { it.start }
            .map { text.text.substring(it.start, it.end) }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun click(text: String) {
            val button = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            checkNotNull(button.config[SemanticsActions.OnClick].action).invoke()
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
