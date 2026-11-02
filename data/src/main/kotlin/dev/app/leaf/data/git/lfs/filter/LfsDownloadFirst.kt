// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.cherryPickTrees
import dev.app.leaf.data.git.cli.remote.mergeTrees
import dev.app.leaf.data.git.cli.remote.rebaseTreeSets
import dev.app.leaf.data.git.cli.remote.replayTreeSets
import dev.app.leaf.data.git.lfs.FetchLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.GitLfsSetup
import dev.app.leaf.data.git.lfs.MAX_LFS_POINTER_SIZE
import dev.app.leaf.data.git.lfs.lfsStore
import dev.app.leaf.data.git.lfs.parseLfsPointer
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.LfsDownloadFirstError
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsFileFailure
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.IOException
import javax.inject.Inject

private const val TAG = "LfsDownloadFirst"

/** What a merge, cherry-pick, revert or rebase checks out, for [LfsDownloadFirst]. Each names commits as git does. */
sealed interface LfsCheckout {
    /** A merge of [other] into HEAD. */
    data class Merge(val other: String) : LfsCheckout

    /** A cherry-pick or a revert of [commits] onto HEAD, which read the same versions. */
    data class CherryPicks(val commits: List<String>) : LfsCheckout

    /** A rebase of HEAD onto [onto]. */
    data class Rebase(val onto: String) : LfsCheckout

    /**
     * A rebase that replays [commits], oldest first, onto [onto] (the empty tree for null, as `--root` starts): a commit
     * editor's plan, whose todo, not git's, says which commits.
     */
    data class Replay(val onto: String?, val commits: List<String>) : LfsCheckout
}

/**
 * Downloads, before git merges, cherry-picks, reverts or rebases with Leaf's LFS filter, the LFS objects that its
 * checkout will smudge (fork-only, stage 2 of `docs/fork/lfs-filter.md`). A download that fails during the checkout
 * stops git halfway, with the files that it wrote before left in the working tree; one that fails here stops the
 * operation before git starts, with nothing changed ([LfsDownloadFirstError]).
 *
 * The objects are those of the files that the operation's trees don't all have alike, the versions that a partial
 * clone downloads first too ([mergeTrees], [cherryPickTrees], [rebaseTreeSets], and [replayTreeSets] for a commit
 * editor's plan), which are LFS pointers at a path that their tree's attributes give the LFS filter, and whose objects
 * the store lacks ([lfsFilesToDownload]). They go in one batch, with the processing screen's progress and Cancel. Where
 * git-lfs is set up, it downloads them itself, as Leaf's filter doesn't run. The filter downloads what this misses.
 */
class LfsDownloadFirst @Inject constructor(
    private val jgit: JGit,
    private val gitLfsSetup: GitLfsSetup,
    private val getLfsUrlGitAction: IGetLfsUrlGitAction,
    private val fetchLfsObjectsGitAction: FetchLfsObjectsGitAction,
    private val repositoryStateRepository: RepositoryStateRepository,
) {
    suspend fun before(repositoryPath: String, checkout: LfsCheckout): Either<Unit, GitError> {
        val found = jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            if (repository.isBare || gitLfsSetup.gitRunsGitLfs(repository.workTree)) {
                return@provide null
            }

            val files = withContext(Dispatchers.IO) {
                try {
                    RevWalk(repository).use { walk ->
                        lfsFilesToDownload(repository, treeSets(repository, walk, checkout))
                    }
                } catch (e: IOException) {
                    // Such as a tree that a treeless clone lacks: git and the filter download what's needed
                    printError(TAG, "Couldn't tell which LFS objects to download first", e)
                    emptyList()
                }
            }

            repository to files
        }

        val (repository, files) = when (found) {
            is Either.Err -> return found
            is Either.Ok -> found.value ?: return Either.Ok(Unit)
        }

        if (files.isEmpty()) {
            return Either.Ok(Unit)
        }

        val pointers = files.map { it.pointer }.distinctBy { it.oid.name() }
        printLog(TAG, "Downloading ${pointers.size} LFS objects before git checks them out")

        val failures = download(repository, pointers)
        val failed = files.mapNotNull { file ->
            failures[file.pointer.oid.name()]?.let { LfsFileFailure(file.path, it) }
        }

        return if (failed.isEmpty()) Either.Ok(Unit) else Either.Err(LfsDownloadFirstError(failed))
    }

    private suspend fun download(repository: Repository, pointers: List<LfsPointer>): Map<String, LfsError> {
        val server = getLfsUrlGitAction(repository, remoteName = null)
            ?: return pointers.associate { it.oid.name() to LfsError.NoServer }
        val total = pointers.sumOf { it.size }

        repositoryStateRepository.updateTaskProgress(TaskProgress(lfsDownloadStage(0, pointers.size), 0))

        return try {
            fetchLfsObjectsGitAction(
                repository,
                server,
                pointers.map { LfsObjectBatch(it.oid.name(), it.size) },
            ) { done, receivedBytes ->
                repositoryStateRepository.updateTaskProgress(
                    TaskProgress(lfsDownloadStage(done, pointers.size), lfsDownloadPercent(receivedBytes, total))
                )
            }
        } finally {
            repositoryStateRepository.updateTaskProgress(null)
        }
    }
}

