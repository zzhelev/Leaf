# CLAUDE.md

Fork of [Gitnuro](https://github.com/JetpackDuba/Gitnuro) (Kotlin, Compose Desktop, JGit) that adds first-class git
worktree support for AI-agent workflows. The fork plan and working rules are in `docs/fork/PLAN.md`. Read them before
starting any work item. Findings about JGit and worktrees are in `docs/fork/architecture-notes.md`.

This file and everything under `docs/fork/` are fork-only. Keep them out of upstream PRs.

## Name

The fork ships as **Leaf**, and its code, build and storage use Leaf's own names.
- **Leaf:** `AppConstants.APP_NAME` and the user-facing strings. Also `projectName` in `app/build.gradle.kts`, which
  produces `Leaf.app`, `Leaf-*.dmg` and `Leaf-linux-*.jar`. Also the macOS bundle ID `io.github.zzhelev.leaf` and
  `leaf.iss`, which has its own AppId. Also `README.md` and `DEVELOPMENT.md`.
- **Code and build:** the Kotlin package `dev.app.leaf` (also the Gradle `group`, so Compose's `Res` package is
  `dev.app.leaf.app.generated.resources`), the root Gradle project `Leaf`, the Rust crate `leaf_rs` (native library
  `libleaf_rs.dylib`) and `LeafException`.
- **Links:** the Welcome page's "Source code" and "Report a bug", the bottom bar's Releases link, `VERSION_CHECK_URL`
  (Leaf's `latest.json` on the `main` branch) and the publishing target in `.github/workflows/release.yml` all
  point at `zzhelev/Leaf`. The release workflow still names upstream's self-hosted runners.
- **Own storage:** every storage name comes from the fork-only `AppStorage` (`common/.../common/storage/`), so Leaf
  never reads or writes an installed Gitnuro's data. On macOS, a packaged Leaf uses:
  - the `java.util.prefs` node `LeafConfig` (tabs, recent repos, pane widths);
  - `~/Library/Application Support/leaf/` (the settings file `user_prefs.json`, and `tmp/`);
  - `~/Library/Logs/io.github.zzhelev.leaf/leaf.log`;
  - the per-repository file `<git dir>/leaf` (sign-off, per worktree) and `<common git dir>/leaf` (side panel folder
    state and the worktrees' base branch, shared by the worktrees). They are the same file in a repository's main
    worktree.

  Dev runs use `LeafDevConfig`, `leaf-dev` and `io.github.zzhelev.leaf-dev` instead (see Build gotchas).
- **Gitnuro is named only where GPL-3.0 needs it:** the README's License section says, in one dated sentence, that
  Leaf is a modified version of Gitnuro (section 5a), and which files keep Gitnuro's license. The app's About text
  doesn't name it (2026-10-09). Both stay as long as any code from Gitnuro is in Leaf.

## Branches and remotes

- `origin` = Leaf (`zzhelev/Leaf`), `upstream` = `JetpackDuba/Gitnuro`.
- `main` is Leaf's integration branch. It tracks `origin/main`, which is the default branch on GitHub. Work items
  branch off `main`. Until 2026-10-05 it was called `fork/main`, and the local `main` mirrored upstream.
- The 2.0 rewrite lives on `upstream/main` (tags `2.0.0-beta01..03`). The other upstream branches are stale. There is
  no local mirror branch: sync by fetching `upstream` and merging `upstream/main` into `main`. `main` is published, so
  never rebase it.
- **Syncing and the package rename:** git follows the rename to `dev/app/leaf` for most files, but not all:
  - A file upstream adds next to existing files comes up as a "file location" conflict, already at the Leaf path.
  - A file upstream adds in a new folder lands under `com/jetpackduba/gitnuro/` without any conflict.
  - A file the fork rewrote comes up as modify/delete, and upstream's change has to be ported by hand.

  After resolving, `git ls-files '*jetpackduba*'` must print nothing, and new files need `dev.app.leaf` packages. The
  first sync (2026-10-07) is described in `docs/fork/CHANGELOG.md`.
- **Commit identity:** `Zhelyazko Zhelev <zzhelev@gmail.com>`, set in the repo's own `.git/config`. Never commit under
  another identity.
- **Pushing:** `git push origin main`, from a terminal or from Leaf. Since 2026-10-10 the repo's `.git/config` sets
  `core.sshCommand` to `ssh -i /Users/zhelyazko.zhelev/.ssh/id_rsa_zhelyazko -o IdentitiesOnly=yes`, the key on
  zzhelev's GitHub account. Linked worktrees share it. Without it, ssh offers the agent's keys in order, and the first
  one GitHub knows belongs to another account (zhelezen), which can't push. Over HTTPS, the `gh` login (zzhelev, with
  the `workflow` scope that changes to `.github/workflows/` need) works too:

  ```bash
  git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push https://github.com/zzhelev/Leaf.git main
  ```
- **Tags:** Leaf's tags are `leaf-X.Y.Z`. `origin` fetches only `leaf-*` tags. Gitnuro's tags come from `upstream`
  into `refs/upstream-tags/*` (list them with `git for-each-ref refs/upstream-tags`), so `git push --tags` never sends
  them.

## Releases

- **Version:** `projectVersion` and `projectVersionSimplified` in `app/build.gradle.kts`, plus `APP_VERSION` and
  `APP_VERSION_CODE` in `AppConstants.kt`. The code goes up by one per release. The macOS packager needs the first
  number to be 1 or more.
- **Steps:**
  1. Bump the version in both files, commit and push `main`.
  2. Tag `leaf-X.Y.Z` (it must equal `projectVersion`) and push the tag, with the push command above and
     `refs/tags/leaf-X.Y.Z` in place of `main`.
  3. The Release Build workflow builds everything and creates a draft release: a macOS DMG (Apple Silicon, signed ad
     hoc), a Windows installer and portable ZIP, Linux jars (x86_64 and aarch64), Debian packages (amd64 and
     arm64), and a `.sha256` file for each. Review the draft, write the notes, publish.
  4. After publishing, update `latest.json` on `main` (`appVersion`, `appCode` = the new `APP_VERSION_CODE`,
     `downloadUrl` = the release page) and push. Installed copies then show the update banner.
     `UpdatesRepository.update` checks once for the whole app, every 5 minutes, and compares `appCode` only. A failed
     check is logged and keeps the last answer. Users can also check right away (`checkNow`, `CheckForUpdatesDialog`):
     "Check for updates" in the Actions list and on the Welcome page, and on macOS "Check for Updates…" in the Help
     menu, the only menu in Leaf's menu bar (`App.kt`, macOS only).
- **Test run without releasing:** Actions → Release Build → Run workflow. It only builds, and keeps the files as
  workflow artifacts for a week.
- `leaf-0.1.0` was published by hand before the workflow existed. It's macOS only, and the app still reported
  2.0-beta03.

## Toolchain (verified on macOS 26.6 arm64, 2026-10-02)

- **JDK 25.** The JVM toolchain is pinned to 25 in `buildSrc` and `app`. `buildSrc` has no toolchain auto-download, so
  Gradle fails with "Cannot find a Java installation ... languageVersion=25" unless `JAVA_HOME` points at a JDK 25.
  On this machine, IntelliJ IDEA's bundled runtime works:
  `export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"` (JBR 25.0.4.1).
  The fork's DEVELOPMENT.md says JDK 25; upstream's still says "JDK 17 or higher".
- **Packaging needs a full JDK 25 with jmods.** IntelliJ's bundled JBR has no `jlink` or `jpackage`, so it builds and
  runs the app but can't package it. Tried on 2026-10-05:
  - JBR SDK 25 (`jbrsdk-25.0.4.1-osx-aarch64-*.tar.gz` from the JetBrainsRuntime GitHub releases): works. It has
    jmods and no Homebrew links, and upstream CI uses the JetBrains distribution too. On this machine it is at
    `~/Library/Java/JavaVirtualMachines/jbrsdk-25.0.4.1/Contents/Home`.
  - Homebrew `openjdk@25`: rejected by Compose's `checkRuntime`. Its native libraries link to `/opt/homebrew`
    (harfbuzz, freetype and others), and a packaged app crashes on launch without them (compose-multiplatform#3107).
  - Temurin 25: ships no jmods, so `jlink` can't build an image containing `jdk.jlink`, which
    `includeAllModules = true` asks for.
- **Git LFS.** The 27 fonts in `app/src/main/composeResources/font/` are LFS-tracked. Without `git-lfs` they are
  131-byte pointer files, and every build (dev runs too) ships broken fonts. Install `git-lfs`, then run `git lfs pull`.
  `git lfs ls-files` marks downloaded files with `*` and pointer-only files with `-`.
- Gradle 9.3.1, via the wrapper.
- **Rust nightly.** `rs/rust-toolchain.toml` pins `nightly`, and rustup installs it automatically on first use.
  `cargo` must be on `PATH` (`~/.cargo/bin`).
- `cargo-kotars` is **not** needed, despite upstream's DEVELOPMENT.md. Bindings are generated with uniffi
  (`cargo run --bin uniffi-bindgen`, defined in `rs/`). The build passed without kotars installed.
- git 2.54 (Apple Git) is at `/usr/bin/git`. The app runs it through `GitCli` (see Git operations).

## Commands (verified)

Run everything from the repo root, with `JAVA_HOME` set as above.

```bash
./gradlew build                          # full build + tests; ~5 min, plus ~1.5 min for a cold Rust build
./gradlew test                           # all tests (:app, :common, :data, :domain)
./gradlew :data:test                     # ~25 s when the build is warm
./gradlew :app:run                       # launch the app
./gradlew :app:run --args="/path/to/repo"  # launch and open a repo/dir in a new tab (App.getDirToOpen)
```

Packaging, verified on 2026-10-05 for arm64 only, with a JBR SDK 25 as `JAVA_HOME` (see Toolchain):

```bash
./gradlew :app:createDistributable  # .app in app/build/compose/binaries/main/app/
./gradlew :app:packageDmg           # .dmg in app/build/compose/binaries/main/dmg/
./gradlew :app:installMacApp        # builds Leaf.app and installs it into /Applications
```

- `installMacApp` (macOS only) builds `Leaf.app` and replaces `/Applications/Leaf.app`; `-PinstallDir=<dir>` installs
  elsewhere.
  - If Leaf is running from there, it waits up to 5 s, then stops. It only counts processes whose command line starts
    with the app's executable.
  - It removes the old copy before copying with `ditto`. `ditto` merges into an existing bundle, and jar names change
    between builds, so leftover jars would break the code signature.
  - Then it unregisters the LaunchServices records of Leaf copies that no longer exist (found with
    `lsregister -dump Bundle`), and registers the installed copy again. macOS registers every copy it sees, such as
    the temporary one in `packageDmg`'s `dmg-workdir`. With such records left over, the Dock showed the generic "exec"
    icon for the running app (2026-10-08). If it happens anyway, run `killall Dock` and reopen Leaf. Failures here
    only warn.
- `gradle.properties` sets `compose.desktop.mac.sign=false`, so jpackage signs ad hoc, with the hardened runtime and
  the JIT and library-validation entitlements the JVM needs. That build runs on the machine that built it. Sharing it
  needs a Developer ID and notarization: set `SIGNING_IDENTITY` and the `NOTARIZATION_*` env vars, and pass
  `-Pcompose.desktop.mac.sign=true`.
- The app is `Leaf.app` with bundle ID `io.github.zzhelev.leaf`, so it installs alongside Gitnuro. It has its own
  storage (see Name), so it doesn't touch an installed Gitnuro's tabs, settings or logs.
- The packaged skiko jar still contains `libskiko-macos-x64.dylib`, because skiko's macos-arm64 runtime jar ships both
  architectures. Only the extracted arm64 library is loaded.
- The app starts with `--enable-native-access=ALL-UNNAMED` (`compose.desktop.application.jvmArgs`), and the Linux fat
  jar's manifest sets `Enable-Native-Access: ALL-UNNAMED`. Without them, JDK 25 warns when the Rust library and JNA
  load native code, and a later JDK will refuse.
- **Settings are JSON, not protobuf.** DataStore Preferences' own file format goes through its bundled protobuf,
  which calls `sun.misc.Unsafe`, and JDK 25 warns about it. DataStore 1.2.1 and 1.3.0-alpha11 both do. So
  `DatastoreModule` stores the settings with the fork-only `JsonPreferencesSerializer`.
  `ProtobufPreferencesMigration` moves an old `user_prefs.preferences_pb` over once, then renames it to
  `.migrated`; the warning shows only on that launch.
- **Linux `.deb`:** `./gradlew :app:packageDeb` builds it on Linux only, and needs `fakeroot` and `dpkg-deb`. It
  installs Leaf to `/opt/leaf` with an entry in the applications menu.
  - The release workflow builds it natively on Ubuntu 22.04 for each CPU: jpackage can't cross-build, the package
    depends on the build system's library package names, and the Rust library needs its glibc or newer. So it
    installs on Debian 12, Ubuntu 22.04 and newer.
  - jpackage lists only the libraries it finds installed as dependencies. skiko's arm64 library needs `libEGL.so.1`,
    so the workflow installs `libegl1` before packaging and checks that the arm64 package depends on it.
  - `packageDeb` repacks jpackage's `.deb` with `dpkg-deb`. The menu entry `leaf-Leaf.desktop` becomes a file in
    `/usr/share/applications`, and the install scripts lose their `xdg-desktop-menu` calls. Those need
    `/etc/xdg/menus`, which only desktop environments provide, so the package failed to install on WSL or a minimal
    system.
  - The repack also drops the hash Compose adds to each jar name (keeping 8 characters where two would clash) and
    rewrites `Leaf.cfg`. jpackage's Linux launcher reads its launch data from a pipe with one `read()` (JDK-8380085,
    fixed in JDK 27), and segfaults when that data doesn't fit a throttled 8 KB pipe, which Linux hands out once a user
    has about 1,024 pipes open. The build fails if the classpath passes 7 KB. Drop this once JBR has the fix.

Packaging config lives in `app/build.gradle.kts` (`compose.desktop.nativeDistributions`). Do not touch it without
asking.

### Build gotchas

- **Rust builds at configuration time.** In `app/build.gradle.kts`, `tasks.register("rustTasks") { rustTasks() }`
  runs `cargo build --release`, then a debug build, then bindgen, inside the task's configuration block. That block
  runs whenever `rustTasks` is in the task graph, which `:app:compileKotlin` puts it in (any app build, run or test).
  `help` or a `:domain`-only build doesn't. So a fresh checkout or worktree has no `leaf_rs.kt` bindings, and
  `:domain` alone fails with unresolved `FileChanged` and `FileType`, until `./gradlew :app:rustTasks` (about 1.5
  minutes cold).
- **Rust failures do not fail the build.** `executePrintingData` sets `isIgnoreExitValue = true` and only prints
  `Code is N`. If something Rust-related looks stale, grep the Gradle output for `Code is` and `failed with exit value`.
- Generated, gitignored outputs:
  - `domain/src/main/kotlin/dev/app/leaf/autogenerated/` (uniffi Kotlin bindings, `leaf_rs.kt`)
  - `app/src/main/resources/libleaf_rs.dylib`
  - `app/src/main/resources/leaf-askpass` (`.exe` on Windows), which `copyRustBuild` copies next to the library
- **Dev runs have their own storage.** `AppStorage` counts a run as packaged in two cases:
  - the `jpackage.app-version` system property is set, which only jpackage's launcher does (the macOS and Windows apps
    and the Linux `.deb`);
  - `AppStorage` was loaded from a jar whose manifest has `Leaf-Packaged: true`. Only the Linux fat jar
    (`fatJarLinux`) does, since `java -jar` sets no jpackage property.

  Anything else is a dev run. So `./gradlew :app:run` and IDE runs, which load module jars or class folders, use the
  prefs node `LeafDevConfig`, `~/Library/Application Support/leaf-dev/` and
  `~/Library/Logs/io.github.zzhelev.leaf-dev/leaf.log`. They can't touch the installed `/Applications/Leaf.app` or
  `/Applications/Gitnuro.app` on this machine.
  - Prefs nodes live in `~/Library/Preferences/com.apple.java.util.prefs.plist` on macOS. They cannot be redirected:
    `FileSystemPreferencesFactory` is unavailable on macOS, and `-Duser.home` does not affect them.
  - Passing `-Duser.home` to `:app:run` through an init script did not take effect; the Compose plugin sets the run
    task's JVM args.

## Module map

- `app`: Compose Desktop UI and entry point (`main.kt`, `App.kt`).
  - Also holds the view models (`viewmodels/`, `repositoryopen/RepositoryOpenViewModel.kt`), Dagger components and
    modules (`di/`), dialogs, theme, keybindings, terminal launchers, LFS glue and the update checker.
  - Its build script holds the Rust build glue and the packaging config.
- `common`: tiny shared utilities: the `@TabScope` annotation, logging (`printLog`/`printError(TAG, ...)`), OS
  detection, `combine` overloads up to 30 flows (`flows/FlowExtensions.kt`), and Closeable helpers. Also the fork-only
  `storage/AppStorage.kt` with every storage name (see Name).
- `domain`: use cases, git-action interfaces, models, the error types (`Either`, `AppError`), repository interfaces
  (tab/app state, settings), `UseCaseExecutor`, `TabCoroutineScope`, `ShellManager`, credentials, and the uniffi
  bindings.
  - Not pure: it depends on JGit and Compose, and many interfaces still leak JGit types.
- `data`: JGit implementations of the git actions (`data/git/**`), the JGit instance cache (`data/git/JGit.kt`), the
  file watcher wrapper, in-memory tab repositories, DataStore-backed settings, and JGit→domain mappers.
  - The fork-only `network/HttpClients.kt` builds the Ktor clients for LFS and the update check. They check TLS
    certificates against the JVM's trust store. An LFS request skips the check only when `http.sslVerify` is false
    for its URL (`Config.isSslVerify`, through JGit's `HttpConfig`), as git-lfs does.
- `ui`: empty placeholder module (only `build.gradle.kts`).
- `rs`: Rust cdylib `leaf_rs`, exported via uniffi.
  - `FileWatcher` uses notify 8.
  - No SSH: OpenSSH's programs do all of it, run by git, by the built-in LFS client and by `SshProgramSigner` (see
    Remote operations, and Commit and tag signing). libssh-rs, with its vendored libssh and OpenSSL, was removed on
    2026-10-09 (stage 5 of `docs/fork/remote-operations.md`).
  - The binary `leaf-askpass` (`rs/leaf-askpass.rs`, standard library only) is the askpass program and credential
    helper of the git commands Leaf runs (see Remote operations). It ships in the app's jar like the library, and
    `AskpassHelper` extracts it to `<app data>/tmp/askpass-<hash>/` the first time it's needed.
- `buildSrc`: convention plugin `buildsrc.convention.kotlin-jvm`. It sets toolchain 25, `-Xexplicit-backing-fields`,
  `-Xcontext-parameters` and the JUnit platform.

## Git operations

**Flow for one operation.** UI lambda → `RepositoryOpenViewModel` method → `XUseCase` → `IXGitAction` (domain) →
`XGitAction` (data) → JGit.

**Adding an operation:**
1. Interface `domain/.../interfaces/IXGitAction.kt`, with a single
   `suspend operator fun invoke(repositoryPath: String, ...): Either<T, GitError>`.
2. Implementation `data/.../git/<area>/XGitAction.kt`: `@Inject constructor(private val jgit: JGit)`, body
   `jgit.provide(repositoryPath) { git -> ... }`.
3. Binding in `app/.../di/modules/TabScopeGitActionsModule.kt` (`@Binds @TabScope`).
4. Use case `domain/.../usecases/XUseCase.kt`.

**`JGit.provide`** (`data/git/JGit.kt`):
- The class is a `@Singleton` cache from path to `Git`. `JGit.open` opens repositories with Leaf's own JGit `FS`:
  `WindowsFs` on Windows (hooks through Git Bash), and the fork-only `PosixFs` on macOS and Linux (see Login shell
  environment). `provide` and `provideOptional` share the cache, so both go through `open`.
- **HEAD reflog of a linked worktree (fork-only `LinkedWorktreeLogs`):** JGit 7.7 writes it into the main worktree's
  `logs/HEAD` (`docs/fork/architecture-notes.md` §6), because `RefDirectory.logFor` resolves HEAD against the common
  git dir's `logs`. For a git dir with a `commondir` file, `JGit.open` gives both file systems a `LinkedWorktreeLogs`,
  and their `resolve` answers `(<common git dir>, "logs")` with `<git dir>/logs`, which is where JGit already reads
  HEAD's log. Branch logs (`logs/refs/`) stay shared. Another `FS` needs the same. Drop it once a JGit release fixes
  `logFor` (eclipse-jgit/jgit#306): `LinkedWorktreeLogsTest` fails if JGit stops taking that folder from `FS.resolve`.
  - Such a file system belongs to its repository. Another worktree opened with a tab's `repository.fs`, as
    `RenameBranchGitAction.moveHead` does, would log its HEAD in the tab's reflog. `moveHead` writes no reflog entry.
- `WindowsFs` finds hooks with JGit's `findHook` (`core.hooksPath`, the common git dir) and runs them with Git for
  Windows' `bin\bash.exe` (`GitBash`, which also quotes arguments for MSYS2), with JGit's folder and `GIT_*`
  variables. It must return `ProcessResult(exitCode, OK)`: JGit takes `ProcessResult(OK)` alone (exit code -1) as a
  failed hook. The install is `findGitForWindows`'s: the first one on PATH, or in the default folders, with Git Bash.
- JGit passes commit-msg an empty path when the git dir is outside the working tree (linked worktrees, submodules):
  `CommitMsgHook` uses `Repository.stripWorkDir`. `PosixFs` and `WindowsFs` both override `runHookIfPresent` to pass
  the absolute path of `<git dir>/COMMIT_EDITMSG` instead (`hookArguments`), as the git CLI does. Another `FS` that
  runs hooks needs the same.
- Exceptions inside `provide` become `GenericError` (or come from an `errorHandle` mapper). `GpgSigningException`,
  `SshSigningException` and `SshNeedsGitException` are looked for in the cause chain first, as JGit wraps them.
- Closing a tab, or replacing it ("Open another repository"), closes and drops the cached `Git` of every repository
  that no remaining tab has open (`CleanRepositoriesResourcesUseCase` → `GitProviderService` → `JGit.cleanupExcept`). The keys to keep are the git
  dirs from `RepositorySelectionState.Open.path`, never `<working tree>/.git`: linked worktrees and submodules have no
  such folder. The cache is a `ConcurrentHashMap`, because tabs add to it while another tab closes.
- `provide` does not switch dispatchers. Many actions call `withContext(Dispatchers.IO)` themselves.
- JGit auto-gc is turned off by `NoAutoGcSystemReader` (`data/git/`), which `main.kt` installs before Dagger is
  created. JGit's gc is not worktree-aware. Never run JGit gc; leave it to the git CLI.

**`repositoryPath` is the git dir** (for example `/repo/.git`), not the working tree. It comes from
`OpenRepositoryGitAction`. Get the working tree with `GetWorktreeUseCase` / `IGetWorktreePathGitAction`. In existing
code, "worktree" means the working directory, not linked worktrees.

**Use cases:**
- Mutations are a non-suspend `operator fun invoke` that calls
  `useCaseExecutor.executeLaunch(TaskType.X, dataToRefresh = arrayOf(DataToRefresh...)) { repositoryPath -> ... }`.
  This fires and forgets, shows the blocking `ProcessingScreen`, records the completed or failed task, and refreshes
  on success.
- A use case that runs inside another task's block calls a suspend function, never another mutation's `invoke`: that
  starts a task of its own, which the running task doesn't wait for, and whose end clears `currentTask` (the
  processing screen) while the first still runs. Fork-only: `DoCommitUseCase.commit` is the amend that continuing a
  rebase makes; it used to be a `DoCommit` task that raced `git rebase --continue`, and a failed amend was ignored.
- A completed task's toast says `successTitle()` ("Merge completed"). Fork-only: a task that ran but stopped at
  conflicts passes `stoppedAtConflicts` to `executeLaunch`, which tells it from the result (merge, rebase and pull
  give `true`). It's recorded as `CompletedTask.Success(stoppedAtConflicts = true)` (`addCompletedTaskWithConflicts`),
  and its toast warns with `conflictsTitle()` ("Merge stopped at conflicts, fix them to continue").
- Queries are `suspend` and use `useCaseExecutor.execute`. So do mutations whose dialog shows the result, such as
  rename branch and delete branch or tag: `execute` records no task, so they get no ProcessingScreen, toast or
  `ErrorDialog`.
- See `domain/.../UseCaseExecutor.kt`.

**Errors:**
- A custom `Either` (`domain/errors/Either.kt`, with `either {}`, `bind()`, `mapErr`) plus the sealed
  `AppError`/`GitError` hierarchy (`domain/errors/AppError.kt`).
- User-facing text: `app/.../ui/Errors.kt`. Dialogs show `getStyledErrorText` (fork-only), which sets the folders and
  commands an error names (`monospaceParts`) in the monospace font. Messages put such a folder on a line of its own,
  between blank lines.
  The text of `ErrorDialog` and of `DialogWarning`, the red box in dialogs, can be selected and copied.
- HIGH-severity failures open `ErrorDialog`; others become toasts.
- JGit reports some failures in a command's result rather than by throwing, so check it (fork-only fixes):
  - A cherry-pick's `FAILED` (local changes in the way) and `CONFLICTING` are errors, as a revert's are.
    `CherryPickCommitUseCase` refreshes after an error too (`refreshEvenIfFailed`), as conflicts are left to resolve.
  - A rebase's `CONFLICTS` means local files stopped the checkout and `FAILED` that they stopped a commit. JGit puts
    the branch back in both, so they throw (`RebaseWouldOverwriteException`, `rebaseHasStopped`), as does `ABORTED`.
    Merge conflicts are `STOPPED`.

**Concurrency:**
- There is no mutex around git operations. Foreground tasks only block UI input. A tab's refreshes take turns (see
  Refresh).
