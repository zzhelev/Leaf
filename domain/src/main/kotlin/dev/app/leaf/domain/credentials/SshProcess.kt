package dev.app.leaf.domain.credentials

import dev.app.leaf.Session
import dev.app.leaf.domain.libssh.ChannelWrapper
import java.io.InputStream
import java.io.OutputStream
import kotlin.getValue

class SshProcess : Process() {
    private lateinit var channel: ChannelWrapper
    private lateinit var session: Session

    private val outputStream by lazy {
        channel.outputStream
    }
    private val inputStream by lazy {
        channel.inputStream
    }
    private val errorOutputStream by lazy {
        channel.errorOutputStream
    }

    override fun getOutputStream(): OutputStream {
        return outputStream
    }

    override fun getInputStream(): InputStream {
        return inputStream
    }

    override fun getErrorStream(): InputStream {
        return errorOutputStream
    }

    override fun waitFor(): Int {
        return channel.waitForExitStatus()
    }

    // JGit asks for the exit status after an error, once it has closed the connection: 127 means that the server
    // couldn't find git-upload-pack or git-receive-pack
    override fun exitValue(): Int {
        if (isRunning()) {
            throw IllegalThreadStateException("The SSH command is still running")
        }

        return channel.waitForExitStatus()
    }

    override fun destroy() {
        closeChannel()
    }

    fun closeChannel() {
        channel.close()
    }

    private fun isRunning(): Boolean {
        return !channel.isEof()
    }

    fun setup(session: Session, commandName: String) {
        val channel = ChannelWrapper(session)

        channel.openSession()
        channel.requestExec(commandName)

        this.session = session
        this.channel = channel
    }

}