package dev.app.leaf.domain.libssh

import dev.app.leaf.Channel
import dev.app.leaf.Session
import dev.app.leaf.common.printError
import dev.app.leaf.domain.exceptions.SshException
import dev.app.leaf.domain.extensions.throwIfSshMessage
import dev.app.leaf.domain.libssh.streams.SshChannelInputErrStream
import dev.app.leaf.domain.libssh.streams.SshChannelInputStream
import dev.app.leaf.domain.libssh.streams.SshChannelOutputStream
import java.util.concurrent.Semaphore

private const val TAG = "ChannelWrapper"

/** Returned as the exit status when the server didn't send one. */
private const val UNKNOWN_EXIT_STATUS = -1

class ChannelWrapper internal constructor(sshSession: Session) {
    private val channel = Channel(sshSession)
        ?: throw SshException("Could not obtain the channel, this is likely a bug. Please file a report.")

    @Volatile
    private var isClosed = false
    private var exitStatus: Int? = null
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
        return !isClosed && channel.isOpen()
    }

    /** Whether the command has ended its output, and everything it wrote has been read. */
    fun isEof(): Boolean {
        return isClosed || channel.isEof()
    }

    /**
     * The command's exit status, waiting for it while the channel is open. Once the channel is closed, it is the
     * status the command had exited with by then, or [UNKNOWN_EXIT_STATUS].
     */
    fun waitForExitStatus(): Int {
        closeMutex.acquire()
        try {
            return exitStatus ?: if (isClosed) {
                UNKNOWN_EXIT_STATUS
            } else {
                channel.exitStatus().also { exitStatus = it }
            }
        } finally {
            closeMutex.release()
        }
    }

    fun close() {
        closeMutex.acquire()
        try {
            if (!isClosed) {
                // JGit reads the server's messages and the exit status after it closes the connection, so both are
                // kept. The exit status is only asked for once the output has ended, so that closing never waits
                // for a command that is still running.
                errorOutputStream.drain()

                if (exitStatus == null && channel.isEof()) {
                    exitStatus = channel.exitStatus()
                }

                // Not thrown: JGit closes connections after errors, and an exception here would replace the error
                val error = channel.closeChannel()
                if (error.isNotEmpty()) {
                    printError(TAG, error)
                }

                channel.close()
                isClosed = true
            }
        } finally {
            closeMutex.release()
        }
    }
}