- There is no `CoroutineExceptionHandler`. An exception that escapes as a throw, rather than as `Either.Err`, kills
  its coroutine silently, with only a stderr stack trace. Return errors from git actions; don't throw them. Opening a
  linked worktree used to hit this.
- `UseCaseExecutor` turns a throw inside its block into `GenericError`, and logs it (fork-only): callers that ignore
  the result, such as refreshes, used to drop it without a trace.

**Git CLI (fork-only, `data/git/cli/`, `domain/gitcli/`):** use it for anything JGit can't do, starting with linked
worktrees.
- Call `GitCli.run(workingDirectory, args, timeout)`. It returns `Either<String /* stdout */, GitCliError>`.
- `GitCli` locates the binary through `GitExecutableLocator` (the configured path from settings, or auto-detection,
  cached). It adds the login shell's variables, then its own non-interactive, `LC_ALL=C` environment on top.
- Use porcelain or `-z` output and parse it in the data layer.
- Never shell out to git through `ShellManager`.
- `GitCli.execute` returns the output whatever the exit code (`GitCliOutput`), takes extra variables and stdin
  (`input`), and streams stderr to a callback. `run` is built on it.

**Hunk and line actions (fork-only, `data/git/workspace/HunkPatch.kt`, `ApplyHunkGitAction.kt`):** staging,
unstaging and discarding a hunk or a line apply a patch with `git apply`, as `git add -p`, `git reset -p` and
`git checkout -p` do: `--cached` to stage, `--cached -R` to unstage, `-R` to discard from the working tree. The six
upstream actions (`StageHunkGitAction`, `DiscardUnstagedHunkLineGitAction`, …) only pick the action and the lines.
- `hunkPatch` takes each line's bytes from `Hunk.oldText` and `Hunk.newText`, the `RawText`s that the diff compared,
  which `FormatHunksGitAction` puts in every hunk. Never build a patch from `Line.text`: JGit decodes each line as
  UTF-8, or byte for byte when it isn't valid UTF-8, so the bytes can't be told back. The patch keeps each line's
  ending (`\r\n` stays) and `\ No newline at end of file`.
