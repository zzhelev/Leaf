// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.lfs.LfsSmudgeSettings
import dev.app.leaf.data.git.lfs.LfsStore
import dev.app.leaf.data.git.lfs.MAX_LFS_POINTER_SIZE
import dev.app.leaf.data.git.lfs.parseLfsPointer
import dev.app.leaf.domain.errors.LfsError
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.util.TemporaryBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

private const val TAG = "LfsFilterSession"

/** Content that git sends to be smudged as it is, kept in memory up to this size, in a temporary file past it. */
private const val IN_MEMORY_LIMIT = 4 * 1024 * 1024

private val CAPABILITIES = listOf("capability=clean", "capability=smudge", "capability=delay")

/** Downloads LFS objects into the store, and returns the ones that it couldn't, by oid, with why. */
fun interface LfsObjectFetcher {
    suspend fun fetch(pointers: List<LfsPointer>): Map<String, LfsError>
}

/**
 * One run of git's long-running filter process for LFS (gitattributes(5), "Long Running Filter Process"), which the
 * askpass helper relays to Leaf (fork-only, `docs/fork/lfs-filter.md`). It cleans and smudges as git-lfs does
 * ([LfsStore]), and delays the files whose objects the store lacks, so that a checkout downloads them in one batch when
 * git asks which are ready (`list_available_blobs`).
 *
 * git sends a request's whole content before it reads the answer, so the session reads it all before it answers:
 * answering while git still writes would block both.
 */
class LfsFilterSession(
    private val store: LfsStore,
    private val settings: LfsSmudgeSettings,
    private val fetcher: LfsObjectFetcher,
) {
    private val delayed = linkedMapOf<String, Delayed>()

    private val failed = linkedMapOf<String, LfsError>()

    /** The files whose objects couldn't be downloaded, by path, with why. */
    val failures: Map<String, LfsError> get() = failed.toMap()

    /** Serves git until it closes [input]. Throws [IOException] when git breaks the protocol. */
    suspend fun run(input: InputStream, output: OutputStream) {
        val reader = PktLineReader(input)
        val writer = PktLineWriter(output)

        handshake(reader, writer)

        while (true) {
            val request = try {
                reader.readList()
            } catch (e: EOFException) {
                // git is done with the filter
                return
            }

            val keys = request.associate { it.substringBefore('=') to it.substringAfter('=', "") }

            when (val command = keys["command"]) {
                "clean" -> clean(reader, writer)
                "smudge" -> smudge(reader, writer, keys["pathname"].orEmpty(), canDelay = keys["can-delay"] == "1")
                "list_available_blobs" -> listAvailable(writer)
                // git asks only for what the handshake offered
                else -> throw IOException("git asked the filter for an unknown command: $command")
            }
        }
    }

    private fun handshake(reader: PktLineReader, writer: PktLineWriter) {
        val welcome = reader.readList()

        if (welcome.firstOrNull() != "git-filter-client" || "version=2" !in welcome) {
            throw IOException("git didn't start the filter protocol, version 2: $welcome")
        }

        writer.writeList("git-filter-server", "version=2")

        val offered = reader.readList()
        writer.writeList(*CAPABILITIES.filter { it in offered }.toTypedArray())
    }

    private fun clean(reader: PktLineReader, writer: PktLineWriter) {
        val content = PacketContent(reader)
        val cleaned = ByteArrayOutputStream()

        try {
            store.clean(content, cleaned)
        } catch (e: IOException) {
            printError(TAG, "Couldn't store a file in the LFS store", e)
            content.drain()
            writer.writeList("status=error")
            return
        }

        content.drain()
        respond(writer, ByteArrayInputStream(cleaned.toByteArray()))
    }

    private suspend fun smudge(reader: PktLineReader, writer: PktLineWriter, path: String, canDelay: Boolean) {
        val content = PacketContent(reader)
        val head = content.readNBytes(MAX_LFS_POINTER_SIZE + 1)
        // git asks again for each file it was told to wait for, with no content this time
        val retried = delayed.remove(path)?.takeIf { head.isEmpty() }
        val pointer = retried?.pointer ?: parseLfsPointer(head, allowExtensions = false)

        if (pointer == null) {
            // As git-lfs does, anything but a pointer goes back as it came, byte for byte
            respondWithWhole(writer, head, content)
            return
        }

        val pointerBytes = retried?.pointerBytes ?: head

        if (settings.skipsSmudge) {
            respond(writer, ByteArrayInputStream(pointerBytes))
            return
        }

        var media = store.find(pointer.oid, pointer.size)

        if (media == null && retried == null) {
            if (canDelay) {
                delayed[path] = Delayed(pointer, pointerBytes)
                writer.writeList("status=delayed")
                return
            }

            // git can't wait for this one, as outside a checkout
            fetcher.fetch(listOf(pointer))[pointer.oid.name()]?.let { failed[path] = it }
            media = store.find(pointer.oid, pointer.size)
        }

        when {
            media != null -> Files.newInputStream(media).use { respond(writer, it) }
            settings.skipsDownloadErrors -> {
                printError(TAG, "Writing the pointer of $path, as lfs.skipdownloaderrors says: ${failed[path]}")
                respond(writer, ByteArrayInputStream(pointerBytes))
            }
            else -> {
                failed.getOrPut(path) { LfsError.RequestFailed("The object isn't in the LFS store after its download") }
                writer.writeList("status=error")
            }
        }
    }

    /** Downloads the objects of the files that wait, all at once, then lists every one of them, failed or not. */
    private suspend fun listAvailable(writer: PktLineWriter) {
        val missing = delayed.values
            .map { it.pointer }
            .filter { store.find(it.oid, it.size) == null }
            .distinctBy { it.oid.name() }

        if (missing.isNotEmpty()) {
            val failures = fetcher.fetch(missing)

            for ((path, file) in delayed) {
                failures[file.pointer.oid.name()]?.let { failed[path] = it }
            }
        }

        // git then asks for each again, and an empty list once there are none left
        writer.writeList(*delayed.keys.map { "pathname=$it" }.toTypedArray())
        writer.writeList("status=success")
    }

    /** Sends [head] and the rest of [content] back, once git has sent all of it. */
    private fun respondWithWhole(writer: PktLineWriter, head: ByteArray, content: PacketContent) {
        val whole = TemporaryBuffer.LocalFile(null, IN_MEMORY_LIMIT)

        try {
            whole.write(head)
            content.copyTo(whole)
            whole.close()
            whole.openInputStream().use { respond(writer, it) }
        } finally {
            whole.destroy()
        }
    }

    /** `status=success`, [content] in packets, then an empty list, which leaves the status as it is. */
    private fun respond(writer: PktLineWriter, content: InputStream) {
        writer.writeList("status=success")

        val buffer = ByteArray(MAX_PACKET_DATA)

        while (true) {
            val read = try {
                content.readNBytes(buffer, 0, buffer.size)
            } catch (e: IOException) {
                printError(TAG, "Couldn't read what goes to git", e)
                writer.flush()
                writer.writeList("status=error")
                return
            }

            if (read == 0) {
                break
            }

            writer.write(buffer, 0, read)
        }

        writer.flush()
        writer.flush()
    }

    private class Delayed(val pointer: LfsPointer, val pointerBytes: ByteArray)
}
