// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.cherrypick.BranchAhead
import dev.app.leaf.domain.cherrypick.BranchCherryPickItem
import dev.app.leaf.domain.cherrypick.BranchesAhead
import dev.app.leaf.domain.models.Branch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

private const val SEPARATOR = "---"

/** The branch menus' cherry-pick item (fork-only): its label, its place after Rebase, and what a click passes on. */
class CherryPickMenuTest {
    private val main = Branch("m".repeat(40), "refs/heads/main", isLocal = true)
    private val fix = Branch("a4880d17".padEnd(40, '0'), "refs/heads/fix", isLocal = true)
    private val originFix = Branch("b".repeat(40), "refs/remotes/origin/fix", isLocal = false)
    private val detached = Branch("d".repeat(40), "HEAD", isLocal = true)

    @Test
    fun `one commit is named by its hash, right after Rebase branch`() {
        val labels = labelsOf(menu(fix, BranchCherryPickItem.OneCommit(fix.hash)))

        assertEquals(
            listOf("Merge branch", "Rebase branch", "Cherry-pick commit a4880d1", SEPARATOR),
            labels.dropWhile { it != "Merge branch" }.take(4),
        )
    }

    @Test
    fun `several commits say how many, and a count git couldn't give says none`() {
        assertEquals(1, labelsOf(menu(fix, BranchCherryPickItem.Commits(3))).count { it == "Cherry-pick 3 commits…" })
        assertEquals(1, labelsOf(menu(fix, BranchCherryPickItem.Commits(null))).count { it == "Cherry-pick commits…" })
    }

    @Test
    fun `remote branches get it too`() {
        val labels = labelsOf(menu(originFix, BranchCherryPickItem.Commits(2)))

        assertEquals(1, labels.count { it == "Cherry-pick 2 commits…" }, labels.toString())
    }

    @Test
    fun `no item without one, on a detached HEAD, or on the current branch`() {
        val cherryPick = { labels: List<String> -> labels.filter { it.startsWith("Cherry-pick") } }

        assertEquals(emptyList<String>(), cherryPick(labelsOf(menu(fix, null))))
        assertEquals(
            emptyList<String>(),
            cherryPick(labelsOf(menu(fix, BranchCherryPickItem.Commits(2), currentBranch = detached))),
        )
        assertEquals(
            emptyList<String>(),
            cherryPick(labelsOf(menu(main, BranchCherryPickItem.Commits(2), isCurrentBranch = true))),
        )
    }

    @Test
    fun `a click passes the item on`() {
        val picked = mutableListOf<BranchCherryPickItem>()
        val item = BranchCherryPickItem.OneCommit(fix.hash)
        val items = menu(fix, item) { picked += it }

        items.filterIsInstance<ContextMenuElement.ContextTextEntry>()
            .single { composed { it.composableLabel() } == "Cherry-pick commit a4880d1" }
            .onClick()

        assertEquals(listOf<BranchCherryPickItem>(item), picked)
    }

    @Test
    fun `the menus' helper finds each branch's item from the counts`() {
        val calls = mutableListOf<String>()
        val cherryPick = BranchCherryPick(
            BranchesAhead(mapOf(fix.name to BranchAhead(1, tipIsMerge = false))),
            currentBranch = main,
        ) { branch, _ -> calls += branch.name }

        assertEquals(BranchCherryPickItem.OneCommit(fix.hash), cherryPick.itemFor(fix))
        assertEquals(null, cherryPick.itemFor(originFix))
        assertFalse(calls.isNotEmpty())
    }

    private fun menu(
        branch: Branch,
        item: BranchCherryPickItem?,
        currentBranch: Branch = main,
        isCurrentBranch: Boolean = false,
        onCherryPick: (BranchCherryPickItem) -> Unit = {},
    ): List<ContextMenuElement> = branchContextMenuItems(
        branch = branch,
        isCurrentBranch = isCurrentBranch,
        currentBranch = currentBranch,
        isLocal = branch.isLocal,
        onCheckoutBranch = {},
        onMergeBranch = {},
        onRebaseBranch = {},
        onDeleteBranch = {},
        onPushToRemoteBranch = {},
        onPullFromRemoteBranch = {},
        onChangeDefaultUpstreamBranch = {},
        onRenameBranch = {},
        onCopyBranchNameToClipboard = {},
        cherryPick = item,
        onCherryPick = onCherryPick,
    )

    /** The labels as the menu reads them, with [SEPARATOR] for a separator. */
    private fun labelsOf(items: List<ContextMenuElement>): List<String> = composed {
        items.map { item ->
            when (item) {
                is ContextMenuElement.ContextTextEntry -> item.composableLabel()
                else -> SEPARATOR
            }
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
