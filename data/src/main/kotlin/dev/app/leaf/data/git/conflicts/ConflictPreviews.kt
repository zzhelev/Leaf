// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.common.printError
import dev.app.leaf.domain.models.CommitRelation
import dev.app.leaf.domain.models.CommitSummary
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictPreview
import dev.app.leaf.domain.models.KeptChanges
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.PreviewImage
import dev.app.leaf.domain.models.PreviewLine
import dev.app.leaf.domain.models.PreviewLineType
import dev.app.leaf.domain.models.SubmodulePreview
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.isPreviewImage
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.submodule.SubmoduleWalk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import kotlin.time.Duration

private const val TAG = "ConflictPreviews"

/** An image larger than this, on a side, shows as its size alone. */
internal const val MAX_PREVIEW_IMAGE_SIZE = 16L * 1024 * 1024

/** The lines of a kept version's diff that its card shows, at most. */
internal const val MAX_PREVIEW_LINES = 300

/** Lines kept around each change in that diff, as `git diff` keeps. */
private const val PREVIEW_CONTEXT = 3

/**
 * What the choice cards show of [path]'s conflict (`conflict-resolver.md`, 2.4, step 5), or null for text, which gets
 * no cards. A file renamed on both sides passes its three entries, from its three paths.
 */
internal fun conflictPreview(
    repository: Repository,
    reader: ObjectReader,
    path: String,
    content: ConflictContent,
    base: DirCacheEntry?,
    ours: DirCacheEntry?,
    theirs: DirCacheEntry?,
    maxCompareTime: Duration = MAX_RESOLVER_COMPARE_TIME,
): ConflictPreview? {
    val versions = VersionReader(
        repository = repository,
        reader = reader,
        path = path,
        images = content == ConflictContent.Binary || content is ConflictContent.Lfs ||
            content == ConflictContent.Deleted || content == ConflictContent.OtherPaths || content is ConflictContent.Renamed,
        countLines = content is ConflictContent.TooLarge,
    )

    return when (content) {
        is ConflictContent.Text -> null
        ConflictContent.Submodule -> submodulePreview(repository, reader, path, base, ours, theirs)
        ConflictContent.Deleted -> keptPreview(repository, reader, path, base, ours, theirs, versions, maxCompareTime)
        else -> ConflictPreview(versions.of(base), versions.of(ours), versions.of(theirs))
    }
}

/** Reads one version at a time, as its card shows it. A submodule's commit gets its summary from [commits]. */
private class VersionReader(
    val repository: Repository,
    val reader: ObjectReader,
    val path: String,
    /** Images of a type the cards show are read, up to [MAX_PREVIEW_IMAGE_SIZE]. */
    val images: Boolean,
    /** Lines are counted, for text too large to merge. */
    val countLines: Boolean,
    val commits: (ObjectId) -> CommitSummary? = { null },
) {
    private val image = images && isPreviewImage(path)

    fun of(entry: DirCacheEntry?): VersionPreview? = when (entry?.fileMode) {
        null -> null
        FileMode.GITLINK -> VersionPreview.Commit(entry.objectId.name, commits(entry.objectId))
        FileMode.SYMLINK -> link(reader.open(entry.objectId).bytes.decodeToString())
        else -> lfs(entry) ?: file(entry)
    }

    private fun link(target: String) = VersionPreview.Link(target, resolves = linkResolves(target))

    /** Whether a link at the path to [target] points to something, as the system follows it. */
    private fun linkResolves(target: String): Boolean = try {
        Files.exists(File(repository.workTree, path).toPath().resolveSibling(target))
    } catch (e: InvalidPathException) {
        false
    }

    private fun lfs(entry: DirCacheEntry): VersionPreview.Lfs? =
        reader.lfsPointer(entry)?.let { lfsVersion(repository, it, image) }

    private fun file(entry: DirCacheEntry): VersionPreview.File {
        val size = reader.getObjectSize(entry.objectId, Constants.OBJ_BLOB)

        return VersionPreview.File(
            size = size,
            lines = if (countLines) reader.lineCount(entry.objectId) else null,
            image = if (image && size <= MAX_PREVIEW_IMAGE_SIZE) {
                PreviewImage(reader.open(entry.objectId, Constants.OBJ_BLOB).bytes)
            } else {
                null
            },
        )
    }
}

/**
 * A Git LFS pointer to [obj], as its card shows it: whether the object is in the repository's LFS store with the
 * pointer's size, and its bytes when it's an [image] small enough to show.
 */
