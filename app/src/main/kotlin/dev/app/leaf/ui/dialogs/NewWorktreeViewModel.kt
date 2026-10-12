// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.TabViewModel
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.NewWorktreeContext
import dev.app.leaf.domain.models.NewWorktreeStart
import dev.app.leaf.domain.models.WorktreeCreationSettings
import dev.app.leaf.domain.models.WorktreeInclude
import dev.app.leaf.domain.usecases.CheckBranchNameUseCase
import dev.app.leaf.domain.usecases.CreateWorktreeIncludeUseCase
import dev.app.leaf.domain.usecases.CreateWorktreeUseCase
import dev.app.leaf.domain.usecases.GetNewWorktreeContextUseCase
import dev.app.leaf.domain.usecases.GetWorktreeIncludeUseCase
import dev.app.leaf.domain.usecases.OpenFileInExternalAppUseCase
import dev.app.leaf.domain.worktrees.NewWorktreeBranch
import dev.app.leaf.domain.worktrees.NewWorktreeRequest
import dev.app.leaf.domain.worktrees.NewWorktreeStartPoint
import dev.app.leaf.domain.worktrees.WorktreeStep
import dev.app.leaf.domain.worktrees.newWorktreeFolder
import dev.app.leaf.domain.worktrees.startPoint
import dev.app.leaf.domain.worktrees.steps
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.system.PickerType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** git checks a branch name this long after the last letter typed. */
private val NAME_CHECK_DELAY = 150.milliseconds

private val HOME: String? = System.getProperty("user.home")?.trimEnd('/')?.takeIf { it.isNotEmpty() }

/** [path] with the home folder as `~`, so that a long path shows its end, the worktree's own folder. */
internal fun abbreviateHome(path: String, home: String? = HOME): String =
    if (home != null && path.startsWith("$home/")) "~/" + path.removePrefix("$home/") else path

/** [path] with a leading `~` as the home folder, as a shell reads it. */
internal fun expandHome(path: String, home: String? = HOME): String =
    if (home != null && (path == "~" || path.startsWith("~/"))) home + path.removePrefix("~") else path

/** What the dialog knows of the repository's `.worktreeinclude`. */
sealed interface WorktreeIncludeState {
    data object Loading : WorktreeIncludeState

    /** The repository has no `.worktreeinclude`. */
    data object Missing : WorktreeIncludeState

    data class Found(val include: WorktreeInclude) : WorktreeIncludeState

    data class Failed(val error: AppError) : WorktreeIncludeState
}

/** Why the folder typed can't be the new worktree's. */
enum class NewWorktreeFolderProblem { NOT_ABSOLUTE, TAKEN }

/**
 * State of the new worktree dialog (fork-only).
 *
 * @param checkOut the worktree checks out [NewWorktreeStartPoint.branchToCheckOut], rather than getting a new branch.
 * @param nameRejected git doesn't accept [branchName]; null while git checks it.
 * @param folder the folder as the field shows it, with the home folder as `~`.
 * @param folderEdited the user typed the folder, so it no longer follows the branch's name.
 * @param location the folder that the repository's worktrees go in, null for next to the main worktree.
 */
data class NewWorktreeState(
    val context: NewWorktreeContext? = null,
    val startPoint: NewWorktreeStartPoint? = null,
    val error: AppError? = null,
    val checkOut: Boolean = false,
    val branchName: String = "",
    val nameRejected: Boolean? = false,
    val folder: String = "",
    val folderEdited: Boolean = false,
    val location: String? = null,
    val include: WorktreeIncludeState = WorktreeIncludeState.Loading,
    val copyFiles: Boolean = true,
    val switchToNew: Boolean = true,
) {
    /** The new worktree's branch: the one it checks out, or the new one's name. */
    val branch: NewWorktreeBranch?
        get() {
            val toCheckOut = startPoint?.branchToCheckOut

            return when {
                checkOut && toCheckOut != null -> NewWorktreeBranch.CheckOut(toCheckOut)
                branchName.isBlank() -> null
                else -> NewWorktreeBranch.Create(branchName.trim())
            }
        }

    /** A new branch whose name a local branch already has. */
    val nameTaken: Boolean
        get() {
            val branch = branch
            return branch is NewWorktreeBranch.Create && context?.localBranches?.contains("refs/heads/${branch.name}") == true
        }

    /** The folder's full path, with `~` read as the home folder. */
    val folderPath: String
        get() = expandHome(folder.trim())

    val folderProblem: NewWorktreeFolderProblem?
        get() = folderProblemOf(folderPath)

    /** The gitignored files to copy, empty when there's none or the box is unchecked. */
    val filesToCopy: List<String>
        get() = (include as? WorktreeIncludeState.Found)?.include?.files?.takeIf { copyFiles }.orEmpty()

    /** The worktree to create, or null while the dialog misses something or git would refuse it. */
    val request: NewWorktreeRequest?
        get() {
            val context = context ?: return null
            val startPoint = startPoint ?: return null
            val branch = branch ?: return null

            val nameRefused = branch is NewWorktreeBranch.Create && (nameTaken || nameRejected != false)

            if (nameRefused || folder.isBlank() || folderProblem != null) return null

            return NewWorktreeRequest(
                mainWorktree = context.mainWorktree,
                startPoint = startPoint.startPoint,
                tracking = startPoint.tracking,
                branch = branch,
                folder = File(folderPath).toPath().normalize().toString(),
                copyFiles = filesToCopy,
                switchTab = switchToNew,
            )
        }

    val steps: List<WorktreeStep>
        get() = request?.steps().orEmpty()
}

