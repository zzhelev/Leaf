// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import dev.app.leaf.domain.models.AgentTool
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.BranchWorktreeUsers
import dev.app.leaf.domain.models.LogColumnsSettings
import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.WorktreeBranchUser
import dev.app.leaf.domain.models.WorktreeInfo
import dev.app.leaf.domain.models.WorktreeOrigin
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.repositoryopen.FolderShows
import dev.app.leaf.repositoryopen.LogBranchFilterState
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branch
import dev.app.leaf.ui.BranchWorktreesState
import dev.app.leaf.ui.components.SideMenuSubentry
import org.jetbrains.skia.Bitmap
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.LogBranchFilterMenu
import dev.app.leaf.ui.context_menu.logBranchFilterMenuItems
import dev.app.leaf.ui.context_menu.logBranchFolderMenuItems
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The log's branch filter (fork-only, upstream issue 207): what its control says, the list's rows and what picking them
 * does, the keys, and the branch menus' items. Rendered at 1 dp a pixel; PNGs in two styles and both themes when
 * `LEAF_RENDER_DIR` is set.
 */
class LogBranchFilterControlTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun local(name: String) = Branch(hash = "h-$name", name = "refs/heads/$name", isLocal = true)
    private fun remote(name: String) = Branch(hash = "h-o-$name", name = "refs/remotes/origin/$name", isLocal = false)

    private val main = local("main")
    private val agent = local("claude/parser-cache")
    private val watcher = local("claude/fix-watcher")
    private val spike = local("wip/spike")
    private val originMain = remote("main")
    private val originAgent = remote("claude/parser-cache")
    private val originBlame = remote("feat/blame")
    private val originHead = remote("HEAD")
    private val detached = Branch(hash = "h-head", name = "HEAD", isLocal = true)

    private fun state(filter: LogBranchFilter, current: Branch? = main) = LogBranchFilterState.of(
        filter = filter,
        localBranches = listOf(spike, agent, main, watcher, detached),
        remoteBranches = listOf(originBlame, originAgent, originMain, originHead),
        currentBranch = current,
    )

    private val worktrees = BranchWorktreesState(
        usersByBranch = mapOf(
            agent.name to BranchWorktreeUsers(
                listOf(
                    WorktreeBranchUser(
                        WorktreeInfo(
                            worktree("/repo/.claude/worktrees/parser-cache", agent),
                            status = null,
                            aheadBehindBase = null,
                            lastCommitTime = null,
                            origin = WorktreeOrigin.InToolFolder(AgentTool.CLAUDE_CODE, "<repo>/.claude/worktrees/*"),
                        ),
                        WorktreeBranchUse.CheckedOut,
                    )
                )
            )
        )
    )

    private fun worktree(path: String, branch: Branch) = Worktree(
        path = path,
        headSha = branch.hash,
        branch = branch.name,
        isMain = false,
        isCurrent = false,
        isDetached = false,
        isBare = false,
        locked = null,
        prunable = null,
    )

    // The rows and the label, worked out

    @Test
    fun `the list has the two ways, then local and remote branches grouped by folder, the checked out one first`() {
        val rows = logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), "")

        assertEquals(listOf(LogBranchFilterRow.AllBranches, LogBranchFilterRow.CurrentBranch), rows.modes)
        assertEquals(
            listOf(
                LogBranchFilterRow.BranchBox(main),
                LogBranchFilterRow.FolderBox("claude"),
                LogBranchFilterRow.BranchBox(watcher, isInFolder = true),
                LogBranchFilterRow.BranchBox(agent, isInFolder = true),
                LogBranchFilterRow.FolderBox("wip"),
                LogBranchFilterRow.BranchBox(spike, isInFolder = true),
            ),
            rows.local,
        )
        assertEquals(
            listOf(
                LogBranchFilterRow.FolderBox("claude"),
                LogBranchFilterRow.BranchBox(originAgent, isInFolder = true),
                LogBranchFilterRow.FolderBox("feat"),
                LogBranchFilterRow.BranchBox(originBlame, isInFolder = true),
                LogBranchFilterRow.BranchBox(originMain),
            ),
            rows.remote,
        )
        assertEquals(-1, logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), "zzz").firstBranch)
    }

    @Test
    fun `typing lists the folders and branches that have it, without the grouping`() {
        val rows = logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), "clau")

        assertEquals(
            listOf(
                LogBranchFilterRow.FolderBox("claude", 0, 4),
                LogBranchFilterRow.BranchBox(watcher, 0, 4),
                LogBranchFilterRow.BranchBox(agent, 0, 4),
            ),
            rows.local,
        )
        assertEquals(
            listOf(LogBranchFilterRow.FolderBox("claude", 0, 4), LogBranchFilterRow.BranchBox(originAgent, 7, 4)),
            rows.remote,
        )
    }

    @Test
    fun `what is typed filters the branches by name, a remote's by its remote's name too, and hides the two ways`() {
        val rows = logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), " Parser ")

        assertEquals(emptyList<LogBranchFilterRow>(), rows.modes)
        assertEquals(listOf(LogBranchFilterRow.BranchBox(agent, 7, 6)), rows.local)
        assertEquals(listOf(LogBranchFilterRow.BranchBox(originAgent, 14, 6)), rows.remote)
        assertEquals(0, rows.firstBranch)
        assertEquals(3, logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), "origin").remote.size)
    }

    @Test
    fun `a row's place in the list counts the headers before it`() {
        val rows = logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), "")

        // All, Current, divider... the divider isn't a row: All 0, Current 1, Local header 2, main 3
        assertEquals(0, rows.itemIndexOf(0))
        assertEquals(1, rows.itemIndexOf(1))
        assertEquals(3, rows.itemIndexOf(2))
        assertEquals(3 + rows.local.size + 1, rows.itemIndexOf(2 + rows.local.size))

        val typed = logBranchFilterRows(state(NO_LOG_BRANCH_FILTER), "origin")
        assertEquals(1, typed.itemIndexOf(0))
    }

    @Test
    fun `the control names what the log shows`() {
        assertEquals(LogBranchFilterLabel.AllBranches, logBranchFilterLabel(state(NO_LOG_BRANCH_FILTER)))
        assertEquals(LogBranchFilterLabel.CurrentBranch, logBranchFilterLabel(state(LogBranchFilter.Current)))
        assertEquals(
            LogBranchFilterLabel.AllBut("claude/parser-cache"),
            logBranchFilterLabel(state(LogBranchFilter.All(setOf(agent.name, originAgent.name)))),
        )
        assertEquals(
            LogBranchFilterLabel.AllButCount(2),
            logBranchFilterLabel(state(LogBranchFilter.All(setOf(agent.name, spike.name)))),
        )
        assertEquals(
            LogBranchFilterLabel.Names("claude/parser-cache", 0),
            logBranchFilterLabel(state(LogBranchFilter.Only(setOf(agent.name, originAgent.name)))),
        )
        assertEquals(
            LogBranchFilterLabel.Names("claude/parser-cache, main", 1),
            logBranchFilterLabel(state(LogBranchFilter.Only(setOf(agent.name, main.name, originBlame.name)))),
        )
    }

    @Test
    fun `the control names folders first, with a slash`() {
        assertEquals(
            LogBranchFilterLabel.AllBut("claude/"),
            logBranchFilterLabel(state(LogBranchFilter.All(hiddenFolders = setOf("claude")))),
        )
        assertEquals(
            LogBranchFilterLabel.AllBut("claude/", 1),
            logBranchFilterLabel(state(LogBranchFilter.All(setOf(spike.name), setOf("claude"), setOf(agent.name)))),
        )
        assertEquals(
            LogBranchFilterLabel.Names("claude/", 0),
            logBranchFilterLabel(state(LogBranchFilter.Only(emptySet(), setOf("claude")))),
        )
        assertEquals(
            LogBranchFilterLabel.Names("claude/, main", 0),
            logBranchFilterLabel(state(LogBranchFilter.Only(setOf(main.name), setOf("claude")))),
        )
    }

    @Test
    fun `a folder's box is on, off, or partly, and clicking it hides or shows it as a pattern`() {
        val hidden = LogBranchFilter.All(hiddenFolders = setOf("claude"))

        assertEquals(FolderShows.ALL, state(NO_LOG_BRANCH_FILTER).folderShows("claude"))
        assertEquals(FolderShows.NONE, state(hidden).folderShows("claude"))
        assertEquals(FolderShows.SOME, state(hidden.copy(shownInHiddenFolders = setOf(agent.name))).folderShows("claude"))

        assertEquals(hidden, state(NO_LOG_BRANCH_FILTER).picked(LogBranchFilterRow.FolderBox("claude")))
        assertEquals(NO_LOG_BRANCH_FILTER, state(hidden).picked(LogBranchFilterRow.FolderBox("claude")))
        assertEquals(
            hidden,
            state(hidden.copy(shownInHiddenFolders = setOf(agent.name))).picked(LogBranchFilterRow.FolderBox("claude")),
        )
    }

    @Test
    fun `a folder's menu hides it or shows it again, and shows it alone`() {
        fun choices(filter: LogBranchFilter): List<LogBranchFilter> {
            val chosen = mutableListOf<LogBranchFilter>()
            logBranchFolderMenuItems("claude", LogBranchFilterMenu(state(filter)) { chosen += it })
                .filterIsInstance<ContextMenuElement.ContextTextEntry>()
                .forEach { it.onClick() }

            return chosen
        }

        val only = LogBranchFilter.Only(emptySet(), setOf("claude"))

        assertEquals(listOf(LogBranchFilter.All(hiddenFolders = setOf("claude")), only), choices(NO_LOG_BRANCH_FILTER))
        assertEquals(listOf(NO_LOG_BRANCH_FILTER, only), choices(LogBranchFilter.All(hiddenFolders = setOf("claude"))))
        assertEquals(listOf<LogBranchFilter>(LogBranchFilter.Only(emptySet())), choices(only))
    }

    @Test
    fun `names that no branch has any more don't count`() {
        val gone = "refs/heads/gone"

        assertEquals(LogBranchFilterLabel.AllBranches, logBranchFilterLabel(state(LogBranchFilter.All(setOf(gone)))))
        assertFalse(state(LogBranchFilter.All(setOf(gone))).isOn)
        assertEquals(LogBranchFilterLabel.Names("main", 0), logBranchFilterLabel(state(LogBranchFilter.Only(setOf(gone)))))
        assertTrue(state(LogBranchFilter.Only(setOf(gone))).isOn)
    }

    @Test
    fun `a detached HEAD and origin's HEAD are no branches to choose, and a detached HEAD alone is named HEAD`() {
        val rows = logBranchFilterRows(state(NO_LOG_BRANCH_FILTER, current = detached), "")

        assertFalse(rows.all.any { it is LogBranchFilterRow.BranchBox && it.branch.simpleName == "HEAD" })
        assertEquals(
            LogBranchFilterLabel.Names("HEAD", 0),
            logBranchFilterLabel(state(LogBranchFilter.Only(emptySet()), current = detached)),
        )
    }

    // The menus

    private fun menuChoices(branch: Branch, filter: LogBranchFilter, current: Branch? = main): List<LogBranchFilter> {
        val chosen = mutableListOf<LogBranchFilter>()
        val items = logBranchFilterMenuItems(branch, LogBranchFilterMenu(state(filter, current)) { chosen += it })

        items.filterIsInstance<ContextMenuElement.ContextTextEntry>().forEach { it.onClick() }

        return chosen
    }

    @Test
    fun `a local branch's menu shows it alone or hides it, with the remote branches of its name`() {
        assertEquals(
            listOf(
                LogBranchFilter.Only(setOf(agent.name, originAgent.name)),
                LogBranchFilter.All(setOf(agent.name, originAgent.name)),
            ),
            menuChoices(agent, NO_LOG_BRANCH_FILTER),
        )
    }

    @Test
    fun `a hidden branch's menu shows it again, and a remote branch's acts on it alone`() {
        assertEquals(
            listOf(LogBranchFilter.Only(setOf(spike.name)), NO_LOG_BRANCH_FILTER),
            menuChoices(spike, LogBranchFilter.All(setOf(spike.name))),
        )
        assertEquals(
            listOf(LogBranchFilter.Only(setOf(originBlame.name)), LogBranchFilter.Only(setOf(agent.name, originBlame.name))),
            menuChoices(originBlame, LogBranchFilter.Only(setOf(agent.name))),
        )
    }

    @Test
    fun `the checked out branch has no hide, and no show only when that's the filter already`() {
        assertEquals(listOf<LogBranchFilter>(LogBranchFilter.Only(setOf(main.name, originMain.name))), menuChoices(main, NO_LOG_BRANCH_FILTER))
        assertEquals(emptyList<LogBranchFilter>(), menuChoices(main, LogBranchFilter.Only(setOf(main.name, originMain.name))))
    }

    @Test
    fun `the selection shows its branches alone, with the remote branches of their names`() {
        assertEquals(
            LogBranchFilter.Only(setOf(agent.name, originAgent.name, spike.name, originBlame.name)),
            state(NO_LOG_BRANCH_FILTER).showingOnly(listOf(agent.name, spike.name, originBlame.name)),
        )
    }

    // Rendered

    private fun render(width: Int, height: Int, content: @Composable () -> Unit) {
        scene?.close()
        scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { content() }
        settle()
    }

    private fun settle() {
        repeat(3) { scene!!.render(System.nanoTime()) }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()

        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }

        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

        return nodes
    }

    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private fun clickable(text: String): SemanticsNode = nodes().single { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null &&
            (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } ||
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == text })
    }

    private fun click(text: String) {
        checkNotNull(clickable(text).config[SemanticsActions.OnClick].action).invoke()
        settle()
    }

    private fun box(text: String): ToggleableState? {
        fun find(node: SemanticsNode): ToggleableState? =
            node.config.getOrNull(SemanticsProperties.ToggleableState) ?: node.children.firstNotNullOfOrNull(::find)

        return find(clickable(text))
    }

    private fun type(text: String) {
        val field = nodes().single { it.config.getOrNull(SemanticsProperties.EditableText) != null }
        checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
        settle()
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        settle()
    }

    private fun renderButton(filter: LogBranchFilter, onSet: (LogBranchFilter) -> Unit = {}) = render(600, 300) {
        AppTheme(Theme.Dark) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                LogBranchFilterButton(state(filter), worktrees, onSet)
            }
        }
    }

    @Test
    fun `off, the control says all branches and has no way to clear`() {
        renderButton(NO_LOG_BRANCH_FILTER)

        assertTrue("All branches" in texts(), texts().toString())
        assertFalse(nodes().any { "Show all branches" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
    }

    @Test
    fun `on, the control names what shows, and its x shows every branch again`() {
        val chosen = mutableListOf<LogBranchFilter>()
        renderButton(LogBranchFilter.Only(setOf(agent.name, originAgent.name))) { chosen += it }

        assertTrue("claude/parser-cache" in texts(), texts().toString())

        click("Show all branches")

        assertEquals(listOf(NO_LOG_BRANCH_FILTER), chosen)
    }

    @Test
    fun `a click on the control opens the list, and another closes it`() {
        renderButton(NO_LOG_BRANCH_FILTER)
        assertFalse("Filter branches" in texts())

        click("Branches in the log")
        assertTrue("Filter branches" in texts(), texts().toString())

        press(Key.Escape)
        assertFalse("Filter branches" in texts(), texts().toString())
    }

    private fun renderList(filter: LogBranchFilter, chosen: MutableList<LogBranchFilter>, closes: MutableList<Unit> = mutableListOf()) =
        render(360, 520) {
            AppTheme(Theme.Dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.surface)) {
                    LogBranchFilterList(state(filter), worktrees, onSet = { chosen += it }, onClose = { closes += Unit })
                }
            }
        }

    @Test
    fun `the boxes say what the log shows, and the checked out branch's is checked`() {
        renderList(LogBranchFilter.Only(setOf(agent.name)), mutableListOf())

        assertEquals(ToggleableState.On, box("main"))
        assertEquals(ToggleableState.On, box("claude/parser-cache"))
        assertEquals(ToggleableState.Off, box("wip/spike"))
        assertEquals(ToggleableState.Off, box("origin/claude/parser-cache"))
        assertEquals(ToggleableState.Off, box("All branches"))
        assertTrue("Claude" in texts(), "The agent's worktree tag: ${texts()}")
    }

    @Test
    fun `a hidden folder's box is off, partly with an exception, and its branches' boxes say which`() {
        renderList(LogBranchFilter.All(hiddenFolders = setOf("claude"), shownInHiddenFolders = setOf(agent.name)), mutableListOf())

        val folderBoxes = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "claude/" }
        }

        assertEquals(2, folderBoxes.size, "a folder row in Local and in Remote")
        assertEquals(ToggleableState.Indeterminate, folderBoxes.first().config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals(ToggleableState.On, box("claude/parser-cache"))
        assertEquals(ToggleableState.Off, box("claude/fix-watcher"))
        assertEquals(ToggleableState.Off, box("origin/claude/parser-cache"))
    }

    @Test
    fun `picking a row changes the filter, and the list stays open`() {
        val chosen = mutableListOf<LogBranchFilter>()
        val closes = mutableListOf<Unit>()
        renderList(LogBranchFilter.Only(setOf(agent.name)), chosen, closes)

        click("wip/spike")
        click("All branches")
        click("Current branch")
        click("main")

        assertEquals(
            listOf(
                LogBranchFilter.Only(setOf(agent.name, spike.name)),
                NO_LOG_BRANCH_FILTER,
                LogBranchFilter.Current,
                LogBranchFilter.Only(setOf(agent.name)),
            ),
            chosen,
        )
        assertEquals(emptyList<Unit>(), closes)
    }

    @Test
    fun `typing filters the list, the first match is highlighted and Enter toggles it, Esc closes`() {
        val chosen = mutableListOf<LogBranchFilter>()
        val closes = mutableListOf<Unit>()
        renderList(NO_LOG_BRANCH_FILTER, chosen, closes)

        type("spike")

        assertFalse("All branches" in texts())
        assertFalse("main" in texts())
        assertTrue("wip/spike" in texts())

        press(Key.Enter)
        assertEquals(listOf(LogBranchFilter.All(setOf(spike.name))), chosen)

        type("")
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.Enter)
        assertEquals(LogBranchFilter.Current, chosen.last())

        press(Key.Escape)
        assertEquals(1, closes.size)
    }

    /** The brightest pixel of a side panel row, and the colour at its left edge, where only the highlight is. */
    private fun sideRow(isDimmed: Boolean, isSelected: Boolean): Pair<Int, Int> {
        render(300, 36) {
            AppTheme(Theme.Dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    SideMenuSubentry(
                        text = "wip/spike",
                        iconResourcePath = Res.drawable.branch,
                        isSelected = isSelected,
                        isDimmed = isDimmed,
                        onClick = {},
                    )
                }
            }
        }

        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))
        var brightest = 0

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val color = bitmap.getColor(x, y)
                brightest = maxOf(brightest, (color shr 16 and 0xFF) + (color shr 8 and 0xFF) + (color and 0xFF))
            }
        }

        return brightest to bitmap.getColor(4, 10)
    }

    @Test
    fun `a branch that the log leaves out is greyed in the side panel, its highlight isn't`() {
        val (shown, shownEdge) = sideRow(isDimmed = false, isSelected = true)
        val (hidden, hiddenEdge) = sideRow(isDimmed = true, isSelected = true)

        assertTrue(hidden < shown * 0.6, "Greyed text: brightest $hidden against $shown")
        assertEquals(shownEdge, hiddenEdge, "The highlight is as strong")
    }

    @Test
    fun `renders in two styles and both themes`() {
        val dir = System.getenv("LEAF_RENDER_DIR") ?: return
        val styles = listOf(BuiltInStyles.TONAL, BuiltInStyles.CLASSIC, BuiltInStyles.SOLARIZED)

        for (style in styles) {
            for (theme in listOf(Theme.Dark, Theme.Light)) {
                writePng(dir, "header-off", style, theme, 900, 60) { header(NO_LOG_BRANCH_FILTER) }
                writePng(dir, "header-on", style, theme, 900, 60) {
                    header(LogBranchFilter.Only(setOf(agent.name, originAgent.name)))
                }
                writePng(dir, "header-allbut", style, theme, 900, 60) {
                    header(LogBranchFilter.All(setOf(watcher.name, spike.name)))
                }
                writePng(dir, "header-folder", style, theme, 900, 60) {
                    header(LogBranchFilter.All(hiddenFolders = setOf("claude")))
                }
                writePng(dir, "list-folder", style, theme, 340, 520) {
                    Box(Modifier.padding(10.dp)) {
                        LogBranchFilterList(
                            state(LogBranchFilter.All(hiddenFolders = setOf("claude"), shownInHiddenFolders = setOf(agent.name))),
                            worktrees, {}, {},
                        )
                    }
                }
                writePng(dir, "list", style, theme, 340, 470) {
                    Box(Modifier.padding(10.dp)) {
                        LogBranchFilterList(state(LogBranchFilter.Only(setOf(agent.name, originAgent.name))), worktrees, {}, {})
                    }
                }
            }
        }
    }

    @Composable
    private fun header(filter: LogBranchFilter) {
        val columns = LogColumnsSettings().fitting(600f, LOG_COLUMN_SPACING).shown

        Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            GraphHeader(
                graphWidth = 120.dp,
                columns = columns,
                columnsMenuItems = emptyList(),
                onGraphDrag = {},
                onGraphDragStopped = {},
                onColumnResize = { _, _ -> },
                onColumnResizeFinished = {},
                onShowSearch = {},
                branchFilterControl = { LogBranchFilterButton(state(filter), worktrees) {} },
            )
        }
    }

    private fun writePng(
        dir: String,
        name: String,
        style: StyleDefinition,
        theme: Theme,
        width: Int,
        height: Int,
        content: @Composable () -> Unit,
    ) {
        render(width, height) {
            AppTheme(theme, style = style) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) { content() }
            }
        }

        File(dir).mkdirs()
        File(dir, "filter-$name-${style.id}-${theme.name.lowercase()}.png")
            .writeBytes(scene!!.render(System.nanoTime()).encodeToData()!!.bytes)
    }
}
