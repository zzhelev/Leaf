package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.data.UserSettingsDataStore
import dev.app.leaf.data.repositories.configuration.mappers.AvatarProviderMapper
import dev.app.leaf.data.repositories.configuration.mappers.LinesHeightMapper
import dev.app.leaf.data.repositories.configuration.mappers.TextDiffViewTypeMapper
import dev.app.leaf.data.repositories.configuration.mappers.ThemeMapper
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ProxyType
import dev.app.leaf.domain.models.CommitChangesSectionSizes
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.domain.models.ui.AppWindowPlacement
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.sorting.SortSettingsCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import java.util.prefs.Preferences as LegacyPreferences

private const val PREF_LATEST_REPOSITORIES_TABS_OPENED = "latestRepositoriesTabsOpened"
private const val PREF_LATEST_REPOSITORY_TAB_SELECTED = "latestRepositoryTabSelected"
private const val PREF_LAST_OPENED_REPOSITORIES_PATH = "lastOpenedRepositoriesList"
private const val PREF_WINDOW_PLACEMENT = "windowsPlacement"
private const val PREF_FIRST_PANE_WIDTH = "firstPaneWidth"
private const val PREF_THIRD_PANE_WIDTH = "thirdPaneWidth"
private const val PREF_STATUS_STAGED_SHARE = "statusStagedShare"
private const val PREF_STATUS_COMMIT_FIELD_HEIGHT = "statusCommitFieldHeight"
private const val PREF_COMMIT_MESSAGE_HEIGHT = "commitMessageHeight"
private const val DEFAULT_FIRST_PANE_WIDTH = 220f
private const val DEFAULT_THIRD_PANE_WIDTH = 330f

private val scaleUiPreference get() = floatPreferencesKey("scale_ui")
private val themePreference get() = stringPreferencesKey("theme")
private val customThemePreference get() = stringPreferencesKey("custom_theme")
private val linesHeightPreference get() = stringPreferencesKey("lines_height")
private val swapStatusPanesPreference get() = booleanPreferencesKey("swap_status_panes")
private val diffDisplayFullFilePreference get() = booleanPreferencesKey("diff_display_full_file")
private val diffTextViewTypePreference get() = stringPreferencesKey("diff_text_view")
private val showChangesAsTreePreference get() = booleanPreferencesKey("show_changes_as_tree")
private val refPanelSettingsPreference get() = stringPreferencesKey("side_panel_sort")
private val filesChangedViewPreference get() = stringPreferencesKey("files_changed_view")

private val dateFormatUseDefaultPreference get() = booleanPreferencesKey("date_format_use_default")
private val dateFormatCustomFormatPreference get() = stringPreferencesKey("date_format_custom_format")
private val dateFormatIs24hPreference get() = booleanPreferencesKey("date_format_is_24h")
private val dateFormatUseRelativePreference get() = booleanPreferencesKey("date_format_use_relative")

private val avatarProviderPreference get() = stringPreferencesKey("avatar_provider")

private val fastForwardMergePreference get() = booleanPreferencesKey("fast_forward_merge")
private val autoStashOnMergePreference get() = booleanPreferencesKey("auto_stash_on_merge")
private val pullWithRebasePreference get() = booleanPreferencesKey("pull_with_rebase")
private val pushWithLeasePreference get() = booleanPreferencesKey("push_with_lease")
private val cloneDefaultDirectoryPreference get() = stringPreferencesKey("clone_default_directory")
private val gitExecutablePathPreference get() = stringPreferencesKey("git_executable_path")

private val useProxyPreference get() = booleanPreferencesKey("use_proxy")
private val proxyUseAuthPreference get() = booleanPreferencesKey("proxy_use_auth")
private val proxyProxyTypePreference get() = intPreferencesKey("proxy_type")
private val proxyHostNamePreference get() = stringPreferencesKey("proxy_host_name")
private val proxyPortNumberPreference get() = intPreferencesKey("proxy_port_number")
private val proxyHostUserPreference get() = stringPreferencesKey("proxy_host_user")
private val proxyHostPasswordPreference get() = stringPreferencesKey("proxy_host_password")
private val cacheCredentialsPreference get() = booleanPreferencesKey("cache_credentials_in_memory")
private val terminalPathPreference get() = stringPreferencesKey("terminal_path")

