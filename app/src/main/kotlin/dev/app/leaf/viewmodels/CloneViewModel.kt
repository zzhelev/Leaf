package dev.app.leaf.viewmodels

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.clone.remoteHostOf
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.ICloneRepositoryGitAction
import dev.app.leaf.domain.interfaces.IListRemoteRefsGitAction
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.RemoteRefs
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.system.PickerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

class CloneViewModel @Inject constructor(
    private val cloneRepositoryGitAction: ICloneRepositoryGitAction,
    private val listRemoteRefsGitAction: IListRemoteRefsGitAction,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val appSettings: AppSettingsService,
) : TabViewModel() {
    private val _repositoryUrl = MutableStateFlow(TextFieldValue(""))
    val repositoryUrl = _repositoryUrl.asStateFlow()

    private val _directoryPath = MutableStateFlow(TextFieldValue())
    val directoryPath = _directoryPath.asStateFlow()

    private val _saveDirAsDefault = MutableStateFlow(false)
    val saveDirAsDefault = _saveDirAsDefault.asStateFlow()

    private val _folder = MutableStateFlow(TextFieldValue(""))
    val folder = _folder.asStateFlow()

    private val _branch = MutableStateFlow(TextFieldValue(""))
    val branch = _branch.asStateFlow()

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.None)
    val cloneState = _cloneState.asStateFlow()

    val error: StateFlow<CloneUiError?>
        field = MutableStateFlow<CloneUiError?>(null)

    /** What "Choose…" shows under the Branch field. It only leaves [BranchChoicesState.Closed] when the button is clicked. */
    val branchChoices: StateFlow<BranchChoicesState>
        field = MutableStateFlow<BranchChoicesState>(BranchChoicesState.Closed)

    private var cloneJob: Job? = null
    private var listJob: Job? = null

    /** [branch] is a branch or a tag, blank for the repository's default branch. [onlyThisBranch] needs one. */
    fun clone(
        directoryPath: String,
        url: String,
        folder: String,
        cloneSubmodules: Boolean,
        branch: String = "",
        onlyThisBranch: Boolean = false,
    ) {
        closeBranchChoices()

        cloneJob = viewModelScope.launch {
            if (directoryPath.isBlank()) {
                error.value = CloneUiError.EmptyDirectory
                return@launch
            }

            if (url.isBlank()) {
                error.value = CloneUiError.EmptyUrl
                return@launch
            }

            if (folder.isBlank()) {
                error.value = CloneUiError.EmptyFolderName
                return@launch
            }

            val directory = File(directoryPath)

            if (!directory.exists()) {
                directory.mkdirs()
            }
            if (_saveDirAsDefault.value) {
                appSettings.setConfiguration(AppConfig.CloneDefaultDirectory(directoryPath))
            }

            // Not created here: the clone creates it, so that it removes it again if it fails or is cancelled
            val repoDir = File(directory, folder)

            val chosenBranch = branch.trim().ifEmpty { null }

            cloneRepositoryGitAction(
                directory = repoDir,
                url = url,
                cloneSubmodules = cloneSubmodules,
                branch = chosenBranch,
                singleBranch = onlyThisBranch && chosenBranch != null,
            )
                .flowOn(Dispatchers.IO)
                .collect { newCloneStatus ->
                    if (newCloneStatus is CloneState.Fail) {
                        error.value = CloneUiError.CloneError(newCloneStatus.reason)
                        _cloneState.value = CloneState.None
                    } else {
                        _cloneState.value = newCloneStatus
                    }
                }
        }
    }

    /**
     * "Choose…": asks the remote at [url] for its branches and tags, once per click. Typing in the Branch field never
     * does, and neither does opening the dialog or changing the URL. Without a URL, it says so as Clone does.
     */
    fun chooseBranch(url: String) {
        if (url.isBlank()) {
            error.value = CloneUiError.EmptyUrl
            return
        }

        resetStateIfError()
        listJob?.cancel()
        branchChoices.value = BranchChoicesState.Loading(remoteHostOf(url))

        listJob = viewModelScope.launch {
            branchChoices.value = when (val result = listRemoteRefsGitAction(url.trim())) {
                is Either.Ok -> BranchChoicesState.Loaded(result.value)
                is Either.Err -> BranchChoicesState.Failed(result.error)
            }
        }
    }

    /** Closes the list, and stops a request that is still running: git is killed. */
    fun closeBranchChoices() {
        listJob?.cancel()
        listJob = null
        branchChoices.value = BranchChoicesState.Closed
    }

    /** Puts a branch or tag that the list offered in the field, and closes the list. */
    fun pickBranch(name: String) {
        _branch.value = TextFieldValue(name, TextRange(name.length))
        closeBranchChoices()
    }

    fun cancelClone() {
        viewModelScope.launch {
            _cloneState.value = CloneState.Cancelling
            cloneJob?.cancelAndJoin()
            _cloneState.value = CloneState.None
        }
    }

    fun resetStateIfError() {
        error.value = null
    }

    fun repoName(url: String): String {
        val urlSplit = url.split("/", "\\").toMutableList()

        // Removes the last element for URLs that end with "/" or "\" instead of the repo name like https://github.com/zzhelev/Leaf/
        if (urlSplit.isNotEmpty() && urlSplit.last().isBlank()) {
            urlSplit.removeLast()
        }

        // Take the last element of the path/URL to generate obtain the repo name
        return urlSplit.lastOrNull()?.removeSuffix(".git").orEmpty()
    }

    /** The default clone directory from the settings, or null when none is set. */
    suspend fun defaultDirectory(): String? = appSettings.cloneDefaultDirectory.first()?.takeIf { it.isNotBlank() }

    fun openDirectoryPicker(): String? {
        return openFilePickerUseCase(PickerType.DIRECTORIES, null)
    }

    fun onDirectoryPathChanged(directory: TextFieldValue) {
        _directoryPath.value = directory
    }

    fun onSaveAsDefaultChanged(value: Boolean) {
        _saveDirAsDefault.value = value
    }

    fun onRepositoryUrlChanged(repositoryUrl: TextFieldValue) {
        // What was listed belongs to the old URL
        if (repositoryUrl.text != _repositoryUrl.value.text) {
            closeBranchChoices()
        }

        _repositoryUrl.value = repositoryUrl
    }

    fun onFolderNameChanged(folderName: TextFieldValue) {
        _folder.value = folderName
    }

    fun onBranchChanged(branch: TextFieldValue) {
        _branch.value = branch
    }
}

/** The "Choose…" list under the Branch field. */
sealed interface BranchChoicesState {
    data object Closed : BranchChoicesState
    /** Waiting for the remote, [host] being where the request went (null for a local path). */
    data class Loading(val host: String?) : BranchChoicesState
    data class Loaded(val refs: RemoteRefs) : BranchChoicesState
    data class Failed(val error: AppError) : BranchChoicesState
}

sealed interface CloneUiError {
    data object EmptyDirectory : CloneUiError
    data object EmptyUrl : CloneUiError
    data object EmptyFolderName : CloneUiError
    data class CloneError(val error: AppError) : CloneUiError
}