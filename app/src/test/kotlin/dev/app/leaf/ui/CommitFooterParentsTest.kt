// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.log.parentLinks
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.usecases.LoadLogResult
import dev.app.leaf.repositoryopen.CommitParentsState
import dev.app.leaf.repositoryopen.ParentJump
import dev.app.leaf.theme.AppTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/** The default width of the commit details' pane. */
private const val WIDTH = 330
private const val HEIGHT = 340

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(name: String, subject: String = "Subject of $name", vararg parents: String) = Commit(
    hash = name.padEnd(40, '0'),
    message = subject,
    committer = someone,
    author = someone,
    date = 1_760_000_000,
    parentsHashes = parents.map { it.padEnd(40, '0') },
)

/**
 * The commit details' footer with a commit's parents (fork-only, upstream issue 270), rendered offscreen at the pane's
 * default width and clicked as a user would: a normal commit, a merge, a root commit, a stash, an octopus merge, and
 * a parent that isn't loaded, that is looked for, and that wasn't found. Set `LEAF_RENDER_DIR` to a folder to get a
 * PNG of every state in both themes, and pass `--rerun`: Gradle doesn't see an environment variable change.
 */
class CommitFooterParentsTest {
    private val older = commit("a1b2c3d", "Wrap Shift+Enter to the last match")
    private val merged = commit("c4d3b2a", "Add the worktree picker to the toolbar")

    private var state by mutableStateOf<CommitParentsState?>(null)
    private var theme = Theme.Dark
    private val clicked = mutableListOf<String>()
    private var toggled = 0
    private var footerHeight = 0
    private val clipboard = FooterClipboard()

    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    private fun parents(
        commit: Commit,
        loaded: List<Commit> = emptyList(),
        isStash: Boolean = false,
        isExpanded: Boolean = false,
        jump: ParentJump = ParentJump.None,
    ) = CommitParentsState(
        commit = commit,
        links = parentLinks(commit, isStash) { hash -> loaded.firstOrNull { it.hash == hash } },
        isExpanded = isExpanded,
        jump = jump,
    )

    @Test
    fun `a commit with one parent shows its hash and subject, without a number`() {
        state = parents(commit("e91b4d2", "Fix the log", "a1b2c3d"), loaded = listOf(older))
        render()

        val row = row("a1b2c3d")

        assertEquals(listOf("a1b2c3d", "Wrap Shift+Enter to the last match"), row.texts())
        assertFalse("1" in texts(), texts().toString())
        assertWithinPane()
    }

    @Test
    fun `a merge shows both parents in git's order, numbered`() {
        state = parents(commit("b7e1a90", "Merge pull request #412", "a1b2c3d", "c4d3b2a"), loaded = listOf(older, merged))
        render()

        assertEquals(listOf("1", "a1b2c3d", "Wrap Shift+Enter to the last match"), row("a1b2c3d").texts())
        assertEquals(listOf("2", "c4d3b2a", "Add the worktree picker to the toolbar"), row("c4d3b2a").texts())
        assertTrue(row("a1b2c3d").boundsInWindow.top < row("c4d3b2a").boundsInWindow.top)
        assertWithinPane()
    }

    @Test
    fun `a root commit says it has no parent`() {
        state = parents(commit("a1f0c33", "Initial commit"))
        render()

        assertTrue("Root commit, no parent" in texts(), texts().toString())
        assertWithinPane()
    }

    @Test
    fun `a stash shows only the commit it was made on`() {
        state = parents(
            commit("5c2e7f8", "WIP on main", "a1b2c3d", "9999999", "8888888"),
            loaded = listOf(older),
            isStash = true,
        )
        render()

        assertEquals(listOf("a1b2c3d", "Wrap Shift+Enter to the last match"), row("a1b2c3d").texts())
        assertFalse("9999999" in texts(), texts().toString())
        assertFalse("1" in texts(), texts().toString())
    }

    @Test
    fun `a parent that the log hasn't loaded says so, and a click on it asks for it`() {
        state = parents(commit("e91b4d2", "Old commit", "3a7c5e0"))
        render()

        assertEquals(listOf("3a7c5e0", "Not loaded yet"), row("3a7c5e0").texts())

        click(row("3a7c5e0").boundsInWindow.center)

        assertEquals(listOf("3a7c5e0".padEnd(40, '0')), clicked)
    }

    @Test
    fun `a parent that is looked for says so`() {
        state = parents(
            commit("e91b4d2", "Old commit", "3a7c5e0"),
            jump = ParentJump.Looking("3a7c5e0".padEnd(40, '0'), loaded = 1_500),
        )
        render()

        assertEquals(listOf("3a7c5e0", "Looking, click to stop"), row("3a7c5e0").texts())
        assertWithinPane()
    }

