package dev.app.leaf.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.domain.index.conversionSteps
import dev.app.leaf.domain.worktrees.shellLines
import dev.app.leaf.ui.status.consequence
import dev.app.leaf.ui.status.summary
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.error_ignore_global_overridden
import dev.app.leaf.app.generated.resources.error_ignore_not_a_file
import dev.app.leaf.app.generated.resources.error_ignore_not_absolute
import dev.app.leaf.app.generated.resources.error_ignore_symbolic_link
import dev.app.leaf.app.generated.resources.error_missing_object
import dev.app.leaf.app.generated.resources.error_partial_clone_fetch
import dev.app.leaf.app.generated.resources.error_partial_clone_not_sent
import dev.app.leaf.app.generated.resources.error_partial_clone_nothing_changed
import dev.app.leaf.app.generated.resources.unsupported_index_commands
import dev.app.leaf.app.generated.resources.error_checkout_branch_bisected_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_branch_checked_out_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_branch_rebased_in_worktree
import dev.app.leaf.app.generated.resources.error_checkout_cannot_fast_forward
import dev.app.leaf.app.generated.resources.error_clone_submodules_failed
import dev.app.leaf.app.generated.resources.error_conflict_cant_discard
import dev.app.leaf.app.generated.resources.error_conflict_conflicts_left
import dev.app.leaf.app.generated.resources.error_conflict_markers_left
import dev.app.leaf.app.generated.resources.error_conflict_renamed_content_unknown
import dev.app.leaf.app.generated.resources.error_conflict_merge_tool_file_name
import dev.app.leaf.app.generated.resources.error_conflict_merge_tool_not_two_files
import dev.app.leaf.app.generated.resources.error_conflict_no_merge_tool
import dev.app.leaf.app.generated.resources.error_conflict_not_a_file
import dev.app.leaf.app.generated.resources.error_conflict_not_conflicted
import dev.app.leaf.app.generated.resources.error_conflict_not_resolved
import dev.app.leaf.app.generated.resources.error_conflict_resolved_before
import dev.app.leaf.app.generated.resources.error_conflict_stages_changed
import dev.app.leaf.app.generated.resources.error_conflict_working_file_changed
import dev.app.leaf.app.generated.resources.error_create_branch_already_exists
import dev.app.leaf.app.generated.resources.error_create_branch_name_not_allowed
import dev.app.leaf.app.generated.resources.error_delete_branch_bisected_in_worktree
import dev.app.leaf.app.generated.resources.error_delete_branch_checked_out_in_worktree
import dev.app.leaf.app.generated.resources.error_delete_file_linked_worktree
import dev.app.leaf.app.generated.resources.error_remote_branch_changed
import dev.app.leaf.app.generated.resources.error_several_push_urls
import dev.app.leaf.app.generated.resources.error_remote_branch_changed_now
import dev.app.leaf.app.generated.resources.error_remote_branches_need_git_helper_missing
import dev.app.leaf.app.generated.resources.error_remote_branches_need_git_not_found
import dev.app.leaf.app.generated.resources.error_remote_branches_need_git_setting_off
import dev.app.leaf.app.generated.resources.error_restore_file_no_work_tree
import dev.app.leaf.app.generated.resources.error_restore_file_not_in_commit
import dev.app.leaf.app.generated.resources.error_restore_file_conflicted
import dev.app.leaf.app.generated.resources.error_restore_file_folder_in_place
import dev.app.leaf.app.generated.resources.error_restore_file_parent_not_folder
import dev.app.leaf.app.generated.resources.error_restore_file_git_failed
import dev.app.leaf.app.generated.resources.error_restore_file_git_failed_kept
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
import dev.app.leaf.app.generated.resources.error_lfs_object_credentials_denied
import dev.app.leaf.app.generated.resources.error_lfs_object_http
import dev.app.leaf.app.generated.resources.error_lfs_object_no_download
import dev.app.leaf.app.generated.resources.error_lfs_object_no_server
import dev.app.leaf.app.generated.resources.error_lfs_object_refused
import dev.app.leaf.app.generated.resources.error_lfs_object_request_failed
import dev.app.leaf.app.generated.resources.error_lfs_object_wrong_content
import dev.app.leaf.app.generated.resources.error_lfs_object_wrong_size
import dev.app.leaf.app.generated.resources.error_open_repository_dir_not_found
import dev.app.leaf.app.generated.resources.error_open_repository_path_is_not_dir
import dev.app.leaf.app.generated.resources.error_open_repository_repo_not_found
import dev.app.leaf.app.generated.resources.error_open_repository_repository_load
import dev.app.leaf.app.generated.resources.error_remote_access_denied
import dev.app.leaf.app.generated.resources.error_remote_authentication_failed
import dev.app.leaf.app.generated.resources.error_remote_branch_not_found
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
import dev.app.leaf.app.generated.resources.error_ssh_needs_git_helper_missing
import dev.app.leaf.app.generated.resources.error_ssh_needs_git_lfs
import dev.app.leaf.app.generated.resources.error_ssh_needs_git_not_found
import dev.app.leaf.app.generated.resources.error_ssh_needs_git_setting_off
import dev.app.leaf.app.generated.resources.error_ssh_signing_cancelled
import dev.app.leaf.app.generated.resources.error_ssh_signing_failed
import dev.app.leaf.app.generated.resources.error_ssh_signing_no_key
import dev.app.leaf.app.generated.resources.error_ssh_signing_program_not_found
import dev.app.leaf.app.generated.resources.error_ssh_signing_start_failed
import dev.app.leaf.app.generated.resources.error_ssh_signing_timed_out
import dev.app.leaf.app.generated.resources.error_ssh_signing_unsupported
import dev.app.leaf.app.generated.resources.error_stale_hunk_discard
import dev.app.leaf.app.generated.resources.error_stale_hunk_stage
import dev.app.leaf.app.generated.resources.error_stale_hunk_unstage
import dev.app.leaf.app.generated.resources.error_whitespace_ignored_hunk_discard
import dev.app.leaf.app.generated.resources.error_whitespace_ignored_hunk_stage
import dev.app.leaf.app.generated.resources.error_whitespace_ignored_hunk_unstage
import dev.app.leaf.app.generated.resources.error_stash_no_data
import dev.app.leaf.domain.conflicts.formatFileSize
import dev.app.leaf.domain.errors.*
import dev.app.leaf.domain.models.MergeToolRefusal
import dev.app.leaf.domain.models.WorktreeBranchUse
import dev.app.leaf.theme.monoTypography
import org.eclipse.jgit.lib.Constants
import org.jetbrains.compose.resources.pluralStringResource
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

