package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.lfs.LfsObject
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lib.Repository

interface IDownloadLfsObjectGitAction {
    suspend operator fun invoke(
        repository: Repository,
        lfsServerUrl: String,
        lfsObject: LfsObject,
        oid: AnyLongObjectId,
    )
}