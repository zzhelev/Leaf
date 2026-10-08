package dev.app.leaf.domain.libssh.streams

import dev.app.leaf.Channel
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

private const val POLL_INTERVAL_MS = 20L
private const val READ_SIZE = 4096UL

/**
 * The command's stderr, where a server explains why it refused a command ("ERROR: Permission to … denied").
 *
 * It polls the channel instead of blocking in libssh: a blocking read would hold the session that stdout is read
 * through. The output that arrived before the channel is closed is kept ([drain]), as JGit closes the connection
 * before it reads the messages.
 */
class SshChannelInputErrStream(private val sshChannel: Channel) : InputStream() {
    private val pending = ArrayDeque<Byte>()
    private var channelClosed = false

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) == -1) -1 else byte[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) {
            return 0
        }

        while (true) {
            synchronized(this) {
                if (pending.isEmpty() && !channelClosed) {
                    readAvailable()
                }

                if (pending.isNotEmpty()) {
                    val count = minOf(len, pending.size)

                    for (i in 0 until count) {
                        b[off + i] = pending.removeFirst()
                    }

                    return count
                }

                if (channelClosed || sshChannel.isEof()) {
                    return -1
                }
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                // JGit interrupts its copying thread to stop it
                throw InterruptedIOException()
            }
        }
    }

    /** Keeps what the server has sent so far, and ends the stream there. Called before the channel is closed. */
    internal fun drain() = synchronized(this) {
        if (!channelClosed) {
            readAvailable()
            channelClosed = true
        }
    }

    private fun readAvailable() {
        while (true) {
            val result = sshChannel.readAvailable(true, READ_SIZE)
                ?: throw IOException("Could not read the error output of the SSH channel")

            val count = result.readCount.toInt()

            if (count == 0) {
                return
            }

            for (i in 0 until count) {
                pending.addLast(result.data[i])
            }
        }
    }
}
