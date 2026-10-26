// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.cli.remote.testRemoteOperationsBackend
import dev.app.leaf.data.git.cli.remote.CheckRemoteBranchesGitAction
import dev.app.leaf.data.git.cli.remote.DeleteRemoteBranchesGitAction
import dev.app.leaf.data.git.cli.remote.GitCliFetchAllRemotesGitAction
import dev.app.leaf.data.git.branches.GetRemoteBranchDeletionInfoGitAction
import dev.app.leaf.data.git.FileChangesWatcher
import dev.app.leaf.data.git.GetWorktreePathGitAction
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.author.LoadAuthorGitAction
import dev.app.leaf.data.git.branches.DeleteBranchesGitAction
import dev.app.leaf.data.git.branches.GetBranchDeletionInfoGitAction
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.GetRefDatesGitAction
import dev.app.leaf.data.git.branches.GetRemoteBranchesGitAction
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.operations.AbortSquashMergeGitAction
import dev.app.leaf.data.git.cli.operations.Autostashes
import dev.app.leaf.data.git.cli.operations.GitCliMergeBranchGitAction
import dev.app.leaf.data.git.cli.operations.PutBackMergeAutostashGitAction
import dev.app.leaf.data.git.cli.operations.SelectingMergeBranchGitAction
import dev.app.leaf.data.git.cli.operations.testGitCliOperations
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.compare.GetComparisonGitAction
import dev.app.leaf.data.git.config.GetWorktreeCreationSettingsGitAction
import dev.app.leaf.data.git.config.LoadSignOffConfigGitAction
import dev.app.leaf.data.git.config.SaveWorktreeCreationSettingsGitAction
import dev.app.leaf.data.git.log.JGitGraphRevWalker
import dev.app.leaf.data.git.rebase.AbortRebaseGitAction
import dev.app.leaf.data.git.rebase.ContinueRebaseGitAction
import dev.app.leaf.data.git.rebase.GitCliRebase
import dev.app.leaf.data.git.rebase.SelectingAbortRebaseGitAction
import dev.app.leaf.data.git.rebase.SelectingContinueRebaseGitAction
import dev.app.leaf.data.git.rebase.SelectingSkipRebaseGitAction
import dev.app.leaf.data.git.rebase.SkipRebaseGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.repository.IsUnchangedSinceGitAction
import dev.app.leaf.data.git.repository.OpenRepositoryGitAction
import dev.app.leaf.data.git.repository.PersistCommitMessageGitAction
import dev.app.leaf.data.git.repository.ResetRepositoryStateGitAction
import dev.app.leaf.data.git.signers.GitSigningConfigs
import dev.app.leaf.data.git.stash.CreateSnapshotStashGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.stash.GetStashListGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.tags.GetTagsGitAction
import dev.app.leaf.data.git.workspace.AddTrailerGitAction
import dev.app.leaf.data.git.workspace.AddIgnoreRulesGitAction
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.git.workspace.CleanUpAfterCommitGitAction
import dev.app.leaf.data.git.workspace.DiscardAllGitAction
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.testAppSettings
import dev.app.leaf.data.git.workspace.FileRestorePlanner
import dev.app.leaf.data.git.workspace.RestoreFileVersionGitAction
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.usecases.RestoreFileVersionUseCase
import dev.app.leaf.data.git.workspace.DiscardEntriesGitAction
import dev.app.leaf.data.git.workspace.DoCommitGitAction
import dev.app.leaf.data.git.workspace.GetPersistedCommitMessagesGitAction
import dev.app.leaf.data.git.workspace.GlobalIgnoreFile
import dev.app.leaf.data.git.workspace.IgnoreTargets
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.data.git.workspace.GetStatusGitAction
import dev.app.leaf.data.git.worktrees.CopyWorktreeFilesGitAction
import dev.app.leaf.data.git.worktrees.GetAheadBehindGitAction
import dev.app.leaf.data.git.worktrees.GetBranchMergedGitAction
import dev.app.leaf.data.git.worktrees.GetCommitTimesGitAction
import dev.app.leaf.data.git.worktrees.GetMergedIntoBaseGitAction
import dev.app.leaf.data.git.worktrees.GetOtherWorktreeHeadsGitAction
import dev.app.leaf.data.git.worktrees.GetCommonGitDirGitAction
import dev.app.leaf.data.git.worktrees.GetUnmergedFilesGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeActivityGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeBaseBranchGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreeStatusGitAction
import dev.app.leaf.data.git.worktrees.GetWorktreesGitAction
import dev.app.leaf.data.git.worktrees.PersistSquashMessageGitAction
import dev.app.leaf.data.git.worktrees.RunGitCommandGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.git.rewrite.GetBranchLinesGitAction
import dev.app.leaf.data.git.rewrite.RewriteBranchGitAction
import dev.app.leaf.data.git.rewrite.GetOntoBaseCheckGitAction
import dev.app.leaf.data.git.rewrite.UndoBranchRewriteGitAction
import dev.app.leaf.domain.usecases.RewriteBranchUseCase
import dev.app.leaf.domain.usecases.GetOntoBaseCheckUseCase
import dev.app.leaf.domain.usecases.UndoBranchRewriteUseCase
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.JGitTagMapper
import dev.app.leaf.data.mappers.RemoteConfigToRemoteMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.AppStateManager
import dev.app.leaf.domain.DataRefreshRunner
import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.WorktreesRefreshRunner
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IFetchAllRemotesGitAction
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.interfaces.IGetStatusGitAction
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.WatcherEvent
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.DataState
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.AbortRebaseUseCase
import dev.app.leaf.domain.usecases.DiscardAllUseCase
import dev.app.leaf.domain.usecases.StatusStageAllUseCase
import dev.app.leaf.data.git.workspace.StageAllGitAction
import dev.app.leaf.domain.usecases.AbortSquashMergeUseCase
import dev.app.leaf.domain.usecases.ContinueRebaseUseCase
import dev.app.leaf.domain.usecases.CreateWorktreeUseCase
import dev.app.leaf.domain.usecases.DeleteBranchesUseCase
import dev.app.leaf.domain.usecases.DoCommitUseCase
import dev.app.leaf.domain.usecases.FetchAllBranchUseCase
import dev.app.leaf.domain.usecases.GetBranchDeletionPlanUseCase
import dev.app.leaf.domain.usecases.FollowRemovedWorktreeUseCase
import dev.app.leaf.domain.usecases.GetAuthorUseCase
import dev.app.leaf.domain.usecases.GetLogUseCase
import dev.app.leaf.domain.usecases.GetNewWorktreeContextUseCase
import dev.app.leaf.domain.usecases.GetRemotesUseCase
import dev.app.leaf.domain.usecases.GetTrackingBranchUseCase
import dev.app.leaf.domain.usecases.GetWorktreeMergeUseCase
import dev.app.leaf.domain.usecases.GetWorktreeRebaseUseCase
import dev.app.leaf.domain.usecases.GetWorktreeRemovalUseCase
import dev.app.leaf.domain.usecases.AddIgnoreRulesUseCase
import dev.app.leaf.domain.usecases.GetWorktreeUseCase
import dev.app.leaf.domain.usecases.IncreaseLogCountUseCase
import dev.app.leaf.domain.usecases.LoadLogUntilCommitUseCase
import dev.app.leaf.domain.usecases.GetWorktreeCleanupUseCase
import dev.app.leaf.domain.usecases.GetWorktreesInfoUseCase
import dev.app.leaf.domain.usecases.MergeBranchUseCase
import dev.app.leaf.domain.usecases.ObserveRepositoryToRefreshUseCase
import dev.app.leaf.domain.usecases.OpenRepositoryUseCase
import dev.app.leaf.domain.usecases.PersistCommitMessageUseCase
import dev.app.leaf.data.git.cherrypick.GetBranchCherryPickCommitsGitAction
import dev.app.leaf.data.git.cherrypick.GetBranchesAheadGitAction
import dev.app.leaf.data.git.cherrypick.GetCherryPickSequenceGitAction
import dev.app.leaf.data.git.cli.operations.GitCliCherryPickCommitsGitAction
import dev.app.leaf.data.git.cli.operations.GitCliCherryPickSequenceGitAction
import dev.app.leaf.domain.usecases.CherryPickCommitsUseCase
import dev.app.leaf.domain.usecases.CherryPickSequenceUseCase
import dev.app.leaf.domain.usecases.GetBranchCherryPickUseCase
import dev.app.leaf.data.git.rebase.GetCommitsOnHeadGitAction
import dev.app.leaf.data.git.rebase.GetRebaseStopGitAction
import dev.app.leaf.domain.rebase.SkippedRebaseCommits
import dev.app.leaf.domain.usecases.RefreshDataUseCase
import dev.app.leaf.domain.usecases.ReportSkippedRebaseCommitsUseCase
import dev.app.leaf.domain.usecases.ResetRepositoryStateUseCase
import dev.app.leaf.domain.usecases.RunWorktreeStepsUseCase
import dev.app.leaf.domain.usecases.SkipRebaseUseCase
import dev.app.leaf.domain.usecases.SwitchWorktreeUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.data.git.cli.remote.CheckHandOffGitAction
import dev.app.leaf.data.git.cli.remote.GitCliDeleteRemoteBranchGitAction
import dev.app.leaf.data.git.cli.remote.GitCliPushBranchGitAction
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchGitAction
import dev.app.leaf.domain.interfaces.IPushBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.remote.StoppedWaitingCheck
import dev.app.leaf.domain.usecases.DeleteRemoteBranchUseCase
import dev.app.leaf.domain.usecases.PushBranchUseCase

