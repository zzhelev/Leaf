package dev.app.leaf.viewmodels

import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AuthorInfo
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.emptyIdentity
import dev.app.leaf.domain.usecases.GetAuthorUseCase
import dev.app.leaf.domain.usecases.SaveAuthorUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class AuthorViewModel @Inject constructor(
    private val saveAuthorUseCase: SaveAuthorUseCase,
    private val getAuthorUseCase: GetAuthorUseCase,
) : TabViewModel() {
    val authorInfo: StateFlow<AuthorInfo> = flow {
        val author = when (val author = getAuthorUseCase()) {
            is Either.Ok -> author.value
            else -> AuthorInfo(emptyIdentity(), emptyIdentity())
        }

        emit(author)
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = AuthorInfo(emptyIdentity(), emptyIdentity()),
        )


    fun saveAuthorInfo(globalName: String?, globalEmail: String?, name: String?, email: String?) = viewModelScope.launch {
        saveAuthorUseCase(
            AuthorInfo(Identity(globalName, globalEmail), Identity(name, email))
        )
    }
}
