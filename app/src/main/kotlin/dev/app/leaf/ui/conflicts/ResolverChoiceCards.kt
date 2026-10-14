// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.conflicts.formatFileSize
import dev.app.leaf.domain.models.CommitRelation
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.KeptChanges
import dev.app.leaf.domain.models.PreviewImage
import dev.app.leaf.domain.models.PreviewLineType
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.imageType
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.theme.diffLineAdded
import dev.app.leaf.theme.diffLineRemoved
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.status.label
import dev.app.leaf.ui.status.named
import dev.app.leaf.ui.status.other
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.viewmodels.ChoicePick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource

/** The cards' row, at most this wide, so that a wide window doesn't stretch two images apart. */
private val MaxCardsWidth = 1100.dp

/** An image preview's height: it's scaled down to fit, keeping its shape. */
private val ImageHeight = 200.dp

/** A resolved file's cards, all but the button that opens the next file. */
private const val RESOLVED_ALPHA = 0.35f

/**
 * A conflict that three panes can't show (`conflict-resolver.md`, 2.4, step 5): what happened, then a card per side,
 * ours on the left and theirs on the right as in the Columns layout, with the base between them when [showBase]. A
 * click on a card, or ← and →, picks it ([ResolverActions.onPick]); the card picked has Mark resolved, and once the file
 * is resolved, in the same place, Next file. A file that each side renamed differently also has "Keep both names".
 */
@Composable
internal fun ChoiceCards(
    file: ConflictedFile,
    pick: ChoicePick?,
    showBase: Boolean,
    paused: Boolean,
    actions: ResolverActions,
) {
    val renamed = file.content as? ConflictContent.Renamed

    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = MaxCardsWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (paragraph in description(file)) {
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SideCard(file, ConflictSide.OURS, pick == ChoicePick.OURS, paused, actions, Modifier.weight(1f))

                if (showBase && showsBaseCard(file)) {
                    BaseCard(file, actions, Modifier.weight(0.8f))
                }

                SideCard(file, ConflictSide.THEIRS, pick == ChoicePick.THEIRS, paused, actions, Modifier.weight(1f))
            }

            if (renamed != null) {
                BothNamesCard(file, renamed, pick == ChoicePick.BOTH, paused, actions)
            }

            Notes(file)

            if (!paused) {
                Text(
                    text = stringResource(Res.string.conflict_resolver_choice_hint, acceptShortcut()),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackgroundSecondary,
                )
            }
        }
    }
}

/** Whether the toolbar's Base adds a card for the base: not where one side deleted the file, whose diff shows it. */
internal fun showsBaseCard(file: ConflictedFile): Boolean =
    file.preview?.base != null && file.content != ConflictContent.Deleted

/**
 * A renamed file whose content has conflicts, in an operation that doesn't tell which commit theirs is: picking a name
 * can't make it a conflict of its content, so Mark resolved waits until the markers are fixed.
 */
internal val ConflictedFile.renamedContentUnknown: Boolean
    get() = (content as? ConflictContent.Renamed)?.let { it.contentConflicts && it.theirsCommit == null } == true

/** What happened, in a paragraph or two. */
@Composable
private fun description(file: ConflictedFile): List<String> {
    val content = file.content
    val deleting = ConflictSide.entries.firstOrNull { file.conflict.version(it) == null }

    return when {
        content is ConflictContent.Renamed -> listOf(
            stringResource(
                Res.string.conflict_resolver_choice_renamed,
                ConflictSide.OURS.named(file.sides),
                content.file.original,
                content.file.ours,
                ConflictSide.THEIRS.named(file.sides),
                content.file.theirs,
            ),
            stringResource(
                when {
                    !content.contentConflicts -> Res.string.conflict_resolver_choice_renamed_merged
                    content.theirsCommit == null -> Res.string.conflict_resolver_renamed_content_unknown
                    else -> Res.string.conflict_resolver_choice_renamed_conflicts
                }
            ),
        )

        content == ConflictContent.OtherPaths -> {
            val present = ConflictSide.entries.firstOrNull { file.conflict.version(it) != null }

            listOf(
                stringResource(Res.string.conflict_resolver_choice_other_paths),
                if (present != null) {
                    stringResource(Res.string.conflict_resolver_choice_other_paths_side, present.named(file.sides))
                } else {
                    stringResource(Res.string.conflict_resolver_choice_other_paths_base)
                },
            )
        }

        // A submodule that one side deleted too
        deleting != null -> listOf(
            stringResource(
                Res.string.conflict_resolver_choice_deleted,
                deleting.named(file.sides),
                deleting.other.named(file.sides),
            )
        )

        else -> listOf(kindDescription(content))
    }
}

