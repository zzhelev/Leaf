package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ICreateTagGitAction
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

class CreateTagGitAction @Inject constructor(
    private val jgit: JGit,
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
                .call()

            Unit
        }
}
