// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `sign-release.sh`, signing on a computer, and `attach-manifest.sh`, which it and the release workflow attach with:
 * against a release in a folder, through a stand-in for gh, and a repository with the release's tag.
 */
@DisabledOnOs(OS.WINDOWS)
class SignReleaseScriptTest {
    @TempDir
    lateinit var dir: File

    private val script = File("sign-release.sh").absoluteFile
    private val signManifest = File("sign-manifest.sh").absoluteFile
    private val version = "1.11.0"
    private val tag = "leaf-1.11.0"

    private lateinit var keys: TestKeys
    private lateinit var mac: File
    private lateinit var github: File
    private lateinit var trusted: File
    private lateinit var repository: File
    private lateinit var commit: String

    /** The stand-in's state: the tag's commit on GitHub, the release's files, the attestations that fail, its calls. */
    private val gh get() = File(dir, "gh")
    private val release get() = File(gh, "release")
    private val calls get() = File(gh, "calls.log").takeIf { it.exists() }?.readLines().orEmpty()
    private val temporary get() = File(dir, "tmp")

    private data class Run(val exitCode: Int, val output: String)

    @BeforeEach
    fun setUp() {
        keys = TestKeys(dir)
        mac = keys.newKey("mac")
        github = keys.newKey("github")
        trusted = File(dir, "trusted-release-keys").apply {
            writeText("# Leaf's keys\ngithub ${keys.publicKeyLine(github)}\nmac ${keys.publicKeyLine(mac)}\n")
        }
        repository = File(dir, "repository")
        commit = commitWith(appVersion = version, code = 38)
        git("tag", tag)

        release.mkdirs()
        for (kind in ReleaseFileKind.entries) {
            File(release, kind.fileName(version)).writeText(kind.key.repeat(50 * (kind.ordinal + 1)))
            File(release, kind.fileName(version) + ".sha256").writeText("not read\n")
        }
        File(gh, "tag-commit").writeText(commit)
        temporary.mkdirs()
        writeGh()
    }

    private fun git(vararg arguments: String): String {
        repository.mkdirs()
        val process = ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + arguments)
            .directory(repository)
            .redirectErrorStream(true)
            .also {
                it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                it.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git $arguments: $output" }

        return output.trim()
    }

    private fun commitWith(appVersion: String, code: Int): String {
        if (!File(repository, ".git").exists()) git("init", "-q")
        File(repository, "app/src/main/kotlin/dev/app/leaf/AppConstants.kt").apply {
            parentFile.mkdirs()
            writeText(
                "object AppConstants {\n    const val APP_VERSION = \"$appVersion\"\n    const val APP_VERSION_CODE = $code\n}\n"
            )
        }
        git("add", ".")
        git("commit", "-q", "-m", "Version $appVersion")

        return git("rev-parse", "HEAD")
    }