@Composable
private fun kindDescription(content: ConflictContent): String = when (content) {
    ConflictContent.Binary -> stringResource(Res.string.conflict_resolver_choice_binary)
    is ConflictContent.Lfs -> stringResource(Res.string.conflict_resolver_choice_lfs)
    ConflictContent.Submodule -> stringResource(Res.string.conflict_resolver_choice_submodule)
    is ConflictContent.SymbolicLink -> stringResource(Res.string.conflict_resolver_choice_link)
    is ConflictContent.TooLarge ->
        stringResource(Res.string.conflict_resolver_choice_too_large, formatFileSize(content.limit))

    is ConflictContent.Text, ConflictContent.OtherPaths, ConflictContent.Deleted, is ConflictContent.Renamed -> ""
}

/** A side's card: its version, and what picking it does once picked, or did once the file was resolved ([paused]). */
@Composable
private fun SideCard(
    file: ConflictedFile,
    side: ConflictSide,
    picked: Boolean,
    paused: Boolean,
    actions: ResolverActions,
    modifier: Modifier,
) {
    val color = side.color()
    val content = file.content
    val renamed = content as? ConflictContent.Renamed
    val version = file.preview?.let { if (side == ConflictSide.OURS) it.ours else it.theirs }
    val stage = if (side == ConflictSide.OURS) file.ours else file.theirs
    val image by decodedImage(version.image())
    // The index says what the side has; the preview may be missing, as it's read on a best-effort basis
    val absent = file.conflict.version(side) == null
    val named = side.named(file.sides)
    val pickLabel = when {
        renamed != null -> stringResource(Res.string.conflict_resolver_choice_name, renamed.file.path(side))
        content == ConflictContent.OtherPaths && absent ->
            stringResource(Res.string.conflict_resolver_choice_leave_out, named)

        content == ConflictContent.OtherPaths -> stringResource(Res.string.conflict_resolver_choice_keep_here, named)
        absent -> stringResource(Res.string.conflict_resolver_choice_delete, named)
        content == ConflictContent.Deleted -> stringResource(Res.string.conflict_resolver_choice_keep, named)
        else -> stringResource(Res.string.conflict_resolver_choice_use, named)
    }

    Card(
        border = if (picked) color else MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
        onClick = { actions.onPick(side) }.takeUnless { paused },
        modifier = modifier.fillMaxHeight().semantics { selected = picked },
    ) {
        Column(
            modifier = Modifier.alpha(if (paused) RESOLVED_ALPHA else 1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardHeader(
                title = sideTitle(side, file),
                tag = when {
                    renamed != null -> stringResource(Res.string.conflict_resolver_card_renamed)
                    absent && content == ConflictContent.OtherPaths -> ""
                    absent -> stringResource(Res.string.conflict_resolver_card_deleted)
                    version != null -> tag(file, version, image, kept = content == ConflictContent.Deleted)
                    else -> ""
                },
                dot = color,
                filled = picked,
            )

            if (renamed != null) {
                Mono(renamed.file.path(side))
            }

            when {
                absent && content == ConflictContent.OtherPaths ->
                    Muted(stringResource(Res.string.conflict_resolver_card_not_here, sideName(side, file)))

                absent -> Muted(stringResource(Res.string.conflict_resolver_card_gone, sideName(side, file)))
                version != null ->
                    VersionBody(file, version, stage, image, kept = content == ConflictContent.Deleted, actions)
            }
        }

        if (picked) {
            PickFooter(pickLabel, color, paused, canMarkResolved = !file.renamedContentUnknown, actions)
        }
    }
}

/** A renamed file's third choice, under the sides': both new names, with the same content. */
@Composable
private fun BothNamesCard(
    file: ConflictedFile,
    renamed: ConflictContent.Renamed,
    picked: Boolean,
    paused: Boolean,
    actions: ResolverActions,
) {
    val color = MaterialTheme.colors.onBackground
    val label = stringResource(Res.string.conflict_resolver_choice_both_names)

    Card(
        border = if (picked) color else MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
        onClick = actions.onPickBothNames.takeUnless { paused },
        modifier = Modifier.fillMaxWidth().semantics { selected = picked },
    ) {
        Column(
            modifier = Modifier.alpha(if (paused) RESOLVED_ALPHA else 1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardHeader(title = label, tag = "", dot = color, filled = picked)
            Muted(stringResource(Res.string.conflict_resolver_card_both_names, renamed.file.ours, renamed.file.theirs))
        }

        if (picked) {
            PickFooter(label, color, paused, canMarkResolved = !file.renamedContentUnknown, actions)
        }
    }
}

/**
 * What the card picked does: Mark resolved next to it, so that the pointer doesn't cross the window, and once the file
 * is resolved, in the same place, Next file.
 */
@Composable
private fun PickFooter(
    label: String,
    color: Color,
    paused: Boolean,
    canMarkResolved: Boolean,
    actions: ResolverActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            // The side's colour on the check alone: ours' blue is too dark for text on the card
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = color)) { append("✓ ") }
                append(label)
            },
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackground.copy(alpha = if (paused) RESOLVED_ALPHA else 1f),
            modifier = Modifier.weight(1f),
        )

        if (paused) {
            PrimaryButton(
                text = stringResource(Res.string.conflict_resolver_card_next_file, acceptShortcut()),
                onClick = actions.onNextFile,
            )
        } else {
            PrimaryButton(
                text = stringResource(Res.string.conflict_resolver_card_mark_resolved, acceptShortcut()),
                enabled = canMarkResolved,
                onClick = actions.onMarkResolved,
            )
        }
    }
}

