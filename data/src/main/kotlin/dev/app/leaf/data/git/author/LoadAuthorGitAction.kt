package dev.app.leaf.data.git.author

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ILoadAuthorGitAction
import dev.app.leaf.domain.models.AuthorInfo
import dev.app.leaf.domain.models.Identity
import org.eclipse.jgit.storage.file.FileBasedConfig
import javax.inject.Inject

class LoadAuthorGitAction @Inject constructor(private val jgit: JGit) : ILoadAuthorGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        val config = git.repository.config
        val globalConfig = git.repository.config.baseConfig

        val repoConfig = FileBasedConfig((config as FileBasedConfig).file, git.repository.fs)
        repoConfig.load()

        val globalName = globalConfig.getString("user", null, "name")?.trim()
        val globalEmail = globalConfig.getString("user", null, "email")?.trim()

        val name = repoConfig.getString("user", null, "name")?.trim()
        val email = repoConfig.getString("user", null, "email")?.trim()

        AuthorInfo(
            Identity(globalName, globalEmail),
            Identity(name, email),
        )
    }
}