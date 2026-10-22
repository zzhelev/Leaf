// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.cli.remote.promisorRemotes
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.diff.ContentSource
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.util.io.NullOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "RenameDetection"

/** git's rename threshold: half of the content alike. JGit's own is 60. */
private const val RENAME_SCORE = 50

/**
 * How many added and how many deleted files the general stage compares when `diff.renameLimit` isn't set. JGit's own
 * is 400, git's 1,000. JGit reads every candidate blob again for each file it pairs it with, so the cost is the square
 * of this: 400 moved and edited files took 27 s on loose objects, and 100 about a second (`CLAUDE.md`, Renames in
 * file lists).
 */
const val DEFAULT_RENAME_LIMIT = 100

/** How long the stages that read blobs may take, after which the files they hadn't paired stay added and deleted. */
val MAX_RENAME_DETECTION_TIME: Duration = 2.seconds

/** A limit under which JGit's content stage never runs, as it runs for `max(added, deleted) <= limit` (0 is no limit). */
private const val NO_CONTENT_RENAMES = -1

/**
 * The changes from [oldTree] to [newTree], with moved files as renames (fork-only), for a commit's file list and a
 * worktree's review. `git.diff()` has no switch for it, and JGit reads `diff.renames` as off when it's unset, where
 * git's default is on, so a move that git shows as `R old -> new` read as a deletion and an addition.
 *
 * - `diff.renames=false` turns it off. Any other value, `copies` too, finds renames.
 * - Exact renames, with the same blob, come first and read nothing.
 * - Then files of the same name, which is how a refactor that moves files and edits their `package` line goes: JGit
 *   compares each added file only with the deleted files of its own name.
 * - Then every file that's left, when there are at most `diff.renameLimit` of each ([DEFAULT_RENAME_LIMIT] when it
 *   isn't set), for the files that were renamed as well as moved.
 * - The last two read blobs, so they stop after [timeLimit] and when the coroutine is cancelled. Whatever the earlier
 *   stages paired stays. In a partial clone they only look at the blobs that the repository has: a list never
 *   downloads anything.
 *
 * JGit's `DiffFormatter` can't do this: it returns an empty list when its progress monitor cancels.
 */
suspend fun Repository.diffWithRenames(
    oldTree: AbstractTreeIterator,
    newTree: AbstractTreeIterator,
    timeLimit: Duration = MAX_RENAME_DETECTION_TIME,
): List<DiffEntry> {
    val entries = DiffFormatter(NullOutputStream.INSTANCE).use { formatter ->
        formatter.setRepository(this)
        // setRepository switches detection on from diff.renames, with JGit's score and limit
        formatter.setDetectRenames(false)
        formatter.scan(oldTree, newTree)
    }

    return withRenames(entries, timeLimit)
}

/**
 * [entries], with the added and deleted files that moved paired as renames, as [diffWithRenames] pairs them. Fork-only:
 * with [newSource], the new side of the added files is read from there, as the files on disk of a comparison with the
 * working tree, which have no blobs (`docs/fork/compare.md`, 7.2).
 */
suspend fun Repository.withRenames(
    entries: List<DiffEntry>,
    timeLimit: Duration = MAX_RENAME_DETECTION_TIME,
    newSource: ContentSource? = null,
): List<DiffEntry> {
    val diffConfig = config.get(DiffConfig.KEY)
    val renamesDisabled = config.getString(ConfigConstants.CONFIG_DIFF_SECTION, null, "renames") != null &&
            !diffConfig.isRenameDetectionEnabled
    val added = entries.filter { it.changeType == ChangeType.ADD }
    val deleted = entries.filter { it.changeType == ChangeType.DELETE }
    // Taken now: JGit changes the type of the entries that it pairs, as it did to a deleted file that it renamed
    val others = entries.filter { it.changeType != ChangeType.ADD && it.changeType != ChangeType.DELETE }

    if (renamesDisabled || added.isEmpty() || deleted.isEmpty()) {
        return entries
    }

    val limit = if (config.getString(ConfigConstants.CONFIG_DIFF_SECTION, null, "renameLimit") != null) {
        diffConfig.renameLimit
    } else {
        DEFAULT_RENAME_LIMIT
    }
    val progress = TimeAndCancellation(currentCoroutineContext()[Job], timeLimit)

    val renamed = newObjectReader().use { reader ->
        RenameStages(this, reader, diffConfig, limit, progress, added, newSource).run(added + deleted)
    }

    // A cancelled call throws, instead of returning what it had
    currentCoroutineContext().ensureActive()

    return others + renamed
}

