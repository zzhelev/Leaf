// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** `sign-manifest.sh`, which the release workflow runs, against Leaf's own reading and checking of what it writes. */
@DisabledOnOs(OS.WINDOWS)
class SignManifestScriptTest {
    @TempDir
    lateinit var dir: File

    private val script = File("sign-manifest.sh").absoluteFile
    private val version = "1.11.0"

    private data class Run(val exitCode: Int, val output: String)

    /** The release's files as the build jobs leave them, each a different size, with their `.sha256` files. */
    private fun releaseFiles(): File {
        val folder = File(dir, "files").apply { mkdirs() }

        for (kind in ReleaseFileKind.entries) {
            File(folder, kind.fileName(version)).writeText(kind.key.repeat(100 * (kind.ordinal + 1)))
            File(folder, kind.fileName(version) + ".sha256").writeText("not read\n")
        }

        return folder
    }

    private fun trustedFile(keys: TestKeys, vararg named: Pair<String, File>): File = File(dir, "trusted-release-keys")
        .apply {
            writeText(
                "# A comment, as the real file starts with\n\n" +
                    named.joinToString("") { (name, key) -> "$name ${keys.publicKeyLine(key)}\n" }
            )
        }

    private fun run(arguments: List<String>, trusted: File?): Run {
        val process = ProcessBuilder(listOf(script.path) + arguments)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .also {
                it.environment().remove("SSH_AUTH_SOCK")
                if (trusted == null) {
                    it.environment().remove("LEAF_TRUSTED_RELEASE_KEYS")
                } else {
                    it.environment()["LEAF_TRUSTED_RELEASE_KEYS"] = trusted.path
                }
            }
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS))

        return Run(process.exitValue(), output)
    }

    private fun sign(files: File, key: File, name: String, trusted: File?, version: String = this.version, code: String = "38") =
        run(listOf(version, code, files.path, key.path, name, File(dir, "out").path), trusted)

    private val out get() = File(dir, "out")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `it writes a manifest that Leaf reads, signed by the key that Leaf trusts under that name`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")
        val trusted = trustedFile(keys, "github" to github, "backup" to keys.newKey("backup"))
        val files = releaseFiles()

        val run = sign(files, github, "github", trusted)

        assertEquals(0, run.exitCode, run.output)
        assertEquals(setOf(MANIFEST_FILE_NAME, signatureFileName("github")), out.list()!!.toSet())

        val bytes = File(out, MANIFEST_FILE_NAME).readBytes()
        val expected = ReleaseManifest(
            MANIFEST_FORMAT,
            version,
            38,
            releasePage(version),
            ReleaseFileKind.entries.associate { kind ->
                val file = File(files, kind.fileName(version))
                kind.key to ReleaseFile(file.name, file.length(), sha256(file))
            },
        )
        assertEquals(expected, ReleaseManifest.parse(bytes))

        val signature = File(out, signatureFileName("github")).readBytes()
        val check = checkReleaseSignatures(bytes, mapOf("github" to signature), parseTrustedKeys(trusted.readText()))
        assertTrue(check.isEnough, check.toString())
        assertEquals(listOf("github"), check.signedBy)
        assertTrue(keys.sshKeygenAccepts(github, bytes, signature))
    }

    @Test
    fun `the files are listed by kind, sorted, as Leaf's kinds are`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")

        assertEquals(0, sign(releaseFiles(), github, "github", trustedFile(keys, "github" to github)).exitCode)

        val text = File(out, MANIFEST_FILE_NAME).readText()
        val kinds = ReleaseFileKind.entries.map { it.key }.sorted()
        assertEquals(kinds, kinds.sortedBy { text.indexOf("\"$it\"") })
        assertTrue(text.endsWith("}\n"))
    }

    @Test
    fun `a missing file stops it, and nothing is written`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")
        val files = releaseFiles()
        File(files, ReleaseFileKind.WINDOWS_X64_SETUP.fileName(version)).delete()

        val run = sign(files, github, "github", trustedFile(keys, "github" to github))

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains(ReleaseFileKind.WINDOWS_X64_SETUP.fileName(version)), run.output)
        assertFalse(out.exists())
    }

    @Test
    fun `a key that Leaf doesn't trust under that name stops it, and nothing is written`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")
        val stranger = keys.newKey("stranger")
        val trusted = trustedFile(keys, "github" to github)
        val files = releaseFiles()

        val wrongKey = sign(files, stranger, "github", trusted)
        assertNotEquals(0, wrongKey.exitCode)
        assertTrue(wrongKey.output.contains("isn't the one that Leaf trusts as github"), wrongKey.output)

        val unknownName = sign(files, github, "backup", trusted)
        assertNotEquals(0, unknownName.exitCode)
        assertTrue(unknownName.output.contains("Leaf trusts no key named backup"), unknownName.output)

        assertFalse(out.exists())
    }

    @Test
    fun `with no key, it says which secret is missing`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")
        val empty = File(dir, "empty").apply { writeText("") }

        val run = sign(releaseFiles(), empty, "github", trustedFile(keys, "github" to github))

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("LEAF_RELEASE_KEY"), run.output)
        assertFalse(out.exists())
    }

    @Test
    fun `versions, codes and names that can't go into a file name or the manifest are refused`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")
        val trusted = trustedFile(keys, "github" to github)
        val files = releaseFiles()

        for (bad in listOf("1.11", "1.11.0.1", "1.11.0-beta", "v1.11.0", "../1.11.0", "1.11.0\n", "1..1", ".1.1", "1.1.", "")) {
            val run = sign(files, github, "github", trusted, version = bad)
            assertNotEquals(0, run.exitCode, "version \"$bad\"")
            assertTrue(run.output.contains("isn't a release version"), run.output)
        }

        for (bad in listOf("", "3a", "-1", "1 2", "12345678901")) {
            assertNotEquals(0, sign(files, github, "github", trusted, code = bad).exitCode, "code \"$bad\"")
        }

        for (bad in listOf("", "GitHub", "-github", "a/b", "a.b", "a b")) {
            val run = sign(files, github, bad, trusted)
            assertNotEquals(0, run.exitCode, "name \"$bad\"")
            assertTrue(run.output.contains("isn't a key name"), run.output)
        }

        assertFalse(out.exists())
    }

    @Test
    fun `without a trusted keys file given, it reads Leaf's own`() {
        val keys = TestKeys(dir)
        val github = keys.newKey("github")

        val run = sign(releaseFiles(), github, "not-a-trusted-key", trusted = null)

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("Leaf trusts no key named not-a-trusted-key"), run.output)
        assertTrue(
            run.output.contains("src/main/resources/dev/app/leaf/releasemanifest/$TRUSTED_KEYS_RESOURCE"),
            run.output,
        )
    }
}
