// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.LineRange
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.RewriteDialogKind
import dev.app.leaf.domain.rewrite.RewriteRequest
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.RewriteFixtures
import dev.app.leaf.ui.RewriteFixtures.BRANCH
import dev.app.leaf.ui.RewriteFixtures.MAIN
import dev.app.leaf.ui.RewriteFixtures.add
import dev.app.leaf.ui.RewriteFixtures.cache
import dev.app.leaf.ui.RewriteFixtures.onMain
import dev.app.leaf.ui.RewriteFixtures.wip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The items that squash and reword a branch's commits, in a commit row's, a selection's and a branch's menus
 * (fork-only, step A3 of `docs/fork/squash-and-rebase.md`): their labels, why one can't be used, and what a click opens.
 */
class RewriteMenusTest {
    private val opened = mutableListOf<RewriteRequest>()
    private val fixedUp = mutableListOf<LineRange>()

    private fun rewrite(busy: BranchOperation? = null, currentBranch: String? = BRANCH) = CommitRewrite(
        lines = RewriteFixtures.lines(),
        currentBranch = currentBranch,
        busy = { busy },
        onOpen = { opened += it },
        onFixup = { fixedUp += it },
    )

    private data class Item(val label: String, val hint: String?, val isEnabled: Boolean)

    private fun items(elements: List<ContextMenuElement>): List<Item> = composed {
        elements.filterIsInstance<ContextMenuElement.ContextTextEntry>().map {
            Item(it.composableLabel(), it.hint?.invoke(), it.isEnabled)
        }
    }

    private fun item(element: ContextMenuElement?): Item? = element?.let { items(listOf(it)).single() }

    @Test
    fun `a commit with a parent on the branch offers all three`() {
        assertEquals(
            listOf(
                Item("Edit message…", null, true),
                Item("Squash into parent…", null, true),
                Item("Fixup into parent", null, true),
            ),
            items(rewrite().commitItems(cache.hash)),
        )
    }

    @Test
    fun `the branch's first commit can be reworded, not joined with its parent on main`() {
        assertEquals(
            listOf(
                Item("Edit message…", null, true),
                Item("Squash into parent…", "Its parent is on main", false),
                Item("Fixup into parent", "Its parent is on main", false),
            ),
            items(rewrite().commitItems(add.hash)),
        )
    }

    @Test
    fun `the items are there but disabled on main, on no branch and while the branch is busy`() {
        for ((commit, busy, why) in listOf(
            Triple(onMain.hash, null, "On main"),
            Triple("f".repeat(40), null, "Not on a local branch"),
            Triple(cache.hash, BranchOperation.REBASE, "In a rebase"),
            Triple(cache.hash, BranchOperation.BISECT, "In a bisect"),
        )) {
            val shown = items(rewrite(busy).commitItems(commit))

            assertEquals(3, shown.size, why)
            assertTrue(shown.all { !it.isEnabled && it.hint == why }, shown.toString())
        }
    }

    @Test
    fun `a fixup! commit that names another commit than its parent can be squashed, not fixed up`() {
        fun itemsOn(named: Commit): List<Item> {
            val fix = RewriteFixtures.commit("f1x0000", "fixup! ${named.shortMessage}", parent = cache.hash)
            val line = RewriteFixtures.line().let { it.copy(commits = listOf(fix) + it.commits.map { c -> if (c == add) named else c }) }
            val lines = BranchLines(base = MAIN, lines = mapOf(BRANCH to line), baseCommits = setOf(onMain.hash))

            return items(CommitRewrite(lines, BRANCH, { null }, { opened += it }, { fixedUp += it }).commitItems(fix.hash))
        }

        assertEquals(
            listOf(
                Item("Edit message…", null, true),
                Item("Squash into parent…", null, true),
                Item("Fixup into parent", "Names “Add the parser”", false),
            ),
            itemsOn(add),
        )
        assertEquals(
            Item("Fixup into parent", "Names “Add the parser for the…”", false),
            itemsOn(add.copy(message = "Add the parser for the release notes")).last(),
        )
    }