    @Test
    fun `a parent that wasn't found says why, for each way a search can end`() {
        val hash = "3a7c5e0".padEnd(40, '0')
        val expected = mapOf(
            LoadLogResult.NotReachable to "Not in the log",
            LoadLogResult.LimitReached(25_000) to "Not in the next 25,000",
            LoadLogResult.LogUnavailable to "Couldn't load the log",
        )

        for ((result, text) in expected) {
            state = parents(commit("e91b4d2", "Old commit", "3a7c5e0"), jump = ParentJump.NotFound(hash, result))
            render()

            assertEquals(listOf("3a7c5e0", text), row("3a7c5e0").texts(), "$result")
            assertWithinPane()
            closeScene()
        }
    }

    @Test
    fun `only the parent that was clicked shows the search`() {
        val second = "c4d3b2a".padEnd(40, '0')
        state = parents(
            commit("b7e1a90", "Merge", "a1b2c3d", "c4d3b2a"),
            loaded = listOf(older),
            jump = ParentJump.Looking(second),
        )
        render()

        assertEquals("Wrap Shift+Enter to the last match", row("a1b2c3d").texts().last())
        assertEquals("Looking, click to stop", row("c4d3b2a").texts().last())
    }

    @Test
    fun `a click selects the parent, and a hidden one works as the others`() {
        val many = commit("f6f6f6f", "Octopus", *Array(6) { "a$it" })
        state = parents(many, isExpanded = true)
        render()

        click(row("a000000").boundsInWindow.center)
        click(row("a500000").boundsInWindow.center)

        assertEquals(listOf("a0".padEnd(40, '0'), "a5".padEnd(40, '0')), clicked)
    }

    @Test
    fun `parents beyond the first three open in place, and close again`() {
        val many = commit("f6f6f6f", "Octopus", *Array(6) { "a$it" })
        state = parents(many)
        render()

        assertTrue("Show 3 more parents" in texts(), texts().toString())
        assertFalse("a300000" in texts())

        click(row("Show 3 more parents").boundsInWindow.center)

        assertEquals(1, toggled)
        assertTrue("Show fewer parents" in texts(), texts().toString())
        assertTrue("a300000" in texts() && "a500000" in texts(), texts().toString())

        click(row("Show fewer parents").boundsInWindow.center)

        assertEquals(2, toggled)
        assertTrue("Show 3 more parents" in texts(), texts().toString())
        assertFalse("a300000" in texts())
    }

    @Test
    fun `an octopus merge opened shows every parent in a block that scrolls instead of growing the footer`() {
        val parentsCount = 40
        val many = commit("f6f6f6f", "Octopus", *Array(parentsCount) { "a" + it.toString(16).padStart(2, '0') })
        state = parents(many, isExpanded = true)
        render()

        // The footer stays about the size of seven and a half rows plus the author, the hash and the link that closes it
        assertTrue(footerHeight < 72 + 8 * 22, "The footer is $footerHeight px high")
        assertTrue("Show fewer parents" in texts(), texts().toString())

        // The last parent is out of sight until the block is scrolled
        val last = "a27"
        assertTrue(row("${last}0000").positionInRoot.y >= footerHeight, "${row("${last}0000").positionInRoot}")

        scene!!.sendPointerEvent(PointerEventType.Move, row("a000000").boundsInWindow.center)
        scene!!.sendPointerEvent(
            PointerEventType.Scroll,
            row("a000000").boundsInWindow.center,
            scrollDelta = Offset(0f, 2_000f),
        )
        settle()

        val shown = row("${last}0000").boundsInWindow
        assertTrue(shown.bottom > 0f && shown.bottom <= footerHeight, "$shown")
    }

    @Test
    fun `the footer of a commit with one parent, or none, is as tall as it was, and a merge's grows by its second row`() {
        val sizes = mutableMapOf<String, Int>()

        for ((label, commit) in mapOf(
            "none" to commit("1111111", "Root"),
            "one" to commit("2222222", "One", "a1b2c3d"),
            "two" to commit("3333333", "Two", "a1b2c3d", "c4d3b2a"),
            "four" to commit("4444444", "Four", "a1", "a2", "a3", "a4"),
        )) {
            state = parents(commit)
            render()
            sizes[label] = footerHeight
            closeScene()
        }

        assertEquals(72, sizes["none"], sizes.toString())
        assertEquals(72, sizes["one"], sizes.toString())
        assertTrue(sizes["two"]!! > 72, sizes.toString())
        assertEquals(2 * 22, sizes["four"]!! - sizes["two"]!!, sizes.toString())
    }

    @Test
    fun `the rows of a merge are one row apart`() {
        state = parents(commit("b7e1a90", "Merge", "a1b2c3d", "c4d3b2a"))
        render()

        assertEquals(22f, row("c4d3b2a").boundsInWindow.top - row("a1b2c3d").boundsInWindow.top)
    }

