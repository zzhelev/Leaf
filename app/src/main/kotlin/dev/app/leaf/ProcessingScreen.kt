package dev.app.leaf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.generic_button_cancel
import dev.app.leaf.app.generated.resources.processing_stop_waiting
import dev.app.leaf.app.generated.resources.processing_waiting_for_server
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.remote.ProcessingButton
import dev.app.leaf.domain.remote.STOP_WAITING_AFTER
import dev.app.leaf.domain.remote.processingButton
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.SecondaryButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** As tall as the button, which a task that can't be stopped for now leaves out. */
private val BUTTON_PLACE_HEIGHT = 24.dp

/**
 * Covers the tab while a foreground task runs. A task that reports [progress] (the git CLI's) shows git's current stage
 * and can be cancelled. Fork-only: once git has handed a server what it carries out on its own, Cancel would promise
 * too much, so the button goes, and comes back as Stop waiting ([processingButton]).
 */
@Composable
fun ProcessingScreen(
    processingTask: TaskType,
    progress: TaskProgress?,
    onCancelOnGoingTask: () -> Unit,
    clock: () -> Long = System::currentTimeMillis,
) {
    val handedOverAt = progress?.handedOverAt
    var now by remember { mutableLongStateOf(clock()) }

    LaunchedEffect(handedOverAt) {
        if (handedOverAt != null) {
            delay(handedOverAt + STOP_WAITING_AFTER.inWholeMilliseconds - clock())
            now = clock()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.surface)
            .onPreviewKeyEvent { true } // Disable all keyboard events
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                getTitle(processingTask),
                style = MaterialTheme.typography.h3,
                color = MaterialTheme.colors.onBackground,
                modifier = Modifier.padding(bottom = 8.dp),
            )
// TODO Does this need to be restored?
//            if (processingState.subtitle.isNotEmpty()) {
//                Text(
//                    processingState.subtitle,
//                    style = MaterialTheme.typography.body1,
//                    color = MaterialTheme.colors.onBackground,
//                    modifier = Modifier.padding(bottom = 32.dp),
//                )
//            }

            val stage = progress?.stage
                ?: progress?.handOff?.let { stringResource(Res.string.processing_waiting_for_server, it.remote) }

            if (stage != null) {
                Text(
                    text = if (progress?.percent != null) "$stage ${progress.percent}%" else stage,
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            val percent = progress?.percent

            if (percent != null) {
                LinearProgressIndicator(
                    progress = percent / 100f,
                    modifier = Modifier.width(280.dp)
                        .padding(bottom = 32.dp),
                    color = MaterialTheme.colors.secondary,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.width(280.dp)
                        .padding(bottom = 32.dp),
                    color = MaterialTheme.colors.secondary,
                )
            }

            when (processingButton(progress, now)) {
                ProcessingButton.CANCEL -> SecondaryButton(
                    text = stringResource(Res.string.generic_button_cancel),
                    onClick = onCancelOnGoingTask,
                )

                ProcessingButton.STOP_WAITING -> SecondaryButton(
                    text = stringResource(Res.string.processing_stop_waiting),
                    onClick = onCancelOnGoingTask,
                )

                // Keeps the progress where it was
                null -> if (progress != null) Spacer(Modifier.height(BUTTON_PLACE_HEIGHT))
            }
        }
    }
}