/**
 * Whether [folder] can be a new worktree's: git needs a full path that's free, or an empty folder. Reading the folder
 * is quick, so it's checked as the user types.
 */
internal fun folderProblemOf(folder: String): NewWorktreeFolderProblem? {
    if (folder.isBlank()) return null

    val file = File(folder)

    return when {
        !file.isAbsolute -> NewWorktreeFolderProblem.NOT_ABSOLUTE
        file.isDirectory && file.list().isNullOrEmpty() -> null
        file.exists() -> NewWorktreeFolderProblem.TAKEN
        else -> null
    }
}

/** Creates a worktree (fork-only) from what the dialog was opened on: the base branch, a branch or a commit. */
class NewWorktreeViewModel @AssistedInject constructor(
    private val getNewWorktreeContextUseCase: GetNewWorktreeContextUseCase,
    private val getWorktreeIncludeUseCase: GetWorktreeIncludeUseCase,
    private val createWorktreeIncludeUseCase: CreateWorktreeIncludeUseCase,
    private val checkBranchNameUseCase: CheckBranchNameUseCase,
    private val createWorktreeUseCase: CreateWorktreeUseCase,
    private val openFilePickerUseCase: OpenFilePickerUseCase,
    private val openFileInExternalAppUseCase: OpenFileInExternalAppUseCase,
    @Assisted val start: NewWorktreeStart,
) : TabViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(start: NewWorktreeStart): NewWorktreeViewModel
    }

    val state: StateFlow<NewWorktreeState>
        field = MutableStateFlow(NewWorktreeState())

    private var nameCheck: Job? = null

    init {
        viewModelScope.launch {
            when (val result = getNewWorktreeContextUseCase()) {
                is Either.Err -> state.value = state.value.copy(error = result.error)
                is Either.Ok -> {
                    val context = result.value
                    val startPoint = start.startPoint(context)

                    state.update {
                        it.copy(
                            context = context,
                            startPoint = startPoint,
                            checkOut = startPoint.branchToCheckOut != null,
                            branchName = startPoint.suggestedName,
                            location = context.settings.folder,
                            switchToNew = context.settings.switchToNew,
                        ).withFolder()
                    }

                    checkName()
                    reloadInclude()
                }
            }
        }
    }

    fun setCheckOut(checkOut: Boolean) = state.update { it.copy(checkOut = checkOut).withFolder() }

    fun setBranchName(name: String) {
        state.update { it.copy(branchName = name, nameRejected = null).withFolder() }
        checkName()
    }

    fun setFolder(folder: String) = state.update { it.copy(folder = folder, folderEdited = true) }

    /** Asks for the folder that the repository's worktrees go in, which the new one then goes in. */
    fun chooseLocation() {
        val current = state.value
        val chosen = openFilePickerUseCase(PickerType.DIRECTORIES, current.location ?: current.context?.mainWorktree?.path)
            ?: return

        state.update { it.copy(location = chosen, folderEdited = false).withFolder() }
    }

    /** Puts the repository's worktrees next to its main worktree again. */
    fun useLocationNextToRepository() = state.update { it.copy(location = null, folderEdited = false).withFolder() }

    fun setCopyFiles(copyFiles: Boolean) = state.update { it.copy(copyFiles = copyFiles) }

    fun setSwitchToNew(switchToNew: Boolean) = state.update { it.copy(switchToNew = switchToNew) }

    /** Reads `.worktreeinclude` again, as the user may have edited it meanwhile. */
    fun reloadInclude() {
        val mainWorktree = state.value.context?.mainWorktree ?: return

        viewModelScope.launch {
            val include = when (val result = getWorktreeIncludeUseCase(mainWorktree.path)) {
                is Either.Err -> WorktreeIncludeState.Failed(result.error)
                is Either.Ok -> result.value?.let { WorktreeIncludeState.Found(it) } ?: WorktreeIncludeState.Missing
            }

            state.update { it.copy(include = include) }
        }
    }

    /** Creates a `.worktreeinclude` that explains itself, and opens it to be edited. */
    fun createInclude() {
        val mainWorktree = state.value.context?.mainWorktree ?: return

        viewModelScope.launch {
            when (val result = createWorktreeIncludeUseCase(mainWorktree.path)) {
                is Either.Err -> state.update { it.copy(include = WorktreeIncludeState.Failed(result.error)) }
                is Either.Ok -> {
                    openFileInExternalAppUseCase(result.value)
                    reloadInclude()
                }
            }
        }
    }

    /** Creates the worktree, and returns true when it started, so that the dialog closes. */
    fun create(): Boolean {
        val current = state.value
        val steps = current.steps

        if (steps.isEmpty()) return false

        createWorktreeUseCase(steps, WorktreeCreationSettings(folder = current.location, switchToNew = current.switchToNew))

        return true
    }

    private fun checkName() {
        nameCheck?.cancel()

        val name = state.value.branchName.trim()

        if (name.isEmpty()) {
            state.update { it.copy(nameRejected = false) }
            return
        }

        nameCheck = viewModelScope.launch {
            delay(NAME_CHECK_DELAY)

            val accepted = (checkBranchNameUseCase(name) as? Either.Ok)?.value

            state.update {
                // A name typed meanwhile gets its own check
                if (it.branchName.trim() == name) it.copy(nameRejected = accepted == false) else it
            }
        }
    }
}

/** This state with the folder that follows the branch's name and the location, unless the user typed one. */
private fun NewWorktreeState.withFolder(): NewWorktreeState {
    val context = context ?: return this

    if (folderEdited) return this

    val name = branch?.name ?: return copy(folder = "")

    return copy(folder = abbreviateHome(newWorktreeFolder(context.mainWorktree, location, name)))
}
