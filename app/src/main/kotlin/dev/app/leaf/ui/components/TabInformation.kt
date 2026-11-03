package dev.app.leaf.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.app.leaf.domain.tabs.TabLook
import kotlinx.coroutines.flow.StateFlow

@Immutable
class TabInformation<T: TabInformationProvider>(val data: T) {
    val name = data.tabName
    val worktreeName = data.worktreeName
    val extraInfo = data.extraInfo
    val look = data.tabLook
}

interface TabInformationProvider {
    val tabName: StateFlow<String?>

    /** The linked worktree that the tab shows, named after [tabName], or null (fork-only). */
    val worktreeName: StateFlow<String?>
    val extraInfo: StateFlow<String?>

    /**
     * The name and colour that the user gave this tab, or null for a tab without a repository (fork-only, upstream
     * issue 242). [tabName] already has the name, and a renamed tab has no [worktreeName].
     */
    val tabLook: StateFlow<TabLook?>

    /** Names the tab [typed], or after its repository again when it's blank. */
    fun renameTab(typed: String)

    fun resetTabName()

    /** Gives the tab the colour of graph lane [color], from 1, or none. */
    fun setTabColor(color: Int?)

    fun dispose()
}