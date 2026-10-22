// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.cherrypick.BranchCherryPick
import dev.app.leaf.domain.cherrypick.CherryPickCandidate
import dev.app.leaf.domain.cherrypick.CherryPickLeftOut
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the dialog that cherry-picks a branch's commits shows, from its state (fork-only). */
class CherryPickBranchDialogTest {
    private val fix = Branch("f".repeat(40), "refs/heads/fix/unread-bar", isLocal = true)

    private fun commit(hash: String, message: String, parents: Int = 1) = Commit(
        hash = hash.repeat(40 / hash.length),
        message = message,
        committer = Identity("A", "a@example.invalid"),
        author = Identity("A", "a@example.invalid"),
        date = 1_790_000_000,
        parentsHashes = List(parents) { "0".repeat(40) },
    )

    private val one = commit("1", "CAPS-1260: Count unread messages")
    private val merge = commit("2", "Merge branch 'main' into fix", parents = 2)
    private val two = commit("3", "CAPS-1260: Show the unread bar")
    private val applied = commit("4", "Fix the badge")
    private val wip = commit("5", "WIP: debug log")

    private fun pick(
        candidates: List<CherryPickCandidate> = listOf(
            CherryPickCandidate(one),
            CherryPickCandidate(merge, CherryPickLeftOut.MERGE),
            CherryPickCandidate(two),
            CherryPickCandidate(applied, CherryPickLeftOut.ALREADY_APPLIED),
            CherryPickCandidate(wip),
        ),
        status: WorktreeStatus = WorktreeStatus(0, 0, 0, 0, null, null),
    ) = BranchCherryPick(
        branch = fix,
        currentBranch = "refs/heads/main",
        workingTree = "/repo",
        candidates = candidates,
        commitCount = candidates.size,
        repositoryState = RepositoryState.SAFE,
        isSequenceInProgress = false,
        status = status,
    )

    private fun state(
        selected: Set<String> = setOf(one.hash, two.hash),
        asOneCommit: Boolean = false,
        pick: BranchCherryPick? = pick(),
    ) = CherryPickBranchState(pick = pick, selected = selected, asOneCommit = asOneCommit)

    @Test
    fun `lists the commits, ticked or left out with why, and the command that runs`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(state()).use { dialog ->
                val texts = dialog.texts()

                assertTrue("Cherry-pick onto main" in texts, texts.toString())
                assertTrue(
                    "Copies 2 of the 3 commits of \"fix/unread-bar\" onto \"main\", each as a new commit." in texts,
                    texts.toString(),
                )
                for (subject in listOf(one, merge, two, applied, wip).map { it.shortMessage }) {
                    assertTrue(subject in texts, subject)
                }
                assertTrue("Merge commit, left out" in texts, texts.toString())
                assertTrue("Already applied" in texts, texts.toString())
                assertTrue("cd /repo\ngit cherry-pick ${one.hash} ${two.hash}" in texts, texts.toString())
                assertTrue(dialog.isEnabled("Cherry-pick 2 commits"))

                dialog.click("Cherry-pick 2 commits")

                assertEquals(listOf("confirm"), dialog.calls)
            }
        }

    @Test
    fun `every commit ticked says so, and one commit is singular`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(state(selected = setOf(one.hash, two.hash, wip.hash))).use { dialog ->
            assertTrue(
                "Copies the 3 commits of \"fix/unread-bar\" onto \"main\", each as a new commit." in dialog.texts(),
                dialog.texts().toString(),
            )
        }

        DialogScene(state(selected = setOf(one.hash), pick = pick(listOf(CherryPickCandidate(one))))).use { dialog ->
            assertTrue(
                "Copies the commit of \"fix/unread-bar\" onto \"main\" as a new commit." in dialog.texts(),
                dialog.texts().toString(),
            )
            assertTrue(dialog.isEnabled("Cherry-pick 1 commit"))
        }
    }

    @Test
    fun `as one commit, it stages the changes and shows the message for the commit field`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(state(asOneCommit = true)).use { dialog ->
                val texts = dialog.texts()

                assertTrue(
                    "Stages the changes of 2 of the 3 commits of \"fix/unread-bar\" in \"main\", for you to commit as one." in
                        texts,
                    texts.toString(),
                )
                assertTrue(
                    "fix/unread-bar\n\n- CAPS-1260: Count unread messages\n- CAPS-1260: Show the unread bar" in texts,
                    texts.toString(),
                )
                assertTrue(texts.any { it.startsWith("cd /repo\ngit cherry-pick --no-commit ${one.hash} ${two.hash}\n# ") })
            }
        }

    @Test
    fun `a click on a commit ticks it, one left out takes none, and As one commit switches`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(state()).use { dialog ->
                dialog.click(wip.shortMessage)
                dialog.click("As one commit: stage their changes together, for you to commit")

                assertEquals(listOf("toggle ${wip.hash}", "as one true"), dialog.calls)
                assertFalse(dialog.isEnabled(merge.shortMessage), "A merge commit can't be ticked")
            }
        }

    @Test
    fun `staged changes are refused, without the commands`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(state(pick = pick(status = WorktreeStatus(1, 2, 3, 0, null, null)))).use { dialog ->
            val texts = dialog.texts()

            assertTrue(
                "1 file has staged changes or conflicts. Commit, unstage or resolve it first." in texts,
                texts.toString(),
            )
            assertFalse(texts.any { it.startsWith("cd ") }, texts.toString())
            assertFalse(dialog.isEnabled("Cherry-pick 2 commits"))
        }
    }

    @Test
    fun `unstaged changes are a note, and nothing ticked disables the button`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(state(pick = pick(status = WorktreeStatus(0, 2, 0, 0, null, null)))).use { dialog ->
            assertTrue(
                "2 files have unstaged changes. git stops before a commit that changes one of them, and keeps your changes." in
                    dialog.texts(),
                dialog.texts().toString(),
            )
            assertTrue(dialog.isEnabled("Cherry-pick 2 commits"))
        }

        DialogScene(state(selected = emptySet())).use { dialog ->
            assertTrue("Tick the commits to pick." in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.isEnabled("Cherry-pick 0 commits"))
        }
    }

    @Test
    fun `while it reads, and when there's nothing to pick`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(CherryPickBranchState()).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Cherry-pick" in texts, texts.toString())
            assertTrue("Reading the branch’s commits…" in texts, texts.toString())
            assertFalse(dialog.isEnabled("Cherry-pick 0 commits"))
        }

        DialogScene(state(selected = emptySet(), pick = pick(candidates = emptyList()))).use { dialog ->
            assertTrue(
                "\"fix/unread-bar\" has no commits that \"main\" doesn’t have." in dialog.texts(),
                dialog.texts().toString(),
            )
        }
    }

    private inner class DialogScene(state: CherryPickBranchState) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 900, height = 900, density = Density(1f)) {
            AppTheme {
                CherryPickBranchDialogContent(
                    branch = fix,
                    state = state,
                    onToggle = { calls += "toggle $it" },
                    onAsOneCommitChanged = { calls += "as one $it" },
                    onConfirm = { calls += "confirm" },
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

        /** Whether the element [text] takes clicks: `PrimaryButton` isn't clickable at all while it's disabled. */
        fun isEnabled(text: String): Boolean = buttons(text).isNotEmpty()

        /** Clicks the clickable element whose text is [text]. */
        fun click(text: String) {
            checkNotNull(buttons(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
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