private val verifySslPreference get() = booleanPreferencesKey("verify_ssl")

operator fun <T> Flow<Preferences>.get(key: Preferences.Key<T>): Flow<T?> {
    return this.map { it[key] }
}

suspend fun <T> DataStore<Preferences>.setValue(key: Preferences.Key<T>, value: T?) {
    this.updateData {
        it.toMutablePreferences().also { preferences ->
            if (value == null) {
                preferences.remove(key)
            } else {
                preferences[key] = value
            }
        }
    }
}

class DataStoreAppSettingsRepository @Inject constructor(
    private val userSettingsDataStore: UserSettingsDataStore,
    private val themeMapper: ThemeMapper,
    private val linesHeightMapper: LinesHeightMapper,
    private val avatarProviderMapper: AvatarProviderMapper,
    private val textDiffViewTypeMapper: TextDiffViewTypeMapper,
) : AppSettingsRepository {
    private val preferences = userSettingsDataStore.preferences

    // UI
    override val scaleUi = preferences.data[scaleUiPreference]

    override val theme = preferences.data[themePreference].map { themeMapper.toDomain(it) }
    override val customTheme = preferences.data[customThemePreference]

    override val linesHeightType =
        preferences.data[linesHeightPreference].map { linesHeightMapper.toDomain(it) }

    override val dateFormatUseDefault get() = preferences.data[dateFormatUseDefaultPreference]
    override val dateFormatCustomFormat get() = preferences.data[dateFormatCustomFormatPreference]
    override val dateFormatIs24h get() = preferences.data[dateFormatIs24hPreference]
    override val dateFormatUseRelative get() = preferences.data[dateFormatUseRelativePreference]

    override val avatarProvider get() = preferences.data[avatarProviderPreference].map { avatarProviderMapper.toDomain(it) }
    override val swapStatusPanes get() = preferences.data[swapStatusPanesPreference]
    override val showChangesAsTree get() = preferences.data[showChangesAsTreePreference]
    override val diffDisplayFullFile get() = preferences.data[diffDisplayFullFilePreference]
    override val diffTextViewType get() = preferences.data[diffTextViewTypePreference].map { textDiffViewTypeMapper.toDomain(it) }
    override val refPanelSettings get() = preferences.data[refPanelSettingsPreference]
        .map { it?.let(SortSettingsCodec::decodeRefPanelSettings) }
    override val filesChangedView get() = preferences.data[filesChangedViewPreference]
        .map { it?.let(SortSettingsCodec::decodeFilesViewState) }

    // Git
    override val pullWithRebase get() = preferences.data[pullWithRebasePreference]
    override val pushWithLease get() = preferences.data[pushWithLeasePreference]
    override val fastForwardMerge get() = preferences.data[fastForwardMergePreference]
    override val autoStashOnMerge get() = preferences.data[autoStashOnMergePreference]
    override val cloneDefaultDirectory get() = preferences.data[cloneDefaultDirectoryPreference]
    override val gitExecutablePath get() = preferences.data[gitExecutablePathPreference]


    // Network
    override val useProxy get() = preferences.data[useProxyPreference]
    override val proxyUseAuth get() = preferences.data[proxyUseAuthPreference]
    override val proxyType get() = preferences.data[proxyProxyTypePreference].map { ProxyType.fromValue(it) }
    override val proxyHostName get() = preferences.data[proxyHostNamePreference]
    override val proxyPortNumber get() = preferences.data[proxyPortNumberPreference]
    override val proxyHostUser get() = preferences.data[proxyHostUserPreference]
    override val proxyHostPassword get() = preferences.data[proxyHostPasswordPreference]

    override val verifySsl get() = preferences.data[verifySslPreference]
    override val cacheCredentialsInMemory get() = preferences.data[cacheCredentialsPreference]

    // Tools
    override val terminalPath get() = preferences.data[terminalPathPreference]

    override suspend fun setConfiguration(appConfig: AppConfig) {
        preferences.apply {
            when (appConfig) {
                is AppConfig.AutoStashOnMerge -> setValue(autoStashOnMergePreference, appConfig.value)
                is AppConfig.CloneDefaultDirectory -> setValue(cloneDefaultDirectoryPreference, appConfig.value)
                is AppConfig.GitExecutablePath -> setValue(gitExecutablePathPreference, appConfig.value)
                is AppConfig.DateFormatCustomFormat -> setValue(dateFormatCustomFormatPreference, appConfig.value)
                is AppConfig.DateFormatIs24h -> setValue(dateFormatIs24hPreference, appConfig.value)
                is AppConfig.DateFormatUseDefault -> setValue(dateFormatUseDefaultPreference, appConfig.value)
                is AppConfig.DateFormatUseRelative -> setValue(dateFormatUseRelativePreference, appConfig.value)
                is AppConfig.FastForwardMerge -> setValue(fastForwardMergePreference, appConfig.value)
                is AppConfig.LinesHeight -> setValue(linesHeightPreference, linesHeightMapper.toData(appConfig.value))
                is AppConfig.UseProxy -> setValue(useProxyPreference, appConfig.value)
                is AppConfig.ProxyHostName -> setValue(proxyHostNamePreference, appConfig.value)
                is AppConfig.ProxyHostPassword -> setValue(proxyHostPasswordPreference, appConfig.value)
                is AppConfig.ProxyHostUser -> setValue(proxyHostUserPreference, appConfig.value)
                is AppConfig.ProxyPortNumber -> setValue(proxyPortNumberPreference, appConfig.value)
                is AppConfig.ProxyProxyType -> setValue(proxyProxyTypePreference, appConfig.value.value)
                is AppConfig.ProxyUseAuth -> setValue(proxyUseAuthPreference, appConfig.value)
                is AppConfig.PullWithRebase -> setValue(pullWithRebasePreference, appConfig.value)
                is AppConfig.PushWithLease -> setValue(pushWithLeasePreference, appConfig.value)
                is AppConfig.ScaleUi -> setValue(scaleUiPreference, appConfig.value)
                is AppConfig.CacheCredentialsInMemory -> setValue(cacheCredentialsPreference, appConfig.value)
                is AppConfig.AvatarProvider -> setValue(
                    avatarProviderPreference,
                    avatarProviderMapper.toData(appConfig.value)
                )

                is AppConfig.Theme -> setValue(themePreference, themeMapper.toData(appConfig.value))
                is AppConfig.CustomTheme -> setValue(customThemePreference, appConfig.value)
                is AppConfig.SwapStatusPanes -> setValue(swapStatusPanesPreference, appConfig.value)
                is AppConfig.DiffDisplayFullFile -> setValue(diffDisplayFullFilePreference, appConfig.value)
                is AppConfig.DiffTextViewType -> setValue(diffTextViewTypePreference, textDiffViewTypeMapper.toData(appConfig.value))
                is AppConfig.TerminalPath -> setValue(terminalPathPreference, appConfig.value)
                is AppConfig.RefPanel -> setValue(
                    refPanelSettingsPreference,
                    SortSettingsCodec.encodeRefPanelSettings(appConfig.value)
                )

                is AppConfig.FilesChangedView -> setValue(
                    filesChangedViewPreference,
                    SortSettingsCodec.encodeFilesViewState(appConfig.value)
                )
            }
        }
    }

    private val preferencesLegacy: LegacyPreferences =
        LegacyPreferences.userRoot().node(AppStorage.current.preferencesNode)

    override var latestTabsOpened: String
        get() = preferencesLegacy.get(PREF_LATEST_REPOSITORIES_TABS_OPENED, "")
        set(value) {
            preferencesLegacy.put(PREF_LATEST_REPOSITORIES_TABS_OPENED, value)
        }

    override var latestRepositoryTabSelected: Int
        get() = preferencesLegacy.getInt(PREF_LATEST_REPOSITORY_TAB_SELECTED, -1)
        set(value) {
            preferencesLegacy.putInt(PREF_LATEST_REPOSITORY_TAB_SELECTED, value)
        }

    override var latestOpenedRepositoriesPath: String
        get() = preferencesLegacy.get(PREF_LAST_OPENED_REPOSITORIES_PATH, "")
        set(value) {
            preferencesLegacy.put(PREF_LAST_OPENED_REPOSITORIES_PATH, value)
        }

    override var firstPaneWidth: Float
        get() {
            return preferencesLegacy.getFloat(PREF_FIRST_PANE_WIDTH, DEFAULT_FIRST_PANE_WIDTH)
        }
        set(value) {
            preferencesLegacy.putFloat(PREF_FIRST_PANE_WIDTH, value)
        }

    override var thirdPaneWidth: Float
        get() {
            return preferencesLegacy.getFloat(PREF_THIRD_PANE_WIDTH, DEFAULT_THIRD_PANE_WIDTH)
        }
        set(value) {
            preferencesLegacy.putFloat(PREF_THIRD_PANE_WIDTH, value)
        }

    override var statusSectionSizes: StatusSectionSizes
        get() {
            val defaults = StatusSectionSizes()

            return StatusSectionSizes(
                stagedShare = preferencesLegacy.getFloat(PREF_STATUS_STAGED_SHARE, defaults.stagedShare),
                commitFieldHeight = preferencesLegacy.getFloat(PREF_STATUS_COMMIT_FIELD_HEIGHT, defaults.commitFieldHeight),
            ).orDefaults()
        }
        set(value) {
            preferencesLegacy.putFloat(PREF_STATUS_STAGED_SHARE, value.stagedShare)
            preferencesLegacy.putFloat(PREF_STATUS_COMMIT_FIELD_HEIGHT, value.commitFieldHeight)
        }

    override var commitChangesSectionSizes: CommitChangesSectionSizes
        get() {
            val defaults = CommitChangesSectionSizes()

            return CommitChangesSectionSizes(
                messageHeight = preferencesLegacy.getFloat(PREF_COMMIT_MESSAGE_HEIGHT, defaults.messageHeight),
            ).orDefaults()
        }
        set(value) {
            preferencesLegacy.putFloat(PREF_COMMIT_MESSAGE_HEIGHT, value.messageHeight)
        }


    var windowPlacement: AppWindowPlacement
        get() {
            // FIXME Preference doing nothing here
            val placement = preferencesLegacy.getInt(PREF_WINDOW_PLACEMENT, 0)

            return AppWindowPlacement.MAXIMIZED
        }
        set(placement) {
            //preferences.putInt(PREF_WINDOW_PLACEMENT, placement.value)
        }
}

