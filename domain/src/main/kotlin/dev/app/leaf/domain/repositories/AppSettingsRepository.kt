package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.AvatarProviderType
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.ProxyType
import dev.app.leaf.domain.models.CommitChangesSectionSizes
import dev.app.leaf.domain.models.LogColumnsSettings
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RefPanelSettings
import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
    // UI
    val scaleUi: Flow<Float?>
    val theme: Flow<Theme?>
    val customTheme: Flow<String?>
    val linesHeightType: Flow<LinesHeightType?>
    val dateFormatUseDefault: Flow<Boolean?>
    val dateFormatCustomFormat: Flow<String?>
    val dateFormatIs24h: Flow<Boolean?>
    val dateFormatUseRelative: Flow<Boolean?>
    val avatarProvider: Flow<AvatarProviderType?>
    val swapStatusPanes: Flow<Boolean?>
    /** Read only: the list/tree toggle of Leaf 1.1.0 and older, the default for [filesChangedView] until it is saved. */
    val showChangesAsTree: Flow<Boolean?>
    val diffDisplayFullFile: Flow<Boolean?>
    val diffTextViewType: Flow<DiffTextViewType?>
    val refPanelSettings: Flow<RefPanelSettings?>
    val filesChangedView: Flow<FilesViewState?>
    val logColumns: Flow<LogColumnsSettings?>

    // Git
    val pullWithRebase: Flow<Boolean?>
    val pushWithLease: Flow<Boolean?>
    val fastForwardMerge: Flow<Boolean?>
    val autoStashOnMerge: Flow<Boolean?>
    val cloneDefaultDirectory: Flow<String?>
    val gitExecutablePath: Flow<String?>
    val remoteOperationsWithGit: Flow<Boolean?>
    val worktreesRefreshInterval: Flow<Int?>

    // Network
    val useProxy: Flow<Boolean?>
    val proxyUseAuth: Flow<Boolean?>
    val proxyType: Flow<ProxyType?>
    val proxyHostName: Flow<String?>
    val proxyPortNumber: Flow<Int?>
    val proxyHostUser: Flow<String?>
    val proxyHostPassword: Flow<String?>
    val verifySsl: Flow<Boolean?>
    val cacheCredentialsInMemory: Flow<Boolean?>

    // Tools
    val terminalPath: Flow<String?>

    suspend fun setConfiguration(appConfig: AppConfig)

    var latestTabsOpened: String
    var latestRepositoryTabSelected: Int
    var latestOpenedRepositoriesPath: String
    var firstPaneWidth: Float
    var thirdPaneWidth: Float
    var statusSectionSizes: StatusSectionSizes
    var commitChangesSectionSizes: CommitChangesSectionSizes
}