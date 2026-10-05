package dev.app.leaf.domain.models

import dev.app.leaf.FileChanged

sealed interface WatcherEvent {
    data class WatchInitError(val code: Int) : WatcherEvent
    data class ChangesDetected(val changes: List<FileChanged>) : WatcherEvent
}