- A line is selected as `git add -p`'s hunk editing does: applied forward, an unselected removed line becomes context
  and an unselected added line is left out; reversed, the other way round. Lines are matched by type and numbers
  (`isSameLine`), as the split view passes copies.
- A line without a line ending has to stay last in its version of the file, which a selection can break
  (`withLastLinesKeptLast`): such a context line moves after the lines that follow it, and a selected line that then
  has a line after it gets a line ending (with `\r` in a CRLF file). Staging the line added after a last line without
  one puts it before that line, as the index can't give that line an ending without staging that change too.
- Context comes from the side that the patch applies to. git refuses the patch when that side no longer has the lines
  that the diff showed, and writes nothing. Its messages (`patch does not apply`, `does not exist in index`, …) become
  `StaleHunkError`, which says the file changed after the diff was shown. The six use cases refresh even then
  (`refreshEvenIfFailed`), so the diff shows the file as it is. Lines added or removed elsewhere in the file don't stop
  the patch: git finds the hunk at its new place, looking from the header's start, nearest first. Both sides of the
  header start where the hunk is in the side that the patch applies to (`newLineNumber` reversed, `oldLineNumber`
  forward), so that a block repeated in the file is found at the copy the diff showed.
- `-c apply.ignoreWhitespace=no` and `--whitespace=nowarn` keep the user's `apply.*` config from loosening the check
  or changing the lines.
- A patch with the whole of an added file creates it (with its mode), and reversed removes it from the index, which
  leaves it untracked. The same goes for a deleted file. Part of one changes the file instead. The diff pane offers
  hunk and line actions for modified files only.
- File names are quoted as git quotes them (`patchName`): a tab would end an unquoted name.
- Without a usable git, the actions fail with `GitCliError` and change nothing. There is no JGit fallback: the old
  JGit code decoded and re-encoded the text as UTF-8, and discarded lines by number without checking them.

**Linked worktrees (fork-only, `data/git/worktrees/`, models in `domain/models/Worktree.kt`):**
- `GetWorktreesInfoUseCase` returns a `WorktreeList`: the base branch, and for each worktree its `Worktree` entry,
  its `WorktreeStatus`, how it compares to the base, and the time of its last commit. It runs at most 4 worktrees'
  git processes at once. A part that fails for one worktree is left null.
- The git actions (`GetWorktreesGitAction`, `GetWorktreeStatusGitAction`, `GetAheadBehindGitAction`,
  `GetWorktreeBaseBranchGitAction`, `SaveWorktreeBaseBranchGitAction`, `GetCommitTimesGitAction`) are bound in
  `WorktreeGitActionsModule`, with `GetCommonGitDirGitAction` (JGit's `commonDirectory`, for the file watcher).
- **Base branch** (`WorktreeBaseBranch`): the worktrees are compared to the branch chosen for the repository, local or
  remote (`refs/remotes/origin/main`), or else to the automatic one: the branch `origin/HEAD` points to, then `main`,
  then `master`, as local branches.
  - The choice is `worktrees.baseBranch` in `<common git dir>/leaf` (`WorktreeBaseBranchConfig`), a full ref, so every
    worktree's tab shares it. No key means automatic, and a value outside `refs/heads/` and `refs/remotes/` counts as
    automatic.
  - It's read on every worktree refresh, not kept in the view model: a choice made in one tab reaches the others with
    their next refresh. `SetWorktreeBaseBranchUseCase` saves it and refreshes only the list (`DataToRefresh.WORKTREES`).
  - A chosen branch that doesn't exist (deleted, renamed outside Leaf, pruned) stays chosen, and the worktrees are
    compared to the automatic one until a branch of that name exists again. Deleting it in Leaf doesn't clear it.
    Renaming it in Leaf moves the choice to the new name (`RenameBranchGitAction`), as git moves `branch.<name>`
    config.
  - Chosen from the Worktrees section header's button (back to Automatic) and from a branch's right-click menu, in the
    side panel and the log. Both are left out while the repository has only one worktree
    (`WorktreeList.baseChoice()`); its row is still compared to the base.
- The output parsers are in `WorktreeParsers.kt`, with fixtures from git 2.54 in `WorktreeParsersTest`.
- `Worktree.branch` is a full ref, as in `Branch.name`. `git worktree list` works from any git dir, a linked
  worktree's included.
- `Worktree.rebasingBranch` and `bisectingBranch` name the branch that a detached worktree rebases or bisects from,
  which `git worktree list` doesn't tell. `GetWorktreesGitAction` reads them from the worktrees' git dirs
  (`worktreeHeads` in `BranchWorktrees.kt`, which the guards read too), matched by canonical folder. Such a worktree
  is compared to the base by that branch, not by the commit it stopped at.
- Each tab keeps the list in `RepositoryDataRepository.worktrees`. `RefreshDataUseCase` refreshes it last, with ALL,
  BRANCHES, LOG or STATUS (`refreshesWorktrees`), and alone with WORKTREES, through the tab's
  `WorktreesRefreshRunner` (`ConflatedRunner`): one refresh at a time, and calls that come meanwhile share the next
  one. The file watcher and a poll keep it up to date with the other worktrees (see Refresh).
- The side panel shows the list (Worktrees section) and marks the branches that worktrees use (see Sidebar and branch
  list).

**Remote operations (fork-only, `data/git/cli/remote/`, `data/git/cli/askpass/`):** push, fetch, pull, clone, and
adding and updating submodules run the git CLI, and git-lfs downloads LFS files when it's installed (stages 1 to 5 of
`docs/fork/remote-operations.md`).
- Push and remote branch deletion run `git push --porcelain --progress` (`GitCliPushBranchGitAction`,
  `GitCliDeleteRemoteBranchGitAction`). JGit's deletion, the fallback, returns the push's failure and keeps the
  remote-tracking branch; it used to delete that branch and report success.
- Fetch runs `git fetch --progress --prune <remote>` for each remote in turn (`GitCliFetchAllRemotesGitAction`), so
  one that fails doesn't stop the others. The failures come back together as `FetchRemotesError`. A remote whose
  dialog the user closed isn't reported, as with JGit ("Cancelled authentication").
- Pull (`GitCliPullBranchGitAction`) fetches with git, then JGit merges or rebases, as its `PullCommand` does after its
  own fetch: Leaf's automatic stash, conflict handling and built-in LFS stay, and git never opens an editor.
  - What is pulled is what `PullCommand` pulls: the chosen remote branch, or the upstream (`branch.<name>.remote` and
    `.merge`), or the branch of the same name on `origin`. An upstream is fetched with `git fetch <remote>`, which
    updates every remote-tracking branch and marks it for merge in `FETCH_HEAD`. Anything else is fetched by name.
    The commit comes from the `FETCH_HEAD` line marked for merge (`parseFetchHead`), never from a remote-tracking
    branch, which may be stale.
  - The merge message names it with git's own description from `FETCH_HEAD` (`branch 'main' of <url>`), and JGit adds
    ` into <branch>`. `pull.ff` is read as `PullCommand` reads it. An upstream that is a local branch (remote `.`) is
    merged without fetching, and a branch without commits takes the pulled commit.
  - `mergeHasConflicts` and `rebaseHasConflicts` (`remote_operations/PullOutcome.kt`) tell both pulls, JGit's and the
    CLI's, what happened. A merge that would overwrite local changes throws `PullWouldOverwriteException` (JGit
    returns `FAILED`, or throws `CheckoutConflictException` for a fast-forward); it used to count as a pull without
    conflicts. The CLI pull then drops its backup stash, since nothing changed. A rebase's `CONFLICTS` and `FAILED`
    throw it too (local files stopped it, and the branch is as it was); `CONFLICTS` used to count as conflicts.
  - JGit's merge, rebase and checkout run inside `useBuiltinLfs` (both pulls and both clones). It turns on
    `filter.lfs.useJGitBuiltin` in the cached repository's in-memory config, and puts it back in `finally`: an
    action's `config.save()`, such as saving the author, writes that config to `.git/config`.
- Clone (`GitCliCloneRepositoryGitAction`) runs `git clone --progress --no-checkout -- <url> <folder>` from the
  folder's parent, then JGit checks out HEAD with Leaf's built-in LFS, as after JGit's own clone, so git-lfs isn't
  needed. An empty repository gets no checkout. With "Clone submodules", `git submodule update --init --recursive`
  follows; without it, submodules aren't initialized, as with a plain `git clone`.
  - Its progress goes into `CloneState.Cloning` (`GitCliRemoteCommand.run`'s `onProgress`), not to the tab's
    processing screen: the clone dialog shows it and has its own Cancel.
  - A clone that fails or is cancelled is removed: the folder, or what's in it when the user chose an empty folder.
    git removes its own on failure and on SIGTERM, but not after JGit's checkout. A folder with files is refused by
    git and left alone. The removal uses JGit's `FileUtils.delete`, which doesn't follow symbolic links; Kotlin's
    `File.deleteRecursively` does, and would empty the folder that a link in the clone points to.
  - Only a clone whose submodules failed is kept, as with `git clone --recurse-submodules`: `CloneSubmodulesError`
    names its folder. The clone dialog's error box scrolls once it's 200 dp tall, as git's output can be long.
  - `CloneViewModel` no longer creates the folder first, so a failed clone leaves none. JGit's clone, the fallback,
    removes the folder too when it created it.
- **LFS with git-lfs (`GitLfsFetch`):** before JGit checks out a clone, or merges or rebases what a pull fetched, Leaf
  runs `git lfs fetch <remote> <commit>`. JGit's checkout then finds every object in `<git dir>/lfs/objects` (git-lfs
  and JGit use the same layout), so Leaf's built-in client (`app/.../lfs/`) downloads nothing.
  - It runs only when the commit's root `.gitattributes` has `filter=lfs` and `git lfs version` works. In a large
    repository without LFS, git-lfs would read the whole tree to find out. Without git-lfs, the built-in client
    downloads as before.
  - It goes through `GitCliRemoteCommand`, so git-lfs asks for credentials through git's helpers and Leaf's dialogs,
    reaches SSH remotes with the system's ssh (`git-lfs-authenticate`), shows progress ("Downloading LFS objects") and
    can be cancelled. A failure is `LfsDownloadError`, before anything is checked out or merged.
  - A clone then gets git-lfs's hooks (`git lfs update`): `git clone` installs them when git-lfs checks the files
    out, but `--no-checkout` doesn't run it, and without `pre-push` a push would fall back to JGit's upload.
  - Other checkouts (switching branches, reset, stash) are unchanged: JGit runs the configured `filter.lfs.smudge`
    (`git-lfs smudge`) once per file, without Leaf's dialogs, or writes the pointers when git-lfs isn't set up.
