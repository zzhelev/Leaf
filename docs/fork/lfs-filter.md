# Plan: Leaf's built-in LFS client as git's LFS filter

Status: written 2026-10-16 on `main` at `0c330b21`, approved on 2026-10-26 with every recommendation (see Decisions,
at the end of section 8). Stage 0 was built on 2026-10-26 (CLAUDE.md, "One LFS store, and Leaf's filters for JGit"),
and stage 1 the same day (CLAUDE.md, "Leaf's LFS client as git's filter"), but for open question 2's strip.

Probes ran with git 2.54.0 (Apple Git-157), git-lfs 3.8.0 and JGit 7.7.0, in temp repos. Each ran with an isolated
`HOME`, `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_NOSYSTEM=1` and `PATH=/usr/bin:/bin` (no git-lfs), and with `-Duser.home` for
JGit, which reads `~/.gitconfig` through `user.home`, not `$HOME`. Their results are in the probe index at the end. The
scripts stayed with the planning session's notes; stage 1's tests take over P3 and P4, the stand-in filter.

## Summary

- **The gap is real, and silent in the common case.** Without git-lfs, item 4's merge, cherry-pick, revert and rebase
  write pointer files and succeed when there is no `filter.lfs` config. When `git lfs install`'s config is left
  behind, they fail with exit 128 before changing anything. JGit, the fallback, does the same or worse, so
  `useGitCli()` needs no LFS condition.
- **git's long-running filter protocol fits.** git starts one filter process per command. `-c filter.lfs.process=…`
  beats the user's config and reaches submodules. Every checkout can be delayed, which gives one batch of downloads per
  checkout. git asks for clean all the time, so the filter has to clean too.
- **Recommended design:** the helper relays the filter's pipe to Leaf through the askpass socket, and Leaf speaks the
  protocol in Kotlin with its built-in client. Smudge reads the store or downloads, clean writes the store, and both
  follow git-lfs's rules byte for byte. Item 4's operations download what they may check out before git starts, so a
  failed download changes nothing.
- **It retires** `GitCliPullBranchGitAction`, `pullNeedsBuiltinLfs` and `pulledCommitUsesLfs`. Pull goes back to
  `git pull` or JGit.
- **Two existing bugs turned up**, neither caused by item 4. They are prerequisites (stage 0):
  - Leaf's own staging, and git outside Leaf, store the raw bytes of an LFS file once the working tree has the real
    content and no clean filter is configured. Leaf's clone and hybrid pull leave the working tree that way.
  - JGit's `Lfs` keeps a linked worktree's objects in that worktree's own git dir.
- **Effort:** L in all, in five stages. Stage 1, the filter for the commands that already run with askpass, is M–L.

## 1. The gap (probes P1, P2, P5, P8)

Each operation ran as Leaf runs it: `merge --ff|--no-ff --no-edit`, `cherry-pick <c>`, `revert --no-edit <c>` and
`rebase <branch>`. The repository's `.gitattributes` has `*.bin filter=lfs diff=lfs merge=lfs -text`, and the branch
changes `a.bin` and adds `b.bin`.

| Case | git (item 4) | JGit 7.7 (the fallback, and Leaf before item 4) |
| --- | --- | --- |
| A: no `filter.lfs` config (git-lfs never installed) | Exit 0. Pointer files, and `git status` is clean. | Same: pointer files. |
| B: `git lfs install`'s config (`process`, `smudge`, `clean`, `required=true`), no git-lfs on PATH | Exit 128: `git-lfs filter-process: git-lfs: command not found`, `fatal: the remote end hung up unexpectedly`. HEAD, the index and the working tree are unchanged, and nothing is left behind, not even `rebase-merge`. | `FilterFailedException` (return code 127). HEAD is unchanged, but a `._a.bin<random>.tmp` stays in the working tree, and revert and rebase leave `b.bin` deleted. |
| C: git-lfs installed, never `git lfs install`ed (not probed: git runs no filter that it isn't configured with) | As A. | As A. |

What Leaf shows:
- **Case A:** the toast ("Merge completed", …). The files are pointers, and nothing shows in Unstaged, so nothing tells
  the user until they open a file.
- **Case B:** `ErrorDialog`, with `"git merge --ff --no-edit feature" failed with exit code 128: git-lfs filter-process:
  git-lfs: command not found …`. Merge's backup stash is deleted, as nothing changed (`IsUnchangedSinceGitAction`).
- **Case C also affects `git pull`:** `RemoteOperationsBackend.gitLfsWorks` only runs `git lfs version`. So a user who
  installed git-lfs and never ran `git lfs install` pulls pointer files.

So item 4 didn't regress. JGit never had `useBuiltinLfs` for these operations: only pull and clone set it. In case B,
git now fails more cleanly than JGit did.

Other git commands that Leaf runs also check out or stage. They have the same gap through the same mechanism, though I
didn't probe them one by one:
- the worktree steps, through `RunGitCommandGitAction`: `git worktree add`, `git switch`, `merge --squash`,
  `merge --no-ff --no-commit` and `rebase --no-autostash`;
- `ResolveConflictGitAction`: `checkout --ours|--theirs`, `add` and `rm`;
- the put-back's `git stash apply` (`Autostashes`), and `git reset --merge` (the squash merge's Abort);
- `git mergetool`, and `git submodule update|add`.

