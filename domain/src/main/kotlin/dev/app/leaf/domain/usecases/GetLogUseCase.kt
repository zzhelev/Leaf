package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.GraphLogGenerator
import dev.app.leaf.domain.Pagination
import dev.app.leaf.domain.errors.*
import dev.app.leaf.domain.interfaces.*
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
    private val getStatusGitAction: IGetStatusGitAction,
    private val getCurrentBranchAction: IGetCurrentBranchGitAction,
    private val graphLogGenerator: GraphLogGenerator,
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    suspend operator fun invoke(repository: String, pagination: Pagination) = either<GraphCommits, AppError> {
        val status = getStatusGitAction(repository).bind()
        val currentBranch = getCurrentBranchAction(repository).bind()

        val hasUncommittedChanges = status.staged.isNotEmpty() || status.unstaged.isNotEmpty()

        val branches = getBranchesGitAction(repository).okOrNull().orEmpty()
        val tags = getTagsGitAction(repository).okOrNull().orEmpty()
        val remoteBranches = getRemotesUseCase().okOrNull().orEmpty().flatMap { it.branchesList }
        val stashes = getStashListGitAction(repository).okOrNull().orEmpty().map { it.hash }

        val hashes = branches.map { it.hash } +
                stashes +
                tags.map { it.commitHash } +
                remoteBranches.map { it.hash }

        val r = graphLogGenerator.generate(
            repository,
            maxCommits = max(repositoryDataRepository.maxCommitsToLoadLimit, INITIAL_COMMITS_LOAD),
            hashes,
            HashSet(stashes),
            forcedFirstLaneBranchHash = if (hasUncommittedChanges) currentBranch?.hash else null,
            pagination = pagination,
        )

        Either.Ok(r)
    }
}