package dev.app.leaf.domain.libssh

import dev.app.leaf.Channel
import dev.app.leaf.Session
import dev.app.leaf.domain.exceptions.SshException
import dev.app.leaf.domain.extensions.throwIfSshMessage
import dev.app.leaf.domain.libssh.streams.SshChannelInputErrStream
import dev.app.leaf.domain.libssh.streams.SshChannelInputStream
import dev.app.leaf.domain.libssh.streams.SshChannelOutputStream
import java.util.concurrent.Semaphore

class ChannelWrapper internal constructor(sshSession: Session) {
    private val channel = Channel(sshSession)
        ?: throw SshException("Could not obtain the channel, this is likely a bug. Please file a report.")

    private var isClosed = false
    private var closeMutex = Semaphore(1)
    val outputStream = SshChannelOutputStream(channel)
    val inputStream = SshChannelInputStream(channel)
    val errorOutputStream = SshChannelInputErrStream(channel)

    fun openSession() {
        channel.openSession()
    }

    fun requestExec(commandName: String) {
        channel.requestExec(commandName).throwIfSshMessage()
    }

    fun isOpen(): Boolean {
        return channel.isOpen()
    }

    fun close() {
        closeMutex.acquire()
        try {
            if (!isClosed) {
                channel.closeChannel().throwIfSshMessage()
                channel.close()
                isClosed = true
            }
        } finally {
            closeMutex.release()
        }
    }
}