/** The commands that convert the index, with the `cd` into its worktree, to paste in a terminal. */
private fun UnsupportedIndex.shellCommands(): List<String> =
    shellLines(conversionSteps(), System.getProperty("user.home")) { "" }

/** The parts of [getErrorText] that [getStyledErrorText] shows in a monospace font. */
private fun AppError.monospaceParts(): List<String> = when (this) {
    is CheckoutBranchError.BranchUsedByWorktree -> listOf(worktreePath)
    is DeleteBranchError.BranchUsedByWorktree -> listOf(worktreePath)
    is DeleteFileError.LinkedWorktree -> listOf(path)
    is RestoreFileError.NotInCommit -> listOf(path, commit.take(7))
    is RestoreFileError.Conflicted -> listOf(path)
    is RestoreFileError.FolderInPlace -> listOf(path)
    is RestoreFileError.ParentNotFolder -> listOf(path, parent)
    is RestoreFileError.GitFailed -> listOfNotNull(path, keptAt)
    is RenameBranchError.BranchRebasedInWorktree -> listOf(worktreePath)
    is RenameBranchError.BranchBisectedInWorktree -> listOf(worktreePath)
    is RenameBranchError.WorktreeHeadNotMoved -> listOf(worktreePath, "git switch $newBranch")
    is SshNeedsGitError -> if (reason == SshNeedsGitError.Reason.LfsPushWithoutGitLfs) listOf("git lfs install") else emptyList()
    is StaleHunkError -> listOf(path)
    is WhitespaceIgnoredHunkError -> listOf(path)
    is IgnoreError.FileNotWritable -> listOf(path)
    is IgnoreError.GlobalListOverridden -> listOf(path)
    is ConflictError.NotConflicted -> paths
    is ConflictError.CantDiscard -> paths
    ConflictError.NoMergeTool -> listOf("git config --global merge.tool meld", "merge.guitool", "git mergetool --tool-help")
    is ConflictError.MergeToolRefused -> listOf(path)
    is ConflictError.NotResolved -> listOf(path, tool)
    is ConflictError.MarkersLeft -> listOf(path, tool, "git update-index --unresolve")
    is ConflictError.StagesChanged -> listOf(path)
    is ConflictError.RenamedContentUnknown -> listOf(path)
    is ConflictError.WorkingFileChanged -> listOf(path)
    is ConflictError.NotAFile -> listOf(path)
    is ConflictError.ConflictsLeft -> listOf(path)
    is UnsupportedIndexError -> index.shellCommands() + listOfNotNull((index as? UnsupportedIndex.UnknownExtension)?.extension)
    is PartialCloneFetchError -> error?.monospaceParts().orEmpty()
    is SeveralPushUrlsError -> urls
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

        is DeleteFileError -> when (this) {
            is DeleteFileError.LinkedWorktree -> stringResource(Res.string.error_delete_file_linked_worktree, path)
        }

        is RestoreFileError -> when (this) {
            RestoreFileError.NoWorkTree -> stringResource(Res.string.error_restore_file_no_work_tree)
            is RestoreFileError.NotInCommit ->
                stringResource(Res.string.error_restore_file_not_in_commit, path, commit.take(7))

            is RestoreFileError.Conflicted -> stringResource(Res.string.error_restore_file_conflicted, path)
            is RestoreFileError.FolderInPlace -> stringResource(Res.string.error_restore_file_folder_in_place, path)
            is RestoreFileError.ParentNotFolder ->
                stringResource(Res.string.error_restore_file_parent_not_folder, path, parent)

            is RestoreFileError.GitFailed -> if (keptAt == null) {
                stringResource(Res.string.error_restore_file_git_failed, path, output)
            } else {
                stringResource(Res.string.error_restore_file_git_failed_kept, path, output, keptAt!!)
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
        is LfsObjectDownloadError -> error.getLfsObjectErrorText()

        is UnsupportedIndexError -> {
            val commands = index.shellCommands()

            listOfNotNull(
                index.summary() + " " + index.consequence(),
                commands.takeIf { it.isNotEmpty() }?.let {
                    stringResource(Res.string.unsupported_index_commands) + "\n\n" + it.joinToString("\n")
                },
            ).joinToString("\n\n")
        }

        is MissingObjectError -> stringResource(Res.string.error_missing_object, message)

        is PartialCloneFetchError -> {
            val explanation = stringResource(Res.string.error_partial_clone_fetch, remote) +
                if (nothingChanged) " " + stringResource(Res.string.error_partial_clone_nothing_changed) else ""
            val cause = error?.getErrorText() ?: stringResource(Res.string.error_partial_clone_not_sent, remote)

            explanation + "\n\n" + cause
        }

        is StaleHunkError -> {
            val explanation = when (action) {
                HunkAction.Stage -> stringResource(Res.string.error_stale_hunk_stage, path)
                HunkAction.Unstage -> stringResource(Res.string.error_stale_hunk_unstage, path)
                HunkAction.Discard -> stringResource(Res.string.error_stale_hunk_discard, path)
            }

            listOf(explanation, output).filter { it.isNotBlank() }.joinToString("\n\n")
        }

        is WhitespaceIgnoredHunkError -> when (action) {
            HunkAction.Stage -> stringResource(Res.string.error_whitespace_ignored_hunk_stage, path)
            HunkAction.Unstage -> stringResource(Res.string.error_whitespace_ignored_hunk_unstage, path)
            HunkAction.Discard -> stringResource(Res.string.error_whitespace_ignored_hunk_discard, path)
        }

        is ConflictError -> when (this) {
            is ConflictError.NotConflicted ->
                stringResource(Res.string.error_conflict_not_conflicted, paths.joinToString("\n"))

            is ConflictError.CantDiscard ->
                stringResource(Res.string.error_conflict_cant_discard, paths.joinToString("\n"))

            ConflictError.NoMergeTool -> stringResource(Res.string.error_conflict_no_merge_tool)
            is ConflictError.MergeToolRefused -> when (reason) {
                MergeToolRefusal.NOT_TWO_FILES -> stringResource(Res.string.error_conflict_merge_tool_not_two_files, path)
                MergeToolRefusal.FILE_NAME -> stringResource(Res.string.error_conflict_merge_tool_file_name, path)
            }

            is ConflictError.NotResolved -> listOf(
                stringResource(Res.string.error_conflict_not_resolved, path, tool),
                resolvedBeforeText(resolvedBefore),
                output,
            ).filter { it.isNotBlank() }.joinToString("\n\n")

            is ConflictError.MarkersLeft -> listOf(
                stringResource(Res.string.error_conflict_markers_left, path, tool),
                resolvedBeforeText(resolvedBefore),
            ).filter { it.isNotBlank() }.joinToString("\n\n")

            is ConflictError.StagesChanged -> stringResource(Res.string.error_conflict_stages_changed, path)
            is ConflictError.RenamedContentUnknown ->
                stringResource(Res.string.error_conflict_renamed_content_unknown, path)
            is ConflictError.WorkingFileChanged -> stringResource(Res.string.error_conflict_working_file_changed, path)
            is ConflictError.NotAFile -> stringResource(Res.string.error_conflict_not_a_file, path)
            is ConflictError.ConflictsLeft -> stringResource(Res.string.error_conflict_conflicts_left, path, count)
        }

        is IgnoreError.FileNotWritable -> when (reason) {
            IgnoreError.Reason.SYMBOLIC_LINK -> stringResource(Res.string.error_ignore_symbolic_link, path)
            IgnoreError.Reason.NOT_A_FILE -> stringResource(Res.string.error_ignore_not_a_file, path)
            IgnoreError.Reason.NOT_ABSOLUTE -> stringResource(Res.string.error_ignore_not_absolute, path)
        }

        is IgnoreError.GlobalListOverridden -> stringResource(Res.string.error_ignore_global_overridden, path)

        is SshNeedsGitError -> when (reason) {
            SshNeedsGitError.Reason.SettingOff -> stringResource(Res.string.error_ssh_needs_git_setting_off)
            SshNeedsGitError.Reason.GitNotFound -> stringResource(Res.string.error_ssh_needs_git_not_found)
            SshNeedsGitError.Reason.HelperMissing -> stringResource(Res.string.error_ssh_needs_git_helper_missing)
            SshNeedsGitError.Reason.LfsPushWithoutGitLfs -> stringResource(Res.string.error_ssh_needs_git_lfs)
        }

        is RemoteBranchChangedError -> {
            val now = now
            val was = was

            if (now != null && was != null) {
                stringResource(Res.string.error_remote_branch_changed_now, branch, now.take(7), was.take(7))
            } else {
                stringResource(Res.string.error_remote_branch_changed, branch)
            }
        }

        is SeveralPushUrlsError -> stringResource(Res.string.error_several_push_urls, remote, urls.joinToString("\n"))

        is RemoteBranchesNeedGitError -> when (reason) {
            SshNeedsGitError.Reason.SettingOff -> stringResource(Res.string.error_remote_branches_need_git_setting_off)
            SshNeedsGitError.Reason.HelperMissing -> stringResource(Res.string.error_remote_branches_need_git_helper_missing)
            // Only a push of LFS objects gives that reason, and deleting branches pushes none
            SshNeedsGitError.Reason.GitNotFound, SshNeedsGitError.Reason.LfsPushWithoutGitLfs ->
                stringResource(Res.string.error_remote_branches_need_git_not_found)
        }

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

/** Why the built-in LFS client couldn't download an object (fork-only). */
@Composable
private fun LfsError.getLfsObjectErrorText(): String = when (this) {
    LfsError.NoServer -> stringResource(Res.string.error_lfs_object_no_server)
    is LfsError.HttpError -> stringResource(Res.string.error_lfs_object_http, code.toString())
    is LfsError.ObjectRefused -> stringResource(
        Res.string.error_lfs_object_refused,
        listOfNotNull(code, message.ifBlank { null }).joinToString(" "),
    )

    LfsError.NoDownloadOffered -> stringResource(Res.string.error_lfs_object_no_download)
    is LfsError.CorruptDownload -> if (receivedSize != expectedSize) {
        stringResource(
            Res.string.error_lfs_object_wrong_size,
            formatFileSize(receivedSize),
            formatFileSize(expectedSize),
        )
    } else {
        stringResource(Res.string.error_lfs_object_wrong_content)
    }

    is LfsError.RequestFailed -> stringResource(Res.string.error_lfs_object_request_failed, message)
    LfsError.CredentialsDenied -> stringResource(Res.string.error_lfs_object_credentials_denied)
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
        is RemoteOperationError.BranchNotFound -> stringResource(Res.string.error_remote_branch_not_found, branch)
        is RemoteOperationError.Failed -> stringResource(Res.string.error_remote_failed, exitCode)
    }

    return listOf(explanation, output).filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * What the merge tool resolved before the file that it didn't, when it went through several in turn (fork-only), or
 * nothing.
 */
@Composable
private fun resolvedBeforeText(resolvedBefore: Int): String = if (resolvedBefore > 0) {
    pluralStringResource(Res.plurals.error_conflict_resolved_before, resolvedBefore, resolvedBefore)
} else {
    ""
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