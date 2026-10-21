// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val HASH = "0123456789abcdef0123456789abcdef01234567"
private const val SEPARATOR = "---"

/** The menus' items that start a comparison (fork-only, `docs/fork/compare.md`, 3.2). */
class CompareMenusTest {
    private val main = Branch(HASH, "refs/heads/main", true)
    private val feature = Branch(HASH, "refs/heads/feature", true)
    private val detached = Branch(HASH, "HEAD", true)
    private val originFeature = Branch(HASH, "refs/remotes/origin/feature", false)
    private val tag = Tag(HASH, HASH, "refs/tags/v1.0")

    @Test
    fun `a commit's menu has both items in a group after Copy commit hash, with the click that does the first`() {
        val calls = mutableListOf<String>()
        val items = logMenu(
            CompareMenuItems(
                selectedName = "5d5537f",
                onCompareWithSelected = { calls += "selected" },
                currentBranch = main,
                onCompareWithCurrent = { calls += "current" },
            )
        )

        assertEquals(
            listOf(
                "Copy commit hash",
                SEPARATOR,
                "Compare with selected (5d5537f)",
                "Compare with main (current)",
                SEPARATOR,
                "Rebase interactive",
            ),
            labelsOf(items).dropWhile { it != "Copy commit hash" }.take(6),
        )

        val hints = hintsOf(items)
        assertEquals(compareClick(), hints["Compare with selected (5d5537f)"])
        assertEquals(null, hints["Compare with main (current)"])

        items.textEntries().single { it.labelIn() == "Compare with selected (5d5537f)" }.onClick()
        items.textEntries().single { it.labelIn() == "Compare with main (current)" }.onClick()
        assertEquals(listOf("selected", "current"), calls)
    }

    @Test
    fun `a commit's menu leaves out what doesn't apply, and the group with nothing in it`() {
        val withoutCompare = labelsOf(logMenu(compare = null))

        assertEquals(
            withoutCompare,
            labelsOf(logMenu(CompareMenuItems(null, {}, null, {}))),
            "nothing selected, and the current branch's tip",
        )

        val onlyCurrent = labelsOf(logMenu(CompareMenuItems(null, {}, main, {})))
        assertEquals(listOf("Compare with main (current)"), onlyCurrent - withoutCompare.toSet())

        val onlySelected = labelsOf(logMenu(CompareMenuItems("feature", {}, null, {})))
        assertEquals(listOf("Compare with selected (feature)"), onlySelected - withoutCompare.toSet())
    }

    @Test
    fun `a detached HEAD is named as such`() {
        val labels = labelsOf(logMenu(CompareMenuItems(null, {}, detached, {})))

        assertEquals(1, labels.count { it == "Compare with HEAD (detached)" })
    }

    @Test
    fun `the click is Meta on macOS and Ctrl elsewhere`() {
        assertEquals(true, isCompareClick(isMetaPressed = true, isCtrlPressed = false, os = OS.MAC))
        assertEquals(false, isCompareClick(isMetaPressed = false, isCtrlPressed = true, os = OS.MAC))

        for (os in listOf(OS.LINUX, OS.WINDOWS, OS.UNKNOWN)) {
            assertEquals(true, isCompareClick(isMetaPressed = false, isCtrlPressed = true, os = os), "$os")
            assertEquals(false, isCompareClick(isMetaPressed = true, isCtrlPressed = false, os = os), "$os")
        }

        for (os in OS.entries) {
            assertEquals(false, isCompareClick(isMetaPressed = false, isCtrlPressed = false, os = os), "$os")
        }

        assertEquals(mapOf(OS.MAC to "⌘-click", OS.LINUX to "Ctrl-click", OS.WINDOWS to "Ctrl-click"),
            composed { listOf(OS.MAC, OS.LINUX, OS.WINDOWS).associateWith { compareClickName(it) } })
    }