internal fun lfsVersion(repository: Repository, obj: LfsObject, image: Boolean): VersionPreview.Lfs {
    val media = Lfs(repository).getMediaFile(LongObjectId.fromString(obj.oid))
    val downloaded = Files.isRegularFile(media) && Files.size(media) == obj.size

    return VersionPreview.Lfs(
        obj = obj,
        downloaded = downloaded,
        image = if (downloaded && image && obj.size <= MAX_PREVIEW_IMAGE_SIZE) {
            PreviewImage(Files.readAllBytes(media))
        } else {
            null
        },
    )
}

/** The lines of a blob, as an editor counts them: a last line without a line ending counts too. */
private fun ObjectReader.lineCount(id: ObjectId): Long = open(id, Constants.OBJ_BLOB).openStream().use { input ->
    val buffer = ByteArray(64 * 1024)
    var lines = 0L
    var last: Byte = '\n'.code.toByte()

    while (true) {
        val read = input.read(buffer)

        if (read < 0) {
            break
        }

        for (i in 0 until read) {
            if (buffer[i] == '\n'.code.toByte()) {
                lines++
            }
        }

        if (read > 0) {
            last = buffer[read - 1]
        }
    }

    if (last != '\n'.code.toByte()) lines + 1 else lines
}

/**
 * A file that one side deleted: the versions, and what the kept one changes from the base. Keep stages the working
 * file, so the diff is the working file's when there is one (as `git add` would read it), and the kept side's version
 * otherwise. A `filter` attribute, such as Git LFS's, makes the working file something else than the blob: then the
 * blob's. A link or an LFS pointer kept gets no diff in its card.
 */
private fun keptPreview(
    repository: Repository,
    reader: ObjectReader,
    path: String,
    base: DirCacheEntry?,
    ours: DirCacheEntry?,
    theirs: DirCacheEntry?,
    versions: VersionReader,
    maxCompareTime: Duration,
): ConflictPreview {
    val preview = ConflictPreview(versions.of(base), versions.of(ours), versions.of(theirs))
    val kept = ours ?: theirs ?: return preview
    val attributes = conflictFileAttributes(repository, path)
    val file = File(repository.workTree, path).toPath()
    val working = if (!attributes.filtered &&
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
        Files.size(file) <= MAX_RESOLVER_TEXT_SIZE
    ) {
        checkedIn(Files.readAllBytes(file), attributes.checkinLineEndings)
    } else {
        null
    }
    val keptBytes = reader.bytesUpTo(kept, MAX_RESOLVER_TEXT_SIZE)
    val baseBytes = base?.let { reader.bytesUpTo(it, MAX_RESOLVER_TEXT_SIZE) }
    val text = working ?: keptBytes

    val changes = if (text != null && baseBytes != null && listOf(text, baseBytes).none { RawText.isBinary(it) }) {
        // Dense changes, as in a lock file, can take long to compare: the card does without them
        try {
            keptChanges(RawText(baseBytes), RawText(text), timeLimit = maxCompareTime)
        } catch (_: ComparisonTookTooLong) {
            null
        }
    } else {
        null
    }

    return preview.copy(
        kept = changes,
        workingFileEdited = working != null && keptBytes != null && !working.contentEquals(keptBytes),
    )
}

private fun ObjectReader.bytesUpTo(entry: DirCacheEntry, limit: Long): ByteArray? =
    if (getObjectSize(entry.objectId, Constants.OBJ_BLOB) <= limit) open(entry.objectId).bytes else null

/**
 * [kept]'s diff against [base], as `git diff` shows it: hunks with [PREVIEW_CONTEXT] lines around each change. Only the
 * first [maxLines] are kept. Throws [ComparisonTookTooLong] once the diff took longer than [timeLimit].
 */
