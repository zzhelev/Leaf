// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Signing a commit or a tag with an SSH key (`gpg.format` `ssh`) failed. [program] is the value of `gpg.ssh.program`. */
sealed interface SshSigningError : GitError {
    /** Neither `user.signingKey` nor a `gpg.ssh.defaultKeyCommand` that prints a key. */
    data object NoSigningKey : SshSigningError

    data class ProgramNotFound(val program: String) : SshSigningError
    data class StartFailed(val program: String, val message: String) : SshSigningError
    data class TimedOut(val program: String, val timeoutSeconds: Long) : SshSigningError

    /** The program printed its usage: it's an ssh-keygen older than OpenSSH 8.2, which has no `-Y sign`. */
    data class SigningUnsupported(val program: String, val output: String) : SshSigningError

    /** The user closed the dialog that asked for the key's passphrase or PIN. */
    data object Cancelled : SshSigningError

    /** The program ran and did not sign; [output] has its own messages. */
    data class SigningFailed(val program: String, val output: String) : SshSigningError
}
