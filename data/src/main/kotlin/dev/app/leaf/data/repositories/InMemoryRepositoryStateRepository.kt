package dev.app.leaf.data.repositories

import dev.app.leaf.domain.MAX_COMPLETED_TASKS_KEPT
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
    override val taskProgress: StateFlow<TaskProgress?>
        field = MutableStateFlow(null)

    @Volatile
    private var currentTaskJob: Job? = null

    override suspend fun <T> runOperation(taskType: TaskType, isForegroundTask: Boolean, block: suspend () -> T): T {
        try {
            if (isForegroundTask) {
                currentTask.value = taskType
                currentTaskJob = currentCoroutineContext()[Job]
            }
            return block()
        } finally {
            if (isForegroundTask) {
                currentTask.value = null
                currentTaskJob = null
                taskProgress.value = null
            }
        }
    }

    override fun updateTaskProgress(progress: TaskProgress?) {
        taskProgress.value = progress
    }

    override fun cancelCurrentTask() {
        if (taskProgress.value != null) {
            currentTaskJob?.cancel()
        }
    }

    override suspend fun addCompletedTaskSuccessfully(completedTask: TaskType) {
        addCompletedTask(
            CompletedTask.Success(System.currentTimeMillis(), completedTask)
        )
    }

    override suspend fun addCompletedTaskWithConflicts(completedTask: TaskType) {
        addCompletedTask(
            CompletedTask.Success(System.currentTimeMillis(), completedTask, stoppedAtConflicts = true)
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