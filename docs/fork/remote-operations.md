# Remote operations: JGit and libssh, or the git CLI

| | |
|---|---|
| Question | Should fetch, pull, push and clone run through the git CLI (`GitCli`) instead of JGit with the libssh-rs transport? |
| Verified against | `main` at `e1426c90`; libssh-rs `41424c6` (JetpackDuba's fork, vendored libssh 0.11); JGit 7.7.0 |
| Date | 2026-10-07 |
| Platform | macOS 26.6 arm64, git 2.54.0 (Apple Git-157), OpenSSH 10.3p1, git-lfs 3.8.0 |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`G/` = `data/src/main/kotlin/dev/app/leaf/data/git/`, `R/` = `rs/src/`.

## Summary

- **#294 reproduced on macOS.** "Something failed writing to channel STDIN: … Remote channel is closed" is what Leaf
  shows whenever an SSH server accepts the key, then refuses the command, for example GitHub with another account's
  key. The server's own message is lost. Three bugs in Leaf's SSH plumbing cause it (section 2), and with all three
  patched in a test harness, JGit reported the server's message instead. Stage 0b fixed them.
- **The libssh path has gaps beyond #294.** Some are security problems:
  - It never verifies the server's host key. Leaf pushed to a server whose key had changed, where the git CLI refused
    with "REMOTE HOST IDENTIFICATION HAS CHANGED". Stage 0c fixed this.
  - The Ktor client used for LFS and the update check trusts every TLS certificate (fixed by stage 0a).
  - It has no FIDO2 keys, no `ProxyCommand`, no `Include` with wildcards, and no SSH agent on Windows.
  - The Cancel button does nothing, and SSH reads have no timeout.
- **Recommendation:** move remote operations to the git CLI in stages: push first, then fetch and pull, then clone.
  Keep JGit and libssh only as a fallback when no usable git is found. Fix the TLS problem now, whatever the
  decision. Section 4 has the plan.

## 1. How remote operations work today

### Entry points

Every network operation goes through `G/remote_operations/HandleTransportGitAction.kt`. It installs a session factory
and a credentials provider on each JGit `Transport`, by type:

| Operation | Git action | JGit call |
|---|---|---|
| Push | `G/remote_operations/PushBranchGitAction.kt` | `git.push()` with an explicit refspec, lease and tags options |
| Delete remote branch | `G/remote_operations/DeleteRemoteBranchGitAction.kt` | `git.push()` with an empty source |
| Fetch | `G/remote_operations/FetchAllRemotesGitAction.kt` | `git.fetch()` per remote, with prune |
| Pull | `G/remote_operations/PullBranchGitAction.kt` | `git.pull()` (fetch + merge or rebase), with Leaf's own autostash |
| Clone | `G/remote_operations/CloneRepositoryGitAction.kt` | `Git.cloneRepository()` without checkout, then a checkout with built-in LFS |
| Submodules | `G/submodules/AddSubmoduleGitAction.kt`, `UpdateSubmoduleGitAction.kt` | JGit submodule commands |

Only clone shows progress in the UI (`CloneState`). Push prints its progress to stdout, fetch has an empty monitor,
and pull has none. `RepositoryTabViewModel.cancelOngoingTask` is a `// TODO` that does nothing, so the processing
screen's Cancel button can't stop a hung operation.

### SSH

- **Chain:** `GSessionManager` → `GSshSessionFactory` → `SshRemoteSession` (all in `G/credentials/`) → uniffi
  `Session`/`Channel` (`R/lib.rs`) → libssh-rs → libssh 0.11.
- **`Session::setup`:**
  - It sets the host, the user (only when the URL has one) and the port (only when explicit).
  - It sets `PublicKeyAcceptedTypes` to a list without any `sk-*` (FIDO2) type.
  - It calls `options_parse_config(None)`, which reads `~/.ssh/config`, then `/etc/ssh/ssh_config`. libssh finds
    `~` with `getpwuid`, not `$HOME`.
  - It connects. **No host key check** until stage 0c: nothing called `ssh_session_is_known_server`.
- **Authentication:**
  - `userauth_public_key_auto("")` tries the agent first, then the `IdentityFile` entries (prepended), then
    `~/.ssh/id_ed25519`, `id_ecdsa` and `id_rsa`.
  - The agent is reached through `SSH_AUTH_SOCK` or `IdentityAgent`, over a Unix socket only, so on Windows there
    is no agent (neither the OpenSSH agent's named pipe nor Pageant).
  - On `DENIED`, `SshCredentialsProvider` asks for a "password" and tries it as a key passphrase, then as a
    password. It is cached in memory per URL.
- **Process:** `D/credentials/SshProcess.kt`, `D/libssh/ChannelWrapper.kt` and `D/libssh/streams/*` adapt the channel
  to `java.lang.Process`.
  - JGit's timeout is ignored: `getSession(…, tms)` and `exec(…, timeout)` drop it.
  - Reads block inside libssh with no timeout, and hold the session mutex while they do.
- **libssh build** (`libssh-rs-sys/build.rs`):
  - No `WITH_EXEC`, so `ProxyCommand` fails with "The libssh is built without support for proxy commands."
  - No `HAVE_GLOB`, so `Include ~/.ssh/config.d/*` is silently skipped.
  - `pki_sk.c` isn't compiled, so there is no FIDO2 support.
  - `PKCS11Provider` is unsupported. `UseKeychain` and `AddKeysToAgent` are ignored, which is harmless.
- **Supported ssh_config keywords:** `Host`, `Match` (not `exec`), `HostName`, `Port`, `User`, `IdentityFile`,
  `IdentitiesOnly`, `IdentityAgent`, `CertificateFile`, and `Include` without wildcards.
- **`ProxyJump`:** libssh 0.11 implements it natively on macOS and Linux. My probe failed with "Timeout connecting to
  127.0.0.1", but the probe read a non-default config file, so that result is inconclusive.
- **Also on libssh:** LFS over SSH (`G/lfs/AuthenticateLfsServerWithSshGitAction.kt`, which runs
  `git-lfs-authenticate`) and SSH commit signing (`Signing` in `R/lib.rs`). So removing libssh would also need
  signing to move.

### HTTPS

- **Transport:** JGit's `TransportHttp` over the JDK's HTTP connection.
- **Credentials:** `G/credentials/HttpCredentialsProvider.kt` with `CredentialHelpers` and `CredentialUrl`.
  - Since `2afb6188` it imitates git closely: the helper list in config order, with `includeIf` read through
    `git config --list -z` (`GitCli`), and `get`/`store`/`erase` sent to every helper.
  - Without a helper, it uses Leaf's in-memory cache, then asks with a dialog.
  - That is roughly 750 lines whose job is to behave like git, and they already need the git CLI to read the config.
- **TLS:**
  - It trusts only the JVM trust store (the bundled runtime's `cacerts`), and JGit reads `http.sslVerify`.
  - No `http.sslCAInfo`, `sslCAPath` or client certificates, and no OS trust store (#48).
  - The `sslTrustNow` handling and the "Do not verify SSL" setting are commented out (`// TODO Reenable`).
- **Proxy:**
  - JGit uses the JVM's `ProxySelector`.
  - Leaf's proxy settings are saved but never applied: `App.initProxySettings` is commented out, and
    `JvmSystemProxyRepository` is bound but never called.
  - `java.net.useSystemProxies` isn't set, and neither `https_proxy` nor `http.proxy` is read.

### LFS

- **Filters and hook:** `A/lfs/AppLfsFactory.kt` replaces JGit's LFS factory: the smudge and clean filters, and a
  pre-push hook that uploads objects.
  - Pull and clone check out with `useBuiltinLfs`, which sets `filter.lfs.usejgitbuiltin` in memory only.
  - No git-lfs install is needed.
- **Transfers:** objects move through the Ktor client from `A/di/modules/NetworkModule.kt`, whose `X509TrustManager`
  accepts every certificate. LFS uploads, downloads and the credentials sent with them, and the update check
  (`UpdatesRepository`), are open to a man in the middle. Stage 0a fixed this; see section 4.
- **Credentials:** `G/lfs/ProvideLfsCredentialsGitAction.kt` follows git-lfs's 401 flow, with helpers, the cache and
  the dialog.

### Already on the git CLI

`G/cli/GitCli.kt`:
- It finds git with `GitExecutableLocator` (minimum 2.36, the Xcode shim skipped without the command line tools).
- It adds the login shell's environment, then `LC_ALL=C` and `GIT_TERMINAL_PROMPT=0`.
- `ProcessRunner` closes stdin, drains both streams, and on timeout or cancellation sends SIGTERM to the process
  tree, then SIGKILL after 2 s. Output is returned only when the process exits, and the default timeout is 30 s.

## 2. Reproducing #294

### Setup (throwaway, deleted afterwards)

- **sshd:** `/usr/sbin/sshd -D -f <tmp>/sshd_config` as my own user, with `ListenAddress 127.0.0.1`, `Port 22222`, a
  temp `HostKey`, `AuthorizedKeysFile <tmp>/authorized_keys`, `StrictModes no` and `UsePAM no`.
- **Keys that act like accounts:** each authorized key has `command="<tmp>/serve.sh <account>",no-pty`.
  - `serve.sh` runs `git receive-pack`/`upload-pack` on a bare repo for `alice`.
  - For anyone else it prints `ERROR: Permission to alice/repo.git denied to <account>.` to stderr and exits 1, as
    GitHub does for another account's key.
- **Leaf's own path:** a JUnit harness in `:data` drove push, fetch and clone through `GSshSessionFactory` and
  `SshRemoteSession`, with the real native library loaded through `uniffi.component.leaf_rs.libraryOverride`.
  - A temp `ssh-agent` with one key decided which account libssh used (`SSH_AUTH_SOCK=… ./gradlew :data:test`
    reaches the test JVM).
  - The agent socket must sit in a short path, since a Unix socket path can't exceed 104 bytes.
- **Host aliases:** a throwaway Rust example called the same libssh options as `Session::setup`, but with
  `options_parse_config(Some(<tmp config>))`. That was needed because libssh finds `~/.ssh` through `getpwuid`, and
  the real `~/.ssh/config` must not be edited.

### Results

| Case | git CLI | Leaf (JGit + libssh) |
|---|---|---|
| Push as bob (wrong account) | `ERROR: Permission to alice/repo.git denied to bob.` `fatal: Could not read from remote repository.` | `SshException: Something failed writing to channel STDIN: Custom { kind: Other, error: "Remote channel is closed" }` |
| Fetch and clone as bob | same | same `SshException` |
| Push, fetch, clone as alice | OK | OK |
| Server host key replaced | `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!`, exit 128 | push OK, no warning (the key was never in any `known_hosts` either) |
| Leaf with three fixes simulated (below), as bob | — | `NoRemoteRepositoryException: ssh://…/alice/repo.git: ERROR: Permission to alice/repo.git denied to bob.` for push, fetch and clone; alice still OK |

### Mechanism

1. The server accepts the key, then the forced command writes its error to stderr, exits and closes the channel.
2. **stderr is never read.** JGit copies `process.errorStream` in a thread. `SshChannelInputErrStream.read()` returns
   -1 as soon as `pollHasBytes(true)` is false. That calls libssh-rs `Channel::poll_timeout`, which passes its
   arguments to `ssh_channel_poll_timeout(channel, timeout, is_stderr)` in the wrong order: `is_stderr` (1) becomes
   the timeout, and the timeout (-1) becomes `is_stderr`. So the poll waits 1 ms, the copy thread ends before the
   server writes, and the message never reaches JGit.
3. **The cleanup error replaces the real one.** `BasePackConnection.readAdvertisedRefs` sees EOF and calls
   `close()` before rethrowing.
   - `endOut()` writes a flush packet to the closed channel, and `SshChannelOutputStream` throws `SshException`.
   - `SshException` is a `LeafException`, which extends `Exception`, not `IOException`. `endOut` only catches
     `IOException`, so this exception escapes and hides the original. That is the stack trace in #294.
4. **Fixing 3 alone exposes the next bug.** JGit's `exitStatus()` calls `SshProcess.waitFor()`, which calls
   `isOpen()` on the channel `close()` already destroyed, and throws `IllegalStateException: Channel object has
   already been destroyed`. `exitValue()` also always returns 0.

The simulation wrapped Leaf's `SshProcess` in the harness:
- stderr was polled until the channel closed, and drained before `destroy`;
- write failures became `IOException`s;
- `waitFor` was made safe after `destroy`.

### Host alias and key choice

Probe with `Host lab-alice` → `HostName 127.0.0.1`, `Port`, `User`, `IdentityFile alice`, and bob's key in the agent:

| Config | libssh | OpenSSH |
|---|---|---|
| `IdentitiesOnly yes`, agent has bob | alice: access OK | alice: access OK |
| `IdentitiesOnly yes`, no agent | alice | alice |
| no `IdentitiesOnly`, agent has bob | bob: `ERROR: Permission … denied to bob.` | bob: same |
| no `IdentitiesOnly`, no agent | alice | alice |

On macOS, libssh 0.11 resolves the alias and chooses keys exactly as OpenSSH does. The #294 reporter has two GitHub
accounts and is on Windows. The likeliest story is that the server rejected the key's account and Leaf hid the
reason. Which key libssh picked on Windows (which has no agent there) is **not reproduced**: I had no Windows machine.
I didn't reproduce a hang ("stuck forever" in 1.4.3). The current code could hang, since reads have no timeout and
Cancel does nothing.

## 3. What the git CLI would give and cost

### Gives

| Need | Through git and the system ssh | Verified here |
|---|---|---|
| #294 | git's stderr carries the server's message | yes |
| Host key checks | ssh's `known_hosts`, `StrictHostKeyChecking` | yes |
| #331 system ssh | `ssh` from PATH, `core.sshCommand`, `GIT_SSH_COMMAND`; the login shell gives `SSH_AUTH_SOCK` (1Password, gpg-agent, Secretive) | yes (agent) |
| `ProxyCommand`, `ProxyJump`, `Include` with wildcards | OpenSSH | yes, all three |
| #360 FIDO2 | OpenSSH `sk-*` keys; touch and PIN prompts go through `SSH_ASKPASS` (`SSH_ASKPASS_PROMPT=none` for the touch notice) | no (no security key here) |
| #48 custom CA | `http.sslCAInfo`, `http.sslCAPath`, `GIT_SSL_CAINFO`; schannel on Windows uses the Windows certificate store | no |
| Proxies | `http.proxy`, `http.<url>.proxy`, `https_proxy`/`no_proxy` from the login shell; ssh proxies as above | no (HTTP) |
| Same behavior as the terminal and the agents | hooks, git-lfs, `includeIf`, credential helpers (including Git Credential Manager's own windows), protocol v2 | partly |

- **#326** (smart-card commit signing) is not a remote operation, and this change doesn't affect it. Smart-card *SSH
  authentication* would work through the system ssh.
- **Less code to keep matching git:** most of `CredentialHelpers`/`CredentialUrl` and the hand-rolled LFS credential
  flow would only be needed by the fallback.

### Costs and open questions

**Progress.** `--progress` writes to stderr, with `\r` between updates and `\n` after `, done.`. Captured:

```
Counting objects:   4% (13/302)\r … Counting objects: 100% (302/302), done.\n
Writing objects: 100% (302/302), 901.48 KiB | 5.53 MiB/s, done.\n
remote: Compressing objects:  50% (1/2)        \r
```

- A parser for `^(remote: )?(.+?): +(\d+)% \((\d+)/(\d+)\)` covers the stages, and `LC_ALL=C` keeps the titles in
  English.
- `ProcessRunner` needs a streaming variant: today it returns the output only when the process exits. That variant
  should have no fixed timeout, or an inactivity timeout, instead of 30 s.
- The full stderr is still kept for error messages.

**Credential prompts.** Verified without a terminal, with one script set as both `GIT_ASKPASS` and `SSH_ASKPASS`, plus
`SSH_ASKPASS_REQUIRE=force` (OpenSSH 8.4+) and `GIT_TERMINAL_PROMPT=0`. Git uses askpass before the terminal check.
It received, in turn:
- from ssh: `The authenticity of host '[127.0.0.1]:22222 …' can't be established. … Are you sure you want to continue
  connecting (yes/no/[fingerprint])?`;
- from ssh: `Enter passphrase for key '<path>': `;
- from git: `Username for 'https://host': ` and `Password for 'https://user@host': `.

The prompt text tells Leaf which dialog to show: the existing `HttpCredentialsDialog` and `SshPasswordDialog`, a new
host-key dialog, and a plain text dialog for unknown prompts.

The helper has to reach the running app. Precedents:
- GitHub Desktop: `desktop/desktop-trampoline`, a C executable that talks TCP to the app.
- VS Code: `extensions/git/src/askpass.sh` and `ssh-askpass.sh`, which run Electron as Node and talk over an IPC
  handle.

Options for Leaf (decided on 2026-10-08: (a)):
- (a) A small Rust `[[bin]]` in `rs/`. It starts fast and is a real `.exe` on Windows, which Windows' own OpenSSH
  needs (it starts `SSH_ASKPASS` with `CreateProcess`).
- (b) A jpackage `--add-launcher` running a Java main. It is slower to start (a JVM per prompt) and changes the
  packaging config.
- The channel would be a Unix socket in a 0700 temp folder (loopback TCP on Windows), with a random token passed in
  the environment.

Caching:
- HTTPS: Leaf's in-memory cache can become a credential helper of its own, added last with `-c credential.helper=…`
  (command-line config is appended to the list). git then sends it `store` and `erase` like any other helper. The
  same binary can serve as askpass and as that helper.
- SSH passphrases: either Leaf stops caching them (and relies on ssh-agent, `UseKeychain`, `AddKeysToAgent`), or the
  helper caches per key path for the session. **Decision needed.**

**Cancellation.**
- `ProcessRunner` already kills the whole tree, ssh and `git-remote-https` included. On POSIX, SIGTERM lets git
  remove its lock files. On Windows, `destroy()` is `TerminateProcess`, which can leave `*.lock` files behind.
- A push killed before the server updates refs changes nothing on the server.
- The Cancel button must first be wired (`cancelOngoingTask`).

**LFS.**
- With git-lfs installed, git does everything: the repository's `pre-push` hook from `git lfs install`, and the
  filters from the global config.
- Without git-lfs, a repository set up with `git lfs install` refuses to push. Verified: the hook prints "This
  repository is configured for Git LFS but 'git-lfs' was not found on your path" and the push fails.
- Plan: check `git lfs version` once. When the repository uses LFS and git-lfs is missing, run that operation on the
  JGit path.
- Pull and clone can avoid the question entirely: the CLI only fetches, and JGit with `AppLfsFactory` still does the
  merge, rebase or checkout (stages 2 and 3).

**Windows.**
- `GitExecutableLocator` already knows Git for Windows' locations, and Git Credential Manager shows its own windows.
- The askpass helper must be an `.exe` (see above).
- With `core.sshCommand` pointed at Windows' OpenSSH, the Windows ssh-agent works, which libssh can't do.
- Hooks are run by git itself, through Git Bash.
- CI only tests on Ubuntu, so Windows needs manual testing on a real machine.

**Error mapping.**
- Push: `--porcelain` gives one line per ref on stdout (`!\trefs/heads/side:refs/heads/main\t[rejected]
  (non-fast-forward)`, exit 1). That replaces `statusMessage` parsing.
- Fatal errors exit with 128. Known stderr patterns can be classified, for example:
  - `Permission denied (publickey)`, `Host key verification failed.`, `REMOTE HOST IDENTIFICATION HAS CHANGED`;
  - `Could not resolve hostname`, `Connection refused`;
  - `Authentication failed for`, `SSL certificate problem`;
  - `[remote rejected] … (pre-receive hook declined)`, `stale info`.
- Put them in a new sealed `RemoteOperationError : GitError` that always carries git's stderr. An unknown failure
  still shows git's own text, which never happens today.
- Don't rely on `git fetch --porcelain`: it needs git 2.41, and Debian 12 has 2.39. Leaf rereads refs after a fetch
  anyway.

**Fallback.**
- Use JGit when `GitExecutableLocator` returns `GitNotFound` or `UnsupportedVersion`, never after a CLI failure: that
  could push twice and would mix up the error messages.
- A selecting implementation per git action interface keeps the use cases unchanged.
- Optionally, a "Network operations: Git / Built-in" setting for one or two releases.
- A kept SSH fallback still lacks host key checks unless stage 0c is done.

**Other details.**
- Push keeps Leaf's explicit refspec (`push.default` doesn't apply), and `-u` sets the upstream.
- Leases map to `--force-with-lease=<ref>:<expected>`, tags to `--tags`, and remote branch deletion to `--delete`.
- Commands run in the working tree (`GetWorktreeUseCase`), not the git dir.
- Never set `GIT_SSH_COMMAND` in production, so the user's `core.sshCommand` applies.

## 4. Recommendation and staged plan

**Move remote operations to the git CLI.** The libssh path fails in ways users can't diagnose (#294), lacks host-key
checks, and can't follow the user's ssh setup (#331, #360, proxies). Leaf already spends a growing amount of code
imitating git's credential handling, using the git CLI to do it. Agents push from the same terminals whose behavior
the CLI reproduces. Effort sizes are rough: S, M, L.

**Stage 0: independent of the decision.**
- **0a (S), done on branch `fix/lfs-tls-verification`.** The update check and LFS check TLS certificates. LFS skips
  the check only for a URL whose `http.sslVerify` is false, as git-lfs does
  (`data/src/main/kotlin/dev/app/leaf/data/network/HttpClients.kt`).
- **0b (S–M), done on branch `fix/ssh-server-messages`.** The libssh path reports the server's message. It fixes
  #294's message upstream too.
  - `D/libssh/streams/*` throw `IOException`s, and stderr is read until the output ends.
  - `D/credentials/SshProcess.kt` and `D/libssh/ChannelWrapper.kt` keep stderr and the real exit status when the
    channel closes, and `exitValue` works after `destroy`.
  - No libssh-rs patch was needed: `R/lib.rs` uses libssh-rs's `read_nonblocking`, `is_eof` and `get_exit_status`
    in place of `poll_timeout`.
  - Test: `SshRemoteSessionTest`, the sshd harness above, skipped without OpenSSH or the native library.
- **0c (M), done on branch `fix/ssh-host-key-check`.** Host key verification in `R/lib.rs`
  (`ssh_session_is_known_server`, `ssh_session_update_known_hosts`) and `SshRemoteSession`, with a confirmation
  dialog (`SshHostKeyDialog`), as ssh does with `StrictHostKeyChecking ask`. `StrictHostKeyChecking` itself isn't
  read.
- 0a and 0c affect upstream Gitnuro too, and should go to it as a private security advisory, not a public issue.

**Stage 1: push (L).** Push first: #294 is about push, push doesn't touch the working tree, and `--porcelain`
describes its result completely.
- New fork-only code under `G/cli/` (for example `GitCliPushGitAction`, `GitProgressParser`, `PushPorcelainParser`,
  `RemoteErrorParser`).
- `G/cli/ProcessRunner.kt` and `GitCli.kt`: streaming, cancellation, and askpass variables for network commands.
- Askpass:
  - the helper binary (`rs/`, its build and extraction in `app/build.gradle.kts` and `A/App.kt`). This is a build
    and packaging change, so ask first;
  - a server for its requests, with `D/credentials/CredentialsStateManager.kt`;
  - a host-key dialog (`Screen` in `A/App.kt`, `entry` in `A/ui/AppTab.kt`).
- `RemoteOperationError` in `D/errors/`, its text in `A/ui/Errors.kt` and `strings.xml`.
- Binding in `A/di/modules/TabScopeGitActionsModule.kt` (the selecting implementation), and wiring
  `cancelOngoingTask`.
- `DeleteRemoteBranchGitAction` moves with push.
- Tests:
  - `TestGitCli` and `file://` bare remotes: a new branch sets its upstream; a non-fast-forward push is rejected; a
    lease is stale; tags; a `pre-receive` rejection; a failing local `pre-push`.
  - Parser tests from the captured outputs above.
  - Sshd tests, skipped without sshd: the wrong-account message, the host key prompt and a passphrase key through
    askpass. They need agent sockets in short paths, and `core.sshCommand` in the temp repository's config, so the
    developer's `~/.ssh` is never read.

**Stage 2: fetch and pull (M).**
- Fetch: `git fetch --prune <remote>` per remote, so each remote reports its own error.
- Pull: `git fetch`, then the existing JGit merge or rebase. That keeps Leaf's autostash, conflict detection and
  built-in LFS, and avoids editor prompts.
- Touches `FetchAllRemotesGitAction` and `PullBranchGitAction`, each with a CLI counterpart.
- Tests: pruning a deleted branch, one failing remote among several, merge and rebase conflicts after a CLI fetch.

**Stage 3: clone and submodules (M).**
- Clone: `git clone --progress --no-checkout`, then today's JGit checkout with built-in LFS, so git-lfs isn't
  required. Progress goes into `CloneState`.
- Submodules: `git submodule update --init --recursive` and `git submodule add`.
- Tests: cloning over `file://` and the sshd, cancelling mid-clone, and checking the partial folder is removed.

**Later.** Let git-lfs handle LFS transfers when it's installed. Decide whether to retire libssh, which also needs
SSH signing to move.

### Decisions

1. The askpass helper: **a Rust binary** (decided on 2026-10-08). It changes the build, so its build steps still need
   approval.
2. Whether to keep a user-visible "Built-in" backend setting during the transition.
3. SSH passphrases: rely on ssh-agent, or let Leaf cache them per key for the session.
4. Whether 0b and 0c are worth doing if the fallback is meant to be rare: **both done** (2026-10-08).
5. Whether to report the host-key and TLS findings to upstream privately.
