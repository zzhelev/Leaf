package dev.app.leaf.ui.dialogs

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.usecases.LoadSignOffConfigUseCase
import dev.app.leaf.domain.usecases.SaveSignOffConfigUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class SignOffDialogViewModel @Inject constructor(
    private val loadSignOffConfigUseCase: LoadSignOffConfigUseCase,
    private val saveSignOffConfigUseCase: SaveSignOffConfigUseCase,
) : TabViewModel() {
    private val _state = MutableStateFlow<SignOffState>(SignOffState.Loading)
    val state = _state.asStateFlow()

    fun loadSignOffFormat() {
        viewModelScope.launch {
            val signOffConfig = loadSignOffConfigUseCase()

            if (signOffConfig is Either.Ok) {
                _state.value = SignOffState.Loaded(signOffConfig.value)
            }
        }
    }

    fun saveSignOffFormat(newIsEnabled: Boolean, newFormat: String) {
        viewModelScope.launch {
            saveSignOffConfigUseCase(SignOffConfig(newIsEnabled, newFormat))
        }
    }
}

sealed interface SignOffState {
    object Loading : SignOffState
    data class Loaded(val signOffConfig: SignOffConfig) : SignOffState
}