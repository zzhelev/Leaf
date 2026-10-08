// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Signing a commit or a tag with gpg (`gpg.format` `openpgp`) failed. [program] is the value of `gpg.program`. */
sealed interface GpgSigningError : GitError {
    /** Neither `user.signingKey` nor a committer whose identity selects the key. */
    data object NoSigningKey : GpgSigningError

    data class ProgramNotFound(val program: String) : GpgSigningError
    data class StartFailed(val program: String, val message: String) : GpgSigningError
    data class TimedOut(val program: String, val timeoutSeconds: Long) : GpgSigningError

    /** gpg needed the key's passphrase, but its pinentry could not ask for it, as it needs a terminal. */
    data class PinentryUnavailable(val output: String) : GpgSigningError

    /** gpg ran and did not sign; [output] has gpg's own messages. */
    data class SigningFailed(val program: String, val output: String) : GpgSigningError
}