- **The built-in LFS client's downloads (fork-only checks):** `DownloadLfsObjectGitAction` downloads to
  `<oid>.<random>.tmp` next to the object in `lfs/objects`, and renames it into place once its size and SHA-256 are
  the pointer's. Otherwise it returns `LfsError.CorruptDownload`, which fails the checkout. The temporary file is
  removed either way.
  - A dropped connection needs the check: Ktor's CIO engine ends the body without an error. The download used to be
    appended to the object's place, unchecked, and kept there truncated.
  - `LfsSmudgeFilter` downloads again when the object in place has another size than the pointer, as git-lfs does.
  - `LfsNetworkDataSource.downloadObject` streams to the file on `Dispatchers.IO` (`prepareGet().execute`): `get()`
    reads the whole body into memory first.
- **The built-in LFS client over SSH** (without git-lfs): `AuthenticateLfsServerWithSshGitAction` asks the host for
  the LFS server as git-lfs does, `ssh [-p <port>] [<user>@]<host> 'git-lfs-authenticate <path> <operation>'`, with
  the URL's path as git-lfs has it (JGit's `URIish` drops the `/` of `ssh://host/~/repo`, which git-lfs keeps).
  - The ssh is the one git-lfs picks: `GIT_SSH_COMMAND`, `core.sshCommand`, `GIT_SSH`, then `ssh`. A command line
    runs with `/bin/sh -c '<command> "$@"'` (Git Bash's on Windows, `GitBash.shellCommand`). `core.sshCommand` is read
    with `git config`, for `includeIf`, and from JGit's config when git can't.
  - It runs through `AskpassProcessRunner` (see Askpass). Success is ssh's exit code: ssh writes warnings to stderr
    when it succeeds too.
- Submodules: "Initialize" and "Update" run `git submodule update --init --recursive --progress -- <path>`
  (`GitCliUpdateSubmoduleGitAction`), so nested submodules are cloned too, which JGit didn't do. Adding one runs
  `git submodule add --progress --name <name> -- <url> <path>` (`GitCliAddSubmoduleGitAction`). git checks submodules
  out itself, with git-lfs when it's installed. Since git 2.38.1 a submodule from a local path or `file://` URL is
  refused unless `protocol.file.allow=always`, as in a terminal. Sync, deinit and delete stay on JGit, as they don't
  touch the network.
- **Choice:** the `Selecting…GitAction`s are bound to the domain interfaces. `RemoteOperationsBackend` picks the git CLI
  unless:
  - the "Use git for remote operations" setting (`AppConfig.RemoteOperationsWithGit`, on by default) is off;
  - no usable git was found, or the build has no helper;
  - a push of an LFS repository (an `lfs` folder in the common git dir, or `filter=lfs` in the root `.gitattributes`)
    wouldn't upload its objects: its `pre-push` hook doesn't run git-lfs, or `git lfs version` fails. JGit uploads
    them itself.

  It decides before git starts, never after a failure, which could push twice. The LFS condition applies only to
  push: a fetch doesn't involve git-lfs, and JGit does a pull's merge and a clone's checkout either way. A clone has
  no repository yet, so it asks `useGitCli()` without one.
- **JGit and SSH:** JGit can't reach SSH remotes. `HandleTransportGitAction` gives an `SshTransport` (so after
  `insteadOf`) the `NoSshSessionFactory`, which fails before connecting with `SshNeedsGitError`. Its reason is
  `RemoteOperationsBackend.whyNotGitCli()` (the setting off, no usable git, no helper), or else an LFS push that git
  wouldn't upload, and its text says what makes git run. HTTPS, `file://` and local remotes keep JGit's fallback.
  JGit's fetch of all remotes reports each remote that failed, the SSH ones with `SshNeedsGitError.describe()`; it
  used to drop every failure, as `HandleTransportGitAction` returns them instead of throwing.
