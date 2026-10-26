// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.WorktreeList
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanFooter
import dev.app.leaf.domain.rewrite.PlanGroup
import dev.app.leaf.domain.rewrite.PlanPlace
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.PlanRowMarks
import dev.app.leaf.domain.rewrite.PlanStart
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.SquashMessageStart
import dev.app.leaf.domain.rewrite.planFooter
import dev.app.leaf.domain.rewrite.planRowMarks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.DiffEntry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How long the editor waits after a change before it checks the plan, so that a run of keys checks once. */
private val CHECK_DELAY = 150.milliseconds

/** A file that a commit of the plan changes, for the panel. */
data class PlanFile(
    val path: String,
    val change: DiffEntry.ChangeType,
)

/**
 * The commit editor while it shows (fork-only, step B5 of `docs/fork/commit-editor.md`).
 *
 * @param selected the hash of the row that the panel shows.
 * @param check the check's answer for [plan] as it is, or null while it runs.
 * @param lastCheck the last answer, which the rows keep showing while the check runs again.
 * @param onto the base's latest commit, offered as "Onto", or null when the plan can't move or the branch has it.
 * @param starts how each group with squashes started its message ("Start from"), by its first commit.
 * @param files the files of the commits that the panel showed, by hash; null while they load, empty when they couldn't.
 * @param currentTip the branch's tip as the tab last read it, null before it has.
 * @param focusRequests counts the times the panel's message should take the focus.
 * @param messageVersion counts the changes that can change the panel's message other than its own typing, so that the
 * field takes the plan's message then, and keeps what is typed otherwise.
 * @param isRunning while Start runs.
 */
data class CommitEditorState(
    val plan: RebasePlan,
    val selected: String,
    val check: PlanCheck? = null,
    val lastCheck: PlanCheck? = null,
    val onto: OntoBaseCheck? = null,
    val place: PlanPlace = PlanPlace(),
    val autostash: Boolean = false,
    val starts: Map<String, SquashMessageStart> = emptyMap(),
    val files: Map<String, List<PlanFile>?> = emptyMap(),
    val currentTip: String? = null,
    val focusRequests: Int = 0,
    val messageVersion: Int = 0,
    val isRunning: Boolean = false,
) {
    /** The footer: the check's answer when it has one, the last one for the button's label while it runs again. */
    val footer: PlanFooter by lazy {
        val shown = planFooter(plan, check ?: lastCheck, place, autostash, currentTip)

        shown.copy(isChecked = check != null, canStart = shown.canStart && check != null && !isRunning)
    }

    val marks: Map<String, PlanRowMarks> by lazy { planRowMarks(plan, check ?: lastCheck) }

    val selectedRow get() = plan.rows.firstOrNull { it.commit.hash == selected } ?: plan.rows.last()

    /** The group of the selected row, or null when it's dropped. */
    val selectedGroup: PlanGroup? get() = plan.groups.firstOrNull { group -> group.commits.any { it.hash == selected } }

    /** The message of [group]'s commit, as the panel shows it. */
    fun messageOf(group: PlanGroup): String = plan.messages[group.target.commit.hash]
        ?: plan.messageStart(group, starts[group.target.commit.hash] ?: SquashMessageStart.SUBJECTS)

    /** Whether the message of [group] was changed from the one it starts from. */
    fun isMessageChanged(group: PlanGroup): Boolean {
        val written = plan.messages[group.target.commit.hash] ?: return false
        val start = plan.messageStart(group, starts[group.target.commit.hash] ?: SquashMessageStart.SUBJECTS)

        return written.trim() != start.trim()
    }

    /** Whether the panel's Start from applies to [group]: it has rows whose messages are added. */
    fun hasMessageStart(group: PlanGroup): Boolean = group.members.any { it.action == PlanAction.SQUASH }
}

/** What the commit editor's view asks for (B5). */
sealed interface CommitEditorAction {
    data class Select(val hash: String) : CommitEditorAction
    data class SelectNext(val newer: Boolean) : CommitEditorAction
    data class SetAction(val hash: String, val action: PlanAction) : CommitEditorAction
    data class MoveSelected(val newer: Boolean) : CommitEditorAction

