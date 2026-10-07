# CLAUDE.md

Fork of [Gitnuro](https://github.com/JetpackDuba/Gitnuro) (Kotlin, Compose Desktop, JGit) that adds first-class git
worktree support for AI-agent workflows. The fork plan and working rules are in `docs/fork/PLAN.md`. Read them before
starting any work item. Findings about JGit and worktrees are in `docs/fork/architecture-notes.md`.

This file and everything under `docs/fork/` are fork-only. Keep them out of upstream PRs.

## Name

The fork ships as **Leaf**, and its code, build and storage use Leaf's own names.
- **Leaf:** `AppConstants.APP_NAME` and the user-facing strings. Also `projectName` in `app/build.gradle.kts`, which
  produces `Leaf.app`, `Leaf-*.dmg` and `Leaf-linux-*.jar`. Also the macOS bundle ID `io.github.zzhelev.leaf` and
  `leaf.iss`, which has its own AppId. Also `README.md`, which opens with a credit to Gitnuro, and `DEVELOPMENT.md`.
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
    state, shared by the worktrees). They are the same file in a repository's main worktree.

  Dev runs use `LeafDevConfig`, `leaf-dev` and `io.github.zzhelev.leaf-dev` instead (see Build gotchas).
- **Kept on purpose:** the credit ("based on Gitnuro" in the About text, and the README), and the Rust dependencies
  `libssh-rs` and `kotars`, which come from JetpackDuba's repositories.

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
- **Pushing:** the SSH key on this machine belongs to another GitHub account and can't push to `zzhelev/Leaf`. Push over
  HTTPS with the `gh` login (zzhelev, which has the `workflow` scope that changes to `.github/workflows/` need):

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
- Perl is needed to build the vendored OpenSSL (`/usr/bin/perl` is fine).
- git 2.54 (Apple Git) is at `/usr/bin/git`. The app itself does not use the git CLI yet.

## Commands (verified)

Run everything from the repo root, with `JAVA_HOME` set as above.

