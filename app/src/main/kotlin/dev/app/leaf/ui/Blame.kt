@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.domain.extensions.lineAt
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.toStringWithSpaces
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.syntaxColors
import dev.app.leaf.theme.headerBackground
import dev.app.leaf.theme.inCodeFont
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.ScrollableLazyColumn
import dev.app.leaf.ui.diff.colouredLine
import dev.app.leaf.ui.find.BlameFindText
import dev.app.leaf.ui.find.FindBar
import dev.app.leaf.ui.find.FindState
import dev.app.leaf.ui.find.FindTarget
import dev.app.leaf.ui.find.LineFindMarks
import dev.app.leaf.ui.find.RunFind
import dev.app.leaf.ui.find.selectableLines
import dev.app.leaf.ui.find.addFindStyles
import dev.app.leaf.ui.find.currentMatchOutline
import dev.app.leaf.ui.find.findColors
import dev.app.leaf.ui.find.findableView
import dev.app.leaf.ui.find.goToLine
import dev.app.leaf.ui.find.goToLineMark
import dev.app.leaf.ui.find.rememberRowFindMarks
import dev.app.leaf.ui.find.selectionBackground
import org.eclipse.jgit.blame.BlameResult
import org.jetbrains.compose.resources.painterResource
import dev.app.leaf.theme.withText

@Composable
fun Blame(
    filePath: String,
    blameResult: BlameResult,
    /** The commit a line comes from, by its id. */
    onSelectCommit: (hash: String) -> Unit,
    onClose: () -> Unit,
    /** The file's colours (fork-only), null while it's coloured or when nothing colours it. */
    highlighted: HighlightedText? = null,
) {
    val syntaxColors = MaterialTheme.syntaxColors
    val focusRequester = remember { FocusRequester() }
    // Fork-only: find, go to line and Select all (upstream issues 275 and 312)
    val find = remember { FindState() }
    val findText = remember(blameResult) { BlameFindText(blameResult.resultContents) }
    val listState = rememberLazyListState()
    val findMarks = rememberRowFindMarks(find)
    val findColors = MaterialTheme.findColors
    val selectionColor = selectionBackground

    FindTarget(find, isAvailable = true)
    RunFind(find, findText, listState, source = filePath)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .findableView(find)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.matchesBinding(KeybindingOption.EXIT) && keyEvent.type == KeyEventType.KeyDown) {
                    onClose()
                    true
                } else
                    false
            },
    ) {
        Header(filePath, onClose = onClose)

        if (find.isOpen) {
            FindBar(
                state = find,
                lineCount = findText.lineCount,
                onGoToLine = { line -> find.goToLine(line, findText, onShowCompleteFile = null) },
                focusAfterClose = focusRequester,
            )
        }

        SelectionContainer(modifier = Modifier.selectableLines(find, findText)) {
            ScrollableLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                val contents = blameResult.resultContents
                val contentsSize = contents.size()
                val contentSizeLengthInString = contentsSize.toString().count()

                items(contentsSize) { index ->
                    val line = contents.lineAt(index)
                    val author = blameResult.getSourceAuthor(index)
                    val commit = blameResult.getSourceCommit(index)

                    val lineMarks = findMarks?.marksOf(index) ?: LineFindMarks.NONE
                    val isGoToLine = findMarks?.goToRow == index
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colors.background)
                            .goToLineMark(isGoToLine)
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {

                        DisableSelection {
                            LineNumber(
                                line = index + 1,
                                highestLineLength = contentSizeLengthInString,
                                isBold = isGoToLine,
                            )

                            Column(
                                modifier = Modifier
                                    .width(200.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colors.secondarySurface)
                                    .clickable(enabled = commit != null) { commit?.let { onSelectCommit(it.name) } },
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = author?.name.orEmpty(),
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 16.dp),
                                    style = MaterialTheme.typography.body2,
                                )
                                Text(
                                    text = commit?.shortMessage ?: "Uncommitted change",
                                    style = MaterialTheme.typography.caption,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }

                        Text(
                            text = remember(line, highlighted, syntaxColors, lineMarks, findColors, selectionColor) {
                                buildAnnotatedString {
                                    append(colouredLine(line, highlighted?.spansOf(index).orEmpty(), syntaxColors))
                                    addFindStyles(lineMarks, length, findColors, selectionColor)
                                }
                            },
                            color = MaterialTheme.colors.onBackground,
                            modifier = Modifier
                                .padding(vertical = 8.dp, horizontal = 16.dp)
                                .currentMatchOutline(lineMarks.current, { layout }, findColors.outline),
                            // Fork-only: with the code font's ligatures as Settings has them
                            style = LocalTextStyle.current.inCodeFont(),
                            maxLines = 1,
                            onTextLayout = { if (lineMarks.current != null) layout = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineNumber(line: Int, highestLineLength: Int, isBold: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .background(MaterialTheme.colors.secondarySurface)
            .padding(start = 4.dp, end = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = line.toStringWithSpaces(highestLineLength),
            color = MaterialTheme.colors.onBackground,
            fontFamily = monoTypography(),
            fontWeight = if (isBold) FontWeight.Bold else null,
        )
    }
}

@Composable
fun MinimizedBlame(
    filePath: String,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp.withText(lines = 2))
            .background(MaterialTheme.colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Minimized file blame",
                style = MaterialTheme.typography.caption,
                maxLines = 1,
            )
            Text(
                text = filePath,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            onClick = onExpand,
            text = "Show",
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .handOnHover()
        ) {
            Image(
                painter = painterResource(Res.drawable.close),
                contentDescription = "Close blame",
                colorFilter = ColorFilter.tint(MaterialTheme.colors.onBackground),
            )
        }
    }
}

@Composable
private fun Header(
    filePath: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .headerBackground()
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = filePath,
            style = MaterialTheme.typography.body1,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .handOnHover()
        ) {
            Image(
                painter = painterResource(Res.drawable.close),
                contentDescription = "Close blame",
                colorFilter = ColorFilter.tint(MaterialTheme.colors.onBackground),
            )
        }
    }
}