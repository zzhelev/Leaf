package dev.app.leaf.domain.models

import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.RefPanelSettings

sealed interface AppConfig {
    data class ScaleUi(val value: Float?) : AppConfig
    data class LinesHeight(val value: LinesHeightType?) : AppConfig
    data class PullWithRebase(val value: Boolean) : AppConfig
    data class PushWithLease(val value: Boolean) : AppConfig
    data class FastForwardMerge(val value: Boolean) : AppConfig
    data class AutoStashOnMerge(val value: Boolean) : AppConfig
    data class DateFormatUseDefault(val value: Boolean) : AppConfig
    data class DateFormatCustomFormat(val value: String) : AppConfig
    data class DateFormatIs24h(val value: Boolean) : AppConfig
    data class DateFormatUseRelative(val value: Boolean) : AppConfig
    data class CloneDefaultDirectory(val value: String) : AppConfig
    data class CacheCredentialsInMemory(val value: Boolean) : AppConfig
    data class AvatarProvider(val value: AvatarProviderType) : AppConfig
    data class Theme(val value: dev.app.leaf.domain.models.ui.Theme) : AppConfig
    data class CustomTheme(val value: String) : AppConfig
    data class SwapStatusPanes(val value: Boolean) : AppConfig

    /** Whether discarding a hunk or a line in the diff asks first. Discarding files always asks. */
    data class ConfirmHunkAndLineDiscards(val value: Boolean) : AppConfig

    data class DiffDisplayFullFile(val value: Boolean) : AppConfig
    data class DiffTextViewType(val value: dev.app.leaf.domain.models.DiffTextViewType) : AppConfig
    data class TerminalPath(val value: String) : AppConfig
    data class GitExecutablePath(val value: String) : AppConfig
    data class RemoteOperationsWithGit(val value: Boolean) : AppConfig
    data class RefPanel(val value: RefPanelSettings) : AppConfig
    data class FilesChangedView(val value: FilesViewState) : AppConfig
    data class LogColumns(val value: LogColumnsSettings) : AppConfig

    /** Seconds between two refreshes of the worktree list while it's shown, 0 for none. See [WorktreesRefreshIntervals]. */
    data class WorktreesRefreshInterval(val seconds: Int) : AppConfig

    /** Days without a change after which a worktree shows as idle, 0 for never. See [WorktreesUntouchedDays]. */
    data class WorktreesUntouchedDays(val days: Int) : AppConfig

    /** The user's rules for labelling worktrees, tried in this order before the built-in ones. */
    data class WorktreeLabels(val rules: List<WorktreeLabelRule>) : AppConfig

    /** What double-clicking a conflicted file and the banner's Resolve conflicts open (fork-only). */
    data class ConflictOpener(val value: dev.app.leaf.domain.models.ConflictOpener) : AppConfig

    /** How the built-in resolver lays out the sides and the result (fork-only). */
    data class ConflictResolverLayout(val value: dev.app.leaf.domain.models.ConflictResolverLayout) : AppConfig

    /** Whether the built-in resolver wraps long lines, or keeps each on one row and scrolls sideways (fork-only). */
    data class ConflictResolverWrapLines(val value: Boolean) : AppConfig

    /** Whether the diff pane wraps long lines, or keeps each on one row and scrolls sideways (fork-only). */
    data class DiffWrapLines(val value: Boolean) : AppConfig
}