```bash
./gradlew build                          # full build + (currently empty) tests; ~5.5 min cold, Rust included
./gradlew test                           # all tests; only :data has tests so far
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
  `:domain` alone fails with unresolved `Session` and `Channel`, until `./gradlew :app:rustTasks` (a few minutes cold).
- **Rust failures do not fail the build.** `executePrintingData` sets `isIgnoreExitValue = true` and only prints
  `Code is N`. If something Rust-related looks stale, grep the Gradle output for `Code is` and `failed with exit value`.
- Generated, gitignored outputs:
  - `domain/src/main/kotlin/dev/app/leaf/autogenerated/` (uniffi Kotlin bindings, `leaf_rs.kt`)
  - `app/src/main/resources/libleaf_rs.dylib`
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
- `ui`: empty placeholder module (only `build.gradle.kts`).
- `rs`: Rust cdylib `leaf_rs`, exported via uniffi.
  - `FileWatcher` uses notify 8.
  - libssh `Session`/`Channel` provide SSH transport.
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
- `WindowsFs` finds hooks with JGit's `findHook` (`core.hooksPath`, the common git dir) and runs them with Git for
  Windows' `bin\bash.exe` (`GitBash`, which also quotes arguments for MSYS2), with JGit's folder and `GIT_*`
  variables. It must return `ProcessResult(exitCode, OK)`: JGit takes `ProcessResult(OK)` alone (exit code -1) as a
  failed hook.
- JGit passes commit-msg an empty path when the git dir is outside the working tree (linked worktrees, submodules):
  `CommitMsgHook` uses `Repository.stripWorkDir`. `PosixFs` and `WindowsFs` both override `runHookIfPresent` to pass
  the absolute path of `<git dir>/COMMIT_EDITMSG` instead (`hookArguments`), as the git CLI does. Another `FS` that
  runs hooks needs the same.
- Exceptions inside `provide` become `GenericError` (or come from an `errorHandle` mapper).
- Closing a tab closes and drops the cached `Git` of every repository that no remaining tab has open
  (`CleanRepositoriesResourcesUseCase` → `GitProviderService` → `JGit.cleanupExcept`). The keys to keep are the git
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
- Queries are `suspend` and use `useCaseExecutor.execute`.
- See `domain/.../UseCaseExecutor.kt`.

**Errors:**
- A custom `Either` (`domain/errors/Either.kt`, with `either {}`, `bind()`, `mapErr`) plus the sealed
  `AppError`/`GitError` hierarchy (`domain/errors/AppError.kt`).
- User-facing text: `app/.../ui/Errors.kt`.
- HIGH-severity failures open `ErrorDialog`; others become toasts.

**Concurrency:**
- There is no mutex around git operations. Foreground tasks only block UI input.
- There is no `CoroutineExceptionHandler`. An exception that escapes as a throw, rather than as `Either.Err`, kills
  its coroutine silently, with only a stderr stack trace. Return errors from git actions; don't throw them. Opening a
  linked worktree used to hit this.

**Git CLI (fork-only, `data/git/cli/`, `domain/gitcli/`):** use it for anything JGit can't do, starting with linked
worktrees.
- Call `GitCli.run(workingDirectory, args, timeout)`. It returns `Either<String /* stdout */, GitCliError>`.
- `GitCli` locates the binary through `GitExecutableLocator` (the configured path from settings, or auto-detection,
  cached). It adds the login shell's variables, then its own non-interactive, `LC_ALL=C` environment on top.
- Use porcelain or `-z` output and parse it in the data layer.
- Never shell out to git through `ShellManager`.

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
- Also used by HTTPS credential helpers (`HttpCredentialsProvider`). On macOS and Linux they run the way git runs them
  (`posixCredentialHelperCommand`): `!command` as a shell command, an absolute path as it is, and a name such as
  `osxkeychain` or `manager` as `git credential-<name>`, with the `git` on the shell's PATH. The command runs through
  `/bin/sh -c`, with the shell's variables passed to `IShellManager.runCommandProcess(environment = ...)`.
  - The shell is needed: Java looks a program name up on the PATH Leaf started with, not the PATH given to the
    process.
  - `git credential-<name>` is part of the helper protocol, not a git operation, so it doesn't go through `GitCli`.
  - `store` and `cache` run as `git credential-store` and `git credential-cache`, like any other name.
  - Windows is unchanged: the helper runs directly, `WindowsGitCredentialsManagerProvider` finds `manager`, and
    `store` and `cache` are refused. `NixGitCredentialsManagerProvider` is no longer called.
  - After a 401, JGit calls `reset` and then `get` again, up to 3 attempts. `HttpCredentialsProvider.reset` runs the
    helper's `erase` with the credentials it last gave, from the helper or typed by the user, like git's
    `credential_reject`. Git stores typed credentials only once the server accepts them; Leaf stores them at once, so
    `reset` first waits for that `store`, then for the `erase`.
  - Without a helper, `get` gives credentials from Leaf's in-memory cache (`CredentialsCacheRepository`, app singleton)
    or asks. `reset` removes the ones it took from the cache, but only while they are still the URL's cached ones,
    like `git credential-store erase`. Typed credentials are cached when the operation succeeds
    (`HandleTransportGitAction` → `cacheCredentialsIfNeeded`) and replace the URL's entry; `reset` drops them first if
    the server rejected them.
  - LFS (`ProvideLfsCredentialsGitAction`) reads that cache with `isLfs = true`, but nothing has cached LFS credentials
    since upstream's 7277d40c, so it asks after every 401.
  - What a helper reads comes from `credentialHelperInput` (fork-only `CredentialUrl.kt`), shared by `get`, `store`
    and `erase` on every OS. It matches git's `credential_from_url`, so Leaf and the git CLI find each other's
    credentials: `host` has the port when the URL has one (`example.com:8443`), and the `useHttpPath` path comes
    from `URIish.rawPath`, without its leading and trailing slashes, decoded like git's `url_decode`
    (`team/project.git`). Never use `URIish.path`, which JGit decodes its own way.
  - Like git, Leaf runs no helper for a URL with a newline, or for a value to send with a newline or a carriage return,
    and `get` gives no credentials. Such a value could add a second `host` line.
  - `credential.<url>.helper` and `.useHttpPath` apply as in git's urlmatch.c (`credentialConfigSubsections`):
    - The scheme, host and port must match, ignoring case and the default port, and `*` stands for one host label.
    - The key's path must be the remote's or a folder above it, and a user name in the key must be the remote's.
    - A key that isn't a URL, such as `example.com:8443`, is a partial URL whose parts must equal the remote's.

    Git applies every match in config order and tries each helper. Leaf runs one helper and takes each setting from
    the most specific match, then from `credential.*`.
- Not used by terminals, which `ShellManager` also starts, or by `GitExecutableLocator`, which already searches the
  Homebrew locations.

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

**ViewModels:**
- They extend `TabViewModel`. Child view models come from `tabViewModel(key) { component -> ... }`, with assisted
  factories when they take parameters.
- State is exposed as `StateFlow`s built with `combine(...)` and the context-parameter helpers `stateIn(initial)`
  (Lazily) and `toUiDataState()` (300 ms Loading debounce).
- Newer code uses explicit backing fields: `val x: StateFlow<T>` + `field = MutableStateFlow(...)`. Older code uses
  `_x` / `asStateFlow()`.
- Newer screens take `onAction(SealedAction)`.

**Data state:** each piece of repository data is a `Flow<DataState<T>>` (Loading/Loaded/Error) in
`InMemoryRepositoryDataRepository`. `RefreshDataUseCase` refreshes it per `DataToRefresh` value.

**Dialogs** are Navigation3 destinations:
- `sealed interface Screen` in `App.kt`, `entry<Screen.X>` in `ui/AppTab.kt`.
- Opened with `backStack.add(...)`, through an `onNavigate` lambda.
- `ui/dialogs/base/IconBasedDialog.kt` is the base for confirm-style dialogs.
- Every dialog renders through `ui/dialogs/base/MaterialDialog.kt`. `DialogSceneStrategy` uses Compose's common
  `Dialog`, which on desktop draws on the main window's canvas, not in an OS window, and only takes clicks inside its
  content's bounds. So `MaterialDialog` fills the space it's given and places the dialog itself, centered plus the drag
  offset. Its top 16 dp are a drag strip, so keep controls out of them; `Modifier.dialogDragHandle()` (from
  `MaterialDialogScope`) adds more handles, like the Settings title.
- Some destructive actions have no confirmation today; for example, delete branch is forced and immediate.

## Repository tabs

**Opening a tab:**
- Paths in: `App.start` restores tabs from prefs (`AppViewModel.loadPersistedTabs`) and handles the CLI argument;
  the Welcome page (picker or recent repos) calls `RepositoryTabViewModel.openRepository`; the menu's "open another
  repository" replaces the current tab.
- Tabs load lazily. `AppTab` calls `loadTab()` when a tab is first composed.
- Then `OpenRepositoryUseCase` → `OpenRepositoryGitAction`, which validates the path and returns the git dir. After
  that: `RepositorySelectionState.Open(gitDir)`, the working-tree path goes into recent repos, then
  `RefreshDataUseCase(ALL)` and `ObserveRepositoryToRefreshUseCase()`.

**State per tab:**
- `InMemoryRepositoryDataRepository`: status, local branches, current branch, tags, remotes, log, stashes,
  submodules, selection state, repo state, rebase state, author, persisted commit message.
- `InMemoryRepositoryStateRepository`: current task, completed tasks, last-operation timestamp, refresh trigger.
- UI state is mostly in one large `RepositoryOpenViewModel`: side panel, log, status, diff.

**Identity and persistence:**
- Tabs are identified by object, not by path, and nothing focuses an existing tab for the same path.
- Persisted keys in the prefs node (`LeafConfig`, see Name): `latestRepositoriesTabsOpened` (JSON),
  `latestRepositoryTabSelected`, and
  `lastOpenedRepositoriesList`.
- What is saved: open tabs as their git dir (`Open.path`), and tabs that haven't loaded yet as the path they were
  created with (`RepositoryTabViewModel.initialPath`). Tabs on the welcome page (`None`) are skipped.
- Never save `repositoryPath.value`. It is started lazily and stays `null` until something collects it, which tabs
  scrolled out of the tab bar never do. A `null` in the saved list makes `loadPersistedTabs` throw at startup.

## Sidebar and branch list

**Side panel** (`app/.../ui/SidePanel.kt`): a filter field plus one `LazyColumn` with a `LazyListScope` extension per
section: `localBranches`, `remotes`, `tags`, `stashes`, `submodules`.

**Section state:** lives in `RepositoryOpenViewModel` as one `isExpandedX` flow plus one combined `xState` flow per
section. The state classes are in `viewmodels/sidepanel/SidePaneStates.kt`. `SidePanelChildViewModel` is unused.

**Rows:**
- `ui/components/SideMenuEntry.kt` (`SideMenuHeader`) and `SideMenuSubentry.kt` (icon, text, `additionalInfo` slot,
  `onDoubleClick`).
- Tooltips: `ui/components/tooltip/DelayedTooltip.kt` and `InstantTooltip.kt`.
- Context menus: `ui/context_menu/*ContextMenu.kt`. `branchContextMenuItems` is shared with the log's `BranchChip`.

**Branches:**
- Model: `domain/models/Branch.kt` (`hash`, full ref `name`, `isLocal`). It has no tracking or ahead/behind fields.
- Loaded by `GetBranchesGitAction` (`git.branchList()`) via `RefreshDataUseCase.refreshBranches`.
- Double-clicking a local branch checks it out immediately, with no guard.

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

## Refresh

**Rust** (`rs/src/lib.rs`):
- `FileWatcher` on notify 8. `watch()` is a **blocking** loop that batches Create/Modify/Remove events and flushes
  them with a ~500 ms throttle.
- It is called from a `callbackFlow` in `data/.../git/FileChangesWatcher.kt`, so it occupies a coroutine thread per
  tab.
- `add_watch` error codes are ignored.

**`domain/.../usecases/ObserveRepositoryToRefreshUseCase.kt`:**
- What it watches:
  - the working-tree root and every non-ignored subdirectory, each non-recursive;
  - the git dir, non-recursive;
  - `<gitdir>/refs` and `<gitdir>/modules`, recursive.
- What a change triggers: under the git dir, `DataToRefresh.ALL`; otherwise `STATUS + LOG + REPO_STATE`.
- Events are dropped (not deferred) while a task runs and for 1.5 s after one.

**No polling and no refresh on window focus.** Manual refresh is F5 / Ctrl+R, which becomes Cmd+R on macOS
(`keybindings/Keybinding.kt`).

**Linked worktree gap:** the git dir is `<common>/.git/worktrees/<name>`, so `<gitdir>/refs` does not exist, and the
common `refs/` and `packed-refs` are not watched.

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

**Settings:**
- Chain: `AppConfig` (domain model) → `AppSettingsRepository` → `DataStoreAppSettingsRepository` →
  `AppSettingsService` (defaults) → `SettingsViewModel` / `SettingsDialog.kt`.
- DataStore keeps them in `user_prefs.json` (`getPreferencesPath()`), written by `JsonPreferencesSerializer`. Each
  key stores its type and value. A damaged file is logged and replaced with the defaults.
- `TerminalPath` is the closest precedent for a "git executable path" setting.

**Tests:**
- Upstream's tests were removed in the 2.0 refactor (commit `36a92c60`). The fork's tests live in
  `data/src/test/kotlin` and `domain/src/test/kotlin`.
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
- `CredentialUrlTest` uses the git CLI as the reference: `git credential fill` and `approve` with a helper that saves
  its input, and `credential.<key>.helper` with one that leaves a file. Git runs in the temp folder with
  `GIT_TERMINAL_PROMPT=0` and no askpass variables, so it fails rather than asking, and no repository's config applies.
- Shared helpers live in `data/src/test/kotlin/dev/app/leaf/data/git/TestGit.kt`:
  - `IsolatedSystemReader` keeps JGit away from the developer's `~/.gitconfig` and JGit config. Install it with
    `SystemReader.setInstance` and restore the original in `@AfterEach`.
  - `TestGitCli` runs the git CLI with global and system config ignored. JGit can't create linked worktrees, so use
    the CLI to set them up. `run(dir, env, args)` adds environment variables, for example `GIT_COMMITTER_DATE` to fix
    commit, tag and reflog dates (`GetRefDatesGitActionTest`).
  - `testJGit(shellVariables)` builds a `JGit` whose login shell environment is the given map. Hook tests use
    `File.writeExecutable`, and `createHookTool`, which makes a `leaf-hook-tool` command that is on no PATH
    (`PosixFsTest`, `GitCliTest`).
  - `WindowsFsTest` runs `WindowsFs` on macOS and Linux, with `/bin/sh` standing in for Git Bash
    (`GitBash("/bin/sh", quoteArgument = { it })`), through `Git.open(gitDir, fs)` rather than `JGit`.
  - See `OpenRepositoryGitActionTest` for the pattern.
