// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchDeletionPlan
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.branches.BranchesDeletionRequest
import dev.app.leaf.domain.branches.copiesToDelete
import dev.app.leaf.domain.branches.offersCopies
import dev.app.leaf.domain.branches.toDelete
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.remote.CHECK_AGAIN_EVERY
import dev.app.leaf.domain.remote.CHECK_ROUNDS
import dev.app.leaf.domain.remote.STOP_WAITING_AFTER
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.DeleteBranchesUseCase
import dev.app.leaf.domain.usecases.GetBranchDeletionPlanUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the "Delete branches" dialog (fork-only, upstream issue 116).
 *
 * @param plan what the dialog read when it opened, null while it reads it or when it couldn't.
 * @param checked the full names of the branches that the user checked.
 * @param result what deleting did to each branch, once it ran. The dialog then shows it instead of the list.
 * @param error why the plan couldn't be read, or why nothing could be deleted: the repository couldn't be opened, or
 * for a remote's branches, git couldn't run or reach the server.
 * @param wasStopped the user stopped git while it deleted on a server, after which the server may have deleted some.
 * @param deletesCopies the local branches' copies on their remotes go too, those that can ([BranchDeletionPlan.copies]).
 * @param deletingOn the remotes whose server git is deleting on: null while nothing runs there.
 * @param canStopWaiting git has been deleting on the server for [STOP_WAITING_AFTER], and the dialog offers "Stop
 * waiting". Stopping can't call back a request that the server has, which it carries out whatever git does, so it only
 * frees the dialog from a slow or hung connection, and it isn't offered between two local branches.
 * @param isChecking after a stop, the server is asked what it still has, and nothing is shown yet.
 * @param isRechecking after a stop, the result shows what the server had, and it's asked again, as one that was still
 * running a hook when git stopped may delete them a moment later.
 */
data class DeleteBranchesState(
    val request: BranchesDeletionRequest,
    val plan: BranchDeletionPlan? = null,
    val checked: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val result: BranchesDeletion? = null,
    val error: AppError? = null,
    val wasStopped: Boolean = false,
    val deletesCopies: Boolean = false,
    val deletingOn: String? = null,
    val isChecking: Boolean = false,
    val isRechecking: Boolean = false,
    val canStopWaiting: Boolean = false,
) {
    /** The branches that confirming deletes, in the dialog's order. */
    val toDelete: List<BranchToDelete>
        get() = plan?.toDelete(checked).orEmpty()

    /** The copies on their remotes that go with them, by the local branch's full name: none unless [deletesCopies]. */
    val copiesToDelete: Map<String, BranchCopy>
        get() = if (deletesCopies) plan?.copiesToDelete(checked).orEmpty() else emptyMap()

    /** The copies that go with the checked branches if the user asks for them, whatever [deletesCopies] says. */
    val copiesOffered: Map<String, BranchCopy>
        get() = plan?.copiesToDelete(checked).orEmpty()

    val canConfirm: Boolean
        get() = plan != null && result == null && !isDeleting && !wasStopped && toDelete.isNotEmpty()

    /**
     * Whether one of the checked branches isn't merged, its commits on other branches or not, which for a remote's may
     * have a pull request open.
     */
    val checksUnmerged: Boolean
        get() = plan?.let { plan ->
            plan.rows.any { it.branch.name in checked && plan.kindOf(it) !is BranchDeletionKind.Merged }
        } == true

    /** The list can't change while it deletes, nor once the result is shown, nor after a stop, which may have deleted. */
    private val isLocked: Boolean
        get() = plan == null || isDeleting || result != null || wasStopped || isChecking

    /** Asks for the checked branches' copies on their remotes to go too, or not. Only when the dialog offers them. */
    fun withDeletesCopies(on: Boolean): DeleteBranchesState =
        if (isLocked || plan?.offersCopies != true) this else copy(deletesCopies = on)

    /** [name] checked or unchecked. A branch that can't be deleted, and a name the list doesn't have, stay as they are. */
    fun withChecked(name: String, isChecked: Boolean): DeleteBranchesState {
        val plan = plan ?: return this
        val info = plan.rows.firstOrNull { it.branch.name == name }

        return when {
            info == null || isLocked || !plan.canCheck(info) -> this
            isChecked -> copy(checked = checked + name)
            else -> copy(checked = checked - name)
        }
    }

    /** "All": every branch that can be deleted. */
    fun withAllChecked(): DeleteBranchesState = checkedWhere { plan, info -> plan.canCheck(info) }

    /** "Safe": the ones checked when the dialog opens, which lose nothing and, for a remote's, don't look long-lived. */
    fun withSafeChecked(): DeleteBranchesState =
        checkedWhere { plan, info -> plan.canCheck(info) && plan.isCheckedAtFirst(info) }

    fun withNoneChecked(): DeleteBranchesState = checkedWhere { _, _ -> false }

    private fun checkedWhere(predicate: (BranchDeletionPlan, BranchDeletionInfo) -> Boolean): DeleteBranchesState {
        val plan = plan ?: return this

        return if (isLocked) {
            this
        } else {
            copy(checked = plan.rows.filter { predicate(plan, it) }.map { it.branch.name }.toSet())
        }
    }
}

