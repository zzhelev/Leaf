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
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.DeletionRemote
import dev.app.leaf.domain.branches.TipCommit
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.RemoteBranchesNeedGitError
import dev.app.leaf.domain.errors.SshNeedsGitError
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


    // A remote's branches (follows upstream issue 116)

    private val minute = 60_000L
    private val now = System.currentTimeMillis()
    private val origin = DeletionRemote("origin", "git@example.com:team/app.git", lastFetchedMillis = now - 4 * minute - 30_000)

    private fun remoteInfo(
        name: String,
        tip: String,
        merged: MergedIntoBase? = null,
        only: Int = 0,
        refusal: BranchKept? = null,
        author: String = "Alex Doe",
        daysAgo: Long = 2,
    ) = BranchDeletionInfo(
        branch = BranchToDelete("refs/remotes/origin/$name", tip.padEnd(40, '0')),
        refusal = refusal,
        merged = merged,
        commitsOnlyHere = only,
        lastCommit = TipCommit(author, now - daysAgo * 24 * 60 * minute),
    )

    private val remoteSquashed = remoteInfo("claude/silly-wiles", tip = "92d81d1", merged = MergedIntoBase.CHANGES)
    private val remotePushed = remoteInfo("feature/rebase-skip", tip = "bd772f2")
    private val remoteUnmerged = remoteInfo("dependabot/compose", tip = "c41e07a", only = 3, author = "dependabot", daysAgo = 1)
    private val remoteRelease = remoteInfo("release/1.0", tip = "e3c6632", merged = MergedIntoBase.COMMITS, daysAgo = 90)
    private val remoteMain = remoteInfo(
        "main",
        tip = "1a2b3c4",
        merged = MergedIntoBase.COMMITS,
        refusal = BranchKept.RemoteDefaultBranch(isNamedByRemote = true),
        daysAgo = 0,
    )
    private val remoteHead = remoteInfo(
        "HEAD",
        tip = "1a2b3c4",
        merged = MergedIntoBase.COMMITS,
        refusal = BranchKept.RemoteHead,
        daysAgo = 0,
    )
    private val remotePlan = BranchDeletionPlan(
        base = "refs/remotes/origin/main",
        infos = listOf(remoteSquashed, remotePushed, remoteUnmerged, remoteRelease, remoteMain, remoteHead),
        remote = origin,
    )
    private val remoteRequest = BranchesDeletionRequest(
        remotePlan.infos.map { Branch("", it.branch.name, isLocal = false) },
        remote = "origin",
    )
    private val remoteReady = DeleteBranchesState(remoteRequest, plan = remotePlan, checked = remotePlan.checkedAtFirst())

    @Test
    fun `a remote's branches say who made them, the remote's URL and last fetch, and the button names the remote`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(remoteReady).use { dialog ->
                val texts = dialog.texts()

                for (text in listOf(
                    "Delete branches on origin",
                    "Delete the 6 selected branches?",
                    "git@example.com:team/app.git · Last fetched 4 min ago",
                    "1 of 6 will be deleted",
                    "claude/silly-wiles", "Squash-merged into origin/main · 92d81d1 · Alex Doe, 2d ago",
                    "feature/rebase-skip", "Not merged, but all its commits are on other branches or tags · bd772f2 · Alex Doe, 2d ago",
                    "dependabot/compose", "Not merged: 3 commits are only on this branch · c41e07a · dependabot, 1d ago",
                    "release/1.0", "Merged into origin/main, looks long-lived · e3c6632 · Alex Doe, 3mo ago",
                    "main", "The default branch on origin · 1a2b3c4 · Alex Doe, just now",
                    "HEAD", "Names the default branch of origin, it isn't a branch · 1a2b3c4 · Alex Doe, just now",
                    "Each branch is deleted only if it still points to the commit shown, so a branch someone pushed to since you fetched stays.",
                )) {
                    assertTrue(text in texts, "$text in $texts")
                }

                // The squash-merged one: release/1.0 looks long-lived, the others aren't merged
                assertEquals(listOf(true, false, false, false), dialog.checkboxStates())
                assertTrue(dialog.isEnabled("Delete 1 on origin"), texts.toString())
                assertFalse(texts.any { it.contains("pull requests") }, "Nothing unmerged is checked")
            }
        }

    @Test
    fun `checking a branch that isn't merged warns that its pull requests may close`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val checked = remoteReady.withChecked(remoteUnmerged.branch.name, true)

            DialogScene(checked).use { dialog ->
                assertTrue(
                    "Some checked branches aren't merged. A server can close the open pull requests of a deleted branch." in
                        dialog.texts(),
                    dialog.texts().toString(),
                )
                assertTrue(dialog.isEnabled("Delete 2 on origin"))
            }

            // Its commits are on other branches, maybe the user's own, but the server's copy may have a pull request
            DialogScene(remoteReady.withChecked(remotePushed.branch.name, true)).use { dialog ->
                assertTrue(dialog.texts().any { it.startsWith("Some checked branches aren't merged.") }, dialog.texts().toString())
            }
        }

    @Test
    fun `while it deletes a remote's, nothing can stop it at first, as the server carries out what it was asked`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(remoteReady.copy(isDeleting = true, deletingOn = "origin")).use { dialog ->
                assertTrue("Deleting on origin…" in dialog.texts(), dialog.texts().toString())
                assertFalse(dialog.hasButton("Cancel"))
                assertFalse(dialog.hasButton("Stop waiting"))
                assertFalse(dialog.isEnabled("Delete 1 on origin"))
            }
        }

    @Test
    fun `after a while, it offers to stop waiting`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(remoteReady.copy(isDeleting = true, deletingOn = "origin", canStopWaiting = true)).use { dialog ->
            dialog.click("Stop waiting")

            assertEquals(listOf("stop"), dialog.calls)
            assertFalse(dialog.isEnabled("Delete 1 on origin"))
        }
    }

    @Test
    fun `after a stop the dialog says some may be gone, and only closes`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(remoteReady.copy(wasStopped = true)).use { dialog ->
            assertTrue(
                "Stopped waiting. Some branches may already be gone on origin: fetch to see which." in dialog.texts(),
                dialog.texts().toString(),
            )
            assertFalse(dialog.hasButton("Cancel"))
            assertEquals(0, dialog.checkboxStates().size, "The list is gone, as it may no longer be true")

            dialog.click("Close")

            assertEquals(listOf("dismiss"), dialog.calls)
        }
    }

    @Test
    fun `a remote's result names the remote, how to push one back, and what the server said`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val result = BranchesDeletion(
                listOf(
                    BranchDeletionOutcome.Deleted(remoteSquashed.branch),
                    BranchDeletionOutcome.Kept(remotePushed.branch, BranchKept.ChangedOnRemote("7c1d2e3".padEnd(40, '0'))),
                    BranchDeletionOutcome.Kept(remoteRelease.branch, BranchKept.RefusedByRemote("hook declined")),
                ),
                remoteMessage = "error: release/1.0 is protected",
            )

            DialogScene(remoteReady.copy(result = result)).use { dialog ->
                val texts = dialog.texts()

                for (text in listOf(
                    "Deleted 1 of 3 on origin",
                    "2 branches were left as they are.",
                    "claude/silly-wiles", "was 92d81d1",
                    "feature/rebase-skip", "Someone pushed to it on origin since you fetched: it's now 7c1d2e3.",
                    "release/1.0", "origin refused: hook declined",
                    "To bring one back, push its commit: git push origin <commit>:refs/heads/<name>",
                    "origin said:", "error: release/1.0 is protected",
                )) {
                    assertTrue(text in texts, "$text in $texts")
                }

                assertTrue(dialog.isEnabled("Copy list"))

                dialog.click("Delete it anyway")
                assertEquals(listOf("anyway"), dialog.calls)
            }

            val all = BranchesDeletion(listOf(BranchDeletionOutcome.Deleted(remoteSquashed.branch)))

            DialogScene(remoteReady.copy(result = all)).use { dialog ->
                assertTrue("Deleted 1 branch on origin" in dialog.texts(), dialog.texts().toString())
                assertFalse(dialog.texts().any { it.endsWith("said:") }, "The server said nothing")
            }

            val none = BranchesDeletion(listOf(BranchDeletionOutcome.Kept(remotePushed.branch, BranchKept.ChangedOnRemote(now = null))))

            DialogScene(remoteReady.copy(result = none)).use { dialog ->
                assertTrue("No branch was deleted on origin" in dialog.texts(), dialog.texts().toString())
                // Without the server's commit there's no lease to delete it anyway with
                assertFalse(dialog.texts().any { it.endsWith("anyway") }, dialog.texts().toString())
            }
        }

    @Test
    fun `merged ones on a remote, and a remote without a default branch`(): Unit = runBlocking(Dispatchers.Swing) {
        val merged = BranchDeletionPlan(remotePlan.base, listOf(remoteSquashed, remoteRelease), mergedOnly = true, remote = origin)

        DialogScene(DeleteBranchesState(remoteRequest.copy(mergedOnly = true), plan = merged, checked = merged.checkedAtFirst()))
            .use { dialog ->
                val texts = dialog.texts()

                assertTrue("Delete merged branches on origin" in texts, texts.toString())
                assertTrue("Branches merged into origin/main" in texts)
                assertTrue("1 of 2 will be deleted" in texts)
                assertTrue(dialog.isEnabled("Delete 1 on origin"))
            }

        val noBase = BranchDeletionPlan(null, listOf(remoteUnmerged), mergedOnly = true, remote = origin)

        DialogScene(DeleteBranchesState(remoteRequest.copy(mergedOnly = true), plan = noBase)).use { dialog ->
            assertTrue(
                "origin has no default branch that Leaf knows of, and there's no base branch, so none counts as merged. " +
                    "git remote set-head origin --auto sets it." in dialog.texts(),
                dialog.texts().toString(),
            )
        }
    }

    @Test
    fun `a remote without a URL or fetch time has no line for them`(): Unit = runBlocking(Dispatchers.Swing) {
        val plain = remotePlan.copy(remote = DeletionRemote("origin", url = null, lastFetchedMillis = null))

        DialogScene(remoteReady.copy(plan = plain)).use { dialog ->
            assertFalse(dialog.texts().any { "Last fetched" in it || "example.com" in it }, dialog.texts().toString())
        }

        val urlOnly = remotePlan.copy(remote = origin.copy(lastFetchedMillis = null))

        DialogScene(remoteReady.copy(plan = urlOnly)).use { dialog ->
            assertTrue("git@example.com:team/app.git" in dialog.texts(), dialog.texts().toString())
        }
    }

    @Test
    fun `with git turned off, the button turns it on`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = RemoteBranchesNeedGitError(SshNeedsGitError.Reason.SettingOff)

        DialogScene(DeleteBranchesState(remoteRequest, error = error)).use { dialog ->
            assertTrue(
                "Leaf deletes several of a remote's branches with git, and \"Use git for remote operations\" is off." in dialog.texts(),
                dialog.texts().toString(),
            )
            assertTrue("Delete branches on origin" in dialog.texts())

            dialog.click("Turn it on")
            assertEquals(listOf("use git"), dialog.calls)
        }
    }

    @Test
    fun `when it finds no git, the button opens Settings`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = RemoteBranchesNeedGitError(SshNeedsGitError.Reason.GitNotFound)

        DialogScene(DeleteBranchesState(remoteRequest, error = error)).use { dialog ->
            dialog.click("Open Settings")
            assertEquals(listOf("settings"), dialog.calls)
        }
    }

    @Test
    fun `without the askpass helper, nothing can be done`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = RemoteBranchesNeedGitError(SshNeedsGitError.Reason.HelperMissing)

        DialogScene(DeleteBranchesState(remoteRequest, error = error)).use { dialog ->
            assertFalse(dialog.hasButton("Turn it on"))
            assertFalse(dialog.hasButton("Open Settings"))
            assertFalse(dialog.isEnabled("Delete"))
        }
    }

    @Test
    fun `git turned off after the list was read still offers the fix, with the list`(): Unit = runBlocking(Dispatchers.Swing) {
        val error = RemoteBranchesNeedGitError(SshNeedsGitError.Reason.SettingOff)

        DialogScene(remoteReady.copy(error = error)).use { dialog ->
            assertTrue(remoteSquashed.branch.simpleName in dialog.texts(), dialog.texts().toString())
            dialog.click("Turn it on")
            assertEquals(listOf("use git"), dialog.calls)
        }
    }

    private fun copyOf(info: BranchDeletionInfo, goes: Boolean, kind: BranchDeletionKind, longLived: Boolean = false) = BranchCopy(
        remote = "origin",
        info = BranchDeletionInfo(
            BranchToDelete("refs/remotes/origin/${info.branch.simpleName}", info.branch.tip),
            refusal = null,
            merged = null,
            commitsOnlyHere = 0,
        ),
        kind = kind,
        looksLongLived = longLived,
        goes = goes,
    )

    private val copiesPlan = plan.copy(
        copies = mapOf(
            inBase.branch.name to copyOf(inBase, goes = true, BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0)),
            squashed.branch.name to copyOf(squashed, goes = true, BranchDeletionKind.OnOtherRefs),
        ),
    )
    private val copiesReady = ready.copy(plan = copiesPlan)
    private val copiesAsked = copiesReady.withDeletesCopies(true)

    @Test
    fun `offers the merged copies on origin, without saying more until asked`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(copiesReady).use { dialog ->
            assertTrue("Also delete their copies on origin (2)" in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.texts().any { it.startsWith("origin/") })
            assertTrue(dialog.hasButton("Delete 3 branches"))

            dialog.clickRow("Also delete their copies on origin (2)")
            assertEquals(listOf("copies true"), dialog.calls)
        }
    }

    @Test
    fun `asked for, each row says what happens to its copy, and the button names origin`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(copiesAsked).use { dialog ->
            assertTrue("origin/claude/eager-ride-4f2a goes too" in dialog.texts(), dialog.texts().toString())
            assertTrue(
                "origin/claude/jolly-fox-91c0 goes too: not merged, a server can close its pull requests" in dialog.texts(),
                dialog.texts().toString(),
            )
            assertTrue(dialog.isEnabled("Delete 3, and 2 on origin"))
            assertTrue(dialog.texts().any { it.startsWith("Each branch is deleted only if it still points to the commit shown") })
            // pushed/topic isn't merged, but only merged copies go, so the remote's warning doesn't apply
            assertFalse(dialog.texts().any { it.startsWith("Some checked branches aren't merged") }, dialog.texts().toString())
        }
    }

    @Test
    fun `a copy whose name looks long-lived goes too, marked`(): Unit = runBlocking(Dispatchers.Swing) {
        val develop = info("develop", tip = "ff53e83", merged = MergedIntoBase.COMMITS)
        val longLived = copyOf(develop, goes = true, BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0), longLived = true)
        val developPlan = BranchDeletionPlan("refs/heads/main", listOf(develop), copies = mapOf(develop.branch.name to longLived))
        val state = DeleteBranchesState(request, plan = developPlan, checked = developPlan.checkedAtFirst()).withDeletesCopies(true)

        DialogScene(state).use { dialog ->
            assertTrue("origin/develop goes too: its name looks long-lived" in dialog.texts(), dialog.texts().toString())
            assertTrue(dialog.isEnabled("Delete 1, and 1 on origin"))
        }
    }

    @Test
    fun `no copy that can go, no switch`(): Unit = runBlocking(Dispatchers.Swing) {
        val refused = BranchDeletionKind.Refused(BranchKept.RemoteDefaultBranch(isNamedByRemote = true))
        val none = ready.copy(
            plan = plan.copy(copies = mapOf(squashed.branch.name to copyOf(squashed, goes = false, refused))),
        )

        DialogScene(none).use { dialog ->
            assertFalse(dialog.texts().any { it.startsWith("Also delete their merged copies") })
        }
    }

    @Test
    fun `while it deletes the copies, it can stop waiting after a while`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(copiesAsked.copy(isDeleting = true, deletingOn = "origin")).use { dialog ->
            assertTrue("Deleting on origin…" in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.hasButton("Stop waiting"))
        }

        DialogScene(copiesAsked.copy(isDeleting = true, deletingOn = "origin", canStopWaiting = true)).use { dialog ->
            dialog.click("Stop waiting")
            assertEquals(listOf("stop"), dialog.calls)
        }
    }

    @Test
    fun `the result says what happened to each copy, and how to bring one back`(): Unit = runBlocking(Dispatchers.Swing) {
        val copy = copiesPlan.copies.getValue(inBase.branch.name).info.branch
        val result = BranchesDeletion(
            outcomes = listOf(BranchDeletionOutcome.Deleted(inBase.branch), BranchDeletionOutcome.Deleted(squashed.branch)),
            copies = mapOf(inBase.branch.name to BranchDeletionOutcome.Deleted(copy)),
        )

        DialogScene(copiesAsked.copy(result = result)).use { dialog ->
            assertTrue("origin/claude/eager-ride-4f2a deleted too" in dialog.texts(), dialog.texts().toString())
            assertTrue(
                "To bring one back, push its commit: git push origin <commit>:refs/heads/<name>" in dialog.texts(),
                dialog.texts().toString(),
            )
        }
    }

    @Test
    fun `a copy that origin kept says why`(): Unit = runBlocking(Dispatchers.Swing) {
        val copy = copiesPlan.copies.getValue(inBase.branch.name).info.branch
        val result = BranchesDeletion(
            outcomes = listOf(BranchDeletionOutcome.Deleted(inBase.branch)),
            copies = mapOf(inBase.branch.name to BranchDeletionOutcome.Kept(copy, BranchKept.ChangedOnRemote("7c1d2e3".padEnd(40, '0')))),
        )

        DialogScene(copiesAsked.copy(result = result)).use { dialog ->
            assertTrue(
                "origin/claude/eager-ride-4f2a stays: Someone pushed to it on origin since you fetched: it's now 7c1d2e3." in dialog.texts(),
                dialog.texts().toString(),
            )

            dialog.click("Delete it anyway")
            assertEquals(listOf("anyway"), dialog.calls)
        }

        // While it deletes anyway, the button is gone, and after a while it can stop waiting
        DialogScene(copiesAsked.copy(result = result, isDeleting = true, deletingOn = "origin", canStopWaiting = true)).use { dialog ->
            assertFalse(dialog.hasButton("Delete it anyway"))
            assertFalse(dialog.isEnabled("Close"))
            dialog.click("Stop waiting")
            assertEquals(listOf("stop"), dialog.calls)
        }
    }

    @Test
    fun `copies that origin couldn't be asked to delete show why`(): Unit = runBlocking(Dispatchers.Swing) {
        val result = BranchesDeletion(
            outcomes = listOf(BranchDeletionOutcome.Deleted(inBase.branch)),
            copyErrors = mapOf("origin" to GenericError("Could not resolve host")),
        )

        DialogScene(copiesAsked.copy(result = result)).use { dialog ->
            assertTrue("origin/claude/eager-ride-4f2a wasn't deleted" in dialog.texts(), dialog.texts().toString())
            assertTrue("Their copies on origin weren't deleted:" in dialog.texts())
            assertTrue(dialog.texts().any { "Could not resolve host" in it }, dialog.texts().toString())
        }
    }

    @Test
    fun `stopped while deleting copies, and origin couldn't be asked, it says some may be gone`(): Unit = runBlocking(Dispatchers.Swing) {
        val result = BranchesDeletion(outcomes = listOf(BranchDeletionOutcome.Deleted(inBase.branch)), wasStopped = true)

        DialogScene(copiesAsked.copy(result = result, wasStopped = true)).use { dialog ->
            assertTrue("origin/claude/eager-ride-4f2a may already be gone" in dialog.texts(), dialog.texts().toString())
            assertTrue(
                "Stopped waiting. Some of their copies may already be gone on origin: fetch to see which." in dialog.texts(),
                dialog.texts().toString(),
            )
        }
    }

    @Test
    fun `after a stop, it asks origin what it still has, which Stop checking stops`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(remoteReady.copy(wasStopped = true, isChecking = true)).use { dialog ->
            assertTrue("Stopped waiting. Asking origin what it still has…" in dialog.texts(), dialog.texts().toString())
            dialog.click("Stop checking")
            assertEquals(listOf("stop checking"), dialog.calls)
        }
    }

    @Test
    fun `after a stop, the result says what origin deleted and what it still has`(): Unit = runBlocking(Dispatchers.Swing) {
        val result = BranchesDeletion(
            listOf(
                BranchDeletionOutcome.Deleted(remoteSquashed.branch),
                BranchDeletionOutcome.Kept(remotePushed.branch, BranchKept.StillOnRemote),
            ),
            wasStopped = true,
        )

        DialogScene(remoteReady.copy(wasStopped = true, result = result)).use { dialog ->
            assertTrue("Stopped waiting: origin deleted 1 of 2" in dialog.texts(), dialog.texts().toString())
            assertTrue("Still on origin." in dialog.texts(), dialog.texts().toString())
            assertTrue(dialog.isEnabled("Close"))
        }
    }

    @Test
    fun `while it asks origin again, the result shows, and Stop checking stops it`(): Unit = runBlocking(Dispatchers.Swing) {
        val result = BranchesDeletion(
            listOf(BranchDeletionOutcome.Kept(remotePushed.branch, BranchKept.StillOnRemote)),
            wasStopped = true,
        )

        DialogScene(remoteReady.copy(wasStopped = true, result = result, isRechecking = true)).use { dialog ->
            assertTrue("Asking origin again, in case it's still deleting…" in dialog.texts(), dialog.texts().toString())
            assertTrue("Still on origin." in dialog.texts())
            dialog.click("Stop checking")
            assertEquals(listOf("stop checking"), dialog.calls)
        }
    }

    @Test
    fun `after a stop before origin deleted any, the title says so`(): Unit = runBlocking(Dispatchers.Swing) {
        val result = BranchesDeletion(
            listOf(BranchDeletionOutcome.Kept(remotePushed.branch, BranchKept.StillOnRemote)),
            wasStopped = true,
        )

        DialogScene(remoteReady.copy(wasStopped = true, result = result)).use { dialog ->
            assertTrue("Stopped waiting: origin deleted none" in dialog.texts(), dialog.texts().toString())
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
            "remote-ready" to remoteReady,
            "remote-unmerged-checked" to remoteReady.withChecked(remoteUnmerged.branch.name, true),
            "remote-deleting" to remoteReady.copy(isDeleting = true, deletingOn = "origin"),
            "remote-deleting-long" to remoteReady.copy(isDeleting = true, deletingOn = "origin", canStopWaiting = true),
            "remote-stopped" to remoteReady.copy(wasStopped = true),
            "remote-result" to remoteReady.copy(
                result = BranchesDeletion(
                    listOf(
                        BranchDeletionOutcome.Deleted(remoteSquashed.branch),
                        BranchDeletionOutcome.Deleted(remotePushed.branch),
                        BranchDeletionOutcome.Kept(remoteUnmerged.branch, BranchKept.ChangedOnRemote("7c1d2e3".padEnd(40, '0'))),
                        BranchDeletionOutcome.Kept(remoteRelease.branch, BranchKept.RefusedByRemote("hook declined")),
                    ),
                    remoteMessage = "error: release/1.0 is protected",
                ),
            ),
            "copies-offered" to copiesReady,
            "copies-asked" to copiesAsked,
            "copies-result" to copiesAsked.copy(
                result = BranchesDeletion(
                    outcomes = listOf(BranchDeletionOutcome.Deleted(inBase.branch), BranchDeletionOutcome.Deleted(squashed.branch)),
                    copies = mapOf(
                        inBase.branch.name to BranchDeletionOutcome.Deleted(copiesPlan.copies.getValue(inBase.branch.name).info.branch),
                    ),
                ),
            ),
            "copies-changed" to copiesAsked.copy(
                result = BranchesDeletion(
                    outcomes = listOf(BranchDeletionOutcome.Deleted(inBase.branch), BranchDeletionOutcome.Deleted(squashed.branch)),
                    copies = mapOf(
                        inBase.branch.name to BranchDeletionOutcome.Kept(
                            copiesPlan.copies.getValue(inBase.branch.name).info.branch,
                            BranchKept.ChangedOnRemote("7c1d2e3".padEnd(40, '0')),
                        ),
                        squashed.branch.name to BranchDeletionOutcome.Deleted(copiesPlan.copies.getValue(squashed.branch.name).info.branch),
                    ),
                ),
            ),
            "remote-checking" to remoteReady.copy(wasStopped = true, isChecking = true),
            "remote-stopped-result" to remoteReady.copy(
                wasStopped = true,
                result = BranchesDeletion(
                    listOf(
                        BranchDeletionOutcome.Deleted(remoteSquashed.branch),
                        BranchDeletionOutcome.Kept(remotePushed.branch, BranchKept.StillOnRemote),
                    ),
                    wasStopped = true,
                ),
            ),
            "remote-needs-git" to DeleteBranchesState(
                remoteRequest,
                error = RemoteBranchesNeedGitError(SshNeedsGitError.Reason.SettingOff),
            ),
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
                    onStop = { calls += "stop" },
                    onStopChecking = { calls += "stop checking" },
                    onDeletesCopiesChange = { calls += "copies $it" },
                    onDeleteAnyway = { calls += "anyway" },
                    onUseGit = { calls += "use git" },
                    onOpenSettings = { calls += "settings" },
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
