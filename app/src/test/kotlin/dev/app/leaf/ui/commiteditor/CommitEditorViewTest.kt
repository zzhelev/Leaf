// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.commiteditor

import androidx.compose.foundation.layout.Row
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.StripColors
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.styleColors
import org.jetbrains.skia.Bitmap
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.rewrite.CheckedGroup
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanPlace
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.repositoryopen.CommitEditorAction
import dev.app.leaf.repositoryopen.CommitEditorState
import dev.app.leaf.repositoryopen.PlanFile
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.RewriteFixtures
import dev.app.leaf.ui.RewriteFixtures.add
import dev.app.leaf.ui.RewriteFixtures.cache
import dev.app.leaf.ui.RewriteFixtures.wip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val WIDTH = 1100
private const val PANEL_WIDTH = 380
private const val HEIGHT = 560

/**
 * The commit editor's centre pane and panel (fork-only, B5 of `docs/fork/commit-editor.md`), rendered from a state: the
 * rows newest first with their tags, the header and Onto, the footer's counts, notes and Start, the keys, and the panel
 * for a group, a reword and a dropped row. Set `LEAF_RENDER_DIR` to a folder to get PNGs in both themes, and pass
 * `--rerun`.
 */
class CommitEditorViewTest {
    private val line = RewriteFixtures.line(pushed = setOf(add.hash))
    private val plan = RebasePlan.of(line, listOf(add, wip, cache))
    private val onto = OntoBaseCheck.Clean(OntoBase(RewriteFixtures.MAIN, "5".repeat(40)), newCommits = 2)
    private val files = mapOf(
        cache.hash to listOf(
            PlanFile("src/parser/Cache.kt", DiffEntry.ChangeType.MODIFY),
            PlanFile("src/parser/CacheTest.kt", DiffEntry.ChangeType.ADD),
        ),
        wip.hash to listOf(PlanFile("NOTES.md", DiffEntry.ChangeType.MODIFY)),
    )

    /** wip squashed into add, then the plan moved onto main, where picking cache conflicts. */
    private val stopping = CommitEditorState(
        plan = plan.withAction(wip.hash, PlanAction.SQUASH).withOnto(onto.onto),
        selected = cache.hash,
        check = PlanCheck(
            listOf(CheckedGroup(add.hash, listOf(add.hash, wip.hash), "t1")),
            PlanStop.Conflict(cache.hash, listOf("src/parser/Cache.kt")),
        ),
        onto = onto,
        place = PlanPlace(worktreeName = "leaf", isTabs = true, uncommitted = 2),
        files = files,
        currentTip = cache.hash,
    )

    @Test
    fun `rows are newest first, with the action, the hash, the subject and their tags`(): Unit = runBlocking(Dispatchers.Swing) {
        EditorScene(stopping).use { editor ->
            val subjects = listOf("Cache the parsed files", "wip", "Add the parser")
            val tops = subjects.map { subject -> editor.node(subject).boundsInWindow.top }

            assertEquals(tops.sorted(), tops)
            assertTrue("+1" in editor.texts(), editor.texts().toString())
            assertTrue("Stops · Cache.kt" in editor.texts(), editor.texts().toString())
            assertTrue(editor.texts().containsAll(listOf("Pick", "Squash")), editor.texts().toString())
            assertTrue("Interactive rebase · claude/parser-cache" in editor.texts())
            assertTrue("3 commits since the branch left main" in editor.texts())
        }
    }