- **Running:** `GitCliRemoteCommand` runs git with no timeout, the askpass variables, Leaf's in-memory cache as git's
  last credential helper (`-c credential.helper=!'<helper>' credential`, only with "Cache HTTP credentials in
  memory"), and git's progress in `RepositoryStateRepository.taskProgress`. Never set `GIT_SSH_COMMAND`: the user's
  `core.sshCommand` must apply. `GitCli` hides a URL's password (`redactUrlPassword`) in the command it logs and puts in
  errors, as a clone's URL may hold one.
  - `GIT_LFS_FORCE_PROGRESS=1` is set, as git-lfs only shows progress on a terminal otherwise. git-lfs writes it to
    stdout, also from the `pre-push` hook, where it lands among `git push --porcelain`'s refs, so both streams go to a
    `GitProgressParser`. When stdout isn't a terminal, git-lfs ends each update with `\n`, not `\r`.
- **Askpass:** `GIT_ASKPASS` and `SSH_ASKPASS` are the helper, with `SSH_ASKPASS_REQUIRE=force` (OpenSSH 8.4+), so
  ssh never prompts on a terminal. The helper reaches `withAskpassServer` through a Unix socket in a new 0700 temp
  folder (a loopback port on Windows), named by `LEAF_ASKPASS_SOCKET`, with a random `LEAF_ASKPASS_TOKEN`.
  `AskpassAnswers` (one per command) answers with Leaf's dialogs, from the prompt (`parseAskpassPrompt`):
  - git's `Username for` and `Password for`: one `HttpCredentialsDialog` for both, or for the password alone when the
    prompt names the user. git-lfs asks the same way through `git credential`, but when no credential helper applies
    to the URL (the cache setting off, no helper of the user's) it asks itself: `Username for "https://host"`, with
    double quotes and no colon;
  - ssh's host key question: `SshHostKeyDialog`, answered `yes`, and ssh adds the key to known_hosts;
  - `Enter passphrase for key`: `SshPasswordDialog`. The passphrase is kept per key file for the session once the
    command authenticated, and dropped when ssh asks for it again. ssh-keygen asks `Enter passphrase for "<path>": `
    (OpenSSH 10) or `Enter passphrase: ` (before), which is about the key that `AskpassAnswers` was given;
  - anything else: `AskpassPromptDialog`, and `AskpassConfirmDialog` for `SSH_ASKPASS_PROMPT=confirm`. Notices
    (`SSH_ASKPASS_PROMPT=none`, such as touching a security key) aren't shown.

  git's prompts are in English (`LC_ALL=C`) and ssh doesn't translate its own. OpenSSH 10 writes `key fingerprint
  is: SHA256:...`, older versions `is SHA256:....`.
- **Errors:** `RemoteOperationError` (`domain/errors/`), from the porcelain's `!` lines (`RefsRejected`) or classified
  from stderr (`remoteOperationError`), always with git's output (`readableGitOutput`). ssh ends its stderr lines
  with `\r\n`, which isn't a progress rewrite. When the user closed one of the dialogs, git's failure is
  `PromptRefused`: git itself only says "terminal prompts disabled".
- **Cancel:** a task that reports progress gets a Cancel button on `ProcessingScreen`.
  `RepositoryStateRepository.cancelCurrentTask` cancels its job, `ProcessRunner` kills git and the programs it
  started, and `UseCaseExecutor` records nothing for a cancelled task, even if its code turned the cancellation into
  an error. `CredentialsStateManager` goes back to `None` when the coroutine waiting for a dialog is cancelled, and
  `AppTab` then closes the dialog.

**Login shell environment (fork-only, `data/shell/LoginShellEnvironment.kt`):** an app opened from the Finder, the
Dock or a Linux desktop launcher inherits a minimal PATH, so hooks can't find node, npx or other Homebrew and nvm tools
(Gitnuro#236).
- At startup, `App.start` calls `prewarm()`. On macOS and Linux, this runs `$SHELL -i -l -c` once in the background,
  with a 10 s timeout, and reads `env -0` printed between random markers (startup files may print, for example
  iTerm's escape codes).
- Only new or changed variables are kept. Shell-process variables (`PWD`, `SHLVL`, `TERM`, …) and the `GIT_DIR`
  family are dropped. Only the count is logged, as values can hold secrets.
- Skipped when `TERM` is set: Leaf was started from a terminal and already has the user's environment. Dev runs
  through Gradle in a terminal skip it too.
- On failure or timeout the inherited environment is kept and the reason is logged. Startup files can check
  `LEAF_RESOLVING_SHELL_ENVIRONMENT=1` to skip slow work.
- Used by `PosixFs`, which overrides `FS.runInShell`. That covers hooks, clean and smudge filters, and diff and merge
  tools, and JGit sets `GIT_DIR` and its other variables after it. Also used by `GitCli`, as `git worktree add` runs
  post-checkout hooks and LFS filters.
- Also used by HTTPS credential helpers, which `CredentialHelpers` (`data/.../credentials/`) finds and runs for
  `HttpCredentialsProvider` and for LFS (`ProvideLfsCredentialsGitAction`). On macOS and Linux they run the way git
  runs them (`posixCredentialHelperCommand`): `!command` as a shell command, an absolute path as it is, and a name
  such as `osxkeychain` or `manager` as `git credential-<name>`, with the `git` on the shell's PATH. The command runs
  through `/bin/sh -c`, with the shell's variables passed to `IShellManager.runCommandProcess(environment = ...)`.
  - The shell is needed: Java looks a program name up on the PATH Leaf started with, not the PATH given to the
    process.
  - `git credential-<name>` is part of the helper protocol, not a git operation, so it doesn't go through `GitCli`.
  - `store` and `cache` run as `git credential-store` and `git credential-cache`, like any other name.
  - Windows is unchanged: the helper runs directly, `WindowsGitCredentialsManagerProvider` finds `manager`, and
    `store` and `cache` are refused. `NixGitCredentialsManagerProvider` is no longer called.
  - After a 401, JGit calls `reset` and then `get` again, up to 3 attempts. `HttpCredentialsProvider.reset` runs the
    helper's `erase` with the credentials it last gave, from the helper or typed by the user, like git's
    `credential_reject`. Like git, Leaf stores credentials only once a request succeeds with them (see `approve`
    below), so rejected ones were never stored.
  - Without a helper, `get` gives credentials from Leaf's in-memory cache (`CredentialsCacheRepository`, app singleton)
    or asks. `reset` removes the ones it took from the cache, but only while they are still the URL's cached ones,
    like `git credential-store erase`. Typed credentials are cached, replacing the URL's entry, once a request
    succeeds with them (`credentialsAccepted`, see `approve` below), and `reset` removes them if a later request
    rejects them.
  - LFS (`ProvideLfsCredentialsGitAction`) first sends a request without credentials, and only after a 401 looks
    for some, like git-lfs (lfsapi/auth.go):
    - With helpers, it asks them about the remote's URL when the LFS server has the remote's scheme, host and port
      (`lfsCredentialsUri`, `getCredURLForAPI` in git-lfs), and about the server's URL otherwise. `GetLfsUrlGitAction`
      gives both, as `LfsServer`. The helpers' credentials are tried once, and erased if the server rejects them.
      Then Leaf asks until the server takes them, and stores those with the helpers.
    - Without one, it uses Leaf's cache with `isLfs = true`, keyed by the LFS server's URL: rejected entries are
      removed, typed credentials are cached once the server takes them.
    - Only a request that succeeds stores or caches anything, as in git-lfs. With helpers, whatever the server took
      is stored with every helper (`approve`), as git-lfs does with `git credential approve`: the helpers' own
      credentials too. Unlike git-lfs, Leaf asks about the LFS server even for object URLs on other hosts (git-lfs
      asks about those URLs).
  - What a helper reads comes from `credentialHelperInput` (fork-only `CredentialUrl.kt`), shared by `get`, `store`
    and `erase` on every OS. It matches git's `credential_from_url`, so Leaf and the git CLI find each other's
    credentials: `host` has the port when the URL has one (`example.com:8443`), and the `useHttpPath` path comes
    from `URIish.rawPath`, without its leading and trailing slashes, decoded like git's `url_decode`
    (`team/project.git`). Never use `URIish.path`, which JGit decodes its own way.
  - Like git, Leaf runs no helper for a URL with a newline, or for a value to send with a newline or a carriage return,
    and `get` gives no credentials. Such a value could add a second `host` line.
  - `CredentialHelpers.find` applies the config the way git's `credential_apply_config` does
    (`credentialSettings`), to every `credential.*` entry in the order git reads them. It always returns the settings,
    with no helpers when none applies, so `credential.username` counts without a helper too:
    - `credential.<url>.*` applies as in git's urlmatch.c (`credentialUrlApplies`). The scheme, host and port must
      match, ignoring case and the default port, and `*` stands for one host label. The key's path must be the
      remote's or a folder above it, and a user name in the key must be the remote's. A key that isn't a URL, such as
      `example.com:8443`, is a partial URL whose parts must equal the remote's.
    - Each `helper` joins the list and an empty one clears it. The last `useHttpPath` and `username` win, and the URL's
      user name beats `credential.username`. A setting without a value is skipped (git refuses it).
  - The entries come from `git config --list -z` through `GitCli`, which also follows `includeIf` (JGit doesn't).
    It runs with `--git-dir=<repository's git dir>`. Without a repository (cloning), it runs with a git dir that
    doesn't exist, so git reads only the global and system config.
    - If git can't run, `jgitCredentialEntries` reads JGit's config instead. JGit doesn't keep the order of different
      subsections, so it lists `credential.*` first, then the matching subsections from the least specific to the
      most. JGit gives `null` for an empty value and `""` for a key without `=`, the other way round from git.
  - What git keeps between helpers is a `HelperCredential` (in `CredentialUrl.kt`, like git's `struct credential`).
    It holds the user name, the password, and what a helper may give besides: `oauth_refresh_token`, and
    `password_expiry_utc` (read like git's `parse_timestamp`, `gitExpiry`). Every helper call, `get`, `store` or
    `erase`, sends the parts that are known, so a helper gets back the refresh token and the expiry it gave.
  - `get` asks the helpers in turn, like `credential_fill`. Each helper is sent what the helpers before it gave,
    starting with the user name that git knows (the URL's or `credential.username`). It stops at the first one that
    completes the user name and the password, so a helper may answer with the password alone.
    - A password whose expiry has passed is dropped, with its expiry, and the next helpers are asked. The user name
      and the refresh token stay.
    - A helper that can't be started is skipped, and `quit=1` stops the search with no credentials.
    - Otherwise it returns `NotStored(credential)`, with at most one of the user name and the password. What the
      user then types is stored together with the rest, such as a refresh token.
  - `store` and `erase` go to every helper, and `erase` waits for each `store` it follows.
  - Once the server accepts credentials, `approve` stores them with every helper, like git's `credential_approve`,
    the helper that gave them included, and waits for them. Like git, it stores nothing without a user name and a
    password, or once the password has expired.
    - HTTPS approves at the first request that succeeds with the credentials, as git's `handle_curl_result` does,
      whether the helpers or the user gave them. JGit doesn't tell its `CredentialsProvider` when credentials work, so
      `HandleTransportGitAction` wraps each `TransportHttp`'s connection factory (`reportAcceptedCredentials`,
      fork-only `AcceptedCredentials.kt`).
    - A 2xx answer to a request with an `Authorization` header calls `HttpCredentialsProvider.credentialsAccepted`.
      That approves what `get` last gave, once per `get`, or caches typed credentials when there is no helper.
    - `cacheCredentialsIfNeeded`, which `HandleTransportGitAction` calls when the block returns, does the same in
      case no request reported it. Each remote of a fetch-all has its own provider.
    - The wrapper keeps a factory's `HttpConnectionFactory2` sessions working: they get the connection that the
      factory created, as JGit's JDK session refuses any other class. A plain factory stays plain, so JGit applies
      `http.sslVerify` itself.
  - Leaf asks only for what git doesn't know, like git's `credential_getpass`, for HTTPS and LFS alike:
    - When it knows the user name (with or without helpers), it asks only for the password.
      `HttpCredentialsRequest.user` and `LfsCredentialsRequest.user` carry the name, and `UserPasswordDialog` shows
      it in a disabled field and starts in the password field.
    - When a helper gave a password but no user name, it asks only for the user name (`askPassword = false`), and
      the dialog has no password field. The password stays out of `credentialsState`.
    - `requestHttpCredentials(user, password)` and `requestLfsCredentials(user, password)` answer with what git knew
      in place of what the dialog sends: the helpers store the credentials under that user name, and look them up by
      it.
    - HTTPS stores a typed user name with a helper's password once a request succeeds with them, like any
      credentials, and `reset` erases them. LFS tries them once, like a helper's credentials: they're stored with the
      helpers if the server takes them, and erased if not, and then Leaf asks for both.
    - After the server rejects a helper's credentials, LFS shows the user name from the settings, not the rejected
      one.
- Also used by `GpgProgramSigner` and `SshProgramSigner`, which run gpg and ssh-keygen (see Commit and tag signing),
  and by the built-in LFS client's ssh.
- Not used by terminals, which `ShellManager` also starts, or by `GitExecutableLocator`, which already searches the
  Homebrew locations.

**Commit and tag signing (fork-only `data/.../signers/GpgProgramSigner.kt`):** `App.start` registers the signers with
JGit's `Signers`: `GpgProgramSigner` for `openpgp`, the default `gpg.format`, and `SshProgramSigner` for `ssh`. Commits,
merges, rebases and tags all find them through `gpg.format`, and `CreateTagGitAction` leaves `tag.gpgSign` and
`tag.forceSignAnnotated` to JGit's `TagCommand`.
- `GpgProgramSigner` runs gpg as git does (`sign_buffer_gpg` in gpg-interface.c):
  - the program is `gpg.openpgp.program` or `gpg.program`, by default `gpg`;
  - it runs with `--status-fd=2 -bsau <key>`, the data on stdin (`ProcessRunner`'s `input`) and the armored signature
    on stdout;
  - it must exit with 0 and print `[GNUPG:] SIG_CREATED` at the start of a status line;
  - the key is `user.signingKey`, or else the committer's `Name <email>`. JGit's `TagCommand` passes no key, so signers
    read `config.signingKey` themselves; `SshProgramSigner` does too.
- gpg gets `LoginShellEnvironment`'s variables. A program name is looked up on that PATH, then on macOS in the Homebrew
  and GPG Suite folders, since Java's `ProcessBuilder` would search the PATH Leaf started with. `locateProgram`
  (`cli/ProgramLookup.kt`) does it, for ssh-keygen and ssh too.
- On Windows, a program name is looked up as Git for Windows' git does: first in the `ucrt64\bin` (`mingw64\bin`
  before 2.56) and `usr\bin` of the Git install that `findGitForWindows` (`GitBash.kt`) picks for hooks, then on PATH,
  as `<name>.exe` and then the name as it is. So the gpg bundled with Git wins over Gpg4win's, as with
  `git commit -S`, unless `gpg.program` names a path. Not run on Windows yet.
- gpg-agent's pinentry asks for passphrases; Leaf has no prompt of its own for gpg. Without a terminal,
  `pinentry-curses` (Homebrew's default) fails with "Inappropriate ioctl for device". The `FAILURE` status codes show
  it, and it becomes `GpgSigningError.PinentryUnavailable`, which suggests pinentry-mac.
- Signing times out after 2 minutes, as nothing can cancel a commit while it runs.
- Failures throw `GpgSigningException`, a `CanceledException`, because JGit wraps a signer's other exceptions into
  "Exception caught during execution of commit command". `JGit.provide` finds it in the cause chain and returns its
  `GpgSigningError` before the operation's own `errorHandle` runs.
- JGit's BouncyCastle signer (Leaf's old `AppGpgSigner`) is gone: it couldn't find keys kept by keyboxd
  (`use-keyboxd`, GnuPG 2.4's default), and its ED25519 signatures failed to verify (Gitnuro#194, #293).
- BouncyCastle is gone too. `jgit-gpg`, `bcpg` and the JCE provider that `main.kt` registered were only there for that
  signer:
  - Leaf's own JCE calls (AES, SHA-256) and the TLS of JGit, Ktor and OkHttp use the JDK's providers.
  - SSH and SSH signing run OpenSSH's programs.
  - Leaf never verifies signatures. Without `jgit-gpg`, JGit has no OpenPGP verifier for `Git.verifySignature()`;
    verifying would mean running gpg, as signing does.
- `SshProgramSigner` (fork-only, `data/.../signers/`) runs ssh-keygen as git does (`sign_buffer_ssh`):
  - the program is `gpg.ssh.program`, by default `ssh-keygen`, read from the config itself: JGit's `GpgConfig.program`
    falls back to `gpg.program`, which git uses only for OpenPGP;
  - it runs `-Y sign -n git -f <key> [-U] <file>` on a temp file, and the signature is `<file>.sig`, without carriage
    returns. Not stdin, so that stand-ins such as 1Password's `op-ssh-sign` get git's arguments;
  - the key is `user.signingKey`, or else the first line that `gpg.ssh.defaultKeyCommand` prints, if it's a public
    key (the command is split like git's `split_cmdline` and runs without a shell). A public key (`key::ssh-…`, or the
    deprecated `ssh-…`) goes into a temp file with `-U`, so the agent signs. Anything else is a path: `~/` is expanded
    and a relative one is in the working tree;
  - ssh-keygen asks for a passphrase through `AskpassProcessRunner`, which is given the private key's path. ssh-keygen
    asks once, so Leaf runs it again (three times at most) when it says `incorrect passphrase supplied`;
  - failures are `SshSigningError`, thrown as `SshSigningException`, like gpg's. It times out after 2 minutes.

**External processes (upstream code):** upstream never invokes the `git` CLI. `ProcessBuilder` is only used in `domain/.../ShellManager.kt`
(credential helpers, terminals, opening a file manager) and in `FileExtensions.kt`. Leaf's `WindowsFs` runs Windows
hooks with JGit's `FS.runProcess` instead.

Worktree operations will go through a fork-only git CLI adapter in its own package (Phase 1.1).

## DI, coroutines, state

**Dagger (KSP):**
- `AppComponent` (`@Singleton`) has a subcomponent `TabComponent` (`@TabScope`), created per tab by
  `AppViewModel.newAppTab2` and held by `RepositoryTabViewModel`.
- Tab-scoped: `TabCoroutineScope`, `InMemoryRepositoryDataRepository`, `InMemoryRepositoryStateRepository`, all git
  actions, `FileChangesWatcher`.
- App-scoped: the `JGit` cache, `AppStateManager` (recent repos), settings.

**Scopes:** `TabCoroutineScope` and `TabViewModel.viewModelScope` are both `SupervisorJob() + Dispatchers.Default`.
- Never block their threads: `Dispatchers.Default` has one per core, shared by every tab. Blocking calls go to
  `Dispatchers.IO`, and a loop that blocks for as long as the tab is open, like the file watcher's (see Refresh), to a
  view of its own, `Dispatchers.IO.limitedParallelism(1)`, which doesn't count against IO's 64 threads.
- Wait for a state with `first { ... }` on its flow, never a loop that reads `.value`: the commit identity dialog's
  loop (`StatusViewModelExtender.getIdentity`, now `awaitAnswer`) kept a core busy while the dialog was open
  (fork-only fix).
- **Tear-down (fork-only):** closing a tab (`AppViewModel.closeTab`) or replacing it (`replaceTab`) calls
  `RepositoryTabViewModel.dispose()`. That clears every view model in `viewModelsMap` and its own (`onClear`, which
  cancels the children of `viewModelScope`), closes the watcher, cancels `TabCoroutineScope` and clears the data.
  Before, only `TabCoroutineScope` was cancelled, and a replaced tab wasn't disposed at all.
  - A view model collects in its own `viewModelScope`, not `TabCoroutineScope`, so that clearing it stops what it
    started. `HistoryViewModel` collected the diff setting in the tab's scope, once more per file history opened.
  - `RepositoryOpenViewModel.onClear` clears its `historyViewModel` and calls `super`; upstream had left it empty,
    so its pipelines outlived it. It also runs when the `RepositoryOpen` entry leaves the tab's back stack
    (`removeViewModel`), as when the tab opens a repository in place.

**ViewModels:**
- They extend `TabViewModel`. Child view models come from `tabViewModel(key) { component -> ... }`, with assisted
  factories when they take parameters.
- State is exposed as `StateFlow`s built with `combine(...)` and the context-parameter helpers `stateIn(initial)`
  (Lazily) and `toUiDataState()` (300 ms Loading debounce).
- Newer code uses explicit backing fields: `val x: StateFlow<T>` + `field = MutableStateFlow(...)`. Older code uses
  `_x` / `asStateFlow()`.
- Newer screens take `onAction(SealedAction)`.

**Data state:** each piece of repository data is a `Flow<DataState<T>>` (Loading/Loaded/Error) in
`InMemoryRepositoryDataRepository`. `RefreshDataUseCase` refreshes it per `DataToRefresh` value. A step that throws
ends as `DataState.Error`, logged (fork-only); it used to stay Loading, without a log line.

**Dialogs** are Navigation3 destinations:
- `sealed interface Screen` in `App.kt`, `entry<Screen.X>` in `ui/AppTab.kt`.
- Opened with `backStack.add(...)`, through an `onNavigate` lambda.
- The Actions list (`QuickActionsDialog`) closes itself before it runs an action, so an action that opens a dialog only
  adds it. Closing afterwards would remove the new dialog, the top of the back stack.
- `ui/dialogs/base/IconBasedDialog.kt` is the base for confirm-style dialogs.
- Every dialog renders through `ui/dialogs/base/MaterialDialog.kt`. `DialogSceneStrategy` uses Compose's common
  `Dialog`, which on desktop draws on the main window's canvas, not in an OS window, and only takes clicks inside its
  content's bounds. So `MaterialDialog` fills the space it's given and places the dialog itself, centered plus the drag
  offset. Its top 16 dp are a drag strip, so keep controls out of them; `Modifier.dialogDragHandle()` (from
  `MaterialDialogScope`) adds more handles, like the Settings title.
- **Confirmations (fork-only):** actions that can lose work open a dialog first.
  - Deleting a branch or a tag goes through `DeleteRefDialog`: without force first, then "Delete anyway" once git
    refuses. A branch that a worktree uses is refused even with force (`DeleteBranchError`, see Delete and rename
    guard), and the dialog then disables Delete.
  - The others go through `Screen.ConfirmAction(action, onConfirm, onStopAsking)` and `ConfirmActionDialog`.
    `ConfirmableAction` says what the dialog shows, and `onConfirm` is what the button used to run, so the action's
    own code is unchanged. Callers get an `onConfirmAction` lambda. Used for deleting a submodule, a file, a remote
    branch or a remote, dropping a stash, aborting a merge, rebase, cherry-pick or revert, skipping a rebase commit,
    force push, and discarding a file or a selection (the Staged and Unstaged menus), a hunk or a line (the diff).
    Discarding a folder has its own `DiscardChangesDialog`. The reset dialog warns when Hard would discard
    uncommitted changes.
  - Discards show what is lost: a hunk's header, the line. A file or a selection in the Staged list loses its unstaged
    changes too, as `DiscardEntriesGitAction` resets the index entry and then checks the file out. Putting back
    removed lines (a line, or a hunk that adds none) or a deleted file loses nothing, so those get no warning.
  - Only hunks and lines can stop asking. The diff passes `onStopAsking`, which adds "Don't ask again for hunks and
    lines"; confirming with it checked turns off `AppConfig.ConfirmHunkAndLineDiscards` (`user_prefs.json` key
    `confirm_hunk_and_line_discards`, on by default, Settings → Layout). `DiffPane` reads it through
    `RepositoryOpenViewModel.confirmHunkAndLineDiscards`, which is true until the settings file is read. Discarding
    files always asks. A file that changes while the dialog is open is refused like any stale hunk (`StaleHunkError`,
    see Hunk and line actions).
  - `onStopAsking` is `Screen.ConfirmAction`'s last parameter, so pass `onConfirm` in the parentheses: a trailing
    lambda would be `onStopAsking`, and fail to compile without `onConfirm`.

## Repository tabs

**Opening a tab:**
- Paths in: `App.start` restores tabs from prefs (`AppViewModel.loadPersistedTabs`) and handles the CLI argument;
  the Welcome page (picker or recent repos) calls `RepositoryTabViewModel.openRepository`; the menu's "open another
  repository" replaces the current tab; switching to a worktree (fork-only, see Identity) selects or adds one.
- `AppViewModel.addNewTabFromPath` only adds a tab. Replacing goes through `replaceTab(tab, path)` (fork-only), with the
  tab from `LocalTab`, and disposes the old one (see DI, coroutines, state). The tab used to be found by path, which
  matched any tab without one: the Welcome page, or a tab whose path nothing had collected yet (a lazy `stateIn`),
  such as one scrolled out of the tab bar. So adding a tab from the CLI, a submodule or a worktree switch could
  replace such a tab, which kept running.
- Tabs load lazily. `AppTab` calls `loadTab()` when a tab is first composed.
- Then `OpenRepositoryUseCase` → `OpenRepositoryGitAction`, which validates the path and returns the git dir. After
  that: `RepositorySelectionState.Open(gitDir)`, the working-tree path goes into recent repos, then
  `RefreshDataUseCase(ALL)` and `ObserveRepositoryToRefreshUseCase()`.
- Fork-only: a linked worktree doesn't go into recent repos (`isLinkedWorktreeGitDir`, a `commondir` file in the git
  dir), however it's opened. Its repository does, and agents' worktrees get deleted, which left dead entries.
- Fork-only: "Start a local repository" (`RepositoryTabViewModel.initLocalRepository`) opens the repository once
  `InitLocalRepositoryGitAction` made it, and shows its failure, such as a folder that can't be written, in
  `ErrorDialog` (`TaskType.RepositoryInit`). The failure used to be thrown and lost.

**State per tab:**
- `InMemoryRepositoryDataRepository`: status, local branches, current branch, tags, remotes, log, stashes,
  submodules, selection state, repo state, rebase state, author, persisted commit message.
- `InMemoryRepositoryStateRepository`: current task, completed tasks, last-operation timestamp, refresh trigger.
- UI state is mostly in one large `RepositoryOpenViewModel`: side panel, log, status, diff.

**Identity and persistence:**
- Tabs are identified by object, not by path. Opening a repository from the Welcome page or the CLI never looks for
  a tab that has it.
- **Switching to a worktree (fork-only):** `AppViewModel.selectOrOpenTab(folder)` selects the first tab with the same
  git dir, or adds a tab at the end. `indexOfRepository` (`domain/worktrees/RepositoryPaths.kt`) compares real paths
  of git dirs: `gitDirOf` turns a working tree into its `.git` folder or the git dir its `.git` file points to
  (absolute or relative), and keeps a git dir as it is. A tab gives `pathToPersist`, so an open tab gives its git dir
  and one that hasn't loaded the path it was created with. `RepositoryOpenViewModel.switchToWorktree` calls it, and
  `RepositoryTabViewModel.switchToWorktree` for the error dialog (see Branches).
- **Tab name and tooltip:** `RepositoryTabViewModel.repositoryPath` (the tooltip) is `workTreeOf` the git dir, and the
  name is its last part. For a linked worktree's git dir, that's the folder that its `gitdir` file names, not
  `<common git dir>/worktrees/<name>`; otherwise the git dir without `/.git`, as before. "Open another repository"
  compares this path with the working tree, so it now replaces a linked worktree's tab too.
- Persisted keys in the prefs node (`LeafConfig`, see Name): `latestRepositoriesTabsOpened` (JSON),
  `latestRepositoryTabSelected`, and
  `lastOpenedRepositoriesList`.
- What is saved: open tabs as their git dir (`Open.path`), and tabs that haven't loaded yet as the path they were
  created with (`RepositoryTabViewModel.initialPath`). Tabs on the welcome page (`None`) are skipped.
- Never save `repositoryPath.value`. It is started lazily and stays `null` until something collects it, which tabs
  scrolled out of the tab bar never do. A `null` in the saved list makes `loadPersistedTabs` throw at startup.

## Sidebar and branch list

**Side panel** (`app/.../ui/SidePanel.kt`): a filter field plus one `LazyColumn` with a `LazyListScope` extension per
section: `localBranches`, `worktrees` (fork-only, `ui/WorktreesSection.kt`), `remotes`, `tags`, `stashes`,
`submodules`.

**Section state:** lives in `RepositoryOpenViewModel` as one `isExpandedX` flow plus one combined `xState` flow per
section. The state classes are in `viewmodels/sidepanel/SidePaneStates.kt`. `SidePanelChildViewModel` is unused.

**Rows:**
- `ui/components/SideMenuEntry.kt` (`SideMenuHeader`) and `SideMenuSubentry.kt` (icon, text, `additionalInfo` slot,
  `onDoubleClick`, and the fork-only `textContent` slot, which replaces the text and `additionalInfo`).
  `additionalInfo` must stay the last parameter: upstream's calls pass it as a trailing lambda, which would otherwise
  bind to the parameter after it, without a compile error.
- Tooltips: `ui/components/tooltip/DelayedTooltip.kt` and `InstantTooltip.kt`.
- Context menus: `ui/context_menu/*ContextMenu.kt`. `branchContextMenuItems` is shared with the log's `BranchChip`.

**Branches:**
- Model: `domain/models/Branch.kt` (`hash`, full ref `name`, `isLocal`). It has no tracking or ahead/behind fields.
- Loaded by `GetBranchesGitAction` (`git.branchList()`) via `RefreshDataUseCase.refreshBranches`.
- Double-clicking a local branch checks it out immediately. When another worktree uses it, it switches to that
  worktree instead (fork-only, `RepositoryOpenViewModel.checkoutBranch`), from the side panel and from the log's
  chips: `BranchWorktreeUsers.worktreeToSwitchTo`, which leaves out a worktree whose folder is missing
  (`Worktree.canSwitchTo`). Then, or when the worktree list is stale, the checkout guard refuses, and its
  `ErrorDialog` has a "Switch to worktree" button while the worktree's folder exists. That also covers double-clicking
  `origin/x` while another worktree has `x`.
- Double-clicking a remote branch (fork-only, `RepositoryOpenViewModel.checkoutRemoteBranch`) checks out the local
  branch with its name, or creates one that tracks it. `GetRemoteBranchCheckoutGitAction` compares the two first: a
  local branch that is behind with no commits of its own gets `FastForwardOnCheckoutDialog` (through
  `fastForwardOffers` and `Screen.FastForwardOnCheckout`), any other is checked out as it is.
  `CheckoutRemoteBranchGitAction` moves a branch that isn't checked out with a `RefUpdate` before the checkout, and
  the current branch with `merge --ff-only`.
- **Checkout guard (fork-only):** JGit checks out a branch that another worktree has, unlike git. So
  `CheckoutBranchGitAction`, and for an existing local branch `CheckoutRemoteBranchGitAction` (before its fast-forward)
  and `GetRemoteBranchCheckoutGitAction` (so nothing is offered), call `refuseIfUsedByOtherWorktree`. It raises
  `CheckoutBranchError.BranchUsedByWorktree` with the branch, the worktree's folder and a `WorktreeBranchUse`.
  - `findOtherWorktreeUsing` (`data/git/worktrees/BranchWorktrees.kt`) reads the worktrees' git dirs as git's
    `die_if_checked_out` does: the main one unless the repository is bare, then `<common git dir>/worktrees/*`. A
    worktree uses a branch when its `HEAD` file points to it, or when HEAD is detached while it rebases the branch
    (`rebase-merge` or `rebase-apply` `head-name`) or bisects from it (`BISECT_START`). A deleted folder still counts.
  - The branch that the tab's worktree has checked out is never refused, as in git.
  - It doesn't see worktrees whose refs are in a reftable: their `HEAD` file is a stub, and JGit 7.7 reads every
    linked worktree's HEAD from the shared reftable (the main worktree's branch).
- **Delete and rename guard (fork-only):** JGit deletes and renames a branch that another worktree has checked out,
  which leaves that worktree on a branch that doesn't exist.
  - `DeleteBranchGitAction` calls `refuseDeletingIfUsedByWorktree` before its merge check, so force doesn't get past
    it, as with git's `-D`. It raises `DeleteBranchError.BranchUsedByWorktree` when any worktree uses the branch, the
    tab's own included (`worktreesUsing`, which `findOtherWorktreeUsing` filters).
  - `RenameBranchGitAction` refuses while a worktree, the tab's own included, rebases the branch or bisects from it
    (`RenameBranchError`). After JGit's rename, it points the HEAD of every other worktree that had the branch to the
    new name (`RefUpdate.link` on that worktree's git dir), as git's `branch -m` does. That writes no reflog entry:
    JGit 7.7 would write it to the main worktree's `logs/HEAD` (`architecture-notes.md` §6). A HEAD it can't move is
    reported as `WorktreeHeadNotMoved`, with the branch renamed, as git leaves it.
  - `RenameBranchDialog` shows errors under the field, and disables Rename branch after a `RenameBranchError`.
- **Worktree badges and menus (fork-only, `ui/BranchWorktreeBadge.kt`):** a local branch that another worktree uses
  gets a folder icon, plus the worktree's folder name unless it repeats the branch's last part (`nameNextTo`), as
  agents' folders do. Its tooltip says how the worktree uses the branch and what that rules out.
  - Local branch rows lay out their text with `BranchNameWithWorktree`, through `SideMenuSubentry`'s `textContent`.
    The branch name comes first, and ends in "…" when it doesn't fit next to the icon. The worktree's name gets what's
    left, and is left out below about five characters (`MIN_WORKTREE_NAME_WIDTH`, `worktreeNameWidth`).
  - `RepositoryOpenViewModel.branchWorktrees` (`BranchWorktreesState`) holds `WorktreeList.usersByBranch()`. The side
    panel passes it to its branch rows, and `Log` provides it to its branch chips as `LocalBranchWorktrees`, which
    keeps it out of the upstream composables in between.
  - `branchContextMenuItems` takes the branch's `BranchWorktreeUsers` and leaves out what the guards would refuse:
    Checkout while another worktree uses the branch, Delete while any does, Rename while any rebases it or bisects
    from it. The guards still decide, as the list may be stale. In place of Checkout it has "Switch to worktree"
    (`onSwitchToWorktree`), which the log's chips get as `LocalOnSwitchToWorktree`.
  - It also takes the base choice (`BranchWorktreesState.baseChoice`): "Compare worktrees to this branch" on any other
    branch, local or remote, and "Compare worktrees automatically" on the chosen one. The log's chips get the callback
    as `LocalOnChooseWorktreesBase`, next to `LocalBranchWorktrees`.

**Worktrees section (fork-only, `ui/WorktreesSection.kt`):** one two-line row per worktree, in git's order, built by
`worktreeRows` (`domain/worktrees/WorktreeRows.kt`) from `WorktreesState`.
- Line 1: the folder name (bold for the tab's worktree), a lock icon when locked, and the last commit's age, or
  "missing" for a prunable worktree, whose row is greyed out. The main worktree has the `source` icon.
- Line 2: the branch, or "rebasing x", "bisecting from x", "detached at abc1234"; a dot for uncommitted changes (the
  conflict color when there are conflicts); and `↑ahead ↓behind` versus the base branch.
- The tooltip (`worktreeTooltip`) has the full path, the change counts, the comparison to the base and to the
  upstream, the lock reason, and for a prunable worktree git's reason and `git worktree prune`.
- A click selects the worktree's commit in the log (its branch when it's on one). A double-click switches to the
  worktree (see Identity and persistence), as does "Switch to worktree" in its menu (`worktreeContextMenuItems`),
  above "Copy path". Neither is offered for the tab's own worktree, a prunable one or a bare repository's
  (`Worktree.canSwitchTo`). The side panel's filter matches the folder name, the path and the branches. When git
  can't list the worktrees, one line says so, with the error in its tooltip.
- The header's button (`WorktreesBaseMenuButton`, the sort button's `SortMenuButton` with the `compare_arrows` icon)
  is muted while the base is automatic, and shows the chosen branch's name otherwise. Its menu shows what Automatic
  picks, the chosen branch (with "Not found" when it's missing), and a note pointing to the branch menus.

**Adding a section touches:** domain model and git action, `RepositoryDataRepository` and its in-memory
implementation, `RefreshDataUseCase` (a new `DataToRefresh`), `SidePaneStates.kt`, `RepositoryOpenViewModel`,
`SidePanel.kt`, a context menu file, `strings.xml`, and a drawable.

**Sorting and grouping (fork-only):**
- Pure logic in `domain/.../sorting/`: `naturalCompare`, `buildRefRows` (sort, keep HEAD on top, group by prefix into
  `RefRow.Folder`/`Item`), `buildFileRows` (flat or compacted folder tree into `FileRow`), `formatAge`, the reflog
  parser, and the settings models with their JSON codec.
- Section rows are built in `viewmodels/sidepanel/RefRowsBuilder.kt` from `RefRowsContext` (settings, `RefDates`,
  folder state). Section headers are `stickyHeader`s; the sort button and menu are in `ui/components/sort/`.
- Dates come from `GetRefDatesGitAction`, refreshed with branches, remotes or tags (`repositoryDataRepository.refDates`).
- Settings: `AppConfig.RefPanel` and `AppConfig.FilesChangedView`, as JSON in the DataStore file. Folder open/closed
  state is per repository in `<common git dir>/leaf`, section `sidePanel` (`RefFolderExpansionConfig`).
- Files changed, Staged and Unstaged render `FileRow`s with `ui/ChangedFilesList.kt` (`CommitChangesState.rows`,
  `StatusState.stagedRows`/`unstagedRows`) and share `AppConfig.FilesChangedView`. The old `show_changes_as_tree` key
  is only read, as that setting's default until it's saved.
- **Status pane sections (fork-only):** two 8 dp handles resize Staged, Unstaged and the commit field.
  - `StatusSectionSizes` (`domain/models/`) holds Staged's share of the lists and the commit field's height. `fitTo`
    fits them to the pane: each list keeps 100 dp and the commit field 140 dp. The drag functions start from the
    fitted heights, so a drag never has to undo an overshoot.
  - They are global: `StatusSectionsConfig` (app singleton) → `AppSettingsRepository.statusSectionSizes`, stored in the
    prefs node as `statusStagedShare` and `statusCommitFieldHeight`, like the pane widths. `StatusPane` keeps a local
    copy during a drag and sends `StatusAction.SectionSizesChanged` when it ends.
- **Files changed pane sections (fork-only):** one handle resizes the commit message below the files list, the same
  way. `CommitChangesSectionSizes` holds the message height (default 120 dp); the files list keeps 100 dp and the
  message 40 dp. The author footer is laid out outside the fitted area, so its 72 dp isn't part of the model. Saved
  through `CommitChangesSectionsConfig` → `AppSettingsRepository.commitChangesSectionSizes`, prefs key
  `commitMessageHeight`.
  - Both panes use `ui/components/SectionDivider.kt` (`SECTION_DIVIDER_HEIGHT`, 8 dp).
- **Offscreen UI checks:** an `ImageComposeScene` built from the real Dagger graph can render `SidePanel` and
  `CommitChanges` to PNG without a window. Drive it on `Dispatchers.Swing`, like a real window. On another thread,
  Compose 1.12.0 intermittently threw "LayoutNode … not found in RectList"; 1.12.1 fixes one cause of that, but the
  harness no longer reproduced it on either version, so the fix is unverified there. `CommitChanges` also needs
  `LocalTab`.
  `LocalWindowInfo.current.keyboardModifiers`, which Staged/Unstaged read for Shift and Ctrl clicks, only follows a
  real window; offscreen, set `WindowInfoImpl`'s `GlobalKeyboardModifiers` state through reflection.
  - `StatusPane` renders too, with `LocalTab` and the tab's `repositoryOpenViewModel()`. Find elements through
    `scene.semanticsOwners`, and simulate a window resize by setting `scene.constraints`.
  - Only one Dagger graph per JVM: DataStore refuses a second instance on `user_prefs.json`. To check what survives
    a restart, write in one Gradle run and read in another.
  - The harness is a dev run, so it writes to the `LeafDevConfig` prefs node. Remove the keys it adds afterwards.
    `openRepository` also writes `lastOpenedRepositoriesList`. `AppStateManager` only knows the saved list after
    `loadRepositoriesTabs()`, so the harness replaces the whole list with the temp repository, a dead entry under
    the Welcome page's recent repositories. Save that key first and put it back afterwards.
  - `Log` renders with just the tab's `repositoryOpenViewModel()`, its `selectedItem` and `repositoryState`, inside
    `AppTheme`. Settings it changes go to the dev `user_prefs.json`; copy that file first and put it back.
  - Hover tooltips (`InstantTooltip`) don't show offscreen, not even the graph node's, so check them in a dev run.

## Log

**Columns (fork-only):** after Graph and Message, the log can show Author, Committer, Date and Commit. The columns
menu (the header's columns button, or a right-click anywhere on the header) checks them, adds the time to dates, and
resets them. By default only Date is on, as before.
- `LogColumnsSettings` (`domain/.../models/LogColumns.kt`) holds an ordered list of `(column, isVisible, width)`,
  `dateShowsTime` and `graphMaxWidth`. The list's order is the display order, so reordering can come later without
  migrating anyone's settings.
- One setting for every tab: `AppConfig.LogColumns`, stored as JSON in `user_prefs.json` (key `log_columns`) by
  `LogColumnsCodec`, which never fails to read. A column missing from the saved list, such as one a later version
  added, goes right after the column before it in the default order (`withMissingColumns`): Committer lands after
  Author.
- Widths are in dp. A column's divider in the header resizes the column on its right, and Message takes what's
  left. A divider stops where Message would drop below 200 dp (`maxWidthFor`). `LogView` keeps a local copy while a
  divider is dragged and sends `LogAction.ResizeColumn` or `SetGraphMaxWidth` when the drag ends.
- When the log is too narrow, `fitting` leaves out checked columns until Message has 200 dp: Committer first, then
  Commit, then Author, then Date. The menu marks them "Needs more room". `Log` measures its width with `BoxWithConstraints`.
- The graph is as wide as its lanes, up to `graphMaxWidth` (120 dp by default, the old fixed limit), and at least
  56 dp (`graphColumnWidth`). Dragging its divider sets `graphMaxWidth` (`draggedGraphMaxWidth`): never past the
  lanes, and a drag that changes nothing on screen keeps the saved size, which may come from a busier repository.
- "Show time" puts the time (`toSystemTimeString`, Settings' time format) at the end of the Date cell, a shade
  lighter, so that the times line up in a column of their own, and widens Date to 180 dp if it's narrower. A time
  that doesn't fit is left out whole (`hiddenWhenTruncated`), not cut. Dates and times use tabular digits (`tnum`).
- The cells use the message's 13 sp text (`body2`); Date and Commit are grey. At 13 sp with tabular digits,
  "Dec 22, 2025" is 86.5 dp wide and a short hash 55.5 dp. With 16 dp of padding and an 8 dp gap before the time,
  "Dec 22, 2025" and "10:48 AM" fit in 180 dp: hence 110, 180 and 80 dp.
- **Author and committer:** each row is a commit, and git records two people on it. Author is who wrote the change;
  Committer is who put this version of it in the history, which differs after someone else's rebase, cherry-pick or
  amend, and is GitHub for pull requests merged on its website.
  - `Commit.isCommittedBySomeoneElse` compares them by email, ignoring case, or by name when either has no email.
  - Then the author's avatar carries the committer's 10 dp avatar in grey on its bottom-right edge, and the Committer
    cell's avatar is grey too. The badge's circle, plus a 1.5 dp ring, is cleared from the author's avatar
    (`BlendMode.Clear` in an offscreen layer), so it stands apart on any row background, the selected row's included.
  - `AvatarImage` draws the initial in `LocalTextStyle`, so the badge provides a 7 sp style.
  - Every row keeps the badge's room (20 × 18 dp, then 4 dp before the name), so names line up.
- **Dates:** Date is the commit date, by which the log orders its rows (`RevSort.COMMIT_TIME_DESC`). After a rebase,
  the dates the changes were written would jump out of order. `Commit.authorDate` holds when the change was written.
  The Date tooltip shows both when they differ as displayed (to the minute), and the Author tooltip shows both
  people with their dates when someone else committed.
- The UI is in `ui/log/LogColumns.kt`. The menu reuses the sort menu's `SortMenuPopup`, which takes a position.
- The commit row's menu has "Copy commit hash".

## Refresh

**Rust** (`rs/src/lib.rs`):
- `FileWatcher` on notify 8. `watch()` is a **blocking** loop that batches Create/Modify/Remove events and flushes
  them with a ~500 ms throttle.
- It is called from a `callbackFlow` in `data/.../git/FileChangesWatcher.kt`, which runs on a single-thread view of
  `Dispatchers.IO` per tab (`flowOn`, fork-only). It used to run on the tab's `Dispatchers.Default`, one thread per
  loaded tab, so with as many tabs as cores nothing else on Default ran. `FileChangesWatcherTest` (`:data`) loads the
  library from `app/src/main/resources` through `uniffi.component.leaf_rs.libraryOverride`.
- `add_watch` error codes are ignored.

**`domain/.../usecases/ObserveRepositoryToRefreshUseCase.kt`:**
- What it watches:
  - the working-tree root and every non-ignored subdirectory, each non-recursive. Fork-only: except the folders of
    the repository's linked worktrees inside it, such as agents' `.claude/worktrees/<name>`
    (`WatchedRepository.isLinkedWorktreeFolder`: a `.git` file that points into `<common git dir>/worktrees/`).
    Every file that an agent or its build wrote there used to refresh the tab's status and log, unless a gitignore
    covered the folder. Submodules' `.git` files point into `modules/`, so they're still watched;
  - the git dir, non-recursive;
  - `<gitdir>/refs` and `<gitdir>/modules`, recursive;
  - fork-only: `<common git dir>/worktrees`, recursive, added once git creates it (with the first linked worktree).
    For a linked worktree, also the common git dir, non-recursive, and its `refs`, recursive.
- What a batch of changes triggers comes from `WatchedRepository` (fork-only, `domain/.../refresh/`), by the most
  important change in it:
  - ALL for the tab's git dir, and in a linked worktree for the shared `refs/`, `packed-refs` and `config`;
  - `STATUS + LOG + REPO_STATE` for anything else, the working tree (which refreshes the worktree list too);
  - WORKTREES for another worktree's git dir under `<common>/worktrees/`, and in a linked worktree for the main
    worktree's files in the common git dir (`HEAD`, `index`, …);
  - nothing for Leaf's `leaf` file (and `leaf.lock`) and JGit's `.probe-` files in either git dir, or for
    `COMMIT_EDITMSG`, `MERGE_MSG` and `SQUASH_MSG` in the tab's git dir.

  Paths are compared as given and as real paths (macOS reports `/private/var/...`), and a folder only contains what
  is under it with a separator. The old `startsWith(gitDir)` also matched `.gitignore` and `.github/`, which
  refreshed everything.
- WORKTREES alone goes through `WorktreeChangesRefresher`: right away, then at most once every 2 s while changes keep
  coming. An agent that runs git rewrites its worktree's index all the time, and each refresh runs git in every
  worktree.
- Events are dropped (not deferred) while a task runs and for 1.5 s after one.

**Taking turns (fork-only, `domain/DataRefreshRunner.kt`):** a tab's refreshes run one at a time.
- `RefreshDataUseCase` runs them through the tab's `DataRefreshRunner`. A call made while a refresh runs waits; calls
  made while one waits add their `DataToRefresh` to its refresh and return at once, so a burst of changes queues one
  refresh at most. Its job then ends before its data is refreshed.
- Before, every call ran at once. While an agent wrote in the tab's worktree, the watcher's refreshes overlapped: two
  walks of the log shared the tab's `GraphLogGenerator`, whose lanes aren't thread-safe, and the older result could
  land last.
- Loading more commits (`IncreaseLogCountUseCase`) runs between refreshes (`runAlone`), as it adds to the log they
  replace.
- The worktree list stays outside: it has its own runner, and a call for WORKTREES alone skips this one.

**Polling (fork-only):** nothing watches the other worktrees' working trees, so what an agent changes in their files
shows up through `PollWorktreesUseCase` (`worktreePollTicks` in `refresh/WorktreesPolling.kt`).
- It refreshes the worktree list every `AppConfig.WorktreesRefreshInterval` seconds (Settings → Git → Worktrees:
  never, 5, 10 by default, 30 or 60; never less than 2 s, whatever the settings file says) while
  `WorktreesVisibility.isShown`: the tab is selected, its side panel shows the Worktrees section expanded, and the
  window isn't minimized. Also while another app has the focus, as agents run in a terminal next to Leaf.
- `SidePanel` reports it to `RepositoryOpenViewModel.onWorktreesVisibilityChanged`, with
  `LocalWindowInfo.isWindowFocused` and the fork-only `LocalWindowMinimized` (provided in `App.kt`). Only the selected
  tab's content is composed, so the other tabs don't poll.
- A refresh comes an interval after the list's last one, whatever ran it (`ConflatedRunner.lastRunEndedAt`), so
  right away when the list is older, as after switching back to the tab. When the window gets the focus while the
  list is shown, it refreshes right away, unless it did in the last second.
- None while an operation runs: the operation refreshes what it changed.
- The section keeps the old list while a refresh runs, and an equal list doesn't recompose anything. In a test
  repository with four worktrees, a refresh took 80 to 150 ms.

**No other refresh on window focus.** Manual refresh is F5 / Ctrl+R, which becomes Cmd+R on macOS
(`keybindings/Keybinding.kt`).

## Conventions

**Code style:**
- Kotlin official style (`kotlin.code.style=official`), 4-space indent.
- Classes are named `XUseCase`, `IXGitAction` / `XGitAction`, `XViewModel`.
- Composables are PascalCase. Side panel sections are lowerCamel `LazyListScope` extensions. Callbacks are `onXxx`.

**Licensing:**
- New files written for Leaf start with these two lines, then a blank line:
  `// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev` and `// SPDX-License-Identifier: AGPL-3.0-only`.
- Files that come from Gitnuro stay GPL-3.0-only and get no header, even when the fork edits them.
- The AGPL text is in `LICENSES/AGPL-3.0-only.txt` and the GPL text in `LICENSE`. README's License section explains
  the split, and its "Name and logo" section keeps the Leaf name and logo out of both licenses.

**Resources:**
- Strings go in Compose resources (`app/src/main/composeResources/values/strings.xml`) with area-prefixed snake_case
  keys (`side_pane_*`, `branch_context_menu_*`, `settings_*`). Some older UI still hardcodes strings; prefer resources
  for new UI.
- Icons are SVGs in `app/src/main/composeResources/drawable/`, loaded with `painterResource(Res.drawable.x)`, usually
  at 16 dp.

**Logging:** `printLog` / `printDebug` / `printError(TAG, ...)`, with `private const val TAG = "ClassName"`.

**Deleting files:** never use Kotlin's `File.deleteRecursively`, or a delete built on `File.walk`. They follow symbolic
links to folders, even when called on the link itself, and delete the files in the folder that a link points to,
possibly outside the repository. Use JGit's `FileUtils.delete(file, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)`,
which deletes a link as a link, and add `FileUtils.IGNORE_ERRORS` for a best-effort cleanup. Without that flag it throws
`IOException` at the first file it can't remove.

**Settings:**
- Chain: `AppConfig` (domain model) → `AppSettingsRepository` → `DataStoreAppSettingsRepository` →
  `AppSettingsService` (defaults) → `SettingsViewModel` / `SettingsDialog.kt`.
- Each row of `SettingsDialog` gives its title and subtitle (`FieldTitles`) `weight(1f)`, so a long subtitle wraps
  and the control keeps its room. Before, the subtitle took the whole row and hid the control (`SettingsRowsTest`).
- DataStore keeps them in `user_prefs.json` (`getPreferencesPath()`), written by `JsonPreferencesSerializer`. Each
  key stores its type and value. A damaged file is logged and replaced with the defaults.
- `TerminalPath` is the closest precedent for a "git executable path" setting.

**Tests:**
- Upstream's tests were removed in the 2.0 refactor (commit `36a92c60`). The fork's tests live in
  `data/src/test/kotlin`, `domain/src/test/kotlin`, `common/src/test/kotlin` and `app/src/test/kotlin`.
- JUnit 5 and MockK are already declared in `app`, `data` and `domain`. The `buildsrc` convention plugin enables the
  JUnit platform, so new test files need no build changes. `kotlinx-coroutines-test` is not a dependency; use
  `runBlocking`.
- **Declare `: Unit` on `fun test() = runBlocking { ... }`.** Otherwise the function returns its last expression,
  for example from `assertInstanceOf`, and JUnit silently skips non-void test methods. Compare each class's `tests=`
  count in `build/test-results` against its `@Test` count.
- MockK can't mock final classes such as `AppSettingsService` on JDK 25. Mock the interface (`AppSettingsRepository`)
  and wrap it in the real class.
- Tests that run shell scripts (fake git executables, `sh -c`) are annotated `@DisabledOnOs(OS.WINDOWS)`.
- Tests that touch git must create temp repos with `@TempDir` (`git init` / `git worktree add` there), never real
  repos.
- Tests that run git's `store` or `cache` helpers point `HOME`, `XDG_CONFIG_HOME` and `XDG_CACHE_HOME` at the temp
  folder (`HttpCredentialsProviderTest`), and stop the cache daemon with `git credential-cache exit`. A socket's path
  can't be longer than 104 bytes on macOS, so the socket goes directly under the temp folder.
- `HttpCredentialsProviderTest` checks what Leaf asks the user against git (`requestThatGitMakes`), by running
  `git credential fill` with a `GIT_ASKPASS` script that records git's prompts. If git asks for `Username`, the name
  wasn't known; if it asks for no `Password`, a helper gave it. Otherwise the password prompt shows the user name:
  `Password for 'https://bob@host': `.
  - `requestCredentials(serverAccepts = true)` then reports the credentials accepted, as the transport does.
  - `fetchThroughLeaf` runs a real JGit fetch through `HandleTransportGitAction` against `FakeGitServer`, a smart HTTP
    server on 127.0.0.1 (the JDK's `com.sun.net.httpserver`). The server wants Basic credentials, advertises a branch
    and fails the `git-upload-pack` POST, so the fetch fails after a request succeeded with the credentials. JGit's
    stateless fetch needs `multi_ack_detailed` in the advertisement. Git's `store` writes the host's port colon as
    `%3a`.
- `CredentialHelpersTest` checks the helper list against `git credential fill`. Each helper is
  `!leaf-helper <name>`, which records that it ran and what it read, and answers from `<name>.answer`. An `includeIf`
  pattern must use the real path (`canonicalPath`): git compares it with `/private/var/...` on macOS.
  `runGitCredentialApprove` feeds `git credential approve` what `fill` gave, and returns what each helper was given
  to store.
- `GpgProgramSignerTest` uses a fake gpg that records its arguments and input. `GpgProgramSignerRealGpgTest` runs only
  when gpg is installed: it creates an ED25519 key in a throwaway `GNUPGHOME` with `use-keyboxd`, and checks signatures
  with `git verify-commit` and `verify-tag`. That home is a short folder (`<temp>/g`), because gpg-agent's sockets go
  in it, and `gpgconf --kill all` stops its gpg-agent and keyboxd afterwards. Never point these tests at `~/.gnupg`.
- `CredentialUrlTest` uses the git CLI as the reference: `git credential fill` and `approve` with a helper that saves
  its input, and `credential.<key>.helper` with one that leaves a file. Git runs in the temp folder with
  `GIT_TERMINAL_PROMPT=0` and no askpass variables, so it fails rather than asking, and no repository's config applies.
- Shared helpers live in `data/src/test/kotlin/dev/app/leaf/data/git/TestGit.kt`:
  - `IsolatedSystemReader` keeps JGit away from the developer's `~/.gitconfig` and JGit config. Install it with
    `SystemReader.setInstance` and restore the original in `@AfterEach`.
  - `TestGitCli` runs the git CLI with global and system config ignored. JGit can't create linked worktrees, so use
    the CLI to set them up. `run(dir, env, args)` adds environment variables, for example `GIT_COMMITTER_DATE` to fix
    commit, tag and reflog dates (`GetRefDatesGitActionTest`). `runFailing` expects git to fail and returns its
    output in English, to compare Leaf with git's own refusals (`BranchWorktreesTest`).
  - `testGitCli(shellVariables, configuredPath)` builds a `GitCli`. Credential tests always give it the variables
    that keep git away from the developer's config, even when the helpers run without them.
  - `testJGit(shellVariables)` builds a `JGit` whose login shell environment is the given map. Hook tests use
    `File.writeExecutable`, and `createHookTool`, which makes a `leaf-hook-tool` command that is on no PATH
    (`PosixFsTest`, `GitCliTest`).
  - `File.symlinkTo(target)` creates a symbolic link. Tests that use it are `@DisabledOnOs(OS.WINDOWS)`, where links
    need Developer Mode or admin rights (`DeleteFileGitActionTest`, `DeleteSubmoduleGitActionTest`).
  - `WindowsFsTest` runs `WindowsFs` on macOS and Linux, with `/bin/sh` standing in for Git Bash
    (`GitBash("/bin/sh", quoteArgument = { it })`), through `Git.open(gitDir, fs)` rather than `JGit`.
  - See `OpenRepositoryGitActionTest` for the pattern.
- `HunkActionsTest` loads hunks through the real `FormatDiffGitAction`, as the diff pane gets them, and reads the
  index with `git cat-file blob :<path>`, so files in Latin-1 or with CRLF are compared byte for byte.
- Remote operation tests (`data/.../git/cli/askpass/`, `cli/remote/`):
  - `builtAskpassHelper()` finds the helper in `app/src/main/resources`. Tests that run it are skipped without it.
  - `answeringDialogs` plays the user: it answers each `CredentialsRequest` in turn and returns the dialogs shown.
  - `TestRemoteCommand` builds a `GitCliRemoteCommand` with git kept away from the developer's config.
  - `GitCliPushBranchGitActionTest` and `GitCliFetchPullTest` use bare repositories on disk, which another clone
    changes. `GitCliCloneTest` clones, and adds and updates submodules, from repositories on disk; its global config
    sets `protocol.file.allow=always` for the local submodules.
  - `GitCliHttpsTest` runs `git http-backend` as CGI behind Basic authentication in a JDK `HttpServer`. The clone
    tests cancel while git waits for a dialog (`awaitDialog`), which is when git has made the folder.
  - `GitCliSshTest` runs sshd with forced commands, and sets `core.sshCommand` (`-F /dev/null`, its own known_hosts,
    `IdentityAgent=none`, `-i <key>`), so that ssh never reads the developer's `~/.ssh`. A clone has no repository
    yet, so its test sets `core.sshCommand` in the test's global config. The forced command answers
    `git-lfs-authenticate` with a `FakeLfsServer`'s URL, as an LFS host does over SSH, and logs every command to
    `commands.log`, which compares the built-in LFS client's command with git-lfs's.
  - `SshProgramSignerRealSshKeygenTest` (skipped without ssh-keygen) makes keys per test, and points `SSH_AUTH_SOCK`
    at a socket that doesn't exist, or at an ssh-agent it starts in a short temp folder. A commit it signs is byte for
    byte what `git commit -S` makes from the same tree and dates (ED25519 signatures are deterministic).
  - `GitCliLfsTest` (skipped without git-lfs) clones, pulls and pushes LFS files through `FakeLfsServer`, a Git LFS
    server (batch API, basic transfer, optional Basic authentication) on 127.0.0.1. Leaf's built-in LFS is in the app
    module, so `LocalObjectsSmudge` stands in for its smudge filter and notes the objects it would have downloaded. A
    `git-lfs` wrapper on the PATH that Leaf's git gets logs each run; a PATH of `/usr/bin:/bin` leaves git-lfs out.