    /** gh as the scripts use it: api for the tag, and release view, download and upload, and attestation verify. */
    private fun writeGh() {
        File(dir, "bin/gh").apply {
            parentFile.mkdirs()
            writeText(
                $$"""
                #!/bin/sh
                state='$${gh.path}'
                printf '%s\n' "$*" >> "$state/calls.log"
                case "$1 $2" in
                    "api "*)
                        [ -f "$state/tag-commit" ] || { echo "gh: No commit found for SHA (HTTP 422)" >&2; exit 1; }
                        cat "$state/tag-commit"
                        ;;
                    "release view")
                        [ -d "$state/release" ] || { echo "release not found" >&2; exit 1; }
                        ls "$state/release"
                        ;;
                    "release download")
                        shift 3
                        while [ $# -gt 0 ]; do
                            case $1 in
                                --pattern) pattern=$2; shift 2 ;;
                                --dir) target=$2; shift 2 ;;
                                *) shift ;;
                            esac
                        done
                        for file in "$state/release"/$pattern; do
                            [ -f "$file" ] && cp "$file" "$target/"
                        done
                        ;;
                    "release upload")
                        shift 3
                        while [ $# -gt 0 ] && [ "$1" != --repo ]; do
                            cp "$1" "$state/release/"
                            shift
                        done
                        ;;
                    "attestation verify")
                        if grep -qx "$(basename "$3")" "$state/bad-attestations" 2>/dev/null; then
                            echo "Error: verifying with issuer \"sigstore.dev\"" >&2
                            exit 1
                        fi
                        ;;
                    *)
                        echo "unexpected: gh $*" >&2
                        exit 2
                        ;;
                esac
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
    }

    private fun run(arguments: List<String>, command: File = script): Run {
        val process = ProcessBuilder(listOf(command.path) + arguments)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .also {
                val environment = it.environment()
                environment.remove("SSH_AUTH_SOCK")
                environment["PATH"] = File(dir, "bin").path + ":" + environment["PATH"]
                environment["HOME"] = dir.path
                environment["TMPDIR"] = temporary.path
                environment["LEAF_REPOSITORY_DIR"] = repository.path
                environment["LEAF_TRUSTED_RELEASE_KEYS"] = trusted.path
            }
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS))

        return Run(process.exitValue(), output)
    }

    private fun signRelease(vararg extra: String, key: File = mac) = run(listOf(version, "--key", key.path) + extra)

    private fun releaseFile(name: String) = File(release, name)

    private fun releaseSignatures(): SignatureCheck {
        val manifest = releaseFile(MANIFEST_FILE_NAME).readBytes()
        val signatures = listOf("github", "mac").associateWith { releaseFile(signatureFileName(it)).takeIf(File::exists)?.readBytes() }

        return checkReleaseSignatures(manifest, signatures, parseTrustedKeys(trusted.readText()))
    }

    /** What the release workflow's sign job attaches: the manifest of the same files, signed with the github key. */
    private fun signedByTheWorkflow(code: String = "38") {
        val files = File(dir, "built").apply { mkdirs() }
        release.listFiles()!!.filter { !it.name.endsWith(".sha256") }.forEach { it.copyTo(File(files, it.name)) }
        val signed = File(dir, "workflow")
        val written = run(listOf(version, code, files.path, github.path, "github", signed.path), signManifest)
        check(written.exitCode == 0) { written.output }
        signed.listFiles()!!.forEach { it.copyTo(File(release, it.name)) }
    }

    @Test
    fun `it signs a release that has no manifest yet, and attaches the manifest and this computer's signature`() {
        val run = signRelease()

        assertEquals(0, run.exitCode, run.output)
        assertTrue(run.output.contains("Signing as mac"), run.output)
        val check = releaseSignatures()
        assertTrue(check.isEnough, check.toString())
        assertEquals(listOf("mac"), check.signedBy)
        assertEquals(38, ReleaseManifest.parse(releaseFile(MANIFEST_FILE_NAME).readBytes()).appCode)

        val attestations = calls.filter { it.startsWith("attestation verify") }
        assertEquals(ReleaseFileKind.entries.map { it.fileName(version) }.toSet(), attestations.map { File(it.split(" ")[2]).name }.toSet())
        for (call in attestations) {
            assertTrue(call.endsWith(
                "--repo zzhelev/Leaf --signer-workflow zzhelev/Leaf/.github/workflows/release.yml " +
                    "--source-ref refs/tags/leaf-1.11.0 --source-digest $commit --deny-self-hosted-runners"
            ), call)
        }
        assertEquals(emptyList<String>(), temporary.list()!!.toList(), "the downloads are left behind")
    }

    @Test
    fun `on a release that the workflow signed, it adds only its signature, and both count`() {
        signedByTheWorkflow()
        val manifest = releaseFile(MANIFEST_FILE_NAME).readBytes()

        val run = signRelease()

        assertEquals(0, run.exitCode, run.output)
        assertArrayEquals(manifest, releaseFile(MANIFEST_FILE_NAME).readBytes())
        assertEquals(listOf("github", "mac"), releaseSignatures().signedBy)
        val uploads = calls.filter { it.startsWith("release upload") }
        assertEquals(1, uploads.size)
        assertFalse(uploads.single().contains("leaf-update.json "), uploads.single())
    }

    @Test
    fun `a release whose manifest isn't the one written here gets nothing`() {
        signedByTheWorkflow(code = "39")
        val before = release.list()!!.toSet()

        val run = signRelease()

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("isn't the one signed here"), run.output)
        assertEquals(before, release.list()!!.toSet())
        assertTrue(calls.none { it.startsWith("release upload") })
    }

    @Test
    fun `a file whose attestation doesn't check out stops it before anything is signed`() {
        File(gh, "bad-attestations").writeText(ReleaseFileKind.LINUX_ARM64_DEB.fileName(version) + "\n")

        val run = signRelease()

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("attestation of ${ReleaseFileKind.LINUX_ARM64_DEB.fileName(version)} doesn't check out"), run.output)
        assertTrue(run.output.contains("sigstore.dev"), run.output)
        assertFalse(releaseFile(MANIFEST_FILE_NAME).exists())
        assertTrue(calls.none { it.startsWith("release upload") })
    }

    @Test
    fun `a file in the release that the workflow didn't build stops it too`() {
        File(release, "Leaf-$version-extra.zip").writeText("added by hand")
        File(gh, "bad-attestations").writeText("Leaf-$version-extra.zip\n")

        val run = signRelease()

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("Leaf-$version-extra.zip"), run.output)
        assertTrue(calls.none { it.startsWith("release upload") })
    }

    @Test
    fun `a missing file stops it, and nothing is attached`() {
        releaseFile(ReleaseFileKind.MACOS_ARM64.fileName(version)).delete()

        val run = signRelease()

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains(ReleaseFileKind.MACOS_ARM64.fileName(version)), run.output)
        assertTrue(calls.none { it.startsWith("release upload") })
    }

    @Test
    fun `the tag must be the same commit here and on GitHub, before anything is downloaded`() {
        File(gh, "tag-commit").writeText("0".repeat(40))

        val moved = signRelease()

        assertNotEquals(0, moved.exitCode)
        assertTrue(moved.output.contains("The tag $tag is $commit here, and ${"0".repeat(40)} on GitHub"), moved.output)
        assertTrue(calls.none { it.startsWith("release download") })

        File(gh, "tag-commit").delete()
        val missingOnGitHub = signRelease()
        assertNotEquals(0, missingOnGitHub.exitCode)
        assertTrue(missingOnGitHub.output.contains("GitHub has no tag $tag"), missingOnGitHub.output)

        git("tag", "-d", tag)
        val missingHere = signRelease()
        assertNotEquals(0, missingHere.exitCode)
        assertTrue(missingHere.output.contains("There's no tag $tag here"), missingHere.output)
    }

    @Test
    fun `the version at the tag must be the one signed`() {
        git("tag", "-d", tag)
        val other = commitWith(appVersion = "1.10.0", code = 37)
        git("tag", tag)
        File(gh, "tag-commit").writeText(other)

        val run = signRelease()

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("APP_VERSION at $tag is \"1.10.0\", not $version"), run.output)
        assertTrue(calls.none { it.startsWith("release download") })
    }

    @Test
    fun `a key that Leaf doesn't trust stops it, and says which line would trust it`() {
        val stranger = keys.newKey("stranger")

        val run = signRelease(key = stranger)

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("<name> ${keys.publicKeyLine(stranger)}"), run.output)
        assertTrue(calls.isEmpty(), calls.toString())
    }

    @Test
    fun `no release, a version that isn't one, and arguments it doesn't know stop it`() {
        release.deleteRecursively()
        val noRelease = signRelease()
        assertNotEquals(0, noRelease.exitCode)
        assertTrue(noRelease.output.contains("There's no release $tag"), noRelease.output)

        for (bad in listOf("1.11", "../1.11.0", "1.11.0-beta")) {
            val run = run(listOf(bad, "--key", mac.path))
            assertNotEquals(0, run.exitCode, bad)
            assertTrue(run.output.contains("isn't a release version"), run.output)
        }

        val unknown = run(listOf(version, "--force"))
        assertNotEquals(0, unknown.exitCode)
        assertTrue(unknown.output.contains("usage:"), unknown.output)
    }

    @Test
    fun `--keep keeps the downloaded files`() {
        val run = signRelease("--keep")

        assertEquals(0, run.exitCode, run.output)
        val kept = temporary.listFiles()!!.single()
        assertEquals(
            ReleaseFileKind.entries.map { it.fileName(version) }.toSet(),
            File(kept, "files").list()!!.toSet(),
        )
    }

    @Test
    fun `attaching refuses a signature file that isn't named after a key`() {
        val signed = File(dir, "signed").apply { mkdirs() }
        File(signed, MANIFEST_FILE_NAME).writeText("{}\n")
        File(signed, "leaf-update.json.Not A Key.sig").writeText("")

        val run = run(listOf(tag, signed.path), File("attach-manifest.sh").absoluteFile)

        assertNotEquals(0, run.exitCode)
        assertTrue(run.output.contains("isn't named after a key"), run.output)
        assertTrue(calls.none { it.startsWith("release upload") })
    }
}
