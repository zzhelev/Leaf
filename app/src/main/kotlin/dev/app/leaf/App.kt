@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation3.runtime.NavKey
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.app_menu_check_for_updates
import dev.app.leaf.app.generated.resources.app_menu_help
import dev.app.leaf.app.generated.resources.logo
import dev.app.leaf.avatarproviders.GravatarAvatarProvider
import dev.app.leaf.avatarproviders.NoneAvatarProvider
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.systemSeparator
import dev.app.leaf.data.git.signers.GpgProgramSigner
import dev.app.leaf.data.git.signers.SshProgramSigner
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.sorting.DiscardedFiles
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.lfs.AppLfsFactory
import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.ColorsScheme
import dev.app.leaf.theme.dialogOverlay
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.AppChrome
import dev.app.leaf.ui.AppTab
import dev.app.leaf.ui.AppViewModel
import dev.app.leaf.ui.LocalAppChrome
import dev.app.leaf.ui.LocalWindowMinimized
import dev.app.leaf.ui.components.TabsRow
import dev.app.leaf.ui.components.TabInformation
import dev.app.leaf.ui.context_menu.AppPopupMenu
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.dialogs.FastForwardOffer
import dev.app.leaf.ui.dialogs.QuitDialog
import dev.app.leaf.viewmodels.RepositoryTabViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.util.LfsFactory
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.io.File
import java.nio.file.Paths
import javax.inject.Inject
import javax.swing.SwingUtilities

private const val TAG = "App"
private const val MAX_CHARS_CURRENT_TAB_NAME = 250
private const val NEW_TAB_DEFAULT_NAME = "New tab"

sealed interface Screen : NavKey {
    data object Welcome : Screen
    data object RepositoryLoading : Screen
    data object RepositoryOpen : Screen
    data object Settings : Screen
    data object CloneRepository : Screen
    data class BranchRename(val ref: Branch) : Screen
    data class BranchChangeUpstream(val ref: Branch) : Screen
    data class BranchCreate(val targetCommit: Commit?) : Screen
    data class BranchDelete(val branch: Branch) : Screen
    data class TagCreate(val targetCommit: Commit) : Screen
    data class TagDelete(val tag: Tag) : Screen
    data class BranchReset(val targetCommit: Commit) : Screen
    data class AddEditRemote(val remote: Remote?) : Screen
    data class Error(val error: CompletedTask.Failure) : Screen
    /** git couldn't put back the changes that it set aside, which are in the stash list (fork-only). */
    data class ChangesInStash(val notice: AutostashNotice) : Screen
    data object SubmoduleAdd : Screen
    data class HttpCredentials(val credentialsRequest: CredentialsRequest.HttpCredentialsRequest) : Screen
    data class SshCredentials(val credentialsRequest: CredentialsRequest.SshCredentialsRequest) : Screen
    data class SshHostKey(val request: CredentialsRequest.SshHostKeyRequest) : Screen
    data class AskpassPrompt(val request: CredentialsRequest.PromptRequest) : Screen
    data class AskpassConfirm(val request: CredentialsRequest.ConfirmRequest) : Screen
    data class LfsCredentials(val credentialsRequest: CredentialsRequest.LfsCredentialsRequest) : Screen
    data object QuickActions : Screen
    data object SignOffData : Screen
    data object Author : Screen
    data object StashWithMessage : Screen
    data object CheckForUpdates : Screen
    /**
     * Asks before [action], which [onConfirm] runs. With [onStopAsking], the dialog offers "Don't ask again", and
     * calls it before [onConfirm] when that's checked. Pass [onConfirm] in the parentheses: a trailing lambda would be
     * [onStopAsking].
     */
    data class ConfirmAction(
        val action: ConfirmableAction,
        val onConfirm: () -> Unit,
        val onStopAsking: (() -> Unit)? = null,
    ) : Screen
    /** Asks whether to fast-forward [offer]'s local branch, then [onCheckout] checks it out. */
    data class FastForwardOnCheckout(
        val offer: FastForwardOffer,
        val onCheckout: (fastForward: Boolean) -> Unit,
    ) : Screen
    data class DiscardFolderChanges(val folderPath: String, val discard: DiscardedFiles) : Screen
    /** Adds ignore rules for untracked files or a folder (fork-only). */
    data class Ignore(val request: IgnoreRequest) : Screen
    /** Removes [worktree] (fork-only), or with [discard] discards it: its changes and its branch go too. */
    data class WorktreeRemove(val worktree: Worktree, val discard: Boolean) : Screen
    data class WorktreeLock(val worktree: Worktree) : Screen
    /** Squash-merges or merges [worktree]'s branch into [target], the base's local branch (fork-only). */
    data class WorktreeMergeIntoBase(val worktree: Worktree, val kind: WorktreeMergeKind, val target: String) : Screen
    /** Rebases [worktree]'s branch onto [base], the base branch (fork-only). */
    data class WorktreeRebaseOntoBase(val worktree: Worktree, val base: String) : Screen
    /** Creates a worktree (fork-only) from [start]: the base branch, a branch or a commit. */
    data class WorktreeCreate(val start: NewWorktreeStart) : Screen
    data object WorktreesPrune : Screen
    data object WorktreesCleanUp : Screen
    /** Converts the worktree's [index] to the format JGit reads (fork-only), after showing the commands. */
    data class ConvertIndex(val index: UnsupportedIndex, val onConvert: () -> Unit) : Screen
}


