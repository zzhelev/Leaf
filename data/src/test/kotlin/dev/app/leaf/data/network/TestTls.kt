// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

private const val KEY_STORE_PASSWORD = "leaf-test"

/**
 * A server's TLS context with a key pair and a certificate for 127.0.0.1 that no trust store holds, made with the JDK's
 * keytool in [dir].
 */
fun selfSignedSslContext(dir: File): SSLContext {
    val keyStoreFile = File(dir, "server.p12")
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val process = ProcessBuilder(
        keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
        "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
        "-storetype", "PKCS12", "-keystore", keyStoreFile.path,
        "-storepass", KEY_STORE_PASSWORD, "-keypass", KEY_STORE_PASSWORD,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "keytool failed: $output" }

    val keyStore = KeyStore.getInstance("PKCS12")
    keyStoreFile.inputStream().use { keyStore.load(it, KEY_STORE_PASSWORD.toCharArray()) }

    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, KEY_STORE_PASSWORD.toCharArray()) }
        .keyManagers

    return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
}