All of them run through plain `GitCli`, without the askpass server, except the submodule commands.

These turned up along the way. They exist today, and item 4 didn't cause them:
- **P5, raw bytes staged.** This is case A, with the real content in the working tree, as Leaf's clone and its hybrid
  pull leave it (JGit's checkout with the built-in LFS).
  - Once git or JGit compares the content, as for a touched file or one that is racily clean, the file shows as
    modified.
  - `git add` and JGit's `AddCommand` then store the raw bytes, so a commit puts the binary into git, not into LFS.
  - JGit stores the pointer only with `filter.lfs.usejgitbuiltin` on, and `useBuiltinLfs` sets it during pull and
    clone only.
  - Leaf's Stage button can do this today, and so can an agent's `git add -A` in a terminal.
- **P8, a linked worktree's store.** JGit's `Lfs(repository)` uses `repository.directory/lfs`. For a linked worktree
  that is `<common git dir>/worktrees/<name>/lfs/objects`, where git-lfs uses `<common git dir>/lfs/objects`.
  - These parts of Leaf don't see git-lfs's objects or the main worktree's in a linked worktree: the smudge filter,
    the choice cards (`ConflictPreviews`, `WriteConflictVersionGitAction`), the pre-push upload
    (`UploadLfsObjectGitAction`) and `LfsCleanFilter`.
  - So they download those objects again, and `git worktree remove` deletes them.
- **JGit's other checkouts:** switching branches, reset, stash and discard write pointers in case A, and fail with the
  temp file in case B. P2 shows the mechanism, and CLAUDE.md already describes it.

## 2. What git asks of a filter (P3, P4, P6, P7)

A stand-in filter logged what git sends. It is a Python script that speaks the protocol from gitattributes(5), "Long
Running Filter Process", and smudges and cleans from the store. git-lfs's config was in the global config, and
`-c filter.lfs.process=<stand-in> -c filter.lfs.required=true` was on the command line, as Leaf would pass them.

- **The command line wins.**
  - `filter.<driver>.process` beats `smudge` and `clean` (`apply_filter` in convert.c). The probe confirms it: git-lfs's
    `smudge` was configured, and the stand-in ran.
  - `-c` applies after every config file. It also reaches every git that the command starts, through
    `GIT_CONFIG_PARAMETERS`: `git submodule update` ran the filter in the submodule, with the submodule's own working
    tree and store.
  - For a clone, it has to be `git -c … clone`, as `git clone -c` writes the key into the clone's config.
- **Where it runs:** at the top of the working tree. The environment has `GIT_CONFIG_PARAMETERS`, `GIT_EXEC_PATH`,
  `GIT_PREFIX` and `GIT_REFLOG_ACTION`, but not `GIT_DIR`.
- **Delay:** git offers `clean`, `smudge` and `delay`.
  - Every checkout sent `can-delay=1`: merges (fast-forward or not), cherry-pick, revert, each step of a rebase, the
    pull's merge, a clone, a submodule update, and the autostash's put-back.
  - Each checkout then has one `list_available_blobs` round, which lists every LFS file that it writes. Leaf can
    download them all there, in one batch.
- **Metadata:** each smudge comes with `treeish=` and `blob=`. Only a clone's come with `ref=`.
- **git asks for clean all the time,** not only when staging.
  - git's index refresh cleans every LFS file whose stat changed or that is racily clean. It runs before and after
    merges, cherry-picks and rebases.
  - The autostash's `git stash` cleans the edited LFS files.
  - Per convert.c (not probed), with `required=true` a filter without `capability=clean` makes git die (`clean filter
    'lfs' failed`). Without `required`, git would hash the raw bytes. So the filter has to clean.
- **Lazy start:** the process starts only when a file with `filter=lfs` needs filtering, so a repository without LFS
  never starts it.
  - Leaf can therefore pass the filter whenever git-lfs isn't set up, without guessing whether the repository uses
    LFS.
  - That also covers a pull that brings LFS in for the first time, the pull plan's known gap.
- **Conflicts:** `merge=lfs` names no driver, as `git lfs install` doesn't define `merge.lfs.driver`.
  - So git text-merges the two pointers, with the markers after their shared `version` line, and smudges the result.
  - The filter must not read that as a pointer. git-lfs passes it through (P6), and so does JGit's
    `LfsPointer.parseLfsPointer`, which refuses unknown lines and a second `oid`.
  - Leaf's resolver then shows the `Lfs` choice cards, as it does today with git-lfs.
  - The stand-in's loose parser read the merged text as theirs's pointer and wrote theirs's content: a strict parser
    matters.
- **A failure:** the filter answered `status=error` or `abort`, with `required=true`.
  - git exits with 128 and `fatal: b.bin: smudge filter lfs failed`.
  - HEAD and the index stay as they were, but the files that git wrote before are in the working tree: `a.bin`, shown
    as ` M a.bin`.
  - A second try is then refused ("Your local changes … would be overwritten").
  - git-lfs leaves the same state in a terminal (P7). With `lfs.skipdownloaderrors=true`, git-lfs writes the pointer
    and the merge succeeds.
- **git-lfs's rules to copy** (P6):
  - clean of an empty file is empty, and clean of a pointer is that pointer;
  - smudge of an empty input, or of anything that isn't a pointer, gives it back unchanged;
  - objects go to `<store>/objects/aa/bb/<oid>`;
  - the pointer is `version https://git-lfs.github.com/spec/v1\noid sha256:<oid>\nsize <n>\n`;
  - with `GIT_LFS_SKIP_SMUDGE=1`, smudge writes the pointer.

## 3. Design (recommended)

### When Leaf's filter applies

Leaf decides before git starts, as for push, for each command that may check out or stage (§6 lists them):
- **git-lfs is set up:** git's config, `includeIf` included, has `filter.lfs.process` or `filter.lfs.smudge`, and
  `git lfs version` works. Nothing changes, and git-lfs does the work.
- **Otherwise (cases A, B and C):** Leaf passes `-c filter.lfs.process=<helper> lfs-filter` and
  `-c filter.lfs.required=true`. `required` makes a failed download fail the operation, as with git-lfs, instead of
  writing the pointer without a word.

The config comes from the cached repository's JGit config, which has the `includeIf` layer
(`IncludedConfigRepository`). So only `git lfs version` costs a process, about 10 ms, as `gitLfsWorks` does now.

### The helper relays, Leaf speaks the protocol

**The helper:** `leaf-askpass lfs-filter`.
- It connects to the askpass socket and sends today's three fields: the token, the kind `lfs-filter`, and its working
  directory.
- It waits for Leaf's `1`, so that none of git's bytes arrive with the header. Today's `readFields` reads ahead 4 KB at
  a time.
- Then it copies its stdin to the socket and the socket to its stdout until either ends. That takes two threads, the
  standard library only, about 60 lines.
- git runs `filter.<driver>.process` through the shell, so the path is quoted as for the credential helper, without
  the `!`.

**Leaf's side:** `AskpassServer` hands an `lfs-filter` connection to a filter session for as long as the connection
lasts, instead of answering once. The session is fork-only, in the data module (`data/git/lfs/filter/`):
- **pkt-lines** (`PktLine.kt`): 4-hex-digit lengths, flush packets, and at most 65,516 bytes of data per packet.
- **The handshake:** it offers `clean`, `smudge` and `delay`.
- **smudge:**
  - It parses the content with `LfsPointer.parseLfsPointer`, and gives back anything else as it came.
  - A pointer whose object is in the store, at the pointer's size, streams from that file.
  - For one whose object isn't there, it answers `status=delayed` when git sent `can-delay=1`, and downloads at once
    otherwise.
- **`list_available_blobs`:**
  - It downloads every delayed object that it lacks, in one batch.
  - Then it lists them all, the failed ones too, so that git asks for each.
  - It answers `status=error` for one that failed.
- **clean:**
  - It streams the content into a temp file in the store's `tmp`, computing the SHA-256 with the JDK.
  - It moves the file into place unless the object is already there, and answers with the pointer.
  - A pointer comes back unchanged, and an empty file comes back empty.
- **git-lfs's settings:** it reads `GIT_LFS_SKIP_SMUDGE` and `lfs.skipdownloaderrors`, as git-lfs does.
- **The repository:** it finds it from the helper's working directory, which is the tab's or a submodule's. The store
  is in the common git dir (stage 0).

**Considered and not recommended:** the protocol, the SHA-256 and the store in the helper, which would then talk to
Leaf only to download.
- For: it moves no file contents through the socket, and objects already in the store need no server.
- Against: it is a second implementation of the pointer and store rules, in Rust. The SHA-256 would be hand-written,
  as the crate uses the standard library only, or come from a new dependency. And every test of those rules would need
  the built helper.
- A Unix socket moves GB/s, so the relay costs some CPU but little time next to the disk.

### Downloads, progress, Cancel, credentials, SSH

- **Downloads:**
  - One batch request covers the objects of a `list_available_blobs` round. The batch API takes many objects, and
    `GetLfsObjectsGitAction` already takes a list, as the pre-push hook does.
  - `DownloadLfsObjectGitAction` then downloads each one and checks it: size and SHA-256, a temp file, a rename.
  - This is a new `FetchLfsObjectsGitAction` next to `FetchLfsObjectGitAction`. `LfsSmudgeFilter` and the choice cards
    keep using the latter.
- **The server:** `IGetLfsUrlGitAction` with no remote, so git-lfs's pick: the branch's remote, `remote.lfsdefault`,
  the only remote, then `origin`. `LfsSmudgeFilter` does the same. During a rebase HEAD is detached, as it is for
  git-lfs.
- **Progress:** "Downloading LFS objects 3/10 (12 MB of 40 MB)", through the command's `onProgress`.
  `GitCliRemoteCommand.run` already passes git's own progress there.
- **Cancel:** the processing screen's Cancel kills git and the helper. The socket closes, the download's coroutine is
  cancelled with `withAskpassServer`'s scope, and the temp file is removed.
- **Credentials:** `ProvideLfsCredentialsGitAction`, as for the choice cards: the credential helpers, Leaf's cache,
  then the dialog. `CredentialsStateManager` is app-wide.
- **SSH:** `AuthenticateLfsServerWithSshGitAction`, with the system's ssh and Leaf's askpass, once per batch.
- **Errors:**
  - The session keeps each failure (`LfsError`) by path.
  - When git then fails with `smudge filter lfs failed`, the operation returns a new `LfsFilterError(paths, error)` in
    place of git's text.
  - That error says what failed (the credentials dialog was closed, a 404, a corrupt download, no server found), and
    which files git had already written.

### Download first, for item 4's operations

A failed download in the middle of a checkout leaves a half-updated working tree, which blocks a second try (§2).
Before a merge, a cherry-pick, a revert or a rebase, Leaf knows both sides. So it can download what the checkout may
need before git starts, as it already does for partial clones:
- `PartialCloneFetch`'s `prefetchForMerge`, `prefetchForCherryPick` and `prefetchForRebase` give the blobs where the
  trees differ.
- Those that are LFS pointers (1,024 bytes at most), and whose objects aren't in the store, are downloaded in one
  batch, with progress and Cancel.
- A failure stops the operation before git starts, with nothing changed. The filter still downloads anything this
  step missed.

This doesn't work for `git pull`, whose commit comes from its own fetch, nor for a clone or a submodule update. There, a
failed download leaves what git-lfs leaves, and the error says which files to discard. Fetching first and then running
`git pull` would cover the pull too, at the cost of a second fetch (open question 4).

### Leaf's own JGit operations (stage 0)

Once git writes the real content, Leaf's own staging, status, stash and checkouts have to clean and smudge too.
Otherwise P5 happens inside Leaf.
- **The built-in LFS, always on without git-lfs:** when git-lfs isn't set up, `JGit.open` puts
  `filter.lfs.usejgitbuiltin=true` into an in-memory layer under the repository's own config, next to
  `IncludedConfigRepository`'s layer, which `config.save()` never writes. That replaces `useBuiltinLfs`'s set and
  restore.
  - JGit then uses `LfsSmudgeFilter` and `LfsCleanFilter` everywhere.
  - Its checkouts (switching branches, reset, stash, discard) then download objects, as a clone does today: one object
    per request, without progress or Cancel (open question 3).
- **One store:** `Lfs(repository)` gets a replacement, `lfsStore(repository)`, whose root is the common git dir. Every
  user listed in §1 takes it, the filter too.

### Windows

- The helper reaches Leaf on a loopback port, as it does for askpass. Rust's stdin and stdout don't translate line
  endings.
- git runs the process through Git Bash's shell, so the path is quoted as `credentialHelperCommand` quotes it.
- Git for Windows ships git-lfs and sets the filter in its system config, so Leaf's filter mostly won't apply there.
- Nothing is tested on Windows.

### Not covered

- **git-lfs's hooks in case B,** in a repository where `git lfs install` wrote `post-checkout`, `post-merge`,
  `post-commit` and `pre-push`. Without git-lfs, they print "git-lfs was not found on your path" and exit with 2.
  `git worktree add` and `git switch` return that as their own exit code. Leaf can't skip them without skipping the
  user's own hooks.
- **git-lfs settings and features:** `lfs.fetchinclude`, `lfs.fetchexclude`, `lfs.storage`, `lfs.concurrenttransfers`
  (git-lfs runs 8 downloads at a time) and file locking. Pointer extensions (`ext-0-…`) pass through, as JGit doesn't
  read them as pointers.
- **git outside Leaf in case A:** Leaf configures nothing in the repository. So a terminal or an agent sees the real
  content with no filter (P5). See open question 2.

## 4. Options

| | What | For | Against |
| --- | --- | --- | --- |
| **A (recommended)** | The relay filter (§3), with the download first for item 4's operations | Covers every git command Leaf runs. git's merge engine, hooks and identity stay. One batch per checkout. Retires the hybrid pull. | L. File contents go through the socket. |
| A′ | The same, with the protocol and the store in the helper | No contents through the socket. Local objects need no server. | Two implementations of the LFS rules. SHA-256 in Rust that uses the standard library only, or a new crate. |
| B | The download first, and a filter that only reads the store | No downloads during the command | `git pull` needs a fetch first, and an object the download missed fails the command. The filter is still needed, which is most of A's work. |
| C | Item 4's operations go to JGit with `useBuiltinLfs` when the repository uses LFS and git-lfs doesn't work, as pull did | S, and works now | Gives up item 4 for those repositories: ort, hooks, git's identity and signing. Doesn't cover the worktree steps or the conflict actions. |
| D | Tell the user to install git-lfs, in a strip like `UnsupportedIndexBanner` | Small | "No git-lfs install is needed" no longer holds. |

## 5. Retiring the hybrid pull, and item 4's `useGitCli()`

Once `git pull` runs with Leaf's filter:
- `SelectingPullBranchGitAction` goes back to two ways: `git pull` (`GitPullGitAction`), or JGit's
  `PullBranchGitAction` when the setting is off or git or the helper is missing.
- **Removed:**
  - `GitCliPullBranchGitAction` (227 lines);
  - `RemoteOperationsBackend.pullNeedsBuiltinLfs` and `pulledCommitUsesLfs`;
  - `FetchHead.kt`, as only `GitCliPullBranchGitAction` parses `FETCH_HEAD`;
  - the pull's use of `GitLfsFetch`.
- **Kept:** `usesLfs` for push, and `PullOutcome.kt` for JGit's pull.
- **Tests that use `GitCliPullBranchGitAction`:**
  - `GitCliFetchPullTest`: its pull cases.
  - `GitCliLfsTest`: the hybrid pull becomes a `git pull` with Leaf's filter.
  - `PartialCloneTest` and `GitCliHttpsTest`.
  - `GitOnlyConfigValuesPullTest` and `IncludedConfigRepositoryTest`: they check JGit's merge after git's fetch, and
    JGit's own pull keeps them meaningful.
  - `RemoteOperationsBackendTest`.
- **Later, optional (stage 5):** a clone checks out with `git -c filter.lfs.process=… clone`, or with git-lfs. That
  drops the JGit checkout after `--no-checkout`, the clone's `useBuiltinLfs` and `GitLfsFetch`.

Item 4's `useGitCli()` needs no LFS condition. JGit writes the same pointers in case A, and fails worse in case B (P2).
Option C is the only interim that would help, at item 4's cost. Recommended: no interim, as this item replaces it.

## 6. Stages and effort

0. **One store, and JGit's built-in LFS when git-lfs isn't set up (S–M).**
   - `lfsStore` in the common git dir, for every `Lfs(repository)`.
   - `usejgitbuiltin` in JGit's in-memory layer, so `useBuiltinLfs` is no longer needed.
   - This is worth doing on its own: it fixes P5 inside Leaf, and P8. It changes what JGit's checkouts do without
     git-lfs: they download.
1. **The filter, for the commands that run with askpass (M–L).**
   - The helper's relay (`rs/leaf-askpass.rs`, already a build input) and the `lfs-filter` connection in
     `AskpassServer`.
   - The session, and `FetchLfsObjectsGitAction`.
   - The rule, in `RemoteOperationsBackend` or a new `LfsFilterChoice`.
   - The `-c` arguments in `GitCliRemoteCommand.run`. That covers `GitCliOperations`: merge, cherry-pick, revert,
     rebase, `GitCliRebase`'s Continue, Skip and Abort, and `git pull`. It also covers the submodule commands.
   - `LfsFilterError`, and its text.
2. **The download first, for item 4's operations (S–M):** the pointer check over `PartialCloneFetch`'s blob sets, and
   the batch download, before `GitCliOperations.run`.
3. **Retire the hybrid pull (S),** with its tests (§5).
4. **The other commands (M).** The worktree steps (`RunGitCommandGitAction`), `ResolveConflictGitAction`,
   `Autostashes`' put-back, `AbortSquashMergeGitAction` and `OpenMergeToolGitAction` run through
   `GitCliRemoteCommand`, where they also get Cancel and Leaf's dialogs. Or else `GitCli.execute` starts the same
   server.
5. **Clone with git's checkout (M), optional.**

The total is about L. Each stage is one branch, with its tests, mutation checks and a manual run on macOS: a `/tmp`
demo with an LFS server.

Found while building stage 0 (2026-10-26):
- JGit's `LfsPointer.parseLfsPointer` reads a pointer that a merge's conflict markers broke as a pointer, to the side
  last in the file. Leaf has its own parser, with git-lfs's rules, which `LfsStoreTest` compares with git-lfs's clean,
  case by case.
- JGit's `LfsBlobFilter.smudgeLfsBlob`, which `AppLfsFactory` used for diffs, blame and JGit's merges, downloaded a
  missing object with JGit's own LFS client. Leaf's replacement reads the store only.
- git-lfs reads `GIT_LFS_SKIP_SMUDGE` as true for `1`, `t`, `true`, `on` and `yes`, in any case, and with no spaces
  around them.
- JGit's status doesn't refresh the index as git's does, so it cleans a touched LFS file at every refresh. Leaf's clean
  keeps files up to 4 MB in memory, so that only larger ones go through the store's `tmp`.
- `useBuiltinLfs` stays, where §6 had it go: the clone and the hybrid pull force Leaf's filters even where git-lfs is
  set up, as `git lfs fetch` has put the objects in the store. JGit's `getNames` reads only the top layer, so its
  restore leaves the in-memory layer alone (`BuiltinLfsLayerTest`).
- git-lfs takes a pointer of up to 1,024 bytes, extension lines included; it trims white space around the pointer but
  not inside its lines, and wants the oid in lower case.
- The manual run found that a working tree file's `ObjectLoader` (JGit's `ContentSource`) is large whatever its size and
  has no cached bytes, so the diff of an edited LFS file failed. `lfsContentOf` reads the head of a stream instead.

