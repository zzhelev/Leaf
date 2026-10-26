package dev.app.leaf.lfs

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dev.app.leaf.data.git.lfs.lfsStore
import org.eclipse.jgit.attributes.FilterCommand
import org.eclipse.jgit.lib.Repository
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@AssistedFactory
interface LfsCleanFilterFactory {
    fun create(repository: Repository, input: InputStream, output: OutputStream): LfsCleanFilter
}

/**
 * Leaf's clean filter, which JGit runs when it stages a file or compares it with the index. It cleans as git-lfs does
 * (fork-only, `LfsStore.clean`): an empty file stays empty, a pointer stays as it is, anything else goes into the
 * repository's LFS store, in the common git dir. The filter used to make a pointer to a pointer, so a working tree of
 * pointer files showed every one of them as modified once JGit compared their content.
 */
class LfsCleanFilter @AssistedInject constructor(
    @Assisted private val repository: Repository,
    @Assisted input: InputStream,
    @Assisted output: OutputStream,
) : FilterCommand(input, output) {
    @Throws(IOException::class)
    override fun run(): Int {
        try {
            repository.lfsStore().clean(`in`, out)
        } finally {
            `in`.close()
            out.close()
        }

        return -1
    }
}
