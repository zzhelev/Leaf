// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.toolbar

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.ui.TextSize
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.worktrees.WorktreeHeadLabel
import dev.app.leaf.domain.worktrees.WorktreeRow
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.textGrowth
import dev.app.leaf.ui.WorktreePickerState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/**
 * The toolbar's switcher (docs/fork/window-chrome.md, D2), rendered offscreen and clicked like a user would. It took
 * the worktree picker's place, and these began as that picker's tests.
 */
class RepositorySwitcherTest {
    private val main = row(worktree("/repo", "refs/heads/main", isMain = true), "repo", "main")
    private val current = row(
        worktree("/work/fix-auth", "refs/heads/feature/auth", isCurrent = true),
        "fix-auth",
        "feature/auth",
    )
    private val agent = row(
        worktree("/repo/.claude/worktrees/busy-jang", "refs/heads/claude/busy-jang"),
        "busy-jang",
        "claude/busy-jang",
    )
    private val missing = row(
        worktree("/work/old-spike", "refs/heads/spike", prunable = "gitdir file points to non-existent location"),
        "old-spike",
        "spike",
    )

    private val switchedTo = mutableListOf<Worktree>()
    private val opened = mutableListOf<String>()
    private var newWorktrees = 0
    private var pickerOpened = 0
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `shows the repository, the linked worktree and what it has checked out`() {
        render(worktrees(main, current, agent, missing), worktreeName = "fix-auth", branchLabel = "feature/auth")

        assertEquals(listOf("repo · fix-auth", "/", "feature/auth"), texts())
    }

    @Test
    fun `lists every worktree, and switches to the one picked`() {
        render(worktrees(main, current, agent, missing), worktreeName = "fix-auth", branchLabel = "feature/auth")

        click("repo · fix-auth")
        assertTrue(texts().containsAll(listOf("Worktrees", "repo", "busy-jang", "old-spike")), texts().toString())

        click("busy-jang")

        assertEquals(listOf(agent.info.worktree), switchedTo)
        assertEquals(listOf("repo · fix-auth", "/", "feature/auth"), texts(), "the popup closed")
    }

    @Test
    fun `doesn't switch to a worktree whose folder is missing`() {
        render(worktrees(main, current, agent, missing), worktreeName = "fix-auth", branchLabel = "feature/auth")

        click("repo · fix-auth")
        click("old-spike")

        assertEquals(emptyList<Worktree>(), switchedTo)
    }

    @Test
    fun `the worktrees end with a row that opens the new worktree dialog, and closes the popup`() {
        render(worktrees(main, current, agent), worktreeName = "fix-auth", branchLabel = "feature/auth")

        click("repo · fix-auth")
        click("New worktree…")

        assertEquals(1, newWorktrees)
        assertEquals(emptyList<Worktree>(), switchedTo)
        assertFalse("New worktree…" in texts(), "the popup closed")
    }

    @Test
    fun `a repository with one worktree lists none, and has Open and the recent repositories`() {
        render(worktrees = null, worktreeName = null, branchLabel = "main")

        click("repo")

        val texts = texts()
        assertFalse("Worktrees" in texts, texts.toString())
        assertTrue(texts.containsAll(listOf("Open a repository…", "Recent repositories", "orbit-api", "dotfiles")), texts.toString())
    }

    @Test
    fun `at a bigger text size the switcher and its Open row are taller by a line`() {
        val growth = textGrowth(TextSize.scaleOf(20)).value
        render(worktrees = null, worktreeName = null, branchLabel = "main", textSize = 20)

        assertEquals(32f + growth, clickableHolding("repo").height, 0.5f)

        click("repo")

        assertEquals(32f + growth, clickableHolding("Open a repository…").height, 0.5f)
    }

    @Test
    fun `Open a repository opens the folder picker, and a recent repository opens it, closing the popup`() {
        render(worktrees = null, worktreeName = null, branchLabel = "main")

        click("repo")
        click("Open a repository…")

        assertEquals(1, pickerOpened)
        assertFalse("Recent repositories" in texts())

        click("repo")
        click("dotfiles")

        assertEquals(listOf("/work/dotfiles"), opened)
        assertFalse("Recent repositories" in texts())
    }