/** Whether the server still had a branch, or a copy, that a stopped delete was deleting. */
private val BranchesDeletion.hasStillOnRemote: Boolean
    get() = (outcomes + copies.values).any { it is BranchDeletionOutcome.Kept && it.reason == BranchKept.StillOnRemote }

/**
 * Reads what deleting [request]'s branches would do, lets the user check them, deletes the checked ones and keeps the
 * result for the dialog to show. It reads again when it opens, as the tab's branch list may be stale.
 */
class DeleteBranchesViewModel @AssistedInject constructor(
    private val getBranchDeletionPlanUseCase: GetBranchDeletionPlanUseCase,
    private val deleteBranchesUseCase: DeleteBranchesUseCase,
    private val appSettingsService: AppSettingsService,
    @Assisted private val request: BranchesDeletionRequest,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(request: BranchesDeletionRequest): DeleteBranchesViewModel
    }

    val state: StateFlow<DeleteBranchesState>
        field = MutableStateFlow(DeleteBranchesState(request))

    init {
        viewModelScope.launch { readPlan() }

        // "Stop waiting" once git has been at it on the server for a while, and no more once it's done
        viewModelScope.launch {
            state.map { it.deletingOn }.distinctUntilChanged().collectLatest { on ->
                if (on == null) {
                    state.update { it.copy(canStopWaiting = false) }
                } else {
                    delay(STOP_WAITING_AFTER)
                    state.update { if (it.deletingOn != null) it.copy(canStopWaiting = true) else it }
                }
            }
        }
    }

    private suspend fun readPlan() {
        when (val result = getBranchDeletionPlanUseCase(request)) {
            is Either.Err -> state.update { it.copy(error = result.error) }
            is Either.Ok -> state.update {
                it.copy(plan = result.value, checked = result.value.checkedAtFirst(), error = null)
            }
        }
    }

    /**
     * Turns "Use git for remote operations" on, which deleting a remote's branches needs
     * ([dev.app.leaf.domain.errors.RemoteBranchesNeedGitError]), then reads the list, or lets the user delete again.
     */
    fun useGitForRemoteOperations() {
        viewModelScope.launch {
            appSettingsService.setConfiguration(AppConfig.RemoteOperationsWithGit(true))

            if (state.value.plan == null) {
                readPlan()
            } else {
                state.update { it.copy(error = null) }
            }
        }
    }

    fun setChecked(name: String, isChecked: Boolean) = state.update { it.withChecked(name, isChecked) }

    fun checkAll() = state.update { it.withAllChecked() }

    fun checkSafe() = state.update { it.withSafeChecked() }

    fun checkNone() = state.update { it.withNoneChecked() }

    fun setDeletesCopies(on: Boolean) = state.update { it.withDeletesCopies(on) }

    private var deleting: Job? = null
    private var checking: Job? = null

    /** Deletes the checked branches, and their copies when asked. The dialog stays open and shows what happened to each. */
    fun delete() {
        val current = state.value

        if (!current.canConfirm) return

        val remote = current.request.remote
        val copies = current.copiesToDelete
        // What the local branches' delete did, kept for a stop while their copies are deleted
        var local: BranchesDeletion? = null

        state.update { it.copy(isDeleting = true, error = null, deletingOn = remote) }

        deleting = viewModelScope.launch {
            try {
                val result = when {
                    remote != null -> deleteBranchesUseCase(current.toDelete, remote)
                    copies.isEmpty() -> deleteBranchesUseCase(current.toDelete)
                    else -> deleteBranchesUseCase.withCopies(current.toDelete, copies) { deleted ->
                        local = deleted
                        val remotes = copies.filterKeys { name -> deleted.deleted.any { it.name == name } }
                            .values.map { it.remote }.distinct()

                        if (remotes.isNotEmpty()) {
                            state.update { it.copy(deletingOn = remotes.joinToString(", ")) }
                        }
                    }
                }

                when (result) {
                    is Either.Err -> state.update { it.copy(isDeleting = false, deletingOn = null, error = result.error) }
                    is Either.Ok -> state.update { it.copy(isDeleting = false, deletingOn = null, result = result.value) }
                }
            } catch (e: CancellationException) {
                state.update { it.copy(isDeleting = false, deletingOn = null, wasStopped = true, isChecking = true) }
                checkAfterStop(remote, current.toDelete, local, copies)
                throw e
            }
        }
    }

    /**
     * Deletes anyway the branches, and the copies, that someone pushed to since the fetch, as the result shows them: each
     * at the commit the server has now, its lease, so that another push meanwhile still keeps it. Their outcomes take
     * the place of the old ones in the result.
     */
    fun deleteAnyway() {
        val current = state.value
        val before = current.result ?: return
        val remote = current.request.remote
        val branches = if (remote != null) before.changedOnRemote else emptyList()
        // The copies as the plan read them, at the server's commit
        val copies = before.changedCopies.mapNotNull { (local, changed) ->
            current.plan?.copies?.get(local)?.let { copy -> local to copy.copy(info = copy.info.copy(branch = changed.second)) }
        }.toMap()

        if (current.isDeleting || current.isChecking || current.isRechecking || (branches.isEmpty() && copies.isEmpty())) return

        val on = remote ?: copies.values.map { it.remote }.distinct().joinToString(", ")

        state.update { it.copy(isDeleting = true, deletingOn = on, error = null) }

        deleting = viewModelScope.launch {
            try {
                var result = before

                if (remote != null) {
                    when (val deleted = deleteBranchesUseCase(branches, remote)) {
                        is Either.Err -> {
                            state.update { it.copy(isDeleting = false, deletingOn = null, error = deleted.error) }
                            return@launch
                        }

                        is Either.Ok -> result = result.withOutcomes(deleted.value.outcomes, deleted.value.remoteMessage)
                    }
                }

                for ((copyRemote, group) in copies.entries.groupBy { it.value.remote }) {
                    val toDelete = group.map { it.value.info.branch }.distinctBy { it.name }

                    result = when (val deleted = deleteBranchesUseCase(toDelete, copyRemote)) {
                        is Either.Err -> result.copy(copyErrors = result.copyErrors + (copyRemote to deleted.error))
                        is Either.Ok -> {
                            val byName = deleted.value.outcomes.associateBy { it.branch.name }
                            val updated = group.mapNotNull { (local, copy) -> byName[copy.info.branch.name]?.let { local to it } }

                            result.copy(copies = result.copies + updated, copyErrors = result.copyErrors - copyRemote)
                                .withOutcomes(emptyList(), deleted.value.remoteMessage)
                        }
                    }
                }

                state.update { it.copy(isDeleting = false, deletingOn = null, result = result) }
            } catch (e: CancellationException) {
                state.update { it.copy(isDeleting = false, deletingOn = null, wasStopped = true, isChecking = true) }
                checkAfterStop(remote, branches, before, copies)
                throw e
            }
        }
    }

    /**
     * Asks the server what a stopped delete left there: for a remote's branches, which [branches] it still has; for
     * [copies], which of those whose [local] branch went. [local] is also the result that was shown before, when
     * deleting anyway, which the answer updates. The dialog shows it as a result at once, and while the server still has
     * some, asks again every [CHECK_AGAIN_EVERY], [CHECK_ROUNDS] times at most: a server that was running a hook when git
     * stopped goes on, and deletes them a moment later. When the server can't be asked at first, the dialog says that
     * some may be gone.
     */
    private fun checkAfterStop(
        remote: String?,
        branches: List<BranchToDelete>,
        local: BranchesDeletion?,
        copies: Map<String, BranchCopy>,
    ) {
        checking = viewModelScope.launch {
            try {
                for (round in 1..CHECK_ROUNDS) {
                    if (round > 1) delay(CHECK_AGAIN_EVERY)

                    val (result, error) = checkOnce(remote, branches, local, copies)

                    if (result == null) {
                        // Asked again, the last answer stays; asked first, the dialog says some may be gone
                        state.update {
                            it.copy(isChecking = false, isRechecking = false, error = if (it.result == null) error else it.error)
                        }

                        return@launch
                    }

                    // A remote that can't be asked any more leaves the last answer
                    if (round > 1 && result.copyErrors.isNotEmpty()) {
                        state.update { it.copy(isRechecking = false) }

                        return@launch
                    }

                    val again = result.hasStillOnRemote && round < CHECK_ROUNDS

                    state.update { it.copy(isChecking = false, isRechecking = again, result = result) }

                    if (!again) return@launch
                }
            } catch (e: CancellationException) {
                // "Stop checking": what's left is what the user saw, the local branches' result included
                state.update {
                    it.copy(isChecking = false, isRechecking = false, result = (it.result ?: local)?.copy(wasStopped = true))
                }
                throw e
            }
        }
    }

    /** One look at what the server has: the result, or why there is none. */
    private suspend fun checkOnce(
        remote: String?,
        branches: List<BranchToDelete>,
        local: BranchesDeletion?,
        copies: Map<String, BranchCopy>,
    ): Pair<BranchesDeletion?, AppError?> = when {
        remote != null -> when (val checked = deleteBranchesUseCase.afterStop(remote, branches)) {
            is Either.Err -> null to checked.error
            is Either.Ok -> (local?.withOutcomes(checked.value) ?: BranchesDeletion(checked.value)).copy(wasStopped = true) to null
        }

        local != null -> checkedCopies(local, copies) to null

        else -> null to null
    }

    /** [local] with what each remote still has of the [copies] of its deleted branches, or why it can't tell. */
    private suspend fun checkedCopies(local: BranchesDeletion, copies: Map<String, BranchCopy>): BranchesDeletion {
        val deleted = local.deleted.map { it.name }.toSet()
        val outcomes = mutableMapOf<String, BranchDeletionOutcome>()
        val errors = mutableMapOf<String, AppError>()

        for ((remote, group) in copies.filterKeys { it in deleted }.entries.groupBy { it.value.remote }) {
            val toCheck = group.map { it.value.info.branch }.distinctBy { it.name }

            when (val checked = deleteBranchesUseCase.afterStop(remote, toCheck)) {
                is Either.Err -> errors[remote] = checked.error
                is Either.Ok -> {
                    val byName = checked.value.associateBy { it.branch.name }

                    for ((localName, copy) in group) {
                        byName[copy.info.branch.name]?.let { outcomes[localName] = it }
                    }
                }
            }
        }

        return local.copy(
            copies = local.copies + outcomes,
            copyErrors = local.copyErrors - copies.values.map { it.remote }.toSet() + errors,
            wasStopped = true,
        )
    }

    /**
     * Stops waiting for git while it deletes on a server: git is stopped, the server may still delete what it was asked
     * to, and it's asked afterwards what it has.
     */
    fun stopDeleting() {
        if (state.value.deletingOn == null || !state.value.canStopWaiting) return

        deleting?.cancel()
    }

    /** Stops asking the server what a stopped delete left there, the first time or again. */
    fun stopChecking() {
        checking?.cancel()
    }
}
