package dev.app.leaf.viewmodels

import androidx.compose.runtime.Immutable
import dev.app.leaf.LogsRepository
import dev.app.leaf.TabViewModel
import dev.app.leaf.common.flows.combine
import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.gitcli.GitExecutable
import dev.app.leaf.domain.gitcli.IGitExecutableLocator
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.AvatarProviderType
import dev.app.leaf.domain.models.ProxyType
import dev.app.leaf.domain.models.WorktreesRefreshIntervals
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

class SettingsViewModel @Inject constructor(
    private val appSettingsService: AppSettingsService,
    private val logsRepository: LogsRepository,
    private val openUrlInBrowserUseCase: OpenUrlInBrowserUseCase,
    private val gitExecutableLocator: IGitExecutableLocator,
) : TabViewModel() {
    /** Emits null while git is being located, as running `git --version` takes a moment. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val gitExecutable: Flow<Either<GitExecutable, GitCliError>?> = appSettingsService.gitExecutablePath
        .distinctUntilChanged()
        .transformLatest { path ->
            emit(null)
            emit(gitExecutableLocator.locate(path))
        }

    val settingsViewState = settingsState()
        .stateIn(emptySettingsState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetConfig -> setAppConfiguration(action.configuration)
            SettingsAction.OpenLogsFolder -> openLogsFolderInFileExplorer()
        }
    }

    private fun setAppConfiguration(appConfig: AppConfig) = viewModelScope.launch {
        appSettingsService.setConfiguration(appConfig)
    }

    fun openLogsFolderInFileExplorer() {
        try {
            openUrlInBrowserUseCase(logsRepository.logsDirectory.absolutePath)
        } catch (e: Exception) {
            printError(TAG, "Failed to open logs dir: ${e.message.orEmpty()}", e)
        }
    }

    private fun settingsState(): Flow<SettingsViewState> {
        return combine(
            appSettingsService.scaleUi,
            appSettingsService.theme,
            appSettingsService.customTheme,
            appSettingsService.linesHeightType,
            appSettingsService.dateFormatUseDefault,
            appSettingsService.dateFormatCustomFormat,
            appSettingsService.dateFormatIs24h,
            appSettingsService.dateFormatUseRelative,
            appSettingsService.avatarProvider,
            appSettingsService.swapStatusPanes,
            appSettingsService.confirmHunkAndLineDiscards,
            appSettingsService.pullWithRebase,
            appSettingsService.pushWithLease,
            appSettingsService.remoteOperationsWithGit,
            appSettingsService.worktreesRefreshInterval,
            appSettingsService.fastForwardMerge,
            appSettingsService.autoStashOnMerge,
            appSettingsService.cloneDefaultDirectory,
            appSettingsService.useProxy,
            appSettingsService.proxyUseAuth,
            appSettingsService.proxyType,
            appSettingsService.proxyHostName,
            appSettingsService.proxyPortNumber,
            appSettingsService.proxyHostUser,
            appSettingsService.proxyHostPassword,
            appSettingsService.verifySsl,
            appSettingsService.cacheCredentialsInMemory,
            appSettingsService.terminalPath,
            appSettingsService.gitExecutablePath,
            gitExecutable,
        ) { scaleUi,
            theme,
            customTheme,
            linesHeightType,
            dateFormatUseDefault,
            dateFormatCustomFormat,
            dateFormatIs24h,
            dateFormatUseRelative,
            avatarProvider,
            swapStatusPanes,
            confirmHunkAndLineDiscards,
            pullWithRebase,
            pushWithLease,
            remoteOperationsWithGit,
            worktreesRefreshInterval,
            fastForwardMerge,
            autoStashOnMerge,
            cloneDefaultDirectory,
            useProxy,
            proxyUseAuth,
            proxyType,
            proxyHostName,
            proxyPortNumber,
            proxyHostUser,
            proxyHostPassword,
            verifySsl,
            cacheCredentialsInMemory,
            terminalPath,
            gitExecutablePath,
            gitExecutable ->

            SettingsViewState(
                scaleUi,
                theme,
                customTheme,
                linesHeightType,
                dateFormatUseDefault,
                dateFormatCustomFormat,
                dateFormatIs24h,
                dateFormatUseRelative,
                avatarProvider,
                swapStatusPanes,
                confirmHunkAndLineDiscards,
                pullWithRebase,
                pushWithLease,
                remoteOperationsWithGit,
                worktreesRefreshInterval,
                fastForwardMerge,
                autoStashOnMerge,
                cloneDefaultDirectory,
                useProxy,
                proxyUseAuth,
                proxyType,
                proxyHostName,
                proxyPortNumber,
                proxyHostUser,
                proxyHostPassword,
                verifySsl,
                cacheCredentialsInMemory,
                terminalPath,
                gitExecutablePath,
                gitExecutable,
            )
        }
    }

    private fun emptySettingsState(): SettingsViewState {
        return SettingsViewState(
            scaleUi = null,
            theme = Theme.Light,
            customTheme = "",
            linesHeightType = LinesHeightType.SPACED,
            dateFormatUseDefault = false,
            dateFormatCustomFormat = "",
            dateFormatIs24h = false,
            dateFormatUseRelative = false,
            avatarProvider = AvatarProviderType.Gravatar,
            swapStatusPanes = false,
            confirmHunkAndLineDiscards = true,
            pullWithRebase = false,
            pushWithLease = false,
            remoteOperationsWithGit = false,
            worktreesRefreshInterval = WorktreesRefreshIntervals.DEFAULT_SECONDS,
            fastForwardMerge = false,
            autoStashOnMerge = false,
            cloneDefaultDirectory = "",
            useProxy = false,
            proxyUseAuth = false,
            proxyType = ProxyType.HTTP,
            proxyHostName = "",
            proxyPortNumber = null,
            proxyHostUser = "",
            proxyHostPassword = "",
            verifySsl = false,
            cacheCredentialsInMemory = false,
            terminalPath = "",
            gitExecutablePath = "",
            gitExecutable = null,
        )
    }
}

@Immutable
data class SettingsViewState(
    val scaleUi: Float?,
    val theme: Theme,
    val customTheme: String?,
    val linesHeightType: LinesHeightType,
    val dateFormatUseDefault: Boolean,
    val dateFormatCustomFormat: String,
    val dateFormatIs24h: Boolean,
    val dateFormatUseRelative: Boolean,
    val avatarProvider: AvatarProviderType,
    val swapStatusPanes: Boolean,
    /** Whether discarding a hunk or a line asks first. */
    val confirmHunkAndLineDiscards: Boolean,
    val pullWithRebase: Boolean,
    val pushWithLease: Boolean,
    val remoteOperationsWithGit: Boolean,
    /** Seconds between two refreshes of the worktree list, 0 for none. */
    val worktreesRefreshInterval: Int,
    val fastForwardMerge: Boolean,
    val autoStashOnMerge: Boolean,
    val cloneDefaultDirectory: String?,
    val useProxy: Boolean,
    val proxyUseAuth: Boolean,
    val proxyType: ProxyType,
    val proxyHostName: String?,
    val proxyPortNumber: Int?,
    val proxyHostUser: String?,
    val proxyHostPassword: String?,
    val verifySsl: Boolean,
    val cacheCredentialsInMemory: Boolean,
    val terminalPath: String?,
    val gitExecutablePath: String?,
    /** Null while git is being located. */
    val gitExecutable: Either<GitExecutable, GitCliError>?,
)

sealed interface SettingsAction {
    data class SetConfig(val configuration: AppConfig) : SettingsAction
    data object OpenLogsFolder : SettingsAction
}