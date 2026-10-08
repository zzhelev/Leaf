// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.domain.errors.RejectReason
import dev.app.leaf.domain.errors.RejectedRef
import dev.app.leaf.domain.errors.RemoteOperationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** git's output as git 2.54 wrote it, captured from pushes to local and SSH remotes. */
class GitOutputParsersTest {
    @Test
    fun `progress is read as git rewrites its lines`() {
        val progress = mutableListOf<GitProgress>()
        val parser = GitProgressParser { progress.add(it) }

        // Split mid-line, as the pipe hands it over
        parser.accept("Enumerating objects: 3, done.\nCounting objects:  33% (1/3)\rCounting obj")
        parser.accept("ects: 100% (3/3), done.\nremote: Compressing objects:  50% (1/2)        \r")
        parser.accept("Writing objects: 100% (3/3), 179 bytes | 179.00 KiB/s, done.\n")

        assertEquals(
            listOf(
                GitProgress("Counting objects", 33),
                GitProgress("Counting objects", 100),
                GitProgress("Compressing objects", 50),
                GitProgress("Writing objects", 100),
            ),
            progress,
        )
    }

    @Test
    fun `readable output drops the progress and keeps the messages`() {
        val stderr = "Enumerating objects: 3, done.\n" +
            "Counting objects:  33% (1/3)\rCounting objects: 100% (3/3), done.\n" +
            "Total 3 (delta 0), reused 0 (delta 0), pack-reused 0 (from 0)\n" +
            "remote: denied by policy        \n" +
            "error: failed to push some refs to '../remote.git'\n"

        assertEquals(
            "remote: denied by policy\nerror: failed to push some refs to '../remote.git'",
            readableGitOutput(stderr),
        )
    }

    @Test
    fun `ssh's lines, which end with a carriage return, are kept`() {
        val stderr = "@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\r\n" +
            "Host key verification failed.\r\n" +
            "fatal: Could not read from remote repository.\n"

        assertEquals(
            "@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\n" +
                "Host key verification failed.\n" +
                "fatal: Could not read from remote repository.",
            readableGitOutput(stderr),
        )
        assertInstanceOf(RemoteOperationError.HostKeyChanged::class.java, remoteOperationError(128, stderr))
    }

    @Test
    fun `the porcelain output lists each ref with its result`() {
        val stdout = "To ../remote.git\n" +
            "*\trefs/heads/main:refs/heads/main\t[new branch]\n" +
            "branch 'main' set up to track 'origin/main'.\n" +
            "!\trefs/heads/side:refs/heads/side\t[rejected] (fetch first)\n" +
            "!\trefs/heads/old:refs/heads/old\t[rejected] (stale info)\n" +
            "!\trefs/heads/x:refs/heads/x\t[remote rejected] (pre-receive hook declined)\n" +
            "-\t:refs/heads/gone\t[deleted]\n" +
            "+\trefs/heads/f:refs/heads/f\tabc1234...def5678 (forced update)\n" +
            "Done\n"

        val refs = parsePushPorcelain(stdout)

        assertEquals(
            listOf(
                PushedRef('*', "refs/heads/main", "refs/heads/main", "[new branch]", null),
                PushedRef('!', "refs/heads/side", "refs/heads/side", "[rejected]", "fetch first"),
                PushedRef('!', "refs/heads/old", "refs/heads/old", "[rejected]", "stale info"),
                PushedRef('!', "refs/heads/x", "refs/heads/x", "[remote rejected]", "pre-receive hook declined"),
                PushedRef('-', "", "refs/heads/gone", "[deleted]", null),
                PushedRef('+', "refs/heads/f", "refs/heads/f", "abc1234...def5678", "forced update"),
            ),
            refs,
        )

        assertEquals(
            listOf(
                RejectedRef("refs/heads/side", RejectReason.FETCH_FIRST, "fetch first"),
                RejectedRef("refs/heads/old", RejectReason.STALE_INFO, "stale info"),
                RejectedRef("refs/heads/x", RejectReason.REMOTE_REJECTED, "pre-receive hook declined"),
            ),
            refs.filter { it.isRejected }.map { it.toRejectedRef() },
        )
    }

    @Test
    fun `a server that refuses another account's key is an access problem`() {
        val error = remoteOperationError(
            128,
            "ERROR: Permission to alice/repo.git denied to bob.\nfatal: Could not read from remote repository.\n\n" +
                "Please make sure you have the correct access rights\nand the repository exists.\n",
        )

        assertInstanceOf(RemoteOperationError.AccessDenied::class.java, error)
        assertEquals(true, error.output.startsWith("ERROR: Permission to alice/repo.git denied to bob."))
    }

    @Test
    fun `a changed host key isn't mistaken for an unverified one`() {
        val stderr = "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@\n" +
            "@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\n" +
            "Host key verification failed.\nfatal: Could not read from remote repository.\n"

        assertInstanceOf(RemoteOperationError.HostKeyChanged::class.java, remoteOperationError(128, stderr))
        assertInstanceOf(
            RemoteOperationError.HostKeyNotVerified::class.java,
            remoteOperationError(128, "Host key verification failed.\nfatal: Could not read from remote repository.\n"),
        )
    }

    @Test
    fun `other failures are told apart by git's messages`() {
        assertInstanceOf(
            RemoteOperationError.AuthenticationFailed::class.java,
            remoteOperationError(128, "git@example.com: Permission denied (publickey).\n"),
        )
        assertInstanceOf(
            RemoteOperationError.AuthenticationFailed::class.java,
            remoteOperationError(128, "fatal: Authentication failed for 'https://example.com/repo.git/'\n"),
        )
        assertInstanceOf(
            RemoteOperationError.ConnectionFailed::class.java,
            remoteOperationError(128, "ssh: Could not resolve hostname nowhere.invalid: nodename nor servname provided\n"),
        )
        assertInstanceOf(
            RemoteOperationError.CertificateProblem::class.java,
            remoteOperationError(128, "fatal: unable to access 'https://x/': SSL certificate problem: self signed certificate\n"),
        )
        assertEquals(
            RemoteOperationError.Failed(1, "local hook says no\nerror: failed to push some refs to '../remote.git'"),
            remoteOperationError(1, "local hook says no\nerror: failed to push some refs to '../remote.git'\n"),
        )
    }
}
