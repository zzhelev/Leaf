// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.theme.AppTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** Width of the column after Message in these tests, as Date's. */
private const val DATE_WIDTH = 110

/** A row whose Message column is 300 wide: the chips' share is 220, room for main and +4, not for another chip. */
private const val NARROW = 300 + DATE_WIDTH

/** A log row's chips (fork-only), rendered offscreen at 1 px per dp and clicked like a user would. */
class LogRefChipsTest {
    private val main = Branch(HASH, "refs/heads/main", isLocal = true)
    private val branches = listOf(
        Branch(HASH, "refs/heads/claude/quirky-bose", isLocal = true),
        main,
        Branch(HASH, "refs/heads/claude/eager-hopper", isLocal = true),
        Branch(HASH, "refs/remotes/origin/main", isLocal = false),
        Branch(HASH, "refs/heads/feature/x", isLocal = true),
    )
    private val tags = listOf(Tag(HASH, "tag-object", "refs/tags/leaf-1.3.0"))

    private val checkedOut = mutableListOf<String>()
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `shows every chip when they fit, the current branch first and the remote branch on its local branch's chip`() {
        render(width = 1400)

        assertEquals(
            listOf("main", "claude/eager-hopper", "claude/quirky-bose", "feature/x", "leaf-1.3.0", "Message"),
            shownTexts(),
        )
    }

    @Test
    fun `puts the chips past their share of Message into +N, and the column after Message keeps its width`() {
        render(width = NARROW)

        assertEquals(listOf("main", "+4", "Message"), shownTexts())
        assertEquals(DATE_WIDTH.toFloat(), node("date").boundsInRoot.width)
    }

    @Test
    fun `at any width, the message keeps 64 and the chips after the first stay within 80% of Message`() {
        for (column in 200..900 step 20) {
            scene?.close()
            render(width = column + DATE_WIDTH)

            val message = node("message").boundsInRoot
            val chipTexts = nodes().filter { it.isShown() && it.texts().any { text -> text != "Message" } }

            assertTrue(message.width >= 63.5f, "$column: $message")

            if (chipTexts.size > 2) {
                val end = chipTexts.maxOf { it.boundsInRoot.right }
                assertTrue(end <= column * 0.8f, "$column: chips end at $end")
            }
        }
    }

    @Test
    fun `cuts the first chip down when it alone doesn't fit next to +N, and the message keeps some room`() {
        val long = Branch(HASH, "refs/heads/feature/a-branch-name-that-goes-on-for-a-long-while", isLocal = true)

        // The long chip is 250 wide, and with +2 doesn't fit in the share of 220
        render(width = NARROW, branches = listOf(long, branches[0], branches[2]), tags = emptyList(), currentBranch = long)

        assertEquals(listOf(long.logName, "+2", "Message"), shownTexts())
        assertTrue(node("message").boundsInRoot.width >= 63f, node("message").boundsInRoot.toString())
    }

    @Test
    fun `ends a long name in an ellipsis, and keeps room for the marks after it`() {
        val name = "feature/a-branch-name-that-goes-on-for-a-long-while"
        val current = Branch(HASH, "refs/heads/$name-1", isLocal = true)
        val other = Branch(HASH, "refs/heads/$name-2", isLocal = true)

        render(width = 1400, branches = listOf(current, other), tags = emptyList(), currentBranch = current)

        val currentText = nodes().first { it.texts().contains(current.logName) }.boundsInRoot
        val otherText = nodes().first { it.texts().contains(other.logName) }.boundsInRoot

        assertTrue(otherText.width < 250f, otherText.toString())
        // The current branch's mark after the name takes its room from the name, rather than being pushed out
        assertTrue(currentText.width < otherText.width - 10f, "$currentText, $otherText")
    }

    @Test
    fun `+N opens a list of every ref, each with a chip of its own, that Esc closes`() {
        render(width = NARROW)

        click("+4")

        assertTrue(
            shownTexts().containsAll(
                listOf(
                    "Branches", "claude/eager-hopper", "claude/quirky-bose", "feature/x",
                    "Remote branches", "origin/main",
                    "Tags", "leaf-1.3.0",
                )
            ),
            shownTexts().toString(),
        )

        pressEscape()

        assertEquals(listOf("main", "+4", "Message"), shownTexts())
    }

    @Test
    fun `a chip in the list acts on its ref, and closes the list first`() {
        render(width = NARROW)

        click("+4")
        doubleClick("origin/main")

        assertEquals(listOf("refs/remotes/origin/main"), checkedOut)
        assertFalse(shownTexts().contains("Remote branches"), shownTexts().toString())
    }

    private fun render(
        width: Int,
        branches: List<Branch> = this.branches,
        tags: List<Tag> = this.tags,
        currentBranch: Branch? = main,
    ) {
        scene = ImageComposeScene(width = width, height = 400, density = Density(1f)) {
            AppTheme {
                Row(
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        LogRefChips(
                            branches = branches,
                            tags = tags,
                            currentBranch = currentBranch,
                            color = Color.Blue,
                            actions = actions(),
                        )
                        Text("Message", maxLines = 1, modifier = Modifier.weight(1f).testTag("message"))
                    }
                    Box(modifier = Modifier.width(DATE_WIDTH.dp).height(20.dp).testTag("date"))
                }
            }
        }

        render()
    }

    private fun actions() = LogRefActions(
        onCheckoutBranch = { checkedOut += it.name },
        onMergeBranch = {},
        onDeleteBranch = {},
        onDeleteRemoteBranch = {},
        onRebaseBranch = {},
        onCheckoutTag = { checkedOut += it.name },
        onDeleteTag = {},
        onPushRemoteBranch = {},
        onPullRemoteBranch = {},
        onChangeDefaultUpstreamBranch = {},
        onRenameBranch = {},
        onCopyBranchNameToClipboard = {},
    )

    private fun render() {
        scene!!.render()
        scene!!.render()
    }

    private var time = 0L

    /** Clicks the middle of the shown text that reads [text], then lets the scene settle. */
    private fun click(text: String) {
        tap(position(text))
        render()
    }

    private fun doubleClick(text: String) {
        val position = position(text)

        tap(position)
        tap(position)
        render()
    }

    private fun tap(position: Offset) {
        val scene = scene!!

        time += 100
        scene.sendPointerEvent(PointerEventType.Move, position, timeMillis = time)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            timeMillis = time,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        time += 20
        scene.sendPointerEvent(PointerEventType.Release, position, timeMillis = time, button = PointerButton.Primary)
    }

    private fun position(text: String): Offset =
        nodes().first { it.isShown() && it.texts().contains(text) }.boundsInWindow.center

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun pressEscape() {
        scene!!.sendKeyEvent(KeyEvent(key = Key.Escape, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = Key.Escape, type = KeyEventType.KeyUp))
        render()
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun node(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /** The texts on screen, left to right and top to bottom. Chips that didn't fit are composed but not placed. */
    private fun shownTexts(): List<String> = nodes()
        .filter { it.isShown() }
        .sortedWith(compareBy({ (it.boundsInRoot.center.y / 10).roundToInt() }, { it.boundsInRoot.left }))
        .flatMap { it.texts() }

    private fun SemanticsNode.isShown() = generateSequence(layoutInfo) { it.parentInfo }.all { it.isPlaced }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
