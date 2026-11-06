// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.app.leaf.LocalTabFocusRequester
import dev.app.leaf.domain.models.SECTION_DIVIDER_HEIGHT
import dev.app.leaf.domain.models.STATUS_DEFAULT_COMMIT_FIELD_HEIGHT
import dev.app.leaf.domain.models.STATUS_DEFAULT_STAGED_SHARE
import dev.app.leaf.domain.models.STATUS_MIN_COMMIT_FIELD_HEIGHT
import dev.app.leaf.domain.models.StatusSection
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.ui.components.SectionDivider
import dev.app.leaf.ui.components.SectionFold
import kotlinx.coroutines.launch

/**
 * The status pane's three sections, Staged and Unstaged (in the order that [stagedOnTop] says) and the commit message,
 * with the handles that resize them, as [sizes] says (fork-only). Each section gets the modifier that sizes it and its
 * [SectionFold].
 *
 * The open sections share the pane, and a folded one is as tall as its header, and its buttons for the commit message.
 * Two open lists split their room as saved, and one open list takes all of it. With both lists folded, an open commit
 * message takes what their headers leave; with everything folded, the headers stack at the top. Only a handle with
 * something to resize on both sides shows.
 *
 * A drag changes the sizes here at once; [onSizesChanged] gets them when the drag ends, and a fold at once, and they
 * come back through [sizes].
 */
@Composable
internal fun StatusSections(
    sizes: StatusSectionSizes,
    stagedOnTop: Boolean,
    /** The message is in the centre pane: the lists get the field's room, and the saved sizes come back with it. */
    isMessageExpanded: Boolean,
    onSizesChanged: (StatusSectionSizes) -> Unit,
    staged: @Composable (Modifier, SectionFold) -> Unit,
    unstaged: @Composable (Modifier, SectionFold) -> Unit,
    commitMessage: @Composable (Modifier, SectionFold) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sectionSizes by remember { mutableStateOf(sizes) }
    // Whether the sections, or anything in them, have the focus
    var hasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tabFocusRequester = LocalTabFocusRequester.current

    LaunchedEffect(sizes) {
        sectionSizes = sizes
    }

    fun save(newSizes: StatusSectionSizes) {
        sectionSizes = newSizes
        onSizesChanged(newSizes)
    }

    val folds = sectionSizes.folds

    // Folding a section removes what in it had the focus, and the tab's shortcuts need something in the tab to have it.
    // So one frame after a fold, when the focus is nowhere in the sections any more, the tab takes it.
    fun foldOf(section: StatusSection) = SectionFold(isFolded = folds.isFolded(section)) {
        val hadFocus = hasFocus

        save(sectionSizes.withFoldToggled(section))

        if (hadFocus) {
            scope.launch {
                withFrameNanos { }

                if (!hasFocus) {
                    try {
                        tabFocusRequester.requestFocus()
                    } catch (_: IllegalStateException) {
                        // The tab is gone
                    }
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.onFocusChanged { hasFocus = it.hasFocus },
    ) {
        val paneHeight = maxHeight.value
        val shownSizes = if (isMessageExpanded) {
            sectionSizes.copy(commitFieldHeight = STATUS_MIN_COMMIT_FIELD_HEIGHT)
        } else {
            sectionSizes
        }
        val commitSection = shownSizes.commitSectionIn(paneHeight)
        val openLists = folds.openLists

        Column(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = if (openLists > 0) Modifier.weight(1f) else Modifier,
            ) {
                // What two open lists share, the handle between them left out
                val lists = maxHeight.value - SECTION_DIVIDER_HEIGHT
                val (stagedHeight, unstagedHeight) = shownSizes.splitLists(lists)

                Column(modifier = if (openLists > 0) Modifier.fillMaxSize() else Modifier) {
                    fun listModifier(isFolded: Boolean, height: Float) = when {
                        isFolded -> Modifier
                        openLists == 2 -> Modifier.height(height.dp)
                        else -> Modifier.weight(1f)
                    }

                    if (stagedOnTop) {
                        staged(listModifier(folds.staged, stagedHeight), foldOf(StatusSection.STAGED))
                    } else {
                        unstaged(listModifier(folds.unstaged, unstagedHeight), foldOf(StatusSection.UNSTAGED))
                    }

                    if (openLists == 2) {
                        SectionDivider(
                            onDrag = { delta ->
                                sectionSizes = sectionSizes.withListsDividerMovedIn(lists, delta, stagedOnTop)
                            },
                            onDragStopped = { save(sectionSizes) },
                            onDoubleClick = { save(sectionSizes.copy(stagedShare = STATUS_DEFAULT_STAGED_SHARE)) },
                        )
                    }

                    if (stagedOnTop) {
                        unstaged(listModifier(folds.unstaged, unstagedHeight), foldOf(StatusSection.UNSTAGED))
                    } else {
                        staged(listModifier(folds.staged, stagedHeight), foldOf(StatusSection.STAGED))
                    }
                }
            }

            if (commitSection != null) {
                SectionDivider(
                    onDrag = { delta ->
                        if (!isMessageExpanded) {
                            sectionSizes = sectionSizes.withCommitDividerMoved(paneHeight, delta, stagedOnTop)
                        }
                    },
                    onDragStopped = { if (!isMessageExpanded) save(sectionSizes) },
                    onDoubleClick = {
                        if (!isMessageExpanded) {
                            save(sectionSizes.copy(commitFieldHeight = STATUS_DEFAULT_COMMIT_FIELD_HEIGHT))
                        }
                    },
                )
            }

            commitMessage(
                when {
                    folds.commitMessage -> Modifier
                    // The title row comes on top of the saved field height
                    commitSection != null -> Modifier.height(commitSection.dp)
                    // Both lists folded: what their headers leave
                    else -> Modifier.weight(1f)
                },
                foldOf(StatusSection.COMMIT_MESSAGE),
            )
        }
    }
}
