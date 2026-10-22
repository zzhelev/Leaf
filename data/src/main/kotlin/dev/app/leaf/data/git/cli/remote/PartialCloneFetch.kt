// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.MissingObjectError
import dev.app.leaf.domain.errors.PartialCloneFetchError
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.AndTreeFilter
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.filter.TreeFilter
import javax.inject.Inject

private const val TAG = "PartialCloneFetch"

/**
 * How many times [PartialCloneFetch.retryingMissing] runs an operation again, each time after downloading an object
 * that it lacked. The first download also takes what the operation says it will need, so one is usually enough.
 */
private const val MAX_FETCHES = 10

/**
 * Downloads what a partial clone (`git clone --filter=blob:none`) hasn't yet, for JGit (fork-only). Such a clone has
 * every commit and tree, but only the files it checked out: git downloads the others from the promisor remote when it
 * first needs them. JGit can't, and throws `MissingObjectException`, so Leaf has git fetch them, as git itself does
 * (`promisor-remote.c`): `git fetch <remote> --filter=blob:none --stdin` with the objects' ids, through
 * [GitCliRemoteCommand], with Leaf's credential dialogs.
 *
 * - What only reads, such as a diff or a blame, runs again once the objects it lacked are there
 *   ([retryingMissing]). JGit sees the pack that git wrote when it next looks for an object.
 * - What changes the working tree downloads first ([prefetchCheckout] and the others): JGit's checkout deletes files
 *   before it writes the new ones, and a missing one stopped it halfway, with HEAD where it was.
 *
 * Nothing runs in a repository without a promisor remote.
 */
class PartialCloneFetch @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) {
    /**
     * Runs [block], and when it fails on an object that the partial clone hasn't downloaded, downloads it and runs
     * [block] again, up to [MAX_FETCHES] times. The first download also takes what [alsoNeeded] says, such as both
     * versions of a diff's file, so that [block] doesn't fail again on the next object.
     *
     * The download shows no progress: [block] is a query, which no task's processing screen waits for.
     */
    suspend fun <T> retryingMissing(
        repositoryPath: String,
        alsoNeeded: (Repository) -> Collection<ObjectId> = { emptyList() },
        block: suspend () -> Either<T, GitError>,
    ): Either<T, GitError> {
        var result = block()
        val fetched = mutableSetOf<ObjectId>()

        repeat(MAX_FETCHES) {
            val missing = ((result as? Either.Err)?.error as? MissingObjectError) ?: return result
            val id = ObjectId.fromString(missing.objectId)

            // git fetched it, and JGit still doesn't find it
            if (id in fetched) {
                return result
            }

            val repository = jgit.provide(repositoryPath) { it.repository }.okOrNull() ?: return result

            if (promisorRemotes(repository).isEmpty()) {
                return result
            }

            val needed = if (fetched.isEmpty()) neededObjects(repository, alsoNeeded) + id else setOf(id)
            val fetch = fetch(repository, needed, nothingChanged = false, onProgress = {})

            if (fetch is Either.Err) {
                return Either.Err(fetch.error)
            }

            fetched += needed
            result = block()
        }

        return result
    }

    /** Before checking out [target], a commit or a ref: downloads the files that differ from HEAD's. */
    suspend fun prefetchCheckout(repository: Repository, target: String): Either<Unit, GitError> =
        prefetch(repository) { walk ->
            val targetId = repository.resolve(target) ?: return@prefetch emptySet()

            blobsThatDiffer(repository, listOf(walk.headTree(repository), walk.parseTree(targetId)))
        }

    /**
     * Before merging [other] into HEAD: downloads the versions that the merge reads, of the files that the merge base,
     * HEAD and [other] don't all have alike. Every merge base, as the recursive merge merges them first.
     */
    suspend fun prefetchForMerge(repository: Repository, other: AnyObjectId): Either<Unit, GitError> =
        prefetch(repository) { walk ->
            val head = repository.resolve(Constants.HEAD)
                ?: return@prefetch blobsThatDiffer(repository, listOf(null, walk.parseTree(other)))

            walk.revFilter = RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(head))
            walk.markStart(walk.parseCommit(other))
            val bases = walk.toList()
            walk.reset()
            walk.revFilter = RevFilter.ALL

