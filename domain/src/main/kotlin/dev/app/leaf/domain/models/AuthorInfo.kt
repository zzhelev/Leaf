package dev.app.leaf.domain.models

data class AuthorInfo(
    val globalIdentity: Identity,
    val repositoryIdentity: Identity,
    /**
     * Fork-only: the author that git commits as, which may come from a file that `includeIf` names, where JGit doesn't
     * look. Null when git has none, or couldn't be run.
     */
    val gitIdentity: Identity? = null,
) {
    fun identityToUse() = gitIdentity ?: Identity(
        repositoryIdentity.name ?: globalIdentity.name ?: "",
        repositoryIdentity.email ?: globalIdentity.email ?: "",
    )

    /** Fork-only: whether Leaf has to ask for an identity to commit with, as neither git nor JGit has one. */
    fun isIdentityMissing() = gitIdentity == null && (
        repositoryIdentity.name.isNullOrEmpty() && globalIdentity.name.isNullOrEmpty() ||
            repositoryIdentity.email.isNullOrEmpty() && globalIdentity.email.isNullOrEmpty()
        )
}