    @Test
    fun `a branch's menu offers the comparison after Merge and Rebase, except on the current branch`() {
        val compared = mutableListOf<String>()

        val items = branchMenu(feature, isCurrentBranch = false) { compared += "feature" }
        assertEquals(
            listOf(
                "Checkout branch",
                "Merge branch",
                "Rebase branch",
                SEPARATOR,
                "Compare with main (current)",
                SEPARATOR,
            ),
            labelsOf(items).take(6),
        )
        items.textEntries().single { it.labelIn() == "Compare with main (current)" }.onClick()
        assertEquals(listOf("feature"), compared)

        assertEquals(0, labelsOf(branchMenu(main, isCurrentBranch = true) {}).count { it.startsWith("Compare with") })
        assertEquals(
            0,
            labelsOf(branchMenu(feature, isCurrentBranch = false, onCompare = null)).count { it.startsWith("Compare with") },
            "without the callback, as upstream's callers pass none",
        )
        assertEquals(
            1,
            labelsOf(branchMenu(originFeature, isCurrentBranch = false) {}).count { it == "Compare with main (current)" },
            "a remote branch",
        )
        assertEquals(
            1,
            labelsOf(branchMenu(feature, isCurrentBranch = false, currentBranch = detached) {})
                .count { it == "Compare with HEAD (detached)" },
        )
    }

    @Test
    fun `a tag's menu offers the comparison between checking it out and deleting it`() {
        var compared = 0
        val items = tagContextMenuItems(
            onCheckoutTag = {},
            onDeleteTag = {},
            currentBranch = main,
            onCompareWithCurrent = { compared++ },
        )

        assertEquals(
            listOf("Checkout tag's commit", SEPARATOR, "Compare with main (current)", SEPARATOR, "Delete tag"),
            labelsOf(items),
        )
        items.textEntries().single { it.labelIn() == "Compare with main (current)" }.onClick()
        assertEquals(1, compared)

        assertEquals(
            listOf("Checkout tag's commit", "Delete tag"),
            labelsOf(tagContextMenuItems(onCheckoutTag = {}, onDeleteTag = {})),
        )
        assertEquals(
            listOf("Checkout tag's commit", "Delete tag"),
            labelsOf(tagContextMenuItems(onCheckoutTag = {}, onDeleteTag = {}, currentBranch = null, onCompareWithCurrent = {})),
            "a repository without a current branch",
        )
    }

    @Test
    fun `the remote part of a shared chip compares that remote branch`() {
        val upstream = Branch(HASH, "refs/remotes/upstream/feature", false)
        val compared = mutableListOf<Branch>()
        val items = sharedChipRemoteItems(
            remotes = listOf(originFeature, upstream),
            currentBranch = main,
            onPushToRemoteBranch = {},
            onPullFromRemoteBranch = {},
            onDeleteRemoteBranch = {},
            onCopyBranchNameToClipboard = {},
            worktreesBase = null,
            onChooseWorktreesBase = {},
            onCompareWithCurrent = { compared += it },
        )

        assertEquals(2, labelsOf(items).count { it == "Compare with main (current)" })
        items.textEntries().filter { it.labelIn() == "Compare with main (current)" }.forEach { it.onClick() }
        assertEquals(listOf(originFeature, upstream), compared)
    }

    private fun logMenu(compare: CompareMenuItems?): List<ContextMenuElement> = logContextMenu(
        onShowStatusAmending = {},
        onCheckoutCommit = {},
        onCreateNewBranch = {},
        onCreateNewTag = {},
        onRevertCommit = {},
        onCherryPickCommit = {},
        onResetBranch = {},
        onRebaseInteractive = {},
        onCopyCommitHash = {},
        isLastCommit = false,
        showInAmend = false,
        compare = compare,
    )

    private fun branchMenu(
        branch: Branch,
        isCurrentBranch: Boolean,
        currentBranch: Branch = main,
        onCompare: (() -> Unit)?,
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
        onCompareWithCurrent = onCompare,
    )

    private fun List<ContextMenuElement>.textEntries() = filterIsInstance<ContextMenuElement.ContextTextEntry>()

    /** The labels as the menu reads them, with [SEPARATOR] for a separator. */
    private fun labelsOf(items: List<ContextMenuElement>): List<String> = composed {
        items.map { item ->
            when (item) {
                is ContextMenuElement.ContextTextEntry -> item.composableLabel()
                else -> SEPARATOR
            }
        }
    }

    private fun hintsOf(items: List<ContextMenuElement>): Map<String, String?> = composed {
        items.textEntries().associate { it.composableLabel() to it.hint?.invoke() }
    }

    private fun ContextMenuElement.ContextTextEntry.labelIn(): String = composed { composableLabel() }

    private fun compareClick(): String = composed { compareClickName(currentOs) }

    private fun <T> composed(read: @androidx.compose.runtime.Composable () -> T): T {
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
