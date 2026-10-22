// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
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
import dev.app.leaf.domain.branches.BranchClick
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.repositoryopen.BranchSelectionController
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A remote's branch rows with the selection for deleting several on the server (fork-only, follows upstream issue
 * 116), next to local branch rows: a selection holds one group, the menu names the remote, and the requests are the
 * remote's. The real [BranchSelectionController] runs behind the rows.
 */
class RemoteBranchSelectionRowsTest {
    private val local = listOf("local-a", "local-b").map { Branch(hash = "", name = "refs/heads/$it", isLocal = true) }
    private val origin = listOf("agent-1", "agent-2", "agent-3", "release/1.0").map {
        Branch(hash = "", name = "refs/remotes/origin/$it", isLocal = false)
    }
    private val upstream = Branch(hash = "", name = "refs/remotes/upstream/up-x", isLocal = false)
    private val rowNames = (local + origin).map { it.simpleName }
    private val localNames = local.map { it.name }

    private val calls = mutableListOf<String>()
    private var logged by mutableStateOf<String?>(null)
    private var keys = PointerKeyboardModifiers()

    private val controller = BranchSelectionController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        localBranches = MutableStateFlow(local),
        remoteBranches = MutableStateFlow(origin + upstream),
        loggedBranch = { logged },
        addCloseableView = {},
        removeCloseableView = {},
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
    fun `a plain click on a remote's branch selects it in the log and highlights it, as a local one`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render()

            click("agent-2")

