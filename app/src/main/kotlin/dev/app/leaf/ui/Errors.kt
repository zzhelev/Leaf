package dev.app.leaf.ui

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.error_create_branch_already_exists
import dev.app.leaf.app.generated.resources.error_create_branch_name_not_allowed
import dev.app.leaf.app.generated.resources.error_git_cli_command_failed
import dev.app.leaf.app.generated.resources.error_git_cli_invalid_configured_path
import dev.app.leaf.app.generated.resources.error_git_cli_not_found
import dev.app.leaf.app.generated.resources.error_git_cli_start_failed
import dev.app.leaf.app.generated.resources.error_git_cli_timed_out
import dev.app.leaf.app.generated.resources.error_git_cli_unsupported_version
import dev.app.leaf.app.generated.resources.error_hook_rejection
import dev.app.leaf.app.generated.resources.error_open_repository_dir_not_found
import dev.app.leaf.app.generated.resources.error_open_repository_path_is_not_dir
import dev.app.leaf.app.generated.resources.error_open_repository_repo_not_found
import dev.app.leaf.app.generated.resources.error_open_repository_repository_load
import dev.app.leaf.app.generated.resources.error_repository_path_not_set
import dev.app.leaf.app.generated.resources.error_repository_read_error
import dev.app.leaf.app.generated.resources.error_sign_ssh_key_not_found
import dev.app.leaf.app.generated.resources.error_stash_no_data
import dev.app.leaf.domain.errors.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppError.getErrorText(): String {
    return when (this) {
        is CreateBranchError -> when (this) {
            is CreateBranchError.BranchAlreadyExists -> stringResource(Res.string.error_create_branch_already_exists, this.name)
            is CreateBranchError.NameNotAllowed -> stringResource(Res.string.error_create_branch_name_not_allowed, this.name)
        }

        is GenericError -> this.message
        is HookRejectionError -> stringResource(Res.string.error_hook_rejection, this.message)
        RepositoryPathNotSetError -> stringResource(Res.string.error_repository_path_not_set)
        is RepositoryReadError -> stringResource(Res.string.error_repository_read_error, this.message)
        is StashChangesError -> when (this) {
            StashChangesError.NoDataToStash -> stringResource(Res.string.error_stash_no_data)
        }

        is OpenRepoError -> when (this) {
            OpenRepoError.DirectoryNotFoundError -> stringResource(Res.string.error_open_repository_dir_not_found)
            OpenRepoError.PathIsNotDirectory -> stringResource(Res.string.error_open_repository_path_is_not_dir)
            is OpenRepoError.RepositoryLoadFailed -> stringResource(Res.string.error_open_repository_repository_load, this.error)
            OpenRepoError.RepositoryNotFoundInPath -> stringResource(Res.string.error_open_repository_repo_not_found)
        }

        is GitCliError -> when (this) {
            is GitCliError.GitNotFound -> stringResource(Res.string.error_git_cli_not_found, this.searchedPaths.joinToString(", "))
            is GitCliError.InvalidConfiguredPath -> stringResource(Res.string.error_git_cli_invalid_configured_path, this.path, this.reason)
            is GitCliError.UnsupportedVersion -> stringResource(Res.string.error_git_cli_unsupported_version, this.path, this.version, this.minimumVersion)
            is GitCliError.CommandFailed -> stringResource(Res.string.error_git_cli_command_failed, this.command, this.exitCode, this.stderr)
            is GitCliError.TimedOut -> stringResource(Res.string.error_git_cli_timed_out, this.command, this.timeoutSeconds)
            is GitCliError.StartFailed -> stringResource(Res.string.error_git_cli_start_failed, this.command, this.message)
        }

        is SshSigningError.InvalidPassword -> throw IllegalStateException("InvalidPassword error should never trigger")
        is SshSigningError.KeyNotFound -> stringResource(Res.string.error_sign_ssh_key_not_found)
    }
}