fun initPreferencesPath() {
    if (currentOs == OS.LINUX) {
        val xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME")

        val settingsPath = if (xdgConfigHome.isNullOrBlank()) {
            val home = System.getProperty("user.home").orEmpty()
            "$home/.config/${AppStorage.current.directoryName}"
        } else {
            "$xdgConfigHome/${AppStorage.current.directoryName}"
        }

        System.setProperty("java.util.prefs.userRoot", settingsPath)
    }
}

/** The settings file, stored as JSON by [JsonPreferencesSerializer]. */
fun getPreferencesPath(): String = getSettingsDirectory() + systemSeparator + "user_prefs.json"

/** DataStore's protobuf settings file from before the JSON format. [ProtobufPreferencesMigration] moves it over. */
fun getProtobufPreferencesPath(): String = getSettingsDirectory() + systemSeparator + "user_prefs.preferences_pb"

// TODO verify this after refactor. Are the paths for mac and windows correct?
private fun getSettingsDirectory(): String {
    val home = System.getProperty("user.home").orEmpty()

    return when (currentOs) {
        OS.LINUX -> {
            val xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME")

            val settingsPath = if (xdgConfigHome.isNullOrBlank()) {
                "$home/.config/${AppStorage.current.directoryName}"
            } else {
                "$xdgConfigHome/${AppStorage.current.directoryName}"
            }

            settingsPath
        }

        OS.MAC -> {
            "$home/Library/Application Support/${AppStorage.current.directoryName}"
        }

        else -> {
            System.getProperty("java.util.prefs.userRoot") ?: "$home$systemSeparator${AppStorage.current.directoryName}"
        }
    }
}