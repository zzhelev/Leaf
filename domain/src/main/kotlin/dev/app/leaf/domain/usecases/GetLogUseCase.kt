package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.errors.*
import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.interfaces.*
import dev.app.leaf.domain.log.LogBranchFilter
import dev.app.leaf.domain.log.NO_LOG_BRANCH_FILTER
import dev.app.leaf.domain.log.shows
import dev.app.leaf.domain.log.showsWorktreeHead
import dev.app.leaf.domain.log.stashBranchOf
import dev.app.leaf.domain.models.GraphCommits
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.dataOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import kotlin.math.max

private const val INITIAL_COMMITS_LOAD = 2000

class GetLogUseCase @Inject constructor(
    private val getBranchesGitAction: IGetBranchesGitAction,
    private val getTagsGitAction: IGetTagsGitAction,
    private val getRemotesUseCase: GetRemotesUseCase,
    private val getStashListGitAction: IGetStashListGitAction,
    private val getOtherWorktreeHeadsGitAction: IGetOtherWorktreeHeadsGitAction,
    private val getStatusGitAction: IGetStatusGitAction,
    private val getCurrentBranchAction: IGetCurrentBranchGitAction,
    private val getLogBranchFilterGitAction: IGetLogBranchFilterGitAction,
    private val getCommitsReachableGitAction: IGetCommitsReachableGitAction,
    private val graphLogGenerator: GraphLogGenerator,
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    suspend operator fun invoke(repository: String, pagination: Pagination) = either<GraphCommits, AppError> {
        // Fork-only: with an index that JGit can't read, the log shows without the uncommitted changes, as the strip
        // that says why does
        val statusResult = getStatusGitAction(repository)
        val status = if ((statusResult as? Either.Err)?.error is UnsupportedIndexError) null else statusResult.bind()
        val currentBranch = getCurrentBranchAction(repository).bind()

        val hasUncommittedChanges = status != null && (status.staged.isNotEmpty() || status.unstaged.isNotEmpty())

        // Fork-only: a page more of the log keeps the branches that the log was walked from (upstream issue 207)
        val start = logStart(repository, (pagination as? Pagination.Paginated)?.data?.branchFilter)

        val r = graphLogGenerator.generate(
            repository,
            maxCommits = max(repositoryDataRepository.maxCommitsToLoadLimit, INITIAL_COMMITS_LOAD),
            start.commits,
            HashSet(start.stashes),
            forcedFirstLaneBranchHash = if (hasUncommittedChanges) currentBranch?.hash else null,
            pagination = pagination,
        )

        Either.Ok(r.copy(branchFilter = start.filter))
    }

    /**
     * What the log's walk starts from (fork-only: the log search walks from the same commits), and the stashes among
     * them. The refs' commits come first, then those that only another worktree's detached HEAD has.
     *
     * Under a branch filter (upstream issue 207, [filter], else the one saved for the repository), only the branches that
     * it shows, with the checked-out one always, and tags only when it shows every branch but some. A stash shows when it
     * was made on a shown branch, and another worktree's detached HEAD while it rebases or bisects one: each would bring
     * the history of a hidden branch along.
     */
    suspend fun logStart(repository: String, filter: LogBranchFilter? = null): LogStart {
        val branchFilter = filter ?: getLogBranchFilterGitAction(repository).okOrNull() ?: NO_LOG_BRANCH_FILTER

        if (branchFilter != NO_LOG_BRANCH_FILTER) {
            return filteredLogStart(repository, branchFilter)
        }

        val branches = getBranchesGitAction(repository).okOrNull().orEmpty()
        val tags = getTagsGitAction(repository).okOrNull().orEmpty()
        val remoteBranches = getRemotesUseCase().okOrNull().orEmpty().flatMap { it.branchesList }
        val stashes = getStashListGitAction(repository).okOrNull().orEmpty().map { it.hash }

        val refHashes = branches.map { it.hash } +
                stashes +
                tags.map { it.commitHash } +
                remoteBranches.map { it.hash }

        // Fork-only: a commit that only another worktree's detached HEAD has (an agent's) is no ref's. A failure leaves
        // the log as it was.
        val otherWorktreeHeads = getOtherWorktreeHeadsGitAction(repository).okOrNull().orEmpty()
            .filter { it !in refHashes }
        val hashes = refHashes + otherWorktreeHeads

        return LogStart(hashes, stashes)
    }

    private suspend fun filteredLogStart(repository: String, filter: LogBranchFilter): LogStart {
        val currentBranch = getCurrentBranchAction(repository).okOrNull()
        val allBranches = getBranchesGitAction(repository).okOrNull().orEmpty()
        val branches = allBranches.filter { filter.shows(it, currentBranch) }
        val remoteBranches = getRemotesUseCase().okOrNull().orEmpty().flatMap { it.branchesList }
            .filter { filter.shows(it, currentBranch) }
        val tags = if (filter is LogBranchFilter.All) getTagsGitAction(repository).okOrNull().orEmpty() else emptyList()
        val shownNames = (branches + remoteBranches).mapTo(HashSet()) { it.name }

        val refHashes = branches.map { it.hash } + tags.map { it.commitHash } + remoteBranches.map { it.hash }
        val otherWorktreeHeads = getOtherWorktreeHeadsGitAction.withBranches(repository).okOrNull().orEmpty()
            .filter { filter.showsWorktreeHead(it.branch, shownNames) }
            .map { it.commit }
            .filter { it !in refHashes }
        val hashes = refHashes + otherWorktreeHeads

        // A stash that names the branch it was made on goes with that branch. One that names none, as a stash with a
        // typed message, shows when the commit it was made on is in the shown history, or, as a detached HEAD, when
        // every branch but some shows.
        val localBranches = allBranches.filter { it.isLocal }.associateBy { it.simpleName }
        val stashes = getStashListGitAction(repository).okOrNull().orEmpty()
        val unnamed = stashes.filter { stash -> stashBranchOf(stash.message)?.let(localBranches::get) == null }
        val unnamedBases = unnamed.mapNotNull { it.parentsHashes.firstOrNull() }.distinct()
        val shownBases = if (filter is LogBranchFilter.All || unnamedBases.isEmpty() || hashes.isEmpty()) {
            emptySet()
        } else {
            getCommitsReachableGitAction(repository, hashes, unnamedBases).okOrNull().orEmpty()
        }

        val shownStashes = stashes.filter { stash ->
            val branch = stashBranchOf(stash.message)?.let(localBranches::get)

            when {
                branch != null -> filter.shows(branch, currentBranch)
                filter is LogBranchFilter.All -> true
                else -> stash.parentsHashes.firstOrNull() in shownBases
            }
        }.map { it.hash }

        return LogStart(hashes + shownStashes, shownStashes, filter)
    }
}

/** The commits that the log's walk starts from, which of them are stashes, and the branch filter they come from. */
data class LogStart(
    val commits: List<String>,
    val stashes: List<String>,
    val filter: LogBranchFilter = NO_LOG_BRANCH_FILTER,
)
