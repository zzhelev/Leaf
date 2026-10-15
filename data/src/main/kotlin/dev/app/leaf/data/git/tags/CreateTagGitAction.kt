package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.author.toPersonIdent
import dev.app.leaf.data.git.signers.GitSigningConfigs
import dev.app.leaf.domain.interfaces.ICreateTagGitAction
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

class CreateTagGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitIdentities: GitIdentities,
    private val gitSigningConfigs: GitSigningConfigs,
) : ICreateTagGitAction {
    override suspend operator fun invoke(repositoryPath: String, tag: String, commit: Commit) =
        jgit.provide(repositoryPath) { git ->
            val commitId =
                ObjectId.fromString(commit.hash) // TODO Should this be used instead of "git.repository.resolve(revCommit.hash) ?: throw Exception("Commit ${revCommit.hash} not found")" used in other places?
            val commit: RevCommit? = RevWalk(git.repository).use { revWalk ->
                revWalk.parseCommit(commitId)
            }

            // JGit signs the tag as git does: when tag.gpgSign or tag.forceSignAnnotated is set, with the signer that
            // App.start registered for gpg.format, and with user.signingKey or else the tagger's identity
            git
                .tag()
                .setAnnotated(true)
                .setName(tag)
                .setObjectId(commit)
                // Fork-only: git's tagger (its committer) and signing settings, which includeIf may set
                .apply { gitIdentities.of(git.repository.directory)?.committer?.let { setTagger(it.toPersonIdent()) } }
                .setGpgConfig(gitSigningConfigs.of(git.repository.directory))
                .call()

            Unit
        }
}
