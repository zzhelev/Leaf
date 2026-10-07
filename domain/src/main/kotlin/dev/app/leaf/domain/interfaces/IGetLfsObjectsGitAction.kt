package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import org.eclipse.jgit.lib.Repository

interface IGetLfsObjectsGitAction {
    suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        operationType: OperationType,
        branch: String,
        lfsObjectBatches: List<LfsObjectBatch>,
        headers: Map<String, String>,
    ): Either<LfsObjects, LfsError>
}