/**
 * A tab's repositories and use cases, built as Dagger builds them for each tab, with the real git actions. Each watch
 * of the file watcher gets a [RecordingFileChangesWatcher], in [watchers], or with [realFileWatcher] the app's
 * [FileChangesWatcher].
 */
class TestTab(
    jgit: JGit,
    gitCli: GitCli,
    statusDelay: Duration = Duration.ZERO,
    realFileWatcher: Boolean = false,
    backUpBeforeMerge: Boolean = true,
) : AutoCloseable {
    val scope = TabCoroutineScope()
    val repositoryData = InMemoryRepositoryDataRepository()
    val repositoryState = InMemoryRepositoryStateRepository()
    val watchers: MutableList<RecordingFileChangesWatcher> = CopyOnWriteArrayList()

    private lateinit var refresh: RefreshDataUseCase

    /** Fork-only: the commits that Skip left out of a rebase, which the refresh reports once it ends. */
    val skippedRebaseCommits = SkippedRebaseCommits()

    val useCaseExecutor = UseCaseExecutor(repositoryData, repositoryState, Provider { refresh }, scope)

    private val commitMapper = JGitCommitMapper(JGitIdentityMapper())
    private val getBranches = GetBranchesGitAction(JGitBranchMapper(), jgit)
    private val getCurrentBranch = GetCurrentBranchGitAction(getBranches, jgit)
    private val getStashList = GetStashListGitAction(commitMapper, jgit)
    private val getStatus = GetStatusGitAction(jgit)
    private val getTags = GetTagsGitAction(JGitTagMapper(), jgit)
    private val getWorktreesInfo = GetWorktreesInfoUseCase(
        GetWorktreesGitAction(gitCli, jgit),
        GetWorktreeStatusGitAction(gitCli),
        GetAheadBehindGitAction(gitCli),
        GetWorktreeBaseBranchGitAction(jgit),
        GetCommitTimesGitAction(jgit),
        GetMergedIntoBaseGitAction(gitCli, jgit),
        GetWorktreeActivityGitAction(gitCli),
        useCaseExecutor,
    )
    private val getRemotes = GetRemotesUseCase(
        GetRemotesGitAction(RemoteConfigToRemoteMapper(), jgit),
        GetRemoteBranchesGitAction(JGitBranchMapper(), jgit),
        useCaseExecutor,
    )
    private val getWorktree = GetWorktreeUseCase(GetWorktreePathGitAction(jgit), useCaseExecutor)

    private val getLog = GetLogUseCase(
        getBranchesGitAction = getBranches,
        getTagsGitAction = getTags,
        getRemotesUseCase = getRemotes,
        getStashListGitAction = getStashList,
        getOtherWorktreeHeadsGitAction = GetOtherWorktreeHeadsGitAction(gitCli, jgit),
        getStatusGitAction = getStatus,
        getCurrentBranchAction = getCurrentBranch,
        graphLogGenerator = GraphLogGenerator(Provider { JGitGraphRevWalker(jgit, commitMapper) }),
        repositoryDataRepository = repositoryData,
    )
    private val dataRefreshRunner = DataRefreshRunner()

    val refreshData = RefreshDataUseCase(
        useCaseExecutor = useCaseExecutor,
        getBranchesGitAction = getBranches,
        getCurrentBranchGitAction = getCurrentBranch,
        repositoryDataRepository = repositoryData,
        repositoryStateRepository = repositoryState,
        getStashListGitAction = getStashList,
        loadAuthorGitAction = LoadAuthorGitAction(jgit, GitIdentities(gitCli)),
        getStatusGitAction = SlowStatus(getStatus, statusDelay),
        getRemotesUseCase = getRemotes,
        getSubmodulesGitAction = GetSubmodulesGitAction(jgit, JGitSubmoduleMapper()),
        getTagsGitAction = getTags,
        getRefDatesGitAction = GetRefDatesGitAction(jgit),
        getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
        getPersistedCommitMessagesGitAction = GetPersistedCommitMessagesGitAction(jgit),
        getTrackingBranchUseCase = GetTrackingBranchUseCase(GetTrackingBranchGitAction(jgit), useCaseExecutor),
        getLogUseCase = getLog,
        getWorktreesInfoUseCase = getWorktreesInfo,
        getBranchesAheadGitAction = GetBranchesAheadGitAction(gitCli),
        getBranchLinesGitAction = GetBranchLinesGitAction(jgit, GetWorktreeBaseBranchGitAction(jgit), commitMapper),
        getCherryPickSequenceGitAction = GetCherryPickSequenceGitAction(),
        getRebaseStopGitAction = GetRebaseStopGitAction(jgit),
        reportSkippedRebaseCommitsUseCase = ReportSkippedRebaseCommitsUseCase(
            skippedRebaseCommits,
            GetCommitsOnHeadGitAction(jgit),
            repositoryState,
        ),
        worktreesRefreshRunner = WorktreesRefreshRunner(),
        dataRefreshRunner = dataRefreshRunner,
        scope = scope,
    ).also { refresh = it }

    /** Loads the log further than its first commits, as scrolling to its end does, and until a commit is there. */
    val increaseLogCount = IncreaseLogCountUseCase(repositoryData, useCaseExecutor, getLog, dataRefreshRunner)
    val loadLogUntilCommit = LoadLogUntilCommitUseCase(repositoryData, increaseLogCount)

    val observeRepositoryToRefresh = ObserveRepositoryToRefreshUseCase(
        tabCoroutineScope = scope,
        fileChangesWatcherProvider = Provider {
            if (realFileWatcher) FileChangesWatcher() else RecordingFileChangesWatcher().also { watchers += it }
        },
        repositoryDataRepository = repositoryData,
        getWorktreeUseCase = getWorktree,
        refreshDataUseCase = refreshData,
        repositoryStateRepository = repositoryState,
        getStatusGitAction = getStatus,
        getCommonGitDirGitAction = GetCommonGitDirGitAction(jgit),
    )

    val openRepository = OpenRepositoryUseCase(
        repositoryDataRepository = repositoryData,
        repositoryStateRepository = repositoryState,
        openRepositoryGitAction = OpenRepositoryGitAction(),
        refreshDataUseCase = refreshData,
        observeRepositoryToRefreshUseCase = observeRepositoryToRefresh,
        getWorktreeUseCase = getWorktree,
        appStateManager = AppStateManager(mockk<AppSettingsRepository>(relaxed = true)),
    )

    val switchWorktree = SwitchWorktreeUseCase(
        useCaseExecutor = useCaseExecutor,
        openRepositoryGitAction = OpenRepositoryGitAction(),
        repositoryDataRepository = repositoryData,
        observeRepositoryToRefreshUseCase = observeRepositoryToRefresh,
        refreshDataUseCase = refreshData,
    )

    val runWorktreeSteps = RunWorktreeStepsUseCase(
        useCaseExecutor = useCaseExecutor,
        runGitCommandGitAction = RunGitCommandGitAction(gitCli),
        copyWorktreeFilesGitAction = CopyWorktreeFilesGitAction(),
        getUnmergedFilesGitAction = GetUnmergedFilesGitAction(gitCli),
        openRepositoryGitAction = OpenRepositoryGitAction(),
        persistSquashMessageGitAction = PersistSquashMessageGitAction(jgit),
        switchWorktreeUseCase = switchWorktree,
        repositoryDataRepository = repositoryData,
    )

    val getWorktreeMerge = GetWorktreeMergeUseCase(
        useCaseExecutor = useCaseExecutor,
        repositoryDataRepository = repositoryData,
        getWorktreeStatusGitAction = GetWorktreeStatusGitAction(gitCli),
        getWorktreeActivityGitAction = GetWorktreeActivityGitAction(gitCli),
        getComparisonGitAction = GetComparisonGitAction(jgit, commitMapper, testPartialCloneFetch(jgit)),
        getBranchMergedGitAction = GetBranchMergedGitAction(gitCli),
        runGitCommandGitAction = RunGitCommandGitAction(gitCli),
    )

    private val autostashes = Autostashes(gitCli, repositoryState)

    val abortSquashMerge = AbortSquashMergeUseCase(
        useCaseExecutor = useCaseExecutor,
        abortSquashMergeGitAction = AbortSquashMergeGitAction(autostashes, gitCli, jgit),
    )

    val doCommit = DoCommitUseCase(
        doCommitGitAction = DoCommitGitAction(
            GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
            commitMapper,
            JGitIdentityMapper(),
            jgit,
            GitIdentities(gitCli),
            GitSigningConfigs(gitCli),
        ),
        useCaseExecutor = useCaseExecutor,
        loadSignOffConfigGitAction = LoadSignOffConfigGitAction(jgit),
        getAuthorUseCase = GetAuthorUseCase(LoadAuthorGitAction(jgit, GitIdentities(gitCli)), useCaseExecutor),
        persistCommitMessageUseCase = PersistCommitMessageUseCase(
            PersistCommitMessageGitAction(jgit, RepositoryStateMapper()),
        ),
        cleanUpAfterCommitGitAction = CleanUpAfterCommitGitAction(jgit),
        putBackMergeAutostashGitAction = PutBackMergeAutostashGitAction(autostashes, jgit),
        addTrailerGitAction = AddTrailerGitAction(gitCli),
    )

    val resetRepositoryState = ResetRepositoryStateUseCase(
        useCaseExecutor = useCaseExecutor,
        resetRepositoryStateGitAction = ResetRepositoryStateGitAction(jgit),
        putBackMergeAutostashGitAction = PutBackMergeAutostashGitAction(autostashes, jgit),
    )

    private val operations = testGitCliOperations(jgit, gitCli, repositoryState = repositoryState)

    /** Fork-only: a branch's commits cherry-picked from its dialog, and the Continue, Skip and Abort that follow. */
    val getBranchCherryPick = GetBranchCherryPickUseCase(
        useCaseExecutor = useCaseExecutor,
        getCurrentBranchGitAction = getCurrentBranch,
        getWorktreePathGitAction = GetWorktreePathGitAction(jgit),
        getBranchCherryPickCommitsGitAction = GetBranchCherryPickCommitsGitAction(gitCli, jgit),
        getRepositoryStateGitAction = GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
        getCherryPickSequenceGitAction = GetCherryPickSequenceGitAction(),
        getWorktreeStatusGitAction = GetWorktreeStatusGitAction(gitCli),
    )

    val cherryPickCommits = CherryPickCommitsUseCase(
        useCaseExecutor = useCaseExecutor,
        cherryPickCommitsGitAction = GitCliCherryPickCommitsGitAction(operations),
        persistSquashMessageGitAction = PersistSquashMessageGitAction(jgit),
    )

    val cherryPickSequence = CherryPickSequenceUseCase(
        useCaseExecutor = useCaseExecutor,
        cherryPickSequenceGitAction = GitCliCherryPickSequenceGitAction(operations),
        persistCommitMessageGitAction = PersistCommitMessageGitAction(jgit, RepositoryStateMapper()),
    )

    /** Merge in the branch menu, with git, and Leaf's backup when [backUpBeforeMerge]. */
    val mergeBranch = MergeBranchUseCase(
        mergeBranchGitAction = SelectingMergeBranchGitAction(
            operations,
            GitCliMergeBranchGitAction(operations),
            MergeBranchGitAction(jgit, testPartialCloneFetch(jgit)),
        ),
        appSettingsService = AppSettingsService(
            mockk<AppSettingsRepository> {
                every { autoStashOnMerge } returns flowOf(backUpBeforeMerge)
                every { fastForwardMerge } returns flowOf(false)
                every { gitExecutablePath } returns flowOf(null)
            }
        ),
        checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
        useCaseExecutor = useCaseExecutor,
        deleteStashGitAction = DeleteStashGitAction(jgit),
        createSnapshotStashGitAction = CreateSnapshotStashGitAction(commitMapper, jgit),
        isUnchangedSinceGitAction = IsUnchangedSinceGitAction(jgit),
    )

    val getWorktreeRebase = GetWorktreeRebaseUseCase(
        useCaseExecutor = useCaseExecutor,
        repositoryDataRepository = repositoryData,
        getWorktreeStatusGitAction = GetWorktreeStatusGitAction(gitCli),
        getWorktreeActivityGitAction = GetWorktreeActivityGitAction(gitCli),
        getAheadBehindGitAction = GetAheadBehindGitAction(gitCli),
        runGitCommandGitAction = RunGitCommandGitAction(gitCli),
    )

    private val gitCliRebase = GitCliRebase(operations)

    val continueRebase = ContinueRebaseUseCase(
        useCaseExecutor = useCaseExecutor,
        continueRebaseGitAction = SelectingContinueRebaseGitAction(gitCliRebase, ContinueRebaseGitAction(jgit)),
        doCommitUseCase = doCommit,
    )

    val skipRebase = SkipRebaseUseCase(
        useCaseExecutor = useCaseExecutor,
        skipRebaseGitAction = SelectingSkipRebaseGitAction(gitCliRebase, SkipRebaseGitAction(jgit)),
        skippedRebaseCommits = skippedRebaseCommits,
    )

    val abortRebase = AbortRebaseUseCase(
        useCaseExecutor = useCaseExecutor,
        abortRebaseGitAction = SelectingAbortRebaseGitAction(gitCliRebase, AbortRebaseGitAction(jgit)),
    )

    val getWorktreeRemoval = GetWorktreeRemovalUseCase(
        useCaseExecutor = useCaseExecutor,
        repositoryDataRepository = repositoryData,
        getWorktreeStatusGitAction = GetWorktreeStatusGitAction(gitCli),
        getWorktreeActivityGitAction = GetWorktreeActivityGitAction(gitCli),
        getBranchMergedGitAction = GetBranchMergedGitAction(gitCli),
    )

    val followRemovedWorktree = FollowRemovedWorktreeUseCase(repositoryData, switchWorktree)

    val getWorktreeCleanup = GetWorktreeCleanupUseCase(getWorktreesInfo, GetWorktreeActivityGitAction(gitCli))

    val createWorktree = CreateWorktreeUseCase(
        useCaseExecutor = useCaseExecutor,
        saveWorktreeCreationSettingsGitAction = SaveWorktreeCreationSettingsGitAction(jgit),
        runWorktreeStepsUseCase = runWorktreeSteps,
    )

    val discardAll = DiscardAllUseCase(
        useCaseExecutor,
        DiscardAllGitAction(GetStatusGitAction(jgit), DiscardEntriesGitAction(jgit), jgit),
    )

    /** "Restore this version of the file" in file history, with git running through the built askpass helper. */
    val restoreFileVersion = RestoreFileVersionUseCase(
        useCaseExecutor,
        RestoreFileVersionGitAction(
            FileRestorePlanner(jgit, gitCli),
            GitCliRemoteCommand(
                gitCli = gitCli,
                askpassHelper = AskpassHelper { builtAskpassHelper() },
                credentialsStateManager = CredentialsStateManager(),
                credentialsRepository = CredentialsCacheRepository(),
                appSettingsService = testAppSettings(),
                repositoryStateRepository = repositoryState,
            ),
        ),
    )

    val stageAll = StatusStageAllUseCase(StageAllGitAction(GetStatusGitAction(jgit), jgit), useCaseExecutor)

    val addIgnoreRules = AddIgnoreRulesUseCase(
        useCaseExecutor,
        AddIgnoreRulesGitAction(jgit, IgnoreTargets(GlobalIgnoreFile(gitCli, LoginShellEnvironment { emptyMap() }))),
    )

    val getNewWorktreeContext = GetNewWorktreeContextUseCase(
        useCaseExecutor = useCaseExecutor,
        repositoryDataRepository = repositoryData,
        getWorktreeCreationSettingsGitAction = GetWorktreeCreationSettingsGitAction(jgit),
    )

    /**
     * Fork-only (upstream issue 116): what the "Delete branches" dialog reads, and its delete, of local branches and
     * of a remote's, which git deletes through the built askpass helper.
     */
    private val remoteBackend = testRemoteOperationsBackend(jgit, gitCli, builtAskpassHelper())
    private val remoteCommand = GitCliRemoteCommand(
        gitCli = gitCli,
        askpassHelper = AskpassHelper { builtAskpassHelper() },
        credentialsStateManager = CredentialsStateManager(),
        credentialsRepository = CredentialsCacheRepository(),
        appSettingsService = testAppSettings(),
        repositoryStateRepository = repositoryState,
    )
    val getBranchDeletionPlan = GetBranchDeletionPlanUseCase(
        useCaseExecutor = useCaseExecutor,
        getWorktreeBaseBranchGitAction = GetWorktreeBaseBranchGitAction(jgit),
        getBranchDeletionInfoGitAction = GetBranchDeletionInfoGitAction(jgit, gitCli),
        getRemoteBranchDeletionInfoGitAction = GetRemoteBranchDeletionInfoGitAction(jgit, gitCli, remoteBackend),
    )
    val deleteBranches = DeleteBranchesUseCase(
        DeleteBranchesGitAction(jgit),
        DeleteRemoteBranchesGitAction(jgit, remoteBackend, remoteCommand),
        CheckRemoteBranchesGitAction(jgit, remoteBackend, remoteCommand),
        useCaseExecutor,
    )

    /** Fetch all in the toolbar's Pull menu, with git. */
    val fetchAll = FetchAllBranchUseCase(
        object : IFetchAllRemotesGitAction {
            private val fetch = GitCliFetchAllRemotesGitAction(jgit, remoteCommand)

            override suspend fun invoke(repositoryPath: String, specificRemote: Remote?) =
                fetch(repositoryPath, specificRemote)
        },
        useCaseExecutor,
    )

    /** Fork-only: the toolbar's Push, with git, and a remote branch's Delete, which hand the server what it does alone. */
    val push = PushBranchUseCase(
        object : IPushBranchGitAction {
            private val push = GitCliPushBranchGitAction(jgit, GetTrackingBranchGitAction(jgit), remoteCommand)

            override suspend fun invoke(
                repositoryPath: String,
                force: Boolean,
                pushTags: Boolean,
                pushWithLease: Boolean,
                specificBranch: Branch?,
            ) = push(repositoryPath, force, pushTags, pushWithLease, specificBranch)
        },
        AppSettingsService(mockk<AppSettingsRepository> { every { pushWithLease } returns flowOf(true) }),
        useCaseExecutor,
    )
    val deleteRemoteBranch = DeleteRemoteBranchUseCase(
        object : IDeleteRemoteBranchGitAction {
            private val delete = GitCliDeleteRemoteBranchGitAction(jgit, DeleteBranchGitAction(jgit), remoteCommand)

            override suspend fun invoke(repositoryPath: String, ref: Branch) = delete(repositoryPath, ref)
        },
        useCaseExecutor,
    )

    /** What the processing screen's Stop waiting starts: asking the server what it did. */
    val stoppedWaitingCheck = StoppedWaitingCheck(CheckHandOffGitAction(jgit, remoteCommand), useCaseExecutor, Provider { refreshData }, scope)

    /** Fork-only: squashing, fixing up and rewording a branch's commits, and undoing it. */
    val rewriteBranch = RewriteBranchUseCase(
        useCaseExecutor,
        RewriteBranchGitAction(jgit, gitCli, remoteCommand, repositoryState),
    )
    val undoBranchRewrite = UndoBranchRewriteUseCase(useCaseExecutor, UndoBranchRewriteGitAction(jgit, gitCli))
    val checkOntoBase = GetOntoBaseCheckUseCase(useCaseExecutor, GetOntoBaseCheckGitAction(jgit, gitCli))

    /** Waits until [flow] has loaded data that passes [predicate]. */
    suspend fun <T> awaitLoaded(flow: Flow<DataState<T>>, predicate: (T) -> Boolean = { true }): T {
        return withTimeout(10.seconds) {
            (flow.first { it is DataState.Loaded && predicate(it.data) } as DataState.Loaded).data
        }
    }

    /** Waits until the file watcher has had [count] watches, and returns the last one. */
    suspend fun awaitWatch(count: Int): RecordingFileChangesWatcher = withTimeout(10.seconds) {
        while (watchers.size < count) {
            delay(10)
        }

        watchers[count - 1]
    }

    /** Cancels the tab and waits for what it runs, such as a refresh, which could still use the repository. */
    override fun close() = runBlocking {
        withTimeout(10.seconds) { scope.coroutineContext.job.cancelAndJoin() }
    }
}

