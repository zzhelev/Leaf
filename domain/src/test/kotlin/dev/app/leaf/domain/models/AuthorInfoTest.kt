// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val NONE = Identity(null, null)
private val GLOBAL = Identity("Personal Name", "personal@example.com")
private val GIT = Identity("Work Name", "work@example.com")

class AuthorInfoTest {
    @Test
    fun `git's identity comes first, as git commits with it`() {
        assertEquals(GIT, AuthorInfo(GLOBAL, NONE, gitIdentity = GIT).identityToUse())
    }

    @Test
    fun `without git's identity, the repository's then the global one, as before`() {
        assertEquals(GLOBAL, AuthorInfo(GLOBAL, NONE).identityToUse())
        assertEquals(
            Identity("Repository Name", "personal@example.com"),
            AuthorInfo(GLOBAL, Identity("Repository Name", null)).identityToUse(),
        )
    }

    @Test
    fun `no identity is asked for when git has one that JGit doesn't see`() {
        assertFalse(AuthorInfo(NONE, NONE, gitIdentity = GIT).isIdentityMissing())
    }

    @Test
    fun `an identity is asked for when neither git nor JGit has a name and an email`() {
        assertTrue(AuthorInfo(NONE, NONE).isIdentityMissing())
        assertTrue(AuthorInfo(Identity("Personal Name", null), NONE).isIdentityMissing())
        assertTrue(AuthorInfo(Identity(null, "personal@example.com"), Identity("", null)).isIdentityMissing())
    }

    @Test
    fun `no identity is asked for when JGit has a name and an email, from either config`() {
        assertFalse(AuthorInfo(GLOBAL, NONE).isIdentityMissing())
        assertFalse(AuthorInfo(Identity("Personal Name", null), Identity(null, "repo@example.com")).isIdentityMissing())
    }
}
