// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IGetConflictedFileGitAction
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictStages
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

private const val TAG = "GetConflictedFileGitAction"

/**
 * Text larger than this, on any side, gets a choice of side instead of the three panes. Provisional, until the
 * resolver's speed is measured on large files (`conflict-resolver.md`, section 7, step 6).
 */
internal const val MAX_RESOLVER_TEXT_SIZE = 10L * 1024 * 1024

/** git-lfs's largest pointer file. */
private const val MAX_LFS_POINTER_SIZE = 1024L

class GetConflictedFileGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetConflictedFileGitAction {
    override suspend fun invoke(repositoryPath: String, path: String): Either<ConflictedFile, GitError> =
        withContext(Dispatchers.IO) {
            jgit.provide(repositoryPath) { git ->
                loadConflictedFile(git.repository, path)
                    ?: raiseError(ConflictError.NotConflicted(listOf(path)))
            }
        }
}

/**
 * The conflicted file at [path], classified as `conflict-resolver.md` 2.4 does, or null when the index has no
 * conflict there. The order follows `git mergetool`: a submodule first, then a deletion, then a symbolic link.
 */
internal fun loadConflictedFile(
    repository: Repository,
    path: String,
    maxTextSize: Long = MAX_RESOLVER_TEXT_SIZE,
): ConflictedFile? {
    val index = repository.readDirCache()
    val conflict = index.fileConflict(path) ?: return null
    val entries = index.stagesOf(path)

    fun stage(stage: Int) = entries.firstOrNull { it.stage == stage }

    val base = stage(DirCacheEntry.STAGE_1)
    val ours = stage(DirCacheEntry.STAGE_2)
    val theirs = stage(DirCacheEntry.STAGE_3)
    val stages = ConflictStages.of(base != null, ours != null, theirs != null)

    val content = repository.newObjectReader().use { reader ->
        val present = listOfNotNull(base, ours, theirs)

        when {
            stages in setOf(ConflictStages.BOTH_DELETED, ConflictStages.ADDED_BY_US, ConflictStages.ADDED_BY_THEM) ->
                ConflictContent.OtherPaths

            present.any { it.fileMode == FileMode.GITLINK } -> ConflictContent.Submodule
            ours == null || theirs == null -> ConflictContent.Deleted
            present.any { it.fileMode == FileMode.SYMLINK } -> ConflictContent.SymbolicLink(
                oursTarget = reader.symbolicLinkTarget(ours),
                theirsTarget = reader.symbolicLinkTarget(theirs),
            )

            else -> fileContent(repository, reader, path, base, ours, theirs, maxTextSize)
        }
    }

    return ConflictedFile(
        path = path,
        base = base?.toConflictStage(),
        ours = ours?.toConflictStage(),
        theirs = theirs?.toConflictStage(),
        conflict = conflict,
        sides = try {
            conflictSides(repository)
        } catch (e: Exception) {
            // The resolver can still name the sides as ours and theirs
            printError(TAG, "Couldn't read which side is which", e)
            null
        },
        content = content,
    )
}

/** The content of a conflict between two files: Git LFS pointers, binary, too large, or text to merge. */
private fun fileContent(
    repository: Repository,
    reader: ObjectReader,
    path: String,
    base: DirCacheEntry?,
    ours: DirCacheEntry,
    theirs: DirCacheEntry,
    maxTextSize: Long,
): ConflictContent {
    val oursPointer = reader.lfsPointer(ours)
    val theirsPointer = reader.lfsPointer(theirs)

    if (oursPointer != null || theirsPointer != null) {
        return ConflictContent.Lfs(oursPointer, theirsPointer)
    }

    val attributes = conflictFileAttributes(repository, path)
    val sides = listOfNotNull(base, ours, theirs)
    val size = sides.maxOf { reader.getObjectSize(it.objectId, Constants.OBJ_BLOB) }

    if (attributes.mergesAsBinary) {
        return ConflictContent.Binary
    }

    if (size > maxTextSize) {
        // Only the start of each is read, as for a diff of large files
        val binary = sides.any { side -> reader.open(side.objectId).openStream().use { RawText.isBinary(it) } }

        return if (binary) ConflictContent.Binary else ConflictContent.TooLarge(size, maxTextSize)
    }

    // The whole of each, as JGit's merge loads them: a file that ends with a lone '\r' is binary too
    val texts = sides.associateWith { reader.open(it.objectId, Constants.OBJ_BLOB).bytes }

    if (texts.values.any { RawText.isBinary(it, it.size, true) }) {
        return ConflictContent.Binary
    }

    val merge = textMerge(
        base = base?.let { RawText(texts.getValue(it)) } ?: RawText.EMPTY_TEXT,
        ours = RawText(texts.getValue(ours)),
        theirs = RawText(texts.getValue(theirs)),
        algorithm = mergeDiffAlgorithm(repository.config),
    )

    return ConflictContent.Text(merge, workingFileCheck(repository, path, merge, attributes.markerSize))
}

/** Looks at the working file's conflict markers, and notes its bytes' id for Save (`conflict-resolver.md`, 2.2). */
private fun workingFileCheck(
    repository: Repository,
    path: String,
    merge: TextMerge,
    markerSize: Int,
): WorkingFileCheck {
    val file = File(repository.workTree, path)

    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return WorkingFileCheck(WorkingFileState.MISSING, conflicts = 0, contentId = null)
    }

    val bytes = file.readBytes()
    val scan = scanConflictMarkers(RawText(bytes), markerSize)
    val state = when {
        !scan.pairsUp -> WorkingFileState.DIFFERENT
        scan.conflicts == 0 -> WorkingFileState.NO_MARKERS
        scan.conflicts == merge.conflicts -> WorkingFileState.MATCHES
        else -> WorkingFileState.DIFFERENT
    }

    return WorkingFileCheck(state, scan.conflicts, blobId(bytes))
}

/** The id that git gives [bytes] as a blob, to tell whether a file changed. */
internal fun blobId(bytes: ByteArray): String = ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, bytes).name

private fun ObjectReader.symbolicLinkTarget(entry: DirCacheEntry): String? =
    if (entry.fileMode == FileMode.SYMLINK) open(entry.objectId).bytes.decodeToString() else null

/** The object that [entry]'s file points to, when it's a Git LFS pointer. */
private fun ObjectReader.lfsPointer(entry: DirCacheEntry): LfsObject? {
    if (getObjectSize(entry.objectId, Constants.OBJ_BLOB) > MAX_LFS_POINTER_SIZE) {
        return null
    }

    // JGit throws on a pointer whose id isn't hex: a file that only looks like a pointer
    val pointer = try {
        open(entry.objectId).openStream().use { LfsPointer.parseLfsPointer(it) }
    } catch (e: IllegalArgumentException) {
        null
    } ?: return null

    return LfsObject(oid = pointer.oid.name(), size = pointer.size)
}

private fun DirCacheEntry.toConflictStage() = ConflictStage(objectId = objectId.name, mode = rawMode)
