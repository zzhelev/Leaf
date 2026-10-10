package dev.app.leaf.domain.services

import dev.app.leaf.common.flows.defaultIfNull
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.AvatarProviderType
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.LogColumnsSettings
import dev.app.leaf.domain.models.ProxyType
import dev.app.leaf.domain.models.WorktreesRefreshIntervals
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RefPanelSettings
import dev.app.leaf.domain.sorting.SortSettingsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

class AppSettingsService @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
) {
    suspend fun setConfiguration(appConfig: AppConfig) {
        appSettingsRepository.setConfiguration(appConfig)
    }

    val scaleUi: Flow<Float?> get() = appSettingsRepository.scaleUi
    val theme: Flow<Theme> get() = appSettingsRepository.theme.defaultIfNull { DEFAULT_THEME }
    val customTheme: Flow<String?> get() = appSettingsRepository.customTheme
    val linesHeightType: Flow<LinesHeightType> get() = appSettingsRepository.linesHeightType.defaultIfNull { DEFAULT_LINES_HEIGHT }
    val dateFormatUseDefault: Flow<Boolean> get() = appSettingsRepository.dateFormatUseDefault.defaultIfNull { DEFAULT_DATE_USE_DEFAULT }
    val dateFormatCustomFormat: Flow<String> get() = appSettingsRepository.dateFormatCustomFormat.defaultIfNull { DEFAULT_DATE_CUSTOM_FORMAT }
    val dateFormatIs24h: Flow<Boolean> get() = appSettingsRepository.dateFormatIs24h.defaultIfNull { DEFAULT_DATE_IS_24H }
    val dateFormatUseRelative: Flow<Boolean> get() = appSettingsRepository.dateFormatUseRelative.defaultIfNull { DEFAULT_DATE_USE_RELATIVE }
    val avatarProvider: Flow<AvatarProviderType> get() = appSettingsRepository.avatarProvider.defaultIfNull { DEFAULT_AVATAR_PROVIDER }
    val swapStatusPanes: Flow<Boolean> get() = appSettingsRepository.swapStatusPanes.defaultIfNull { DEFAULT_SWAP_STATUS_PANES }
    /** Whether discarding a hunk or a line in the diff asks first. Discarding files always asks. */
    val confirmHunkAndLineDiscards: Flow<Boolean>
        get() = appSettingsRepository.confirmHunkAndLineDiscards.defaultIfNull {
            DEFAULT_CONFIRM_HUNK_AND_LINE_DISCARDS
        }
    val diffDisplayFullFile: Flow<Boolean> get() = appSettingsRepository.diffDisplayFullFile.defaultIfNull { DEFAULT_DIFF_DISPLAY_FULL_FILE }
    val diffTextViewType: Flow<DiffTextViewType> get() = appSettingsRepository.diffTextViewType.defaultIfNull { DEFAULT_DIFF_TEXT_VIEW_TYPE }
    val refPanelSettings: Flow<RefPanelSettings> get() = appSettingsRepository.refPanelSettings.defaultIfNull { RefPanelSettings() }
    val filesChangedView: Flow<FilesViewState>
        get() = combine(
            appSettingsRepository.filesChangedView,
            appSettingsRepository.showChangesAsTree,
            SortSettingsCodec::filesViewStateOrLegacy,
        )
    val logColumns: Flow<LogColumnsSettings> get() = appSettingsRepository.logColumns.defaultIfNull { LogColumnsSettings() }
    val pullWithRebase: Flow<Boolean> get() = appSettingsRepository.pullWithRebase.defaultIfNull { DEFAULT_PULL_WITH_REBASE }
    val pushWithLease: Flow<Boolean> get() = appSettingsRepository.pushWithLease.defaultIfNull { DEFAULT_PUSH_WITH_LEASE }
    val fastForwardMerge: Flow<Boolean> get() = appSettingsRepository.fastForwardMerge.defaultIfNull { DEFAULT_FAST_FORWARD_MERGE }
    val autoStashOnMerge: Flow<Boolean> get() = appSettingsRepository.autoStashOnMerge.defaultIfNull { DEFAULT_AUTO_STASH_ON_MERGE }
    val cloneDefaultDirectory: Flow<String?> get() = appSettingsRepository.cloneDefaultDirectory
    val gitExecutablePath: Flow<String?> get() = appSettingsRepository.gitExecutablePath

    /** Whether push, fetch and pull run the git CLI, or JGit, Leaf's built-in implementation. */
    val remoteOperationsWithGit: Flow<Boolean>
        get() = appSettingsRepository.remoteOperationsWithGit.defaultIfNull { DEFAULT_REMOTE_OPERATIONS_WITH_GIT }

    /** Seconds between two refreshes of the worktree list while it's shown, 0 for none. */
    val worktreesRefreshInterval: Flow<Int>
        get() = appSettingsRepository.worktreesRefreshInterval.defaultIfNull {
            WorktreesRefreshIntervals.DEFAULT_SECONDS
        }
    val useProxy: Flow<Boolean> get() = appSettingsRepository.useProxy.defaultIfNull { DEFAULT_USE_PROXY }
    val proxyUseAuth: Flow<Boolean> get() = appSettingsRepository.proxyUseAuth.defaultIfNull { DEFAULT_PROXY_USE_AUTH }
    val proxyType: Flow<ProxyType> get() = appSettingsRepository.proxyType.defaultIfNull { DEFAULT_PROXY_TYPE }
    val proxyHostName: Flow<String?> get() = appSettingsRepository.proxyHostName
    val proxyPortNumber: Flow<Int?> get() = appSettingsRepository.proxyPortNumber
    val proxyHostUser: Flow<String?> get() = appSettingsRepository.proxyHostUser
    val proxyHostPassword: Flow<String?> get() = appSettingsRepository.proxyHostPassword
    val verifySsl: Flow<Boolean> get() = appSettingsRepository.verifySsl.defaultIfNull { DEFAULT_VERIFY_SSL }
    val cacheCredentialsInMemory: Flow<Boolean> get() = appSettingsRepository.cacheCredentialsInMemory.defaultIfNull { DEFAULT_CACHE_CREDENTIALS_IN_MEMORY }
    val terminalPath: Flow<String?> get() = appSettingsRepository.terminalPath

    companion object {
        val DEFAULT_THEME = Theme.Dark
        val DEFAULT_LINES_HEIGHT = LinesHeightType.SPACED
        const val DEFAULT_DATE_USE_DEFAULT = true
        const val DEFAULT_DATE_IS_24H = true
        const val DEFAULT_DATE_USE_RELATIVE = true
        const val DEFAULT_DATE_CUSTOM_FORMAT = "dd MMM yyyy"
        val DEFAULT_AVATAR_PROVIDER = AvatarProviderType.Gravatar
        const val DEFAULT_SWAP_STATUS_PANES = false
        const val DEFAULT_CONFIRM_HUNK_AND_LINE_DISCARDS = true
        const val DEFAULT_DIFF_DISPLAY_FULL_FILE = false
        val DEFAULT_DIFF_TEXT_VIEW_TYPE = DiffTextViewType.Unified
        const val DEFAULT_PULL_WITH_REBASE = false
        const val DEFAULT_PUSH_WITH_LEASE = true
        const val DEFAULT_REMOTE_OPERATIONS_WITH_GIT = true
        const val DEFAULT_FAST_FORWARD_MERGE = true
        const val DEFAULT_AUTO_STASH_ON_MERGE = true
        const val DEFAULT_USE_PROXY = false
        const val DEFAULT_PROXY_USE_AUTH = false
        val DEFAULT_PROXY_TYPE = ProxyType.HTTP
        const val DEFAULT_VERIFY_SSL = true
        const val DEFAULT_CACHE_CREDENTIALS_IN_MEMORY = true
    }
}