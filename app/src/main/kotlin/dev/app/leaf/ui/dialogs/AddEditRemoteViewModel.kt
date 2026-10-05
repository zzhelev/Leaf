package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.usecases.AddRemoteUseCase
import dev.app.leaf.domain.usecases.UpdateRemoteUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update


class AddEditRemoteViewModel @AssistedInject constructor(
    private val addRemoteUseCase: AddRemoteUseCase,
    private val updateRemoteUseCase: UpdateRemoteUseCase,
    @Assisted val remoteToEdit: Remote?,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(commit: Remote?): AddEditRemoteViewModel
    }

    val remote: StateFlow<Remote>
        field = MutableStateFlow(remoteToEdit ?: Remote("", "", ""))

    val isNewRemote = remoteToEdit == null

    fun save() {
        if (remoteToEdit == null) {
            addRemoteUseCase(remote.value)
        } else {
            updateRemoteUseCase(remote.value)
        }
    }

    fun updateRemoteName(name: String) {
        remote.update { it.copy(name = name) }
    }

    fun updateAllUri(uri: String) {
        remote.update { it.copy(fetchUri = uri, pushUri = uri) }
    }

    fun updateFetchUri(uri: String) {
        remote.update { it.copy(fetchUri = uri) }
    }

    fun updatePushUri(uri: String) {
        remote.update { it.copy(pushUri = uri) }
    }
}