            blobsThatDiffer(
                repository,
                bases.map { walk.parseTree(it) } + walk.parseTree(head) + walk.parseTree(other),
            )
        }

    /**
     * Before cherry-picking or reverting [commit] onto HEAD: downloads the versions that applying its changes reads,
     * of the files that its parent, HEAD and [commit] don't all have alike.
     */
    suspend fun prefetchForCherryPick(repository: Repository, commit: AnyObjectId): Either<Unit, GitError> =
        prefetch(repository) { walk ->
            val picked = walk.parseCommit(commit)
            val parent = picked.parents.firstOrNull()?.let { walk.parseTree(it) }

            blobsThatDiffer(repository, listOf(parent, walk.headTree(repository), walk.parseTree(picked)))
        }

    /**
     * Before rebasing HEAD onto [onto]: downloads the files that differ between HEAD and [onto], which the rebase
     * checks out first, and both versions of what each commit it replays changes. A merge commit isn't replayed.
     */
    suspend fun prefetchForRebase(repository: Repository, onto: AnyObjectId): Either<Unit, GitError> =
        prefetch(repository) { walk ->
            val head = repository.resolve(Constants.HEAD)
                ?: return@prefetch blobsThatDiffer(repository, listOf(null, walk.parseTree(onto)))
            val needed = blobsThatDiffer(repository, listOf(walk.parseTree(head), walk.parseTree(onto))).toMutableSet()

            walk.markStart(walk.parseCommit(head))
            walk.markUninteresting(walk.parseCommit(onto))

            for (commit in walk.toList()) {
                if (commit.parentCount <= 1) {
                    val parent = commit.parents.firstOrNull()?.let { walk.parseTree(it) }
                    needed += blobsThatDiffer(repository, listOf(parent, walk.parseTree(commit)))
                }
            }

            needed
        }

    /**
     * Downloads, before a change that JGit makes, the objects that [needed] finds. Its progress goes to the processing
     * screen, which can cancel it, and a failure stops the change before it starts.
     */
    private suspend fun prefetch(
        repository: Repository,
        needed: (RevWalk) -> Set<ObjectId>,
    ): Either<Unit, GitError> {
        if (promisorRemotes(repository).isEmpty()) {
            return Either.Ok(Unit)
        }

        val ids = withContext(Dispatchers.IO) { RevWalk(repository).use(needed) }

        return fetch(repository, ids, nothingChanged = true, onProgress = null)
    }

    /**
     * Fetches those of [ids] that the repository doesn't have from its promisor remotes, in git's order, until it has
     * them all.
     *
     * @param onProgress where git's progress goes; null for the processing screen.
     */
    private suspend fun fetch(
        repository: Repository,
        ids: Collection<ObjectId>,
        nothingChanged: Boolean,
        onProgress: ((TaskProgress?) -> Unit)?,
    ): Either<Unit, GitError> {
        val remotes = promisorRemotes(repository)
        var missing = missingOf(repository, ids)

        if (missing.isEmpty() || remotes.isEmpty()) {
            return Either.Ok(Unit)
        }

        var error: GitError? = null

        for (remote in remotes) {
            printLog(TAG, "Downloading ${missing.size} objects that the partial clone lacks from $remote")

            // What git runs to fetch a promisor remote's objects: no negotiation, no tags, no FETCH_HEAD
            val args = listOf(
                "-c", "fetch.negotiationAlgorithm=noop",
                "fetch", remote,
                "--no-tags", "--no-write-fetch-head", "--recurse-submodules=no", "--filter=blob:none", "--stdin",
            )
            val input = missing.joinToString("") { it.name + "\n" }.toByteArray()
            val directory = repository.commandDirectory()
            val output = if (onProgress == null) {
                remoteCommand.run(directory, args, input = input)
            } else {
                remoteCommand.run(directory, args, onProgress = onProgress, input = input)
            }

            error = when (output) {
                is Either.Err -> output.error
                is Either.Ok -> output.value.takeIf { it.exitCode != 0 }?.let {
                    remoteOperationError(it.exitCode, it.stderr)
                }
            }

            missing = missingOf(repository, missing)

            if (missing.isEmpty()) {
                return Either.Ok(Unit)
            }
        }

        printError(TAG, "${missing.size} objects are still missing: $error")

        return Either.Err(PartialCloneFetchError(remotes.last(), error, nothingChanged))
    }

    private suspend fun missingOf(repository: Repository, ids: Collection<ObjectId>): List<ObjectId> =
        withContext(Dispatchers.IO) {
            ids.filterNot { repository.objectDatabase.has(it) }
        }

    private suspend fun neededObjects(
        repository: Repository,
        alsoNeeded: (Repository) -> Collection<ObjectId>,
    ): Set<ObjectId> = withContext(Dispatchers.IO) {
        try {
            alsoNeeded(repository).toSet()
        } catch (e: MissingObjectException) {
            // A tree that a treeless clone lacks: the object that failed is downloaded alone
            emptySet()
        }
    }
}

