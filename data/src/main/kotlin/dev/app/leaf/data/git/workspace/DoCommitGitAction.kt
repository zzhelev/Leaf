package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.use
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.author.withIdentities
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.signers.GitSigningConfigs
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.HookRejectionError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IDoCommitGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import org.eclipse.jgit.api.errors.AbortedByHookException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import javax.inject.Inject

private const val TAG = "DoCommitGitAction"

class DoCommitGitAction @Inject constructor(
    private val getRepositoryStateGitAction: GetRepositoryStateGitAction,
    private val commitMapper: JGitCommitMapper,
    private val identityMapper: JGitIdentityMapper,
    private val jgit: JGit,
    private val gitIdentities: GitIdentities,
    private val gitSigningConfigs: GitSigningConfigs,
) : IDoCommitGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        message: String,
        amend: Boolean,
        author: Identity?,
    ): Either<Commit, GitError> = jgit.provide(
        repositoryPath,
        errorHandle = { ex ->
            if (ex is AbortedByHookException) {
//                val out = output.toString(Charsets.UTF_8)
//                printLog(TAG, out)

                // TODO Do we need to read the output as it was done before the refactor?
                HookRejectionError(ex.hookStdErr)
            } else {
                GenericError(ex.message.orEmpty())
            }
        }
    ) { git ->
        val state = getRepositoryStateGitAction(repositoryPath).bind()
        val isMerging = state.isMerging
        val output = ByteArrayOutputStream()
        val printStream = PrintStream(output, true, Charsets.UTF_8)
        // Fork-only: who git commits as and how it signs, which JGit can't tell when includeIf sets it
        val identities = if (author == null) gitIdentities.of(git.repository.directory) else null
        val gpgConfig = gitSigningConfigs.of(git.repository.directory)

        use(output, printStream) {
            val commit = git
                .commit()
                .setMessage(message)
                .setAllowEmpty(amend || isMerging) // Only allow empty commits when amending
                .setAmend(amend)
                .setHookErrorStream(printStream)
                .setHookOutputStream(printStream)
                .run {
                    if (author != null) {
                        // Fork-only: the identity given for this commit is its committer's too
                        identityMapper.toData(author).let { setAuthor(it).setCommitter(it) }
                    } else {
                        withIdentities(identities, amend)
                    }
                }
                .setGpgConfig(gpgConfig)
                .call()

            commitMapper.toDomain(commit)
        }
    }
}