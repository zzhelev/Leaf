// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.author

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.eclipse.jgit.api.CommitCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.util.RawParseUtils
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "GitIdentities"
private val VAR_TIMEOUT = 10.seconds

/** Who git commits as: its author and committer, each null when git has none, rather than one it would make up. */
data class CommitIdentities(val author: Identity?, val committer: Identity?)

/**
 * Reads who git commits as in a repository, with `git var GIT_AUTHOR_IDENT` and `git var GIT_COMMITTER_IDENT`. JGit
 * reads `user.name` and `user.email` only, and never from the files that `includeIf` names, so an identity set for
 * some folders, such as a work identity, didn't apply to Leaf's commits (Gitnuro#186). git's answer also has
 * `author.*` and `committer.*`, and `GIT_AUTHOR_NAME` and the like from the login shell, as in a terminal.
 *
 * `user.useConfigOnly` keeps git from making an identity up from the account and the host's name, which `git commit`
 * does with a warning: Leaf asks for one instead (`CommitAuthorDialog`).
 */
class GitIdentities @Inject constructor(
    private val gitCli: GitCli,
) {
    /** Who git commits as in the repository whose git dir is [gitDir], or null if git couldn't be run. */
    suspend fun of(gitDir: File): CommitIdentities? = coroutineScope {
        val author = async { identity(gitDir, "GIT_AUTHOR_IDENT") }
        val committer = async { identity(gitDir, "GIT_COMMITTER_IDENT") }

        val authorResult = author.await()
        val committerResult = committer.await()

        if (authorResult is Either.Ok && committerResult is Either.Ok) {
            CommitIdentities(authorResult.value, committerResult.value)
        } else {
            val error = (authorResult as? Either.Err)?.error ?: (committerResult as? Either.Err)?.error
            printError(TAG, "Using JGit's identity, as git couldn't be run: $error")
            null
        }
    }

    private suspend fun identity(gitDir: File, variable: String): Either<Identity?, GitCliError> {
        val args = listOf("--git-dir=${gitDir.absolutePath}", "-c", "user.useConfigOnly=true", "var", variable)

        return when (val result = gitCli.run(gitDir, args, VAR_TIMEOUT)) {
            is Either.Ok -> Either.Ok(parseIdentity(result.value))
            // git has no name or email that it would commit with, or an empty one, so it refuses
            is Either.Err -> if (result.error is GitCliError.CommandFailed) Either.Ok(null) else result
        }
    }
}

/** The name and email in an identity that `git var` printed: `Name <email> 1792057724 +0300`. */
internal fun parseIdentity(output: String): Identity? {
    val ident = RawParseUtils.parsePersonIdent(output.trim()) ?: return null

    return Identity(ident.name, ident.emailAddress)
}

/**
 * Commits as [identities] say: with git's committer, and git's author unless the commit is amended, as `git commit
 * --amend` keeps the author. Where git has none, JGit's own stays. Both get the same time, as with JGit's.
 */
internal fun CommitCommand.withIdentities(identities: CommitIdentities?, amend: Boolean): CommitCommand {
    val committer = identities?.committer?.toPersonIdent()
    val author = identities?.author?.takeIf { !amend }?.toPersonIdent()?.let { author ->
        committer?.let { PersonIdent(author, it.whenAsInstant, it.zoneId) } ?: author
    }

    committer?.let { setCommitter(it) }
    author?.let { setAuthor(it) }

    return this
}

/** This identity now, in the time zone that JGit uses. */
internal fun Identity.toPersonIdent() = PersonIdent(name.orEmpty(), email.orEmpty())
