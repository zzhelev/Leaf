// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.errors.LargeObjectException
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.ObjectStream
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Where Leaf's built-in LFS keeps objects: in the common git dir, as git-lfs does, in every worktree. And its clean
 * filter, which must give what git-lfs's gives, byte for byte.
 */
@DisabledOnOs(OS.WINDOWS)
class LfsStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    private val git by lazy { TestGitCli(File(tempDir, "empty.gitconfig")) }

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the main worktree's objects are in its git dir`() {
        val repo = repository()

        Git.open(repo).use {
            assertEquals(File(repo, ".git/lfs").canonicalPath, it.repository.lfsStore().root.toFile().canonicalPath)
        }
    }

    @Test
    fun `a linked worktree's objects are in the common git dir, where git-lfs keeps them`() {
        val repo = repository()
        val worktree = linkedWorktree(repo)

        Git.open(worktree).use {
            val store = it.repository.lfsStore()

            assertEquals(File(repo, ".git/lfs").canonicalPath, store.root.toFile().canonicalPath)
            assertEquals(
                File(repo, ".git/lfs/objects/${objectPath(PHOTO_OID)}").canonicalPath,
                store.mediaFile(LongObjectId.fromString(PHOTO_OID)).toFile().canonicalPath,
            )
        }
    }

    @Test
    fun `a bare repository's objects are in it`() {
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "init", "-q", "--bare", bare.path)

        Git.open(bare).use {
            assertEquals(File(bare, "lfs").canonicalPath, it.repository.lfsStore().root.toFile().canonicalPath)
        }
    }

    @Test
    fun `an object that an older Leaf put in a linked worktree's own git dir moves to the common git dir`() {
        val repo = repository()
        val worktree = linkedWorktree(repo)
        val oid = LongObjectId.fromString(PHOTO_OID)
        val old = File(repo, ".git/worktrees/wt/lfs/objects/${objectPath(PHOTO_OID)}").apply {
            parentFile.mkdirs()
            writeText(PHOTO)
        }

        Git.open(worktree).use {
            val store = it.repository.lfsStore()
            val found = store.find(oid, PHOTO.length.toLong())

            assertEquals(store.mediaFile(oid), found)
            assertEquals(PHOTO, found?.toFile()?.readText())
            assertFalse(old.exists())
        }
    }

    @Test
    fun `an object of another size than the pointer's isn't found`() {
        val repo = repository()

        Git.open(repo).use {
            val store = it.repository.lfsStore()
            val oid = LongObjectId.fromString(PHOTO_OID)
            store.mediaFile(oid).toFile().apply { parentFile.mkdirs() }.writeText("pho")

            assertNull(store.find(oid, PHOTO.length.toLong()))
            assertEquals(store.mediaFile(oid), store.find(oid))
        }
    }

    @Test
    fun `clean of a file gives git-lfs's pointer, and stores the object where git-lfs does`() {
        val gitLfs = realGitLfs()
        val leaf = repository("leaf")
        val gitLfsRepo = repository("git-lfs")
        val content = "binary \u0000 content\r\nwith all sorts of bytes".toByteArray() + ByteArray(70_000) { it.toByte() }

        val leafPointer = Git.open(leaf).use { clean(it.repository.lfsStore(), content) }
        val gitLfsPointer = runGitLfsClean(gitLfs, gitLfsRepo, content)

        assertEquals(gitLfsPointer.decodeToString(), leafPointer.decodeToString())

        val path = ".git/lfs/objects/${objectPath(sha256(content))}"
        assertArrayEquals(File(gitLfsRepo, path).readBytes(), File(leaf, path).readBytes())
    }

    @Test
    fun `clean leaves an empty file empty, and a pointer as it is, as git-lfs does`() {
        val gitLfs = realGitLfs()
        val repo = repository()
        val gitLfsRepo = repository("git-lfs")
        val pointer = lfsPointerText(LongObjectId.fromString(PHOTO_OID), 5).toByteArray()

        Git.open(repo).use {
            val store = it.repository.lfsStore()

            assertArrayEquals(runGitLfsClean(gitLfs, gitLfsRepo, ByteArray(0)), clean(store, ByteArray(0)))
            assertArrayEquals(runGitLfsClean(gitLfs, gitLfsRepo, pointer), clean(store, pointer))
            assertArrayEquals(pointer, clean(store, pointer))
            assertFalse(Files.exists(store.objects))
        }
    }

    @Test
    fun `clean of a pointer that a merge's markers broke stores it, as git-lfs does`() {
        val gitLfs = realGitLfs()
        val repo = repository()
        val gitLfsRepo = repository("git-lfs")
        val conflicted = (
            "version https://git-lfs.github.com/spec/v1\n<<<<<<< HEAD\noid sha256:$PHOTO_OID\nsize 5\n=======\n" +
                "oid sha256:${sha256("other")}\nsize 5\n>>>>>>> feature\n"
            ).toByteArray()

        Git.open(repo).use {
            val leafPointer = clean(it.repository.lfsStore(), conflicted)

            assertArrayEquals(runGitLfsClean(gitLfs, gitLfsRepo, conflicted), leafPointer)
            assertTrue(leafPointer.decodeToString().contains(sha256(conflicted)))
        }
    }

    @Test
    fun `clean puts back an object of the wrong size, which a copy that broke off left`() {
        val repo = repository()

        Git.open(repo).use {
            val store = it.repository.lfsStore()
            val media = store.mediaFile(LongObjectId.fromString(PHOTO_OID)).toFile()
            media.parentFile.mkdirs()
            media.writeText("ph")

            clean(store, PHOTO.toByteArray())

            assertEquals(PHOTO, media.readText())
            assertTrue(Files.list(store.tmp).use { files -> files.count() } == 0L)
        }
    }

    @Test
    fun `clean of a large file goes through the store's tmp, and of a stored one changes nothing`() {
        val repo = repository()

        Git.open(repo).use {
            val store = it.repository.lfsStore()
            val large = ByteArray(5 * 1024 * 1024) { index -> (index % 251).toByte() }
            val largeOid = LongObjectId.fromString(sha256(large))

            assertArrayEquals(lfsPointerText(largeOid, large.size.toLong()).toByteArray(), clean(store, large))
            assertArrayEquals(large, store.mediaFile(largeOid).toFile().readBytes())

            val photo = store.mediaFile(LongObjectId.fromString(PHOTO_OID)).toFile()
            clean(store, PHOTO.toByteArray())
            photo.setLastModified(1_000_000_000_000)
            clean(store, PHOTO.toByteArray())
            clean(store, large)

            assertEquals(1_000_000_000_000, photo.lastModified())
            assertEquals(0L, Files.list(store.tmp).use { files -> files.count() })
        }
    }

    @Test
    fun `content for diffs is the stored object, or the pointer when the store lacks it, and nothing downloads`() {
        val repo = repository()
        val pointer = lfsPointerText(LongObjectId.fromString(PHOTO_OID), PHOTO.length.toLong()).toByteArray()

        Git.open(repo).use {
            val repository = it.repository
            val loader = ObjectLoader.SmallObject(org.eclipse.jgit.lib.Constants.OBJ_BLOB, pointer)
            val text = ObjectLoader.SmallObject(org.eclipse.jgit.lib.Constants.OBJ_BLOB, "plain".toByteArray())

            assertSame(loader, repository.lfsContentOf(loader))
            assertSame(text, repository.lfsContentOf(text))

            val media = repository.lfsStore().mediaFile(LongObjectId.fromString(PHOTO_OID)).toFile()
                .apply { parentFile.mkdirs() }

            // A copy that broke off isn't the object
            media.writeText("pho")
            assertSame(loader, repository.lfsContentOf(loader))

            media.writeText(PHOTO)
            assertEquals(PHOTO, repository.lfsContentOf(loader).bytes.decodeToString())
        }
    }

    @Test
    fun `content for diffs reads a working tree file's loader as a stream, as it has no cached bytes`() {
        val repo = repository()
        val pointer = lfsPointerText(LongObjectId.fromString(PHOTO_OID), PHOTO.length.toLong()).toByteArray()

        Git.open(repo).use {
            val repository = it.repository
            repository.lfsStore().mediaFile(LongObjectId.fromString(PHOTO_OID)).toFile()
                .apply { parentFile.mkdirs() }
                .writeText(PHOTO)

            // As JGit's ContentSource gives a working tree file: large whatever its size
            val workingTreeFile = object : ObjectLoader() {
                override fun getType() = org.eclipse.jgit.lib.Constants.OBJ_BLOB
                override fun getSize() = pointer.size.toLong()
                override fun isLarge() = true
                override fun getCachedBytes(): ByteArray = throw LargeObjectException()
                override fun openStream() = ObjectStream.SmallStream(type, pointer)
            }

            assertEquals(PHOTO, repository.lfsContentOf(workingTreeFile).bytes.decodeToString())
        }
    }

    @Test
    fun `what counts as a pointer is what git-lfs counts, clean for clean`() {
        val gitLfs = realGitLfs()
        val repo = repository()
        val gitLfsRepo = repository("git-lfs")
        val version = "version https://git-lfs.github.com/spec/v1"
        val oid = "oid sha256:$PHOTO_OID"
        val extension = "ext-0-foo sha256:${sha256("ext")}"
        val cases = mapOf(
            "canonical" to "$version\n$oid\nsize 5\n",
            "CRLF" to "$version\r\n$oid\r\nsize 5\r\n",
            "no last line ending" to "$version\n$oid\nsize 5",
            "blank lines" to "$version\n\n$oid\n\nsize 5\n",
            "an extension" to "$version\n$extension\n$oid\nsize 5\n",
            "an older version" to "version https://hawser.github.com/spec/v1\n$oid\nsize 5\n",
            "another version" to "version https://example.com/spec/v9\n$oid\nsize 5\n",
            "oid before version" to "$oid\n$version\nsize 5\n",
            "a line after size" to "$version\n$oid\nsize 5\nextra line\n",
            "a line after size that starts with a space" to "$version\n$oid\nsize 5\n extra\n",
            "a comment" to "# comment\n$version\n$oid\nsize 5\n",
            "conflict markers" to "$version\n<<<<<<< HEAD\n$oid\nsize 5\n=======\n$oid\nsize 5\n>>>>>>> feature\n",
            "size isn't a number" to "$version\n$oid\nsize five\n",
            "size with a plus" to "$version\n$oid\nsize +5\n",
            "size with a zero first" to "$version\n$oid\nsize 05\n",
            "a negative size" to "$version\n$oid\nsize -5\n",
            "a space after size" to "$version\n$oid\nsize 5 \n",
            "a tab after size" to "$version\n$oid\nsize 5\t\n",
            "a space before version" to " $version\n$oid\nsize 5\n",
            "a space before oid" to "$version\n $oid\nsize 5\n",
            "a space after version" to "$version \n$oid\nsize 5\n",
            "a tab between key and value" to "$version\noid\tsha256:$PHOTO_OID\nsize 5\n",
            "a line of spaces" to "$version\n   \n$oid\nsize 5\n",
            "CR line endings" to "$version\r$oid\rsize 5\r",
            "a SHA-1 oid" to "$version\noid sha1:${"a".repeat(40)}\nsize 5\n",
            "an uppercase oid" to "$version\noid sha256:${PHOTO_OID.uppercase()}\nsize 5\n",
            "an oid of 65 digits" to "$version\noid sha256:${PHOTO_OID}a\nsize 5\n",
            "no size" to "$version\n$oid\n",
            "600 bytes" to "$version\n" + "$extension\n".repeat(6) + "$oid\nsize 5\n",
        )

        Git.open(repo).use {
            val store = it.repository.lfsStore()
            val differences = cases.filter { (_, text) ->
                !clean(store, text.toByteArray()).contentEquals(runGitLfsClean(gitLfs, gitLfsRepo, text.toByteArray()))
            }

            assertEquals(emptySet<String>(), differences.keys)
        }
    }

    @Test
    fun `a smudge filter doesn't take a pointer with extension lines, which needs git-lfs's extension programs`() {
        val pointer = "version https://git-lfs.github.com/spec/v1\next-0-foo sha256:${sha256("ext")}\n" +
            "oid sha256:$PHOTO_OID\nsize 5\n"

        assertEquals(PHOTO_OID, parseLfsPointer(pointer.toByteArray())?.oid?.name())
        assertNull(parseLfsPointer(pointer.toByteArray(), allowExtensions = false))
    }

    @Test
    fun `a pointer whose id isn't hex isn't a pointer`() {
        val notHex = "version https://git-lfs.github.com/spec/v1\noid sha256:${"z".repeat(64)}\nsize 5\n"

        assertNull(parseLfsPointer(notHex.toByteArray()))
        assertNull(parseLfsPointer(ByteArray(0)))
        assertNull(parseLfsPointer(ByteArray(MAX_LFS_POINTER_SIZE + 1) { 'a'.code.toByte() }))
    }

    private fun repository(name: String = "repo"): File {
        val repo = File(tempDir, name)
        git.run(tempDir, "init", "-q", "-b", "main", repo.path)
        File(repo, "README.md").writeText("readme\n")
        git.run(repo, "add", "README.md")
        git.run(repo, "commit", "-q", "-m", "First")

        return repo
    }

    private fun linkedWorktree(repo: File): File {
        val worktree = File(tempDir, "wt")
        git.run(repo, "worktree", "add", "-q", "-b", "agent", worktree.path)

        return worktree
    }

    private fun clean(store: LfsStore, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        store.clean(content.inputStream(), output)

        return output.toByteArray()
    }

    private fun realGitLfs(): String {
        val path = ProcessBuilder("/bin/sh", "-c", "command -v git-lfs").start()
            .let { process -> process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() } }
        assumeTrue(path.isNotEmpty()) { "git-lfs isn't installed" }

        return path
    }

    /** What `git-lfs clean` writes for [content], in [repo], which it stores the object in. */
    private fun runGitLfsClean(gitLfs: String, repo: File, content: ByteArray): ByteArray {
        val process = ProcessBuilder(gitLfs, "clean", "--", "file.bin")
            .directory(repo)
            .apply {
                environment()["HOME"] = tempDir.path
                environment()["GIT_CONFIG_GLOBAL"] = File(tempDir, "empty.gitconfig").path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        process.outputStream.use { it.write(content) }
        val output = process.inputStream.readBytes()
        check(process.waitFor() == 0) { "git-lfs clean failed" }

        return output
    }
}

private const val PHOTO = "photo"

private val PHOTO_OID = sha256(PHOTO)

private fun sha256(text: String): String = sha256(text.toByteArray())

private fun objectPath(oid: String) = "${oid.substring(0, 2)}/${oid.substring(2, 4)}/$oid"

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