    @Test
    fun `the label is the worktree list's words, else the branch, else a detached HEAD's commit`() {
        val branch = Branch(hash = HASH, name = "refs/heads/feature/auth", isLocal = true)
        val detached = Branch(hash = HASH, name = "HEAD", isLocal = true)
        var label = ""

        fun labelOf(branch: Branch?, worktrees: WorktreePickerState?): String {
            val labelScene = ImageComposeScene(width = 200, height = 40) {
                AppTheme {
                    label = switcherBranchLabel(branch, worktrees)
                    Text(label)
                }
            }
            labelScene.render()
            labelScene.close()

            return label
        }

        assertEquals("feature/auth", labelOf(branch, null))
        assertEquals("detached at 0123456", labelOf(detached, null))
        assertEquals("", labelOf(null, null))
        val rebasing = current.copy(head = WorktreeHeadLabel.Rebasing("feature/auth"))
        assertEquals(labelOf(detached, worktrees(main, rebasing)), "rebasing feature/auth")
    }

    @Test
    fun `renders the open switcher in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            render(
                worktrees(main, current, agent, missing),
                worktreeName = "fix-auth",
                branchLabel = "feature/auth",
                theme = theme,
                density = 2f,
                isOpen = true,
            )
            val image = scene!!.render(System.nanoTime())
            File(folder, "switcher-${theme.name.lowercase()}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene!!.close()
        }
        scene = null
    }

    private fun worktrees(vararg rows: WorktreeRow) = WorktreePickerState(rows.toList(), baseBranch = "refs/heads/main")

    private fun render(
        worktrees: WorktreePickerState?,
        worktreeName: String?,
        branchLabel: String,
        theme: Theme = Theme.Dark,
        density: Float = 1f,
        isOpen: Boolean = false,
        textSize: Int = TextSize.DEFAULT,
    ) {
        var isOpen by mutableStateOf(isOpen)

        scene = ImageComposeScene(
            width = (900 * density).toInt(),
            height = (800 * density).toInt(),
            density = Density(density, TextSize.scaleOf(textSize)),
        ) {
            AppTheme(theme) {
                RepositorySwitcher(
                    state = SwitcherState(
                        repositoryName = "repo",
                        worktreeName = worktreeName,
                        branchLabel = branchLabel,
                        worktrees = worktrees,
                        recentRepositories = listOf("/work/orbit-api", "/work/dotfiles"),
                    ),
                    isOpen = isOpen,
                    onOpenChange = { isOpen = it },
                    actions = SwitcherActions(
                        onSwitchToWorktree = { switchedTo += it },
                        onNewWorktree = { newWorktrees++ },
                        onOpenRepository = { opened += it },
                        onOpenRepositoryFromPicker = { pickerOpened++ },
                    ),
                )
            }
        }.also { it.render() }
    }

    /** Clicks the middle of the first text that reads [text], then lets the scene settle. */
    private fun click(text: String) {
        val scene = scene!!
        // Past the guard that keeps a click right after the popup closed from opening it again
        Thread.sleep(350)
        val bounds = nodes().first { it.texts().contains(text) }.boundsInWindow
        val position = Offset(bounds.center.x, bounds.center.y)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        scene.render()
        scene.render()
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    /** The bounds of the clickable element that holds the text [text]. */
    private fun clickableHolding(text: String) = generateSequence(nodes().first { it.texts().contains(text) }) { it.parent }
        .first { it.config.contains(SemanticsActions.OnClick) }
        .boundsInWindow

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun row(worktree: Worktree, name: String, branch: String) = WorktreeRow(
        info = WorktreeInfo(worktree, status = null, aheadBehindBase = null, lastCommitTime = null),
        name = name,
        head = WorktreeHeadLabel.OnBranch(branch),
        ageLabel = null,
    )

    private fun worktree(
        path: String,
        branch: String,
        isMain: Boolean = false,
        isCurrent: Boolean = false,
        prunable: String? = null,
    ) = Worktree(
        path = path,
        headSha = HASH,
        branch = branch,
        isMain = isMain,
        isCurrent = isCurrent,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = prunable,
    )
}
