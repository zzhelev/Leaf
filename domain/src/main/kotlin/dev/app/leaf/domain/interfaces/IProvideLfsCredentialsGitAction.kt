package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsServer
import org.eclipse.jgit.lib.Repository

interface IProvideLfsCredentialsGitAction {
    suspend operator fun <T> invoke(
        repository: Repository,
        lfsServer: LfsServer,
        callback: suspend (username: String?, password: String?) -> Either<T, LfsError>,
    ): Either<T, LfsError>
}