Found while building stage 1 (2026-10-26):
- The rule reads git's config with `git config --get-regexp` in the command's folder, not JGit's config: the command
  has a folder, not a repository, and a clone has none yet. It costs one process, about 10 ms, per command.
- git writes a request's whole content before it reads the answer. A session that answered a large file that isn't a
  pointer as it read it would block, as would git, once the pipe and the socket were full. So the session reads the
  content first, in memory up to 4 MB and in a temporary file past it.
- A blocking read on the socket doesn't see the command's cancellation, so cancelling closes the connection. git and
  the helper are killed with the command; the test holds a download at `FakeLfsServer` and cancels it. What closes
  the connection is a coroutine, which runs no `finally` if it's cancelled before it starts: a cancellation right
  after the filter started hung 18 times in 20, until it was started `UNDISPATCHED`.
- `GitLfsFetch` asked `git lfs version` itself; it now takes `GitLfsSetup`'s answer, which the rule asks for too, so
  git-lfs runs once a minute for both.
- The tests met P5 in their own setup: a commit by git outside Leaf, after Leaf's filter had written the real content,
  staged the file's bytes, and the next pull refused, as the file then differed from the index.
- `includeIf "gitdir:<path>/.git/"` doesn't match the git dir itself on git 2.54; `gitdir:<path>/.git` does.

