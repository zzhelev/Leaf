// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The toolbar (docs/fork/window-chrome.md, step 3), rendered offscreen at the widths of a laptop and a desktop. */
class ToolbarTest {
    private val calls = mutableListOf<String>()
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `labels go as the window narrows, Branch and Stash first, then Pull and Push`() {
        assertEquals(ToolbarLabels(true, true, 248.dp), toolbarLabelsFor(1440.dp))
        assertEquals(ToolbarLabels(false, true, 248.dp), toolbarLabelsFor(1100.dp))
        assertEquals(ToolbarLabels(false, false, 248.dp), toolbarLabelsFor(950.dp))
        assertEquals(ToolbarLabels(false, false, 160.dp), toolbarLabelsFor(800.dp))
    }

    @Test
    fun `at 1440 every button has its label`() {
        render(width = 1440)

        assertTrue(texts().containsAll(listOf("Pull", "Push", "Branch", "Stash", "Pop", "Actions…")), texts().toString())
        assertFits(1440)
    }

    @Test
    fun `at 1100 Branch, Stash and Pop are icons, and everything fits`() {
        render(width = 1100)

        val texts = texts()
        assertTrue(texts.containsAll(listOf("Pull", "Push", "Actions…")), texts.toString())
        assertTrue(texts.none { it in listOf("Branch", "Stash", "Pop") }, texts.toString())
        assertFits(1100)
    }

    @Test
    fun `at 800 every group is icons, and everything fits`() {
        render(width = 800)

        val texts = texts()
        assertTrue(texts.none { it in listOf("Pull", "Push", "Branch", "Stash", "Pop") }, texts.toString())
        assertFits(800)
    }

    @Test
    fun `each button does what it says, and Stash does nothing without changes`() {
        render(width = 1440, canStash = false)

        for (label in listOf("Pull", "Push", "Create a new branch", "Stash", "Pop", "Actions…", "Terminal", "Settings")) {
            click(label)
        }

        assertEquals(listOf("pull DEFAULT", "push", "branch", "pop", "actions", "terminal", "settings"), calls)
    }

    @Test
    fun `hovering a button shows its tooltip without moving the buttons after it`() {
        render(width = 1440)

        // A tooltip's popup takes a place in the row it's in, which a row's spacing used to give a gap of its own
        for (hovered in listOf("Create a new branch", "Actions…", "Terminal")) {
            val before = listOf("Stash", "Pop", "Terminal", "Settings").associateWith { boundsOf(it).left }

            hover(hovered)

            assertEquals(before, before.keys.associateWith { boundsOf(it).left }, "hovering $hovered")
            hover(null)
        }
    }

    @Test
    fun `without a remote, Pull and Push do nothing`() {
        render(width = 1440, hasRemotes = false)

        click("Pull")
        click("Push")

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `renders the toolbar in five styles at 1440 and 900, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (style in listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED, BuiltInStyles.TEAL, BuiltInStyles.LEAF)) {
            for (theme in listOf(Theme.Light, Theme.Dark)) {
                for (width in listOf(1440, 900)) {
                    render(width = width, theme = theme, style = style, density = 2f)
                    val image = scene!!.render(System.nanoTime())
                    File(folder, "toolbar-${style.id}-${theme.name.lowercase()}-$width.png")
                        .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    scene!!.close()
                }
            }
        }
        scene = null
    }

    /** The left group ends before the right one starts, and Settings ends inside the toolbar's padding. */
    private fun assertFits(width: Int) {
        val stashGroupEnd = boundsOf("Pop").right
        val actionsStart = boundsOf("Actions…").left
        val settingsEnd = boundsOf("Settings").right

        assertTrue(stashGroupEnd < actionsStart, "Pop ends at $stashGroupEnd, Actions starts at $actionsStart")
        assertTrue(settingsEnd <= width - 12f + 0.5f, "Settings ends at $settingsEnd in $width")
        assertTrue(boundsOf("Repository repo, main. Switch worktree or repository").width >= 100f, "the switcher keeps room")
    }

    private fun render(
        width: Int,
        canStash: Boolean = true,
        hasRemotes: Boolean = true,
        theme: Theme = Theme.Light,
        style: StyleDefinition = BuiltInStyles.TONAL,
        density: Float = 1f,
    ) {
        scene = ImageComposeScene(
            width = (width * density).toInt(),
            height = (60 * density).toInt(),
            density = Density(density),
        ) {
            AppTheme(theme, style = style) {
                Column(Modifier.background(MaterialTheme.colors.background).fillMaxSize()) {
                    ToolbarRow(
                        state = ToolbarState(
                            switcher = SwitcherState(
                                repositoryName = "repo",
                                worktreeName = null,
                                branchLabel = "main",
                                worktrees = null,
                                recentRepositories = emptyList(),
                            ),
                            hasRemotes = hasRemotes,
                            isPullWithRebaseDefault = false,
                            canStash = canStash,
                            canPopStash = true,
                        ),
                        showSwitcher = false,
                        onShowSwitcherChange = {},
                        actions = ToolbarActions(
                            onPull = { calls += "pull $it" },
                            onFetchAll = { calls += "fetch" },
                            onPush = { calls += if (it) "push tags" else "push" },
                            onForcePush = { calls += "force push" },
                            onCreateBranch = { calls += "branch" },
                            onStash = { calls += "stash" },
                            onStashWithMessage = { calls += "stash with message" },
                            onPopStash = { calls += "pop" },
                            onQuickActions = { calls += "actions" },
                            onTerminal = { calls += "terminal" },
                            onSettings = { calls += "settings" },
                            switcher = SwitcherActions({}, {}, {}, {}),
                        ),
                    )
                }
            }
        }.also { it.render() }
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun boundsOf(description: String): Rect = nodes()
        .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
        .boundsInWindow

    /** Moves the pointer to the middle of [description], or out of the toolbar for null. */
    private fun hover(description: String?) {
        val scene = scene!!
        val position = description?.let { boundsOf(it).center.let { center -> Offset(center.x, center.y) } }
            ?: Offset(5f, 55f)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.render()
        scene.render()
    }

    private fun click(description: String) {
        val scene = scene!!
        val position = boundsOf(description).center.let { Offset(it.x, it.y) }

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        scene.render()
    }
}