/** Takes [delay] longer for each status, as in a large worktree, so that a test can see what happens meanwhile. */
private class SlowStatus(private val status: IGetStatusGitAction, private val delay: Duration) : IGetStatusGitAction {
    override suspend fun invoke(repository: String, paths: List<String>): Either<Status, AppError> {
        delay(delay)
        return status(repository, paths)
    }
}

/**
 * A file watcher that only notes the folders it was given, and whether it was closed. Like the app's watcher, it
 * skips a folder that doesn't exist. It never sees changes: it reports the [events] that a test sends.
 */
class RecordingFileChangesWatcher : IFileChangesWatcher {
    val watchedPaths: MutableList<String> = CopyOnWriteArrayList()
    val events = Channel<WatcherEvent>(Channel.UNLIMITED)

    @Volatile
    var isClosed = false
        private set

    private val recursivePaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun addPathToWatch(path: String, isRecursive: Boolean) {
        if (File(path).isDirectory) {
            // As the app's watcher, watching a folder again is one registration still
            if (path !in watchedPaths) watchedPaths += path

            if (isRecursive) recursivePaths += path else recursivePaths -= path
        }
    }

    override fun removePathFromWatch(path: String) {
        watchedPaths -= path
        recursivePaths -= path
    }

    /** Whether [path] is watched with everything under it. */
    fun isWatchedRecursively(path: String) = path in recursivePaths

    override suspend fun observeEvents(): Flow<WatcherEvent> = events.receiveAsFlow()

    override fun close() {
        isClosed = true
    }
}