/** The base's card, shown with the toolbar's Base: it can't be picked. */
@Composable
private fun BaseCard(file: ConflictedFile, actions: ResolverActions, modifier: Modifier) {
    val version = file.preview?.base ?: return
    val image by decodedImage(version.image())

    Card(
        border = MaterialTheme.colors.onBackground.copy(alpha = 0.1f),
        onClick = null,
        modifier = modifier.fillMaxHeight().alpha(0.85f),
    ) {
        CardHeader(
            title = stringResource(Res.string.conflict_resolver_card_base),
            tag = tag(file, version, image, kept = false),
            dot = null,
            filled = false,
        )

        (file.content as? ConflictContent.Renamed)?.let { Mono(it.file.original) }

        VersionBody(file, version, file.base, image, kept = false, actions)
    }
}

@Composable
private fun Card(
    border: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .run { if (onClick != null) handMouseClickable(onClick = onClick) else this }
            .background(MaterialTheme.colors.surface)
            .border(2.dp, border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The side's name and a radio dot in its colour, filled once picked, and on the right what the version is. */
@Composable
private fun CardHeader(title: String, tag: String, dot: Color?, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dot != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .run { if (filled) background(dot) else border(1.5.dp, dot, CircleShape) }
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = tag,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
        )
    }
}

/** What a version is, at the top right of its card: "Deleted", "Changed · +6 −2", "PNG · 512 × 512 · 48 KB"… */
@Composable
private fun tag(file: ConflictedFile, version: VersionPreview, image: DecodedImage, kept: Boolean): String {
    val kind = imageType(file.path).uppercase()

    return when (version) {
        is VersionPreview.File -> {
            val size = formatFileSize(version.size)
            val changes = file.preview?.kept

            when {
                kept && changes != null ->
                    stringResource(Res.string.conflict_resolver_card_changed, changes.added, changes.removed)

                version.image != null -> imageTag(kind, image, size)
                kept -> stringResource(Res.string.conflict_resolver_card_changed_size, size)
                version.lines != null -> stringResource(Res.string.conflict_resolver_card_text, size)
                file.content == ConflictContent.Binary -> stringResource(Res.string.conflict_resolver_card_binary, size)
                else -> stringResource(Res.string.conflict_resolver_card_file, size)
            }
        }

        is VersionPreview.Link -> stringResource(Res.string.conflict_resolver_card_link)
        is VersionPreview.Commit -> stringResource(Res.string.conflict_resolver_card_commit, version.commit.take(7))
        is VersionPreview.Lfs -> if (version.image != null) {
            imageTag(kind, image, formatFileSize(version.obj.size))
        } else {
            stringResource(Res.string.conflict_resolver_card_lfs, formatFileSize(version.obj.size))
        }
    }
}

