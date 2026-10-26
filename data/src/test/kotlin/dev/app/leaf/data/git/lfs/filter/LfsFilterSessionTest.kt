// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.lfs.LfsSmudgeSettings
import dev.app.leaf.data.git.lfs.LfsStore
import dev.app.leaf.data.git.lfs.lfsPointerText
import dev.app.leaf.data.git.lfs.lfsStore
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsFileFailure
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Config
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

private const val PHOTO = "the photo's content\n"
private const val DRAWING = "the drawing's content\n"

private val HANDSHAKE_ANSWER = listOf("git-filter-server\nversion=2\n", "capability=clean\ncapability=smudge\ncapability=delay\n")

/**
 * The filter protocol's conversations with git, scripted: git's side is written up front, and Leaf's answers are read
 * as the segments between flush packets, a content's packets joined.
 */
class LfsFilterSessionTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var store: LfsStore

    /** What the session asked to download, call by call. */
    private val fetched = mutableListOf<List<String>>()

    /** The objects that the fake server has, and so downloads into the store. */
    private val serverObjects = mutableMapOf<String, ByteArray>()

    @BeforeEach
    fun setUp() {
        store = Git.init().setDirectory(File(tempDir, "repo")).call().use { it.repository.lfsStore() }
    }

    @Test
    fun `the handshake answers version 2, with the capabilities that git offered and Leaf has`(): Unit = runBlocking {
        val answers = converse {
            writeList("git-filter-client", "version=2")
            writeList("capability=clean", "capability=smudge", "capability=other")
        }

        assertEquals(listOf("git-filter-server\nversion=2\n", "capability=clean\ncapability=smudge\n"), answers)
    }

    @Test
    fun `a conversation that isn't git's filter protocol, version 2, is refused`() {
        assertThrows<IOException> {
            runBlocking { converse { writeList("git-filter-client", "version=3") } }
        }
    }

    @Test
    fun `smudge writes the stored object, and anything else as it came`(): Unit = runBlocking {
        stored(PHOTO)
        // Longer than git's pipe and the socket hold, which git writes whole before it reads the answer
        val random = Random(1)
        val notAPointer = String(CharArray(MAX_PACKET_DATA * 3) { 'a' + random.nextInt(26) })
        val conflicted = pointer(PHOTO).replace("oid ", "<<<<<<< ours\noid ")

        val answers = converse {
            handshake()
            smudge("photo.bin", pointer(PHOTO))
            smudge("big.bin", notAPointer)
            smudge("conflicted.bin", conflicted)
            smudge("empty.bin", "")
        }

        assertEquals(
            HANDSHAKE_ANSWER +
                success(PHOTO) +
                success(notAPointer) +
                success(conflicted) +
                success(""),
            answers,
        )
        assertEquals(emptyList<List<String>>(), fetched)
    }

    @Test
    fun `smudge delays the files whose objects the store lacks, and downloads them all at once`(): Unit = runBlocking {
        serverObjects[oid(PHOTO)] = PHOTO.toByteArray()
        serverObjects[oid(DRAWING)] = DRAWING.toByteArray()

        val answers = converse {
            handshake()
            smudge("photo.bin", pointer(PHOTO), canDelay = true)
            smudge("copy.bin", pointer(PHOTO), canDelay = true)
            smudge("drawing.bin", pointer(DRAWING), canDelay = true)
            listAvailable()
            smudge("photo.bin", "")
            smudge("copy.bin", "")
            smudge("drawing.bin", "")
            listAvailable()
        }

        assertEquals(
            HANDSHAKE_ANSWER +
                listOf("status=delayed\n", "status=delayed\n", "status=delayed\n") +
                listOf("pathname=photo.bin\npathname=copy.bin\npathname=drawing.bin\n", "status=success\n") +
                success(PHOTO) + success(PHOTO) + success(DRAWING) +
                listOf("", "status=success\n"),
            answers,
        )
        assertEquals(listOf(listOf(oid(PHOTO), oid(DRAWING))), fetched)
    }

    @Test
    fun `smudge downloads at once when git can't wait`(): Unit = runBlocking {
        serverObjects[oid(PHOTO)] = PHOTO.toByteArray()

        val answers = converse {
            handshake()
            smudge("photo.bin", pointer(PHOTO))
        }

        assertEquals(HANDSHAKE_ANSWER + success(PHOTO), answers)
        assertEquals(listOf(listOf(oid(PHOTO))), fetched)
    }

    @Test
    fun `a file whose object can't be downloaded fails, and the session says which and why`(): Unit = runBlocking {
        serverObjects[oid(PHOTO)] = PHOTO.toByteArray()
        val session = session()

        val answers = converse(session) {
            handshake()
            smudge("photo.bin", pointer(PHOTO), canDelay = true)
            smudge("drawing.bin", pointer(DRAWING), canDelay = true)
            listAvailable()
            smudge("photo.bin", "")
            smudge("drawing.bin", "")
        }

        assertEquals(
            HANDSHAKE_ANSWER +
                listOf("status=delayed\n", "status=delayed\n") +
                listOf("pathname=photo.bin\npathname=drawing.bin\n", "status=success\n") +
                success(PHOTO) +
                listOf("status=error\n"),
            answers,
        )
        assertEquals(mapOf("drawing.bin" to REFUSED), session.failures)
    }

    @Test
    fun `with lfs skipdownloaderrors, a file whose object can't be downloaded is written as its pointer`(): Unit =
        runBlocking {
            val config = Config().apply { setBoolean("lfs", null, "skipdownloaderrors", true) }

            val answers = converse(session(LfsSmudgeSettings(config, emptyMap()))) {
                handshake()
                smudge("drawing.bin", pointer(DRAWING))
            }

            assertEquals(HANDSHAKE_ANSWER + success(pointer(DRAWING)), answers)
        }

    @Test
    fun `with GIT_LFS_SKIP_SMUDGE, smudge writes pointers and downloads nothing`(): Unit = runBlocking {
        serverObjects[oid(PHOTO)] = PHOTO.toByteArray()

        val answers = converse(session(LfsSmudgeSettings(Config(), mapOf("GIT_LFS_SKIP_SMUDGE" to "1")))) {
            handshake()
            smudge("photo.bin", pointer(PHOTO), canDelay = true)
        }

        assertEquals(HANDSHAKE_ANSWER + success(pointer(PHOTO)), answers)
        assertEquals(emptyList<List<String>>(), fetched)
    }

    @Test
    fun `clean stores a file and answers its pointer, and keeps a pointer and an empty file as they are`(): Unit =
        runBlocking {
            val large = Random(3).nextBytes(MAX_PACKET_DATA * 2 + 7)

            val answers = converse {
                handshake()
                clean("photo.bin", PHOTO.toByteArray())
                clean("large.bin", large)
                clean("pointer.bin", pointer(DRAWING).toByteArray())
                clean("empty.bin", ByteArray(0))
            }

            assertEquals(
                HANDSHAKE_ANSWER +
                    success(pointer(PHOTO)) +
                    success(lfsPointerText(LongObjectId.fromString(oid(large)), large.size.toLong())) +
                    success(pointer(DRAWING)) +
                    success(""),
                answers,
            )
            assertArrayEquals(large, Files.readAllBytes(store.mediaFile(LongObjectId.fromString(oid(large)))))
            assertEquals(PHOTO, Files.readString(store.mediaFile(LongObjectId.fromString(oid(PHOTO)))))
        }

    @Test
    fun `a file that fails in a submodule is named from the command's working tree`(): Unit = runBlocking {
        val workTree = File(tempDir, "super")
        val submodule = File(workTree, "sub").apply { mkdirs() }
        val run = LfsFilterRun(workTree, File("/Applications/My Leaf/leaf-askpass")) { session() }

        val git = script {
            handshake()
            smudge("photo.bin", pointer(DRAWING))
        }
        run.serve(submodule, ByteArrayInputStream(git), ByteArrayOutputStream())

        assertEquals(listOf(LfsFileFailure("sub/photo.bin", REFUSED)), run.failedFiles)
        assertEquals(
            listOf(
                "-c", "filter.lfs.process='/Applications/My Leaf/leaf-askpass' lfs-filter",
                "-c", "filter.lfs.required=true",
            ),
            run.arguments,
        )
    }

    private fun PktLineWriter.handshake() {
        writeList("git-filter-client", "version=2")
        writeList("capability=clean", "capability=smudge", "capability=delay")
    }

    private fun PktLineWriter.smudge(path: String, content: String, canDelay: Boolean = false) {
        writeList(*listOfNotNull("command=smudge", "pathname=$path", "can-delay=1".takeIf { canDelay }).toTypedArray())
        content(content.toByteArray())
    }

    private fun PktLineWriter.clean(path: String, content: ByteArray) {
        writeList("command=clean", "pathname=$path")
        content(content)
    }

    private fun PktLineWriter.listAvailable() = writeList("command=list_available_blobs")

    private fun PktLineWriter.content(bytes: ByteArray) {
        bytes.asList().chunked(MAX_PACKET_DATA).forEach { write(it.toByteArray()) }
        flush()
    }

    /** A successful answer with [content]: the status, the content and an empty list. */
    private fun success(content: String) = listOf("status=success\n", content, "")

    private fun session(settings: LfsSmudgeSettings = LfsSmudgeSettings(Config(), emptyMap())) =
        LfsFilterSession(store, settings) { pointers -> fetch(pointers) }

    /** Downloads what the fake server has into the store, and refuses the rest. */
    private fun fetch(pointers: List<LfsPointer>): Map<String, LfsError> {
        fetched += pointers.map { it.oid.name() }

        return pointers.mapNotNull { pointer ->
            val content = serverObjects[pointer.oid.name()] ?: return@mapNotNull pointer.oid.name() to REFUSED
            store.mediaFile(pointer.oid).toFile().apply { parentFile.mkdirs() }.writeBytes(content)
            null
        }.toMap()
    }

    /** git's side of a conversation, as [write] writes it. */
    private fun script(write: PktLineWriter.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { PktLineWriter(it).write() }.toByteArray()

    /** Runs [session] on git's side as [write] writes it, and returns Leaf's answers, segment by segment. */
    private suspend fun converse(session: LfsFilterSession = session(), write: PktLineWriter.() -> Unit): List<String> {
        val output = ByteArrayOutputStream()

        session.run(ByteArrayInputStream(script(write)), output)

        val reader = PktLineReader(ByteArrayInputStream(output.toByteArray()))
        val segments = mutableListOf<String>()
        val segment = ByteArrayOutputStream()

        while (true) {
            val packet = try {
                reader.read()
            } catch (e: java.io.EOFException) {
                break
            }

            if (packet == null) {
                segments += segment.toString(Charsets.ISO_8859_1)
                segment.reset()
            } else {
                segment.write(packet)
            }
        }

        assertTrue(segment.size() == 0, "Leaf's answer ends inside a segment")

        return segments
    }

    private fun stored(content: String) {
        store.mediaFile(LongObjectId.fromString(oid(content))).toFile().apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun pointer(content: String) =
        lfsPointerText(LongObjectId.fromString(oid(content)), content.toByteArray().size.toLong())

    private fun oid(content: String) = oid(content.toByteArray())

    private fun oid(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private val REFUSED = LfsError.ObjectRefused(404, "Object does not exist")