## 7. Tests

Temp repositories only. `FakeLfsServer` serves the objects: the batch API, the basic transfer, Basic authentication.
git runs with `pathWithoutGitLfs`, and the helper comes from `builtAskpassHelper()`. Tests are skipped without it.

- **Unit tests (data):**
  - pkt-lines: lengths, flush, 65,516-byte packets, and a stream that ends inside a packet;
  - the session, against scripted conversations: a handshake that offers fewer capabilities; smudge from the store;
    smudge delayed, then listed; smudge of a non-pointer, of a conflicted pointer and of an empty input; clean of a new
    file, of a pointer and of an empty file; an object larger than many packets; error and abort;
    `GIT_LFS_SKIP_SMUDGE` and `lfs.skipdownloaderrors`.
- **Byte for byte against git-lfs** (skipped without git-lfs): the same operations in two copies of a repository, one
  with git-lfs on PATH and one with Leaf's filter. The working files, `git ls-files -s` and the store must match. Clean
  in every case of P6.
- **Each operation with Leaf's filter, in cases A, B and C:**
  - merge (fast-forward or not), cherry-pick, revert, and rebase, with conflicts, then Continue through `GitCliRebase`;
  - `git pull` (merge and rebase) and `git submodule update`;
  - the worktree steps and the conflict actions (stage 4).

  Each test checks the real content in the working tree, and a clean `git status` through the filter. With stage 0,
  JGit's status must show nothing either.