            assertEquals(listOf("select agent-2"), calls)
            assertEquals(listOf("agent-2"), highlighted())
            assertEquals(emptySet<String>(), controller.selection.value.selected)
        }

    @Test
    fun `Ctrl-click and Shift-click select a remote's branches`(): Unit = runBlocking(Dispatchers.Swing) {
        render()

        click("agent-1")
        click("agent-3", shift = true)
        click("release/1.0", ctrl = true)

        assertEquals(listOf("agent-1", "agent-2", "agent-3", "release/1.0"), highlighted())
        assertEquals("refs/remotes/origin/", controller.selection.value.group)
    }

    @Test
    fun `Ctrl-click on a local branch while a remote's are selected starts over with it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render()

            click("agent-1")
            click("agent-2", ctrl = true)
            click("local-a", ctrl = true)

            assertEquals(setOf("refs/heads/local-a"), controller.selection.value.selected)
            assertEquals(listOf("local-a"), highlighted())
        }

    @Test
    fun `Ctrl-click on a remote's branch after a plain click on a local one doesn't take the local one along`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render()

            click("local-b")
            click("agent-1", ctrl = true)

            assertEquals(setOf("refs/remotes/origin/agent-1"), controller.selection.value.selected)
        }

    @Test
    fun `the menu on a selected remote branch deletes them on the remote`(): Unit = runBlocking(Dispatchers.Swing) {
        render()
        click("agent-1")
        click("agent-3", ctrl = true)

        rightClick("agent-3")

        assertTrue("Delete 2 branches on origin" in texts(), texts().toString())
        assertTrue("Clear selection" in texts())
        assertFalse(texts().any { it.startsWith("Delete branch") }, "The branch's own items are for one branch")

        click("Delete 2 branches on origin", menu = true)

        assertEquals(listOf("delete"), calls.filter { it == "delete" })
    }

    @Test
    fun `a double-click checks a remote's branch out, but not with Ctrl or Shift held`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render()

            doubleClick("agent-2")

            assertEquals(listOf("select agent-2", "checkout agent-2"), calls)

            calls.clear()
            doubleClick("agent-3", ctrl = true)
            doubleClick("agent-1", shift = true)

            assertTrue(calls.none { it.startsWith("checkout") }, calls.toString())
        }

    @Test
    fun `the deletion request is the remote's, and merged ones are every branch of that remote`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render()
            click("agent-1")
            click("agent-2", ctrl = true)

            val request = controller.deletionRequest()

            assertEquals("origin", request.remote)
            assertEquals(listOf("refs/remotes/origin/agent-1", "refs/remotes/origin/agent-2"), request.branches.map { it.name })
            assertFalse(request.mergedOnly)

            val merged = controller.mergedDeletionRequest("origin")

            assertEquals("origin", merged.remote)
            assertTrue(merged.mergedOnly)
            assertEquals(origin.map { it.name }, merged.branches.map { it.name }, "Not upstream's")

            assertNull(controller.mergedDeletionRequest().remote, "The local one stays local")
        }

    private suspend fun render(theme: Theme = Theme.Dark) {
        scene = ImageComposeScene(width = 420, height = 300, density = Density(1f)) {
            AppTheme(theme) {
                CompositionLocalProvider(LocalWindowInfo provides window) {
                    val selection by controller.selection.collectAsState()

                    Column(Modifier.width(380.dp)) {
                        for (branch in local) {
                            Branch(
                                branch = branch,
                                displayName = branch.simpleName,
                                depth = 0,
                                ageLabel = null,
                                currentBranch = null,
                                worktreeUsers = null,
                                worktreesBaseBranch = null,
                                worktreesBase = null,
                                isSelectedItem = selection.isHighlighted(branch.name, logged?.takeIf { it in localNames }),
                                onBranchClicked = { click -> onClick(branch, click, local) },
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

                        for (branch in origin) {
                            RemoteBranches(
                                remoteBranch = branch,
                                displayName = branch.simpleName,
                                depth = 0,
                                ageLabel = null,
                                currentBranch = null,
                                worktreesBase = null,
                                isSelectedItem = selection.isHighlighted(branch.name, logged?.takeIf { it !in localNames }),
                                onBranchClicked = { click -> onClick(branch, click, origin) },
                                selectedCount = if (selection.isMultiSelectionOf(branch.name)) selection.selected.size else 0,
                                remoteName = "origin",
                                onDeleteSelected = { calls += "delete" },
                                onClearSelection = { controller.clear() },
                                onCheckoutBranch = { calls += "checkout ${it.simpleName}" },
                                onDeleteBranch = {},
                                onPushRemoteBranch = {},
                                onPullRemoteBranch = {},
                                onRebaseRemoteBranch = {},
                                onMergeRemoteBranch = {},
                                onChooseWorktreesBase = {},
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

    private fun onClick(branch: Branch, click: BranchClick, shown: List<Branch>) {
        if (controller.onClick(branch.name, click) { shown.map { it.name } }) {
            logged = branch.name
            calls += "select ${branch.simpleName}"
        }
    }

    /** The names of the rows that are highlighted, top to bottom: the rows say it in their semantics. */
    private suspend fun highlighted(): List<String> {
        settle()

        return scene!!.semanticsOwners
            .flatMap { it.getAllSemanticsNodes(true) }
            .filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
            .sortedBy { it.boundsInWindow.top }
            .mapNotNull { node -> node.texts().firstOrNull { it in rowNames } }
    }

    private suspend fun click(name: String, ctrl: Boolean = false, shift: Boolean = false, menu: Boolean = false) {
        press(position(name, menu), ctrl, shift)
    }

    private suspend fun doubleClick(name: String, ctrl: Boolean = false, shift: Boolean = false) {
        val at = position(name, false)

        // A double-click's first press can't follow a press on another row too closely
        delay(350L)
        press(at, ctrl, shift, settle = false)
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

    private fun position(text: String, menu: Boolean): Offset {
        val node = if (menu) nodes().first { text in it.texts() } else row(text)

        return node.boundsInWindow.center.let { if (menu) it else Offset(250f, it.y) }
    }

    private fun row(name: String): SemanticsNode = nodes().first { it.texts() == listOf(name) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private suspend fun settle() {
        repeat(8) {
            scene!!.render(System.nanoTime())
            delay(20L)
        }
    }
}
