package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.usecases.DataToRefresh
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RepositoryStateRepository {
    val currentTask: StateFlow<TaskType?>
    val completedTasks: StateFlow<List<CompletedTask>>
    val lastOperationTimestamp: Flow<Long>
    val refreshTriggered: Flow<List<DataToRefresh>>

    /**
     * The foreground task's progress, set by the operations that run the git CLI. They are also the tasks that stop
     * when cancelled, so a task with progress can be cancelled. Null for other tasks.
     */
    val taskProgress: StateFlow<TaskProgress?>

    suspend fun <T> runOperation(taskType: TaskType, isForegroundTask: Boolean, block: suspend () -> T): T
    fun updateTaskProgress(progress: TaskProgress?)

    /** Cancels the foreground task, if it can be cancelled (see [taskProgress]). */
    fun cancelCurrentTask()
    suspend fun addCompletedTaskSuccessfully(completedTask: TaskType)
    suspend fun addCompletedTaskFailed(completedTask: TaskType, reason: AppError, severity: FailureSeverity)
    suspend fun refreshTriggered(dataToRefresh: List<DataToRefresh>)
}

sealed interface CompletedTask {
    val date: Long
    val taskType: TaskType

    data class Success(
        override val date: Long,
        override val taskType: TaskType
    ) : CompletedTask

    data class Failure(
        override val date: Long,
        override val taskType: TaskType,
        val reason: AppError,
        val severity: FailureSeverity,
    ) : CompletedTask
}

enum class FailureSeverity {
    LOW,
    HIGH,
}


/*
completedTasks -> Ok or Error

Ok = only the task type
Err = Task type + severity

UI collects both to show notifications
Specific components may observe them to detect potentially related events to the currently displayed UI. An example
is updating the diff windows when a line was staged for a specific diff (or even the whole file).



 */