    @Test
    fun `squash into parent opens the dialog with the commit and its parent, fixup runs at once`() {
        val items = rewrite().commitItems(cache.hash).filterIsInstance<ContextMenuElement.ContextTextEntry>()

        items[1].onClick()
        items[2].onClick()

        assertEquals(RewriteDialogKind.SQUASH, opened.single().kind)
        assertEquals(listOf(wip, cache), opened.single().range.commits)
        assertEquals(listOf(wip, cache), fixedUp.single().commits)
    }

    @Test
    fun `edit message opens the reword of that commit`() {
        rewrite().commitItems(wip.hash).filterIsInstance<ContextMenuElement.ContextTextEntry>().first().onClick()

        assertEquals(RewriteDialogKind.REWORD, opened.single().kind)
        assertEquals(listOf(wip), opened.single().range.commits)
    }

    @Test
    fun `a selection counts its commits, and says why it can't be squashed`() {
        assertEquals(Item("Squash 2 commits…", null, true), item(rewrite().rangeItem(listOf(cache.hash, wip.hash))))
        assertEquals(
            Item("Squash 2 commits…", "Not one after another", false),
            item(rewrite().rangeItem(listOf(cache.hash, add.hash))),
        )
        assertEquals(
            Item("Squash 2 commits…", "Includes commits on main", false),
            item(rewrite().rangeItem(listOf(add.hash, onMain.hash))),
        )
        assertEquals(
            Item("Squash 2 commits…", "Not on one branch", false),
            item(rewrite().rangeItem(listOf(add.hash, "f".repeat(40)))),
        )
    }

    @Test
    fun `a selection's squash opens the dialog with its commits oldest first`() {
        (rewrite().rangeItem(listOf(cache.hash, wip.hash, add.hash)) as ContextMenuElement.ContextTextEntry).onClick()

        assertEquals(RewriteDialogKind.SQUASH, opened.single().kind)
        assertEquals(listOf(add, wip, cache), opened.single().range.commits)
    }

    @Test
    fun `a branch with commits of its own can be squashed whole, the base branch has no item`() {
        assertEquals(Item("Squash branch into one commit…", null, true), item(rewrite().branchItem(BRANCH)))
        assertEquals(
            Item("Squash branch into one commit…", "In a merge", false),
            item(rewrite(BranchOperation.MERGE).branchItem(BRANCH)),
        )
        assertNull(rewrite().branchItem(MAIN))
        assertNull(rewrite().branchItem("refs/heads/unknown"))

        (rewrite().branchItem(BRANCH) as ContextMenuElement.ContextTextEntry).onClick()

        assertEquals(RewriteDialogKind.SQUASH_BRANCH, opened.single().kind)
        assertEquals(listOf(add, wip, cache), opened.single().range.commits)
    }

    // The commit editor's items (docs/fork/commit-editor.md, B5)

    private val editors = mutableListOf<RebasePlan>()
    private val drops = mutableListOf<RebasePlan>()

    private fun withEditor(busy: BranchOperation? = null) = CommitRewrite(
        lines = RewriteFixtures.lines(pushed = setOf(add.hash)),
        currentBranch = BRANCH,
        busy = { busy },
        onOpen = { opened += it },
        onFixup = { fixedUp += it },
        onEditor = { editors += it },
        onDrop = { drops += it },
    )

    @Test
    fun `with the editor, a commit row adds Drop and, after a separator, the editor from that commit`() {
        val elements = withEditor().commitItems(wip.hash)

        assertEquals(
            listOf(
                Item("Edit message…", null, true),
                Item("Squash into parent…", null, true),
                Item("Fixup into parent", null, true),
                Item("Drop commit…", null, true),
                Item("Interactive rebase from here…", null, true),
            ),
            items(elements),
        )
        assertTrue(elements[4] is ContextMenuElement.ContextSeparator, elements.toString())

        val entries = elements.filterIsInstance<ContextMenuElement.ContextTextEntry>()
        entries[3].onClick()
        entries[4].onClick()

        // Drop's plan is from the commit, with it dropped; the editor's from the commit, that commit included
        assertEquals(listOf(wip, cache), drops.single().original)
        assertEquals(PlanAction.DROP, drops.single().rows.first().action)
        assertEquals(listOf(wip, cache), editors.single().original)
        assertTrue(!editors.single().isChanged)
    }

