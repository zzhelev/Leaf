package dev.app.leaf.domain.models

import dev.app.leaf.domain.exceptions.LeafException

data class Error(
    val taskType: TaskType,
    val date: Long,
    val exception: Exception,
    val isUnhandled: Boolean,
)


fun newErrorNow(
    taskType: TaskType,
    exception: Exception,
): Error {
    return Error(
        taskType = taskType,
        date = System.currentTimeMillis(),
        exception = exception,
        isUnhandled = exception !is LeafException
    )
}
