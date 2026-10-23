// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.context_menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.LineRange
import dev.app.leaf.domain.rewrite.RewriteDialogKind
import dev.app.leaf.domain.rewrite.RewriteRefusal
import dev.app.leaf.domain.rewrite.RewriteRequest
import dev.app.leaf.domain.rewrite.shortName
import dev.app.leaf.Screen
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the log's and the branches' menus need to squash and reword commits (fork-only, step A3 of
 * `docs/fork/squash-and-rebase.md`): every branch's own commits, the current branch, what a worktree is in the middle of
 * with a branch ([busy]), the dialog to open ([onOpen]) and the fixup that runs at once ([onFixup]).
 *
 * An item that can't be used is in the menu all the same, disabled, with why at its right.
 */
class CommitRewrite(
    private val lines: BranchLines,
    private val currentBranch: String?,
    private val busy: (branch: String) -> BranchOperation?,
    private val onOpen: (RewriteRequest) -> Unit,
    private val onFixup: (LineRange) -> Unit,
) {
    private val baseName = lines.base?.let(::shortName)

    /** A commit row's items: "Edit message…", "Squash into parent…" and "Fixup into parent". */
    fun commitItems(commit: String): List<ContextMenuElement> = buildList {
        val choices = lines.commitChoices(commit, currentBranch, busy)

        addChoice({ stringResource(Res.string.rewrite_menu_edit_message) }, Res.drawable.message, choices.reword) {
            onOpen(RewriteRequest(RewriteDialogKind.REWORD, it))
        }
        addChoice({ stringResource(Res.string.rewrite_menu_squash_into_parent) }, Res.drawable.align_top, choices.intoParent) {
            onOpen(RewriteRequest(RewriteDialogKind.SQUASH, it))
        }
        addChoice({ stringResource(Res.string.rewrite_menu_fixup_into_parent) }, Res.drawable.align_top, choices.intoParent) {
            onFixup(it)
        }
    }

    /** "Squash N commits…" on a row of [commits], selected in the log. */
    fun rangeItem(commits: Collection<String>): ContextMenuElement = buildList {
        val count = commits.size

        addChoice(
            label = { pluralStringResource(Res.plurals.rewrite_menu_squash_selected, count, count) },
            icon = Res.drawable.align_top,
            choice = lines.rangeChoice(commits, currentBranch, busy),
            isRange = true,
        ) {
            onOpen(RewriteRequest(RewriteDialogKind.SQUASH, it))
        }
    }.single()

    /** "Squash branch into one commit…" on the local branch [branch], or null for one with no commits of its own. */
    fun branchItem(branch: String): ContextMenuElement? {
        val choice = lines.branchChoice(branch, busy) ?: return null

        return buildList {
            addChoice({ stringResource(Res.string.rewrite_menu_squash_branch) }, Res.drawable.align_top, choice, isRange = true) {
                onOpen(RewriteRequest(RewriteDialogKind.SQUASH_BRANCH, it))
            }
        }.single()
    }

    private fun MutableList<ContextMenuElement>.addChoice(
        label: @Composable () -> String,
        icon: DrawableResource,
        choice: Either<LineRange, RewriteRefusal>,
        isRange: Boolean = false,
        onPick: (LineRange) -> Unit,
    ) {
        when (choice) {
            is Either.Ok -> addContextMenu(
                composableLabel = label,
                icon = { painterResource(icon) },
                onClick = { onPick(choice.value) },
            )

            is Either.Err -> addContextMenu(
                composableLabel = label,
                icon = { painterResource(icon) },
                hint = { rewriteRefusalText(choice.error, baseName, isRange) },
                isEnabled = false,
            )
        }
    }
}

/**
 * Given by the log and the side panel, so that upstream's composables between them and the rows don't change
 * (fork-only).
 */
val LocalCommitRewrite = compositionLocalOf<CommitRewrite?> { null }

/** The [CommitRewrite] of [viewModel]'s tab, whose dialog [onRewrite] opens; null without one, as in tests. */
@Composable
fun rememberCommitRewrite(
    viewModel: RepositoryOpenViewModel,
    currentBranch: Branch?,
    onRewrite: ((RewriteRequest) -> Unit)?,
): CommitRewrite? {
    val lines by viewModel.branchLines.collectAsState()

    return remember(viewModel, lines, currentBranch?.name, onRewrite) {
        onRewrite?.let { open ->
            CommitRewrite(
                lines = lines,
                currentBranch = currentBranch?.name,
                busy = viewModel::rewriteBusyWith,
                onOpen = open,
                onFixup = viewModel::fixupIntoParent,
            )
        }
    }
}

/**
 * Why an item can't rewrite, at its right: short, as the menu is narrow. [base] is the base branch's short name, and
 * [isRange] says that the item is about several commits.
 */
@Composable
fun rewriteRefusalText(refusal: RewriteRefusal, base: String?, isRange: Boolean): String = when (refusal) {
    is RewriteRefusal.OnBase -> if (isRange) {
        stringResource(Res.string.rewrite_refused_range_on_base, refusal.base)
    } else {
        stringResource(Res.string.rewrite_refused_on_base, refusal.base)
    }

    RewriteRefusal.NotOnLocalBranch, is RewriteRefusal.NotOnLine -> if (isRange) {
        stringResource(Res.string.rewrite_refused_range_not_on_one_branch)
    } else {
        stringResource(Res.string.rewrite_refused_not_on_local_branch)
    }

    is RewriteRefusal.OnSeveralBranches -> stringResource(Res.string.rewrite_refused_on_branches, refusal.count)
    RewriteRefusal.FirstCommit -> if (base != null) {
        stringResource(Res.string.rewrite_refused_parent_on_base, base)
    } else {
        stringResource(Res.string.rewrite_refused_first_commit)
    }

    RewriteRefusal.NotConsecutive -> stringResource(Res.string.rewrite_refused_not_consecutive)
    is RewriteRefusal.JoinsMerge -> stringResource(Res.string.rewrite_refused_joins_merge)
    RewriteRefusal.OneCommit -> stringResource(Res.string.rewrite_refused_one_commit)
    RewriteRefusal.TooManyCommits -> stringResource(Res.string.rewrite_refused_too_many)
    is RewriteRefusal.Busy -> stringResource(
        when (refusal.operation) {
            BranchOperation.MERGE -> Res.string.rewrite_refused_merging
            BranchOperation.CHERRY_PICK -> Res.string.rewrite_refused_cherry_picking
            BranchOperation.REVERT -> Res.string.rewrite_refused_reverting
            BranchOperation.REBASE -> Res.string.rewrite_refused_rebasing
            BranchOperation.BISECT -> Res.string.rewrite_refused_bisecting
            BranchOperation.APPLY_PATCHES -> Res.string.rewrite_refused_applying_patches
        }
    )
}

/** The dialog of [request], whose Squash or Save rewrites the commits in [viewModel]'s tab (fork-only). */
fun rewriteCommitsScreen(viewModel: RepositoryOpenViewModel, request: RewriteRequest) = Screen.RewriteCommits(
    request = request,
    cleanup = viewModel.statusState.value.messageCleanup,
    onConfirm = { message -> viewModel.rewriteBranch(request, message) },
)