@Composable
fun getTitle(taskType: TaskType): String {
    return when (taskType) {
        TaskType.AbortRebase -> "Aborting rebase"
        TaskType.AddIgnoreRules -> "Adding ignore rules"
        TaskType.AddRemote -> "Adding remote"
        TaskType.AddSubmodule -> "Adding submodule"
        TaskType.ApplyStash -> "Applying stash"
        TaskType.BlameFile -> "Blaming file"
        TaskType.ChangeBranchUpstream -> "Changing branch upstream"
        TaskType.ChangesDetection -> "Detecting changes"
        TaskType.CheckoutBranch -> "Checking out branch"
        TaskType.CheckoutCommit -> "Checking out commit"
        TaskType.CheckoutRemoteBranch -> "Checking out remote branch"
        TaskType.CheckoutTag -> "Checking out tag"
        TaskType.CherryPickCommit -> "Cherry picking commit"
        TaskType.ContinueRebase -> "Continuing rebase"
        TaskType.CreateBranch -> "Creating branch"
        TaskType.CreateTag -> "Creating tag"
        TaskType.DeinitSubmodule -> "Deinitializing submodule"
        TaskType.DeleteBranch -> "Deleting branch"
        TaskType.DeleteFile -> "Deleting file"
        TaskType.RestoreFileVersion -> "Restoring file"
        TaskType.DeleteRemote -> "Deleting remote"
        TaskType.DeleteRemoteBranch -> "Deleting remote branch"
        TaskType.DeleteStash -> "Deleting stash"
        TaskType.DeleteSubmodule -> "Deleting submodule"
        TaskType.DeleteTag -> "Deleting tag"
        TaskType.DiscardFile -> "Discarding file"
        TaskType.DiscardAllFiles -> "Discarding all changes"
        TaskType.DoCommit -> "Committing"
        TaskType.Fetch -> "Fetching"
        TaskType.GetCommitDiffEntries -> "Getting commit diff entries"
        TaskType.GetFileCommits -> "Getting file commits"
        TaskType.HistoryFile -> "Viewing file history"
        TaskType.InitSubmodule -> "Initializing submodule"
        TaskType.LoadAuthor -> "Loading author"
        TaskType.MergeBranch -> "Merging branch"
        TaskType.PopStash -> "Popping stash"
        TaskType.Pull -> "Pulling"
        TaskType.PullFromBranch -> "Pulling from branch"
        TaskType.Push -> "Pushing"
        TaskType.PushToBranch -> "Pushing to branch"
        TaskType.RebaseBranch -> "Rebasing branch"
        TaskType.RenameBranch -> "Renaming branch"
        TaskType.RepositoryClone -> "Cloning repository"
        TaskType.RepositoryInit -> "Creating repository"
        TaskType.RepositoryOpen -> "Opening repository"
        TaskType.SwitchWorktree -> "Switching worktree"
        TaskType.FollowRemovedWorktree -> "Switching to the main worktree"
        TaskType.CreateWorktree -> "Creating worktree"
        TaskType.RemoveWorktree -> "Removing worktree"
        TaskType.DiscardWorktree -> "Discarding worktree"
        TaskType.PruneWorktrees -> "Pruning worktrees"
        TaskType.CleanUpWorktrees -> "Removing worktrees"
        TaskType.LockWorktree -> "Locking worktree"
        TaskType.UnlockWorktree -> "Unlocking worktree"
        TaskType.SquashMergeWorktree -> "Squash-merging"
        TaskType.MergeWorktree -> "Merging"
        TaskType.AbortSquashMerge -> "Aborting squash merge"
        TaskType.RebaseWorktree -> "Rebasing branch"
        TaskType.CherryPickCommits, TaskType.CherryPickCommitsAsOne -> "Cherry-picking commits"
        TaskType.RewriteBranch -> "Rewriting commits"
        TaskType.UndoBranchRewrite -> "Undoing"
        TaskType.RunRebasePlan -> "Rewriting commits"
        TaskType.StartRebasePlan -> "Rebasing"
        TaskType.BackToRebasePlan -> "Aborting the rebase"
        TaskType.ContinueCherryPick -> "Continuing cherry-pick"
        TaskType.SkipCherryPick -> "Skipping commit"
        TaskType.AbortCherryPick -> "Aborting cherry-pick"
        TaskType.OpenMergeTool, TaskType.ResolveConflictsInMergeTool -> "Waiting for the merge tool"
        TaskType.ResolveConflict -> "Resolving conflicts"
        TaskType.ConvertIndex -> "Converting the index"
        TaskType.ResetRepoState -> "Resetting repository state"
        TaskType.ResetToCommit -> "Resetting to commit"
        TaskType.RevertCommit -> "Reverting commit"
        TaskType.SaveAuthor -> "Saving author"
        TaskType.SkipRebase -> "Skipping rebase"
        TaskType.StageAllFiles -> "Staging all files"
        TaskType.StageDir -> "Staging directory"
        TaskType.StageFile -> "Staging file"
        TaskType.StageHunk -> "Staging hunk"
        TaskType.StageLine -> "Staging line"
        TaskType.Stash -> "Stashing"
        TaskType.SyncSubmodule -> "Synchronizing submodule"
        TaskType.Unspecified -> "No task specified"
        TaskType.UnstageAllFiles -> "Unstaging all files"
        TaskType.UnstageDir -> "Unstaging directory"
        TaskType.UnstageFile -> "Unstaging file"
        TaskType.UnstageHunk -> "Unstaging hunk"
        TaskType.UnstageLine -> "Unstaging line"
        TaskType.UpdateSubmodule -> "Updating submodule"
        TaskType.RefreshBranches -> ""
        TaskType.RefreshLog -> ""
        TaskType.RefreshRemotes -> ""
        TaskType.RefreshRepositoryState -> ""
        TaskType.RefreshStashes -> ""
        TaskType.RefreshStatus -> ""
        TaskType.RefreshSubmodules -> ""
        TaskType.RefreshTags -> ""
        TaskType.GetWorktree -> ""
        TaskType.UpdateRemote -> ""
    }
}
