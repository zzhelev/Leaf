package dev.app.leaf.domain.libssh.streams

import dev.app.leaf.Channel
import java.io.IOException
import java.io.OutputStream

class SshChannelOutputStream(private val sshChannel: Channel) : OutputStream() {
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    // Failures are IOExceptions: JGit ignores those while it closes a connection after an error. Anything else
    // replaced the error, for example "Remote channel is closed" in place of the server's message.
    override fun write(b: ByteArray, off: Int, len: Int) {
        val error = try {
            sshChannel.writeBytes(b.copyOfRange(off, off + len))
        } catch (e: IllegalStateException) {
            // The channel was closed and destroyed
            throw IOException(e.message, e)
        }

        if (error.isNotEmpty()) {
            throw IOException(error)
        }
    }

    override fun close() {
    }
}
