package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import dev.app.leaf.LoadingRepository
import dev.app.leaf.ProcessingScreen
import dev.app.leaf.Screen
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.lfs
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsState
import dev.app.leaf.domain.models.NotificationData
import dev.app.leaf.domain.models.NotificationType
import dev.app.leaf.domain.models.RepositorySelectionState
import dev.app.leaf.domain.models.successTitle
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.repositoryopen.RepositoryOpenPage
import dev.app.leaf.tabViewModel
import dev.app.leaf.theme.dialogOverlay
import dev.app.leaf.ui.components.Notification
import dev.app.leaf.ui.dialogs.*
import dev.app.leaf.ui.dialogs.base.UserPasswordDialog
import dev.app.leaf.ui.dialogs.errors.ErrorDialog
import dev.app.leaf.ui.dialogs.settings.SettingsDialog
import dev.app.leaf.viewmodels.RepositoryTabViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.time.Duration.Companion.milliseconds


fun <T : NavKey> NavBackStack<T>.addAndRemovePrevious(item: T) {
    this.add(item)

    repeat(lastIndex) {
        this.removeFirst()
    }
}


@Composable
fun AppTab(
    repositoryTabViewModel: RepositoryTabViewModel,
) {
    val errors by repositoryTabViewModel.severeErrors.collectAsState()
    val lastError = errors.firstOrNull()

    val tasks = repositoryTabViewModel.notifications.collectAsState().value
        .toList()
        .sortedBy { it.date }

    val repositorySelectionStatus = repositoryTabViewModel.repositorySelectionState.collectAsState()
    val repositorySelectionStatusValue = repositorySelectionStatus.value
    val processingTask = repositoryTabViewModel.processingTask.collectAsState().value
    val taskProgress = repositoryTabViewModel.taskProgress.collectAsState().value

    val backStack = repositoryTabViewModel.backStack
    val dialogStrategy = remember { DialogSceneStrategy<NavKey>() }


    LaunchedEffect(repositoryTabViewModel) {
        repositoryTabViewModel.loadTab()
    }

    LaunchedEffect(lastError) {
        lastError?.let {
            backStack.add(Screen.Error(it))
        }
    }

    LaunchedEffect(repositorySelectionStatusValue) {
        val screen = when (repositorySelectionStatusValue) {
            RepositorySelectionState.None -> Screen.Welcome

            RepositorySelectionState.Unknown, is RepositorySelectionState.Opening -> Screen.RepositoryLoading

            is RepositorySelectionState.Open -> Screen.RepositoryOpen
        }

        if (!backStack.contains(screen)) {
            backStack.addAndRemovePrevious(screen)
        }
    }

    val dialogsMetadata =
        DialogSceneStrategy.dialog(
            dialogProperties = DialogProperties(
                scrimColor = MaterialTheme.colors.dialogOverlay,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            )
        )

    val credentialsState by repositoryTabViewModel.credentialsState.collectAsState()

    LaunchedEffect(credentialsState) {
        val destination = when (val state = credentialsState) {
            is CredentialsRequest.HttpCredentialsRequest -> Screen.HttpCredentials(state)
            is CredentialsRequest.LfsCredentialsRequest -> Screen.LfsCredentials(state)
            is CredentialsRequest.SshCredentialsRequest -> Screen.SshCredentials(state)
            is CredentialsRequest.SshHostKeyRequest -> Screen.SshHostKey(state)
            is CredentialsRequest.PromptRequest -> Screen.AskpassPrompt(state)
            is CredentialsRequest.ConfirmRequest -> Screen.AskpassConfirm(state)
            else -> null
        }

        if (destination != null) {
            backStack.add(destination)
        } else if (credentialsState is CredentialsState.None) {
            // The operation that asked ended or was cancelled, so its dialog has nothing left to answer
            backStack.removeAll { it.isCredentialsDialog() }
        }
    }

    Box {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colors.surface)
                .fillMaxSize()
        ) {

            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    onBack = {},
                    sceneStrategies = listOf(dialogStrategy),
                    entryProvider = entryProvider {
                        entry<Screen.Welcome> {
                            WelcomePage(
                                repositoryTabViewModel = repositoryTabViewModel,
                                onShowCloneDialog = { backStack.add(Screen.CloneRepository) },
                                onShowSettings = { backStack.add(Screen.Settings) }
                            )
                        }
                        entry<Screen.RepositoryLoading> {
                            val path = (repositorySelectionStatusValue as? RepositorySelectionState.Opening)?.path

                            if (path != null) {
                                LoadingRepository(path)
                            }

                        }
                        entry<Screen.RepositoryOpen> { entry ->
                            val repositoryOpenViewModel = tabViewModel(entry) { it.repositoryOpenViewModel() }

                            RepositoryOpenPage(
                                repositoryOpenViewModel = repositoryOpenViewModel,
                                onNavigate = { backStack.add(it) }
                            )
                        }
                        entry<Screen.Settings>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            val viewModel = tabViewModel(entry, { it.settingsViewModel() })
                            SettingsDialog(
                                settingsViewModel = viewModel,
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.CloneRepository>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            CloneDialog(
                                cloneViewModel = tabViewModel(entry) { it.cloneViewModel() },
                                onClose = { backStack.removeLastOrNull() },
                                onOpenRepository = { dir ->
                                    repositoryTabViewModel.openRepository(dir.absolutePath)
                                },
                            )
                        }
                        entry<Screen.BranchRename>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            RenameBranchDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider
                                        .renameBranchDialogViewModelFactory()
                                        .create(entry.ref)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.BranchCreate>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            CreateBranchDialog(
                                viewModel = tabViewModel(entry) {
                                    it.createBranchViewModelFactory().create(entry.targetCommit)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.BranchDelete>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            DeleteBranchDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider.deleteBranchViewModelFactory().create(entry.branch)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.BranchChangeUpstream>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            SetDefaultUpstreamBranchDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider
                                        .setUpstreamBranchDialogViewModelFactory()
                                        .create(entry.ref)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.Error>( // TODO Navigating from a dialog (such as add submodule) to this produces a crash
                            metadata = dialogsMetadata
                        ) {
                            ErrorDialog(
                                error = it.error,
                                onAccept = {
                                    backStack.removeLastOrNull()
                                    repositoryTabViewModel.completedTaskAlreadyShown(it.error)
                                },
                            )
                        }
                        entry<Screen.AddEditRemote>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            AddEditRemoteDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider
                                        .addEditRemoteViewModelFactory()
                                        .create(entry.remote)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.SubmoduleAdd>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            AddSubmodulesDialog(
                                viewModel = tabViewModel(entry) { it.submoduleDialogViewModel() },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.HttpCredentials>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            HttpCredentialsDialog(
                                user = entry.credentialsRequest.user,
                                askPassword = entry.credentialsRequest.askPassword,
                                onDismiss = {
                                    repositoryTabViewModel.credentialsDenied()
                                    backStack.removeLastOrNull()
                                },
                                onAccept = { user, password ->
                                    repositoryTabViewModel.httpCredentialsAccepted(user, password)
                                    backStack.removeLastOrNull()
                                }
                            )
                        }
                        entry<Screen.SshCredentials>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            SshPasswordDialog(
                                credentialsRequest = entry.credentialsRequest,
                                onReject = {
                                    repositoryTabViewModel.credentialsDenied()
                                    backStack.removeLastOrNull()
                                },
                                onAccept = { password ->
                                    repositoryTabViewModel.sshCredentialsAccepted(password)
                                    backStack.removeLastOrNull()
                                }
                            )
                        }
                        entry<Screen.SshHostKey>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            SshHostKeyDialog(
                                request = entry.request,
                                onTrust = {
                                    repositoryTabViewModel.sshHostKeyTrusted()
                                    backStack.removeLastOrNull()
                                },
                                onReject = {
                                    repositoryTabViewModel.credentialsDenied()
                                    backStack.removeLastOrNull()
                                },
                            )
                        }
                        entry<Screen.AskpassPrompt>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            AskpassPromptDialog(
                                request = entry.request,
                                onAnswer = { answer ->
                                    repositoryTabViewModel.promptAnswered(answer)
                                    backStack.removeLastOrNull()
                                },
                                onReject = {
                                    repositoryTabViewModel.credentialsDenied()
                                    backStack.removeLastOrNull()
                                },
                            )
                        }
                        entry<Screen.AskpassConfirm>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            AskpassConfirmDialog(
                                request = entry.request,
                                onConfirm = {
                                    repositoryTabViewModel.confirmed()
                                    backStack.removeLastOrNull()
                                },
                                onReject = {
                                    repositoryTabViewModel.credentialsDenied()
                                    backStack.removeLastOrNull()
                                },
                            )
                        }
                        entry<Screen.LfsCredentials>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            // TODO Refactor dialogs to have their own view models and not rely on repositoryTabViewModel
                            val request = entry.credentialsRequest

                            UserPasswordDialog(
                                title = "LFS Server Credentials",
                                subtitle = when {
                                    request.user != null -> "Introduce the password for your LFS server"
                                    !request.askPassword -> "Introduce the username for your LFS server"
                                    else -> "Introduce the credentials for your LFS server"
                                },
                                icon = painterResource(Res.drawable.lfs),
                                user = request.user,
                                askPassword = request.askPassword,
                                onDismiss = {
                                    repositoryTabViewModel.credentialsDenied()
                                    backStack.removeLastOrNull()
                                },
                                onAccept = { user, password ->
                                    repositoryTabViewModel.lfsCredentialsAccepted(user, password)
                                    backStack.removeLastOrNull()
                                }
                            )
                        }
                        entry<Screen.SignOffData>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            SignOffDialog(
                                viewModel = tabViewModel(entry) { it.signOffDialogViewModel() },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.TagCreate>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            CreateTagDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider.createTagViewModelFactory().create(entry.targetCommit)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.TagDelete>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            DeleteTagDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider.deleteTagViewModelFactory().create(entry.tag)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.BranchReset>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            ResetBranchDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider.resetBranchViewModelFactory().create(entry.targetCommit)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.DiscardFolderChanges>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            DiscardChangesDialog(
                                viewModel = tabViewModel(entry) { viewModelsProvider ->
                                    viewModelsProvider.discardChangesViewModelFactory().create(entry.entries)
                                },
                                folderPath = entry.folderPath,
                                fileCount = entry.entries.size,
                                keptNewFiles = entry.keptNewFiles,
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.ConfirmAction>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            ConfirmActionDialog(
                                action = entry.action,
                                onConfirm = {
                                    backStack.removeLastOrNull()
                                    entry.onConfirm()
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.FastForwardOnCheckout>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            FastForwardOnCheckoutDialog(
                                offer = entry.offer,
                                onCheckout = { fastForward ->
                                    backStack.removeLastOrNull()
                                    entry.onCheckout(fastForward)
                                },
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                        entry<Screen.QuickActions>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            QuickActionsDialog(
                                viewModel = tabViewModel(entry) { it.quickActionsViewModel() },
                                onDismiss = { backStack.removeLastOrNull() },
                                onShowSignOff = {
                                    backStack.removeLastOrNull()
                                    backStack.add(Screen.SignOffData)
                                },
                                onShowClone = {
                                    backStack.removeLastOrNull()
                                    backStack.add(Screen.Clone)
                                },
                            )
                        }
                        entry<Screen.Author>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            val viewModel = tabViewModel(entry) { it.authorViewModel() }

                            AuthorDialog(
                                viewModel = viewModel,
                                onDismiss = { backStack.removeLastOrNull() }
                            )
                        }
                        entry<Screen.StashWithMessage>(
                            metadata = dialogsMetadata
                        ) { entry ->
                            val viewModel = tabViewModel(entry) { it.stashWithMessageViewModel() }
                            StashWithMessageDialog(
                                viewModel = viewModel,
                                onDismiss = { backStack.removeLastOrNull() },
                            )
                        }
                    }
                )
            }
        }

        if (processingTask != null) {
            ProcessingScreen(
                processingTask,
                progress = taskProgress,
                onCancelOnGoingTask = { repositoryTabViewModel.cancelOngoingTask() }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (task in tasks) {

                val notificationData = task.toNotificationData()

                if (notificationData != null) {
                    Notification(notificationData)
                }

                LaunchedEffect(task) {
                    delay(2000L.milliseconds)
                    repositoryTabViewModel.completedTaskAlreadyShown(task)
                }
            }
        }
    }
}

fun CompletedTask.toNotificationData(): NotificationData? {
    val message = this.taskType.successTitle() ?: return null


    val type = when (this) {
        is CompletedTask.Failure -> NotificationType.Error
        is CompletedTask.Success -> NotificationType.Positive
    }


    return NotificationData(type, message)
}

private fun Screen.isCredentialsDialog() = when (this) {
    is Screen.HttpCredentials,
    is Screen.SshCredentials,
    is Screen.SshHostKey,
    is Screen.LfsCredentials,
    is Screen.AskpassPrompt,
    is Screen.AskpassConfirm -> true

    else -> false
}
