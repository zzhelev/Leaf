package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class SetRepositorySelectionStateToNoneUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    operator fun invoke() {
        repositoryDataRepository.setRepositorySelectionState(RepositorySelectionState.None)
    }
}