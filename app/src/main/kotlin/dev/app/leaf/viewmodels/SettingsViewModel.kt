package dev.app.leaf.viewmodels

import androidx.compose.runtime.Immutable
import dev.app.leaf.LogsRepository
import dev.app.leaf.TabViewModel
import dev.app.leaf.common.flows.combine
import dev.app.leaf.common.printError
import dev.app.leaf.domain.commitmessage.CommitMessageHints
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.gitcli.GitExecutable
import dev.app.leaf.domain.gitcli.IGitExecutableLocator
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.AvatarProviderType
import dev.app.leaf.domain.models.ConflictOpener
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.WorktreeLabelRule
import dev.app.leaf.domain.models.WorktreesRefreshIntervals
import dev.app.leaf.domain.models.WorktreesUntouchedDays
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StylesService
import dev.app.leaf.domain.styles.UnreadableStyleFile
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.system.PickerType
import dev.app.leaf.system.SaveFilePickerUseCase
import dev.app.leaf.ui.dialogs.settings.StyleGalleryAction
import dev.app.leaf.ui.dialogs.settings.StyleNotice
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import dev.app.leaf.extensions.stateIn
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine as combineFlows
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
    private val stylesService: StylesService,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val saveFilePickerUseCase: SaveFilePickerUseCase,
) : TabViewModel() {
    /** What the last import or export of a style gave, until dismissed (fork-only). */
    private val styleNotice = MutableStateFlow<StyleNotice?>(null)

    init {
        // Fork-only: a style file changed outside Leaf shows when Settings opens
        viewModelScope.launch { stylesService.reload() }
    }

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
            is SettingsAction.Styles -> onStyleAction(action.action)
        }
    }

    private fun setAppConfiguration(appConfig: AppConfig) = viewModelScope.launch {
        appSettingsService.setConfiguration(appConfig)
    }

    /** Fork-only: asks where to export a style, on the thread that clicked, as the pickers are modal. */
    fun pickExportPath(title: String, fileName: String): String? = saveFilePickerUseCase(title, fileName)

    fun pickImportPath(): String? = openFilePickerUseCase(PickerType.FILES, null)

    private fun onStyleAction(action: StyleGalleryAction) = viewModelScope.launch {
        when (action) {
            is StyleGalleryAction.Select -> stylesService.select(action.style.id)
            is StyleGalleryAction.Duplicate -> stylesService.duplicate(action.style, action.name).onErr {
                styleNotice.value = StyleNotice.Failed(it)
            }
            is StyleGalleryAction.Delete -> stylesService.delete(action.style).onErr {
                styleNotice.value = StyleNotice.Failed(it)
            }
            is StyleGalleryAction.Export -> styleNotice.value = when (val result = stylesService.export(action.style, File(action.path))) {
                is Either.Ok -> StyleNotice.Exported(action.style.name, action.path)
                is Either.Err -> StyleNotice.Failed(result.error)
            }
            is StyleGalleryAction.Import -> styleNotice.value = when (val result = stylesService.import(File(action.path))) {
                is Either.Ok -> StyleNotice.Imported(result.value.name)
                is Either.Err -> StyleNotice.Failed(result.error)
            }
            StyleGalleryAction.DismissNotice -> styleNotice.value = null
        }
    }

    fun openLogsFolderInFileExplorer() {
        try {
            openUrlInBrowserUseCase(logsRepository.logsDirectory.absolutePath)
        } catch (e: Exception) {
            printError(TAG, "Failed to open logs dir: ${e.message.orEmpty()}", e)
        }
    }

    private fun settingsState(): Flow<SettingsViewState> {
        // Fork-only: the theme and the styles in one flow, as the combine below takes 30 at most
        val appearance: Flow<Appearance> = combineFlows(
            appSettingsService.theme,
            stylesService.selected,
            stylesService.styles,
            stylesService.library,
            styleNotice,
        ) { theme, style, styles, library, notice -> Appearance(theme, style, styles, library.unreadable, notice) }

        return combine(
            appSettingsService.scaleUi,
            appearance,
            appSettingsService.linesHeightType,
            appSettingsService.dateFormatUseDefault,
            appSettingsService.dateFormatCustomFormat,
            appSettingsService.dateFormatIs24h,
            appSettingsService.dateFormatUseRelative,
            appSettingsService.avatarProvider,
            appSettingsService.swapStatusPanes,
            appSettingsService.confirmHunkAndLineDiscards,
            appSettingsService.diffWrapLines,
            appSettingsService.pullWithRebase,
            appSettingsService.pushWithLease,
            appSettingsService.remoteOperationsWithGit,
            appSettingsService.worktreesRefreshInterval,
            appSettingsService.worktreesUntouchedDays,
            appSettingsService.worktreeLabels,
            appSettingsService.conflictOpener,
            appSettingsService.conflictResolverLayout,
            appSettingsService.conflictResolverWrapLines,
            appSettingsService.commitMessageHints,
            appSettingsService.fastForwardMerge,
            appSettingsService.autoStashOnMerge,
            appSettingsService.cloneDefaultDirectory,
            appSettingsService.cacheCredentialsInMemory,
            appSettingsService.terminalPath,
            appSettingsService.gitExecutablePath,
            gitExecutable,
        ) { scaleUi,
            appearance,
            linesHeightType,
            dateFormatUseDefault,
            dateFormatCustomFormat,
            dateFormatIs24h,
            dateFormatUseRelative,
            avatarProvider,
            swapStatusPanes,
            confirmHunkAndLineDiscards,
            diffWrapLines,
            pullWithRebase,
            pushWithLease,
            remoteOperationsWithGit,
            worktreesRefreshInterval,
            worktreesUntouchedDays,
            worktreeLabels,
            conflictOpener,
            conflictResolverLayout,
            conflictResolverWrapLines,
            commitMessageHints,
            fastForwardMerge,
            autoStashOnMerge,
            cloneDefaultDirectory,
            cacheCredentialsInMemory,
            terminalPath,
            gitExecutablePath,
            gitExecutable ->

            SettingsViewState(
                scaleUi,
                appearance.theme,
                appearance.style,
                appearance.styles,
                appearance.unreadableStyles,
                appearance.styleNotice,
                linesHeightType,
                dateFormatUseDefault,
                dateFormatCustomFormat,
                dateFormatIs24h,
                dateFormatUseRelative,
                avatarProvider,
                swapStatusPanes,
                confirmHunkAndLineDiscards,
                diffWrapLines,
                pullWithRebase,
                pushWithLease,
                remoteOperationsWithGit,
                worktreesRefreshInterval,
                worktreesUntouchedDays,
                worktreeLabels,
                conflictOpener,
                conflictResolverLayout,
                conflictResolverWrapLines,
                commitMessageHints,
                fastForwardMerge,
                autoStashOnMerge,
                cloneDefaultDirectory,
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
            style = BuiltInStyles.DEFAULT,
            styles = BuiltInStyles.ALL,
            unreadableStyles = emptyList(),
            styleNotice = null,
            linesHeightType = LinesHeightType.SPACED,
            dateFormatUseDefault = false,
            dateFormatCustomFormat = "",
            dateFormatIs24h = false,
            dateFormatUseRelative = false,
            avatarProvider = AvatarProviderType.Gravatar,
            swapStatusPanes = false,
            confirmHunkAndLineDiscards = true,
            diffWrapLines = true,
            pullWithRebase = false,
            pushWithLease = false,
            remoteOperationsWithGit = false,
            worktreesRefreshInterval = WorktreesRefreshIntervals.DEFAULT_SECONDS,
            worktreesUntouchedDays = WorktreesUntouchedDays.DEFAULT_DAYS,
            worktreeLabels = emptyList(),
            conflictOpener = ConflictOpener.DEFAULT,
            conflictResolverLayout = ConflictResolverLayout.DEFAULT,
            conflictResolverWrapLines = true,
            commitMessageHints = CommitMessageHints(),
            fastForwardMerge = false,
            autoStashOnMerge = false,
            cloneDefaultDirectory = "",
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
    /** The style that draws Leaf, and every style there is (fork-only). */
    val style: StyleDefinition,
    val styles: List<StyleDefinition>,
    /** Files in the styles folder that couldn't be read, and what the last import or export gave. */
    val unreadableStyles: List<UnreadableStyleFile>,
    val styleNotice: StyleNotice?,
    val linesHeightType: LinesHeightType,
    val dateFormatUseDefault: Boolean,
    val dateFormatCustomFormat: String,
    val dateFormatIs24h: Boolean,
    val dateFormatUseRelative: Boolean,
    val avatarProvider: AvatarProviderType,
    val swapStatusPanes: Boolean,
    /** Whether discarding a hunk or a line asks first. */
    val confirmHunkAndLineDiscards: Boolean,
    /** Whether the diff pane wraps long lines. */
    val diffWrapLines: Boolean,
    val pullWithRebase: Boolean,
    val pushWithLease: Boolean,
    val remoteOperationsWithGit: Boolean,
    /** Seconds between two refreshes of the worktree list, 0 for none. */
    val worktreesRefreshInterval: Int,
    /** Days without a change after which a worktree shows as idle, 0 for never. */
    val worktreesUntouchedDays: Int,
    /** The user's rules for labelling worktrees, see [AppConfig.WorktreeLabels]. */
    val worktreeLabels: List<WorktreeLabelRule>,
    /** What double-clicking a conflicted file and the banner's Resolve conflicts open. */
    val conflictOpener: ConflictOpener,
    /** How the built-in resolver lays out the sides and the result. */
    val conflictResolverLayout: ConflictResolverLayout,
    /** Whether the built-in resolver wraps long lines. */
    val conflictResolverWrapLines: Boolean,
    /** The commit field's helpers: the subject's count, the ruler, Conventional Commits suggestions. */
    val commitMessageHints: CommitMessageHints,
    val fastForwardMerge: Boolean,
    val autoStashOnMerge: Boolean,
    val cloneDefaultDirectory: String?,
    val cacheCredentialsInMemory: Boolean,
    val terminalPath: String?,
    val gitExecutablePath: String?,
    /** Null while git is being located. */
    val gitExecutable: Either<GitExecutable, GitCliError>?,
)

sealed interface SettingsAction {
    data class SetConfig(val configuration: AppConfig) : SettingsAction
    data object OpenLogsFolder : SettingsAction

    /** Fork-only: the style gallery's. */
    data class Styles(val action: StyleGalleryAction) : SettingsAction
}

/** Settings → Appearance's theme and styles, in one flow (fork-only). */
data class Appearance(
    val theme: Theme,
    val style: StyleDefinition,
    val styles: List<StyleDefinition>,
    val unreadableStyles: List<UnreadableStyleFile>,
    val styleNotice: StyleNotice?,
)
