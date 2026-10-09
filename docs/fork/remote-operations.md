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
  decision. Section 4 has the plan. Stages 0, 1 (push), 2 (fetch and pull), 3 (clone and submodules) and 4 (LFS
  downloads with git-lfs) are done, and so is stage 5: libssh is gone.

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
- 0a and 0c affect upstream Gitnuro too. Reporting them upstream was considered and declined (decision 5).

**Stage 1: push (L), done on branch `feat/git-cli-push`.** Push first: #294 is about push, push doesn't touch the
working tree, and `--porcelain` describes its result completely. What was built is described in the fork changelog
and in CLAUDE.md ("Remote operations"); the plan as written before follows.
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

Found while building it:
- OpenSSH 10 asks about an unknown host with `ED25519 key fingerprint is: SHA256:...`, where older versions wrote
  `is SHA256:....`, and ends its stderr lines with `\r\n`. Both are handled and tested.
- "Push to remote branch" sent `refs/heads/refs/remotes/<remote>/<branch>` with JGit, so it created a branch of that
  name on the remote. Both paths now push to `<branch>`.
- Deleting a remote branch that the remote no longer has succeeds with a full ref name (`git push --delete
  refs/heads/x`), with a warning from the remote, as JGit's `NON_EXISTING` did.
- Not done: ssh's notices (`SSH_ASKPASS_PROMPT=none`, such as "Confirm user presence" for a security key) aren't
  shown, so the processing screen is all the user sees while ssh waits for a touch. Windows isn't tested.

**Stage 2: fetch and pull (M), done on branch `feat/git-cli-fetch-pull`.** The fork changelog and CLAUDE.md
("Remote operations") describe what was built; the plan as written before follows.
- Fetch: `git fetch --prune <remote>` per remote, so each remote reports its own error.
- Pull: `git fetch`, then the existing JGit merge or rebase. That keeps Leaf's autostash, conflict detection and
  built-in LFS, and avoids editor prompts.
- Touches `FetchAllRemotesGitAction` and `PullBranchGitAction`, each with a CLI counterpart.
- Tests: pruning a deleted branch, one failing remote among several, merge and rebase conflicts after a CLI fetch.

Found while building it:
- The commit to merge comes from `FETCH_HEAD`, as with `git pull`: fetching the upstream's remote marks the upstream
  for merge even when the fetch refspec doesn't cover it, and git writes the lines marked for merge first. A
  remote-tracking branch could be stale.
- JGit's merge of a pull that would overwrite local changes returns `FAILED` (or throws `CheckoutConflictException`
  for a fast-forward), and Leaf's JGit pull reported it as "Pull completed". Both pulls now report the files.
- JGit's merge message adds ` into main`, which git leaves out for `main` and `master`. Both pulls keep JGit's.

**Stage 3: clone and submodules (M), done on branch `feat/git-cli-clone`.** The fork changelog and CLAUDE.md
("Remote operations") describe what was built; the plan as written before follows.
- Clone: `git clone --progress --no-checkout`, then today's JGit checkout with built-in LFS, so git-lfs isn't
  required. Progress goes into `CloneState`.
- Submodules: `git submodule update --init --recursive` and `git submodule add`.
- Tests: cloning over `file://` and the sshd, cancelling mid-clone, and checking the partial folder is removed.

Found while building it:
- git removes the folder of a clone that fails, and also when it gets SIGTERM, which is how Leaf stops it. When the
  folder existed (empty) before, git keeps it and removes what's in it. JGit's clone does the same. Leaf's
  `CloneViewModel` created the folder before cloning, so a failed clone always left an empty one; it no longer does.
- Nothing removes what JGit's checkout or the submodules wrote, so Leaf removes a cancelled clone itself. Kotlin's
  `File.deleteRecursively` follows symbolic links to folders, even when it's called on the link itself: with it, a
  test whose clone had a link to an outside folder lost that folder's files. JGit's `FileUtils.delete` doesn't follow
  them. Leaf's "Delete" in the Status pane and "Delete submodule" still use `deleteRecursively`, which is a separate
  fix (it's upstream's code too).
