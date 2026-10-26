// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The most data that one packet holds: git's `LARGE_PACKET_MAX` (65,520) less its 4-digit length. */
internal const val MAX_PACKET_DATA = 65516

private const val LENGTH_DIGITS = 4

private val FLUSH = "0000".toByteArray()

/**
 * Reads git's pkt-lines (gitprotocol-common(5)), as its long-running filter protocol sends them: a length of 4
 * hexadecimal digits that counts itself, then the data, or `0000` for a flush packet.
 */
internal class PktLineReader(private val input: InputStream) {
    /** The next packet's data, or null for a flush packet. Throws [EOFException] when git closed the stream before it. */
    fun read(): ByteArray? {
        val header = input.readNBytes(LENGTH_DIGITS)

        if (header.isEmpty()) {
            throw EOFException("git closed the filter's input")
        }

        if (header.size < LENGTH_DIGITS) {
            throw IOException("A packet's length ends early")
        }

        val length = header.decodeToString().toIntOrNull(16)
            ?: throw IOException("A packet's length isn't hexadecimal: ${header.decodeToString()}")

        if (length == 0) {
            return null
        }

        if (length <= LENGTH_DIGITS || length > LENGTH_DIGITS + MAX_PACKET_DATA) {
            throw IOException("A packet's length is out of range: $length")
        }

        val data = input.readNBytes(length - LENGTH_DIGITS)

        if (data.size < length - LENGTH_DIGITS) {
            throw IOException("A packet ends early")
        }

        return data
    }

    /** The text packets up to the next flush packet, each without its line feed. */
    fun readList(): List<String> = buildList {
        while (true) {
            add((read() ?: break).decodeToString().removeSuffix("\n"))
        }
    }
}

/** Writes git's pkt-lines ([PktLineReader]). */
internal class PktLineWriter(private val output: OutputStream) {
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(length in 1..MAX_PACKET_DATA) { "A packet holds 1 to $MAX_PACKET_DATA bytes, not $length" }

        output.write("%04x".format(length + LENGTH_DIGITS).toByteArray())
        output.write(data, offset, length)
    }

    /** A flush packet, after which everything written goes to git. */
    fun flush() {
        output.write(FLUSH)
        output.flush()
    }

    /** [items] as text packets, each with a line feed, then a flush packet. */
    fun writeList(vararg items: String) {
        for (item in items) {
            write("$item\n".toByteArray())
        }

        flush()
    }
}

/**
 * The content that git sends with a request, the data of its packets up to the flush packet, as one stream. Closing it
 * leaves the protocol's stream open.
 */
internal class PacketContent(private val reader: PktLineReader) : InputStream() {
    private var packet: ByteArray? = ByteArray(0)
    private var position = 0

    override fun read(): Int {
        val byte = ByteArray(1)

        return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }

        while (true) {
            val current = packet ?: return -1

            if (position < current.size) {
                val count = minOf(len, current.size - position)
                System.arraycopy(current, position, b, off, count)
                position += count

                return count
            }

            packet = reader.read()
            position = 0
        }
    }

    /** Reads what is left of the content, so that the next request starts where git sends it. */
    fun drain() {
        val buffer = ByteArray(MAX_PACKET_DATA)

        while (read(buffer, 0, buffer.size) != -1) {
            // Nothing to keep
        }
    }
}
