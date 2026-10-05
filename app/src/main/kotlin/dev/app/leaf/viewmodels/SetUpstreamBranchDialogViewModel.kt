package dev.app.leaf.viewmodels

import dev.app.leaf.TabViewModel
import dev.app.leaf.common.flows.combine
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RemoteInfo
import dev.app.leaf.domain.models.TrackingBranch
import dev.app.leaf.domain.usecases.GetRemotesUseCase
import dev.app.leaf.domain.usecases.GetTrackingBranchUseCase
import dev.app.leaf.domain.usecases.SetTrackingBranchUseCase
import dev.app.leaf.extensions.stateIn
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class SetUpstreamBranchDialogViewModel @AssistedInject constructor(
    private val getTrackingBranchUseCase: GetTrackingBranchUseCase,
    private val setTrackingBranchUseCase: SetTrackingBranchUseCase,
    private val getRemotesUseCase: GetRemotesUseCase,
    @Assisted private val branch: Branch,
) : TabViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(branch: Branch): SetUpstreamBranchDialogViewModel
    }

    val selectedRemote: StateFlow<SelectedUiValue<RemoteInfo?>>
        field = MutableStateFlow<SelectedUiValue<RemoteInfo?>>(SelectedUiValue.Default)

    val selectedBranch: StateFlow<SelectedUiValue<Branch?>>
        field = MutableStateFlow<SelectedUiValue<Branch?>>(SelectedUiValue.Default)

    val isCompleted: StateFlow<Boolean>
        field = MutableStateFlow<Boolean>(false)

    val setDefaultUpstreamBranchState: StateFlow<SetDefaultUpstreamBranchState> = flow {
        // TODO Show error instead of empty for both calls?
        val remotes = getRemotesUseCase()
            .okOrNull()
            .orEmpty()
            .map {
                val branchesWithoutHead = it.branchesList.filter { branch ->
                    branch.simpleName != "HEAD"
                }
                it.copy(branchesList = branchesWithoutHead)
            }
        val trackingBranch = getTrackingBranchUseCase(branch).okOrNull()

        val remote: RemoteInfo?
        val remoteBranch: Branch?

        if (trackingBranch != null) {
            remote = remotes.firstOrNull { it.remote.name == trackingBranch.remote }
            remoteBranch = remote?.branchesList?.firstOrNull { it.simpleName == trackingBranch.branch }
        } else {
            remote = null
            remoteBranch = null
        }

        val state = SetDefaultUpstreamBranchState.Loaded(
            branch = branch,
            trackingBranch = trackingBranch,
            remotes = remotes,
            selectedRemote = remote,
            selectedBranch = remoteBranch,
            isCompleted = false,
        )

        emit(state)
    }
        .combine(selectedRemote, selectedBranch) { data, remote, branch ->
            val dataWithSelectedBranch = if (branch is SelectedUiValue.Selected) {
                data.copy(selectedBranch = branch.value)
            } else {
                data
            }

            val dataWithSelectedBranchAndRemote = if (remote is SelectedUiValue.Selected) {
                dataWithSelectedBranch.copy(selectedRemote = remote.value)
            } else {
                dataWithSelectedBranch
            }

            dataWithSelectedBranchAndRemote
        }
        .combine(isCompleted) { data, isCompleted ->
            data.copy(isCompleted = isCompleted)
        }
        .stateIn(SetDefaultUpstreamBranchState.Loading)


    fun changeDefaultUpstreamBranch() = viewModelScope.launch {
        val state = setDefaultUpstreamBranchState.value

        if (state is SetDefaultUpstreamBranchState.Loaded) {
            // TODO Handle error in dialog instead of generic view
            val setRemoteResult = setTrackingBranchUseCase(
                branch,
                state.selectedRemote?.remote?.name,
                state.selectedBranch,
            ).await()

            if (setRemoteResult is Either.Ok) {
                isCompleted.value = true
            }

        }
    }

    fun setSelectedBranch(branchOption: Branch) {
        selectedBranch.value = SelectedUiValue.Selected(branchOption)
    }

    fun setSelectedRemote(remote: RemoteInfo) {
        val state = setDefaultUpstreamBranchState.value
        val remoteConfig = remote.remote

        if (state is SetDefaultUpstreamBranchState.Loaded) {
            val branch = if (remoteConfig.name == state.trackingBranch?.remote) {
                remote.branchesList.firstOrNull { it.simpleName == state.trackingBranch.branch }
            } else {
                null
            }

            selectedRemote.value = SelectedUiValue.Selected(remote)
            selectedBranch.value = SelectedUiValue.Selected(branch)
        }
    }
}

sealed interface SelectedUiValue<out T> {
    data object Default : SelectedUiValue<Nothing>
    data class Selected<T>(val value: T) : SelectedUiValue<T>
}

sealed interface SetDefaultUpstreamBranchState {
    object Loading : SetDefaultUpstreamBranchState
    data class Loaded(
        val branch: Branch,
        val trackingBranch: TrackingBranch?,
        val remotes: List<RemoteInfo>,
        val selectedRemote: RemoteInfo?,
        val selectedBranch: Branch?,
        val isCompleted: Boolean,
    ) : SetDefaultUpstreamBranchState
}