private class RenameStages(
    private val repository: Repository,
    private val reader: ObjectReader,
    private val diffConfig: DiffConfig,
    private val limit: Int,
    private val progress: ProgressMonitor,
    added: List<DiffEntry>,
    private val newSource: ContentSource?,
) {
    private val addedByPath = added.associateBy { it.newPath }
    private val hasBlob: (DiffEntry) -> Boolean = readableBlobs(repository, addedOnDisk = newSource != null)
    private val sources = ContentSource.create(reader).let { blobs -> ContentSource.Pair(blobs, newSource ?: blobs) }

    /** [candidates] (the added and deleted files) with the renames that the stages found. */
    fun run(candidates: List<DiffEntry>): List<DiffEntry> {
        val exact = detect(candidates, NO_CONTENT_RENAMES, NullProgressMonitor.INSTANCE) ?: return candidates
        val renames = exact.filter { it.changeType == ChangeType.RENAME }.toMutableList()
        var left = unmatched(exact)

        // The files that a partial clone lacks can only pair when their blobs are the same
        val unreadable = left.filterNot(hasBlob)
        left = left - unreadable.toSet()

        left = byName(left, renames)
        left = general(left, renames)

        return renames + left + unreadable
    }

    private fun byName(candidates: List<DiffEntry>, renames: MutableList<DiffEntry>): List<DiffEntry> {
        val left = mutableListOf<DiffEntry>()

        for (group in candidates.groupBy { it.fileName() }.values) {
            val result = if (group.canPair() && !progress.isCancelled) detect(group, limit, progress) else null

            if (result == null) {
                left += group
            } else {
                renames += result.filter { it.changeType == ChangeType.RENAME }
                left += unmatched(result)
            }
        }

        return left
    }

    private fun general(candidates: List<DiffEntry>, renames: MutableList<DiffEntry>): List<DiffEntry> {
        val result = if (candidates.canPair() && !progress.isCancelled) detect(candidates, limit, progress) else null

        return if (result == null) {
            candidates
        } else {
            renames += result.filter { it.changeType == ChangeType.RENAME }
            unmatched(result)
        }
    }

    /**
     * What [candidates] pair to, or null when the stage couldn't run. JGit makes a copy of a file of which another is
     * deleted and added more than once, where git shows an addition, so a copy is the addition that it came from.
     */
    private fun detect(candidates: List<DiffEntry>, renameLimit: Int, monitor: ProgressMonitor): List<DiffEntry>? {
        val detector = RenameDetector(reader, diffConfig).apply {
            renameScore = RENAME_SCORE
            setRenameLimit(renameLimit)
            addAll(candidates)
        }

        return try {
            detector.compute(sources, monitor)
        } catch (e: CanceledException) {
            null
        } catch (e: MissingObjectException) {
            // A repository that doesn't say it's a partial clone: the files stay added and deleted
            printLog(TAG, "Left ${candidates.size} files unpaired, as ${e.objectId.name} is missing")

            null
        }
    }

    private fun unmatched(result: List<DiffEntry>): List<DiffEntry> = result.mapNotNull { entry ->
        when (entry.changeType) {
            ChangeType.RENAME -> null
            ChangeType.COPY -> addedByPath.getValue(entry.newPath)
            else -> entry
        }
    }
}

private fun DiffEntry.fileName(): String =
    (if (changeType == ChangeType.ADD) newPath else oldPath).substringAfterLast('/')

private fun List<DiffEntry>.canPair() = any { it.changeType == ChangeType.ADD } && any { it.changeType == ChangeType.DELETE }

/**
 * Whether the repository has a file's blob, which the stages that compare content read. Always, but in a partial
 * clone, where a blob that was never checked out is downloaded when it's needed, and a list isn't a reason.
 */
private fun readableBlobs(repository: Repository, addedOnDisk: Boolean): (DiffEntry) -> Boolean {
    if (promisorRemotes(repository).isEmpty()) {
        return { true }
    }

    return { entry ->
        val id = if (entry.changeType == ChangeType.ADD) entry.newId else entry.oldId

        // A file on disk is there to read
        (addedOnDisk && entry.changeType == ChangeType.ADD) ||
                !id.isComplete || repository.objectDatabase.has(id.toObjectId())
    }
}

/** Cancels the detector's stages once [timeLimit] is up or [job] is, as it's the only way to stop JGit's detector. */
private class TimeAndCancellation(private val job: Job?, timeLimit: Duration) : ProgressMonitor {
    private val deadline = System.nanoTime() + timeLimit.inWholeNanoseconds

    override fun isCancelled(): Boolean = job?.isActive == false || System.nanoTime() - deadline > 0

    override fun start(totalTasks: Int) {}

    override fun beginTask(title: String?, totalWork: Int) {}

    override fun update(completed: Int) {}

    override fun endTask() {}

    override fun showDuration(enabled: Boolean) {}
}
