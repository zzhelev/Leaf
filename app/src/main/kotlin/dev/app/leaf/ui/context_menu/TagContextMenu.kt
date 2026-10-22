package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.delete
import dev.app.leaf.app.generated.resources.start
import dev.app.leaf.app.generated.resources.tag_context_menu_checkout_tag_commit
import dev.app.leaf.domain.models.Branch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun tagContextMenuItems(
    onCheckoutTag: () -> Unit,
    onDeleteTag: () -> Unit,
    // Fork-only: compares the tagged commit with the current branch
    currentBranch: Branch? = null,
    onCompareWithCurrent: (() -> Unit)? = null,
    // Fork-only: compares the tagged commit with a branch, tag or commit picked from a list
    onCompareWith: (() -> Unit)? = null,
): List<ContextMenuElement> {
    return mutableListOf<ContextMenuElement>(
        ContextMenuElement.ContextTextEntry(
            composableLabel = { stringResource(Res.string.tag_context_menu_checkout_tag_commit) },
            icon = { painterResource(Res.drawable.start) },
            onClick = onCheckoutTag
        ),
    ).apply {
        val compareWithCurrent = onCompareWithCurrent?.takeIf { currentBranch != null }

        if (compareWithCurrent != null || onCompareWith != null) {
            add(ContextMenuElement.ContextSeparator)

            if (compareWithCurrent != null && currentBranch != null) {
                addCompareWithCurrent(currentBranch, compareWithCurrent)
            }

            if (onCompareWith != null) {
                addCompareWith(onCompareWith)
            }

            add(ContextMenuElement.ContextSeparator)
        }
    } + listOf(
        ContextMenuElement.ContextTextEntry(
            composableLabel = { stringResource(Res.string.tag_context_menu_delete_tag) },
            icon = { painterResource(Res.drawable.delete) },
            onClick = onDeleteTag
        )
    )
}