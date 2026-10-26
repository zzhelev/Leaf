// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanPlace
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.PlanStart
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.RewriteKind
import dev.app.leaf.domain.rewrite.SquashMessageStart
import dev.app.leaf.ui.RewriteFixtures
import dev.app.leaf.ui.RewriteFixtures.BRANCH
import dev.app.leaf.ui.RewriteFixtures.add
import dev.app.leaf.ui.RewriteFixtures.cache
import dev.app.leaf.ui.RewriteFixtures.wip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The commit editor's state and what it does (fork-only, B5 of `docs/fork/commit-editor.md`), with fakes for the check,
 * the place, Onto and the runs: the check after each change and only its last answer, the plans kept per branch, the
 * focus and the message field's version, and Start, which rewrites or starts a rebase as the check says.
 */
class CommitEditorControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = MutableStateFlow(RewriteFixtures.lines())
    private val worktrees = MutableStateFlow<WorktreeList?>(null)
    private val checked = mutableListOf<RebasePlan>()
    private var answer: (RebasePlan) -> PlanCheck = { PlanCheck(emptyList(), null) }
    private var gate: CompletableDeferred<Unit>? = null
    private var place = PlanPlace(worktreeName = "leaf", isTabs = true)
    private val runs = mutableListOf<RebasePlan>()
    private val starts = mutableListOf<Pair<RebasePlan, Boolean>>()
    private var runResult: Either<BranchRewriteResult, AppError> = Either.Ok(result())
    private var startResult: Either<PlanRebaseOutcome, AppError> = Either.Ok(PlanRebaseOutcome.Stopped("/repo/.git", "/repo", true))
    private val rewritten = mutableListOf<Pair<RebasePlan, Int>>()
    private val started = mutableListOf<RebasePlan>()
    private val closeables = mutableListOf<String>()
    private val onto = OntoBaseCheck.Clean(OntoBase(RewriteFixtures.MAIN, "5".repeat(40)), newCommits = 2)

    private val controller = CommitEditorController(
        scope = scope,
        branchLines = lines,
        worktrees = worktrees,
        check = { plan ->
            gate?.await()
            synchronized(checked) { checked += plan }
            answer(plan)
        },
        placeOf = { _, _ -> place },
        ontoOf = { onto },
        filesOf = { commit -> listOf(PlanFile("src/${commit.shortHash}.kt", org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY)) },
        run = { plan -> runs += plan; CompletableDeferred(runResult) },
        start = { plan, autostash -> starts += plan to autostash; CompletableDeferred(startResult) },
        onRewritten = { _, plan, after -> rewritten += plan to after },
        onStarted = { started += it },
        addCloseableView = { closeables += "+$it" },
        removeCloseableView = { closeables += "-$it" },
        checkDelay = 10.milliseconds,
    )

    private val plan = RebasePlan.of(RewriteFixtures.line(), listOf(add, wip, cache))

    private fun result() = BranchRewriteResult(BRANCH, RewriteKind.REBASE, cache.hash, "9".repeat(40), emptyList())

    @AfterEach
    fun tearDown() = scope.cancel()

    private suspend fun awaitState(predicate: (CommitEditorState) -> Boolean): CommitEditorState =
        withTimeout(5_000) { controller.state.first { it != null && predicate(it) }!! }

    private suspend fun awaitChecked() = awaitState { it.check != null }

    @Test
    fun `opening shows the plan with the newest commit selected, checks it, and reads where it would run`(): Unit = runBlocking {
        controller.open(plan)

        val state = awaitState { it.check != null && it.place == place && it.onto != null && it.files[cache.hash] != null }

        assertEquals(cache.hash, state.selected)
        assertEquals(listOf("+COMMIT_EDITOR"), closeables)
        assertEquals(listOf(PlanFile("src/c0ffee1.kt", org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY)), state.files[cache.hash])
        assertEquals(cache.hash, state.currentTip)
        assertFalse(state.footer.canStart)
    }

    @Test
    fun `each change is checked again, and an answer for an older plan never shows`(): Unit = runBlocking {
        controller.open(plan)
        awaitChecked()

        gate = CompletableDeferred()
        controller.setAction(wip.hash, PlanAction.DROP)
        val first = controller.state.value!!.plan
        controller.setAction(add.hash, PlanAction.REWORD)
        val second = controller.state.value!!.plan

        assertNull(controller.state.value!!.check)
        assertFalse(controller.state.value!!.footer.canStart)

        gate!!.complete(Unit)
        val state = awaitChecked()

        assertSame(second, state.plan)
        // The first change's check was cancelled by the second, before it answered
        assertTrue(synchronized(checked) { checked.none { it === first } })
        assertTrue(state.footer.canStart)
    }

    @Test
    fun `a check that stops makes Start a rebase, and the last answer stays on the rows while it runs again`(): Unit =
        runBlocking {
            answer = { PlanCheck(emptyList(), PlanStop.Conflict(cache.hash, listOf("Cache.kt"))) }
            controller.open(plan)
            awaitChecked()

            controller.setAction(wip.hash, PlanAction.DROP)
            val checking = controller.state.value!!

            assertNull(checking.check)
            assertEquals(listOf("Cache.kt"), checking.marks.getValue(cache.hash).stopFiles)

            val state = awaitChecked()

            assertEquals(PlanStart.REBASE, state.footer.start)
            assertTrue(state.footer.canStart)
        }

    @Test
    fun `a row that turns Reword or joins focuses the message, and only changes other than typing renew the field`(): Unit =
        runBlocking {
            controller.open(plan)
            awaitChecked()

            controller.setAction(cache.hash, PlanAction.REWORD)
            assertEquals(1, controller.state.value!!.focusRequests)
            val version = controller.state.value!!.messageVersion

            controller.setMessage("Cache the files\n")
            assertEquals(version, controller.state.value!!.messageVersion)
            assertEquals("Cache the files\n", controller.state.value!!.plan.messages[cache.hash])

            controller.setAction(wip.hash, PlanAction.SQUASH)
            assertEquals(2, controller.state.value!!.focusRequests)
            assertTrue(controller.state.value!!.messageVersion > version)

            controller.setAction(add.hash, PlanAction.PICK)
            assertEquals(2, controller.state.value!!.focusRequests)
        }

    @Test
    fun `Start from and Put the message back start the group's message over`(): Unit = runBlocking {
        controller.open(plan)
        awaitChecked()

        controller.setAction(cache.hash, PlanAction.SQUASH)
        controller.select(wip.hash)
        controller.setMessage("Mine\n")
        val group = controller.state.value!!.selectedGroup!!

        assertTrue(controller.state.value!!.isMessageChanged(group))

        val before = controller.state.value!!.messageVersion
        controller.setMessageStart(SquashMessageStart.ALL)

        assertTrue(controller.state.value!!.messageVersion > before)
        assertFalse(controller.state.value!!.isMessageChanged(controller.state.value!!.selectedGroup!!))
        assertTrue("Cache the parsed files" in controller.state.value!!.messageOf(controller.state.value!!.selectedGroup!!))

        controller.setMessage("Mine again\n")
        controller.resetMessage()

        assertNull(controller.state.value!!.plan.messages[wip.hash])

        // Without a message written, the plan stays as it is, and the field still takes the new start
        val untyped = controller.state.value!!.messageVersion
        controller.setMessageStart(SquashMessageStart.OLDEST)

        assertTrue(controller.state.value!!.messageVersion > untyped)
    }

    @Test
    fun `↑↓ select, ⌥↑↓ move the selected row, and a drag moves by the rows as shown, newest first`(): Unit = runBlocking {
        controller.open(plan)
        awaitChecked()

        controller.selectNext(newer = false)
        assertEquals(wip.hash, controller.state.value!!.selected)
        controller.selectNext(newer = true)
        controller.selectNext(newer = true)
        assertEquals(cache.hash, controller.state.value!!.selected)

        controller.moveSelected(newer = false)
        assertEquals(listOf(add, cache, wip).map { it.hash }, controller.state.value!!.plan.rows.map { it.commit.hash })

        // Shown newest first: wip, cache, add. The top row to the bottom
        controller.moveShown(0, 2)
        assertEquals(listOf(wip, add, cache).map { it.hash }, controller.state.value!!.plan.rows.map { it.commit.hash })
    }

    @Test
    fun `a plan closed with Cancel opens again as it was, for the same commits, and Reset starts it over`(): Unit = runBlocking {
        controller.open(plan)
        awaitChecked()
        controller.setAction(wip.hash, PlanAction.DROP)
        controller.cancel()

        assertNull(controller.state.value)
        assertEquals(listOf("+COMMIT_EDITOR", "-COMMIT_EDITOR"), closeables)

        controller.open(plan)
        assertEquals(PlanAction.DROP, controller.state.value!!.plan.rows[1].action)

        controller.reset()
        assertFalse(controller.state.value!!.plan.isChanged)

        // A plan of other commits doesn't take the kept one
        controller.setAction(wip.hash, PlanAction.DROP)
        controller.cancel()
        controller.open(RebasePlan.of(RewriteFixtures.line(), listOf(wip, cache)))
        assertFalse(controller.state.value!!.plan.isChanged)
    }

    @Test
    fun `a branch that moved keeps Start disabled, and Reset makes a plan of its commits now`(): Unit = runBlocking {
        controller.open(plan.withAction(wip.hash, PlanAction.DROP))
        awaitChecked()

        val newTip = RewriteFixtures.commit("d00d1e5", "More", parent = cache.hash.take(7))
        lines.value = BranchLines(
            base = RewriteFixtures.MAIN,
            lines = mapOf(BRANCH to RewriteFixtures.line().copy(commits = listOf(newTip, cache, wip, add))),
        )

        val moved = awaitState { it.currentTip == newTip.hash }

        assertFalse(moved.footer.canStart)

        controller.reset()

        assertEquals(newTip.hash, controller.state.value!!.plan.expectedTip)
        assertEquals(4, controller.state.value!!.plan.rows.size)

        // The kept plan was for the old tip
        controller.cancel()
        controller.open(plan)
        assertFalse(controller.state.value!!.plan.isChanged)
    }

    @Test
    fun `Onto moves the plan onto the base's latest commit, and back`(): Unit = runBlocking {
        controller.open(plan)
        awaitState { it.onto != null && it.check != null }

        controller.setOnto(true)
        assertEquals(onto.onto, controller.state.value!!.plan.onto)

        controller.setOnto(false)
        assertNull(controller.state.value!!.plan.onto)
    }

    @Test
    fun `Start rewrites a plan that doesn't stop, closes and says what it did`(): Unit = runBlocking {
        controller.open(plan.withAction(wip.hash, PlanAction.DROP))
        awaitChecked()

        controller.start()
        withTimeout(5_000) { controller.state.first { it == null } }

        assertEquals(1, runs.size)
        assertEquals(listOf(2), rewritten.map { it.second })
        assertTrue(starts.isEmpty())

        // Nothing is kept once it ran
        controller.open(plan)
        assertFalse(controller.state.value!!.plan.isChanged)
    }

    @Test
    fun `Start replays a plan that stops with the box's choice, and closes when it stopped`(): Unit = runBlocking {
        answer = { PlanCheck(emptyList(), PlanStop.Edit(wip.hash)) }
        place = place.copy(uncommitted = 2)
        controller.open(plan.withAction(wip.hash, PlanAction.EDIT))
        awaitState { it.check != null && it.place == place }

        assertFalse(controller.state.value!!.footer.canStart)

        controller.setAutostash(true)
        controller.start()
        withTimeout(5_000) { controller.state.first { it == null } }

        assertEquals(listOf(true), starts.map { it.second })
        assertEquals(1, started.size)
        assertTrue(rewritten.isEmpty())
    }

    @Test
    fun `a rebase that runs through says what it did, like a rewrite`(): Unit = runBlocking {
        answer = { PlanCheck(emptyList(), PlanStop.Unknown(dev.app.leaf.domain.rewrite.UnknownStopReason.OLD_GIT)) }
        startResult = Either.Ok(PlanRebaseOutcome.Done(result()))
        controller.open(plan.withAction(wip.hash, PlanAction.DROP))
        awaitChecked()

        controller.start()
        withTimeout(5_000) { controller.state.first { it == null } }

        assertEquals(1, rewritten.size)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a failure leaves the editor open with its plan, and checks it again`(): Unit = runBlocking {
        runResult = Either.Err(GenericError("Branch moved"))
        controller.open(plan.withAction(wip.hash, PlanAction.DROP))
        awaitChecked()
        val checks = synchronized(checked) { checked.size }

        controller.start()

        val state = awaitState { !it.isRunning && it.check != null && synchronized(checked) { checked.size } > checks }

        assertNotNull(state)
        assertEquals(PlanAction.DROP, state.plan.rows[1].action)
        assertTrue(rewritten.isEmpty())
    }

    @Test
    fun `a plan opened before the controller's coroutines start is still checked`(): Unit = runBlocking {
        val held = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute { held.await() }
        val lateScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val late = CommitEditorController(
            scope = lateScope,
            branchLines = lines,
            worktrees = worktrees,
            check = { PlanCheck(emptyList(), null) },
            placeOf = { _, _ -> place },
            ontoOf = { null },
            filesOf = { emptyList() },
            run = { CompletableDeferred(runResult) },
            start = { _, _ -> CompletableDeferred(startResult) },
            onRewritten = { _, _, _ -> },
            checkDelay = 1.milliseconds,
        )

        try {
            late.open(plan)
            held.countDown()

            assertNotNull(withTimeout(5_000) { late.state.first { it?.check != null } })
        } finally {
            lateScope.cancel()
            executor.shutdownNow()
        }
    }

    @Test
    fun `Start does nothing while the check runs`(): Unit = runBlocking {
        gate = CompletableDeferred()
        controller.open(plan.withAction(wip.hash, PlanAction.DROP))

        controller.start()

        assertTrue(runs.isEmpty())
        gate!!.complete(Unit)
    }
}
