package com.jetpackduba.gitnuro.data.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * Disables JGit's automatic gc by default, which JGit runs after merges, rebases, fetches and pushes.
 *
 * Unlike git, JGit's gc ignores the HEAD, index and reflogs of linked worktrees, so objects only referenced there
 * (a detached HEAD, staged changes) get pruned once they are older than gc.pruneExpire.
 *
 * The values sit below every config file (JGit, system, user, repository), so an explicit gc.auto or
 * gc.autoPackLimit still wins. Nothing is written to disk.
 */
class NoAutoGcSystemReader(delegate: SystemReader) : SystemReader.Delegate(delegate) {
    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig {
        val defaults = Config(parent).apply {
            setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTO, 0)
            setInt(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOPACKLIMIT, 0)
        }

        return super.openJGitConfig(defaults, fs)
    }
}

/**
 * Must be called before any repository is opened, as JGit repositories capture the config chain when created.
 */
fun disableJGitAutoGc() {
    SystemReader.setInstance(NoAutoGcSystemReader(SystemReader.getInstance()))
}
