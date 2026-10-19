package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.flow.Flow
import java.io.File

interface ICloneRepositoryGitAction {
    /**
     * Clones [url] into [directory]. [branch] is the branch or tag to check out, as `git clone --branch` takes it: a
     * tag leaves HEAD detached. [singleBranch] clones only that branch's history (or the remote's default branch
     * without [branch]), as `git clone --single-branch` does, so a later fetch brings only that branch too.
     */
    operator fun invoke(
        directory: File,
        url: String,
        cloneSubmodules: Boolean,
        branch: String? = null,
        singleBranch: Boolean = false,
    ): Flow<CloneState>
}