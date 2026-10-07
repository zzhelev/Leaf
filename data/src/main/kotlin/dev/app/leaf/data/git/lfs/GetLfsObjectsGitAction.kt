package dev.app.leaf.data.git.lfs

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IGetLfsObjectsGitAction
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.repositories.LfsRepository
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

class GetLfsObjectsGitAction @Inject constructor(
    private val lfsRepository: LfsRepository,
    private val provideLfsCredentialsGitAction: ProvideLfsCredentialsGitAction,
) : IGetLfsObjectsGitAction {
    override suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        operationType: OperationType,
        branch: String,
        lfsObjectBatches: List<LfsObjectBatch>,
        headers: Map<String, String>,
    ): Either<LfsObjects, LfsError> {
        return if (headers.containsKey(NetworkConstants.AUTH_HEADER)) {
            lfsRepository.getLfsObjects(
                lfsServer.url,
                operationType = operationType,
                branch = branch,
                objects = lfsObjectBatches,
                headers = headers,
                username = null,
                password = null,
            )
        } else {
            provideLfsCredentialsGitAction(repository, lfsServer) { user, password ->
                lfsRepository.getLfsObjects(
                    lfsServer.url,
                    operationType = operationType,
                    branch = branch,
                    objects = lfsObjectBatches,
                    headers = headers,
                    username = user,
                    password = password,
                )
            }
        }
    }
}