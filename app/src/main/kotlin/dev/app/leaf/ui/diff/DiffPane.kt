@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf.ui.diff

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.common.printError
import dev.app.leaf.data.git.animatedImages
import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.extensions.*
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.repositories.CloseableView
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.toStringWithSpaces
import dev.app.leaf.repositoryopen.RepositoryOpenViewModel
import dev.app.leaf.theme.*
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.LocalSidewaysScroll
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.components.SidewaysScroll
import dev.app.leaf.ui.components.UnwrappedLine
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.ContextMenu
import dev.app.leaf.ui.context_menu.ContextMenuElement
import dev.app.leaf.ui.context_menu.SelectionAwareTextContextMenu
import dev.app.leaf.ui.dialogs.ConfirmableAction
import dev.app.leaf.ui.status.StatusAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.compose.animatedimage.Blank
import org.jetbrains.compose.animatedimage.animate
import org.jetbrains.compose.animatedimage.loadAnimatedImage
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import java.io.FileInputStream
import java.text.NumberFormat
import kotlin.math.max

private const val MAX_MOVES_COUNT = 5
private const val TAG = "DiffPane"

/**
 * A line of a diff is drawn up to this many characters, as in VS Code (`editor.stopRenderingLineAfter`): a minified file
 * can have lines of hundreds of thousands, which froze the view.
 */
const val MAX_DIFF_LINE_CHARACTERS = 10_000

/** Syntax colours cover this many characters of a line, as each span is a style that its layout splits at. */
const val MAX_HIGHLIGHTED_LINE_CHARACTERS = 1_000

@Composable
private fun <T> loadOrNull(key: Any, action: suspend () -> T?): T? {
    var result: T? by remember(key) { mutableStateOf(null) }
    // Keyed like the result, so that another image loads too
    LaunchedEffect(key) {
        result = try {
            action()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            printError(TAG, "Loading failed", ex)
            null
        }
    }
    return result
}

@Composable
fun DiffPane(
    viewModel: RepositoryOpenViewModel,
    onCloseDiffView: () -> Unit,
    /** Asks before a discard, which `onConfirm` runs. `onStopAsking` is the dialog's "Don't ask again". */
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit, onStopAsking: () -> Unit) -> Unit,
    // Fork-only: a comparison's full view shows the diff without a close button, and Esc leaves the view rather than
    // closing the diff. It adds its buttons for the files to the header, and names the sides above the lines.
    isClosable: Boolean = true,
    headerActions: (@Composable () -> Unit)? = null,
    sideLabels: (@Composable (isSplit: Boolean) -> Unit)? = null,
    /** The header's button that hides or shows the log beside the diff (fork-only), where the log is. */
    logToggle: (@Composable () -> Unit)? = null,
) {
    // Fork-only: the lines mark their whitespace while the View menu says so
    val showWhitespace by viewModel.diffShowWhitespace.collectAsState()
    // Fork-only: and take the colours of their versions, once they're coloured
    val diffResult by viewModel.diffResult.collectAsState()
    val highlightedVersions by viewModel.diffHighlights.collectAsState()
    val loadedDiff = (diffResult as? ViewDiffResult.Loaded)?.diffResult
    val highlights = remember(loadedDiff, highlightedVersions) { loadedDiff?.let(highlightedVersions::forDiff) }

    CompositionLocalProvider(LocalShowWhitespace provides showWhitespace, LocalDiffHighlights provides highlights) {
        DiffPaneContent(viewModel, onCloseDiffView, onConfirmAction, isClosable, headerActions, sideLabels, logToggle)
    }
}