    @Test
    fun `the editor's items say why they can't be used, as part A's do`() {
        for ((commit, busy, why) in listOf(
            Triple(onMain.hash, null, "On main"),
            Triple(cache.hash, BranchOperation.REBASE, "In a rebase"),
        )) {
            val shown = items(withEditor(busy).commitItems(commit))

            assertEquals(5, shown.size, why)
            assertTrue(shown.all { !it.isEnabled && it.hint == why }, shown.toString())
        }
    }

    @Test
    fun `a selection and a branch open the editor with their commits, from the oldest`() {
        assertEquals(
            Item("Interactive rebase of these 2 commits…", null, true),
            item(withEditor().rangeEditorItem(listOf(cache.hash, wip.hash))),
        )
        assertEquals(Item("Interactive rebase…", null, true), item(withEditor().branchEditorItem(BRANCH)))
        assertEquals(
            Item("Interactive rebase…", "In a merge", false),
            item(withEditor(BranchOperation.MERGE).branchEditorItem(BRANCH)),
        )
        assertNull(withEditor().branchEditorItem(MAIN))
        assertNull(rewrite().branchEditorItem(BRANCH))
        assertNull(rewrite().rangeEditorItem(listOf(cache.hash)))

        (withEditor().rangeEditorItem(listOf(cache.hash, wip.hash)) as ContextMenuElement.ContextTextEntry).onClick()
        (withEditor().branchEditorItem(BRANCH) as ContextMenuElement.ContextTextEntry).onClick()

        assertEquals(listOf(listOf(wip, cache), listOf(add, wip, cache)), editors.map { it.original })
    }

    @Test
    fun `a disabled entry is faded and takes no click`(): Unit = runBlocking(Dispatchers.Swing) {
        var clicks = 0
        var dismissed = 0
        val entry = ContextMenuElement.ContextTextEntry(
            composableLabel = { "Fixup into parent" },
            onClick = { clicks++ },
            hint = { "On main" },
            isEnabled = false,
        )
        val scene = ImageComposeScene(width = 300, height = 60, density = Density(1f)) {
            AppTheme {
                TextEntry(showIcons = true, contextTextEntry = entry, onDismissRequest = { dismissed++ })
            }
        }

        try {
            scene.render()
            val row = scene.semanticsOwners.flatMap { owner ->
                buildList {
                    fun visit(node: androidx.compose.ui.semantics.SemanticsNode) {
                        add(node)
                        node.children.forEach(::visit)
                    }
                    visit(owner.rootSemanticsNode)
                }
            }.first { it.config.getOrNull(SemanticsProperties.Disabled) != null }

            val center = row.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Move, center)
            scene.sendPointerEvent(
                PointerEventType.Press,
                center,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(PointerEventType.Release, center, button = PointerButton.Primary)
            scene.render()

            assertEquals(0, clicks)
            assertEquals(0, dismissed)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `an enabled entry still clicks`(): Unit = runBlocking(Dispatchers.Swing) {
        var clicks = 0
        var dismissed = 0
        val entry = ContextMenuElement.ContextTextEntry(composableLabel = { "Edit message…" }, onClick = { clicks++ })
        val scene = ImageComposeScene(width = 300, height = 60, density = Density(1f)) {
            AppTheme {
                TextEntry(showIcons = true, contextTextEntry = entry, onDismissRequest = { dismissed++ })
            }
        }

        try {
            scene.render()
            val at = Offset(100f, 15f)
            scene.sendPointerEvent(PointerEventType.Move, at)
            scene.sendPointerEvent(
                PointerEventType.Press,
                at,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
            scene.render()

            assertEquals(1, clicks)
            assertEquals(1, dismissed)
        } finally {
            scene.close()
        }
    }

    private fun <T> composed(read: @Composable () -> T): T {
        var value: T? = null
        val scene = ImageComposeScene(width = 10, height = 10) {
            value = read()
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
