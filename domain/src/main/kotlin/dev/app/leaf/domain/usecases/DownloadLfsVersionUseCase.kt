// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IDownloadLfsVersionGitAction
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.VersionPreview
import javax.inject.Inject

/**
 * Downloads the Git LFS object of one version of a conflicted file, from its choice card, and reads the version again
 * for the card. It runs without the processing screen, as a card shows its progress and its failure itself.
 */
class DownloadLfsVersionUseCase @Inject constructor(
    private val downloadLfsVersionGitAction: IDownloadLfsVersionGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    /** [obj] is the object of a pointer at [path]. [onProgress] gets the bytes received so far. */
    suspend operator fun invoke(
        path: String,
        obj: LfsObject,
        onProgress: (Long) -> Unit,
    ): Either<VersionPreview.Lfs, AppError> = useCaseExecutor.execute { repositoryPath ->
        downloadLfsVersionGitAction(repositoryPath, path, obj, onProgress)
    }
}