- **Failures:**
  - the server refuses an object (404) or is down. With stage 2, nothing changes, and `LfsFilterError` names the
    object. For `git pull`, the error names the files already written;
  - Cancel while `FakeLfsServer` holds a download: git and the helper are gone, and the store has no temp file left.
- **Credentials:** `answeringDialogs` with Basic authentication. Closing the dialog gives `LfsError.CredentialsDenied`
  in the error.
- **SSH:** `GitCliSshTest`'s sshd, whose forced command answers `git-lfs-authenticate` with a `FakeLfsServer`'s URL.
  Skipped without sshd.
- **Linked worktree and submodule:** a linked worktree's objects go to the common git dir (P8), and a submodule's to
  its own.
- **Racy files:** `touch` an LFS file, then merge. git doesn't refuse with "local changes", and the index keeps the
  pointer.
- **The rule:** with git-lfs set up, Leaf adds no `-c`, which the logging git-lfs wrapper of `GitCliLfsTest` on the
  PATH shows. Cases A, B and C get Leaf's filter.
- **Stage 0:** JGit stages the pointer of an edited LFS file (P5), and the choice cards find an object that git-lfs
  downloaded in a linked worktree.
- **Linux CI:**
  - fake programs use `printf`, and git's identity is in the test's global config;
  - Ubuntu has git-lfs in `/usr/bin`, so tests without it use `pathWithoutGitLfs`;
  - Ubuntu 22.04's git 2.34 has the long-running protocol (2.11) and delay (2.15);
  - the relay's Windows code compiles only on Windows, which CI doesn't build.
