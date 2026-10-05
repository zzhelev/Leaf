package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class IncreaseLogCountUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val useCaseExecutor: UseCaseExecutor,
    private val getLogUseCase: GetLogUseCase,
) {
    suspend operator fun invoke(newLimit: Int): Either<Unit, AppError> {
        return useCaseExecutor.execute { repositoryPath ->
            // If the data is being loaded or failed, do not try to load more items
            val log = (repositoryDataRepository.log.value as? DataState.Loaded)?.data ?: return@execute Either.Ok(Unit)

            if (newLimit > repositoryDataRepository.maxCommitsToLoadLimit) {
                repositoryDataRepository.maxCommitsToLoadLimit = newLimit
                repositoryDataRepository.updateLog {
                    getLogUseCase(
                        repositoryPath,
                        pagination = Pagination.Paginated(log)
                    )
                }
            }

            Either.Ok(Unit)
        }
    }
}