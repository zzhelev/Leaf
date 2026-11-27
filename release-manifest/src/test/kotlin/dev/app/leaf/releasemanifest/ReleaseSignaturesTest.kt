// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.releasemanifest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReleaseSignaturesTest {
    @TempDir
    lateinit var dir: File

    private val manifest = "{\n    \"format\": 1\n}\n".toByteArray()

    @Test
    fun `one signature of one required is enough`() {
        val keys = TestKeys(dir)
        val mac = keys.newKey("mac")
        val trusted = listOf(keys.trusted("mac", mac), keys.trusted("backup", keys.newKey("backup")))

        val check = checkReleaseSignatures(manifest, mapOf("mac" to keys.sign(mac, manifest)), trusted, required = 1)

        assertTrue(check.isEnough)
        assertEquals(listOf("mac"), check.signedBy)
        assertEquals(mapOf("backup" to "No signature"), check.problems)
    }

    @Test
    fun `with two required, one isn't enough and two are`() {
        val keys = TestKeys(dir)
        val mac = keys.newKey("mac")
        val second = keys.newKey("second")
        val trusted = listOf(keys.trusted("mac", mac), keys.trusted("second", second))

        val one = checkReleaseSignatures(manifest, mapOf("mac" to keys.sign(mac, manifest)), trusted, required = 2)
        val two = checkReleaseSignatures(
            manifest,
            mapOf("mac" to keys.sign(mac, manifest), "second" to keys.sign(second, manifest)),
            trusted,
            required = 2,
        )

        assertFalse(one.isEnough)
        assertTrue(two.isEnough)
        assertEquals(listOf("mac", "second"), two.signedBy)
    }

    @Test
    fun `a key that two names share counts once`() {
        val keys = TestKeys(dir)
        val mac = keys.newKey("mac")
        val trusted = listOf(keys.trusted("mac", mac), keys.trusted("again", mac))
        val signature = keys.sign(mac, manifest)

        val check = checkReleaseSignatures(manifest, mapOf("mac" to signature, "again" to signature), trusted, required = 2)

        assertFalse(check.isEnough)
        assertEquals(listOf("mac"), check.signedBy)
        assertEquals("The same key as mac", check.problems["again"])
    }

    @Test
    fun `a signature in a file named after another key doesn't count`() {
        val keys = TestKeys(dir)
        val mac = keys.newKey("mac")
        val second = keys.newKey("second")
        val trusted = listOf(keys.trusted("mac", mac), keys.trusted("second", second))

        val check = checkReleaseSignatures(manifest, mapOf("mac" to keys.sign(second, manifest)), trusted, required = 1)

        assertFalse(check.isEnough)
        assertTrue(check.problems.getValue("mac").contains("another key"), check.problems.toString())
    }

    @Test
    fun `a key that isn't trusted doesn't count, whatever its file is called`() {
        val keys = TestKeys(dir)
        val trusted = listOf(keys.trusted("mac", keys.newKey("mac")))
        val stranger = keys.newKey("stranger")

        val check = checkReleaseSignatures(
            manifest,
            mapOf("stranger" to keys.sign(stranger, manifest), "mac" to keys.sign(stranger, manifest)),
            trusted,
            required = 1,
        )

        assertFalse(check.isEnough)
        assertEquals(emptyList<String>(), check.signedBy)
    }

    @Test
    fun `at least one signature is required, whatever the number says`() {
        val keys = TestKeys(dir)
        val trusted = listOf(keys.trusted("mac", keys.newKey("mac")))

        assertFalse(checkReleaseSignatures(manifest, emptyMap(), trusted, required = 0).isEnough)
        assertFalse(checkReleaseSignatures(manifest, emptyMap(), emptyList(), required = 0).isEnough)
    }

    @Test
    fun `a trusted key that can't be read is a problem, not an error`() {
        val keys = TestKeys(dir)
        val mac = keys.newKey("mac")
        val trusted = listOf(TrustedKey("broken", "ssh-rsa AAAA"), keys.trusted("mac", mac))

        val check = checkReleaseSignatures(
            manifest,
            mapOf("broken" to keys.sign(mac, manifest), "mac" to keys.sign(mac, manifest)),
            trusted,
            required = 1,
        )

        assertTrue(check.isEnough)
        assertTrue(check.problems.getValue("broken").startsWith("The trusted key can't be read"))
    }

    @Test
    fun `Leaf's own trusted keys are its resource file's, every line of it, with names and keys of their own`() {
        val file = File("src/main/resources/dev/app/leaf/releasemanifest/$TRUSTED_KEYS_RESOURCE")
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val names = TRUSTED_RELEASE_KEYS.map { it.name }

        assertEquals(file.readText(), trustedKeysFile())
        assertEquals(parseTrustedKeys(file.readText()), TRUSTED_RELEASE_KEYS)
        assertEquals(lines.size, TRUSTED_RELEASE_KEYS.size, "a line that isn't a key")
        assertEquals(names.distinct(), names)
        val keys = TRUSTED_RELEASE_KEYS.map { Ed25519PublicKey.parse(it.publicKey) }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(REQUIRED_SIGNATURES >= 1)

        if (TRUSTED_RELEASE_KEYS.isNotEmpty()) {
            assertTrue(REQUIRED_SIGNATURES <= TRUSTED_RELEASE_KEYS.size)
        }
    }

    @Test
    fun `a trusted keys file`() {
        val text = """
            # The keys

            github ssh-ed25519 AAAAgithub leaf-release github
              backup   ssh-ed25519   AAAAbackup
            Bad-Name ssh-ed25519 AAAAbad
            short ssh-ed25519
            #commented ssh-ed25519 AAAAcommented
        """.trimIndent()

        assertEquals(
            listOf(TrustedKey("github", "ssh-ed25519 AAAAgithub"), TrustedKey("backup", "ssh-ed25519 AAAAbackup")),
            parseTrustedKeys(text),
        )
        assertEquals(emptyList<TrustedKey>(), parseTrustedKeys(""))
    }

    @Test
    fun `key names`() {
        for (name in listOf("mac", "second", "backup", "work-laptop", "pc2")) assertTrue(isKeyName(name), name)
        for (name in listOf("", "Mac", "-mac", "a b", "../x", "a.sig", "mac/")) assertFalse(isKeyName(name), name)
    }
}
