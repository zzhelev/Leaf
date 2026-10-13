// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val LIBRARY = "libleaf_rs.dylib"

class NativeLibraryExtractionTest {
    @TempDir
    lateinit var tempDir: File

    private val library = "library built in worktree one".toByteArray()
    private val otherLibrary = "library built in worktree two".toByteArray()

    @Test
    fun `names the folder after the start of the library's SHA-256`() {
        val file = extractNativeLibrary("abc".toByteArray(), tempDir, LIBRARY)

        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals(File(tempDir, "leaf_rs-ba7816bf8f01cfea/$LIBRARY"), file)
        assertArrayEquals("abc".toByteArray(), file.readBytes())
    }

    @Test
    fun `the same bytes give the same file, which isn't written again`() {
        val first = extractNativeLibrary(library, tempDir, LIBRARY)
        first.setLastModified(1_600_000_000_000)
        val inode = first.fileKey()

        val second = extractNativeLibrary(library, tempDir, LIBRARY)

        assertEquals(first, second)
        assertEquals(inode, second.fileKey())
        assertEquals(1_600_000_000_000, second.lastModified())
        assertArrayEquals(library, second.readBytes())
    }

    @Test
    fun `other bytes give another file and leave the first one alone`() {
        val first = extractNativeLibrary(library, tempDir, LIBRARY)
        val inode = first.fileKey()

        val second = extractNativeLibrary(otherLibrary, tempDir, LIBRARY)

        assertNotEquals(first.parentFile, second.parentFile)
        assertEquals(LIBRARY, second.name)
        assertArrayEquals(otherLibrary, second.readBytes())
        assertArrayEquals(library, first.readBytes())
        assertEquals(inode, first.fileKey())
    }

    @Test
    fun `a file in its place with other bytes of the same size is replaced, not written into`() {
        assertEquals(library.size, otherLibrary.size)
        val target = extractNativeLibrary(library, tempDir, LIBRARY)
        target.writeBytes(otherLibrary)
        // A process that loaded the file keeps it through this link
        val loaded = File(tempDir, "loaded")
        Files.createLink(loaded.toPath(), target.toPath())

        val file = extractNativeLibrary(library, tempDir, LIBRARY)

        assertEquals(target, file)
        assertArrayEquals(library, file.readBytes())
        assertArrayEquals(otherLibrary, loaded.readBytes())
        assertNotEquals(loaded.fileKey(), file.fileKey())
    }

    @Test
    fun `a truncated file in its place is replaced, not written into`() {
        val target = extractNativeLibrary(library, tempDir, LIBRARY)
        target.writeBytes(library.copyOf(5))
        val loaded = File(tempDir, "loaded")
        Files.createLink(loaded.toPath(), target.toPath())

        val file = extractNativeLibrary(library, tempDir, LIBRARY)

        assertArrayEquals(library, file.readBytes())
        assertArrayEquals(library.copyOf(5), loaded.readBytes())
    }

    @Test
    fun `leaves the file that older Leafs load alone`() {
        val old = File(tempDir, LIBRARY)
        old.writeBytes(otherLibrary)
        val inode = old.fileKey()

        extractNativeLibrary(library, tempDir, LIBRARY)

        assertArrayEquals(otherLibrary, old.readBytes())
        assertEquals(inode, old.fileKey())
    }

    @Test
    fun `leaves only the library in its folder`() {
        val file = extractNativeLibrary(library, tempDir, LIBRARY)

        assertEquals(listOf(LIBRARY), file.parentFile.list()!!.toList())
    }

    @Test
    fun `uses the file that another Leaf moved into place when its own move fails`() {
        val file = extractNativeLibrary(library, tempDir, LIBRARY, move = { _, target ->
            val theirs = File(tempDir, "theirs")
            theirs.writeBytes(library)
            Files.move(theirs.toPath(), target, StandardCopyOption.ATOMIC_MOVE)

            throw IOException("The file is in use by another process")
        })

        assertArrayEquals(library, file.readBytes())
        assertEquals(listOf(LIBRARY), file.parentFile.list()!!.toList())
    }

    @Test
    fun `fails when its move fails and the file isn't in place`() {
        assertThrows(IOException::class.java) {
            extractNativeLibrary(library, tempDir, LIBRARY, move = { _, _ -> throw IOException("Disk full") })
        }

        val folder = tempDir.listFiles()!!.single()
        assertEquals(emptyList<String>(), folder.list()!!.toList())
    }

    @Test
    fun `Leafs that start at once all get the whole library`() {
        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        try {
            val results = (1..threads).map {
                executor.submit<File> {
                    start.await()
                    extractNativeLibrary(library, tempDir, LIBRARY)
                }
            }
            start.countDown()
            val files = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, files.toSet().size)
            assertArrayEquals(library, files.first().readBytes())
            assertEquals(listOf(LIBRARY), files.first().parentFile.list()!!.toList())
        } finally {
            executor.shutdownNow()
        }
    }
}

private fun File.fileKey(): Any? = Files.readAttributes(toPath(), BasicFileAttributes::class.java).fileKey()