    /** A drag, by rows as the list shows them, newest first. */
    data class MoveShown(val from: Int, val to: Int) : CommitEditorAction
    data class SetMessage(val text: String) : CommitEditorAction
    data object ResetMessage : CommitEditorAction
    data class SetMessageStart(val start: SquashMessageStart) : CommitEditorAction
    data class SetOnto(val ontoLatest: Boolean) : CommitEditorAction
    data class SetAutostash(val autostash: Boolean) : CommitEditorAction
    data object PlaceFixups : CommitEditorAction
    data object Reset : CommitEditorAction
    data object Cancel : CommitEditorAction
    data object Start : CommitEditorAction
}

/**
 * The commit editor's state and what it does (fork-only, B5): the plan edited row by row, the check run again 150 ms
 * after the last change (the next change cancels it), the plans kept per branch while the branch's tip doesn't move
 * (decision 6), and Start, which rewrites without a worktree when the check says the plan doesn't stop (B3), and
 * replays it with `git rebase -i` when it does (B4).
 *
 * @param check the check of a plan, which never fails: a failure is a plan that may stop.
 * @param placeOf where a plan for a branch would replay, from the tab's worktree list.
 * @param ontoOf the base's latest commit that a plan of [BranchLine] can move onto.
 * @param filesOf the files that a commit changes, or null when they couldn't be read.
 * @param onRewritten after a plan rewrote the branch: the strip says what it did.
 */
