package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.flow.Flow
import java.io.File

interface ICloneRepositoryGitAction {
    operator fun invoke(directory: File, url: String, cloneSubmodules: Boolean): Flow<CloneState>
}