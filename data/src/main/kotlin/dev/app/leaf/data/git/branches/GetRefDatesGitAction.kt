// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetRefDatesGitAction
import dev.app.leaf.domain.models.RefDates
import dev.app.leaf.domain.sorting.ReflogLine
import dev.app.leaf.domain.sorting.lastCheckoutTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "GetRefDatesGitAction"

class GetRefDatesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetRefDatesGitAction {
    /** Commit and tag objects never change, so their dates are kept across refreshes. */
    private val timeByObjectId = ConcurrentHashMap<ObjectId, Long>()

    override suspend operator fun invoke(repositoryPath: String): Either<RefDates, GitError> =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                val repository = git.repository
                val refDatabase = repository.refDatabase
                val commitTimes = HashMap<String, Long>()
                val tagTimes = HashMap<String, Long>()

                RevWalk(repository).use { walk ->
                    val branches = refDatabase.getRefsByPrefix(Constants.R_HEADS) +
                            refDatabase.getRefsByPrefix(Constants.R_REMOTES)

                    for (ref in branches) {
                        val id = ref.objectId ?: continue
                        commitTime(walk, id)?.let { commitTimes[ref.name] = it }
                    }

                    for (ref in refDatabase.getRefsByPrefix(Constants.R_TAGS)) {
                        tagTime(walk, ref)?.let { tagTimes[ref.name] = it }
                    }
                }

                RefDates(
                    commitTimes = commitTimes,
                    tagTimes = tagTimes,
                    lastCheckoutTimes = readCheckoutTimes(repository),
                )
            }
        }

    private fun commitTime(walk: RevWalk, id: AnyObjectId): Long? = cached(id) {
        (walk.parseAny(id) as? RevCommit)?.commitTimeMillis()
    }

    /** The tagger date of an annotated tag, or the commit date of the commit a lightweight tag points to. */
    private fun tagTime(walk: RevWalk, ref: Ref): Long? {
        val id = ref.objectId ?: return null

        return cached(id) {
            when (val target = walk.parseAny(id)) {
                is RevTag -> target.taggerIdent?.whenAsInstant?.toEpochMilli()
                    ?: (walk.peel(target) as? RevCommit)?.commitTimeMillis()

                is RevCommit -> target.commitTimeMillis()
                else -> null
            }
        }
    }

    private inline fun cached(id: AnyObjectId, read: () -> Long?): Long? {
        val key = id.toObjectId()
        timeByObjectId[key]?.let { return it }

        val time = try {
            read()
        } catch (ex: MissingObjectException) {
            null
        }

        if (time != null) timeByObjectId[key] = time

        return time
    }

    /** A missing or unreadable reflog only means no checkout dates, so it doesn't fail the whole load. */
    private fun readCheckoutTimes(repository: Repository): Map<String, Long> = try {
        val entries = repository.refDatabase.getReflogReader(Constants.HEAD)?.reverseEntries.orEmpty()

        lastCheckoutTimes(entries.map { ReflogLine(it.comment, it.who.whenAsInstant.toEpochMilli()) })
    } catch (ex: IOException) {
        printError(TAG, "Failed to read the HEAD reflog", ex)
        emptyMap()
    }

    private fun RevCommit.commitTimeMillis(): Long = commitTime * 1000L
}