@Composable
private fun DiffPaneContent(
    viewModel: RepositoryOpenViewModel,
    onCloseDiffView: () -> Unit,
    onConfirmAction: (ConfirmableAction, onConfirm: () -> Unit, onStopAsking: () -> Unit) -> Unit,
    isClosable: Boolean,
    headerActions: (@Composable () -> Unit)?,
    sideLabels: (@Composable (isSplit: Boolean) -> Unit)?,
    logToggle: (@Composable () -> Unit)?,
) {
    val diffResultState = viewModel.diffResult.collectAsState()
    val confirmDiscards by viewModel.confirmHunkAndLineDiscards.collectAsState()
    val textDiffType by viewModel.diffTypeFlow.collectAsState(DiffTextViewType.Unified)
    val isDisplayFullFile by viewModel.isDisplayFullFile.collectAsState(false)
    val wrapLines by viewModel.diffWrapLines.collectAsState()
    val whitespaceMode by viewModel.diffWhitespace.collectAsState()
    val showWhitespace by viewModel.diffShowWhitespace.collectAsState()
    val viewDiffResult = diffResultState.value ?: return
    val isRepositoryInSafeState by viewModel.isRepositoryInSafeState.collectAsState(false)
    val conflictSides by viewModel.conflictSides.collectAsState()
    val focusRequester = remember { FocusRequester() }

    fun closeDiffView(forgetFile: Boolean = true) {
        viewModel.clearDiff()
        // Fork-only: a diff that the user closed is no longer followed from commit to commit
        if (forgetFile) viewModel.forgetFollowedFile()
        onCloseDiffView()
    }

    // Asks first unless the user turned that off (fork-only). Discarding whole files always asks.
    fun discard(action: ConfirmableAction, run: () -> Unit) {
        if (confirmDiscards) {
            onConfirmAction(action, run) { viewModel.stopConfirmingHunkAndLineDiscards() }
        } else {
            run()
        }
    }

    fun discardHunk(entry: DiffEntry, hunk: Hunk) =
        discard(ConfirmableAction.DiscardHunk(entry.filePath, hunk)) { viewModel.resetHunk(entry, hunk) }

    fun discardLine(entry: DiffEntry, hunk: Hunk, line: Line) =
        discard(ConfirmableAction.DiscardLine(entry.filePath, line)) { viewModel.discardHunkLine(entry, hunk, line) }

    LaunchedEffect(Unit) {
        viewModel.closeViewFlow.collectLatest {
            if (it == CloseableView.DIFF && isClosable) closeDiffView()
        }
    }

    Column(
        modifier = Modifier
            .background(MaterialTheme.colors.background)
            .fillMaxSize()
            .focusable(true)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused && isClosable) {
                    viewModel.addToCloseables()
                }
            }
    ) {
        when (viewDiffResult) {
            is ViewDiffResult.DiffNotFound -> {
                // Not during composition, which may run it more than once. The file stays followed: the selection can
                // be cleared on purpose, for a commit that doesn't change it, while this pane is composed for one more frame.
                LaunchedEffect(viewDiffResult) {
                    closeDiffView(forgetFile = false)
                }
            }

            is ViewDiffResult.Loaded -> {
                val diffType = viewDiffResult.diffType
                val diffEntry = viewDiffResult.diffResult.diffEntry
                val diffResult = viewDiffResult.diffResult

                DiffHeader(
                    diffType = diffType,
                    diffEntry = diffEntry,
                    onCloseDiffView = { closeDiffView() }.takeIf { isClosable },
                    headerActions = headerActions,
                    textDiffViewType = textDiffType,
                    isTextDiff = diffResult is DiffResult.TextDiff,
                    isDisplayFullFile = isDisplayFullFile,
                    onStageFile = { viewModel.stageFile(it) },
                    onUnstageFile = { viewModel.unstageFile(it) },
                    onChangeDiffType = { viewModel.changeTextDiffType(it) },
                    onDisplayFullFile = { viewModel.changeDisplayFullFile(it) },
                    logToggle = logToggle,
                    viewMenu = {
                        DiffViewMenu(
                            diffType = diffType,
                            options = DiffViewOptions(whitespaceMode, showWhitespace, wrapLines),
                            onWhitespaceChanged = { viewModel.changeDiffWhitespace(it) },
                            onShowWhitespaceChanged = { viewModel.changeDiffShowWhitespace(it) },
                            onWrapLinesChanged = { viewModel.changeDiffWrapLines(it) },
                        )
                    },
                )

                // Fork-only: says that lines are hidden, and why the hunks and lines have no buttons
                val onlyWhitespaceChanged = diffResult is DiffResult.TextDiff && diffResult.onlyWhitespaceChanged

                if (whitespaceMode.ignoresWhitespace && diffResult is DiffResult.TextDiff && canIgnoreWhitespace(diffType)) {
                    WhitespaceIgnoredStrip(
                        mode = whitespaceMode,
                        actionsOff = whitespaceActionsOffText(diffType),
                        onShowThem = { viewModel.changeDiffWhitespace(DiffWhitespaceMode.SHOW_ALL) },
                    )
                }

                // Fork-only: a file with conflicts gets a strip with the merge tool, ours and theirs, and what each is
                if (diffType is DiffType.UncommittedDiff && diffType.statusEntry.conflict != null) {
                    val entry = diffType.statusEntry

                    ConflictBar(
                        statusEntry = entry,
                        sides = conflictSides,
                        onOpenInExternalMergeTool = { viewModel.onAction(StatusAction.OpenInExternalMergeTool(entry)) },
                        onResolve = { viewModel.onAction(StatusAction.OpenInResolver(entry)) },
                        onUseSide = { side ->
                            val action = ConfirmableAction.UseConflictSide(listOf(entry), side, conflictSides)
                            val resolve = { viewModel.onAction(StatusAction.UseConflictSide(listOf(entry.filePath), side)) }

                            onConfirmAction(action, resolve) {}
                        },
                    )
                }

                // Fork-only: a comparison names its sides, A on the left or removed, B on the right or added
                if (sideLabels != null && diffResult is DiffResult.TextDiff) {
                    sideLabels(diffResult is DiffResult.TextSplit)
                }

                val scrollState by viewModel.lazyListState.collectAsState()
                // Fork-only: a rename without changes has no hunks, which left the pane blank
                // (and for a diff that hides whitespace changes, it is the whitespace that changed)
                val renamedWithoutChanges = if (onlyWhitespaceChanged) null else diffResult.renamedWithoutChanges()
                // Fork-only: lines that don't wrap scroll sideways together, back to their start in another file
                val sideways = remember(wrapLines, diffType.filePath) { if (wrapLines) null else SidewaysScroll() }

                if (onlyWhitespaceChanged) {
                    OnlyWhitespaceChanged(onShowChanges = { viewModel.changeDiffWhitespace(DiffWhitespaceMode.SHOW_ALL) })
                } else if (renamedWithoutChanges != null) {
                    RenamedWithoutChanges(renamedWithoutChanges)
                } else when (diffResult) {
                    is DiffResult.TextSplit -> DiffLines(sideways, diffResult, lineTextStart(diffType)) {
                        HunkSplitTextDiff(
                            diffType = diffType,
                            scrollState = scrollState,
                            diffResult = diffResult,
                            canUseHunkActions = isRepositoryInSafeState,
                            onUnstageHunk = { entry, hunk ->
                                viewModel.unstageHunk(entry, hunk)
                            },
                            onStageHunk = { entry, hunk ->
                                viewModel.stageHunk(entry, hunk)
                            },
                            onResetHunk = ::discardHunk,
                            onUnStageLine = { entry, hunk, line ->
                                if (diffType is DiffType.UncommittedDiff) {
                                    if (diffType.entryType == EntryType.STAGED)
                                        viewModel.unstageHunkLine(entry, hunk, line)
                                    else {
                                        viewModel.stageHunkLine(entry, hunk, line)
                                    }
                                }
                            },
                            onDiscardLine = ::discardLine,
                        )
                    }

                    is DiffResult.Text -> DiffLines(sideways, diffResult, lineTextStart(diffType)) {
                        HunkUnifiedTextDiff(
                            diffType = diffType,
                            scrollState = scrollState,
                            diffResult = diffResult,
                            canUseHunkActions = isRepositoryInSafeState,
                            onUnstageHunk = { entry, hunk ->
                                viewModel.unstageHunk(entry, hunk)
                            },
                            onStageHunk = { entry, hunk ->
                                viewModel.stageHunk(entry, hunk)
                            },
                            onResetHunk = ::discardHunk,
                            onUnStageLine = { entry, hunk, line ->
                                if (diffType is DiffType.UncommittedDiff) {
                                    if (diffType.entryType == EntryType.STAGED)
                                        viewModel.unstageHunkLine(entry, hunk, line)
                                    else {
                                        viewModel.stageHunkLine(entry, hunk, line)
                                    }
                                }
                            },
                            onDiscardLine = ::discardLine,
                        )
                    }

                    is DiffResult.NonText -> {
                        NonTextDiff(
                            diffResult,
                            onOpenFileWithExternalApp = { path -> viewModel.openFileWithExternalApp(path) })
                    }

                    is DiffResult.Submodule -> {
                        SubmoduleDiff(
                            diffResult,
                            onOpenSubmodule = { viewModel.openSubmodule(diffResult.diffEntry.filePath) }
                        )
                    }
                }
            }

            // Fork-only: says why, rather than closing
            is ViewDiffResult.Failed -> {
                Column {
                    PathOnlyDiffHeader(
                        filePath = viewDiffResult.diffType?.filePath.orEmpty(),
                        onCloseDiffView = { closeDiffView() })
                    DiffLoadFailure(viewDiffResult.error, onTryAgain = { viewModel.retryDiff() })
                }
            }

            is ViewDiffResult.Loading -> {
                Column {
                    PathOnlyDiffHeader(
                        filePath = viewDiffResult.diffType.filePath,
                        onCloseDiffView = { closeDiffView() })
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colors.primaryVariant
                    )
                }

            }

            ViewDiffResult.None -> {
                Column {
                    PathOnlyDiffHeader(filePath = "", onCloseDiffView = { closeDiffView() })
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colors.primaryVariant
                    )
                }
            }
        }
    }
}

