// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.random.Random

/** git's pkt-lines, as gitprotocol-common(5) has them and git's long-running filter protocol sends them. */
class PktLineTest {
    @Test
    fun `a list is text packets with a line feed, and a flush packet`() {
        val output = ByteArrayOutputStream()

        PktLineWriter(output).writeList("git-filter-server", "version=2")

        assertEquals("0016git-filter-server\n000eversion=2\n0000", output.toString())
        assertEquals(listOf("git-filter-server", "version=2"), reader(output.toByteArray()).readList())
    }

    @Test
    fun `an empty list is a flush packet alone`() {
        val output = ByteArrayOutputStream()

        PktLineWriter(output).writeList()

        assertEquals("0000", output.toString())
        assertEquals(emptyList<String>(), reader(output.toByteArray()).readList())
    }

    @Test
    fun `a packet holds at most 65,516 bytes of data`() {
        val output = ByteArrayOutputStream()
        val writer = PktLineWriter(output)
        val data = Random(1).nextBytes(MAX_PACKET_DATA)

        writer.write(data)
        assertThrows<IllegalArgumentException> { writer.write(ByteArray(MAX_PACKET_DATA + 1)) }
        assertThrows<IllegalArgumentException> { writer.write(ByteArray(0)) }

        assertEquals("fff0", output.toByteArray().copyOf(4).decodeToString())
        assertArrayEquals(data, reader(output.toByteArray()).read())
    }

    @Test
    fun `a stream that ends between packets is git closing it, and one that ends inside a packet is broken`() {
        assertThrows<EOFException> { reader(ByteArray(0)).read() }
        assertThrows<IOException> { reader("00".toByteArray()).read() }
        assertThrows<IOException> { reader("0009ab".toByteArray()).read() }
    }

    @Test
    fun `a length that isn't hexadecimal or that is out of range is broken`() {
        assertThrows<IOException> { reader("zzzzdata".toByteArray()).read() }
        assertThrows<IOException> { reader("0004".toByteArray()).read() }
        assertThrows<IOException> { reader("fff1".toByteArray() + ByteArray(0xfff1)).read() }
        assertNull(reader("0000".toByteArray()).read())
    }

    @Test
    fun `content is the data of its packets up to the flush packet, and the next request follows`() {
        val output = ByteArrayOutputStream()
        val writer = PktLineWriter(output)
        val content = Random(2).nextBytes(MAX_PACKET_DATA * 2 + 100)

        content.asList().chunked(MAX_PACKET_DATA).forEach { writer.write(it.toByteArray()) }
        writer.flush()
        writer.writeList("command=smudge")

        val reader = reader(output.toByteArray())
        assertArrayEquals(content, PacketContent(reader).readBytes())
        assertEquals(listOf("command=smudge"), reader.readList())
    }

    @Test
    fun `draining reads the rest of the content, whatever was read of it`() {
        val output = ByteArrayOutputStream()
        val writer = PktLineWriter(output)

        writer.write("first".toByteArray())
        writer.write("second".toByteArray())
        writer.flush()
        writer.writeList("command=clean")

        val reader = reader(output.toByteArray())
        val content = PacketContent(reader)
        assertEquals("fir", content.readNBytes(3).decodeToString())
        content.drain()

        assertEquals(-1, content.read())
        assertEquals(listOf("command=clean"), reader.readList())
    }

    private fun reader(bytes: ByteArray) = PktLineReader(ByteArrayInputStream(bytes))
}
