package dev.app.leaf.data.mappers

import dev.app.leaf.data.extensions.isLocal
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.lib.Ref
import javax.inject.Inject

class JGitBranchMapper @Inject constructor(): DataMapper<Branch?, Ref?> {
    override fun toData(value: Branch?): Nothing {
        throw NotImplementedError()
    }

    override fun toDomain(value: Ref?): Branch? {
        val value = value ?: return null

        return Branch(
            hash = value.objectId.name,
            name = value.name,
            isLocal = value.isLocal,
        )
    }
}