/**
 * The remotes that a partial clone downloads its missing objects from, in the order git asks them:
 * `extensions.partialClone` (which older git wrote), then each remote with `remote.<name>.promisor`. Empty for a
 * repository that isn't a partial clone.
 */
internal fun promisorRemotes(repository: Repository): List<String> {
    val config = repository.config
    val partialClone = config.getString(ConfigConstants.CONFIG_EXTENSIONS_SECTION, null, "partialClone")
    val promisors = config.getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION).filter { remote ->
        config.getBoolean(ConfigConstants.CONFIG_REMOTE_SECTION, remote, "promisor", false)
    }

    return (listOfNotNull(partialClone) + promisors).distinct()
}

/** This exception as the object that JGit didn't find, when it is one, as JGit wraps it in its commands. */
internal fun Throwable.missingObjectError(): MissingObjectError? {
    val missing = generateSequence(this) { it.cause }.filterIsInstance<MissingObjectException>().firstOrNull()
        ?: return null

    return MissingObjectError(missing.objectId.name, missing.message.orEmpty())
}

/** Both versions of a commit's file, which its diff reads. Uncommitted files are in the clone. */
internal fun DiffType.blobIds(): List<ObjectId> = when (this) {
    // Fork-only: the new side of a comparison with the working tree is a file on disk, which has no blob
    is DiffType.CommitDiff -> listOfNotNull(diffEntry.oldId, diffEntry.newId.takeIf { !readsWorkingTree })
        .filter { it.isComplete }
        .map { it.toObjectId() }
        .filter { it != ObjectId.zeroId() }

    is DiffType.UncommittedDiff -> emptyList()
}

/**
 * The versions of [path] that blame reads: the file in each commit that changed it, from HEAD back, and where it was
 * added, the files that commit added or deleted, among which blame looks for where it was renamed from. Older names
 * are downloaded as blame reaches them.
 */
internal fun fileVersions(repository: Repository, path: String): List<ObjectId> {
    val head = repository.resolve(Constants.HEAD) ?: return emptyList()
    val versions = mutableListOf<ObjectId>()

    RevWalk(repository).use { walk ->
        walk.markStart(walk.parseCommit(head))
        walk.treeFilter = AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF)

        for (commit in walk) {
            TreeWalk.forPath(walk.objectReader, path, commit.tree)?.use { versions += it.getObjectId(0) }

            val parent = commit.parents.firstOrNull()?.let { walk.parseCommit(it) } ?: continue
            val inParent = TreeWalk.forPath(walk.objectReader, path, parent.tree)?.use { true } ?: false

            if (!inParent) {
                versions += blobsThatDiffer(repository, listOf(parent.tree, commit.tree))
            }
        }
    }

    return versions.distinct()
}

/**
 * The blobs that [trees] have at the paths where they aren't all alike: what a checkout or a merge between them reads.
 * A null tree is the empty tree. Submodules aren't blobs.
 */
internal fun blobsThatDiffer(repository: Repository, trees: List<AnyObjectId?>): Set<ObjectId> {
    val blobs = mutableSetOf<ObjectId>()

    TreeWalk(repository).use { walk ->
        walk.isRecursive = true
        walk.filter = TreeFilter.ANY_DIFF

        for (tree in trees) {
            if (tree == null) walk.addTree(EmptyTreeIterator()) else walk.addTree(tree)
        }

        while (walk.next()) {
            for (index in 0 until walk.treeCount) {
                val mode = walk.getFileMode(index)

                if (mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE || mode == FileMode.SYMLINK) {
                    blobs += walk.getObjectId(index)
                }
            }
        }
    }

    return blobs
}

/** HEAD's tree, or null for a branch without commits. */
private fun RevWalk.headTree(repository: Repository): AnyObjectId? =
    repository.resolve(Constants.HEAD)?.let { parseTree(it) }
