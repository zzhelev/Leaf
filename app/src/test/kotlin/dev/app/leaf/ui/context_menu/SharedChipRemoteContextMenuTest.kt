// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.WorktreeBaseBranch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"

/** The items of the remote branches that share a local branch's chip in the log (fork-only). */
class SharedChipRemoteContextMenuTest {
    private val main = Branch(HASH, "refs/heads/main", true)
    private val originMain = Branch(HASH, "refs/remotes/origin/main", false)
    private val upstreamMain = Branch(HASH, "refs/remotes/upstream/main", false)

    @Test
    fun `names the remote branch, and leaves out what the local branch's items already do`() {
        assertEquals(
            listOf(
                "Push \"main\" to \"origin/main\"",
                "Pull \"origin/main\" to \"main\"",
                "Delete remote branch \"origin/main\"",
                "Copy branch name \"origin/main\"",
            ),
            labels(listOf(originMain), currentBranch = main),
        )
    }

    @Test
    fun `offers no push or pull with a detached HEAD`() {
        assertEquals(
            listOf("Delete remote branch \"origin/main\"", "Copy branch name \"origin/main\""),
            labels(listOf(originMain), currentBranch = Branch(HASH, "HEAD", true)),
        )
    }

    @Test
    fun `has a part for each remote branch, apart from the others`() {
        assertEquals(
            listOf(
                "Delete remote branch \"origin/main\"",
                "Copy branch name \"origin/main\"",
                "-",
                "Delete remote branch \"upstream/main\"",
                "Copy branch name \"upstream/main\"",
            ),
            labels(listOf(originMain, upstreamMain), currentBranch = null),
        )
    }

    @Test
    fun `compares the worktrees to the remote branch, or back to the automatic one when it's the chosen one`() {
        val automatic = WorktreeBaseBranch(automatic = main.name)
        val chosen = WorktreeBaseBranch(automatic = main.name, chosen = originMain.name, chosenExists = true)

        assertEquals(
            "Compare worktrees to \"origin/main\"",
            labels(listOf(originMain), currentBranch = null, worktreesBase = automatic)[1],
        )
        assertEquals(
            "Compare worktrees automatically",
            labels(listOf(originMain), currentBranch = null, worktreesBase = chosen)[1],
        )
        assertEquals(listOf<String?>(originMain.name), chosenBases(automatic))
        assertEquals(listOf<String?>(null), chosenBases(chosen))
    }

    @Test
    fun `acts on the remote branch`() {
        val acted = mutableListOf<String>()

        items(
            remotes = listOf(originMain),
            currentBranch = main,
            onPush = { acted += "push ${it.name}" },
            onPull = { acted += "pull ${it.name}" },
            onDelete = { acted += "delete ${it.name}" },
            onCopy = { acted += "copy ${it.name}" },
        )
            .filterIsInstance<ContextMenuElement.ContextTextEntry>()
            .forEach { it.onClick() }

        assertEquals(
            listOf(
                "push refs/remotes/origin/main",
                "pull refs/remotes/origin/main",
                "delete refs/remotes/origin/main",
                "copy refs/remotes/origin/main",
            ),
            acted,
        )
    }

    /** The labels, with "-" for a separator, read in a composition as the menu reads them. */
    private fun labels(
        remotes: List<Branch>,
        currentBranch: Branch?,
        worktreesBase: WorktreeBaseBranch? = null,
    ): List<String> {
        val items = items(remotes, currentBranch, worktreesBase)

        var labels = emptyList<String>()
        val scene = ImageComposeScene(width = 10, height = 10) {
            labels = items.map { item ->
                when (item) {
                    is ContextMenuElement.ContextTextEntry -> item.composableLabel()
                    ContextMenuElement.ContextSeparator -> "-"
                }
            }
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        return labels
    }

    private fun chosenBases(worktreesBase: WorktreeBaseBranch): List<String?> {
        val chosen = mutableListOf<String?>()

        items(listOf(originMain), currentBranch = null, worktreesBase, onChooseBase = { chosen += it })
            .filterIsInstance<ContextMenuElement.ContextTextEntry>()
            .forEach { it.onClick() }

        return chosen
    }

    private fun items(
        remotes: List<Branch>,
        currentBranch: Branch?,
        worktreesBase: WorktreeBaseBranch? = null,
        onPush: (Branch) -> Unit = {},
        onPull: (Branch) -> Unit = {},
        onDelete: (Branch) -> Unit = {},
        onCopy: (Branch) -> Unit = {},
        onChooseBase: (String?) -> Unit = {},
    ) = sharedChipRemoteItems(
        remotes = remotes,
        currentBranch = currentBranch,
        onPushToRemoteBranch = onPush,
        onPullFromRemoteBranch = onPull,
        onDeleteRemoteBranch = onDelete,
        onCopyBranchNameToClipboard = onCopy,
        worktreesBase = worktreesBase,
        onChooseWorktreesBase = onChooseBase,
    )
}
