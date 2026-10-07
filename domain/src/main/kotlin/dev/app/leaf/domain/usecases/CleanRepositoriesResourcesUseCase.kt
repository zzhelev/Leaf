package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.services.IGitProviderService
import javax.inject.Inject

class CleanRepositoriesResourcesUseCase @Inject constructor(
    private val gitProviderService: IGitProviderService,
) {
    operator fun invoke(otherRepositories: List<String>) {
        gitProviderService.cleanupExcept(otherRepositories.toSet())
    }
}