@Composable
fun NonTextDiff(diffResult: DiffResult.NonText, onOpenFileWithExternalApp: (String) -> Unit) {
    val oldBinaryContent = diffResult.oldBinaryContent
    val newBinaryContent = diffResult.newBinaryContent

    val showOldAndNew = oldBinaryContent != EntryContent.Missing && newBinaryContent != EntryContent.Missing

    Row(
        modifier = Modifier
            .fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {

        if (showOldAndNew) {
            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .padding(start = 24.dp, end = 8.dp, top = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SideTitle("Old")
                SideDiff(oldBinaryContent, onOpenFileWithExternalApp)
            }
            Column(
                modifier = Modifier
                    .weight(0.5f)
                    .padding(start = 8.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SideTitle("New")
                SideDiff(newBinaryContent, onOpenFileWithExternalApp)
            }
        } else if (oldBinaryContent != EntryContent.Missing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = 24.dp),
            ) {
                SideDiff(oldBinaryContent, onOpenFileWithExternalApp)
            }
        } else if (newBinaryContent != EntryContent.Missing) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(all = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                SideDiff(newBinaryContent, onOpenFileWithExternalApp)
            }
        }
    }
}

@Composable
fun SubmoduleDiff(diffResult: DiffResult.Submodule, onOpenSubmodule: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Submodule ${diffResult.diffEntry.filePath}",
            style = MaterialTheme.typography.h4,
            modifier = Modifier.padding(bottom = 8.dp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onBackground,
        )

        SelectionContainer {
            Column {
                Text(
                    AnnotatedString(
                        "Old ID: ",
                        SpanStyle(fontWeight = FontWeight.SemiBold)
                    ) + AnnotatedString(diffResult.diffEntry.oldId.name()),
                    color = MaterialTheme.colors.onBackground,
                )
                Text(
                    AnnotatedString(
                        "New ID: ",
                        SpanStyle(fontWeight = FontWeight.SemiBold)
                    ) + AnnotatedString(diffResult.diffEntry.newId.name()),
                    color = MaterialTheme.colors.onBackground,
                )
            }
        }

        val submoduleStatus = diffResult.submodule

        if (
            submoduleStatus != null &&
            listOf(
                SubmoduleState.INITIALIZED,
                SubmoduleState.REV_CHECKED_OUT
            ).contains(submoduleStatus.state) &&
            submoduleStatus.indexId == diffResult.diffEntry.newId?.toObjectId()?.name
        ) {
            PrimaryButton(
                modifier = Modifier.padding(top = 8.dp),
                text = "Open submodule",
                onClick = onOpenSubmodule,
            )
        }
    }

}

@Composable
fun SideTitle(text: String) {
    Text(
        text = text,
        fontSize = 20.sp,
        color = MaterialTheme.colors.onBackground,
    )
}

@Composable
fun SideDiff(entryContent: EntryContent, onOpenFileWithExternalApp: (String) -> Unit) {
    when (entryContent) {
        EntryContent.Binary -> BinaryDiff()
        is EntryContent.ImageBinary -> ImageDiff(
            entryContent.imagePath,
            entryContent.contentType,
            onOpenFileWithExternalApp = { onOpenFileWithExternalApp(entryContent.imagePath) }
        )

        else -> {
        }
//        is EntryContent.Text -> //TODO maybe have a text view if the file was a binary before?
// TODO Show some info about this       EntryContent.TooLargeEntry -> TODO()
    }
}