class App @Inject constructor(
    private val appStateManager: AppStateManager,
    private val appSettings: AppSettingsService,
    private val appEnvInfo: AppEnvInfo,
    private val appViewModel: AppViewModel,
    private val tempFilesManager: TempFilesManager,
    private val logsRepository: LogsRepository,
    private val gpgSigner: GpgProgramSigner,
    private val sshSigner: SshProgramSigner,
    private val lfsFactory: AppLfsFactory,
    private val loginShellEnvironment: LoginShellEnvironment,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalFoundationApi::class)
    suspend fun start(args: Array<String>) {
        logsRepository.initLogging()
        // In the background, so that the shell has usually finished before the first hook needs its PATH
        loginShellEnvironment.prewarm()

        Signers.set(GpgConfig.GpgFormat.OPENPGP, gpgSigner)
        Signers.set(GpgConfig.GpgFormat.SSH, sshSigner)

        val windowPlacement = WindowPlacement.Maximized //appSettingsRepository.windowPlacement.toWindowPlacement
        val dirToOpen = getDirToOpen(args)

        appEnvInfo.isFlatpak = args.contains("--flatpak")
        appStateManager.loadRepositoriesTabs()

        appViewModel.loadPersistedTabs()
        LfsFactory.setInstance(lfsFactory)

        if (dirToOpen != null)
            addDirTab(dirToOpen)

        val themeInitial = appSettings.theme.first()
        val customThemeInitial = appSettings.customTheme.firstOrNull()
        val accentStyleInitial = appSettings.accentStyle.first()
        val scaleInitial = appSettings.scaleUi.firstOrNull()
        val linesHeightTypeInitial = appSettings.linesHeightType.first()
        val avatarProviderTypeInitial = appSettings.avatarProvider.first()
        val dateFormatUseDefaultInitial = appSettings.dateFormatUseDefault.first()
        val dateFormatCustomFormatInitial = appSettings.dateFormatCustomFormat.first()
        val dateFormatIs24hInitial = appSettings.dateFormatIs24h.first()
        val dateFormatUseRelativeInitial = appSettings.dateFormatUseRelative.first()

        application {
            val theme by appSettings.theme.collectAsState(themeInitial)
            val customThemeRaw by appSettings.customTheme.collectAsState(customThemeInitial)
            val accentStyle by appSettings.accentStyle.collectAsState(accentStyleInitial)
            val customTheme = remember(customThemeRaw) {
                val customThemeRaw = customThemeRaw
                if (customThemeRaw != null) {
                    // TODO move to use case, this is only a temporary solution
                    // TODO 2: Perhaps serialize in a UI module class as it's related to UI logic and not domain
                    try {
                        json.decodeFromString<ColorsScheme>(customThemeRaw)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                } else {
                    null
                }
            }
            val scale = appSettings.scaleUi.collectAsState(scaleInitial).value
            val linesHeightType by appSettings.linesHeightType.collectAsState(linesHeightTypeInitial)
            val avatarProviderType by appSettings.avatarProvider.collectAsState(avatarProviderTypeInitial)
            val dateFormatUseDefault by appSettings.dateFormatUseDefault.collectAsState(dateFormatUseDefaultInitial)
            val dateFormatCustomFormat by appSettings.dateFormatCustomFormat.collectAsState(
                dateFormatCustomFormatInitial
            )
            val dateFormatIs24h by appSettings.dateFormatIs24h.collectAsState(dateFormatIs24hInitial)
            val dateFormatUseRelative by appSettings.dateFormatUseRelative.collectAsState(dateFormatUseRelativeInitial)

            val dateFormat by derivedStateOf {
                DateTimeFormat(
                    useSystemDefault = dateFormatUseDefault,
                    customFormat = dateFormatCustomFormat,
                    is24hours = dateFormatIs24h,
                    useRelativeDate = dateFormatUseRelative,
                )
            }

            val windowState = rememberWindowState(
                placement = windowPlacement,
                size = DpSize(1280.dp, 720.dp)
            )

            // Save window state for next time the Window is started
            // TODO appSettings.windowPlacement = windowState.placement.preferenceValue

            val currentTab = appViewModel.currentTab.collectAsState().value
            val tabName = currentTab?.name?.collectAsState()?.value
            val worktreeName = currentTab?.worktreeName?.collectAsState()?.value
            val currentTabName = listOfNotNull(tabName ?: NEW_TAB_DEFAULT_NAME, worktreeName)
                .joinToString(" · ")
                .take(MAX_CHARS_CURRENT_TAB_NAME)

            LaunchedEffect(currentTab, tabName) {
                appViewModel.updatePersistedTabs()
            }

            // Fork-only: every way of quitting asks first while a conflict resolver has changes that aren't saved
            val quitNow: () -> Unit = {
                SwingUtilities.invokeLater {
                    tempFilesManager.clearAll()
                    this@application.exitApplication()
                }
            }

            DisposableEffect(Unit) {
                // macOS: Cmd+Q, Quit in the app menu and the Dock, logging out. Without a handler, the JDK exits at once.
                val desktop = desktopSupporting(Desktop.Action.APP_QUIT_HANDLER)

                desktop?.setQuitHandler { _, response ->
                    appViewModel.quit(onQuit = quitNow, onCancelled = response::cancelQuit)
                }

                onDispose { desktop?.setQuitHandler(null) }
            }

            val quitPrompt = appViewModel.quitPrompt.collectAsState().value

            Window(
                title = "${System.getenv("title") ?: AppConstants.APP_NAME} - $currentTabName",
                onCloseRequest = { appViewModel.quit(onQuit = quitNow) },
                state = windowState,
                icon = painterResource(Res.drawable.logo),
            ) {
                // Windows and Linux would show a Swing menu bar inside the window, which doesn't look like the rest of
                // Leaf. macOS shows it in the system menu bar, where apps usually let you check for updates.
                if (currentOs == OS.MAC) {
                    MenuBar {
                        Menu(stringResource(Res.string.app_menu_help)) {
                            Item(
                                text = stringResource(Res.string.app_menu_check_for_updates),
                                onClick = {
                                    val backStack = currentTab?.data?.backStack

                                    if (backStack != null && Screen.CheckForUpdates !in backStack) {
                                        backStack.add(Screen.CheckForUpdates)
                                    }
                                },
                            )
                        }
                    }
                }

                val compositionValues: MutableList<ProvidedValue<*>> =
                    mutableListOf(LocalTextContextMenu provides AppPopupMenu())

                if (scale != null) {
                    compositionValues.add(LocalDensity provides Density(scale, 1f))
                }

                val avatarProvider = when (avatarProviderType) {
                    AvatarProviderType.Gravatar -> GravatarAvatarProvider()
                    AvatarProviderType.None -> NoneAvatarProvider()
                }

                compositionValues.add(LocalAvatarProvider provides avatarProvider)
                compositionValues.add(LocalDateTimeFormat provides dateFormat)
                compositionValues.add(LocalWindowMinimized provides windowState.isMinimized)

                // A quit from the Dock or a logout can come while Leaf is minimized or in the background
                LaunchedEffect(quitPrompt != null) {
                    if (quitPrompt != null) {
                        windowState.isMinimized = false
                        window.toFront()

                        desktopSupporting(Desktop.Action.APP_REQUEST_FOREGROUND)?.requestForeground(false)
                    }
                }

                CompositionLocalProvider(
                    values = compositionValues.toTypedArray()
                ) {
                    AppTheme(
                        selectedTheme = theme,
                        customTheme = customTheme,
                        accentStyle = accentStyle,
                        linesHeightType = linesHeightType,
                    ) {
                        Box(modifier = Modifier.background(MaterialTheme.colors.background)) {
                            AppTabs()
                        }

                        if (quitPrompt != null) {
                            Dialog(
                                onDismissRequest = { appViewModel.answerQuit(null) },
                                properties = DialogProperties(
                                    scrimColor = MaterialTheme.colors.dialogOverlay,
                                    dismissOnClickOutside = false,
                                    usePlatformDefaultWidth = false,
                                ),
                            ) {
                                QuitDialog(quitPrompt, onAnswer = appViewModel::answerQuit)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun addDirTab(dirToOpen: File) {
        val absolutePath = dirToOpen.normalize().absolutePath
            .removeSuffix(systemSeparator)
            .removeSuffix("$systemSeparator.git")

        appViewModel.addNewTabFromPath(absolutePath, true)
    }


    @Composable
    fun AppTabs() {
        val tabs by appViewModel.tabs.collectAsState()
        val currentTab = appViewModel.currentTab.collectAsState().value
        // Fork-only: the conflict resolver's Focus hides the tab bar
        val chrome = remember { AppChrome() }

        if (currentTab != null) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colors.background)
                    .onPreviewKeyEvent {
                        when {
                            it.matchesBinding(KeybindingOption.OPEN_NEW_TAB) -> {
                                appViewModel.addNewEmptyTab()
                                true
                            }

                            it.matchesBinding(KeybindingOption.CLOSE_CURRENT_TAB) -> {
                                appViewModel.closeTab(currentTab)
                                true
                            }

                            it.matchesBinding(KeybindingOption.CHANGE_CURRENT_TAB_LEFT) -> {
                                val tabToSelect = tabs.getOrNull(tabs.indexOf(currentTab) - 1)
                                if (tabToSelect != null) {
                                    appViewModel.selectTab(tabToSelect)
                                }
                                true
                            }

                            it.matchesBinding(KeybindingOption.CHANGE_CURRENT_TAB_RIGHT) -> {
                                val tabToSelect = tabs.getOrNull(tabs.indexOf(currentTab) + 1)
                                if (tabToSelect != null) {
                                    appViewModel.selectTab(tabToSelect)
                                }
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                if (!chrome.tabBarHidden) {
                    Tabs(
                        tabsInformationList = tabs,
                        currentTab = currentTab,
                        onAddedTab = {
                            appViewModel.addNewEmptyTab()
                        },
                        onCloseTab = { tab ->
                            appViewModel.closeTab(tab)
                        }
                    )
                }

                CompositionLocalProvider(LocalTab provides currentTab.data, LocalAppChrome provides chrome) {
                    TabContent(currentTab.data)
                }
            }
        }
    }

    @Composable
    fun Tabs(
        tabsInformationList: List<TabInformation<RepositoryTabViewModel>>,
        currentTab: TabInformation<RepositoryTabViewModel>?,
        onAddedTab: () -> Unit,
        onCloseTab: (TabInformation<RepositoryTabViewModel>) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabsRow(
                tabs = tabsInformationList,
                defaultTabName = NEW_TAB_DEFAULT_NAME,
                currentTab = currentTab,
                onTabSelected = { selectedTab ->
                    appViewModel.selectTab(selectedTab)
                },
                onTabClosed = onCloseTab,
                onAddNewTab = onAddedTab,
                onMoveTab = { fromIndex, toIndex ->
                    appViewModel.onMoveTab(fromIndex, toIndex)
                },
            )
        }
    }

    private fun getDirToOpen(args: Array<String>): File? {
        if (args.isNotEmpty()) {
            val repoToOpen = args.first()
            val path = Paths.get(repoToOpen)

            val repoDir = if (!path.isAbsolute)
                File(System.getProperty("user.dir"), repoToOpen)
            else
                path.toFile()

            return if (repoDir.isDirectory)
                repoDir
            else
                null
        }

        return null
    }
}

/** The desktop, when it supports [action] (fork-only). Without desktop support, [Desktop.getDesktop] throws. */
private fun desktopSupporting(action: Desktop.Action): Desktop? =
    if (Desktop.isDesktopSupported()) Desktop.getDesktop().takeIf { it.isSupported(action) } else null

@Composable
private fun TabContent(viewModel: RepositoryTabViewModel) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colors.background)
            .fillMaxSize(),
    ) {
        AppTab(viewModel)
    }
}

@Composable
fun LoadingRepository(repoPath: String) {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Opening repository", fontSize = 36.sp, color = MaterialTheme.colors.onBackground)
            Text(repoPath, fontSize = 24.sp, color = MaterialTheme.colors.onBackgroundSecondary)
        }
    }
}