@Composable
private fun imageTag(kind: String, image: DecodedImage, size: String): String =
    if (image is DecodedImage.Shown) {
        stringResource(Res.string.conflict_resolver_card_image_size, kind, image.bitmap.width, image.bitmap.height, size)
    } else {
        stringResource(Res.string.conflict_resolver_card_image, kind, size)
    }

/** A version's preview: the kept version's diff, an image, a link's target, a submodule's commit, an LFS object. */
@Composable
private fun VersionBody(
    file: ConflictedFile,
    version: VersionPreview,
    stage: ConflictStage?,
    image: DecodedImage,
    /** The version that a side kept, where the other deleted the file: its diff against the base. */
    kept: Boolean,
    actions: ResolverActions,
) {
    val changes = file.preview?.kept

    when (version) {
        is VersionPreview.File -> when {
            kept && changes != null -> DiffPreview(changes)
            version.image != null -> {
                ImagePreview(image)
                OpenLink(stage, actions)
            }

            version.lines != null -> Muted(stringResource(Res.string.conflict_resolver_card_lines, "%,d".format(version.lines)))
            file.content == ConflictContent.Binary || file.content == ConflictContent.OtherPaths || kept ->
                OpenLink(stage, actions)
        }

        is VersionPreview.Link -> {
            Text(
                text = "→ ${version.target}",
                style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography()),
                color = MaterialTheme.colors.onBackground,
            )

            if (version.resolves) {
                Muted(stringResource(Res.string.conflict_resolver_card_link_resolves))
            } else {
                Text(
                    text = stringResource(Res.string.conflict_resolver_card_link_missing),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.secondary,
                )
            }
        }

        is VersionPreview.Commit -> {
            val summary = version.summary

            if (summary != null) {
                Text(
                    text = summary.subject,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                )
                Muted(
                    stringResource(
                        Res.string.conflict_resolver_card_author,
                        summary.author,
                        (summary.timeMillis / 1000).toSmartSystemString(showTime = true),
                    )
                )
            } else {
                Muted(stringResource(Res.string.conflict_resolver_card_commit_missing))
            }
        }

        is VersionPreview.Lfs -> {
            Text(
                text = stringResource(Res.string.conflict_resolver_card_lfs_object, version.obj.oid.take(12)),
                style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography()),
                color = MaterialTheme.colors.onBackgroundSecondary,
            )

            when {
                !version.downloaded -> Muted(stringResource(Res.string.conflict_resolver_card_lfs_missing))
                version.image != null -> {
                    ImagePreview(image)
                    OpenLink(stage, actions)
                }

                else -> OpenLink(stage, actions)
            }
        }
    }
}

