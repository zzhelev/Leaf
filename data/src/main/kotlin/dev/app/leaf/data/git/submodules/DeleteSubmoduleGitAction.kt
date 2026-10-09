package dev.app.leaf.data.git.submodules

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IDeleteSubmoduleGitAction
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FileUtils
import java.io.File
import javax.inject.Inject

private const val TAG = "DeleteSubmoduleGitAction"

class DeleteSubmoduleGitAction @Inject constructor(
    private val jgit: JGit,
) : IDeleteSubmoduleGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        path: String,
    ) = jgit.provide(repositoryPath) { git ->
        git.rm().addFilepattern(path).call()

        val repository = git.repository
        val gitModules = File(repository.workTree, ".gitmodules")

        if (gitModules.exists() && gitModules.isFile) {
            val config = FileBasedConfig(gitModules, repository.fs)

            config.load()
            config.unsetSection("submodule", path)
            config.save()
        }

        val moduleDir = File(repository.directory, "modules/$path")
        val workspace = File(repository.workTree, path)

        // JGit's delete doesn't follow symbolic links. Kotlin's deleteRecursively does, and would empty the folder that
        // a link in the submodule points to, such as a link to a shared hooks folder in its git dir.
        FileUtils.delete(moduleDir, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
        FileUtils.delete(workspace, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
    }
}