internal fun keptChanges(
    base: RawText,
    kept: RawText,
    maxLines: Int = MAX_PREVIEW_LINES,
    timeLimit: Duration = Duration.INFINITE,
): KeptChanges {
    val edits = HistogramDiff().diff(comparatorWithin(timeLimit), base, kept)
    val lines = mutableListOf<PreviewLine>()
    var more = 0

    fun add(type: PreviewLineType, text: String) {
        if (lines.size < maxLines) lines += PreviewLine(type, text.removeSuffix("\r")) else more++
    }

    var first = 0

    while (first < edits.size) {
        // Edits closer than twice the context share a hunk
        var last = first

        while (last + 1 < edits.size && edits[last + 1].beginA - edits[last].endA <= 2 * PREVIEW_CONTEXT) {
            last++
        }

        val startA = (edits[first].beginA - PREVIEW_CONTEXT).coerceAtLeast(0)
        val endA = (edits[last].endA + PREVIEW_CONTEXT).coerceAtMost(base.size())
        val startB = (edits[first].beginB - PREVIEW_CONTEXT).coerceAtLeast(0)
        val endB = (edits[last].endB + PREVIEW_CONTEXT).coerceAtMost(kept.size())

        add(PreviewLineType.HUNK, "@@ -${hunkRange(startA, endA - startA)} +${hunkRange(startB, endB - startB)} @@")

        var a = startA

        for (edit in edits.subList(first, last + 1)) {
            while (a < edit.beginA) {
                add(PreviewLineType.CONTEXT, base.getString(a++))
            }

            for (line in edit.beginA until edit.endA) {
                add(PreviewLineType.REMOVED, base.getString(line))
            }

            for (line in edit.beginB until edit.endB) {
                add(PreviewLineType.ADDED, kept.getString(line))
            }

            a = edit.endA
        }

        while (a < endA) {
            add(PreviewLineType.CONTEXT, base.getString(a++))
        }

        first = last + 1
    }

    return KeptChanges(
        added = edits.sumOf { it.lengthB },
        removed = edits.sumOf { it.lengthA },
        lines = lines,
        more = more,
    )
}

/** A hunk header's range as git writes it: the count left out when it's 1, and the line before an empty range. */
private fun hunkRange(start: Int, count: Int) = when (count) {
    0 -> "$start,0"
    1 -> "${start + 1}"
    else -> "${start + 1},$count"
}

/**
 * A submodule's commits, named from its own repository when it has one: their subjects, which contains the other, and
 * the commit it has checked out. Its checkout isn't touched.
 */
private fun submodulePreview(
    repository: Repository,
    reader: ObjectReader,
    path: String,
    base: DirCacheEntry?,
    ours: DirCacheEntry?,
    theirs: DirCacheEntry?,
): ConflictPreview {
    val submodule = try {
        SubmoduleWalk.getSubmoduleRepository(repository, path)
    } catch (e: IOException) {
        printError(TAG, "Couldn't open the submodule at $path", e)
        null
    }

    return submodule.use { sub ->
        fun commit(id: ObjectId): RevCommit? = sub?.let {
            try {
                RevWalk(it).use { walk -> walk.parseCommit(id) }
            } catch (e: MissingObjectException) {
                null
            } catch (e: IncorrectObjectTypeException) {
                null
            }
        }

        val versions = VersionReader(
            repository = repository,
            reader = reader,
            path = path,
            images = false,
            countLines = false,
            commits = { id -> commit(id)?.summary() },
        )

        ConflictPreview(
            base = versions.of(base),
            ours = versions.of(ours),
            theirs = versions.of(theirs),
            submodule = SubmodulePreview(
                initialized = sub != null,
                relation = sub?.let { relation(it, ours, theirs) },
                checkedOut = sub?.resolve(Constants.HEAD)?.name,
            ),
        )
    }
}

private fun RevCommit.summary() = CommitSummary(
    subject = shortMessage,
    author = authorIdent.name,
    timeMillis = authorIdent.whenAsInstant.toEpochMilli(),
)

/** Which of the two commits has the other in its history, when the submodule has both and both are commits. */
private fun relation(submodule: Repository, ours: DirCacheEntry?, theirs: DirCacheEntry?): CommitRelation? {
    if (ours?.fileMode != FileMode.GITLINK || theirs?.fileMode != FileMode.GITLINK) {
        return null
    }

    return try {
        fun contains(tip: ObjectId, other: ObjectId) = RevWalk(submodule).use { walk ->
            walk.isMergedInto(walk.parseCommit(other), walk.parseCommit(tip))
        }

        when {
            contains(ours.objectId, theirs.objectId) -> CommitRelation.OURS_CONTAINS_THEIRS
            contains(theirs.objectId, ours.objectId) -> CommitRelation.THEIRS_CONTAINS_OURS
            else -> CommitRelation.DIVERGED
        }
    } catch (e: MissingObjectException) {
        null
    } catch (e: IncorrectObjectTypeException) {
        null
    }
}
