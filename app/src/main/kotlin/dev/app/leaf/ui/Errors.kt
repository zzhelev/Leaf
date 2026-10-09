package dev.app.leaf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.error_checkout_branch_bisected_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_branch_checked_out_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_branch_rebased_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_cannot_fast_forward
import dev.app.leaf.app.generated.resources.error_clone_submodules_failed
import dev.app.leaf.app.generated.resources.error_create_branch_already_exists
import dev.app.leaf.app.generated.resources.error_create_branch_name_not_allowed
import dev.app.leaf.app.generated.resources.error_delete_branch_bisected_in_worktree
import dev.app.leaf.app.generated.resources.error_delete_branch_checked_out_in_worktree
import dev.app.leaf.app.generated.resources.error_delete_branch_not_merged
import dev.app.leaf.app.generated.resources.error_delete_branch_rebased_in_worktree
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
import dev.app.leaf.app.generated.resources.error_lfs_download_failed
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
import dev.app.leaf.app.generated.resources.error_rename_branch_bisected_in_worktree
import dev.app.leaf.app.generated.resources.error_rename_branch_rebased_in_worktree
import dev.app.leaf.app.generated.resources.error_rename_branch_worktree_head_not_moved
import dev.app.leaf.app.generated.resources.error_repository_path_not_set
import dev.app.leaf.app.generated.resources.error_repository_read_error
import dev.app.leaf.app.generated.resources.error_ssh_signing_cancelled
import dev.app.leaf.app.generated.resources.error_ssh_signing_failed
import dev.app.leaf.app.generated.resources.error_ssh_signing_no_key
import dev.app.leaf.app.generated.resources.error_ssh_signing_program_not_found
import dev.app.leaf.app.generated.resources.error_ssh_signing_start_failed
import dev.app.leaf.app.generated.resources.error_ssh_signing_timed_out
import dev.app.leaf.app.generated.resources.error_ssh_signing_unsupported
import dev.app.leaf.app.generated.resources.error_stash_no_data
import dev.app.leaf.domain.errors.*
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.theme.monoTypography
import org.eclipse.jgit.lib.Constants
import org.jetbrains.compose.resources.stringResource

/**
 * [getErrorText], with the folders and commands that the error names in a monospace font, so they stand out from the
 * sentences around them.
 */
@Composable
fun AppError.getStyledErrorText(): AnnotatedString {
    val text = getErrorText()
    val monospace = SpanStyle(fontFamily = monoTypography())

    return buildAnnotatedString {
        append(text)

        for (part in monospaceParts().filter { it.isNotEmpty() }) {
            var start = text.indexOf(part)

            while (start >= 0) {
                addStyle(monospace, start, start + part.length)
                start = text.indexOf(part, start + part.length)
            }
        }
    }
}

/** The parts of [getErrorText] that [getStyledErrorText] shows in a monospace font. */
private fun AppError.monospaceParts(): List<String> = when (this) {
    is CheckoutBranchError.BranchUsedByWorktree -> listOf(worktreePath)
    is DeleteBranchError.BranchUsedByWorktree -> listOf(worktreePath)
    is RenameBranchError.BranchRebasedInWorktree -> listOf(worktreePath)
    is RenameBranchError.BranchBisectedInWorktree -> listOf(worktreePath)
    is RenameBranchError.WorktreeHeadNotMoved -> listOf(worktreePath, "git switch $newBranch")
    else -> emptyList()
}

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

        is DeleteBranchError -> when (this) {
            is DeleteBranchError.BranchUsedByWorktree -> when (use) {
                WorktreeBranchUse.CheckedOut ->
                    stringResource(Res.string.error_delete_branch_checked_out_in_worktree, branch, worktreePath)

                WorktreeBranchUse.Rebasing ->
                    stringResource(Res.string.error_delete_branch_rebased_in_worktree, branch, worktreePath)

                WorktreeBranchUse.Bisecting ->
                    stringResource(Res.string.error_delete_branch_bisected_in_worktree, branch, worktreePath)
            }
        }

        is RenameBranchError -> when (this) {
            is RenameBranchError.BranchRebasedInWorktree ->
                stringResource(Res.string.error_rename_branch_rebased_in_worktree, branch, worktreePath)

            is RenameBranchError.BranchBisectedInWorktree ->
                stringResource(Res.string.error_rename_branch_bisected_in_worktree, branch, worktreePath)

            is RenameBranchError.WorktreeHeadNotMoved ->
                stringResource(
                    Res.string.error_rename_branch_worktree_head_not_moved,
                    oldBranch,
                    newBranch,
                    worktreePath,
                )
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

        is LfsDownloadError -> stringResource(Res.string.error_lfs_download_failed) + " " + error.getErrorText()

        is SshSigningError -> when (this) {
            SshSigningError.NoSigningKey -> stringResource(Res.string.error_ssh_signing_no_key)
            is SshSigningError.ProgramNotFound -> stringResource(Res.string.error_ssh_signing_program_not_found, this.program)
            is SshSigningError.StartFailed -> stringResource(Res.string.error_ssh_signing_start_failed, this.program, this.message)
            is SshSigningError.TimedOut -> stringResource(Res.string.error_ssh_signing_timed_out, this.program, this.timeoutSeconds)
            is SshSigningError.SigningUnsupported -> stringResource(Res.string.error_ssh_signing_unsupported, this.program, this.output)
            SshSigningError.Cancelled -> stringResource(Res.string.error_ssh_signing_cancelled)
            is SshSigningError.SigningFailed -> stringResource(Res.string.error_ssh_signing_failed, this.program, this.output)
        }
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