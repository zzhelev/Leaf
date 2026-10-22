// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.MergedIntoBase
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What "Delete branches" and "Delete merged branches" show, from their state (fork-only, upstream issue 116): a status
 * line for every branch, which are checked at first, what a click does, and the result that stays. Set
 * `LEAF_RENDER_DIR` to a folder to get a PNG of each state in both themes.
 */
class DeleteBranchesDialogTest {
    private val home = System.getProperty("user.home")

    private val inBase = info("claude/eager-ride-4f2a", tip = "a1b2c3d", merged = MergedIntoBase.COMMITS)
    private val squashed = info("claude/jolly-fox-91c0", tip = "7e90d12", merged = MergedIntoBase.CHANGES, only = 3)
    private val pushed = info("pushed/topic", tip = "0b1c2d3")
    private val unmerged = info("feature/export-csv", tip = "9c8d7e6", only = 5)
    private val used = info(
        "worktree-agent-a1b2c3d",
        tip = "5f4e3d2",
        refusal = BranchKept.UsedByWorktree("$home/leaf-agent1", WorktreeBranchUse.CheckedOut, isHere = false),
    )
    private val here = info(
        "main",
        tip = "1122334",
        merged = MergedIntoBase.COMMITS,
        refusal = BranchKept.UsedByWorktree("/work/leaf", WorktreeBranchUse.CheckedOut, isHere = true),
    )

    private val plan = BranchDeletionPlan(
        base = "refs/heads/main",
        infos = listOf(inBase, squashed, pushed, unmerged, used, here),
    )
    private val request = BranchesDeletionRequest(plan.infos.map { Branch("", it.branch.name, isLocal = true) })
    private val ready = DeleteBranchesState(request, plan = plan, checked = plan.checkedAtFirst())

