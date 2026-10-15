// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IGetConflictedFileGitAction
import dev.app.leaf.domain.conflicts.inParts
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictStages
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.TextLimit
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.io.EolStreamTypeUtil
import org.eclipse.jgit.lib.CoreConfig.EolStreamType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GetConflictedFileGitAction"

/**
 * Text larger than this, on any side, gets a choice of side instead of the three panes. Below it, a pick, Undo and
 * switching layouts took under 100 ms, and the resolver held under 200 MB (`conflict-resolver.md`, section 10).
 */
internal const val MAX_RESOLVER_TEXT_SIZE = 10L * 1024 * 1024

/**
 * How long comparing the versions may take before the text gets a choice of side too. Most files take a fraction of
 * it, but dense changes, as in a lock file, take time that grows with the square of the size (`conflict-resolver.md`,
 * section 10).
 */
internal val MAX_RESOLVER_COMPARE_TIME = 2.seconds

/** git-lfs's largest pointer file. */
private const val MAX_LFS_POINTER_SIZE = 1024L

class GetConflictedFileGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
) : IGetConflictedFileGitAction {
    override suspend fun invoke(repositoryPath: String, path: String): Either<ConflictedFile, GitError> =
        withContext(Dispatchers.IO) {
            partialClone.retryingMissing(repositoryPath) {
                jgit.provide(repositoryPath) { git ->
                    loadConflictedFile(git.repository, path)
                        ?: raiseError(ConflictError.NotConflicted(listOf(path)))
                }
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
    maxCompareTime: Duration = MAX_RESOLVER_COMPARE_TIME,
): ConflictedFile? {
    val index = repository.readDirCache()
    val conflict = index.fileConflict(path) ?: return null
    val entries = index.stagesOf(path)

    fun stage(stage: Int) = entries.firstOrNull { it.stage == stage }

    val base = stage(DirCacheEntry.STAGE_1)
    val ours = stage(DirCacheEntry.STAGE_2)
    val theirs = stage(DirCacheEntry.STAGE_3)
    val stages = ConflictStages.of(base != null, ours != null, theirs != null)
    val sides = try {
        conflictSides(repository)
    } catch (e: Exception) {
        // The resolver can still name the sides as ours and theirs
        printError(TAG, "Couldn't read which side is which", e)
        null
    }

    // A file that each side renamed differently opens as one, under its old name, whichever of its paths was opened
    if (stages in OTHER_PATHS) {
        renamedFiles(repository, index).firstOrNull { path in it.paths }?.let { renamed ->
            return renamedFile(repository, index, renamed, sides)
        }
    }

    val (content, preview) = repository.newObjectReader().use { reader ->
        val present = listOfNotNull(base, ours, theirs)

        val content = when {
            stages in OTHER_PATHS -> ConflictContent.OtherPaths

            present.any { it.fileMode == FileMode.GITLINK } -> ConflictContent.Submodule
            ours == null || theirs == null -> ConflictContent.Deleted
            present.any { it.fileMode == FileMode.SYMLINK } -> ConflictContent.SymbolicLink(
                oursTarget = reader.symbolicLinkTarget(ours),
                theirsTarget = reader.symbolicLinkTarget(theirs),
            )

            else -> fileContent(repository, reader, path, base, ours, theirs, sides, maxTextSize, maxCompareTime)
        }

        // The cards can do without it: a side's choice doesn't depend on it
        val preview = try {
            conflictPreview(repository, reader, path, content, base, ours, theirs, maxCompareTime)
        } catch (e: Exception) {
            printError(TAG, "Couldn't read what the choice cards show of $path", e)
            null
        }

        content to preview
    }

    return ConflictedFile(
        path = path,
        base = base?.toConflictStage(),
        ours = ours?.toConflictStage(),
        theirs = theirs?.toConflictStage(),
        conflict = conflict,
        sides = sides,
        content = content,
        preview = preview,
    )
}

/** What git leaves at each path of a file renamed differently on each side, and of other conflicts at several paths. */
private val OTHER_PATHS = setOf(ConflictStages.BOTH_DELETED, ConflictStages.ADDED_BY_US, ConflictStages.ADDED_BY_THEM)

/**
 * [renamed] as one conflicted file, under its old name: the base from it, ours and theirs from their new names. Its
 * content has conflicts when git left markers in either new name's working file (the user may have fixed them since).
 */
private fun renamedFile(
    repository: Repository,
    index: DirCache,
    renamed: RenamedFile,
    sides: ConflictSides?,
): ConflictedFile {
    val base = index.stagesOf(renamed.original).single()
    val ours = index.stagesOf(renamed.ours).single()
    val theirs = index.stagesOf(renamed.theirs).single()
    val content = ConflictContent.Renamed(
        file = renamed,
        contentConflicts = listOf(renamed.ours, renamed.theirs)
            .any { hasConflictMarkers(File(repository.workTree, it)) },
        theirsCommit = theirsCommit(repository)?.name,
    )
    val preview = try {
        repository.newObjectReader().use { reader ->
            conflictPreview(repository, reader, renamed.original, content, base, ours, theirs)
        }
    } catch (e: Exception) {
        printError(TAG, "Couldn't read what the choice cards show of ${renamed.original}", e)
        null
    }

    return ConflictedFile(
        path = renamed.original,
        base = base.toConflictStage(),
        ours = ours.toConflictStage(),
        theirs = theirs.toConflictStage(),
        conflict = FileConflict(
            ours = index.fileConflict(renamed.ours)?.ours,
            theirs = index.fileConflict(renamed.theirs)?.theirs,
        ),
        sides = sides,
        content = content,
        preview = preview,
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
    sides: ConflictSides?,
    maxTextSize: Long,
    maxCompareTime: Duration,
): ConflictContent {
    val oursPointer = reader.lfsPointer(ours)
    val theirsPointer = reader.lfsPointer(theirs)

    if (oursPointer != null || theirsPointer != null) {
        return ConflictContent.Lfs(oursPointer, theirsPointer)
    }

    val attributes = conflictFileAttributes(repository, path)
    val versions = listOfNotNull(base, ours, theirs)
    val size = versions.maxOf { reader.getObjectSize(it.objectId, Constants.OBJ_BLOB) }

    if (attributes.mergesAsBinary) {
        return ConflictContent.Binary
    }

    if (size > maxTextSize) {
        // Only the start of each is read, as for a diff of large files
        val binary = versions.any { side -> reader.open(side.objectId).openStream().use { RawText.isBinary(it) } }

        return if (binary) ConflictContent.Binary else ConflictContent.TooLarge(size, TextLimit.Size(maxTextSize))
    }

    // The whole of each, as JGit's merge loads them: a file that ends with a lone '\r' is binary too
    val texts = versions.associateWith { reader.open(it.objectId, Constants.OBJ_BLOB).bytes }

    if (texts.values.any { RawText.isBinary(it, it.size, true) }) {
        return ConflictContent.Binary
    }

    // Long runs of unchanged lines are edited in parts, and lines saved in one resume in it
    val merge = try {
        textMerge(
            base = base?.let { RawText(texts.getValue(it)) } ?: RawText.EMPTY_TEXT,
            ours = RawText(texts.getValue(ours)),
            theirs = RawText(texts.getValue(theirs)),
            algorithm = mergeDiffAlgorithm(repository.config),
            timeLimit = maxCompareTime,
        ).inParts()
    } catch (_: ComparisonTookTooLong) {
        return ConflictContent.TooLarge(size, TextLimit.Time(maxCompareTime))
    }
    val style = ConflictMarkerStyle(
        withBase = conflictStyleWithBase(repository.config),
        labels = conflictLabels(repository, sides),
        size = attributes.markerSize,
    )

    return workingFile(repository, path, merge, style, attributes)
}

/**
 * Looks at the working file's conflict markers, and notes its bytes' id for Save (`conflict-resolver.md`, 2.2). A file
 * with markers that the resolver saved, picks or lines typed included, opens as it was saved ([resumedFrom]).
 */
private fun workingFile(
    repository: Repository,
    path: String,
    merge: TextMerge,
    style: ConflictMarkerStyle,
    attributes: ConflictFileAttributes,
): ConflictContent.Text {
    val file = File(repository.workTree, path)

    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return ConflictContent.Text(merge, WorkingFileCheck(WorkingFileState.MISSING, conflicts = 0, contentId = null))
    }

    val bytes = file.readBytes()
    val scan = scanConflictMarkers(RawText(bytes), style.size)

    fun check(state: WorkingFileState) = WorkingFileCheck(state, scan.conflicts, blobId(bytes))

    if (scan.pairsUp && scan.conflicts == 0) {
        return ConflictContent.Text(merge, check(WorkingFileState.NO_MARKERS))
    }

    // Only when converting the file as git adds it, and back as Save writes it, gives the same bytes
    val canonical = checkedIn(bytes, attributes.checkinLineEndings)
    val resumed = if (checkedOut(canonical, attributes.checkoutLineEndings).contentEquals(bytes)) {
        merge.resumedFrom(canonical, style)
    } else {
        null
    }

    return when {
        resumed != null -> ConflictContent.Text(resumed, check(WorkingFileState.MATCHES))
        scan.pairsUp && scan.conflicts == merge.conflicts -> ConflictContent.Text(merge, check(WorkingFileState.MATCHES))
        else -> ConflictContent.Text(merge, check(WorkingFileState.DIFFERENT))
    }
}

/** [bytes] as `git add` converts their line endings. JGit's output streams only convert for a checkout. */
internal fun checkedIn(bytes: ByteArray, lineEndings: EolStreamType): ByteArray =
    EolStreamTypeUtil.wrapInputStream(ByteArrayInputStream(bytes), lineEndings).use { it.readBytes() }

/** [bytes] as a checkout, and Save, convert their line endings. */
private fun checkedOut(bytes: ByteArray, lineEndings: EolStreamType): ByteArray =
    ByteArrayOutputStream().also { out ->
        EolStreamTypeUtil.wrapOutputStream(out, lineEndings).use { it.write(bytes) }
    }.toByteArray()

/** The id that git gives [bytes] as a blob, to tell whether a file changed. */
internal fun blobId(bytes: ByteArray): String = ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, bytes).name

private fun ObjectReader.symbolicLinkTarget(entry: DirCacheEntry): String? =
    if (entry.fileMode == FileMode.SYMLINK) open(entry.objectId).bytes.decodeToString() else null

/** The object that [entry]'s file points to, when it's a Git LFS pointer. */
internal fun ObjectReader.lfsPointer(entry: DirCacheEntry): LfsObject? = lfsPointer(entry.objectId)

/** The object that the blob [id] points to, when it's a Git LFS pointer. */
internal fun ObjectReader.lfsPointer(id: ObjectId): LfsObject? {
    if (getObjectSize(id, Constants.OBJ_BLOB) > MAX_LFS_POINTER_SIZE) {
        return null
    }

    // JGit throws on a pointer whose id isn't hex: a file that only looks like a pointer
    val pointer = try {
        open(id).openStream().use { LfsPointer.parseLfsPointer(it) }
    } catch (e: IllegalArgumentException) {
        null
    } ?: return null

    return LfsObject(oid = pointer.oid.name(), size = pointer.size)
}

private fun DirCacheEntry.toConflictStage() = ConflictStage(objectId = objectId.name, mode = rawMode)
