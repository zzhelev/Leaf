// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.index.UnsupportedIndex
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer

/**
 * Reading the extensions of index files that git 2.54 wrote: every index version (2, 3 with extended flags, 4 with
 * prefix-compressed paths), paths of every length, and split and sparse indexes, whose extensions come after the
 * entries, so they are found only when every entry was read right.
 */
class IndexExtensionsTest {
    @TempDir
    lateinit var tempDir: File

    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }

    /** A repository with paths of 1 to 40 characters, in folders and not, so that entries need every padding. */
    private val repository by lazy {
        File(tempDir, "repo").apply {
            mkdirs()
            git.run(this, "init")

            for (length in 1..40) {
                File(this, "f".repeat(length)).writeText("$length\n")
                File(this, "dir/" + "g".repeat(length)).apply { parentFile.mkdirs() }.writeText("$length\n")
            }

            git.run(this, "add", ".")
            git.run(this, "commit", "-m", "Initial commit")
        }
    }

    private val indexFile get() = File(repository, ".git/index")

    @Test
    fun `an index that JGit reads has no extension that readers must know`() {
        val extensions = indexExtensions(indexFile)

        assertTrue("TREE" in extensions, "$extensions")
        assertEquals(null, unsupportedIndex())
    }

    @Test
    fun `a split index has the link extension, in versions 2 and 4`() {
        // Version 3 only when an entry needs it, which git otherwise writes as 2: see the next test
        for (version in listOf("2", "4")) {
            git.run(repository, "update-index", "--no-split-index")
            git.run(repository, "update-index", "--index-version", version)
            git.run(repository, "update-index", "--split-index")

            assertEquals(version.toInt(), indexVersion(), "the index git wrote")
            assertTrue("link" in indexExtensions(indexFile), "version $version: ${indexExtensions(indexFile)}")
            assertEquals(UnsupportedIndex.Split(repository.absolutePath, false), unsupportedIndex())
        }
    }

    @Test
    fun `entries with extended flags are read past`() {
        // An intent-to-add entry needs the extended flags, so git writes version 3. Added after the split, it's in the
        // index file rather than in the shared index.
        git.run(repository, "update-index", "--split-index")
        File(repository, "new.txt").writeText("new\n")
        git.run(repository, "add", "--intent-to-add", "new.txt")

        assertEquals(3, indexVersion())
        assertTrue("link" in indexExtensions(indexFile), "${indexExtensions(indexFile)}")
    }

    @Test
    fun `a sparse index has the sdir extension`() {
        git.run(repository, "sparse-checkout", "set", "--cone", "--sparse-index", "dir")

        assertTrue("sdir" in indexExtensions(indexFile), "${indexExtensions(indexFile)}")
        assertEquals(UnsupportedIndex.Sparse(repository.absolutePath), unsupportedIndex())
    }

    @Test
    fun `a sparse index in version 4 has the sdir extension too`() {
        git.run(repository, "update-index", "--index-version", "4")
        git.run(repository, "sparse-checkout", "set", "--cone", "--sparse-index", "dir")

        assertEquals(4, indexVersion())
        assertEquals(UnsupportedIndex.Sparse(repository.absolutePath), unsupportedIndex())
    }

    @Test
    fun `feature manyFiles writes version 4 without a checksum, which JGit reads`() {
        // Its settings apply to an index that git creates
        git.run(repository, "config", "feature.manyFiles", "true")
        indexFile.delete()
        git.run(repository, "reset")

        assertEquals(4, indexVersion())
        assertFalse(indexExtensions(indexFile).any { !it.first().isUpperCase() }, "${indexExtensions(indexFile)}")
        assertEquals(null, unsupportedIndex())
    }

    @Test
    fun `an extension that JGit doesn't know is named`() {
        withExtension("abcd", byteArrayOf(1, 2, 3))

        assertEquals(UnsupportedIndex.UnknownExtension(repository.absolutePath, "abcd"), unsupportedIndex())
    }

    @Test
    fun `optional extensions, uppercase, are left to JGit, which skips them`() {
        withExtension("ZZZZ", byteArrayOf(1, 2, 3))

        assertTrue("ZZZZ" in indexExtensions(indexFile))
        assertEquals(null, unsupportedIndex())
    }

    @Test
    fun `no index, an empty one, or a damaged one has no extensions`() {
        val fresh = File(tempDir, "fresh").apply { mkdirs() }
        git.run(fresh, "init")

        assertEquals(emptyList<String>(), indexExtensions(File(fresh, ".git/index")))

        val damaged = File(tempDir, "damaged-index")
        for (bytes in listOf(ByteArray(0), "DIRC".toByteArray(), indexFile.readBytes().copyOf(100), ByteArray(64))) {
            damaged.writeBytes(bytes)
            assertEquals(emptyList<String>(), indexExtensions(damaged))
        }
    }

    private fun unsupportedIndex(): UnsupportedIndex? =
        FileRepositoryBuilder.create(File(repository, ".git")).use { unsupportedIndex(it) }

    private fun indexVersion(): Int = ByteBuffer.wrap(indexFile.readBytes(), 4, 4).int

    /** Adds an extension to the index, before its checksum, which git would then refuse but this doesn't check. */
    private fun withExtension(signature: String, data: ByteArray) {
        val bytes = indexFile.readBytes()
        val content = bytes.copyOf(bytes.size - 20)
        val extension = ByteBuffer.allocate(8 + data.size).put(signature.toByteArray()).putInt(data.size).put(data)

        indexFile.writeBytes(content + extension.array() + bytes.copyOfRange(bytes.size - 20, bytes.size))
    }
}
