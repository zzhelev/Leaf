package dev.app.leaf.domain.libssh.streams

import dev.app.leaf.Channel
import java.io.IOException
import java.io.InputStream

private const val READ_FAILED =
    "Could not read result from SSH channel. Please check your network connectivity and try again."

class SshChannelInputStream(private val sshChannel: Channel) : InputStream() {
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = sshChannel.read(false, len.toULong())
            ?: throw IOException(READ_FAILED)

        if (result.readCount == 0.toULong()) {
            return -1
        }

        val read = result.readCount.toInt()
        result.data.copyInto(b, destinationOffset = off, endIndex = read)

        return read
    }

    override fun read(): Int {
        val result = sshChannel.read(false, 1L.toULong())
            ?: throw IOException(READ_FAILED)

        if (result.readCount == 0.toULong()) {
            return -1
        }

        return result.data.first().toInt() and 0xff
    }

    override fun close() {
        // The channel is closed by [LibSshChannel]
    }
}