@Composable
private fun ImageDiff(
    imagePath: String,
    contentType: String,
    onOpenFileWithExternalApp: () -> Unit,
) {
    if (animatedImages.contains(contentType)) {
        AnimatedImage(imagePath, onOpenFileWithExternalApp)
    } else {
        StaticImage(imagePath, onOpenFileWithExternalApp)
    }
}

@Composable
private fun StaticImage(
    tempImagePath: String,
    onOpenFileWithExternalApp: () -> Unit,
) {
    var image by remember(tempImagePath) { mutableStateOf<ImageBitmap?>(null) }
    var cantDecode by remember(tempImagePath) { mutableStateOf(false) }

    LaunchedEffect(tempImagePath) {
        try {
            image = withContext(Dispatchers.IO) {
                FileInputStream(tempImagePath).use { inputStream ->
                    inputStream.readAllBytes().decodeToImageBitmap()
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // Some files of an image type can't be decoded, such as HEIC, and the exception used to end the app
            printError(TAG, "Can't decode the image $tempImagePath", ex)
            cantDecode = true
        }
    }

    if (cantDecode) {
        BinaryDiff()
        return
    }

    Image(
        bitmap = image ?: ImageBitmap.Blank,
        contentDescription = null,
        modifier = Modifier
            .run {
                val safeImage = image
                if (safeImage == null)
                    fillMaxSize()
                else {
                    // At most its own size, as before, and scaled down to fit the pane, keeping its shape: a large
                    // image used to be cut off
                    sizeIn(maxWidth = safeImage.width.dp, maxHeight = safeImage.height.dp)
                        .aspectRatio(safeImage.width.toFloat() / safeImage.height.coerceAtLeast(1))
                }
            }
            .handMouseClickable {
                onOpenFileWithExternalApp()
            }
    )
}

@Composable
private fun AnimatedImage(
    imagePath: String,
    onOpenFileWithExternalApp: () -> Unit,
) {
    Image(
        bitmap = loadOrNull(imagePath) { loadAnimatedImage(imagePath) }?.animate() ?: ImageBitmap.Blank,
        contentDescription = null,
        modifier = Modifier.fillMaxSize()
            .handMouseClickable {
                onOpenFileWithExternalApp()
            }
    )
}

@Composable
fun BinaryDiff() {
    Image(
        painter = painterResource(Res.drawable.binary),
        contentDescription = null,
        modifier = Modifier.width(400.dp),
        colorFilter = ColorFilter.tint(MaterialTheme.colors.primary)
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HunkUnifiedTextDiff(
    diffType: DiffType,
    scrollState: LazyListState,
    diffResult: DiffResult.Text,
    canUseHunkActions: Boolean,
    onUnstageHunk: (DiffEntry, Hunk) -> Unit,
    onStageHunk: (DiffEntry, Hunk) -> Unit,
    onUnStageLine: (DiffEntry, Hunk, Line) -> Unit,
    onResetHunk: (DiffEntry, Hunk) -> Unit,
    onDiscardLine: (DiffEntry, Hunk, Line) -> Unit,
) {
    val hunks = diffResult.hunks
    var selectedText by remember { mutableStateOf(AnnotatedString("")) }

    CompositionLocalProvider(
        LocalTextContextMenu provides SelectionAwareTextContextMenu {
            selectedText = it
        }
    ) {
        SelectionContainer {
            ScrollableLazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                state = scrollState
            ) {
                for (hunk in hunks) {
                    item {
                        DisableSelection {
                            HunkHeader(
                                header = hunk.header,
                                diffType = diffType,
                                // Fork-only: a hunk of a diff that ignored whitespace can't be applied as a patch
                                canUseHunkActions = canUseHunkActions && !hunk.ignoresWhitespace,
                                onUnstageHunk = { onUnstageHunk(diffResult.diffEntry, hunk) },
                                onStageHunk = { onStageHunk(diffResult.diffEntry, hunk) },
                                onResetHunk = { onResetHunk(diffResult.diffEntry, hunk) },
                            )
                        }
                    }

                    val oldHighestLineNumber = hunk.lines.maxOf { it.displayOldLineNumber }
                    val newHighestLineNumber = hunk.lines.maxOf { it.displayNewLineNumber }
                    val highestLineNumber = max(oldHighestLineNumber, newHighestLineNumber)
                    val highestLineNumberLength = highestLineNumber.toString().count()

                    items(hunk.lines) { line ->
                        // Fork-only: the line's buttons and its menu offer the same actions (DiffLineActions.kt)
                        val actions = lineActions(line, diffType, canUseHunkActions && !hunk.ignoresWhitespace)
                        val onLineAction = { action: LineAction ->
                            if (action == LineAction.DISCARD) {
                                onDiscardLine(diffResult.diffEntry, hunk, line)
                            } else {
                                onUnStageLine(diffResult.diffEntry, hunk, line)
                            }
                        }

                        DiffContextMenu(
                            selectedText = selectedText,
                            actions = actions,
                            onLineAction = onLineAction,
                        ) {
                            DiffLine(
                                highestLineNumberLength,
                                line,
                                diffType = diffType,
                                actions = actions,
                                onLineAction = onLineAction,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HunkSplitTextDiff(
    diffType: DiffType,
    scrollState: LazyListState,
    diffResult: DiffResult.TextSplit,
    canUseHunkActions: Boolean,
    onUnstageHunk: (DiffEntry, Hunk) -> Unit,
    onStageHunk: (DiffEntry, Hunk) -> Unit,
    onResetHunk: (DiffEntry, Hunk) -> Unit,
    onUnStageLine: (DiffEntry, Hunk, Line) -> Unit,
    onDiscardLine: (DiffEntry, Hunk, Line) -> Unit,
) {
    val hunks = diffResult.hunks

    /**
     * Disables selection in one side when the other is being selected
     */
    var selectableSide by remember { mutableStateOf(SelectableSide.BOTH) }

    var selectedText by remember { mutableStateOf(AnnotatedString("")) }

    CompositionLocalProvider(
        LocalTextContextMenu provides SelectionAwareTextContextMenu {
            selectedText = it
        }
    ) {
        SelectionContainer {
            ScrollableLazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                state = scrollState
            ) {
                for (splitHunk in hunks) {
                    item {
                        DisableSelection {
                            HunkHeader(
                                header = splitHunk.sourceHunk.header,
                                diffType = diffType,
                                canUseHunkActions = canUseHunkActions && !splitHunk.sourceHunk.ignoresWhitespace,
                                onUnstageHunk = { onUnstageHunk(diffResult.diffEntry, splitHunk.sourceHunk) },
                                onStageHunk = { onStageHunk(diffResult.diffEntry, splitHunk.sourceHunk) },
                                onResetHunk = { onResetHunk(diffResult.diffEntry, splitHunk.sourceHunk) },
                            )
                        }
                    }

                    val oldHighestLineNumber = splitHunk.sourceHunk.lines.maxOf { it.displayOldLineNumber }
                    val newHighestLineNumber = splitHunk.sourceHunk.lines.maxOf { it.displayNewLineNumber }
                    val highestLineNumber = max(oldHighestLineNumber, newHighestLineNumber)
                    val highestLineNumberLength = highestLineNumber.toString().count()

                    items(splitHunk.lines) { linesPair ->
                        SplitDiffLine(
                            highestLineNumberLength = highestLineNumberLength,
                            oldLine = linesPair.first,
                            newLine = linesPair.second,
                            selectableSide = selectableSide,
                            diffType = diffType,
                            selectedText = selectedText,
                            canUseLineActions = canUseHunkActions && !splitHunk.sourceHunk.ignoresWhitespace,
                            onLineAction = { line, action ->
                                if (action == LineAction.DISCARD) {
                                    onDiscardLine(diffResult.diffEntry, splitHunk.sourceHunk, line)
                                } else {
                                    onUnStageLine(diffResult.diffEntry, splitHunk.sourceHunk, line)
                                }
                            },
                            onChangeSelectableSide = { newSelectableSide ->
                                if (newSelectableSide != selectableSide) {
                                    selectableSide = newSelectableSide
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DynamicSelectionDisable(isDisabled: Boolean, content: @Composable () -> Unit) {
    if (isDisabled) {
        DisableSelection(content)
    } else
        content()
}

@Composable
fun SplitDiffLine(
    highestLineNumberLength: Int,
    oldLine: Line?,
    newLine: Line?,
    selectableSide: SelectableSide,
    diffType: DiffType,
    selectedText: AnnotatedString,
    canUseLineActions: Boolean,
    onChangeSelectableSide: (SelectableSide) -> Unit,
    onLineAction: (Line, LineAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colors.secondarySurface)
            .height(IntrinsicSize.Min)
    ) {
        SplitDiffLineSide(
            modifier = Modifier
                .weight(1f),
            highestLineNumberLength = highestLineNumberLength,
            line = oldLine,
            displayLineNumber = oldLine?.displayOldLineNumber ?: 0,
            currentSelectableSide = selectableSide,
            lineSelectableSide = SelectableSide.OLD,
            onChangeSelectableSide = onChangeSelectableSide,
            diffType = diffType,
            canUseLineActions = canUseLineActions,
            onLineAction = onLineAction,
            selectedText = selectedText,
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .background(MaterialTheme.colors.secondarySurface)
        )

        SplitDiffLineSide(
            modifier = Modifier
                .weight(1f),
            highestLineNumberLength = highestLineNumberLength,
            line = newLine,
            displayLineNumber = newLine?.displayNewLineNumber ?: 0,
            currentSelectableSide = selectableSide,
            lineSelectableSide = SelectableSide.NEW,
            onChangeSelectableSide = onChangeSelectableSide,
            diffType = diffType,
            canUseLineActions = canUseLineActions,
            onLineAction = onLineAction,
            selectedText = selectedText,
        )

    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SplitDiffLineSide(
    modifier: Modifier,
    highestLineNumberLength: Int,
    line: Line?,
    displayLineNumber: Int,
    currentSelectableSide: SelectableSide,
    lineSelectableSide: SelectableSide,
    diffType: DiffType,
    selectedText: AnnotatedString,
    canUseLineActions: Boolean,
    onChangeSelectableSide: (SelectableSide) -> Unit,
    onLineAction: (Line, LineAction) -> Unit,
) {
    var pressedAndMoved by remember(line) { mutableStateOf(Pair(false, false)) }
    var movesCount by remember(line) { mutableStateOf(0) }

    Box(
        modifier = modifier
            .onPointerEvent(PointerEventType.Press) {
                movesCount = 0
                pressedAndMoved = pressedAndMoved.copy(first = true, second = false)
                onChangeSelectableSide(SelectableSide.BOTH)

            }
            .onPointerEvent(PointerEventType.Release) {
                pressedAndMoved = pressedAndMoved.copy(first = false)

                // When using DynamicDisableSelection, there is a bug in compose where ctrl+C copies different stuff
                // than using the contextual menu Copy.
                // Ctrl+c copies everything that is not currently contained in the DisableSelection block,
                // even if it was during text selection. The context menu only copies what is currently selected.
                //
                // With this workaround, both sides are enabled if the mouse hasn't been moved (or not enough
                // to select something)
                if (movesCount < MAX_MOVES_COUNT)
                    onChangeSelectableSide(SelectableSide.BOTH)
            }
            .onPointerEvent(PointerEventType.Move) {
                movesCount++

                if (pressedAndMoved.first)
                    onChangeSelectableSide(lineSelectableSide)
            }
    ) {
        if (line != null) {
            val actions = lineActions(line, diffType, canUseLineActions)

            // To avoid both sides being selected, disable one side when the use is interacting with the other
            DynamicSelectionDisable(
                currentSelectableSide != lineSelectableSide &&
                        currentSelectableSide != SelectableSide.BOTH
            ) {
                DiffContextMenu(
                    selectedText,
                    actions = actions,
                    onLineAction = { onLineAction(line, it) },
                ) {
                    SplitDiffLine(
                        highestLineNumberLength = highestLineNumberLength,
                        line = line,
                        lineNumber = displayLineNumber,
                        diffType = diffType,
                        actions = actions,
                        onLineAction = { onLineAction(line, it) },
                        isOldSide = lineSelectableSide == SelectableSide.OLD,
                    )
                }
            }
        }
    }
}

@Composable
fun DiffContextMenu(
    selectedText: AnnotatedString,
    actions: List<LineAction>,
    onLineAction: (LineAction) -> Unit,
    content: @Composable () -> Unit,
) {
    ContextMenu(
        enabled = selectedText.isEmpty(),
        items = { lineActionMenuItems(actions, onLineAction) },
    ) {
        content()
    }
}

enum class SelectableSide {
    BOTH,
    OLD,
    NEW;
}

@Composable
fun HunkHeader(
    header: String,
    diffType: DiffType,
    canUseHunkActions: Boolean,
    onUnstageHunk: () -> Unit,
    onStageHunk: () -> Unit,
    onResetHunk: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colors.secondarySurface)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = header,
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.body1,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Hunks options are only visible when repository is a normal state (not during merge/rebase)
        if (
            (diffType is DiffType.UncommittedDiff && canUseHunkActions) &&
            diffType.statusType == StatusType.MODIFIED
        ) {
            val buttonText: String
            val colors: RoleColors
            if (diffType.entryType == EntryType.STAGED) {
                buttonText = "Unstage hunk"
                colors = MaterialTheme.styleColors.neutral
            } else {
                buttonText = "Stage hunk"
                colors = MaterialTheme.styleColors.action
            }

            if (diffType.entryType == EntryType.UNSTAGED) {
                SecondaryButton(
                    text = "Discard hunk",
                    colors = MaterialTheme.styleColors.destructive,
                    onClick = onResetHunk,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SecondaryButton(
                text = buttonText,
                colors = colors,
                modifier = Modifier.padding(horizontal = 16.dp),
                onClick = {
                    if (diffType.entryType == EntryType.STAGED) {
                        onUnstageHunk()
                    } else {
                        onStageHunk()
                    }
                }
            )
        }
    }
}

@Composable
private fun DiffHeader(
    diffType: DiffType,
    diffEntry: DiffEntry,
    textDiffViewType: DiffTextViewType,
    isDisplayFullFile: Boolean,
    isTextDiff: Boolean,
    /** Null leaves the close button out (fork-only). */
    onCloseDiffView: (() -> Unit)?,
    /** Buttons that come before the view's (fork-only): a comparison's files. */
    headerActions: (@Composable () -> Unit)?,
    onStageFile: (StatusEntry) -> Unit,
    onUnstageFile: (StatusEntry) -> Unit,
    onChangeDiffType: (DiffTextViewType) -> Unit,
    onDisplayFullFile: (Boolean) -> Unit,
    /** The View menu (fork-only), for any text diff: added and deleted files' lines can be long too. */
    viewMenu: @Composable () -> Unit,
    logToggle: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fileName = diffEntry.fileName
        val dirPath: String = diffEntry.parentDirectoryPath

        Box(
            modifier = Modifier
                .weight(1f, true)
        ) {
            SelectionContainer {
                Row {
                    if (dirPath.isNotEmpty()) {
                        Text(
                            text = dirPath.removeSuffix("/"),
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .weight(1f, false),
                        )

                        Text(
                            text = "/",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.body2,
                            overflow = TextOverflow.Visible,
                            color = MaterialTheme.colors.onBackgroundSecondary,
                        )
                    }
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onBackground,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 16.dp),
                    )

                    // Fork-only: where a renamed file came from
                    diffEntry.renamedFrom?.let { RenamedFromHeader(it) }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            headerActions?.invoke()

            if (diffType.statusType != StatusType.ADDED && diffType.statusType != StatusType.REMOVED && isTextDiff) {
                DiffTypeButtons(
                    diffType = textDiffViewType,
                    isDisplayFullFile = isDisplayFullFile,
                    onChangeDiffType = onChangeDiffType,
                    onDisplayFullFile = onDisplayFullFile,
                )
            }

            if (isTextDiff) {
                viewMenu()
            }

            if (diffType is DiffType.UncommittedDiff) {
                UncommittedDiffFileHeaderButtons(
                    diffType,
                    onUnstageFile = onUnstageFile,
                    onStageFile = onStageFile
                )
            }

            logToggle?.invoke()

            if (onCloseDiffView != null) {
                IconButton(
                    onClick = onCloseDiffView,
                    modifier = Modifier
                        .handOnHover()
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.close),
                        contentDescription = "Close diff",
                        tint = MaterialTheme.colors.onBackground,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun StateIcon(
    icon: DrawableResource,
    tooltip: String,
    isToggled: Boolean,
    onClick: () -> Unit,
) {
    DelayedTooltip(tooltip) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .run {
                    if (isToggled)
                        this.background(MaterialTheme.colors.onSurface.copy(alpha = 0.2f))
                    else
                        this
                }
                .handMouseClickable { if (!isToggled) onClick() }
                .padding(4.dp)
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface,
                modifier = Modifier
                    .size(24.dp),
            )
        }
    }
}

@Composable
fun DiffTypeButtons(
    diffType: DiffTextViewType,
    isDisplayFullFile: Boolean,
    onChangeDiffType: (DiffTextViewType) -> Unit,
    onDisplayFullFile: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 16.dp)
        ) {
            StateIcon(
                icon = Res.drawable.horizontal_split,
                tooltip = "Divide by hunks",
                isToggled = !isDisplayFullFile,
                onClick = { onDisplayFullFile(false) },
            )

            StateIcon(
                icon = Res.drawable.description,
                tooltip = "View the complete file",
                isToggled = isDisplayFullFile,
                onClick = { onDisplayFullFile(true) },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateIcon(
                icon = Res.drawable.unified,
                tooltip = "Unified diff",
                isToggled = diffType == DiffTextViewType.Unified,
                onClick = { onChangeDiffType(DiffTextViewType.Unified) },
            )

            StateIcon(
                icon = Res.drawable.vertical_split,
                tooltip = "Split diff",
                isToggled = diffType == DiffTextViewType.Split,
                onClick = { onChangeDiffType(DiffTextViewType.Split) },
            )
        }
    }
}

@Composable
fun UncommittedDiffFileHeaderButtons(
    diffType: DiffType.UncommittedDiff,
    onUnstageFile: (StatusEntry) -> Unit,
    onStageFile: (StatusEntry) -> Unit,
) {
    val buttonText: String
    val colors: RoleColors

    if (diffType.entryType == EntryType.STAGED) {
        buttonText = "Unstage file"
        colors = MaterialTheme.styleColors.neutral
    } else {
        buttonText = "Stage file"
        colors = MaterialTheme.styleColors.action
    }

    SecondaryButton(
        text = buttonText,
        colors = colors,
        onClick = {
            if (diffType.entryType == EntryType.STAGED) {
                onUnstageFile(diffType.statusEntry)
            } else {
                onStageFile(diffType.statusEntry)
            }
        }
    )
}

@Composable
private fun PathOnlyDiffHeader(
    filePath: String,
    onCloseDiffView: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = filePath,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onCloseDiffView,
            modifier = Modifier
                .handOnHover()
        ) {
            Image(
                painter = painterResource(Res.drawable.close),
                contentDescription = "Close diff",
                colorFilter = ColorFilter.tint(MaterialTheme.colors.onBackground),
            )
        }
    }
}

@Composable
fun DiffLine(
    highestLineNumberLength: Int,
    line: Line,
    diffType: DiffType,
    actions: List<LineAction>,
    onLineAction: (LineAction) -> Unit,
) {
    val backgroundColor = when (line.lineType) {
        LineType.ADDED -> MaterialTheme.colors.diffLineAdded
        LineType.REMOVED -> MaterialTheme.colors.diffLineRemoved
        LineType.CONTEXT -> MaterialTheme.colors.background
    }

    Row(
        modifier = Modifier
            .background(backgroundColor)
            .height(IntrinsicSize.Min),
    ) {
        val oldLineText = if (line.lineType == LineType.REMOVED || line.lineType == LineType.CONTEXT) {
            line.displayOldLineNumber.toStringWithSpaces(highestLineNumberLength)
        } else
            emptyLineNumber(highestLineNumberLength)

        val newLineText = if (line.lineType == LineType.ADDED || line.lineType == LineType.CONTEXT) {
            line.displayNewLineNumber.toStringWithSpaces(highestLineNumberLength)
        } else
            emptyLineNumber(highestLineNumberLength)

        DisableSelection {
            LineNumber(
                text = oldLineText,
                remarked = line.lineType != LineType.CONTEXT,
            )

            LineNumber(
                text = newLineText,
                remarked = line.lineType != LineType.CONTEXT,
            )
        }

        DiffLineText(line, diffType, actions, onLineAction)
    }
}

@Composable
fun SplitDiffLine(
    highestLineNumberLength: Int,
    line: Line,
    lineNumber: Int,
    diffType: DiffType,
    actions: List<LineAction>,
    onLineAction: (LineAction) -> Unit,
    /** The line is on the old side, whose context lines take the old version's colours (fork-only). */
    isOldSide: Boolean = false,
) {
    val backgroundColor = when (line.lineType) {
        LineType.ADDED -> MaterialTheme.colors.diffLineAdded
        LineType.REMOVED -> MaterialTheme.colors.diffLineRemoved
        LineType.CONTEXT -> MaterialTheme.colors.background
    }
    Row(
        modifier = Modifier
            .background(backgroundColor)
            .fillMaxHeight(),
    ) {
        DisableSelection {
            LineNumber(
                text = lineNumber.toStringWithSpaces(highestLineNumberLength),
                remarked = line.lineType != LineType.CONTEXT,
            )
        }

        DiffLineText(line, diffType, actions, onLineAction, isOldSide)
    }
}


@Composable
fun DiffLineText(
    line: Line,
    diffType: DiffType,
    actions: List<LineAction>,
    onLineAction: (LineAction) -> Unit,
    isOldSide: Boolean = false,
) {
    // Fork-only: the colours of the line's version, once it's coloured (docs/fork/syntax-highlighting.md)
    val highlights = LocalDiffHighlights.current
    val spans = remember(line, highlights, isOldSide) { highlights?.spansOf(line, isOldSide).orEmpty() }

    val text = line.text
    val matchLine = line.textDiffed
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Box(modifier = Modifier.hoverable(hoverInteraction)) {
        // Fork-only: Unstaged also has Discard next to Stage, and neither shows while the hunks have no buttons
        if (isHovered && actions.isNotEmpty()) {
            LineActionButtons(actions, onLineAction)
        }

        val textStart = lineTextStart(diffType)
        val sideways = LocalSidewaysScroll.current

        // Fork-only: on one row that scrolls sideways while the lines don't wrap, after the buttons' room
        if (sideways == null) {
            DiffText(text, matchLine, spans, startPadding = textStart)
        } else {
            UnwrappedLine(sideways, start = textStart) {
                DiffText(text, matchLine, spans, startPadding = textStart)
            }
        }
    }
}

/**
 * [spans]: [text]'s syntax colours, in its own offsets (fork-only), left out where [matchLine] isn't cut from [text].
 * [insertedColor]: the background of [matchLine]'s inserted text, the diff pane's green unless given (fork-only).
 * [startPadding]: the room before the text, where the diff pane puts a line's buttons (fork-only).
 */
@Composable
fun DiffText(
    text: String,
    matchLine: MatchLine?,
    spans: List<SyntaxSpan> = emptyList(),
    insertedColor: Color = MaterialTheme.colors.diffContentAdded,
    startPadding: Dp = 16.dp,
) {
    val line = matchLine ?: MatchLine(listOf(DiffMatchPatch.Diff(DiffMatchPatch.Operation.EQUAL, text)))
    val fittingSpans = remember(text, matchLine, spans) { if (spansFit(text, matchLine)) spans else emptyList() }

    val diffContentRemoved = MaterialTheme.colors.diffContentRemoved
    val syntaxColors = MaterialTheme.syntaxColors
    val diffContentAdded = insertedColor
    val mutedColor = MaterialTheme.colors.onBackgroundSecondary

    val hiddenCharacters = remember(line) { (line.shownText().length - MAX_DIFF_LINE_CHARACTERS).coerceAtLeast(0) }
    val hiddenCharactersText = if (hiddenCharacters > 0) {
        pluralStringResource(
            Res.plurals.diff_line_more_characters,
            hiddenCharacters,
            NumberFormat.getIntegerInstance().format(hiddenCharacters),
        )
    } else {
        null
    }

    val annotatedString = remember(line, fittingSpans, syntaxColors, hiddenCharactersText, diffContentAdded) {
        formatDiff(
            line = line,
            spans = fittingSpans,
            syntaxColors = syntaxColors,
            contentAddedColor = diffContentAdded,
            contentRemovedColor = diffContentRemoved,
            hiddenCharactersText = hiddenCharactersText,
            mutedColor = mutedColor,
        )
    }

    // Fork-only: marks of the spaces, tabs and line ending, drawn over the text while the View menu asks for them
    val whitespace = rememberWhitespaceOverlay(line.diffs.map { it.text })

    Text(
        text = annotatedString,
        modifier = Modifier
            .padding(start = startPadding)
            .fillMaxWidth()
            .then(whitespace?.modifier ?: Modifier),
        fontFamily = monoTypography(),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.onBackground,
        overflow = TextOverflow.Visible,
        softWrap = true,
        onTextLayout = whitespace?.onTextLayout ?: {},
    )
}

@Composable
fun LineNumber(text: String, remarked: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .padding(start = 8.dp, end = 4.dp),
        fontFamily = monoTypography(),
        style = MaterialTheme.typography.body2,
        color = if (remarked) MaterialTheme.colors.onBackground else MaterialTheme.colors.onBackgroundSecondary,
    )
}

fun emptyLineNumber(charactersCount: Int): String {
    val numberBuilder = StringBuilder()
    // Add whitespaces before the numbers
    repeat(charactersCount) {
        numberBuilder.append(" ")
    }

    return numberBuilder.toString()
}

/** The text of [this] line as the diff shows it, before it's cut at [MAX_DIFF_LINE_CHARACTERS]. */
private fun MatchLine.shownText(): String =
    diffs.joinToString("") { it.text.replaceTabs().removeLineDelimiters() }

/**
 * The line as the diff draws it, coloured with [spans] (fork-only), in the offsets of the line's own text. Past
 * [MAX_DIFF_LINE_CHARACTERS] it ends with [hiddenCharactersText] in [mutedColor], and only its first
 * [MAX_HIGHLIGHTED_LINE_CHARACTERS] are coloured.
 */
fun formatDiff(
    line: MatchLine,
    spans: List<SyntaxSpan>,
    syntaxColors: SyntaxColors,
    contentAddedColor: Color,
    contentRemovedColor: Color,
    hiddenCharactersText: String? = null,
    mutedColor: Color = Color.Gray,
): AnnotatedString {
    val isAllSameType = line.diffs
        .filter { it.text != "\n" }
        .map { it.operation }
        .count() == 1

    val diffBuilder = AnnotatedString.Builder()
    val diffs = line.diffs

    diffs
        .forEach { diff ->
            val color = if (isAllSameType) {
                Color.Transparent
            } else {
                when (diff.operation) {
                    DiffMatchPatch.Operation.DELETE -> contentRemovedColor
                    DiffMatchPatch.Operation.INSERT -> contentAddedColor
                    else -> Color.Transparent
                }
            }

            val newAnnotatedString = AnnotatedString(
                text = diff.text
                    .replaceTabs()
                    .removeLineDelimiters(),
                spanStyle = SpanStyle(background = color),
            )

            diffBuilder.append(newAnnotatedString)
        }

    val annotatedString = diffBuilder.toAnnotatedString()
    val shown = if (annotatedString.length > MAX_DIFF_LINE_CHARACTERS) {
        annotatedString.subSequence(0, MAX_DIFF_LINE_CHARACTERS)
    } else {
        annotatedString
    }
    val highlightedLength = minOf(shown.length, MAX_HIGHLIGHTED_LINE_CHARACTERS)

    return buildAnnotatedString {
        append(shown)
        addSyntaxStyles(spans, diffs.map { it.text }, highlightedLength, syntaxColors)

        if (shown.length < annotatedString.length && hiddenCharactersText != null) {
            withStyle(SpanStyle(color = mutedColor)) {
                append(" … $hiddenCharactersText")
            }
        }
    }
}