    @Test
    fun `says what deleting each branch does, and what is checked`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ready).use { dialog ->
            val texts = dialog.texts()

            for (text in listOf(
                "Delete branches",
                "Delete the 6 selected branches?",
                "3 of 6 will be deleted",
                "claude/eager-ride-4f2a", "Merged into main · a1b2c3d",
                "claude/jolly-fox-91c0", "Squash-merged into main. Its 3 commits will be on no branch. · 7e90d12",
                "pushed/topic", "Not merged, but all its commits are on other branches or tags · 0b1c2d3",
                "feature/export-csv", "Not merged: 5 commits are only on this branch · 9c8d7e6",
                "worktree-agent-a1b2c3d", "Checked out in the worktree ~/leaf-agent1 · 5f4e3d2",
                "main", "Checked out in this worktree · 1122334",
            )) {
                assertTrue(text in texts, "$text in $texts")
            }

            // The four that can be checked, three of them at first
            assertEquals(4, dialog.checkboxStates().size)
            assertEquals(listOf(true, true, true, false), dialog.checkboxStates())
            assertTrue(dialog.isEnabled("Delete 3 branches"), texts.toString())
        }
    }

    @Test
    fun `a click on a row checks or unchecks it, and a refused branch takes no click`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(ready).use { dialog ->
                dialog.clickRow("feature/export-csv")
                dialog.clickRow("claude/eager-ride-4f2a")

                assertEquals(listOf("check refs/heads/feature/export-csv true", "check refs/heads/claude/eager-ride-4f2a false"), dialog.calls)
                assertFalse(dialog.hasClickableRow("worktree-agent-a1b2c3d"))
                assertFalse(dialog.hasClickableRow("main"))
            }
        }

    @Test
    fun `all, safe and none check what they say`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ready).use { dialog ->
            dialog.click("all")
            dialog.click("safe")
            dialog.click("none")

            assertEquals(listOf("all", "safe", "none"), dialog.calls)
        }
    }

    @Test
    fun `Delete confirms, Cancel leaves, and with nothing checked Delete does nothing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(ready).use { dialog ->
                dialog.click("Delete 3 branches")
                dialog.click("Cancel")

                assertEquals(listOf("confirm", "dismiss"), dialog.calls)
            }

            DialogScene(ready.copy(checked = emptySet())).use { dialog ->
                assertFalse(dialog.isEnabled("Delete"))
                assertTrue("0 of 6 will be deleted" in dialog.texts())
            }
        }

    @Test
    fun `while it deletes, nothing can be checked and the dialog stays`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ready.copy(isDeleting = true)).use { dialog ->
            dialog.click("Cancel")

            assertTrue(dialog.calls.isEmpty(), "Leaving would stop it between two branches: ${dialog.calls}")
            assertFalse(dialog.isEnabled("Delete 3 branches"))
        }
    }

    @Test
    fun `reading the branches says so, and a failure to read them says why`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(DeleteBranchesState(request)).use { dialog ->
            assertTrue("Checking the branches…" in dialog.texts(), dialog.texts().toString())
            assertTrue("Checking what deleting them would do" in dialog.texts())
            assertFalse(dialog.isEnabled("Delete"))
        }

        DialogScene(DeleteBranchesState(request, error = GenericError("The repository can't be read"))).use { dialog ->
            assertTrue(dialog.texts().any { "The repository can't be read" in it }, dialog.texts().toString())
            assertFalse("Checking the branches…" in dialog.texts())
        }
    }

    @Test
    fun `the result stays, with each deleted branch's commit and why the others stay`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val result = BranchesDeletion(
                listOf(
                    BranchDeletionOutcome.Deleted(inBase.branch),
                    BranchDeletionOutcome.Deleted(squashed.branch),
                    BranchDeletionOutcome.Kept(unmerged.branch, BranchKept.Moved("e5f6a7b8".padEnd(40, '0'))),
                ),
            )

            DialogScene(ready.copy(result = result)).use { dialog ->
                val texts = dialog.texts()

                for (text in listOf(
                    "Deleted 2 of 3 branches",
                    "1 branch was left as it is.",
                    "Deleted", "claude/eager-ride-4f2a", "was a1b2c3d", "claude/jolly-fox-91c0", "was 7e90d12",
                    "Not deleted", "feature/export-csv",
                    "It moved since you opened this: it was 9c8d7e6 and is now e5f6a7b. Nothing was deleted.",
                    "To bring one back, run git branch followed by its name and commit.",
                )) {
                    assertTrue(text in texts, "$text in $texts")
                }

                assertEquals(0, dialog.checkboxStates().size, "The list is gone")
                assertFalse(dialog.hasButton("Cancel"), "There is nothing to cancel")
                assertTrue(dialog.isEnabled("Close"))
                assertTrue(dialog.isEnabled("Copy list"))

                dialog.click("Copy list")
                dialog.click("Close")

                assertEquals(listOf("copy", "dismiss"), dialog.calls)
            }
        }

    @Test
    fun `every reason that a branch stays is worded`(): Unit = runBlocking(Dispatchers.Swing) {
        val kept = listOf(
            BranchKept.NoLongerExists to "It no longer exists.",
            BranchKept.Failed("The branch is locked") to "Git couldn't delete it: The branch is locked",
            BranchKept.UsedByWorktree("$home/leaf-agent1", WorktreeBranchUse.Rebasing, isHere = false) to
                "Being rebased in the worktree ~/leaf-agent1",
            BranchKept.UsedByWorktree("/work/leaf", WorktreeBranchUse.Bisecting, isHere = true) to
                "A bisect in this worktree started from it",
        )
        val result = BranchesDeletion(kept.mapIndexed { i, (reason, _) ->
            BranchDeletionOutcome.Kept(BranchToDelete("refs/heads/b$i", "abcdef0".padEnd(40, '0')), reason)
        })

        DialogScene(ready.copy(result = result)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("No branch was deleted" in texts, texts.toString())
            assertTrue("4 branches were left as they are." in texts, texts.toString())
            assertFalse(dialog.hasButton("Copy list"), "Nothing was deleted, so there is nothing to copy")

            for ((_, text) in kept) {
                assertTrue(text in texts, "$text in $texts")
            }
        }
    }

    @Test
    fun `everything deleted says so`(): Unit = runBlocking(Dispatchers.Swing) {
        val result = BranchesDeletion(listOf(BranchDeletionOutcome.Deleted(inBase.branch)))

        DialogScene(ready.copy(result = result)).use { dialog ->
            assertTrue("Deleted 1 branch" in dialog.texts(), dialog.texts().toString())
            assertTrue("Each one's commit is below, to bring it back." in dialog.texts())
            assertFalse("Not deleted" in dialog.texts())
        }
    }

    @Test
    fun `delete merged branches lists the merged ones, and why a worktree's stay`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val mergedPlan = BranchDeletionPlan(
                base = "refs/heads/main",
                infos = listOf(
                    inBase,
                    squashed,
                    info(
                        "claude/in-use",
                        tip = "5f4e3d2",
                        merged = MergedIntoBase.COMMITS,
                        refusal = BranchKept.UsedByWorktree("$home/leaf-agent2", WorktreeBranchUse.CheckedOut, isHere = false),
                    ),
                    unmerged,
                    pushed,
                ),
                mergedOnly = true,
            )
            val mergedState = DeleteBranchesState(
                request.copy(mergedOnly = true),
                plan = mergedPlan,
                checked = mergedPlan.checkedAtFirst(),
            )

            DialogScene(mergedState).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Delete merged branches" in texts, texts.toString())
                assertTrue("Branches merged into main" in texts)
                assertTrue("claude/eager-ride-4f2a" in texts)
                assertTrue("claude/in-use" in texts)
                assertFalse("feature/export-csv" in texts, "Not merged: not listed")
                assertFalse("pushed/topic" in texts, "Not merged: not listed")
                assertTrue("2 of 3 will be deleted" in texts, texts.toString())
            }
        }

    @Test
    fun `delete merged branches says when none is merged, or there is no base`(): Unit = runBlocking(Dispatchers.Swing) {
        val none = BranchDeletionPlan("refs/heads/main", listOf(unmerged, pushed), mergedOnly = true)

        DialogScene(DeleteBranchesState(request.copy(mergedOnly = true), plan = none)).use { dialog ->
            assertTrue("No branch is merged into main." in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.isEnabled("Delete"))
        }

        val noBase = BranchDeletionPlan(null, listOf(unmerged), mergedOnly = true)

        DialogScene(DeleteBranchesState(request.copy(mergedOnly = true), plan = noBase)).use { dialog ->
            assertTrue(dialog.texts().any { it.startsWith("There's no base branch") }, dialog.texts().toString())
        }
    }

    @Test
    fun `without a base the list says that nothing counts as merged`(): Unit = runBlocking(Dispatchers.Swing) {
        val noBase = BranchDeletionPlan(null, listOf(pushed, unmerged))

        DialogScene(DeleteBranchesState(request, plan = noBase, checked = noBase.checkedAtFirst())).use { dialog ->
            assertTrue(dialog.texts().any { it.startsWith("There's no base branch") }, dialog.texts().toString())
        }
    }

    @Test
    fun `a branch that no longer exists leaves an empty list that says so`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(DeleteBranchesState(request, plan = BranchDeletionPlan("refs/heads/main", emptyList()))).use { dialog ->
            assertTrue("None of the selected branches exist any more." in dialog.texts(), dialog.texts().toString())
        }
    }

    @Test
    fun `a long list scrolls`(): Unit = runBlocking(Dispatchers.Swing) {
        val many = (1..40).map { info("claude/agent-$it", tip = "abc${it.toString().padStart(4, '0')}", merged = MergedIntoBase.COMMITS) }
        val longPlan = BranchDeletionPlan("refs/heads/main", many)

        DialogScene(DeleteBranchesState(request, plan = longPlan, checked = longPlan.checkedAtFirst())).use { dialog ->
            assertEquals(1, dialog.scrollRanges().count { it > 0f }, dialog.scrollRanges().toString())
            assertTrue("40 of 40 will be deleted" in dialog.texts())
            assertTrue(dialog.isEnabled("Delete 40 branches"))
        }
    }

    @Test
    fun `renders every state in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()
        val mergedPlan = BranchDeletionPlan("refs/heads/main", listOf(inBase, squashed, used, here), mergedOnly = true)
        val cases = linkedMapOf(
            "loading" to DeleteBranchesState(request),
            "ready" to ready,
            "ready-none-checked" to ready.copy(checked = emptySet()),
            "deleting" to ready.copy(isDeleting = true),
            "merged-only" to DeleteBranchesState(
                request.copy(mergedOnly = true),
                plan = mergedPlan,
                checked = mergedPlan.checkedAtFirst(),
            ),
            "result" to ready.copy(
                result = BranchesDeletion(
                    listOf(
                        BranchDeletionOutcome.Deleted(inBase.branch),
                        BranchDeletionOutcome.Deleted(squashed.branch),
                        BranchDeletionOutcome.Kept(unmerged.branch, BranchKept.Moved("e5f6a7b8".padEnd(40, '0'))),
                        BranchDeletionOutcome.Kept(used.branch, used.refusal!!),
                    ),
                ),
            ),
            "error" to DeleteBranchesState(request, error = GenericError("The repository can't be read")),
        )

        for (themeUnderTest in listOf(Theme.Dark, Theme.Light)) {
            for ((name, value) in cases) {
                DialogScene(value, themeUnderTest).use { dialog ->
                    val image = dialog.scene.render(0L)

                    File(folder, "delete-branches-$name-${themeUnderTest.name.lowercase()}.png")
                        .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    private fun info(
        name: String,
        tip: String,
        merged: MergedIntoBase? = null,
        only: Int = 0,
        refusal: BranchKept.UsedByWorktree? = null,
    ) = BranchDeletionInfo(
        branch = BranchToDelete("refs/heads/$name", tip.padEnd(40, '0')),
        refusal = refusal,
        merged = merged,
        commitsOnlyHere = only,
    )

    private inner class DialogScene(state: DeleteBranchesState, theme: Theme = Theme.Dark) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = 900, height = 1000, density = Density(1f)) {
            AppTheme(theme) {
                DeleteBranchesDialogContent(
                    state = state,
                    onCheckedChange = { name, checked -> calls += "check $name $checked" },
                    onCheckAll = { calls += "all" },
                    onCheckSafe = { calls += "safe" },
                    onCheckNone = { calls += "none" },
                    onConfirm = { calls += "confirm" },
                    onCopyList = { calls += "copy" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** Whether each checkbox is checked, in the dialog's order. */
        fun checkboxStates(): List<Boolean> = nodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ToggleableState) }
            .map { it == ToggleableState.On }

        fun scrollRanges(): List<Float> = nodes().mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke()
        }

        /** Whether the button [text] takes clicks: `PrimaryButton` isn't clickable at all while it's disabled. */
        fun isEnabled(text: String): Boolean = buttons(text).isNotEmpty()

        fun hasButton(text: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        fun click(text: String) {
            checkNotNull(buttons(text).first().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun hasClickableRow(name: String): Boolean = rows(name).isNotEmpty()

        /** Clicks the row of the branch [name], which toggles its checkbox. */
        fun clickRow(name: String) {
            checkNotNull(rows(name).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun rows(name: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.ToggleableState) == null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == name }
        }

        private fun buttons(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}
