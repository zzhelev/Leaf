package dev.app.leaf.ui

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.error_checkout_branch_bisected_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_branch_checked_out_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_branch_rebased_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_cannot_fast_forward
import dev.app.leaf.app.generated.resources.error_clone_submodules_failed
import dev.app.leaf.app.generated.resources.error_create_branch_already_exists
import dev.app.leaf.app.generated.resources.error_create_branch_name_not_allowed
import dev.app.leaf.app.generated.resources.error_delete_branch_not_merged
import dev.app.leaf.app.generated.resources.error_delete_tag_has_own_commits
import dev.app.leaf.app.generated.resources.error_fetch_remote_failed
import dev.app.leaf.app.generated.resources.error_git_cli_command_failed
import dev.app.leaf.app.generated.resources.error_git_cli_invalid_configured_path
import dev.app.leaf.app.generated.resources.error_git_cli_not_found
import dev.app.leaf.app.generated.resources.error_git_cli_start_failed
import dev.app.leaf.app.generated.resources.error_git_cli_timed_out
import dev.app.leaf.app.generated.resources.error_git_cli_unsupported_version
import dev.app.leaf.app.generated.resources.error_gpg_signing_failed
import dev.app.leaf.app.generated.resources.error_gpg_signing_no_key
import dev.app.leaf.app.generated.resources.error_gpg_signing_pinentry_unavailable
import dev.app.leaf.app.generated.resources.error_gpg_signing_program_not_found
import dev.app.leaf.app.generated.resources.error_gpg_signing_start_failed
import dev.app.leaf.app.generated.resources.error_gpg_signing_timed_out
import dev.app.leaf.app.generated.resources.error_hook_rejection
import dev.app.leaf.app.generated.resources.error_open_repository_dir_not_found
import dev.app.leaf.app.generated.resources.error_open_repository_path_is_not_dir
import dev.app.leaf.app.generated.resources.error_open_repository_repo_not_found
import dev.app.leaf.app.generated.resources.error_open_repository_repository_load
import dev.app.leaf.app.generated.resources.error_remote_access_denied
import dev.app.leaf.app.generated.resources.error_remote_authentication_failed
import dev.app.leaf.app.generated.resources.error_remote_certificate_problem
import dev.app.leaf.app.generated.resources.error_remote_connection_failed
import dev.app.leaf.app.generated.resources.error_remote_failed
import dev.app.leaf.app.generated.resources.error_remote_host_key_changed
import dev.app.leaf.app.generated.resources.error_remote_host_key_not_verified
import dev.app.leaf.app.generated.resources.error_remote_prompt_refused
import dev.app.leaf.app.generated.resources.error_remote_ref_fetch_first
import dev.app.leaf.app.generated.resources.error_remote_ref_non_fast_forward
import dev.app.leaf.app.generated.resources.error_remote_ref_other
import dev.app.leaf.app.generated.resources.error_remote_ref_remote_rejected
import dev.app.leaf.app.generated.resources.error_remote_ref_stale_info
import dev.app.leaf.app.generated.resources.error_remote_refs_rejected
import dev.app.leaf.app.generated.resources.error_repository_path_not_set
import dev.app.leaf.app.generated.resources.error_repository_read_error
import dev.app.leaf.app.generated.resources.error_sign_ssh_key_not_found
import dev.app.leaf.app.generated.resources.error_stash_no_data
import dev.app.leaf.domain.errors.*
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.eclipse.jgit.lib.Constants
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppError.getErrorText(): String {
    return when (this) {
        is CreateBranchError -> when (this) {
            is CreateBranchError.BranchAlreadyExists -> stringResource(Res.string.error_create_branch_already_exists, this.name)
            is CreateBranchError.NameNotAllowed -> stringResource(Res.string.error_create_branch_name_not_allowed, this.name)
        }

        is CheckoutBranchError -> when (this) {
            is CheckoutBranchError.CannotFastForward ->
                stringResource(Res.string.error_checkout_cannot_fast_forward, localBranch, remoteBranch)

            is CheckoutBranchError.BranchUsedByWorktree -> when (use) {
                WorktreeBranchUse.CheckedOut ->
                    stringResource(Res.string.error_checkout_branch_checked_out_in_worktree, branch, worktreePath)

                WorktreeBranchUse.Rebasing ->
                    stringResource(Res.string.error_checkout_branch_rebased_in_worktree, branch, worktreePath)

                WorktreeBranchUse.Bisecting ->
                    stringResource(Res.string.error_checkout_branch_bisected_in_worktree, branch, worktreePath)
            }
        }

        is DeleteRefError -> when (this) {
            is DeleteRefError.BranchNotMerged -> stringResource(Res.string.error_delete_branch_not_merged, this.branchName)
            is DeleteRefError.TagHasOwnCommits -> stringResource(Res.string.error_delete_tag_has_own_commits, this.tagName)
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

        is GpgSigningError -> when (this) {
            GpgSigningError.NoSigningKey -> stringResource(Res.string.error_gpg_signing_no_key)
            is GpgSigningError.ProgramNotFound -> stringResource(Res.string.error_gpg_signing_program_not_found, this.program)
            is GpgSigningError.StartFailed -> stringResource(Res.string.error_gpg_signing_start_failed, this.program, this.message)
            is GpgSigningError.TimedOut -> stringResource(Res.string.error_gpg_signing_timed_out, this.program, this.timeoutSeconds)
            is GpgSigningError.PinentryUnavailable -> stringResource(Res.string.error_gpg_signing_pinentry_unavailable, this.output)
            is GpgSigningError.SigningFailed -> stringResource(Res.string.error_gpg_signing_failed, this.program, this.output)
        }

        is RemoteOperationError -> getRemoteOperationErrorText()
        is FetchRemotesError -> failures.map { failure ->
            stringResource(Res.string.error_fetch_remote_failed, failure.remote) + " " +
                failure.error.getRemoteOperationErrorText()
        }.joinToString("\n\n")

        is CloneSubmodulesError -> stringResource(Res.string.error_clone_submodules_failed, directory) + " " +
            error.getErrorText()

        is SshSigningError.InvalidPassword -> throw IllegalStateException("InvalidPassword error should never trigger")
        is SshSigningError.KeyNotFound -> stringResource(Res.string.error_sign_ssh_key_not_found)
    }
}

/** The explanation, then what git printed, which often has the server's own message. */
@Composable
private fun RemoteOperationError.getRemoteOperationErrorText(): String {
    val explanation = when (this) {
        is RemoteOperationError.RefsRejected -> {
            val refs = refs.map { it.getRejectionText() }
            (listOf(stringResource(Res.string.error_remote_refs_rejected)) + refs).joinToString("\n")
        }

        is RemoteOperationError.PromptRefused -> stringResource(Res.string.error_remote_prompt_refused)
        is RemoteOperationError.AuthenticationFailed -> stringResource(Res.string.error_remote_authentication_failed)
        is RemoteOperationError.AccessDenied -> stringResource(Res.string.error_remote_access_denied)
        is RemoteOperationError.HostKeyChanged -> stringResource(Res.string.error_remote_host_key_changed)
        is RemoteOperationError.HostKeyNotVerified -> stringResource(Res.string.error_remote_host_key_not_verified)
        is RemoteOperationError.ConnectionFailed -> stringResource(Res.string.error_remote_connection_failed)
        is RemoteOperationError.CertificateProblem -> stringResource(Res.string.error_remote_certificate_problem)
        is RemoteOperationError.Failed -> stringResource(Res.string.error_remote_failed, exitCode)
    }

    return listOf(explanation, output).filter { it.isNotBlank() }.joinToString("\n\n")
}

@Composable
private fun RejectedRef.getRejectionText(): String {
    val name = destination.removePrefix(Constants.R_HEADS).removePrefix(Constants.R_TAGS)

    return when (reason) {
        RejectReason.FETCH_FIRST -> stringResource(Res.string.error_remote_ref_fetch_first, name)
        RejectReason.NON_FAST_FORWARD -> stringResource(Res.string.error_remote_ref_non_fast_forward, name)
        RejectReason.STALE_INFO -> stringResource(Res.string.error_remote_ref_stale_info, name)
        RejectReason.REMOTE_REJECTED -> stringResource(Res.string.error_remote_ref_remote_rejected, name, detail)
        RejectReason.OTHER -> stringResource(Res.string.error_remote_ref_other, name, detail)
    }
}