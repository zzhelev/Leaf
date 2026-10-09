package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.DataRefreshRunner
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
    private val dataRefreshRunner: DataRefreshRunner,
) {
    // Fork-only: between refreshes, which share the log's generator and would replace the log it adds to
    suspend operator fun invoke(newLimit: Int): Either<Unit, AppError> = dataRefreshRunner.runAlone {
        useCaseExecutor.execute { repositoryPath ->
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