- Cloning an empty repository leaves HEAD unborn, and JGit's checkout of that branch fails, so Leaf skips it.
- With JGit (the fallback), "Clone submodules" did nothing: `setCloneSubmodules` is ignored with `setNoCheckout`, and
  Leaf then only ran `submodule init`, whether the box was ticked or not. JGit's submodule update doesn't go into
  nested submodules either.
- Since git 2.38.1, `git submodule` refuses `file://` and local paths unless `protocol.file.allow=always`
  (CVE-2022-39253). JGit had no such check. Leaf keeps git's default.
- A clone's URL may hold a password, and `GitCli` logged every command line; it now hides the password.

**Stage 4: LFS downloads with git-lfs (S–M), done on branch `feat/git-lfs-transfers`.** Planned on 2026-10-09 from
what Leaf did with git-lfs installed:
- Uploads already went through git-lfs since stage 1: the CLI push runs its `pre-push` hook.
- Switching branches, reset and the like ran git-lfs too: JGit runs the configured `filter.lfs.smudge`
  (`git-lfs smudge -- %f`) once per file, without Leaf's dialogs.
- Only clone and pull forced Leaf's built-in client (`useBuiltinLfs`), which asks the server about one object at a
  time and reaches SSH remotes through libssh (`AuthenticateLfsServerWithSshGitAction`).

What was built: before JGit checks out a clone, or merges or rebases what a pull fetched, `git lfs fetch <remote>
<commit>` downloads the commit's objects, and JGit's checkout reads them from `<git dir>/lfs/objects`. One mechanism
for both, with JGit's checkout and its tests unchanged. It runs only when git-lfs is installed and the commit's root
`.gitattributes` uses LFS. Considered and not chosen: letting `git clone` check out when git-lfs is set up (two
checkout paths for clone, and none for pull), and letting JGit run `git-lfs smudge` per file (a process per file, no
dialogs).

