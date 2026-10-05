package dev.app.leaf.data.repositories

import dev.app.leaf.domain.MAX_COMPLETED_TASKS_KEPT
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.flow.*
import javax.inject.Inject

class InMemoryRepositoryStateRepository @Inject constructor() : RepositoryStateRepository {
    override val currentTask: StateFlow<TaskType?>
        field = MutableStateFlow(null)
    override val completedTasks: StateFlow<List<CompletedTask>>
        field = MutableStateFlow(emptyList())
    override val lastOperationTimestamp: Flow<Long> = completedTasks.map {
        completedTasks.value.lastOrNull()?.date ?: 0L
    }
    override val refreshTriggered: Flow<List<DataToRefresh>>
        field = MutableSharedFlow()

    override suspend fun <T> runOperation(taskType: TaskType, isForegroundTask: Boolean, block: suspend () -> T): T {
        try {
            if (isForegroundTask) {
                currentTask.value = taskType
            }
            return block()
        } finally {
            if (isForegroundTask) {
                currentTask.value = null
            }
        }
    }

    override suspend fun addCompletedTaskSuccessfully(completedTask: TaskType) {
        addCompletedTask(
            CompletedTask.Success(System.currentTimeMillis(), completedTask)
        )
    }

    override suspend fun addCompletedTaskFailed(
        completedTask: TaskType,
        reason: AppError,
        severity: FailureSeverity
    ) {
        addCompletedTask(
            CompletedTask.Failure(
                System.currentTimeMillis(),
                completedTask,
                reason,
                severity
            )
        )
    }

    override suspend fun refreshTriggered(dataToRefresh: List<DataToRefresh>) {
        refreshTriggered.emit(dataToRefresh)
    }

    private fun addCompletedTask(completedTask: CompletedTask) {
        completedTasks.update { currentTasks ->
            currentTasks
                .toMutableList()
                .apply {
                    this.add(completedTask)
                }
                .takeLast(MAX_COMPLETED_TASKS_KEPT)
        }
    }
}