    @Test
    fun `without a parents section the footer is as tall as before`() {
        state = null
        render()

        assertEquals(72, footerHeight)
    }

    @Test
    fun `a right click opens a menu that selects the parent or copies its hash`() {
        state = parents(commit("e91b4d2", "Fix", "a1b2c3d"), loaded = listOf(older))
        render()

        rightClick(row("a1b2c3d").boundsInWindow.center)

        assertTrue("Select in the log" in texts(), texts().toString())
        assertTrue("Copy commit hash" in texts(), texts().toString())

        click(menuItem("Copy commit hash").center)
        Thread.sleep(200)

        assertEquals("a1b2c3d".padEnd(40, '0'), clipboard.text)
        assertTrue(clicked.isEmpty(), "Copying selects nothing")
    }

    @Test
    fun `the menu's first item selects the parent as a click does`() {
        state = parents(commit("e91b4d2", "Fix", "a1b2c3d"), loaded = listOf(older))
        render()

        rightClick(row("a1b2c3d").boundsInWindow.center)
        click(menuItem("Select in the log").center)

        assertEquals(listOf("a1b2c3d".padEnd(40, '0')), clicked)
    }

    @Test
    fun `renders every state in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()
        val hash = "3a7c5e0".padEnd(40, '0')
        val cases = linkedMapOf(
            "normal" to parents(commit("e91b4d2", "Fix the log search wrap-around", "a1b2c3d"), listOf(older)),
            "merge" to parents(commit("b7e1a90", "Merge pull request #412 from zzhelev/worktree-picker", "a1b2c3d", "c4d3b2a"), listOf(older, merged)),
            "root" to parents(commit("a1f0c33", "Initial commit")),
            "stash" to parents(commit("5c2e7f8", "WIP on main", "a1b2c3d", "9999999"), listOf(older), isStash = true),
            "not-loaded" to parents(commit("e91b4d2", "Old", "3a7c5e0")),
            "looking" to parents(commit("e91b4d2", "Old", "3a7c5e0"), jump = ParentJump.Looking(hash, 1_500)),
            "not-found" to parents(commit("e91b4d2", "Old", "3a7c5e0"), jump = ParentJump.NotFound(hash, LoadLogResult.LimitReached(25_000))),
            "octopus" to parents(commit("f6f6f6f", "Octopus merge", *Array(5) { "a$it" }), listOf(older)),
            "octopus-open" to parents(commit("f6f6f6f", "Octopus merge", *Array(40) { "a" + it.toString(16).padStart(2, '0') }), isExpanded = true),
        )

        for (themeUnderTest in listOf(Theme.Dark, Theme.Light)) {
            for ((name, value) in cases) {
                theme = themeUnderTest
                state = value
                render()
                val image = scene!!.render(frameTime)
                File(folder, "parents-$name-${themeUnderTest.name.lowercase()}.png")
                    .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                closeScene()
            }
        }
    }

    private fun render() {
        footerHeight = 0
        frameTime = 0L

        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme(theme) {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    Box(Modifier.onSizeChanged { footerHeight = it.height }) {
                        CommitFooter(
                            shortHash = "b7e1a90",
                            hash = "b7e1a90".padEnd(40, '0'),
                            date = 1_760_000_000,
                            author = someone,
                            parents = state?.let { current ->
                                {
                                    ParentRows(
                                        state = current,
                                        onParentClicked = { clicked += it },
                                        onToggleMore = {
                                            toggled++
                                            state = state?.copy(isExpanded = !state!!.isExpanded)
                                        },
                                        modifier = Modifier.padding(end = 16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle() {
        repeat(30) {
            scene!!.render(frameTime)
            frameTime += 16_000_000L
        }
    }

    private fun click(position: Offset) {
        val scene = scene!!

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        settle()
    }

    private fun rightClick(position: Offset) {
        val scene = scene!!

        Thread.sleep(50)
        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isSecondaryPressed = true),
            button = PointerButton.Secondary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
        settle()
    }

    /** The row of a parent, or of the link that opens more: the node that has [text] among its texts. */
    private fun row(text: String): SemanticsNode = mergedNodes().firstOrNull { text in it.texts() }
        ?: error("No row with $text in ${mergedNodes().map { it.texts() }}")

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun menuItem(label: String): Rect = nodes().first { label in it.texts() }.boundsInWindow

    private fun assertWithinPane() {
        for (node in nodes()) {
            if (node.texts().isEmpty()) continue
            val bounds = node.boundsInWindow

            assertTrue(bounds.right <= WIDTH + 0.5f, "${node.texts()} reaches ${bounds.right}, past the pane's $WIDTH")
        }
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun mergedNodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}

@OptIn(ExperimentalComposeUiApi::class)
private class FooterClipboard : Clipboard {
    @Volatile
    var text: String? = null

    override val nativeClipboard: NativeClipboard get() = error("Not used")

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
