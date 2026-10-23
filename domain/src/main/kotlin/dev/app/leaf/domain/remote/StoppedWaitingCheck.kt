// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.remote

import dev.app.leaf.common.TabScope
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.HandOffOutcome
import dev.app.leaf.domain.interfaces.ICheckHandOffGitAction
import dev.app.leaf.domain.models.ServerHandOff
import dev.app.leaf.domain.usecases.DataToRefresh
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/** What the tab shows after the user stopped waiting for a server (fork-only), until it's dismissed. */
sealed interface StoppedWaitingState {
    val handOff: ServerHandOff

    /** Asking the server what it did, the first time or again. */
    data class Asking(override val handOff: ServerHandOff) : StoppedWaitingState

    /** The server took the push, or deleted the branch, and the remote-tracking branch says so. */
    data class Done(override val handOff: ServerHandOff) : StoppedWaitingState

    /** It hadn't by the last time it was asked: it has [now] on the branch, or no such branch when null. */
    data class NotDone(override val handOff: ServerHandOff, val now: String?) : StoppedWaitingState

    /** Nothing tells: the remote pushes to several URLs, or the user stopped checking before an answer came. */
    data class Unknown(override val handOff: ServerHandOff) : StoppedWaitingState

    /** The server couldn't be asked, the first time. */
    data class Failed(override val handOff: ServerHandOff, val error: AppError) : StoppedWaitingState
}

/**
 * Asks the server what it did with what git had handed it, once the user stopped waiting on the processing screen
 * (fork-only): a server that has a whole push, or a delete's command, carries it out whatever git does next, while
 * Leaf's remote-tracking branch stays where it was. It asks every [CHECK_AGAIN_EVERY] while the server hasn't done it,
 * [CHECK_ROUNDS] times at most, as a server that was running a hook finishes a moment later. Once the server did it,
 * the remote-tracking branch is set as git would have set it, and the branches and the log are refreshed.
 *
 * It runs in the tab's scope, not the task's, which was cancelled, and shows no processing screen: the tab can be used
 * meanwhile, and [state] says what it found.
 */
@TabScope
class StoppedWaitingCheck @Inject constructor(
    private val checkHandOffGitAction: ICheckHandOffGitAction,
    private val useCaseExecutor: UseCaseExecutor,
    private val refreshDataUseCase: Provider<RefreshDataUseCase>,
    private val tabScope: TabCoroutineScope,
) {
    val state: StateFlow<StoppedWaitingState?>
        field = MutableStateFlow(null)

    /** [CHECK_AGAIN_EVERY] and [CHECK_ROUNDS], which tests shorten. */
    internal var checkAgainEvery = CHECK_AGAIN_EVERY
    internal var rounds = CHECK_ROUNDS

    @Volatile
    private var job: Job? = null

    /** Starts asking about [handOff], in place of what was shown before. */
    fun start(handOff: ServerHandOff) {
        job?.cancel()
        state.value = StoppedWaitingState.Asking(handOff)
        job = tabScope.launch { ask(handOff) }
    }

    /** Asks again about what was shown, after it couldn't be asked or once the rounds ended. */
    fun tryAgain() {
        state.value?.let { start(it.handOff) }
    }

    /** Stops asking: what the server said last stays, or else that nothing tells. */
    fun stopChecking() {
        job?.cancel()
    }

    fun dismiss() {
        val asking = job

        job = null
        asking?.cancel()
        state.value = null
    }

    private suspend fun ask(handOff: ServerHandOff) {
        val me = currentCoroutineContext()[Job]
        var answer: StoppedWaitingState? = null

        try {
            for (round in 1..rounds) {
                if (round > 1) delay(checkAgainEvery)

                val result = useCaseExecutor.execute { repositoryPath -> checkHandOffGitAction(repositoryPath, handOff) }

                val outcome = when (result) {
                    // Asked again, the last answer stays
                    is Either.Err -> {
                        state.value = answer ?: StoppedWaitingState.Failed(handOff, result.error)
                        return
                    }

                    is Either.Ok -> result.value
                }

                when (outcome) {
                    HandOffOutcome.Done -> {
                        state.value = StoppedWaitingState.Done(handOff)
                        refreshDataUseCase.get()(DataToRefresh.REMOTES, DataToRefresh.BRANCHES, DataToRefresh.LOG)
                        return
                    }

                    HandOffOutcome.CantTell -> {
                        state.value = StoppedWaitingState.Unknown(handOff)
                        return
                    }

                    is HandOffOutcome.NotDone -> answer = StoppedWaitingState.NotDone(handOff, outcome.now)
                }
            }

            state.value = answer
        } catch (e: CancellationException) {
            // "Stop checking"; dismissing, or asking about something else, has replaced it already
            if (job === me) {
                state.value = answer ?: StoppedWaitingState.Unknown(handOff)
            }

            throw e
        }
    }
}