private fun treeSets(repository: Repository, walk: RevWalk, checkout: LfsCheckout): List<List<AnyObjectId?>> {
    return when (checkout) {
        is LfsCheckout.Merge -> repository.resolve(checkout.other)
            ?.let { listOf(mergeTrees(repository, walk, it)) }
            .orEmpty()

        is LfsCheckout.CherryPicks -> checkout.commits
            .mapNotNull { repository.resolve(it) }
            .map { cherryPickTrees(repository, walk, it) }

        is LfsCheckout.Rebase -> repository.resolve(checkout.onto)
            ?.let { rebaseTreeSets(repository, walk, it) }
            .orEmpty()

        is LfsCheckout.Replay -> replayTreeSets(
            repository,
            walk,
            checkout.onto?.let { repository.resolve(it) },
            checkout.commits.mapNotNull { repository.resolve(it) },
        )
    }
}

/** An LFS file that a checkout smudges: its path, and its pointer. */
internal data class LfsFileToDownload(val path: String, val pointer: LfsPointer)

/**
 * The LFS files whose objects [repository]'s store lacks, among the files that each set of [treeSets] doesn't have
 * alike: a pointer by git-lfs's rules, without extension lines, as Leaf's filter passes those through as they are, at a
 * path that the attributes of the pointer's own tree give the LFS filter, as git asks the filter only for those. A blob
 * that a partial clone hasn't downloaded is left out: git downloads it, and the filter its object.
 */
internal fun lfsFilesToDownload(repository: Repository, treeSets: List<List<AnyObjectId?>>): List<LfsFileToDownload> {
    val store = repository.lfsStore()
    val files = linkedMapOf<Pair<String, String>, LfsFileToDownload>()

    repository.newObjectReader().use { reader ->
        for (trees in treeSets) {
            TreeWalk(repository, reader).use { walk ->
                walk.isRecursive = true
                walk.filter = TreeFilter.ANY_DIFF
                walk.operationType = TreeWalk.OperationType.CHECKOUT_OP
                walk.setAttributesNodeProvider(repository.createAttributesNodeProvider())

                for (tree in trees) {
                    if (tree == null) walk.addTree(EmptyTreeIterator()) else walk.addTree(tree)
                }

                while (walk.next()) {
                    for (index in 0 until walk.treeCount) {
                        val mode = walk.getFileMode(index)

                        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                            continue
                        }

                        val pointer = pointerIn(reader, walk.getObjectId(index)) ?: continue

                        if (walk.getAttributes(index).getValue("filter") != "lfs") {
                            continue
                        }

                        if (store.find(pointer.oid, pointer.size) == null) {
                            val path = walk.pathString
                            files.putIfAbsent(path to pointer.oid.name(), LfsFileToDownload(path, pointer))
                        }
                    }
                }
            }
        }
    }

    return files.values.toList()
}

/** The LFS pointer that [blob] is, or null: not one, too large for one, or not in the repository. */
private fun pointerIn(reader: ObjectReader, blob: ObjectId): LfsPointer? {
    if (!reader.has(blob) || reader.getObjectSize(blob, Constants.OBJ_BLOB) > MAX_LFS_POINTER_SIZE) {
        return null
    }

    return parseLfsPointer(reader.open(blob, Constants.OBJ_BLOB).bytes, allowExtensions = false)
}

/** What the processing screen says while LFS objects download: "Downloading LFS objects (3 of 10)". */
internal fun lfsDownloadStage(done: Int, count: Int): String =
    "Downloading LFS objects (${minOf(done + 1, count)} of $count)"

/** How far the downloads are, by bytes, or null when there's nothing to count. */
internal fun lfsDownloadPercent(receivedBytes: Long, totalBytes: Long): Int? =
    if (totalBytes > 0) (receivedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else null
