package dev.app.leaf.domain.libssh.streams

import dev.app.leaf.Channel
import dev.app.leaf.domain.exceptions.SshException
import java.io.InputStream

class SshChannelInputErrStream(private val sshChannel: Channel) : InputStream() {
    private var cancelled = false

    override fun read(): Int {
        return if (sshChannel.pollHasBytes(true)) {
            val read = sshChannel.read(true, 1L.toULong())
                ?: throw SshException("Could not read result from SSH channel. Please check your network connectivity and try again.")

            val byteArray = read.data

            val first = byteArray.first()

            first.toInt()
        } else
            -1
    }

    override fun close() {
        cancelled = true
    }
}