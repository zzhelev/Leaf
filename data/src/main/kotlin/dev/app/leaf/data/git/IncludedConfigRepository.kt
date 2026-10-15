// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.config.IncludedConfig
import dev.app.leaf.data.git.signers.configText
import kotlinx.coroutines.sync.Mutex
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.errors.ConfigInvalidException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.FileSnapshot
import org.eclipse.jgit.lib.BaseRepositoryBuilder
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.lib.RepositoryCache
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.io.IOException

private const val TAG = "IncludedConfigRepository"

/**
 * A repository whose config has, under the repository's own file, what git reads through `includeIf` and JGit
 * doesn't ([IncludedConfig]): JGit's commands then see the identity, signing settings, hooks folder and other
 * settings that git sees (Gitnuro#186). Without such entries it reads as JGit's own repository does.
 *
 * JGit's [FileRepository] puts its config on the user's config when it's created, so this returns a config of its own
 * for the same file, on [included], which is on the user's config. JGit's constructor reads the config before this
 * class is set up, and gets JGit's own then. Its config object is used afterwards only by JGit's conversions of the
 * ref storage, which Leaf never runs, and for the object database's pack settings.
 */
class IncludedConfigRepository(options: BaseRepositoryBuilder<*, *>) : FileRepository(options) {
    /** What git reads through `includeIf`, on the user's and the system's config. */
    private val included = Config(SystemReader.getInstance().userConfig)

    /** The repository's own config, on [included]. Null while JGit's constructor runs. */
    private var ownConfig: FileBasedConfig? = null

    /** The files that [included] comes from, or that may change what git reads, as they were when it was read. */
    @Volatile
    private var snapshots: List<Pair<File, FileSnapshot>>? = null

    /** Lets one refresh at a time read git's config ([JGit.provide]). */
    val includedConfigLock = Mutex()

    init {
        ownConfig = FileBasedConfig(included, File(commonDirectory, Constants.CONFIG), fs).apply {
            load()
            addChangeListener { fireEvent(it) }
        }
    }

    override fun getConfig(): FileBasedConfig {
        val config = ownConfig ?: return super.getConfig()

        try {
            // Reloads the user's and the system's config when they changed, as JGit's own getConfig does
            SystemReader.getInstance().userConfig

            if (config.isOutdated) {
                config.load()
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        } catch (e: ConfigInvalidException) {
            throw RuntimeException(e)
        }

        return config
    }

    /** Whether git's config was never read, or one of the files it depends on changed since. */
    fun isIncludedConfigStale(): Boolean {
        return snapshots?.any { (file, snapshot) -> snapshot.isModified(file) } ?: true
    }

    /** Puts [includedConfig] under the repository's own config, and notes its files as they are now. */
    fun useIncludedConfig(includedConfig: IncludedConfig) {
        snapshots = includedConfig.files.map { it to FileSnapshot.save(it) }

        try {
            included.fromText(configText(includedConfig.entries))
        } catch (e: ConfigInvalidException) {
            printError(TAG, "Reading the config as JGit does, as JGit couldn't read git's: ${e.message}")
            included.fromText("")
        }
    }
}

/**
 * Opens the repository at [gitDir] (a git dir, or a working tree with its `.git`) as [Git.open] does, as an
 * [IncludedConfigRepository]. Closing the [Git] closes the repository.
 */
fun openWithIncludedConfig(gitDir: File, fs: FS): Git {
    val builder = RepositoryBuilder()
        .setFS(fs)
        .setGitDir(RepositoryCache.FileKey.lenient(gitDir, fs).file)
        .setMustExist(true)
        .setup()

    val repository = IncludedConfigRepository(builder)

    if (!repository.objectDatabase.exists()) {
        repository.close()
        throw RepositoryNotFoundException(builder.gitDir)
    }

    return Git(repository, true)
}