- **Mutation checks:** the rule (each case), `required=true`, passing non-pointers through, the empty file, listing the
  failed paths, the store in the common git dir, and the set of objects downloaded first.

## 8. Open questions

1. **Relay (the protocol in Kotlin), or the protocol in the helper?** Recommended: the relay.
2. **Case A, and git outside Leaf.** Leaf's filter writes the real content where git in a terminal, with no filter,
   expects pointers. An agent's `git add` of a touched LFS file then stores the raw bytes (P5), and Leaf's clone and
   pull already cause this today.
   - Either accept that, and say so in a strip ("This repository uses Git LFS, and git outside Leaf has no LFS
     filter: install git-lfs").
   - Or apply Leaf's filter in case B only, and keep pointers in case A.

   Recommended: apply it in both cases, and add the strip, as a UI change to show first.
3. **Stage 0's JGit checkouts.** With JGit's built-in LFS always on without git-lfs, switching branches, reset, stash
   and discard download LFS objects, where today they write pointers. They download one object per request, without
   progress or Cancel.
   - Either accept that.
   - Or keep JGit's checkouts on pointers, and turn on only the built-in clean, which is enough to stage correctly.
4. **Failed downloads in `git pull`.**
   - Either accept git-lfs's behaviour: the files already written stay, and the error names them.
   - Or fetch first, download, then run `git pull`, which fetches a second time.

   Recommended: accept.
5. **`required=true`,** failing as git-lfs does, and reading `lfs.skipdownloaderrors`? Recommended: yes.
6. **Stage 4's commands:** run them through `GitCliRemoteCommand` (recommended), or give `GitCli` the same server?
7. **Stage 5, clone with git's checkout:** now, later, or not at all?
8. **An interim for item 4:** none (recommended), or option C until stage 1 ships?

### Decisions (2026-10-26)

The user: "go with the recommendations on all eight questions".
1. The relay: the protocol in Kotlin, the helper copies bytes.
2. Leaf's filter in cases A, B and C, plus the strip about git outside Leaf, shown to the user as a UI change first.
3. No recommendation was written. Read as accepting the design of §3: JGit's built-in LFS always on without git-lfs,
   so JGit's checkouts download too. The other choice (clean only) leaves a working tree with real content from git's
   commands and pointers from JGit's checkouts.
4. Accept git-lfs's behaviour for a failed download in `git pull`; the error names the files written.
5. `required=true`, and `lfs.skipdownloaderrors` read.
6. Stage 4's commands through `GitCliRemoteCommand`.
7. No recommendation was written. Read as §5 and §6 have it: stage 5 later, after stages 0 to 4, still optional.
8. No interim for item 4.

## Probe index

| # | Case | Result |
| --- | --- | --- |
| P1 | Item 4's operations without git-lfs, cases A and B | A: exit 0, pointers, status clean. B: exit 128, nothing changed, no state left. |
| P2 | The same with JGit 7.7 (`-Duser.home` isolated) | A: pointers. B: `FilterFailedException`, a `._<file><random>.tmp` left, revert and rebase delete `b.bin`. |
| P3, P4 | A stand-in long-running filter on the command line, with git-lfs's config in the global config | It beats the config, and reaches submodules and clone. Every checkout sends `can-delay=1`. Refreshes and autostash ask for clean. It starts lazily. A conflicted pointer gets smudged. A failure leaves the files already written. |
| P5 | Case A with the real content in the working tree, no clean filter | git and JGit show the file as modified. `git add` and JGit's add store the raw bytes. JGit with `usejgitbuiltin` stores the pointer. |
| P6 | git-lfs's clean and smudge, edge cases | Empty gives empty. A pointer gives the same pointer. A non-pointer and a conflicted pointer pass through. `GIT_LFS_SKIP_SMUDGE` gives the pointer. |
| P7 | git-lfs's `filter-process`, with a download that fails during a merge | Exit 128, `a.bin` written, HEAD unchanged. With `lfs.skipdownloaderrors`, the pointer is written, and the exit code is 0. |
| P8 | JGit's `Lfs` in a linked worktree | `<common>/worktrees/<name>/lfs/objects`, where git-lfs uses `<common>/lfs/objects`. |
