// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branch
import dev.app.leaf.app.generated.resources.sort
import dev.app.leaf.domain.branches.BranchClick
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.SideMenuHeader
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.repositoryopen.BranchSelectionController
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Local branch rows with the selection for deleting several at once (fork-only, upstream issue 116): which rows are
 * highlighted after clicks with and without Ctrl or Shift, what a double-click does, and the menu on a selected row.
 * The real [BranchSelectionController] runs behind the rows, as the side panel's view model has it.
 */
class BranchSelectionRowsTest {
    private val names = listOf("claude/eager-ride", "claude/jolly-fox", "feature/export", "main", "old")
    private val branches = names.map { Branch(hash = "", name = "refs/heads/$it", isLocal = true) }
    private val main = branches[3]

    private val localBranches = MutableStateFlow(branches)
    private val calls = mutableListOf<String>()
    private val closeable = mutableListOf<String>()
    private var logged by mutableStateOf<String?>(null)
    private var keys = PointerKeyboardModifiers()

    private val controller = BranchSelectionController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        localBranches = localBranches,
        loggedBranch = { logged },
        addCloseableView = { closeable += "add $it" },
        removeCloseableView = { closeable += "remove $it" },
    )

    private val window = object : WindowInfo {
        override val isWindowFocused = true
        override val keyboardModifiers: PointerKeyboardModifiers get() = keys
    }

    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `a plain click selects the branch in the log, as it always did, and selects nothing here`(): Unit = runBlocking(Dispatchers.Swing) {
        render()

        click("feature/export")

        assertEquals(listOf("select feature/export"), calls)
        assertEquals(emptySet<String>(), controller.selection.value.selected)
        assertEquals(listOf("feature/export"), highlighted())
    }

    @Test
    fun `Ctrl-click adds to the branch the log has selected, and a click without a key starts over`(): Unit = runBlocking(Dispatchers.Swing) {
        render()

        click("claude/eager-ride")
        click("feature/export", ctrl = true)
        click("old", ctrl = true)

        assertEquals(listOf("select claude/eager-ride"), calls, "Only the plain click selects in the log")
        assertEquals(listOf("claude/eager-ride", "feature/export", "old"), highlighted())

        // Two quick presses on one row are a double-click, whose second press isn't a click
        delay(350L)
        click("feature/export", ctrl = true)

        assertEquals(listOf("claude/eager-ride", "old"), highlighted(), "A second Ctrl-click takes it out")

        click("main")

        assertEquals(listOf("main"), highlighted())
        assertEquals(emptySet<String>(), controller.selection.value.selected)
    }

    @Test
    fun `Shift-click takes the rows between the last click and this one`(): Unit = runBlocking(Dispatchers.Swing) {
        render()

        click("claude/jolly-fox")
        click("main", shift = true)

        assertEquals(listOf("claude/jolly-fox", "feature/export", "main"), highlighted())

        click("old", ctrl = true)

        assertEquals(listOf("claude/jolly-fox", "feature/export", "main", "old"), highlighted())
    }

    @Test
    fun `a double-click checks a branch out, but not with Ctrl or Shift held`(): Unit = runBlocking(Dispatchers.Swing) {
        render()

        doubleClick("feature/export")

        // The second press is the double-click's: it doesn't select again
        assertEquals(listOf("select feature/export", "checkout feature/export"), calls)

        calls.clear()
        doubleClick("old", ctrl = true)
        doubleClick("claude/eager-ride", shift = true)

        assertTrue(calls.none { it.startsWith("checkout") }, calls.toString())
    }

    @Test
    fun `the menu on a selected branch offers to delete them all, and its own menu otherwise`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("claude/eager-ride")
        click("old", ctrl = true)

        rightClick("old")

        assertTrue("Delete 2 branches" in texts(), texts().toString())
        assertTrue("Clear selection" in texts())
        assertFalse("Checkout branch" in texts(), "The branch's own items are for one branch")

        click("Delete 2 branches", menu = true)

        assertEquals(listOf("delete"), calls.filter { it == "delete" })
    }

    @Test
    fun `Clear selection in the menu empties the selection`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("claude/eager-ride")
        click("old", ctrl = true)

        rightClick("claude/eager-ride")
        click("Clear selection", menu = true)

        assertEquals(emptySet<String>(), controller.selection.value.selected)
    }

    @Test
    fun `the menu on a branch that isn't selected is its own, whatever is selected`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("claude/eager-ride")
        click("old", ctrl = true)

        rightClick("claude/jolly-fox")

        assertTrue("Checkout branch" in texts(), texts().toString())
        assertFalse(texts().any { it.startsWith("Delete 2") })
        assertEquals(setOf("refs/heads/claude/eager-ride", "refs/heads/old"), controller.selection.value.selected)
    }

    @Test
    fun `Esc is a closable view while something is selected, and clears it`(): Unit = runBlocking(Dispatchers.Swing) {
        render()

        click("claude/eager-ride")
        click("old", ctrl = true)

        // The first remove is the empty selection at the start
        assertEquals(listOf("remove ${CloseableView.BRANCH_SELECTION}", "add ${CloseableView.BRANCH_SELECTION}"), closeable)

        controller.clear()
        settle()

        assertEquals(
            listOf("remove", "add", "remove").map { "$it ${CloseableView.BRANCH_SELECTION}" },
            closeable,
        )
        assertEquals(emptyList<String>(), highlighted().filter { it != "claude/eager-ride" })
    }

    @Test
    fun `a refresh drops the branches that are gone and keeps the others`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("claude/eager-ride")
        click("old", ctrl = true)
        click("feature/export", ctrl = true)

        localBranches.value = branches.filter { it.simpleName != "old" }
        settle()

        assertEquals(
            setOf("refs/heads/claude/eager-ride", "refs/heads/feature/export"),
            controller.selection.value.selected,
        )
    }

    @Test
    fun `the deletion request has the selected branches, and the merged request every local one`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("claude/eager-ride")
        click("old", ctrl = true)

        assertEquals(
            listOf("claude/eager-ride", "old"),
            controller.deletionRequest().branches.map { it.simpleName },
        )
        assertFalse(controller.deletionRequest().mergedOnly)
        assertEquals(names, controller.mergedDeletionRequest().branches.map { it.simpleName })
        assertTrue(controller.mergedDeletionRequest().mergedOnly)
    }

    @Test
    fun `selecting a folder's branches adds them to the selection`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("old")

        controller.add(listOf("refs/heads/claude/eager-ride", "refs/heads/claude/jolly-fox"))
        settle()

        assertEquals(listOf("claude/eager-ride", "claude/jolly-fox"), highlighted().filter { it.startsWith("claude/") })
        assertEquals(3, controller.selection.value.selected.size, "The branch that was selected is in it")
    }

    @Test
    fun `renders the side panel with a selection in both themes, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (themeUnderTest in listOf(Theme.Dark, Theme.Light)) {
            for (selectedNames in listOf(emptyList(), listOf("claude/eager-ride"), listOf("claude/eager-ride", "feature/export", "old"))) {
                controller.clear()
                for ((index, name) in selectedNames.withIndex()) {
                    controller.onClick("refs/heads/$name", if (index == 0) BranchClick.Plain else BranchClick.Toggle) { emptyList() }
                    if (index == 0) logged = "refs/heads/$name"
                }

                render(theme = themeUnderTest, withHeader = true)
                settle()
                File(folder, "branch-selection-${selectedNames.size}-${themeUnderTest.name.lowercase()}.png")
                    .writeBytes(scene!!.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                closeScene()
                scene = null
            }
        }
    }

    private suspend fun render(theme: Theme = Theme.Dark, withHeader: Boolean = false) {
        scene = ImageComposeScene(width = 420, height = if (withHeader) 240 else 300, density = Density(1f)) {
            AppTheme(theme) {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    val selection by controller.selection.collectAsState()

                    Column(Modifier.width(380.dp).run { if (withHeader) background(MaterialTheme.colors.background) else this }) {
                        if (withHeader) {
                            SideMenuHeader(
                                text = "Local branches",
                                icon = painterResource(Res.drawable.branch),
                                itemsCount = branches.size,
                                isExpanded = true,
                                sortAction = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (selection.selected.size >= 2) {
                                            BranchSelectionChip(
                                                count = selection.selected.size,
                                                onClear = { controller.clear() },
                                                modifier = Modifier.padding(end = 4.dp),
                                            )
                                        }

                                        Icon(
                                            painter = painterResource(Res.drawable.sort),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colors.onBackgroundSecondary,
                                        )
                                    }
                                },
                            )
                        }

                        for (branch in branches) {
                            Branch(
                                branch = branch,
                                displayName = branch.simpleName,
                                depth = 0,
                                ageLabel = null,
                                currentBranch = main,
                                worktreeUsers = null,
                                worktreesBaseBranch = null,
                                worktreesBase = null,
                                isSelectedItem = selection.isHighlighted(branch.name, logged),
                                onBranchClicked = { click ->
                                    if (controller.onClick(branch.name, click) { branches.map { it.name } }) {
                                        logged = branch.name
                                        calls += "select ${branch.simpleName}"
                                    }
                                },
                                onBranchDoubleClicked = { calls += "checkout ${branch.simpleName}" },
                                selectedCount = if (selection.isMultiSelectionOf(branch.name)) selection.selected.size else 0,
                                onDeleteSelected = { calls += "delete" },
                                onClearSelection = { controller.clear() },
                                onCheckoutBranch = {},
                                onMergeBranch = {},
                                onRebaseBranch = {},
                                onDeleteBranch = {},
                                onChangeDefaultUpstreamBranch = {},
                                onRenameBranch = {},
                                onChooseWorktreesBase = {},
                                onSwitchToWorktree = {},
                                onNewWorktree = {},
                                onCompareWithCurrent = {},
                                onCompareWith = {},
                                onCompareWithWorkingTree = {},
                                cherryPickItem = null,
                                onCherryPick = {},
                                onCopyBranchNameToClipboard = {},
                            )
                        }
                    }
                }
            }
        }

        settle()
    }

    /** The names of the rows that are highlighted, top to bottom: the rows say it in their semantics. */
    private suspend fun highlighted(): List<String> {
        settle()

        return scene!!.semanticsOwners
            .flatMap { it.getAllSemanticsNodes(true) }
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .sortedBy { it.boundsInWindow.top }
            .mapNotNull { node -> node.texts().firstOrNull { it in names } }
    }

    private suspend fun click(name: String, ctrl: Boolean = false, shift: Boolean = false, menu: Boolean = false) {
        press(position(name, menu), ctrl, shift)
    }

    private suspend fun doubleClick(name: String, ctrl: Boolean = false, shift: Boolean = false) {
        val at = position(name, false)

        press(at, ctrl, shift, settle = false)
        // A double-click's second press comes after a minimum time, and before the next frames: its timeout is short
        delay(60L)
        press(at, ctrl, shift)
    }

    private suspend fun press(position: Offset, ctrl: Boolean, shift: Boolean, settle: Boolean = true) {
        val scene = scene!!

        keys = PointerKeyboardModifiers(isCtrlPressed = ctrl, isShiftPressed = shift)
        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        keys = PointerKeyboardModifiers()
        // Away from the rows, which draw a hovered one in another shade
        scene.sendPointerEvent(PointerEventType.Move, Offset(410f, 290f))

        if (settle) {
            settle()
        }
    }

    private suspend fun rightClick(name: String) {
        val scene = scene!!
        val position = position(name, false)

        delay(50L)
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

    /** Where a row, or a menu item, is: its center, with the row's own right part for the click. */
    private fun position(text: String, menu: Boolean): Offset {
        val node = if (menu) nodes().first { text in it.texts() } else row(text)

        return node.boundsInWindow.center.let { if (menu) it else Offset(250f, it.y) }
    }

    private fun row(name: String): SemanticsNode = nodes().first { it.texts() == listOf(name) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** A few frames, as a recomposition that takes a highlight away needs the next one to be drawn. */
    private suspend fun settle() {
        repeat(8) {
            scene!!.render(System.nanoTime())
            delay(20L)
        }
    }
}
