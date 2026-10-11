package dev.app.leaf.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow

@Immutable
class TabInformation<T: TabInformationProvider>(val data: T) {
    val name = data.tabName
    val worktreeName = data.worktreeName
    val extraInfo = data.extraInfo
}

interface TabInformationProvider {
    val tabName: StateFlow<String?>

    /** The linked worktree that the tab shows, named after [tabName], or null (fork-only). */
    val worktreeName: StateFlow<String?>
    val extraInfo: StateFlow<String?>

    fun dispose()
}