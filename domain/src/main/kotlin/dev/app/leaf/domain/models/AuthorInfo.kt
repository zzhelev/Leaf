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
    /**
     * The identity that commits get: git's, else the repository's values over the user's. A value that neither sets is
     * null (fork-only), as an empty one, which git refuses too, so that the bottom bar can say it isn't set.
     */
    fun identityToUse() = gitIdentity ?: Identity(
        repositoryIdentity.name.ifSet() ?: globalIdentity.name.ifSet(),
        repositoryIdentity.email.ifSet() ?: globalIdentity.email.ifSet(),
    )

    /** Fork-only: whether Leaf has to ask for an identity to commit with, as neither git nor JGit has one. */
    fun isIdentityMissing() = gitIdentity == null && (
        repositoryIdentity.name.isNullOrEmpty() && globalIdentity.name.isNullOrEmpty() ||
            repositoryIdentity.email.isNullOrEmpty() && globalIdentity.email.isNullOrEmpty()
        )
}

private fun String?.ifSet(): String? = this?.takeIf { it.isNotEmpty() }
