// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.commitmessage.CommitTemplate
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommitTemplateGitAction
import java.io.File
import javax.inject.Inject

private const val TAG = "GetCommitTemplate"

/** A template larger than this isn't one that a person wrote, and isn't read. */
private const val MAX_TEMPLATE_SIZE = 1024 * 1024

/**
 * Reads the file that `commit.template` names (fork-only), as `git commit` does: from the repository's config, which
 * has what the files that `includeIf` names set (`IncludedConfigRepository`), `~/` in the user's home, and a relative
 * path from the working tree's root, where git runs its commands. A bare repository, which git can't commit in, has
 * none. git refuses to commit when the file can't be read; Leaf logs it and offers no template.
 */
class GetCommitTemplateGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommitTemplateGitAction {
    override suspend fun invoke(repositoryPath: String): Either<CommitTemplate?, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val path = repository.config.getString("commit", null, "template")

            if (path.isNullOrEmpty() || repository.isBare) {
                null
            } else {
                commitTemplateAt(path, repository.workTree, repository.fs.userHome())
            }
        }
}

/** The template that [path] names, resolved as git resolves it from [workTree], with `~/` in [home]. */
internal fun commitTemplateAt(path: String, workTree: File, home: File?): CommitTemplate? {
    val file = when {
        path.startsWith("~/") -> home?.let { File(it, path.removePrefix("~/")) }
        // ~user/ and %(prefix)/, which git expands too, are rare enough to leave out
        path.startsWith("~") || path.startsWith("%(") -> null
        File(path).isAbsolute -> File(path)
        else -> File(workTree, path)
    }

    if (file == null || !file.isFile || file.length() > MAX_TEMPLATE_SIZE) {
        printError(TAG, "Offering no commit template, as commit.template names $path, which Leaf can't read")
        return null
    }

    return CommitTemplate(file.path, file.readText())
}
