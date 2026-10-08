// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.util.Collections

/** The real `leaf-askpass` against [withAskpassServer], run the way git and ssh run it. */
@DisabledOnOs(OS.WINDOWS)
class AskpassServerTest {
    private lateinit var helper: File
    private val requests: MutableList<AskpassRequest> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun findHelper() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!
    }

    @Test
    fun `a prompt is answered on stdout, with a newline as git and ssh read it`(): Unit = runBlocking {
        val result = withAskpassServer(answering("s3cret pass")) { environment ->
            runHelper(environment, listOf("Password for 'https://bob@example.com': "))
        }

        assertEquals(HelperResult(0, "s3cret pass\n"), result)
        assertEquals(listOf(AskpassRequest.Prompt("Password for 'https://bob@example.com': ")), requests)
    }

    @Test
    fun `a prompt of several lines arrives whole`(): Unit = runBlocking {
        val prompt = "The authenticity of host 'example.com (1.2.3.4)' can't be established.\n" +
            "ED25519 key fingerprint is SHA256:abc.\nAre you sure you want to continue connecting (yes/no/[fingerprint])? "

        withAskpassServer(answering("yes")) { environment -> runHelper(environment, listOf(prompt)) }

        assertEquals(listOf(AskpassRequest.Prompt(prompt)), requests)
    }

    @Test
    fun `a refused prompt makes the helper fail without an answer`(): Unit = runBlocking {
        val result = withAskpassServer(answering(null)) { environment ->
            runHelper(environment, listOf("Username for 'https://example.com': "))
        }

        assertEquals(HelperResult(1, ""), result)
    }

    @Test
    fun `ssh's confirmations are answered with the exit code`(): Unit = runBlocking {
        val confirm = mapOf("SSH_ASKPASS_PROMPT" to "confirm")

        val allowed = withAskpassServer(answering("")) { runHelper(it + confirm, listOf("Allow use of key?")) }
        val refused = withAskpassServer(answering(null)) { runHelper(it + confirm, listOf("Allow use of key?")) }

        assertEquals(HelperResult(0, ""), allowed)
        assertEquals(HelperResult(1, ""), refused)
        assertEquals(listOf(AskpassRequest.Confirm("Allow use of key?"), AskpassRequest.Confirm("Allow use of key?")), requests)
    }

    @Test
    fun `ssh's notices aren't sent to Leaf`(): Unit = runBlocking {
        val result = withAskpassServer(answering("unused")) { environment ->
            runHelper(environment + mapOf("SSH_ASKPASS_PROMPT" to "none"), listOf("Confirm user presence for key"))
        }

        assertEquals(HelperResult(0, ""), result)
        assertEquals(emptyList<AskpassRequest>(), requests)
    }

    @Test
    fun `as a credential helper it passes git's input and prints the answer`(): Unit = runBlocking {
        val input = "protocol=https\nhost=example.com\nusername=bob\n\n"

        val result = withAskpassServer(answering("username=bob\npassword=p=w\n")) { environment ->
            runHelper(environment, listOf("credential", "get"), input)
        }

        assertEquals(HelperResult(0, "username=bob\npassword=p=w\n"), result)
        assertEquals(listOf(AskpassRequest.Credential("get", input)), requests)
    }

    @Test
    fun `operations that credential helpers don't know are ignored`(): Unit = runBlocking {
        val result = withAskpassServer(answering("unused")) { environment ->
            runHelper(environment, listOf("credential", "capability"), "")
        }

        assertEquals(HelperResult(0, ""), result)
        assertEquals(emptyList<AskpassRequest>(), requests)
    }

    @Test
    fun `a request without the command's token isn't answered`(): Unit = runBlocking {
        val result = withAskpassServer(answering("secret")) { environment ->
            runHelper(environment + mapOf(ASKPASS_TOKEN_VARIABLE to "0".repeat(64)), listOf("Password: "))
        }

        assertEquals(1, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals(emptyList<AskpassRequest>(), requests)
    }

    @Test
    fun `without Leaf's variables the helper fails, and as a credential helper gives nothing`(): Unit = runBlocking {
        assertEquals(1, runHelper(emptyMap(), listOf("Password: ")).exitCode)
        assertEquals(HelperResult(0, ""), runHelper(emptyMap(), listOf("credential", "get"), "protocol=https\n\n"))
    }

    @Test
    fun `the socket's folder is removed once the command is done`(): Unit = runBlocking {
        val socket = withAskpassServer(answering("x")) { environment -> File(environment.getValue(ASKPASS_SOCKET_VARIABLE)) }

        assertTrue(!socket.parentFile.exists()) { "${socket.parentFile} is left behind" }
    }

    private fun answering(answer: String?): suspend (AskpassRequest) -> String? = { request ->
        requests.add(request)
        answer
    }

    private suspend fun runHelper(variables: Map<String, String>, args: List<String>, input: String? = null) =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(listOf(helper.absolutePath) + args)
                .apply {
                    environment().remove(ASKPASS_SOCKET_VARIABLE)
                    environment().remove(ASKPASS_TOKEN_VARIABLE)
                    environment().remove("SSH_ASKPASS_PROMPT")
                    environment().putAll(variables)
                }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

            process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray()) } }
            val stdout = process.inputStream.readBytes().decodeToString()

            HelperResult(process.waitFor(), stdout)
        }

    private data class HelperResult(val exitCode: Int, val stdout: String)
}
