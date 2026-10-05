package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.use
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
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
                        setAuthor(identityMapper.toData(author))
                    } else {
                        this
                    }
                }
                .call()

            commitMapper.toDomain(commit)
        }
    }
}