package dev.app.leaf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.app.leaf.di.TabComponent
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.pathToPersist
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.usecases.CleanRepositoriesResourcesUseCase
import dev.app.leaf.domain.worktrees.indexOfRepository
import dev.app.leaf.ui.components.TabInformation
import dev.app.leaf.viewmodels.RepositoryTabViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val tabComponentFactory: TabComponent.Factory,
    private val cleanRepositoriesResourcesUseCase: CleanRepositoriesResourcesUseCase,
) : ViewModel() {
    val tabs: StateFlow<List<TabInformation<RepositoryTabViewModel>>>
        field = MutableStateFlow<List<TabInformation<RepositoryTabViewModel>>>(emptyList())

    val currentTab: StateFlow<TabInformation<RepositoryTabViewModel>?>
        field = MutableStateFlow<TabInformation<RepositoryTabViewModel>?>(null)

    fun loadPersistedTabs() {
        val repositoriesSaved = appSettingsRepository.latestTabsOpened

        val repositoriesList = if (repositoriesSaved.isNotEmpty())
            Json.decodeFromString<List<String>>(repositoriesSaved).map { path ->
                newAppTab2(
                    path = path,
                )
            }
        else
            listOf()

        tabs.value = repositoriesList.ifEmpty { listOf(newAppTab2()) }

        val latestSelectedTabIndex = appSettingsRepository.latestRepositoryTabSelected

        currentTab.value = when (latestSelectedTabIndex < 0) {
            true -> tabs.value.first()
            false -> tabs.value.getOrNull(latestSelectedTabIndex) ?: tabs.value.first()
        }
    }

    suspend fun addNewTabFromPath(path: String, selectTab: Boolean, tabToBeReplacedPath: String? = null) {
        val tabToBeReplaced = tabs
            .value
            .firstOrNull {
                it.data.repositoryPath.firstOrNull() == tabToBeReplacedPath
            }

        val newTab = newAppTab2(
            path = path,
        )

        tabs.update {
            val newTabsList = it.toMutableList()

            if (tabToBeReplaced != null) {
                val index = newTabsList.indexOf(tabToBeReplaced)
                newTabsList[index] = newTab
            } else {
                newTabsList.add(newTab)
            }

            newTabsList
        }

        if (selectTab) {
            currentTab.value = newTab
        }
    }

    /**
     * Selects the tab that has the repository at [directory] open, or opens it in a new tab (fork-only). Tabs are
     * compared by git dir ([indexOfRepository]), so a worktree's folder finds the tab of that worktree, whichever path
     * the tab was opened from, even before it has loaded.
     */
    fun selectOrOpenTab(directory: String) = viewModelScope.launch {
        val tabsList = tabs.value
        val tabPaths = tabsList.map { tab ->
            tab.data.repositorySelectionState.value.pathToPersist(initialPath = tab.data.initialPath)
        }

        val index = withContext(Dispatchers.IO) { indexOfRepository(directory, tabPaths) }
        val tab = tabsList.getOrNull(index)

        if (tab != null) {
            selectTab(tab)
        } else {
            addNewTabFromPath(directory, selectTab = true)
        }
    }

    fun selectTab(tab: TabInformation<RepositoryTabViewModel>) {
        currentTab.value = tab

        persistTabSelected(tab)
    }

    private fun persistTabSelected(tab: TabInformation<RepositoryTabViewModel>) {
        appSettingsRepository.latestRepositoryTabSelected = tabs.value.indexOf(tab)
    }

    fun closeTab(tab: TabInformation<RepositoryTabViewModel>) = viewModelScope.launch {
        val tabsList = tabs.value.toMutableList()
        var newCurrentTab: TabInformation<RepositoryTabViewModel>? = null
        tab.data.dispose()

        if (currentTab.value == tab) {
            val index = tabsList.indexOf(tab)

            if (tabsList.count() == 1) {
                newCurrentTab = newAppTab2()
            } else if (index > 0) {
                newCurrentTab = tabsList[index - 1]
            } else if (index == 0) {
                newCurrentTab = tabsList[1]
            }
        }

        tabsList.remove(tab)

        if (newCurrentTab != null) {
            if (!tabsList.contains(newCurrentTab)) {
                tabsList.add(newCurrentTab)
            }

            tabs.value = tabsList
            currentTab.value = newCurrentTab
        } else {
            tabs.value = tabsList
        }

        // Git dirs, the keys of the JGit cache
        val remainingRepositories = tabsList.mapNotNull {
            (it.data.repositorySelectionState.value as? RepositorySelectionState.Open)?.path
        }

        cleanRepositoriesResourcesUseCase(remainingRepositories)

        updatePersistedTabs()
        System.gc()
    }

    suspend fun updatePersistedTabs() {
        val tabsToPersist = tabs
            .value
            .mapNotNull { tab ->
                tab.data.repositorySelectionState.value
                    .pathToPersist(initialPath = tab.data.initialPath)
                    ?.let { path -> tab to path }
            }

        appSettingsRepository.latestTabsOpened = Json.encodeToString(tabsToPersist.map { it.second })
        appSettingsRepository.latestRepositoryTabSelected = tabsToPersist.indexOfFirst { it.first == currentTab.value }
    }

    fun addNewEmptyTab() {
        val newTab = newAppTab2()

        tabs.update {
            it.toMutableList().apply {
                add(newTab)
            }
        }

        currentTab.value = newTab
    }

    private fun newAppTab2(path: String? = null): TabInformation<RepositoryTabViewModel> {
        val tabComponent: TabComponent = tabComponentFactory.create()
        val viewModel = tabComponent
            .repositoryTabViewModelFactory()
            .create(path)

        return TabInformation(viewModel)
    }

    fun onMoveTab(fromIndex: Int, toIndex: Int) = viewModelScope.launch {
        tabs.update {
            it.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }

        updatePersistedTabs()
    }
}
