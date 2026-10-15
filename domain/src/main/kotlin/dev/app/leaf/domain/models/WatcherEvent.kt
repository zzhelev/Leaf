package dev.app.leaf.domain.models

sealed interface WatcherEvent {
    data class WatchInitError(val message: String) : WatcherEvent
    data class ChangesDetected(val changes: List<FileChanged>) : WatcherEvent

    /**
     * Fork-only: the system dropped events, so some changes went unreported, such as the files of a large checkout in
     * one folder.
     */
    data object ChangesLost : WatcherEvent
}