    @Test
    fun `the footer counts the plan, says where it stops and offers the box, and Start waits for it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            EditorScene(stopping).use { editor ->
                val texts = editor.texts()

                assertTrue("3 commits → 2" in texts, texts.toString())
                assertTrue("1 joined" in texts, texts.toString())
                assertTrue("Stops at c0ffee1 “Cache the parsed files”: Cache.kt conflicts." in texts, texts.toString())
                assertTrue(texts.any { it.startsWith("1 commit that this rewrites is on origin/claude/parser-cache") }, texts.toString())
                assertTrue("Set my 2 uncommitted changes aside, and put them back after the rebase" in texts, texts.toString())
                assertFalse(editor.isEnabled("Start rebase"))
            }

            EditorScene(stopping.copy(autostash = true)).use { editor ->
                assertTrue(editor.isEnabled("Start rebase"))

                editor.click("Start rebase")
                editor.click("Reset the plan")
                editor.click("Cancel")

                assertEquals(listOf(CommitEditorAction.Start, CommitEditorAction.Reset, CommitEditorAction.Cancel), editor.actions)
            }
        }

    @Test
    fun `a plan that doesn't stop says Rewrite, and one that the check hasn't answered can't start`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val dropping = CommitEditorState(
                plan = plan.withAction(wip.hash, PlanAction.DROP),
                selected = wip.hash,
                check = PlanCheck(emptyList(), null),
                currentTip = cache.hash,
            )

            EditorScene(dropping).use { editor ->
                val start = "Rewrite 3 commits"

                assertTrue(editor.isEnabled(start))
                assertTrue("1 dropped" in editor.texts())
            }

            EditorScene(dropping.copy(check = null)).use { editor ->
                assertFalse(editor.isEnabled("Rewrite 3 commits"))
            }
        }

    @Test
    fun `P R S F E D set the selected row's action, the arrows select, and Alt with them moves it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            EditorScene(stopping).use { editor ->
                editor.press(Key.R)
                editor.press(Key.D)
                editor.press(Key.DirectionDown)
                editor.press(Key.DirectionUp, alt = true)
                editor.press(Key.F)

                assertEquals(
                    listOf(
                        CommitEditorAction.SetAction(cache.hash, PlanAction.REWORD),
                        CommitEditorAction.SetAction(cache.hash, PlanAction.DROP),
                        CommitEditorAction.SelectNext(newer = false),
                        CommitEditorAction.MoveSelected(newer = true),
                        CommitEditorAction.SetAction(cache.hash, PlanAction.FIXUP),
                    ),
                    editor.actions,
                )
            }
        }

    @Test
    fun `Onto switches between the latest main and where the branch started`(): Unit = runBlocking(Dispatchers.Swing) {
        EditorScene(stopping).use { editor ->
            val start = editor.texts().single { it.startsWith("Where it started") }

            assertTrue(editor.texts().any { it == "Latest main · 5555555, 2 new commits" }, editor.texts().toString())

            editor.click(start)

            assertEquals(listOf(CommitEditorAction.SetOnto(false)), editor.actions)
        }
    }

    @Test
    fun `fixup! commits that aren't under the commit they name get the strip that puts them there`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val fixup = RewriteFixtures.commit("f1f1f1f", "fixup! Add the parser", parent = cache.hash.take(7))
            val withFixup = line.copy(commits = listOf(fixup, cache, wip, add))
            val state = CommitEditorState(
                plan = RebasePlan.of(withFixup, listOf(add, wip, cache, fixup)),
                selected = fixup.hash,
                check = PlanCheck(emptyList(), null),
            )

            EditorScene(state).use { editor ->
                assertTrue("1 fixup! commit can go under the commit it names." in editor.texts(), editor.texts().toString())

                // Right after the text as it is laid out, not at the strip's far end, where a wide window put it out
                // of sight. The text's box fills the row when it takes the rest of it, so its laid-out line is measured.
                val sentence = editor.node("1 fixup! commit can go under the commit it names.")
                val layouts = mutableListOf<TextLayoutResult>()
                checkNotNull(sentence.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
                val textEnd = sentence.boundsInRoot.left + layouts.single().getLineRight(0)
                val button = editor.buttonBounds("Put them there")

                assertTrue(button.left - textEnd in 0f..20f, "text ends at $textEnd, the button is at $button")

                editor.click("Put them there")

                assertEquals(listOf(CommitEditorAction.PlaceFixups), editor.actions)
            }
        }

    @Test
    fun `the fixup strip is drawn as Leaf's notices, and Cancel and Reset are outlined without a fill`(): Unit =
        runBlocking(Dispatchers.Swing) {
        val fixup = RewriteFixtures.commit("f1f1f1f", "fixup! Add the parser", parent = cache.hash.take(7))
        val state = CommitEditorState(
            plan = RebasePlan.of(line.copy(commits = listOf(fixup, cache, wip, add)), listOf(add, wip, cache, fixup)),
            selected = fixup.hash,
            check = PlanCheck(emptyList(), null),
        )

        // Classic, whose notices are a strong amber and whose Unstage role, which Cancel and Reset had, is red
        EditorScene(state, Theme.Light, BuiltInStyles.CLASSIC).use { editor ->
            val pixels = editor.pixels()
            val sentence = editor.node("1 fixup! commit can go under the commit it names.").boundsInRoot
            val strip = checkNotNull(editor.notices).background.compositeOver(editor.background)

            assertColor(strip, pixels.getColor(WIDTH - 20, sentence.center.y.toInt()), "the strip, right of its button")

            for (button in listOf("Cancel", "Reset the plan")) {
                val bounds = editor.buttonBounds(button)
                val y = bounds.center.y.toInt()

                assertColor(editor.background, pixels.getColor(bounds.left.toInt() + 3, y), "inside $button")
                assertColor(editor.secondaryText, pixels.getColor(bounds.left.toInt(), y), "the line of $button")
            }
        }
    }

    @Test
    fun `the action menu shows each key at the right of its item, and why joining can't be chosen on the oldest row`(): Unit =
        runBlocking(Dispatchers.Swing) {
            EditorScene(stopping.copy(selected = add.hash)).use { editor ->
                // Rows are newest first, so the last pill is the oldest row's, which has nothing below to join
                editor.clickDescribed(editor.described("Action of").last())

                val squash = editor.node("Squash into the one below").boundsInRoot
                val key = editor.node("S").boundsInRoot

                assertTrue(key.left > squash.right, "the key $key is right of the label $squash")
                assertTrue(kotlin.math.abs(key.center.y - squash.center.y) < 2f, "on the label's line: $key, $squash")
                assertEquals(2, editor.texts().count { it == "Nothing below to join" }, editor.texts().toString())
                assertTrue(listOf("P", "R", "F", "E", "D").all { it in editor.texts() }, editor.texts().toString())
            }
        }

    private fun assertColor(expected: Color, argb: Int, what: String) {
        val actual = Color(argb)
        val close = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)
            .all { (a, b) -> kotlin.math.abs(a - b) < 3f / 255 }

        assertTrue(close, "$what: expected ${Integer.toHexString(expected.toArgb())}, got ${Integer.toHexString(argb)}")
    }

    @Test
    fun `the panel shows the commit the row becomes, its message and Start from, and the stop's files`(): Unit =
        runBlocking(Dispatchers.Swing) {
            PanelScene(stopping.copy(selected = wip.hash)).use { panel ->
                val texts = panel.texts()

                assertTrue("Commit 1 of 2 after the rebase" in texts, texts.toString())
                assertTrue("Start from" in texts, texts.toString())
                assertTrue("Files in b1c2d3e" in texts, texts.toString())
                assertTrue("NOTES.md" in texts, texts.toString())
                assertTrue(panel.message().startsWith("Add the parser"), panel.message())

                panel.setMessage("Add the parser and its cache")

                assertEquals(listOf(CommitEditorAction.SetMessage("Add the parser and its cache")), panel.actions)
            }

            PanelScene(stopping).use { panel ->
                assertTrue("Commit 2 of 2 after the rebase" in panel.texts(), panel.texts().toString())
                assertTrue("conflicts" in panel.texts(), panel.texts().toString())
                assertFalse("Start from" in panel.texts())
            }
        }

    @Test
    fun `a changed message offers to put it back, and a dropped row says so`(): Unit = runBlocking(Dispatchers.Swing) {
        val reworded = stopping.copy(plan = stopping.plan.withMessage(cache.hash, "Cache them\n"))

        PanelScene(reworded).use { panel ->
            assertEquals("Cache them", panel.message())

            panel.click("Put the message back")

            assertEquals(listOf(CommitEditorAction.ResetMessage), panel.actions)
        }

        PanelScene(stopping.copy(plan = plan.withAction(cache.hash, PlanAction.DROP))).use { panel ->
            assertTrue("Dropped" in panel.texts(), panel.texts().toString())
            assertTrue("Files in c0ffee1" in panel.texts())
        }
    }

    @Test
    fun `renders in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            val scene = ImageComposeScene(width = WIDTH + PANEL_WIDTH, height = HEIGHT, density = Density(1f)) {
                AppTheme(theme) {
                    Row {
                        CommitEditor(stopping, onAction = {}, modifier = Modifier.width(WIDTH.dp).fillMaxHeight())
                        CommitEditorPanel(stopping.copy(selected = wip.hash), onAction = {}, modifier = Modifier.width(PANEL_WIDTH.dp))
                    }
                }
            }

            var time = 0L
            repeat(20) { scene.render(time); time += 16_000_000L }
            File(folder, "commit-editor-${theme.name.lowercase()}.png")
                .writeBytes(scene.render(time).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }

    private open class Scene(
        width: Int,
        theme: Theme = Theme.Dark,
        style: StyleDefinition = BuiltInStyles.DEFAULT,
        content: @androidx.compose.runtime.Composable ((CommitEditorAction) -> Unit) -> Unit,
    ) : AutoCloseable {
        val actions = mutableListOf<CommitEditorAction>()

        /** The strip of notices and the background that the scene's theme drew with. */
        var notices: StripColors? = null
        var background = Color.Unspecified
        var secondaryText = Color.Unspecified

        protected val scene = ImageComposeScene(width = width, height = HEIGHT, density = Density(1f)) {
            AppTheme(theme, style = style) {
                notices = MaterialTheme.styleColors.warningStrip
                background = MaterialTheme.colors.background
                secondaryText = MaterialTheme.colors.onBackgroundSecondary
                content { actions += it }
            }
        }
        private var time = 0L

        init {
            settle()
        }

        fun settle() = repeat(10) {
            scene.render(time)
            time += 16_000_000L
        }

        fun texts(): List<String> = nodes().flatMap { it.texts() }

        fun node(text: String): SemanticsNode = nodes().first { text in it.texts() }

        /** Whether the element with [text] takes a click: a disabled button has no click, or says it's disabled. */
        fun isEnabled(text: String) = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }.any { node ->
            text in node.texts() && node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Disabled) == null
        }

        fun click(text: String) {
            checkNotNull(clickable(text).config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        fun press(key: Key, alt: Boolean = false) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isAltPressed = alt))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isAltPressed = alt))
            settle()
        }

        /** The pixels of the next frame. */
        fun pixels(): Bitmap = Bitmap.makeFromImage(scene.render(time))

        /** The content descriptions that start with [prefix], in the order they're shown. */
        fun described(prefix: String): List<String> = nodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .filter { it.startsWith(prefix) }

        /** Clicks the element described as [description]. */
        fun clickDescribed(description: String) {
            val node = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }.first {
                description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() &&
                    it.config.getOrNull(SemanticsActions.OnClick) != null
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        /** Where the button that says [text] is, its padding included. */
        fun buttonBounds(text: String) = clickable(text).boundsInRoot

        private fun clickable(text: String) = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }.first { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && text in node.texts()
        }

        protected fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

        protected fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        override fun close() = scene.close()
    }

    private class EditorScene(state: CommitEditorState, theme: Theme = Theme.Dark, style: StyleDefinition = BuiltInStyles.DEFAULT) :
        Scene(WIDTH, theme, style, { onAction -> CommitEditor(state, onAction) })

    private class PanelScene(state: CommitEditorState) : Scene(PANEL_WIDTH, content = { onAction -> CommitEditorPanel(state, onAction) }) {
        private fun field() = nodes().single { it.config.getOrNull(SemanticsProperties.EditableText) != null }

        fun message(): String = field().config[SemanticsProperties.EditableText].text

        fun setMessage(text: String) {
            checkNotNull(field().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            settle()
        }
    }
}