Found while building it:
- git-lfs asks for credentials through `git credential`, so with a helper (Leaf's cache, osxkeychain) git's prompts and
  helpers apply. With none for the URL, it asks `GIT_ASKPASS` itself: `Username for "http://host"`, in double quotes
  without a colon. Leaf's prompt parser didn't know that, so an LFS push without a helper got the generic prompt
  dialog. Both forms now get the credentials dialog.
- git-lfs shows progress only on a terminal unless `GIT_LFS_FORCE_PROGRESS=1`, and writes it to stdout: `git lfs
  fetch`'s, and the `pre-push` hook's, which reaches `git push`'s stdout among the `--porcelain` refs. Off a terminal it
  ends each update with `\n`. Leaf now reads progress from both streams.
- `git clone --no-checkout` installs no git-lfs hooks: git-lfs installs them when it runs as a filter, and with
  `--no-checkout` it never does. Leaf's stage 3 clones therefore had no `pre-push` hook, and their pushes fell back to
  JGit's upload. A clone that uses git-lfs now runs `git lfs update`.
- Over SSH, git-lfs asks the host for the LFS server with `git-lfs-authenticate` through the system's ssh and its
  config: tested with the sshd harness, whose forced command answers it. No libssh is involved.
- Not changed: other checkouts still run `git-lfs smudge` per file without Leaf's dialogs; Leaf's built-in client still
  handles everything when git-lfs isn't installed, including the upload of JGit's push.

**Stage 5: retire libssh (M), done on branch `feat/retire-libssh`.** Approved on 2026-10-09 with its build change
(step 4). The fork changelog and CLAUDE.md describe what was built; the plan as written before follows, then what was
found.

`libssh-rs`, with its vendored libssh 0.11 and OpenSSL, leaves the Rust crate, which keeps the file watcher and the
askpass helper. Every SSH connection and SSH signature then runs the system's OpenSSH, with the user's config, agent,
known_hosts and security keys, as in a terminal. libssh has three uses left, which move first (steps 1 to 3).

What users lose:
- SSH remotes without git, or with "Use git for remote operations" off. Leaf users have git in practice: worktrees
  need it, and on Windows hooks need Git for Windows. HTTPS, `file://` and local remotes keep JGit's fallback.
- LFS uploads over SSH without git-lfs.

SSH signing and LFS downloads over SSH keep working without git-lfs. They need ssh-keygen 8.2 or later and ssh, which
macOS, Git for Windows and desktop Linux distributions have (Leaf's `.deb` doesn't depend on `openssh-client`).

**Step 1: SSH signing with ssh-keygen (M).** It replaces `G/signers/SshSigner.kt` and `Signing` in `R/lib.rs`.

Today `user.signingKey` must be a private key file, which libssh reads. There is no agent, no `key::` literal, no
security key, no `gpg.ssh.program` (so 1Password's `op-ssh-sign` can't sign) and no `~` expansion. Leaf asks for the
passphrase at every signature, and any failure, a missing key file included, shows the passphrase dialog again.

A new `SshProgramSigner`, built like `GpgProgramSigner`, runs what git runs (`sign_buffer_ssh` in gpg-interface.c):
- **Key:** `user.signingKey`, or else the first line that `gpg.ssh.defaultKeyCommand` prints, if it's a literal key.
  Without either, it fails with `SshSigningError.NoSigningKey`, as git does.
  - A literal key (`key::ssh-ed25519 AAAA…`, or the deprecated `ssh-ed25519 AAAA…`) goes into a temp file, and
    ssh-keygen gets `-U`, so the agent signs with it.
  - Anything else is a path. `~/` is expanded as git's `interpolate_path` does, and a relative path starts from the
    working tree.
- **Program:** `gpg.ssh.program`, by default `ssh-keygen`. It's looked up like gpg (`locateGpgProgram`): on the login
  shell's PATH, and on Windows in Git for Windows' `usr\bin` first. It's read from the config directly, because JGit's
  `GpgConfig.program` falls back to `gpg.program`, which git uses only for OpenPGP.
- **Command:** `<program> -Y sign -n git -f <key> [-U] <data file>`. The signature is read from `<data file>.sig`,
  without carriage returns. The data goes in a file, not on stdin, as with git, so that programs such as `op-ssh-sign`,
  which expect git's arguments, work.
- **Passphrase:** ssh-keygen uses the agent when it has the key. Otherwise it asks through `SSH_ASKPASS` (the askpass
  helper, with `SSH_ASKPASS_REQUIRE=force`).
  - OpenSSH 10.3's ssh-keygen asks `Enter passphrase for "<path>": ` (checked here); older versions ask
    `Enter passphrase: `. Both become `SshPassphrase` for the key.
  - So the passphrase is kept per key file for the session (decision 3), shared with ssh's prompts for the same file.
  - A security key's PIN gets the generic dialog. Its touch notice isn't shown, as with push.
- **Errors:** `SshSigningError` gets the same kinds of errors as `GpgSigningError`:
  - the program isn't found, or can't start;
  - it timed out (after 2 minutes);
  - ssh-keygen's own message. If it printed `usage:`, the text says that `ssh-keygen -Y sign` needs OpenSSH 8.2p1
    or later, as git's does;
  - the user closed the passphrase dialog.

  They're thrown as a `CanceledException`, which `JGit.provide` turns back into the error, like
  `GpgSigningException`.
- **Tests:**
  - A fake program that records its arguments and input.
  - With the real ssh-keygen (skipped without it): a plain key, a `.pub` path, an encrypted key through the dialog
    (kept, then asked again after a wrong one), `key::` with a temporary ssh-agent (short socket path),
    `defaultKeyCommand`, and `~/` with a temporary HOME.
  - Signatures are checked with `git verify-commit` and `verify-tag` and an allowed signers file. ED25519 signatures
    are deterministic, so a commit that Leaf signs should be byte for byte the one `git commit -S` makes from the same
    tree and dates.

**Step 2: the built-in LFS client over SSH with the system's ssh (S–M).** It replaces
`G/lfs/AuthenticateLfsServerWithSshGitAction.kt`, which `A/lfs/LfsSmudgeFilter.kt` and `LfsPrePushHook` call when
git-lfs isn't installed.
- **Command:** what git-lfs runs, as captured here with git-lfs 3.8 and a `GIT_SSH_COMMAND` that logs its arguments:
  `ssh [-p <port>] [<user>@]<host> 'git-lfs-authenticate <path> <operation>'`. The remote command is one argument,
  and `<path>` is the SSH URL's path: `org/repo.git` for `git@host:org/repo.git`, `/org/repo.git` for
  `ssh://git@host:2222/org/repo.git`.
- **Bug found:** for a repository with one remote and no tracking branch, `GetLfsUrlGitAction` appends `.git/info/lfs`
  to SSH URLs too, so Leaf sends `git-lfs-authenticate org/repo.git/info/lfs`. git-lfs sends the remote's own path, and
  Leaf will too.
- **Which ssh:** as git-lfs picks it: `GIT_SSH_COMMAND`, then `core.sshCommand`, then `GIT_SSH`, then `ssh`.
  - A command runs through the shell with the arguments appended, Git Bash's on Windows.
  - `core.sshCommand` is read with `git config` when git is usable, so that `includeIf` applies (a different account
    per folder). Otherwise it comes from JGit's config.
  - On Windows, `ssh` is looked up in Git for Windows first, as gpg is.
  - Not handled: plink and TortoisePlink take `-P` for the port, not `-p`, so they only work on the default port.
- **Prompts:** the same askpass environment and `AskpassAnswers` as `GitCliRemoteCommand`. Host keys, passphrases and
  passwords get Leaf's dialogs, and passphrases are kept per key file.
- **Result:** success means ssh exited with 0 and printed JSON. Today any output on stderr fails, but the system's ssh
  writes warnings there on success ("Permanently added … to the list of known hosts"). On failure the server's message
  goes into the `LfsException`.
- **Timeout:** 2 minutes, as nothing can cancel a checkout's filter. As today, it runs once per file. git-lfs keeps
  the answer until it expires, which Leaf doesn't.
- **Tests:** the sshd harness of `GitCliSshTest`, whose forced command records `SSH_ORIGINAL_COMMAND`. They check:
  - the command, against what git-lfs itself sends when it's installed;
  - a key with a passphrase, and an unknown host key, through the dialogs;
  - `core.sshCommand`;
  - the server's error message.

**Step 3: the JGit fallback without SSH (S–M).** JGit's SSH transport used libssh: `GSessionManager`,
`GSshSessionFactory`, `SshRemoteSession`, `SshCredentialsProvider`, `D/credentials/SshProcess.kt` and `D/libssh/`.
They're removed.
- **Failing early:** when JGit meets an SSH remote (an `SshTransport`, so `insteadOf` rewrites are seen),
  `HandleTransportGitAction` gives it a session factory that fails before connecting, with `SshNeedsGitError`.
- **Text:** the error says why git wasn't used and what to do:
  - the setting is off: turn on "Use git for remote operations";
  - no usable git (missing, or older than 2.36): install git, or set its path in Settings;
  - an LFS push that git wouldn't upload (git-lfs is missing, or the repository's `pre-push` hook doesn't run it):
    install git-lfs, or run `git lfs install` in the repository.

  The factory asks `RemoteOperationsBackend` for the reason when it fails, which is cheap, as the locator caches.
  `JGit.provide` finds the error in the cause chain, like `GpgSigningException`.
- A JGit fetch of several remotes still fetches the HTTPS ones, and reports the SSH ones with that text.
- The setting's subtitle says that SSH remotes need it.
- **Tests:** each of the three reasons, against an SSH URL that can't connect, so that a connection attempt would show
  up as a different error. HTTPS and `file://` remotes still go through JGit.

**Step 4: libssh leaves the Rust crate (S, build change).**
- `R/lib.rs` loses `Session`, `Channel`, their holders, `HostKeyState`, `HostKeyCheck`, `ReadResult`, `Signing` and the
  libssh imports. `FileWatcher` and its types stay.
- `rs/Cargo.toml` loses `libssh-rs` and `libssh-rs-sys`. Also `kotars` and `jni`, which nothing in the crate uses
  (decision 7).
- Unchanged: `app/build.gradle.kts` (its Rust tasks build whatever the crate has), the packaging config and
  `release.yml`. The comment in `build_with_tests.yml` that names LibSSH is updated.
- `DEVELOPMENT.md` no longer asks for Perl, and the About dialog no longer credits LibSSH (`AppConstants`).
- Measured before and after: a cold Rust build, `libleaf_rs.dylib` (4.2 MB today) and `Leaf.app`.
- `SshRemoteSessionTest` (10 tests) goes with the code. The git CLI's sshd tests already cover host keys and the
  server's messages.

**Order:** one branch, `feat/retire-libssh`, with the steps in order. Each step is one commit with its tests and
mutation checks. The fork docs (CLAUDE.md, this note, the changelog) follow in a commit of their own, then the full
build. Steps 1 and 2 stand on their own and change behavior for the better: signing gains agent keys, `key::` and
`gpg.ssh.program`. Step 3 changes behavior only for SSH remotes on the JGit fallback. Nothing is tested on Windows.

Found while building it:
- ssh-keygen asks for a passphrase once, and fails on a wrong one, so Leaf runs it again, three times at most, as ssh
  asks three times. Each run after the first counts as a retry, which drops a kept passphrase.
- ED25519 signatures are deterministic: a commit signed by Leaf is byte for byte the one `git commit -S` makes from the
  same tree, dates and message. JGit doesn't end a message with a line break, which git does.
- JGit's `URIish` drops the slash of `ssh://host/~/repo` (its path is `~/repo`), which git-lfs keeps
  (`git-lfs-authenticate /~/repo`). Leaf puts it back for URLs with a scheme.
- JGit's fetch of all remotes never reported a failure: `HandleTransportGitAction` returns JGit's errors instead of
  throwing them, and the fetch only caught exceptions. Without this, SSH remotes on the JGit fallback would have been
  skipped without a word. It now names each remote that failed (upstream code).
- `git config --get` exits with 1 when the key isn't set, and JGit's config can't have a key that git doesn't, so
  reading `core.sshCommand` needs no case of its own for it.
- Measured on this machine (Apple Silicon), cold, with Cargo's caches warm: the release build of `rs/` went from 97 s
  to 72 s, the debug build from 88 s to 18 s, and `libleaf_rs.dylib` from 4,154,464 to 595,072 bytes.

### Decisions

1. The askpass helper: **a Rust binary** (decided on 2026-10-08). It changes the build, so its build steps still need
   approval.
2. A user-visible backend setting during the transition: **yes**, "Use git for remote operations" in Settings, on by
   default (decided on 2026-10-08).
3. SSH passphrases: **Leaf keeps them per key file for the session**, as it did with JGit, and drops one that ssh
   asks for again (decided on 2026-10-08).
4. Whether 0b and 0c are worth doing if the fallback is meant to be rare: **both done** (2026-10-08).
5. Whether to report the host-key and TLS findings to upstream privately: **no** (decided on 2026-10-09).
6. Stage 5, with "Use git for remote operations" off and an SSH remote: **fail with the error of step 3**, which says
   to turn the setting on (decided on 2026-10-09). Not chosen: running git for SSH remotes whatever the setting, as
   Leaf would then have to know each remote's transport before it picks the backend, and a fetch of several remotes
   would mix git and JGit.
7. Stage 5: **also remove `kotars` and `jni`** from `rs/Cargo.toml`, which nothing uses (decided on 2026-10-09).
8. Stage 5, an LFS push over SSH without git-lfs: **fail with the error of step 3** (decided on 2026-10-09). Not
   chosen: uploading with Leaf's built-in client, then `git push --no-verify`, which would skip the user's other
   `pre-push` hooks.
