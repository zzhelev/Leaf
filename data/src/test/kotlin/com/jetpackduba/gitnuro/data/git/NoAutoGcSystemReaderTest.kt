package com.jetpackduba.gitnuro.data.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val GC = ConfigConstants.CONFIG_GC_SECTION
private const val AUTO = ConfigConstants.CONFIG_KEY_AUTO
private const val AUTO_PACK_LIMIT = ConfigConstants.CONFIG_KEY_AUTOPACKLIMIT

class NoAutoGcSystemReaderTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `auto gc is disabled when no config file sets it`() {
        SystemReader.setInstance(NoAutoGcSystemReader(isolatedSystemReader()))

        initRepository().use { repository ->
            assertEquals(0, repository.config.getInt(GC, AUTO, -1))
            assertEquals(0, repository.config.getInt(GC, AUTO_PACK_LIMIT, -1))
        }
    }

    @Test
    fun `explicit user config takes precedence`() {
        val isolatedReader = isolatedSystemReader()
        isolatedReader.userConfigFile.writeText("[gc]\n\tauto = 42\n\tautoPackLimit = 7\n")
        SystemReader.setInstance(NoAutoGcSystemReader(isolatedReader))

        initRepository().use { repository ->
            assertEquals(42, repository.config.getInt(GC, AUTO, -1))
            assertEquals(7, repository.config.getInt(GC, AUTO_PACK_LIMIT, -1))
        }
    }

    @Test
    fun `defaults are never written to config files`() {
        val isolatedReader = isolatedSystemReader()
        SystemReader.setInstance(NoAutoGcSystemReader(isolatedReader))
        val reader = SystemReader.getInstance()

        initRepository().use { repository ->
            reader.getJGitConfig().save()
            reader.getUserConfig().save()
            repository.config.save()

            val configFiles = listOf(
                isolatedReader.jGitConfigFile,
                isolatedReader.userConfigFile,
                File(repository.directory, Constants.CONFIG),
            )

            for (file in configFiles) {
                val config = FileBasedConfig(null, file, FS.DETECTED).apply { load() }
                assertTrue(config.getNames(GC).isEmpty(), "$file contains gc settings: ${file.readText()}")
            }
        }
    }

    @Test
    fun `auto gc does not run when the loose object limit is exceeded`() {
        SystemReader.setInstance(NoAutoGcSystemReader(isolatedSystemReader()))

        initRepositoryExceedingLooseObjectLimit().use { repository ->
            repository.autoGC(NullProgressMonitor.INSTANCE)

            assertEquals(0, packCount(repository))
        }
    }

    @Test
    fun `auto gc runs in the same scenario without NoAutoGcSystemReader`() {
        SystemReader.setInstance(isolatedSystemReader())

        initRepositoryExceedingLooseObjectLimit().use { repository ->
            repository.autoGC(NullProgressMonitor.INSTANCE)

            assertEquals(1, packCount(repository))
        }
    }

    private fun isolatedSystemReader() = IsolatedSystemReader(tempDir, originalReader)

    private fun initRepository(): Repository = Git.init()
        .setDirectory(File(tempDir, "repo"))
        .setInitialBranch("main")
        .call()
        .repository

    private fun initRepositoryExceedingLooseObjectLimit(): Repository {
        val repository = initRepository()

        repository.config.apply {
            setBoolean(GC, null, ConfigConstants.CONFIG_KEY_AUTODETACH, false)
            save()
        }

        val ident = PersonIdent("Gitnuro Test", "test@example.invalid")
        Git(repository).commit()
            .setMessage("Initial commit")
            .setAllowEmpty(true)
            .setSign(false)
            .setAuthor(ident)
            .setCommitter(ident)
            .call()

        // JGit estimates the loose object count from objects/17 and auto gc kicks in when that directory holds more
        // than (gc.auto + 255) / 256 objects, which is 27 with the default gc.auto of 6700.
        repository.newObjectInserter().use { inserter ->
            val formatter = ObjectInserter.Formatter()
            var inserted = 0
            var counter = 0

            while (inserted <= 27) {
                val content = "blob ${counter++}".toByteArray()

                if (formatter.idFor(Constants.OBJ_BLOB, content).name.startsWith("17")) {
                    inserter.insert(Constants.OBJ_BLOB, content)
                    inserted++
                }
            }

            inserter.flush()
        }

        return repository
    }

    private fun packCount(repository: Repository): Int {
        return File(repository.directory, "objects/pack")
            .listFiles { file -> file.name.endsWith(".pack") }
            ?.size ?: 0
    }
}