class CommitEditorController(
    private val scope: CoroutineScope,
    private val branchLines: StateFlow<BranchLines>,
    private val worktrees: StateFlow<WorktreeList?>,
    private val check: suspend (RebasePlan) -> PlanCheck,
    private val placeOf: suspend (WorktreeList?, String) -> PlanPlace,
    private val ontoOf: suspend (BranchLine) -> OntoBaseCheck?,
    private val filesOf: suspend (Commit) -> List<PlanFile>?,
    private val run: (RebasePlan) -> Deferred<Either<BranchRewriteResult, AppError>>,
    private val start: (RebasePlan, autostash: Boolean) -> Deferred<Either<PlanRebaseOutcome, AppError>>,
    private val onRewritten: (BranchRewriteResult, RebasePlan, after: Int) -> Unit,
    private val onStarted: (RebasePlan) -> Unit = {},
    private val addCloseableView: (CloseableView) -> Unit = {},
    private val removeCloseableView: (CloseableView) -> Unit = {},
    private val checkDelay: Duration = CHECK_DELAY,
) {
    val state: StateFlow<CommitEditorState?>
        field = MutableStateFlow<CommitEditorState?>(null)

    /** The plans that were closed without being started, by branch: opening the editor again shows them. */
    private val kept = mutableMapOf<String, RebasePlan>()

    /**
     * Plans to check, the newest only: the collector cancels a check when the plan changes again. It replays the last
     * one, so that a plan sent before the collector started isn't lost; and, unlike a state flow, it takes the same plan
     * again, to check it again after a failed run.
     */
    private val toCheck = MutableSharedFlow<RebasePlan>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            toCheck.collectLatest { plan ->
                delay(checkDelay)

                val answer = check(plan)

                state.update { current ->
                    if (current != null && current.plan === plan) current.copy(check = answer, lastCheck = answer) else current
                }
            }
        }

        scope.launch {
            branchLines.collect { lines ->
                state.update { current -> current?.copy(currentTip = tipOf(lines, current.plan.line.branch)) }
            }
        }

        scope.launch {
            worktrees.collectLatest { list ->
                val branch = state.value?.plan?.line?.branch ?: return@collectLatest
                val place = placeOf(list, branch)

                state.update { current -> if (current?.plan?.line?.branch == branch) current.copy(place = place) else current }
            }
        }
    }

    /**
     * Opens the editor with [plan], or with the plan kept for its branch when that one is for the same commits and the
     * branch hasn't moved since.
     */
    fun open(plan: RebasePlan, useKept: Boolean = true) {
        val branch = plan.line.branch
        val keptPlan = kept[branch]?.takeIf { useKept && it.expectedTip == plan.expectedTip && it.original == plan.original }
        val shown = keptPlan ?: plan

        if (keptPlan == null) kept.remove(branch)

        state.value = CommitEditorState(
            plan = shown,
            selected = shown.rows.last().commit.hash,
            currentTip = tipOf(branchLines.value, branch),
        )
        addCloseableView(CloseableView.COMMIT_EDITOR)
        toCheck.tryEmit(shown)
        loadFiles(shown.rows.last().commit)

        scope.launch {
            val place = placeOf(worktrees.value, branch)
            state.update { current -> if (current?.plan?.line?.branch == branch) current.copy(place = place) else current }
        }

        if (shown.canMoveOnto) {
            scope.launch {
                val onto = ontoOf(shown.line)
                state.update { current -> if (current?.plan?.line?.branch == branch) current.copy(onto = onto) else current }
            }
        }
    }

    /** Cancel and Esc: the editor closes, and its plan is kept for the branch while it's changed. */
    fun cancel() {
        val current = state.value ?: return

        if (current.isRunning) return

        if (current.plan.isChanged) kept[current.plan.line.branch] = current.plan else kept.remove(current.plan.line.branch)

        close()
    }

    private fun close() {
        state.value = null
        removeCloseableView(CloseableView.COMMIT_EDITOR)
    }

    fun select(hash: String) {
        val current = state.value ?: return

        if (current.plan.rows.none { it.commit.hash == hash }) return

        state.value = current.copy(selected = hash)
        current.plan.rows.firstOrNull { it.commit.hash == hash }?.let { loadFiles(it.commit) }
    }

    /** ↑ ([newer]) or ↓ in the list, which shows the newest first. */
    fun selectNext(newer: Boolean) {
        val current = state.value ?: return
        val rows = current.plan.rows
        val at = rows.indexOfFirst { it.commit.hash == current.selected }
        val next = rows.getOrNull(if (newer) at + 1 else at - 1) ?: return

        select(next.commit.hash)
    }

    /** P R S F E D, or the row's menu: [hash]'s row does [action]. A row that turns Reword or joins focuses the message. */
    fun setAction(hash: String, action: PlanAction) {
        val current = state.value ?: return
        val changed = current.plan.withAction(hash, action)
        val focuses = changed !== current.plan && (action == PlanAction.REWORD || action.joins)

        updatePlan(changed, selected = hash, focusMessage = focuses)
    }

    /** ⌥↑ ([newer]) or ⌥↓: the selected row moves one place. */
    fun moveSelected(newer: Boolean) {
        val current = state.value ?: return

        updatePlan(current.plan.movedOne(current.selected, towardsNewer = newer))
    }

    /** A drag in the list, by rows as it shows them, newest first. */
    fun moveShown(from: Int, to: Int) {
        val current = state.value ?: return
        val last = current.plan.rows.lastIndex

        updatePlan(current.plan.moved(last - from, last - to))
    }

    /** The panel's message for the group of the selected row. */
    fun setMessage(text: String) {
        val current = state.value ?: return
        val group = current.selectedGroup ?: return

        updatePlan(current.plan.withMessage(group.target.commit.hash, text), isTyping = true)
    }

    /** The message of the selected row's group starts again from what it started from. */
    fun resetMessage() {
        val current = state.value ?: return
        val group = current.selectedGroup ?: return

        updatePlan(current.plan.withoutMessage(group.target.commit.hash))
    }

    /** The panel's Start from for the selected row's group: its message starts again from it. */
    fun setMessageStart(start: SquashMessageStart) {
        val current = state.value ?: return
        val group = current.selectedGroup ?: return
        val hash = group.target.commit.hash

        state.value = current.copy(starts = current.starts + (hash to start), messageVersion = current.messageVersion + 1)
        updatePlan(current.plan.withoutMessage(hash))
    }

    /** "Onto": the latest base's commit, or back to where the branch started. */
    fun setOnto(ontoLatest: Boolean) {
        val current = state.value ?: return
        val onto = if (ontoLatest) current.onto?.onto ?: return else null

        updatePlan(current.plan.withOnto(onto))
    }

    fun setAutostash(autostash: Boolean) {
        state.update { it?.copy(autostash = autostash) }
    }

    /** "Put them there": the `fixup!` commits go under the commits they name. */
    fun placeFixups() {
        val current = state.value ?: return

        updatePlan(current.plan.withFixupsPlaced())
    }

    /**
     * Reset the plan: every row back to Pick, in its place, with no new message. After the branch moved, a new plan of
     * the branch as it is now, from the same commit when the branch still has it.
     */
    fun reset() {
        val current = state.value ?: return
        val plan = current.plan
        val line = branchLines.value.lines[plan.line.branch]

        if (line == null || line.commits.isEmpty() || line.commits.first().hash == plan.expectedTip) {
            state.value = current.copy(starts = emptyMap())
            updatePlan(plan.reset())

            return
        }

        val from = line.commits.indexOfFirst { it.hash == plan.original.first().hash }.takeIf { it >= 0 } ?: line.commits.lastIndex
        val fresh = RebasePlan.of(line, line.commits.subList(0, from + 1).reversed())

        kept.remove(plan.line.branch)
        state.value = current.copy(
            plan = fresh,
            selected = fresh.rows.last().commit.hash,
            check = null,
            starts = emptyMap(),
            onto = null,
            messageVersion = current.messageVersion + 1,
        )
        toCheck.tryEmit(fresh)

        if (fresh.canMoveOnto) {
            scope.launch {
                val onto = ontoOf(fresh.line)
                state.update { now -> if (now?.plan === fresh) now.copy(onto = onto) else now }
            }
        }
    }

    /** Start (⌘↵): rewrites, or starts the rebase, as the footer says. A failure is the task's, which shows it. */
    fun start() {
        val current = state.value ?: return
        val footer = current.footer

        if (!footer.canStart) return

        state.value = current.copy(isRunning = true)

        scope.launch {
            val plan = current.plan

            when (footer.start) {
                PlanStart.REWRITE -> when (val result = run(plan).await()) {
                    is Either.Ok -> {
                        finish(plan)
                        onRewritten(result.value, plan, footer.after)
                    }

                    is Either.Err -> failed(plan)
                }

                PlanStart.REBASE -> when (val outcome = start(plan, current.autostash).await()) {
                    is Either.Ok -> {
                        finish(plan)

                        when (val value = outcome.value) {
                            is PlanRebaseOutcome.Done -> onRewritten(value.result, plan, footer.after)
                            is PlanRebaseOutcome.Stopped -> onStarted(plan)
                        }
                    }

                    is Either.Err -> failed(plan)
                }
            }
        }
    }

    private fun finish(plan: RebasePlan) {
        kept.remove(plan.line.branch)

        if (state.value?.plan === plan) close()
    }

    /** After a failure, the editor stays, and checks the plan again: what failed may have changed what it says. */
    private fun failed(plan: RebasePlan) {
        state.update { current -> if (current?.plan === plan) current.copy(isRunning = false, check = null) else current }
        toCheck.tryEmit(plan)
    }

    private fun updatePlan(
        plan: RebasePlan,
        selected: String? = null,
        focusMessage: Boolean = false,
        isTyping: Boolean = false,
    ) {
        val current = state.value ?: return

        if (current.isRunning) return

        val samePlan = plan == current.plan
        val shown = if (samePlan) current.plan else plan

        state.value = current.copy(
            plan = shown,
            selected = selected ?: current.selected,
            check = if (samePlan) current.check else null,
            focusRequests = current.focusRequests + if (focusMessage) 1 else 0,
            messageVersion = current.messageVersion + if (isTyping || samePlan) 0 else 1,
        )

        if (!samePlan) toCheck.tryEmit(shown)
    }

    private fun loadFiles(commit: Commit) {
        if (state.value?.files?.containsKey(commit.hash) == true) return

        state.update { it?.copy(files = it.files + (commit.hash to null)) }

        scope.launch {
            val files = filesOf(commit).orEmpty()
            state.update { it?.copy(files = it.files + (commit.hash to files)) }
        }
    }

    private fun tipOf(lines: BranchLines, branch: String): String? {
        if (lines.lines.isEmpty()) return null

        return lines.lines[branch]?.commits?.firstOrNull()?.hash.orEmpty()
    }
}