/** The kept version's diff against the base, as the diff pane colours it. */
@Composable
private fun DiffPreview(changes: KeptChanges) {
    val style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography())

    if (changes.lines.isEmpty()) {
        Muted(stringResource(Res.string.conflict_resolver_card_no_changes))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.background)
            .padding(vertical = 4.dp),
    ) {
        for (line in changes.lines) {
            val (prefix, background) = when (line.type) {
                PreviewLineType.HUNK -> "" to Color.Transparent
                PreviewLineType.CONTEXT -> "  " to Color.Transparent
                PreviewLineType.ADDED -> "+ " to MaterialTheme.colors.diffLineAdded
                PreviewLineType.REMOVED -> "- " to MaterialTheme.colors.diffLineRemoved
            }

            Text(
                text = prefix + line.text.replace("\t", "    "),
                style = style,
                color = if (line.type == PreviewLineType.HUNK) {
                    MaterialTheme.colors.onBackgroundSecondary
                } else {
                    MaterialTheme.colors.onBackground
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 6.dp),
            )
        }

        if (changes.more > 0) {
            Text(
                text = stringResource(Res.string.conflict_resolver_card_more_lines, changes.more),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ImagePreview(image: DecodedImage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ImageHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        when (image) {
            is DecodedImage.Shown -> Image(
                bitmap = image.bitmap,
                contentDescription = null,
                // Scaled down to fit, keeping its shape, but never up: a small icon stays small
                contentScale = ContentScale.Inside,
                modifier = Modifier.padding(8.dp).fillMaxSize(),
            )

            DecodedImage.Failed -> Muted(stringResource(Res.string.conflict_resolver_card_bad_image))
            DecodedImage.Loading, DecodedImage.None -> Unit
        }
    }
}

/** "Open in the default app", for a version that the cards can't show whole. */
@Composable
private fun OpenLink(stage: ConflictStage?, actions: ResolverActions) {
    if (stage == null) {
        return
    }

    Text(
        text = stringResource(Res.string.conflict_resolver_card_open),
        style = MaterialTheme.typography.body2,
        color = MaterialTheme.colors.primaryVariant,
        modifier = Modifier.handMouseClickable { actions.onOpenVersion(stage) },
    )
}

/** What the kind of conflict adds under the cards: an edited working file, how a submodule's commits relate. */
@Composable
private fun Notes(file: ConflictedFile) {
    val preview = file.preview ?: return

    if (file.content == ConflictContent.Deleted && preview.workingFileEdited) {
        Muted(stringResource(Res.string.conflict_resolver_card_working_file_edited))
    }

    val submodule = preview.submodule ?: return
    val ours = file.ours?.objectId?.take(7)
    val theirs = file.theirs?.objectId?.take(7)

    if (!submodule.initialized) {
        Muted(stringResource(Res.string.conflict_resolver_card_not_initialized))
        return
    }

    when (submodule.relation) {
        CommitRelation.OURS_CONTAINS_THEIRS ->
            Muted(stringResource(Res.string.conflict_resolver_card_ours_contains, ours.orEmpty(), theirs.orEmpty()))

        CommitRelation.THEIRS_CONTAINS_OURS ->
            Muted(stringResource(Res.string.conflict_resolver_card_ours_contains, theirs.orEmpty(), ours.orEmpty()))

        CommitRelation.DIVERGED -> Muted(stringResource(Res.string.conflict_resolver_card_diverged))
        null -> Unit
    }

    submodule.checkedOut?.let { commit ->
        Muted(stringResource(Res.string.conflict_resolver_card_checked_out, commit.take(7)))
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography()),
        color = MaterialTheme.colors.onBackground,
    )
}

@Composable
private fun Muted(text: String) {
    Text(text = text, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackgroundSecondary)
}

/** "Ours: main", as the columns' headers name the side. */
@Composable
private fun sideTitle(side: ConflictSide, file: ConflictedFile): String {
    val title = stringResource(
        if (side == ConflictSide.OURS) Res.string.conflict_resolver_ours else Res.string.conflict_resolver_theirs
    )

    return file.sides?.label(side)?.let { "$title: $it" } ?: title
}

/** "main", or "ours" when the side has no name. */
@Composable
private fun sideName(side: ConflictSide, file: ConflictedFile): String =
    file.sides?.label(side) ?: side.named(file.sides)

@Composable
private fun ConflictSide.color(): Color = when (this) {
    ConflictSide.OURS -> MaterialTheme.colors.primary
    ConflictSide.THEIRS -> TheirsColor
}

private fun VersionPreview?.image(): PreviewImage? = when (this) {
    is VersionPreview.File -> image
    is VersionPreview.Lfs -> image
    else -> null
}

/** An image preview, decoded off the UI thread. */
private sealed interface DecodedImage {
    data object None : DecodedImage
    data object Loading : DecodedImage
    data class Shown(val bitmap: ImageBitmap) : DecodedImage
    data object Failed : DecodedImage
}

@Composable
private fun decodedImage(image: PreviewImage?) = produceState<DecodedImage>(
    initialValue = if (image == null) DecodedImage.None else DecodedImage.Loading,
    image,
) {
    if (image == null) {
        value = DecodedImage.None
        return@produceState
    }

    value = withContext(Dispatchers.IO) {
        try {
            DecodedImage.Shown(image.bytes.decodeToImageBitmap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Some files of an image type can't be decoded, as the diff pane found
            DecodedImage.Failed
        }
    }
}
