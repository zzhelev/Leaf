package dev.app.leaf.ui.context_menu

import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.delete
import dev.app.leaf.app.generated.resources.edit
import dev.app.leaf.app.generated.resources.remote_context_menu_edit
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

fun remoteContextMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFetch: () -> Unit,
): List<ContextMenuElement> = listOf(
    ContextMenuElement.ContextTextEntry(
        composableLabel = { stringResource(Res.string.remote_context_menu_edit) },
        icon = { painterResource(Res.drawable.edit) },
        onClick = onEdit
    ),
    ContextMenuElement.ContextTextEntry(
        composableLabel = { stringResource(Res.string.remote_context_menu_delete) },
        icon = { painterResource(Res.drawable.delete) },
        onClick = onDelete
    ),
    ContextMenuElement.ContextSeparator,
    ContextMenuElement.ContextTextEntry(
        composableLabel = { stringResource(Res.string.remote_context_menu_fetch_all) },
        icon = null,
        onClick = onFetch
    ),
)