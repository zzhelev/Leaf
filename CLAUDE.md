# CLAUDE.md

Fork of [Gitnuro](https://github.com/JetpackDuba/Gitnuro) (Kotlin, Compose Desktop, JGit) that adds first-class git
worktree support for AI-agent workflows. The fork plan and working rules are in `docs/fork/PLAN.md`. Read them before
starting any work item. Findings about JGit and worktrees are in `docs/fork/architecture-notes.md`.

This file and everything under `docs/fork/` are fork-only. Leaf sends no pull requests upstream; it merges Gitnuro's
changes in (see `docs/fork/PLAN.md`).

## Name

The fork ships as **Leaf**, and its code, build and storage use Leaf's own names.
- **Leaf:** `AppConstants.APP_NAME` and the user-facing strings. Also `projectName` in `app/build.gradle.kts`, which
  produces `Leaf.app`, `Leaf-*.dmg` and `Leaf-linux-*.jar`. Also the macOS bundle ID `io.github.zzhelev.leaf` and
  `leaf.iss`, which has its own AppId. Also `README.md` and `DEVELOPMENT.md`.
- **Code and build:** the Kotlin package `dev.app.leaf` (also the Gradle `group`, so Compose's `Res` package is
  `dev.app.leaf.app.generated.resources`), the root Gradle project `Leaf`, the Rust crate `leaf-rs` (which builds
  `leaf-askpass`) and `LeafException`.
- **Links:** the Welcome page's "Source code" and "Report a bug", the bottom bar's Releases link, `VERSION_CHECK_URL`
  (Leaf's `latest.json` on the `main` branch) and the publishing target in `.github/workflows/release.yml` all
  point at `zzhelev/Leaf`. The release workflow still names upstream's self-hosted runners.
- **Own storage:** every storage name comes from the fork-only `AppStorage` (`common/.../common/storage/`), so Leaf
  never reads or writes an installed Gitnuro's data. On macOS, a packaged Leaf uses:
  - the `java.util.prefs` node `LeafConfig` (tabs, recent repos, pane widths);
  - `~/Library/Application Support/leaf/` (the settings file `user_prefs.json`, and `tmp/`);
  - `~/Library/Logs/io.github.zzhelev.leaf/leaf.log`;
  - the per-repository file `<git dir>/leaf` (sign-off, per worktree) and `<common git dir>/leaf` (side panel folder
    state, the worktrees' base branch, and where new worktrees go, shared by the worktrees). They are the same file in
    a repository's main worktree;
  - the per-worktree file `<git dir>/LEAF_COMMIT_DRAFT`, the commit field's draft, and during a rebase
    `<git dir>/rebase-merge/leaf-amend-message-<commit>`, its message for an edit stop (see The plain draft).

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

  After resolving, `git ls-files '*jetpackduba*'` must print nothing, and new files need `dev.app.leaf` packages.
  Also read what git merged without a conflict in files the fork changed: on 2026-10-16 it merged upstream's fix of
  the Actions list into Leaf's, which gave two actions a second dismiss (see Dialogs). The syncs of 2026-10-07 and
  2026-10-16 are described in `docs/fork/CHANGELOG.md`.
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
- **Rust 1.99.0.** `rs/rust-toolchain.toml` pins it, with the `minimal` profile (no docs, clippy or rustfmt), and
  rustup installs it automatically the first time cargo runs in `rs/`. `cargo` must be on `PATH` (`~/.cargo/bin`).
  It was `nightly` until 2026-10-15, though nothing needed a nightly feature. Edition 2024 needs 1.85 or newer, so
  this machine's default stable (1.75) can't build it. `rs/Cargo.lock` is tracked, so every build gets the same
  dependencies (since 2026-10-15, none: `leaf-askpass` uses the standard library only).
- git 2.54 (Apple Git) is at `/usr/bin/git`. The app runs it through `GitCli` (see Git operations).

## Commands (verified)

Run everything from the repo root, with `JAVA_HOME` set as above.

```bash
./gradlew build                          # full build + tests; ~5 min (a cold Rust build adds ~2 s)
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
  jar's manifest sets `Enable-Native-Access: ALL-UNNAMED`. Without them, JDK 25 warns when skiko, Compose's renderer,
  loads its native library, and a later JDK will refuse. Leaf has no native library of its own since 2026-10-15, and
  no JNA.
- **Settings are JSON, not protobuf.** DataStore Preferences' own file format goes through its bundled protobuf,
  which calls `sun.misc.Unsafe`, and JDK 25 warns about it. DataStore 1.2.1 and 1.3.0-alpha11 both do. So
  `DatastoreModule` stores the settings with the fork-only `JsonPreferencesSerializer`.
  `ProtobufPreferencesMigration` moves an old `user_prefs.preferences_pb` over once, then renames it to
  `.migrated`; the warning shows only on that launch. `RemovedSettingsMigration` runs after it and deletes the keys
  of removed settings (`REMOVED_SETTINGS`).
- **Linux `.deb`:** `./gradlew :app:packageDeb` builds it on Linux only, and needs `fakeroot` and `dpkg-deb`. It
  installs Leaf to `/opt/leaf` with an entry in the applications menu.
  - The release workflow builds it natively on Ubuntu 22.04 for each CPU: jpackage can't cross-build, the package
    depends on the build system's library package names, and `leaf-askpass` needs its glibc or newer. So it
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

- **Rust tasks (fork-only, `app/build.gradle.kts`, group `rust`):**
  - `cargoBuild` runs `cargo build --release` in `rs/` (`cross build --target=…` with `-PuseCross=true` on Linux), and
    `copyRustBuild` copies `leaf-askpass` into the app's resources. `processResources` depends on it, so every app
    build, run or package does, and so does `:data:test`, whose tests run `leaf-askpass`. `rustTasks` runs both.
  - They declare their inputs (the `.rs` files, `Cargo.toml`, `Cargo.lock` and `rust-toolchain.toml`, plus the release
    and target options) and their outputs, so Gradle skips them while those don't change. A cargo failure fails the
    build. `:domain` builds without cargo.
  - Until 2026-10-15, cargo ran in `rustTasks`'s configuration block whenever the task was in the graph, three times
    per build, and a failure only printed `Code is N`.
  - Leftovers of the Rust file watcher in older checkouts, both gitignored, go by themselves: `:domain`'s
    `removeRustBindings` deletes `domain/src/main/kotlin/dev/app/leaf/autogenerated/` (uniffi's bindings, which no
    longer compile) before compiling, and `copyRustBuild` deletes `libleaf_rs.*` from the app's resources.
- Generated, gitignored outputs:
  - `app/src/main/resources/leaf-askpass` (`.exe` on Windows), which `copyRustBuild` copies there
  - `rs/target/`
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
  - Its build script builds `leaf-askpass` with cargo, and holds the packaging config.
- `common`: tiny shared utilities: the `@TabScope` annotation, logging (`printLog`/`printError(TAG, ...)`), OS
  detection, `combine` overloads up to 30 flows (`flows/FlowExtensions.kt`), and Closeable helpers. Also the fork-only
  `storage/AppStorage.kt` with every storage name (see Name).
- `domain`: use cases, git-action interfaces, models, the error types (`Either`, `AppError`), repository interfaces
  (tab/app state, settings), `UseCaseExecutor`, `TabCoroutineScope`, `ShellManager` and credentials.
  - Not pure: it depends on JGit and Compose, and many interfaces still leak JGit types.
- `data`: JGit implementations of the git actions (`data/git/**`), the JGit instance cache (`data/git/JGit.kt`), the
  file watcher (`data/git/FileChangesWatcher.kt`), in-memory tab repositories, DataStore-backed settings, and
  JGit→domain mappers.
  - The fork-only `network/HttpClients.kt` builds the Ktor clients for LFS and the update check. They check TLS
    certificates against the JVM's trust store. An LFS request skips the check only when `http.sslVerify` is false
    for its URL (`Config.isSslVerify`, through JGit's `HttpConfig`), as git-lfs does.
- `ui`: empty placeholder module (only `build.gradle.kts`).
- `rs`: the binary `leaf-askpass` (`rs/leaf-askpass.rs`, standard library only), the askpass program and credential
  helper of the git commands Leaf runs (see Remote operations). It ships in the app's jar, and `AskpassHelper`
  extracts it to `<app data>/tmp/askpass-<hash>/` the first time it's needed.
  - No library: the file watcher, the cdylib's last export, moved to the JDK's `WatchService` on 2026-10-15 (see
    Refresh), and uniffi, its bindings and JNA went with it.
  - No SSH: OpenSSH's programs do all of it, run by git, by the built-in LFS client and by `SshProgramSigner` (see
    Remote operations, and Commit and tag signing). libssh-rs, with its vendored libssh and OpenSSL, was removed on
    2026-10-09 (stage 5 of `docs/fork/remote-operations.md`).
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
- **The files that `includeIf` names, under JGit's config (fork-only `IncludedConfigRepository`,
  `config/IncludedConfig.kt`):** JGit's parser expands only `[include]`, so what a file that `includeIf` names sets (a
  work identity for `~/work/`, signing, `core.hooksPath`, `core.autocrlf`, …) never reached JGit (Gitnuro#186).
  `JGit.open` opens an `IncludedConfigRepository`, whose config is the repository's own file on a layer of what only
  git reads, on the user's config. JGit's own commits (the interactive rebase, and pull's merge and rebase, merge,
  cherry-pick, revert and rebase when git can't run them), its reflog entries, its stashes and its other settings then
  see what git sees.
  - `GitIncludedConfig` runs `git --git-dir=<git dir> config --list -z --show-origin --show-scope` and keeps the
    entries of the files reached through `includeIf` (`includedConfig`). git lists an included file's entries right
    after the include, so the next entry from the file that the path names (`~/` in git's HOME, a relative path next
    to the including file, both as git prints them, without resolving links) starts them. A key that the user's or the system's
    config sets again after such a file also gets those later values, as git takes the last one.
  - `provide`, `provideOptional` and `provideOnce` read it again (one at a time, `includedConfigLock`) when a file
    that it depends on changed (JGit's `FileSnapshot`): every file git read, every include's target even when missing,
    the repository's HEAD (`onbranch:`), its config and `config.worktree`, and the user's `~/.gitconfig` and XDG
    config. Otherwise they only check those files.
  - JGit's `FileRepository` puts its config on the user's config in its constructor, and reads it there, before a
    subclass is set up: `getConfig()` returns JGit's own until the subclass's exists. After that, JGit uses its
    private config only to load it and to convert the ref storage, which Leaf never does; the object database keeps
    it for pack settings.
  - Saving the repository's config writes only the repository's own entries. Without a usable git, the layer is
    empty, and a repository that no `includeIf` applies to reads as before.
  - A key that the repository's own config sets stays the repository's in JGit, even where that file has an
    `includeIf` after it, which git reads later. JGit reads no `author.*` and `committer.*`, so its merges and reverts
    take `user.*` for both.
  - Repositories that Leaf opens elsewhere (`SubmoduleWalk`, `RenameBranchGitAction.moveHead`,
    `OpenRepositoryGitAction`'s checks) are plain JGit repositories.
  - Tests: `IncludedConfigTest` checks the entries against git's own list, `IncludedConfigRepositoryTest` JGit's
    merge, revert, cherry-pick, rebase, interactive rebase and pull, a snapshot stash, a signed merge, a hook and the
    reflog against git.
- **Hooks are where git finds them (fork-only `GitHooksDirectory`):** `PosixFs` and `WindowsFs` override `findHook`,
  which runs `git --git-dir=<git dir> rev-parse --path-format=absolute --git-path hooks` in the working tree (the git
  dir when bare). That's git's answer: `core.hooksPath` from a file that `includeIf` names (Gitnuro#186), `~/`
  expanded, a relative path from the working tree's root, and the common git dir's `hooks` in a linked worktree. JGit
  took `core.hooksPath` from its own config, as a plain path. A hook that is missing, or not executable where the file
  system can tell, is skipped (`hookIn`), as JGit and git skip it. Without a usable git, JGit's own `findHook` looks.
  - git runs once per hook that JGit looks for, three times per commit (pre-commit, commit-msg, post-commit), about
    13 ms each. `JGit` gets the lookup injected and gives it to `PosixFs`; Dagger gives `WindowsFs` its own.
  - Tests: `GitHooksDirectoryTest` compares each case with `git commit` running the same hooks; `WindowsFsTest`.
- `WindowsFs` runs hooks with Git for Windows' `bin\bash.exe` (`GitBash`, which also quotes arguments for MSYS2),
  with JGit's folder and `GIT_*` variables. It must return `ProcessResult(exitCode, OK)`: JGit takes
  `ProcessResult(OK)` alone (exit code -1) as a failed hook. The install is `findGitForWindows`'s: the first one on
  PATH, or in the default folders, with Git Bash.
- JGit passes commit-msg an empty path when the git dir is outside the working tree (linked worktrees, submodules):
  `CommitMsgHook` uses `Repository.stripWorkDir`. `PosixFs` and `WindowsFs` both override `runHookIfPresent` to pass
  the absolute path of `<git dir>/COMMIT_EDITMSG` instead (`hookArguments`), as the git CLI does. Another `FS` that
  runs hooks needs the same.
- Exceptions inside `provide` become `GenericError` (or come from an `errorHandle` mapper). `GpgSigningException`,
  `SshSigningException` and `SshNeedsGitException` are looked for in the cause chain first, as JGit wraps them, then
  (fork-only) an index that JGit can't read (`UnsupportedIndexError`) and a missing object (`MissingObjectError`): see
  Index formats that JGit can't read, and Partial clones.
- Closing a tab, or replacing it ("Open another repository"), closes and drops the cached `Git` of every repository
  that no remaining tab has open (`CleanRepositoriesResourcesUseCase` → `GitProviderService` → `JGit.cleanupExcept`). The keys to keep are the git
  dirs from `RepositorySelectionState.Open.path`, never `<working tree>/.git`: linked worktrees and submodules have no
  such folder. The cache is a `ConcurrentHashMap`, because tabs add to it while another tab closes.
- `provide` does not switch dispatchers. Many actions call `withContext(Dispatchers.IO)` themselves.
- JGit auto-gc is turned off by `NoAutoGcSystemReader` (`data/git/`), which `main.kt` installs before Dagger is
  created. JGit's gc is not worktree-aware. Never run JGit gc; leave it to the git CLI.
- **Config values that JGit lacks (fork-only `GitCompatibleConfigGetter`, `data/git/`):** JGit's enums have no
  `merge.conflictStyle=zdiff3`, and no `diff.algorithm` of `patience`, `minimal` or `default`, and its `getEnum` throws
  "Invalid value" for them. It reads both keys in every merge (`ResolveMerger`, `MergeConfig`) and `diff.algorithm` in
  every diff (`DiffFormatter`), before any setter on a command applies.
  - `main.kt` installs the getter with `Config.setTypedConfigGetter` (`readGitOnlyConfigValues`), which every
    `Config.getEnum` goes through. It reads `zdiff3` as `diff3`, `default` and `minimal` as `myers`, and `patience` as
    `histogram`, as git means them (git's merges use `diff.algorithm` too). Other values go to JGit's reader, so
    values that git refuses still fail. It writes no config.
  - Tests that run JGit's merges or diffs with these values call `readGitOnlyConfigValues()` and put JGit's reader
    back with `Config.setTypedConfigGetter(null)`.
  - JGit's diff3 leaves out as many lines of a conflict's base as it leaves out of the sides, where git's zdiff3 shows
    them all.

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
  give `true`, and continuing or skipping a rebase that stopped again at a later commit's conflicts). It's recorded
  as `CompletedTask.Success(stoppedAtConflicts = true)` (`addCompletedTaskWithConflicts`), and its toast warns with
  `conflictsTitle()` ("Merge stopped at conflicts, fix them to continue").
  - Fork-only: a toast stays as long as its text takes to read (`notificationDuration`: 2 s plus 60 ms a character,
    4 to 12 s, and at least 6 s for a warning or an error), and while the pointer is over it. Upstream's went after
    2 s, before a longer message could be read.
  - Fork-only: a task that succeeded but left the user's set-aside changes in the stash list also shows a dialog,
    `AutostashNoticeDialog` (see Changes that git sets aside).
  - The toast is gone after a few seconds, so `OperationInProgressBanner` (fork-only, `ui/status/`) shows an amber strip under
    the toolbar while the repository is in the middle of a merge, rebase, cherry-pick or revert, with the files that
    still have conflicts and an Abort button right after the text (the text is only as wide as it needs, so the
    button doesn't drift to the far right of a wide window). It follows `StatusState` (`operationInProgress`), not the task, so it
    shows for conflicts from a terminal or an agent too, and stays until the operation is finished or aborted. None
    while the interactive rebase's list is open. Its Abort and the status pane's go through
    `abortOperationInProgress`, which asks first (`ConfirmableAction.AbortOperation`).
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
- **A line's buttons and menu (fork-only, `ui/diff/DiffLineActions.kt`):** the pointer over a changed line shows
  Stage and Discard in Unstaged, Unstage in Staged, and its right-click menu has the same items (`lineActions`).
  Upstream had only the button to stage or unstage, and Discard in the menu. Unstaged's text starts 40 dp after the
  line numbers to make room for two buttons (`lineTextStart`, through `DiffText`'s `startPadding`). Like the hunk
  buttons, neither shows while a merge, rebase, cherry-pick or revert goes on (`isRepositoryInSafeState`).

**Diff of a conflicted file (fork-only, `data/git/diff/UnmergedDiffEntry.kt`):** the unstaged diff of a file with
conflicts compares the working file with ours (stage 2). For a file that both sides changed, that's what
`git diff --ours` shows.
- JGit's diff of the index against the working tree gives such a file one entry per stage, and the first compares
  stage 1, the common ancestor. So `GetDiffEntryFromStatusEntryGitAction` asks `unmergedDiffEntry` first. That runs
  `git.diff()` against an in-core index holding only ours, at stage 0. It looks at the index, not at the status
  entry, so a stale selection is handled too.
- Without ours (deleted by us), the whole file is added, where git shows nothing. A working file that is ours as it
  is gets an `UnchangedDiffEntry`, so the pane shows the file instead of closing. Its working tree id is zero: JGit's
  `DiffFormatter` throws while parsing the header of an entry whose two ids are the same.
- The rest of the diff code is unchanged. It reads the old side by object id and the new side from the working tree
  by path, whatever index it scanned.
- Conflicted files get no hunk or line actions (see above).

**Ignoring untracked files (fork-only, `domain/ignore/IgnoreRules.kt`, `data/git/workspace/IgnoreFiles.kt`,
`ui/dialogs/IgnoreDialog.kt`, Gitnuro#175):** "Ignore…" in the Unstaged menus of an untracked file (after "Delete file"),
of a selection (`Ignore N files…`) and of a folder row (`Ignore folder…`) opens `IgnoreDialog` (`Screen.Ignore`).
- **Who offers it:** `ignoreRequestFor` (the untracked files, `StatusType.ADDED`, of an Unstaged selection) and
  `ignoreRequestForFolder` (a folder row that shows one), both in `IgnoreRules.kt` and used by `StatusPane`. A tracked
  file isn't offered: ignoring it changes nothing until `git rm --cached`. Staged has none.
- **The dialog** (`IgnoreViewModel`, `IgnoreState`): what to ignore (`ignoreOptions`: the files, their folders, their
  extensions; or a custom pattern, one rule per line, written as typed, starting from the first option's rules), where
  (`IgnorePlace.GITIGNORE`: `<working tree>/.gitignore`, `EXCLUDE`: `<common git dir>/info/exclude`, shared by the
  worktrees, or `GLOBAL`: the user's global ignore list), and the lines it adds (`rulesToAdd` leaves out those the file
  has). `GetIgnoreContextUseCase` reads both
  files' lines and the place used last (`ignore.place` in `<common git dir>/leaf`, `IgnorePlaceConfig`) when it opens;
  `AddIgnoreRulesUseCase` (`TaskType.AddIgnoreRules`) writes and refreshes the status. The place used last is never
  `GLOBAL`: one click from a hurried Add shouldn't write a rule for every repository. The file watcher would see a
  `.gitignore` change too, but drops refreshes while a task runs and for 1.5 s after one, and doesn't see `info/exclude`.
- **Rules** (`ignoreFileRule`, `ignoreFolderRule`, `ignoreExtensionRule`): `/path/file`, `/path/folder/`, `*.ext`.
  Backslashes go before `\ * ? [` and before the white space that ends a name. **JGit reads pattern lines differently
  from git:** git drops only trailing spaces unless escaped, JGit all trailing white space, and also before the slash of
  a folder rule. So a name ending in a tab was ignored by git and still listed by Leaf. Every end of a name is escaped.
  A name with a line break has no rule (a pattern line can't hold it).
- **Writing** (`IgnoreFiles.append`): bytes appended, so the encoding, a byte order mark and the line endings stay. The
  file's first line ending is used (LF for a new or one-line file), a last line without one gets it first, and a line
  that is there already (byte for byte without `\r`) isn't added. Nothing is written through a symbolic link, which git
  2.32 and later ignores as `.gitignore` too, or to a folder: `IgnoreError.FileNotWritable`, checked again at the open
  (`NOFOLLOW_LINKS`). Only the three files of the places are ever written (`IgnoreTargets` finds them).
- **The global list** (`GlobalIgnoreFile`): `core.excludesFile` from `git config --global --type=path` (`~`, `includeIf`),
  else `$XDG_CONFIG_HOME/git/ignore` or `~/.config/git/ignore` from the login shell's environment. Never from the
  repository's own config, which could point Leaf at any file: when `git config` without `--global` says another path,
  the place is refused (`GlobalListOverridden`). A link to a file is written through (dotfile managers), a link to
  nothing, a folder and a relative path aren't.
- **The watcher:** a folder that the rule ignores stops being watched once the refreshed status lists it, and so does
  one that an editor's edit of a pattern file ignores (see Refresh, `WorkingTreeWatches`).
- Tests: `IgnoreRulesTest` (domain), `AddIgnoreRulesGitActionTest` (every rule, 120 random names, the `.gitignore`
  states and the places, against `git ls-files --others --exclude-standard` and Leaf's JGit status, which must agree),
  `AddGlobalIgnoreRulesGitActionTest` (the global list), `IgnoringFilesTest` (a `TestTab`), `IgnoreContextMenuTest`,
  `IgnoreDialogTest`.

**Resolving conflicts (fork-only, `data/git/conflicts/`, models in `domain/models/FileConflict.kt`):** "Open in merge
tool" runs `git mergetool`, and "Use ours" / "Use theirs" take one side. Findings from git 2.54 are in the tests.
- `StatusEntry.conflict` (a `FileConflict`) says what ours (stage 2) and theirs (stage 3) have at a conflicted path: a
  file, a symlink, a submodule, or nothing where that side deleted it. `GetStatusGitAction` reads it from the index
  (`fileConflicts` in `IndexConflicts.kt`). git records a file against a symlink or a submodule at two paths
  (`both`, `both~feature`), each with one side.
- **Merge tool (`OpenMergeToolGitAction`):** `git --literal-pathspecs mergetool --tool=<tool> --no-prompt -- <path>` in
  the tab's working tree, with no timeout and stdin closed. The tool is `merge.guitool`, else `merge.tool`, as
  `--gui` picks it, so the user's `mergetool.<tool>.cmd`, `.path` and `.trustExitCode` apply.
  - Leaf names the tool itself: without one, git guesses and asks "Hit return to start" even with `--no-prompt`, and an
    unknown `merge.tool` sends it there too. None set is `ConflictError.NoMergeTool`, and git isn't run.
  - Only a conflict between two files goes to git (`mergeToolRefusal`). For a deletion, a symlink or a submodule, git
    asks which side to keep, also with `--no-prompt`. Names with `*`, `?` or `[` are refused too: git-mergetool's
    `set -- $files` lets the shell expand them, and `[ab].txt` merged `a.txt`, whatever `--literal-pathspecs` says.
  - When git stops at a question, or the tool isn't installed, it exits without removing its `_BASE_`, `_LOCAL_`,
    `_REMOTE_` and `_BACKUP_` copies. So Leaf adds `-c mergetool.writeToTemp=true`, which puts them in a temporary
    folder, and `-c mergetool.keepBackup=false`, so that no `<file>.orig` shows in Unstaged. Each only when the user
    hasn't set it (`MergeToolConfig`).
  - "Was the merge successful?" (an untrusted exit code and a file that isn't newer than its backup, to the second)
    reads the end of input: git puts the file back, exits 1, and Leaf reports `ConflictError.NotResolved` with what git
    and the tool printed. So does a trusted exit code other than 0.
  - A trusted exit code of 0 stages the file whatever it holds. `git diff --cached --check` then looks for
    `leftover conflict marker`, and when it finds one, `git update-index --unresolve` puts the conflict back from the
    index's resolve-undo record, leaving the file as the tool left it, partial work included (`git checkout -m` would
    rewrite it). That's `ConflictError.MarkersLeft`, in `ErrorDialog`.
  - A `mergetool.<tool>.cmd` that starts the tool with `open -a` (macOS) runs it from `/`, so git's relative paths
    (`$MERGED`, and `$LOCAL` without `writeToTemp`) point nowhere, and `open -W` exits with 0 whether the file was
    saved or not. With `trustExitCode`, that staged the file with its markers before the check above. git's own
    support for a tool runs its program from the working tree: Homebrew's `meld` wrapper execs Meld's binary.
  - The processing screen shows `<tool>: <path>` with Cancel, which kills git and what it started. A tool that detaches
    from its starter, as `open -W -a Meld` does, stays open.
- **Ours and theirs (`ResolveConflictGitAction`):** for a side that has a file, `git checkout --ours|--theirs` then
  `git add` (with `keepWorkingFile`, the resolver's Keep, only `git add` where the working file is there and isn't a
  folder); for one that deleted it, `git rm`; for a submodule, `git update-index --cacheinfo` with that side's commit
  (`git add` would stage whatever the submodule has checked out). All with `--literal-pathspecs`. A path that has no
  conflicts any more stops it before anything runs (`ConflictError.NotConflicted`).
- **Which side is which (`GetConflictSidesGitAction`, `ConflictSides`):** read from what git and JGit leave in the
  worktree's git dir. Merge: HEAD's branch, and `MERGE_HEAD`'s (a local branch at it, then a remote one, then the name
  in git's merge message). Rebase: the branch at `onto`, and the commit replayed (`REBASE_HEAD`, or JGit's
  `rebase-merge/stopped-sha`) with `head-name`'s branch. Cherry-pick and revert: HEAD's branch, and the commit. Anything
  else (a stash that didn't apply cleanly) has no theirs. `StatusViewModelExtender.conflictSides` reads them again with
  each status that has conflicts, as a rebase's next commit can stop at the same files.
  `ui/status/ConflictSideLabels.kt` words them: `main`, `main (onto)`, `a1b2c3d "Fix parser" from feature`,
  `the revert of …`.
- **UI:** a conflicted file's Unstaged menu starts with "Open in external merge tool" (only when `canOpenInMergeTool`),
  "Use ours" and "Use theirs"; a selection of conflicted files only gets the last two. The diff pane of a conflicted
  file has `ConflictBar` under its header: "Ours: main · Theirs: feature", then the same buttons, with the sides in
  their tooltips. The header itself had no room in a narrow pane. `OperationInProgressBanner` adds the same sides after
  its message while files have conflicts.
  - Ours and theirs ask first (`ConfirmableAction.UseConflictSide`): the other side's changes and the user's edits are
    lost, and a file that the chosen side deleted is deleted.
  - Discard leaves conflicted files alone (see Confirmations).
  - Their toast is "Conflict resolved". Markers left by the merge tool give `ErrorDialog` instead, which stays.
  - Double-click on a conflicted file and the banner have no conflict action of their own: the built-in resolver
    (`docs/fork/conflict-resolver.md`, another session's work) adds them, with a setting to pick it or the external
    tool.
- Tests: `OpenMergeToolGitActionTest` sets up a fake tool through `mergetool.<name>.cmd`, as users set up Meld, and
  `ConflictRepository.kt` makes each kind of conflict. git compares the file's time with its backup's to the second,
  so a fake tool with an untrusted exit code has to `touch -t` the file. git removes its copies once the tool exits.

**Built-in conflict resolver (fork-only, `data/git/conflicts/`, models in `domain/models/TextMerge.kt` and
`ConflictedFile.kt`, UI in `ui/conflicts/`):** steps 1 to 5 of `docs/fork/conflict-resolver.md`: the model, its git
actions, the views, Columns (ours | result | theirs) and Stacked (the sides on top of the result), editing the result
one part at a time, with one undo history, and a card per side for the conflicts that three panes can't show. Also
step 6: the size limit, from measurements (the doc's section 10), and the words that each side changed, marked inside
its lines, with colours while editing (section 11).
- `GetConflictedFileGitAction` loads a conflicted path as `ConflictedFile`: its stages (through `stagesOf`), its
  `FileConflict`, its `ConflictSides`, and a `ConflictContent` picked in `git mergetool`'s order:
  - `OtherPaths`: a stage alone (DD, AU, UA), as rename/rename leaves;
  - then `Submodule`, `Deleted` and `SymbolicLink`;
  - `Lfs`: a pointer of 1,024 bytes or less on ours or theirs. JGit's `LfsPointer` throws on an id that isn't hex,
    which counts as not a pointer;
  - `Binary`: the `merge` attribute unset or `binary`, or `RawText.isBinary` on each whole version, as JGit's merge
    reads them;
  - `TooLarge`: text over `MAX_RESOLVER_TEXT_SIZE` (10 MB), or whose merge took longer than
    `MAX_RESOLVER_COMPARE_TIME` (2 s), with a `TextLimit` that says which. Below 10 MB, a pick, Undo and switching
    layouts took under 100 ms. Dense changes, as in a lock file, make the diffs take time that grows with the square
    of the size: 16 s for a 10 MB lock file, and minutes past it;
  - then `Text`: the merge, and the working file's check.
- `textMerge` (`TextMergeChunks.kt`) merges with JGit's `MergeAlgorithm` and `diff.algorithm`, read as `ResolveMerger`
  reads it, through `GitCompatibleConfigGetter` (`mergeDiffAlgorithm`, histogram for a value that git refuses too), so
  the conflicts are the ones that Leaf's merges write.
  - JGit's chunks give their lines in one version only. So `mergeParts` walks the two diffs with `MergeAlgorithm`'s
    own comparisons (`<` for an edit alone, `>=` to join edits), and each part takes JGit's chunks, which must fit
    (`IllegalStateException` otherwise). Every `TextMerge.Chunk` has its lines in the base, ours and theirs.
  - JGit cuts a conflict's base by the lines alike at its start and end, which gives `[1,-1)` with an empty base: it's
    clamped. Myers can give two edits of one side that touch; such changes are joined.
  - The diffs are nearly all of the time a merge takes, so `KeptDiffs` keeps the two that JGit's merge computed for
    `mergeParts`, which used to compute them again.
  - With a `timeLimit`, the merge compares lines through `comparatorWithin`, which reads the clock every 1,024 lines
    compared and throws `ComparisonTookTooLong` past it. JGit's diffs can't be stopped otherwise, and compare lines
    all along, so nothing is left running.
  - `TextMergeChunksTest` checks 5,000 random merges per algorithm against JGit's formatter and its OURS and THEIRS
    strategies, and that keeping one side's changes gives that version back byte for byte.
- `resultBytes` (`ConflictResult.kt`) writes each line's own bytes and line ending, from `RawText`, and open conflicts
  with markers as git writes them (`ConflictResultTest` compares with `git merge-file`):
  - CRLF as git's `is_cr_needed` decides (JGit always writes LF), and a line ending added before a marker;
  - `diff3` style for `diff3` and `zdiff3`, and the `conflict-marker-size` attribute (`ConflictAttributes.kt`);
  - JGit's formatter can drop the file's last line ending; this doesn't.
- `scanConflictMarkers` (`ConflictMarkerScan.kt`) counts the working file's conflicts as `git rerere` does
  (`WorkingFileCheck`: `MATCHES`, `NO_MARKERS`, `DIFFERENT`, `MISSING`). git joins and splits conflicts where JGit
  doesn't, so a file nobody edited can count differently from the merge.
- `WriteConflictResultGitAction` writes in place, through a checkout's line ending conversion, with git's labels
  (`conflictLabels`: `HEAD`; the branch merged, or `<id> (<subject>)` and `parent of …`; git stash's `Updated
  upstream`). It writes nothing when the stages changed (`ConflictError.NotConflicted`, `StagesChanged`), when the
  working file changed since loading unless `overwriteChanges` (`WorkingFileChanged`), or when a folder or a symbolic
  link is in the file's place (`NotAFile`).
- Use cases: `GetConflictedFileUseCase`, `SaveConflictResolutionUseCase` (no refresh: the file watcher sees the file)
  and `MarkConflictResolvedUseCase` (refused with `ConflictsLeft` while a conflict is open, then stages with
  `StageEntryGitAction` and refreshes the status). They use `execute`, so the resolver shows their errors itself.
- Tests: `GetConflictedFileGitActionTest` and `WriteConflictResultGitActionTest` use conflicts that git's own merge,
  rebase, cherry-pick, revert and stash leave (`ConflictMerges.kt` writes each side's files), and Save with nothing
  resolved must give back git's file. `ResolvingConflictsTest` runs the use cases in a `TestTab`.

- **The view (step 2):** `ConflictResolver` takes the whole tab, as file history does: `RepositoryOpenViewModel`
  holds `showConflictResolver` and a `ConflictResolverViewModel` (`openConflictResolver`, made through a `Provider`),
  and `RepoContent` shows it in place of the main view. A worktree switch closes it (`closeNow`), after the worktree
  picker asks about changes that aren't saved (`whenConflictResolverLeft`, see Region editing).
  - Entry points: "Resolve…" first in a conflicted file's menu (`ConflictMenuActions.onResolve`), a "Resolve…" button
    in the diff's `ConflictBar`, "Resolve conflicts" before Abort on the banner (`resolveConflicts`, the first
    conflicted file), and double-click on a conflicted file. They go through `StatusAction.OpenInResolver` and
    `RepositoryOpenViewModel.onAction`, which also turns `EntryAction` on a conflicted file into `openConflict` instead
    of staging it with its markers.
  - `openConflict` follows Settings → Conflicts (`AppConfig.ConflictOpener`, `conflict_opener`, built-in by default):
    with the external tool picked, a file it can take (`canOpenInMergeTool`) in a repository with a tool
    (`GetMergeToolUseCase`, `readMergeToolConfig`) goes to `git mergetool`, anything else to the resolver.
  - The banner's button follows the same setting (`conflictsToOpen`, `domain/conflicts/ConflictsToOpen.kt`, Phase 3.6):
    with the external tool picked, every conflicted file that it can take goes to `git mergetool`, one after another
    in path order, in one task (`OpenMergeToolUseCase.inTurn`, `TaskType.ResolveConflictsInMergeTool`), as
    `git mergetool` goes through them in a terminal. The processing screen shows `<tool>: <path> (2 of 3)`.
    - `resolveInTurn` skips a file that has no conflicts any more, or that the tool can't take, as the status was
      older. Any other failure stops it, as `git mergetool` stops when it can't ask "Continue merging other unresolved
      paths?": closing the tool without saving is how the user stops. `NotResolved` and `MarkersLeft` then carry how
      many files it resolved before (`resolvedBefore`), which `ErrorDialog` says.
    - The files that the tool can't take wait for the next click, which opens them in the resolver once none of the
      files left can go to the tool.
    - Tests: `ResolveInTurnTest`, `ConflictsToOpenTest` (domain), and `ResolvingConflictsInMergeToolTest`, which runs
      the files that `conflictsToOpen` picks from a tab's status through a fake tool.
  - The rows come from `columnRows` (`domain/conflicts/ResolverColumns.kt`): one `ColumnRow` per line of a chunk's
    tallest column, fillers in the others. `arrowFor` and `taking` are the arrows: ▶ and ◀ take a side; once one is
    taken, the other side's adds after it. A gutter's buttons go one a row from the chunk's first, those left over on
    its last (`gutterButtonsOnRow`), and a resolved conflict's × (open it again) where the side taken had its arrow
    (`reopensFrom`): two stacked on one row made it a button taller than its line (found in the manual run,
    2026-10-15). A button is still 4 px over a line, as the first row of every change is.
  - The view model follows the status (`afterStatus`): it stays on the shown file once that stops being conflicted,
    until the user opens the next one (below), and says "All conflicts resolved" when none is left, or closes when
    nothing was resolved here. Leaving a file with changes that aren't saved, picks or lines typed, asks in a strip
    (`ResolverPrompt`). Save's `WorkingFileChanged` gives Reload or Keep mine.
  - A shown file that stops being conflicted gives `AfterStatus.Resolved(next)`, and the view model sets
    `ConflictResolverState.handOff`. The view dims the file (`PAUSED_ALPHA`), which scrolls but takes no clicks
    (`takingNoClicks` consumes presses in `PointerEventPass.Initial`), shows "✓ Resolved" after the file's name and
    one "Next file" where Mark resolved was, so the pointer stays put: in the toolbar, or on the choice card picked.
    `HandOffStrip` only says what Cmd+Enter opens. `nextFile`, or `markResolved` again (Cmd+Enter), loads it. Choice
    cards dim themselves, all but the card's Next file. While
    paused, picks, editing, Undo, Save and the panels' actions do nothing, and leaving doesn't ask about unsaved
    picks: the file was written and staged.
  - While the next file loads, the view keeps the last one (`LastLoaded`), fading it out under `LoadingCover`, which
    takes the pointer and says "Loading…" after 400 ms; the next one fades in.
  - Keys: the tab's handler sends Cmd+S (Stash elsewhere) and Esc to the resolver while it shows; the view handles
    Cmd+Enter (Mark resolved), F7 / Shift+F7, ← and → on choice cards, and while no part is edited Enter (edit the
    current conflict), Cmd+Z and Shift+Cmd+Z (Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y elsewhere) itself, so `Keybinding.kt` is
    unchanged. The view takes the focus when it opens, so `RepositoryOpenPage` gives it back to the tab whenever
    `showConflictResolver` turns false, however the resolver closed. Without that, the tab's shortcuts did nothing
    until a click.
  - Colours come from the theme (`primary` for ours, `conflictFile`, `addFile`), and a fixed purple for theirs.
  - The conflicts that three panes can't show get a card per side (see Choice cards).
  - Tests: `ConflictResolverTest` renders `ConflictResolverContent` from a state and clicks it, `ResolverColumnsTest`
    (domain) the rows and arrows, `ConflictResolverStatusTest` `afterStatus`. The view model itself was run against a
    real repository through the Dagger graph in a temporary harness (see Offscreen UI checks), not by a kept test.
- **The Stacked layout (step 3, `ResolverStackedView.kt`):** ours and theirs on top, the result below at the full
  width, with a handle between them (45% for the top by default, kept in the app singleton `ConflictResolverSession`
  until Leaf quits, not saved).
  - The switch: the toolbar's Columns and Stacked icons, and Settings → Conflicts → "Resolver layout". Both write
    `AppConfig.ConflictResolverLayout` (`conflict_resolver_layout`, Columns by default), which the view model reads.
  - Rows: `sidesRows` and `resultRows`, next to `columnRows`. The result gets one filler row for a chunk with no line
    in it (deleted lines, an open conflict's placeholder), so that every chunk has a place in both lists. Chips stand
    in for the arrows: Take ↓, Add ↓, Taken (`dropping` takes that side's lines out) and Applied or Apply.
  - The lists of both layouts are hoisted (`ResolverLists`), so a switch keeps the place: the chunk and the row of it
    at the top (`ChunkPlace`, `placeOf`, `rowOf`). The top pair follows the result's place through a `snapshotFlow`,
    except while `ResolverLists.pinned` says the view moved to a conflict.
  - The follower scrolls with `requestScrollToItem`, applied at the next layout. `scrollToItem` lays out at once, and
    throws "performMeasureAndLayout called during measure layout" when the collector runs during a layout, as it does
    in an offscreen scene, whose effects run inline. That exception killed the follower without failing anything.
- **Widths and long lines:** the Columns layout's gutters and the file list's edge are drag handles (`dragHandle`,
  `ColumnShares` in `domain/conflicts/ResolverSizes.kt`), kept in `ConflictResolverSession` until Leaf quits. "Wrap
  lines" in the toolbar turns wrapping off, and writes the setting "Wrap long lines" in Settings → Conflicts
  (`AppConfig.ConflictResolverWrapLines`, `conflict_resolver_wrap_lines`, on by default), which the view model reads.
  - Without wrapping, `LineText` (`ResolverSideways.kt`) lays each line out on one row through `UnwrappedNode`, which
    measures it with an unbounded width and places it `SidewaysScroll.offset` to the left, so scrolling only places
    the lines again. It answers intrinsic heights for an unbounded width: the rows' `IntrinsicSize.Min` asks with the
    column's width, and DiffText would report its wrapped height, making each row as tall as wrapped text.
  - `scrollsSideways` takes sideways swipes and Shift with the wheel in `PointerEventPass.Initial`, before the lists,
    and consumes them. `FitSideways` sets how far it goes from the longest line, measured once per merge.
  - Tests drive the handles and the wheel with `ImageComposeScene.sendPointerEvent` (`drag`, `doubleClick` in
    `ConflictResolverTest`). A drag's deltas start past the touch slop, but `draggable` hands over the whole movement.
- **Focus:** the toolbar's last button and ⇧⌘F (Ctrl+Shift+F elsewhere, `isFocusShortcut`) hide everything around the
  resolver: `RepositoryOpenPage` leaves out its toolbar (`Menu`), the operation banner and the bottom bar, and hides
  `App`'s tab bar through the fork-only `LocalAppChrome` (`ui/AppChrome.kt`, a flag that `App.AppTabs` reads). The
  resolver hides its file list, and the toolbar's file name becomes a menu of the files (`FileMenu`), followed by
  "2 of 3 files" (`FileCounter`, the files resolved here first). Esc goes to
  `ConflictResolverViewModel.escape`, which leaves Focus before closing. Kept in `ConflictResolverSession`.
  - Semantics actions run even on a disabled item, so tests check `SemanticsProperties.Disabled` rather than clicking
    it. A clickable node merges its children's text into its own config in the merged tree that `semanticsOwners`
    gives.
- **Region editing (step 4, `ResolverEditor.kt`, `domain/conflicts/ResolverEditing.kt`):** one part of the result at a
  time turns into a `BasicTextField` in place of its rows, in both layouts. A part is a chunk: the view model cuts runs
  of unchanged lines into chunks of `MAX_PART_LINES` (200) when it loads a file (`inParts`).
  - Starting: a double-click on the result's line, the Edit button that the pointer over a line shows
    (`EditableRow`), "Edit these lines" in the result's menu, or Enter for the current conflict
    (`ConflictResolverViewModel.startEdit`, `editCurrentConflict`). The caret goes to the end of that line.
  - The field starts from the lines that the result shows, or the base's for an open conflict, as Meld, IntelliJ and
    VS Code do (`editStart`). `LineFormat` writes the text back: in the part's charset (UTF-8 when its lines are, else
    ISO-8859-1, which keeps every byte), with its line ending, and without the last one where the file ends without
    one. Characters that the charset can't hold aren't taken (`typeInEdit`). An empty text is no line.
  - `TypedLines` hold the bytes, equal by content. `Chunk.Unchanged.edited` and `Chunk.Change.edited` replace their
    lines in the result (`CellKind.EDITED`); a conflict's are `ConflictResolution.Edited`, which counts as resolved.
    `resultBytes` writes them, with `TypedLines.lineEnding` for a last line that lines follow. A change's ✓ and ×, a
    conflict's arrows and menu, and `putBack` (unchanged lines' ↺, and the menu) replace them.
  - Finishing (`finishEdit`): Esc (the tab's EXIT branch reaches `escape`, which finishes first), a press anywhere in
    the resolver but the field (`finishingEditOnPressOutside`, after the field notes its own presses in
    `PointerEventPass.Initial`, `EditorPresses`), or anything that changes the merge, saves, moves or leaves. The field
    finishes too when it leaves the composition, as when the layout changes. Scrolled out of sight, it stays: LazyColumn
    keeps the focused item composed, and typing brings it back into view. A text that didn't change leaves the part as
    it was, so an open conflict stays open. The view takes the focus back when editing ends.
  - Undo and Redo (`ResolverHistory`, the toolbar's two icons and the keys) go through the merges before each pick and
    finished edit of the file shown, 500 at most, and start over when a file loads. While a part is edited, its field
    has the keys and undoes the typing. A step out of sight scrolls into view (`revealChunk`).
  - The list stays still (`EditorPlace`, one per list in `ResolverLists`): rows are keyed by chunk and row
    (`rowKey`), so LazyColumn keeps its first item by key while a part's rows turn into one item and back
    (`listItems`). When the list's top is inside the part, the field is keyed as that row and moved once laid out for
    the row's line to stay where it was, and finishing puts the line at the top back as a row.
  - Columns: `EditorRowLayout` makes the item as tall as the field or a side's lines, and the others get one filler.
    Line numbers are placed at the field's lines (`EditorLineNumbers`). Tabs show as four spaces (`TabsAsSpaces`), as
    `DiffText` shows them, and keywords, annotations and comments in the rows' colours (`EditorColours`, plain past
    `MAX_COLOURED_EDIT_CHARACTERS`). Without wrapping, the field doesn't wrap, scrolls with `SidewaysScroll` (its
    `editorWidth` lets the longest typed line be reached), and keeps the caret in sight.
  - **Opening a file that was saved** (`ResumedMerge.kt`, `resumedFrom`): `GetConflictedFileGitAction` cuts the merge
    into parts, converts the working file as `git add` would (`checkinLineEndings`, through JGit's input stream: its
    output streams only convert for a checkout), and picks out the state that Save wrote. First a state of picks
    alone, matched part by part against the file's lines (`pickedFrom`, every way through, 64 at most, as picks with
    the same lines can write other line endings; a loop, as a recursion per part overflowed the stack with a lock
    file's thousands of parts). Otherwise each conflict still open anchors at its block of markers,
    the stretches between them are diffed with what the merge writes untouched, and each part takes the lines that
    the diff maps to it: a pick that gives them (`pickOf`), or lines typed. Lines inserted where a pick meets unchanged
    lines go to the unchanged lines. The state is kept only when it writes the file again byte for byte, and the
    conversion back gives the file; otherwise the merge opens as before (`MATCHES` while the markers count the same,
    else `DIFFERENT`). A file without markers still gets the No conflict markers panel.
  - Unsaved changes: `ConflictResolverViewModel.hasUnsavedChanges` counts typing that isn't finished. Closing the tab
    (`AppViewModel.closeTab`, through `RepositoryTabViewModel.whenConflictResolverLeft`), the worktree picker's switch
    and opening another repository in the tab ask in the resolver's strip first (`ResolverLeave.Then`).
  - **Quitting** (`ui/QuitRequests.kt`, `ui/dialogs/QuitDialog.kt`): closing the window, and on macOS Cmd+Q, Quit in
    the app menu and the Dock, and logging out go through `AppViewModel.quit`. When a tab's resolver has changes that
    aren't saved (`unsavedFile`), `QuitDialog` asks first ("Quit Leaf?", each file and its tab): Save and quit
    (Enter), Discard and quit, or Cancel (Esc). Save and quit saves the files one after another (`saveBeforeQuit`); one
    that fails stops the quit and selects its tab, whose strip says why. A quit asked for while another waits for its
    answer or its saves is left to that one.
    - On macOS, the JDK's default quit handler exits at once (`System.exit`), without the window's close request. So
      `App.kt` installs its own (`Desktop.setQuitHandler`), which answers `cancelQuit()` when the user stays: a logout
      stops, as with TextEdit. Leaf comes to the front, un-minimized, while the dialog shows. Cmd+Q now clears Leaf's
      temp files too, as the close button already did.
  - Tests: `ResolverEditingTest` (domain), `ResolverEditingBytesTest` (data: 2,000 random merges, CRLF and ISO-8859-1
    included, where editing a part without changing it writes the same file, and 2,000 random saved states, which
    come back from their bytes), the saved files in `GetConflictedFileGitActionTest` (`core.autocrlf` included), and
    the editing tests in `ConflictResolverTest`. The view model, with the keys, was run against a real repository in a temporary harness.
- **Choice cards (step 5, `ResolverChoiceCards.kt`, `ConflictPreviews.kt`):** a conflict that three panes can't show
  (`ConflictedFile.hasChoiceCards`) shows what happened, then a card per side, ours left and theirs right. A click on a
  card, or ← and →, picks it (`ConflictResolverViewModel.pick`, a `ChoicePick` in `ResolverFile.Loaded.pick`); Mark
  resolved (Cmd+Enter, or the button on the card picked; the toolbar has none for cards) stages it through
  `ResolveConflictUseCase`, with no dialog, as picking stages nothing. Once resolved, the card's button is Next file,
  in the same place. The toolbar's Base adds the base's card (`showsBaseCard`), except where one side deleted the file.
  A resolved file takes no picks.
  - `GetConflictedFileGitAction` reads `ConflictedFile.preview` (`conflictPreview`, models in
    `domain/models/ConflictPreview.kt`); a failure there is logged and leaves it null, as the choice doesn't need it.
    Each version is a `VersionPreview`: a file's size, its lines for text too large to merge (`TooLarge`), and its
    bytes for an image (`PREVIEW_IMAGE_TYPES`, by extension, up to `MAX_PREVIEW_IMAGE_SIZE`, 16 MB), which the card
    decodes off the UI thread; a link's target and whether it resolves from the link's folder; a submodule's commit,
    named from the submodule's repository (`SubmoduleWalk.getSubmoduleRepository`), with which contains the other and
    its checkout (`SubmodulePreview`); a Git LFS pointer's object, downloaded when JGit's `Lfs.getMediaFile` has it
    with the pointer's size (`lfsVersion`), which the card can download (below).
  - One side deleted the file: Keep stages the working file as it is (`keepWorkingFile`), as `git mergetool`'s
    "modified" does, so edits since the merge stay. The card's diff (`keptChanges`, git's hunks with 3 lines of
    context, at most `MAX_PREVIEW_LINES`, 300) is the working file's, converted as `git add` would (`checkedIn`), and
    `workingFileEdited` says when it isn't the side's version any more. A diff that takes longer than
    `MAX_RESOLVER_COMPARE_TIME` is left out. With a `filter` attribute
    (`ConflictFileAttributes.filtered`), the working file isn't the blob's bytes, so the diff is the blob's.
  - "Open in the default app" (binary files and downloaded LFS objects): `OpenConflictVersionUseCase` has
    `WriteConflictVersionGitAction` write the stage's blob, or its LFS object, to `<app data>/tmp/<id>_<name>`, moved
    into place, then opens it as the diff pane opens an image.
  - **Downloading an LFS object (decided 2026-10-15):** a card whose object isn't in the store has "Download 4.2 MB"
    (`LfsDownloadRow`), which downloads that object alone with the built-in LFS client (`DownloadLfsVersionUseCase`,
    `DownloadLfsVersionGitAction`), then reads the version again (`lfsVersion`): the image, or "Open in the default
    app". Nothing downloads without the click, as LFS servers can be slow or metered.
    - The server is the checkout's (`IGetLfsUrlGitAction`, the app's `GetLfsUrlGitAction`), from the remote that
      git-lfs picks: a rebase's HEAD is detached, so `remote.lfsdefault`, the only remote or `origin` (see The built-in
      LFS client's server). Not git-lfs: it has no command that fetches one object, and `git lfs fetch` takes a
      commit, which a revert's or a stash's side isn't on the remote.
    - `ConflictResolverViewModel.downloadLfsObject` keeps the downloads by object id in
      `ConflictResolverState.lfsDownloads`: `Running` with the bytes received (`LfsRepository.downloadObject`'s
      `onProgress`) and Cancel, or `Failed` with the error in the card and Try again. One that ends well leaves the
      map, and the shown file's versions of that object take the new preview (`withLfsVersion`), so the pick stays. A
      download goes on when another file opens. Closing the credentials dialog puts Download back
      (`LfsError.CredentialsDenied`, `lfsDownloadFailure`).
    - It runs with `execute`, without the processing screen: the card shows its progress and its failure.
    - `FetchLfsObjectGitAction` (`data/git/lfs/`) downloads one object for the card and for `LfsSmudgeFilter`:
      `git-lfs-authenticate` for an SSH server, the batch API, then `DownloadLfsObjectGitAction`. The batch API's
      refusal of the object (`LfsObject.error`, such as `404 Object does not exist`) is `LfsError.ObjectRefused`; the
      smudge filter used to skip the download then, and fail reading a file that wasn't there. Exceptions (ssh's, an
      unknown host) are `LfsError.RequestFailed`. `LfsObjectDownloadError` carries them as a `GitError`.
  - **Renamed on both sides (`RenamedFiles.kt`, `ConflictContent.Renamed`, `RenamedFile`):** git records the old name
    at stage 1 alone, ours' new name at stage 2 alone and theirs' at stage 3 alone, and writes the same merged blob at
    both new names. `renamedFiles` pairs the new names by that blob (a blob that two pairs share stays out), and the old
    name by content (JGit's `RenameDetector`, at git's 50%, through `IndexEntryChange`, as JGit keeps
    `DiffEntry.add` and `delete` to itself), or as the only one left. `GetConflictedFileGitAction` opens any of the
    three paths as one `ConflictedFile` under the old name; the view model reads the groups with each status
    (`GetRenamedFilesUseCase`) and lists each once (`ResolverFileEntry.renamedTo`, `a.txt → b.txt, c.txt`).
  - The cards are the new names and "Keep both names" (`ChoicePick.BOTH`, `pickBothNames`).
    `ResolveRenamedFileGitAction` runs `git rm` on the old name and a new name not kept, and `git add` on a name kept
    (checked out from its side first when its working file is gone). A name kept whose working file has conflict
    markers (`hasConflictMarkers`, any length: git's are eight characters there) gets the stages of a content conflict
    instead, through `git update-index --index-info`: the base from the old name, ours from HEAD, theirs from the
    commit applied (`theirsCommit`: `MERGE_HEAD`, `CHERRY_PICK_HEAD`, or the commit a rebase replays). Its working file
    is written again with the resolver's markers (`writeMergeResult`, Save's writer) unless it was edited since, and
    the view model opens it next (`follow`). Without theirs' commit (a revert, a stash), Mark resolved waits
    (`renamedContentUnknown`, `ConflictError.RenamedContentUnknown`), and markers fixed by hand are staged as they are.
  - Other conflicts at several paths (`ConflictContent.OtherPaths`, such as `d~HEAD` for a file against a folder) get a
    card per side, as a deleted file does: the side with a file keeps it there (`keepWorkingFile`), the other leaves it
    out.
  - Tests: `ConflictPreviewsTest` (data: each kind from real merges, a submodule's repository, the LFS store, CRLF and
    filters, and the hunks against git's), `RenamedFilesTest` (pairing, conflicting content, the stages written, a
    stale group), `WriteConflictVersionGitActionTest`, Keep in `ResolveConflictGitActionTest`, `FileSizesTest`, and the
    cards in `ConflictResolverTest`. The view model, with the keys and the cards' buttons, was run against a real
    repository with one conflict of each kind, two renamed files and a file against a folder, in a temporary harness.
  - Tests of the download: `DownloadLfsVersionGitActionTest` (data: a `FakeLfsServer`, and a stand-in for
    `IGetLfsUrlGitAction`), `LfsDownloadsTest` (app), and the cards' states in `ConflictResolverTest`. The view model
    downloaded from a local LFS server through the Dagger graph in a temporary harness.
- **Words marked (step 6, `domain/conflicts/ChangedWords.kt`, `ResolverWords.kt`):** the rows mark the words that each
  side changed inside its lines, against the base, in a stronger shade of the line's colour (`CellKind.markColor`). The
  result's lines keep the marks of the side that they come from; lines typed and the base's lines get none, nor does a
  line changed whole.
  - `changedWords` diffs a chunk's words (runs of letters, digits and `_`, runs of spaces, other characters alone) with
    JGit's histogram diff, and joins the words alike between two changes when they're on one line and no longer than
    either change (diff-match-patch's semantic cleanup). A chunk of more than `MAX_COMPARED_WORDS` (2,000) gets none.
  - `ChangedWords` keeps them per chunk and side, the first time that a row asks, for as long as the file is shown
    (each layout remembers one by the merge's texts). The cells take it as a parameter, not a `CompositionLocal`: the
    lists scroll from a `LaunchedEffect`, which in an offscreen scene lays them out before the composition is applied,
    and a local changed in it threw "pending composition has not been applied", which froze the view.
  - Marks go through `DiffText`'s `MatchLine` (`LineMarks.matchLine`), whose inserted text takes `insertedColor`.
  - Tests: `ChangedWordsTest` (domain), the marks of each cell in both layouts and the field's colours in
    `ConflictResolverTest`, and `EditorColoursTest`.

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

**Worktree actions (fork-only, Phase 3, `docs/fork/worktree-lifecycle.md`):** creating, removing, discarding, pruning,
locking and unlocking worktrees, and merging and rebasing their branches, run the git CLI, and their dialogs show the
commands.
- An action is a list of `WorktreeStep`s (`domain/worktrees/WorktreeSteps.kt`): a git command and the folder it runs in
  (`Git`), switching the tab (`SwitchTab`, or only when a command before it stopped at conflicts with
  `onlyIfStoppedAtConflicts`), copying gitignored files into a new worktree (`CopyFiles`), or writing a squash merge's
  message (`WriteSquashMessage`).
  - The dialog shows the list (`WorktreeCommands`, from `shellLines`): a `cd` each time the folder changes, the commands
    quoted as a shell reads them, the home folder as `~`, and other steps as comments, so the lines can be pasted
    into a terminal.
  - `RunWorktreeStepsUseCase` runs the same list on confirm, as a foreground task. It stops at the first failure,
    whose `GitCliError.CommandFailed` names the command, and refreshes even then. `RunGitCommandGitAction` gives git
    no time limit. `runSteps` runs them inside another task (`CreateWorktreeUseCase`).
  - A merge or a rebase (`Git.mayStopAtConflicts`) that stops at conflicts doesn't stop the steps: the task ends as one
    that stopped at conflicts (`stoppedAtConflicts`). git exits with 1 for conflicts, but also when it refuses a squash
    merge or a rebase (a plain merge refuses with 2), so only exit code 1 with unmerged paths
    (`git ls-files --unmerged`) counts.
  - The commands run in the main worktree, and name a worktree relative to it when it's inside or next to it
    (`pathArgument`: `../leaf-agent1`, `.claude/worktrees/a1`), and absolute otherwise.
- **Remove and Discard** share `RemoveWorktreeDialog`. When it opens, `GetWorktreeRemovalUseCase` reads again what
  removing would lose (`WorktreeRemoval`), as the list may be stale:
  - the worktree's status;
  - its last change (`GetWorktreeActivityGitAction`): the newest of its git dir's `index`, `HEAD` and `logs/HEAD`,
    and of the files `git status` lists. A change in the last 5 minutes warns that an agent may still be working in
    it;
  - whether its branch is merged into the base (`GetBranchMergedGitAction`): its tip is in the base, or
    `git merge-tree --write-tree <base> <branch>` gives the base's tree, so a squash-merged branch counts as merged.
    git before 2.38 only gets the first test. The merge's tree is written to the object database, unreferenced.
- **Remove** asks for what loses work:
  - "Delete its uncommitted changes too" (`--force`) for a worktree with changes, or whose status couldn't be read;
  - "Remove it although it's locked" for a locked one, which runs `git worktree unlock` first. git's own override,
    `--force --force`, would also delete changes made after the dialog checked;
  - "Also delete the branch" adds `git branch -D`. Not `-d`, which checks HEAD or the upstream rather than the base,
    and fails for a squash-merged branch. The base branch, or the local branch of a remote base, is never offered.
- **Discard** always forces and deletes the branch, and asks a second time ("Discard anyway") when that loses work:
  changes, or a branch not merged into the base (`RemoveWorktreeState.onConfirm`).
- **The tab's own worktree:** removing it switches the tab to the main worktree first. `SwitchWorktreeUseCase.switch`
  runs inside another task; `invoke` and `followRemovedWorktree` launch it as a task of its own.
- **Prune** shows `git worktree prune --dry-run -v`'s lines (git writes them to stderr), then runs
  `git worktree prune`. "Clean up merged worktrees…" removes merged and missing worktrees in one go (see Cleaning up
  merged worktrees). **Lock** asks for an optional reason. **Unlock** runs at once, as it loses nothing.
- **Create** (`NewWorktreeDialog`, `NewWorktreeViewModel`) opens from the Worktrees header's "+", the picker's last
  row, a branch's menu (side panel and log) and a commit's menu, with a `NewWorktreeStart`. The log gets it through
  `LocalOnNewWorktree`, so that Gitnuro's composables in between don't change.
  - `NewWorktreeStart.startPoint` (`domain/worktrees/NewWorktreeSteps.kt`) turns it into git's start point: the base
    branch, or the tab's branch or commit without one; `--no-track` for a remote base, `--track` for a remote branch
    picked in a menu. A local branch that no worktree uses can be checked out instead of branched from.
  - `GetNewWorktreeContextUseCase` reads the main worktree, the base, the branches and which worktree uses them, and
    the repository's settings (`WorktreeCreationConfig`: `worktrees.newWorktreeFolder` and
    `worktrees.switchToNewWorktree` in `<common git dir>/leaf`). `CreateWorktreeUseCase` saves them and runs the steps.
  - The folder is `<repo>-<branch>` (`/` as `-`) next to the main worktree or in the chosen folder
    (`newWorktreeFolder`), shown with the home folder as `~` (`abbreviateHome`, `expandHome`). git checks the branch
    name as it's typed (`CheckBranchNameGitAction`, `git check-ref-format --branch`, which must print the name back:
    it expands `@{-1}`).
  - **`.worktreeinclude`** (`GetWorktreeIncludeGitAction`), the file that Claude Code, Codex and Conductor read: in the
    main worktree, gitignore syntax. git picks the files: `git ls-files --others --ignored --exclude-from=<file>`
    lists the untracked files that its patterns match, and `git check-ignore --stdin` keeps those that git ignores.
    A nested worktree comes as its folder, with a final `/`, and is skipped. `CopyWorktreeFilesGitAction` never copies
    a link, over an existing file, or out of either worktree. Without the file, the dialog explains it and creates
    one (`CreateWorktreeIncludeGitAction`, `WORKTREE_INCLUDE_TEMPLATE`), and it reads the file again when Leaf's
    window gets the focus back.

**Reviewing a worktree (fork-only, Phase 3.3, `WorktreeReview`, `WorktreeReviewViewModelExtender`):** "Review changes"
in a worktree row's menu shows, in place of Files changed, what the worktree's branch would bring into the base: the
commits and the changed files, as `git log base..branch` and `git diff base...branch` show them. It's offered for a
worktree on a branch other than the base (`branchToReview`, `domain/worktrees/WorktreeReview.kt`).
- `GetBranchComparisonGitAction` reads it with JGit, as it only reads: the merge base (`RevFilter.MERGE_BASE`, the
  first one with several), the commits oldest first (the newest `MAX_COMPARED_COMMITS`, 1000, and the count of all),
  the commits that the base has and the branch doesn't, and the diff from the merge base's tree to the branch's
  (`diffWithRenames`, as `GetCommitDiffEntriesGitAction` does for a commit: files that the branch moved are renames,
  see Renames in file lists). Branches without a commit in common have no diff. A file opens in the diff pane as a
  commit's file does (`DiffType.CommitDiff`): both sides are read by blob id.
- **State:** the review sets the selection to `SelectedItem.None` while it's shown, rather than being a `SelectedItem`
  of its own, which Gitnuro's code handles in many places. Selecting anything else closes it; the close button and
  Esc (`CloseableView.WORKTREE_REVIEW`) close it and select again what was selected before.
- It reads the comparison again when the commit of the branch or the base changes in the tab's branch data, which the
  file watcher refreshes when an agent commits. A refresh that finds the same commits doesn't, and the last
  comparison stays on screen while the next loads.
- The worktree's uncommitted changes aren't in it, as a merge leaves them out. A line under the files counts them,
  from the worktree list's status, with a link that switches the tab to the worktree, or shows the tab's own.

**Renames in file lists (fork-only, `data/git/diff/RenameDetection.kt`, the commit and review lists of Gitnuro#347,
2026-10-19):** a commit that git shows as `R old -> new` used to list a deletion and an addition. `git.diff()` finds
renames only when `diff.renames` is set, as JGit reads the unset key as off, where git's default is on.
`GetCommitDiffEntriesGitAction` and `GetBranchComparisonGitAction` call `Repository.diffWithRenames` instead, so a
commit's list and a worktree review's show a moved file as one `RENAME` entry (`DiffEntry.filePath` is the new path,
`oldPath` the old one, `score` how much is alike). The Staged and Unstaged lists show none: they come from JGit's status
and the index, where git's `R  old -> new` needs a rename status type and every stage, unstage, discard and diff action
keyed by path to handle it.
- **Detection:** a plain scan with `DiffFormatter` (renames off), then JGit's `RenameDetector` on its added and deleted
  files, at git's score of 50 (JGit's own is 60), in stages, each on what the one before left:
  1. exact renames, the same blob: found by id, no blob read (1,500 moved files, a few ms);
  2. files of the same name, each added file against the deleted files of its own name only: how a refactor that moves
     files and edits their `package` line goes (git does this stage too);
  3. everything left, when each side has at most `diff.renameLimit` files, for a file renamed as well as moved.
     `DEFAULT_RENAME_LIMIT` (100) applies when the key isn't set. JGit's own is 400, git's 1,000.
- `diff.renames=false` turns it off; any other value, `copies` too, finds renames. Not `DiffFormatter`'s own detection
  (`setDetectRenames(false)` after `setRepository`, which switches it on from the key with JGit's score and limit).
- **Why a limit of 100 (probe, 2026-10-19, JGit 7.7):** the content stage (2 and 3) re-reads and hashes the added
  file's blob for every deleted file it compares it with, so it costs the square of the files per side. Moved and
  edited files on loose objects, which an agent's fresh commits are: 150 files took 2.7 s, 400 files 27 s (100 KB each,
  120 s). Packed objects are about 20 times faster. The same cost applied to `diff.renames=true`, with JGit's limit.
- **A budget:** stages 2 and 3 stop after `MAX_RENAME_DETECTION_TIME` (2 s) and when the coroutine is cancelled
  (`TimeAndCancellation`, a `ProgressMonitor`), keeping what the earlier stages paired. A cancelled call throws
  (`ensureActive`), though `JGit.provide` turns that into an error result. `DiffFormatter` can't do this: it returns an
  empty list when its monitor cancels, which would have blanked the list.
- **A partial clone** (`readableBlobs`): stages 2 and 3 only see the files whose blobs the repository has, so a list
  downloads nothing (see Partial clones, Lists don't read blobs). A commit's exact renames are found whatever is
  missing, and a commit made in the clone itself pairs its edited moves too.
- **JGit's quirks, handled:**
  - `RenameDetector` changes the entries that it pairs (it turned a deleted file's change type into `RENAME`), so the
    entries that stay as they are (`MODIFY`) are taken before it runs, and only its own leftovers are used after it.
    A stage that is cancelled in the middle changes none.
  - A file that was added twice with a deleted file's content is a `RENAME` and a `COPY`. git shows an addition for the
    second, so a copy is the `ADD` it came from.
- **The rows (`FileItem.renamedFrom`, `RenamedFrom`, `ChangedFilesList`):** `toFileItems` sets it from the entry
  (`DiffEntry.renamedFrom`), and Staged and Unstaged leave it null. A renamed row shows `← label` right after the file
  name, in split columns at the start of the directory column, and its tooltip says "Renamed from <old path> (92%
  similar)". Option B of three (chosen 2026-10-19); the first layout put the hint after the directory, which is long in
  Kotlin and Java paths and took all the room in a 380 px pane, so the hint was cut off in most rows.
  - The directory is what gives way. The name takes at most 55% of the row and the hint 80% of what's left
    (`atMost`), so a long class name leaves the hint room.
  - `RenamedFrom.label` shows only the part of the old path that differs, as git writes `{old => new}`: the old name
    when the file stayed in its folder (`Lexer.kt`), the old folders that differ when it kept its name (`…/legacy/`,
    the `…/` standing for the folders that both paths start with), and those with the old name when both changed
    (`…/parse/Lex.kt`). The whole old path when nothing of its folder is left: into a folder below its own, or from the
    top folder.
  - The arrow has the colour of the row's icon (`modifyFile`), and the label is stronger than the muted directory. The
    rename icon is `ArrowForward`, where `Refresh` read as "reload".
  - A search matches the old path too (`matchesSearch`). Sorting and the three views need nothing: `path` is the new
    path, and `FileChangeKind.Renamed` already ranked.
- **The diff pane (`ui/diff/RenamedDiff.kt`):** the header adds `← old path · 92% similar` after the name (the path is
  cut first when it's narrow; no percentage for 100). A rename with no change has no hunks, which left the pane blank:
  it says "Renamed from X. The content is unchanged." (`renamedWithoutChanges`, for the unified and the split view).
  A rename with edits shows the diff from the old file to the new one, read by blob id as any commit's file.
- **File history:** `HistoryViewModel` finds the commit's entry by the new or the old path. Under the new path only,
  the history of `old.txt` closed its diff at the commit that moved it, which was a deletion before.
- Tests: `RenameDetectionTest` (exact, edited, in a folder, half alike against git's `-M`, copies, `diff.renames` false,
  true and `copies`, the limit and `diff.renameLimit`, many files by name, 1,500 exact, no time, out of time half way,
  cancelled, a blobless partial clone and a commit made in one), `GetBranchComparisonGitActionTest` (the review, and the
  diff of a moved file), `RenamedFromTest`, `CommitChangesRenamesTest`, `ChangedFilesListRenamesTest`,
  `RenamedDiffTest`, `HistoryViewModelTest`.

**Merging a worktree into the base (fork-only, Phase 3.4, `MergeWorktreeDialog`, `WorktreeMergeSteps.kt`):**
"Squash-merge into main…" and "Merge into main…" follow "Review changes" in a worktree row's menu, for a worktree on a
branch that is neither the base nor the branch merged into (`branchToMerge`). That's the base's local branch
(`mergeTarget`): the base itself, or `main` for `origin/main`. Without a local branch, the items aren't offered.
- `GetWorktreeMergeUseCase` reads, when the dialog opens (`WorktreeMerge`): the worktree that has the target checked out
  and its status, the branch's worktree's status and last change, the commits that the branch brings (3.3's
  comparison, from the target), and whether its changes are already in the target (`GetBranchMergedGitAction`).
- git gets the branch's short name, unless `git rev-parse --symbolic-full-name` resolves it to something else, such as
  a tag of the same name, which `git merge agent1` would merge. Then it gets the full name, and its message says
  "Merge branch 'refs/heads/agent1'".
- **Refused** (`refusal`), with the button disabled:
  - nothing to merge;
  - a squash merge whose changes are already in the target, which would stage nothing (a merge is allowed);
  - staged changes or conflicts in the target's worktree, as `git merge --squash` takes staged changes in without a
    word; also when its status can't be read, or its folder is missing;
  - a worktree that rebases the target or bisects from it;
  - no worktree with the target checked out, when the tab's worktree is the branch's own, or has changes to tracked
    files that `git switch` would carry over (untracked files don't count: git refuses to overwrite them).
- **Steps** (`mergeSteps`): the tab switches to the target's worktree (or `git switch main` runs in the tab's worktree
  when no worktree has it), then `git merge --squash <branch>` or `git merge --no-ff --no-commit <branch>`, then for a
  squash `WriteSquashMessage`, over git's "Squashed commit of the following:".
- **The squash message** (`squashMessage`): a single commit's own message; otherwise the branch's name, then
  `- <subject>` per commit, oldest first, and a line counting the commits past `MAX_COMPARED_COMMITS`.
- **A squash merge in progress:** git records no state for it (no `MERGE_HEAD`; JGit says SAFE), so
  `isSquashMergeInProgress` is `SQUASH_MSG` there while something is staged or has conflicts. A leftover `SQUASH_MSG`
  doesn't count.
  - The commit field shows `SQUASH_MSG` then (`CommitMessageSource`: `MERGE_MSG` during a merge, cherry-pick or
    revert, an edit stop's message during a rebase, then a squash merge's, then the plain draft), and saves edits to it
    (`PersistSquashMessageUseCase`, which never empties it).
  - `OperationInProgressBanner` says "Squash merge staged…" (`Operation.SQUASH`). Its Abort runs `git reset --merge`
    (`AbortSquashMergeUseCase`), which undoes the staged changes and keeps unstaged ones, and refuses, changing nothing,
    while a file that the squash merge changed has unstaged changes. What `merge.autoStash` set aside comes back too
    (see Changes that git sets aside).
  - A squash merge that stops at conflicts writes `MERGE_MSG` too. Ours is the target; theirs is "the changes being
    applied", as git doesn't record the branch.
- **After Leaf's commit,** `CleanUpAfterCommitGitAction` removes what git's commit removes and JGit's leaves:
  `SQUASH_MSG`, `MERGE_MSG`, `MERGE_MODE` and `AUTO_MERGE`, unless a rebase goes on. A `MERGE_MSG` left there would be
  the message of the next commit made in a terminal. Then `PutBackMergeAutostashGitAction` puts back what
  `merge.autoStash` set aside (see Changes that git sets aside).
- **The commit field** (fork-only fixes): a commit clears the field it committed from. Upstream cleared the field shown
  after the commit's refresh, which was the plain draft after a merge, and the next merge then showed the old merge
  message, as a field fills from its file only while it's blank. For the same reason, the fields of `MERGE_MSG` and
  `SQUASH_MSG` are cleared once their file is gone, unless the field still shows `MERGE_MSG` (a merge, cherry-pick or
  revert goes on). What disables its buttons, and the line that says why, are in the next bullet.
- **What disables the primary button, and the hint that says why (fork-only, `ui/status/FinishBlockers.kt` and
  `FinishHint.kt`, 2026-10-19, Gitnuro#120 and #124):** `StatusState.finishBlocker()` returns the first thing that stops
  Merge, a cherry-pick's or a revert's Commit, a rebase's Continue, or Commit and Amend outside an operation, or null.
  `CommitField` enables the primary button when it's null, and shows `FinishHint` for `finishHint()` in a line between
  the message field and the buttons ("Resolve the conflicts in 2 files first.", "Stage or discard the changes to 3
  tracked files to continue.", "Write a commit message.", "Stage some changes to commit."). Until 2026-10-19 Merge, a
  cherry-pick's Commit and a rebase's Continue were disabled by any file in the Unstaged list (`!hasUnstagedFiles`), an
  untracked build output or a merge tool's `.orig` file included, and nothing said why. Agents leave such files in their
  worktrees, so finishing a merge in Leaf was blocked.
  - **The rules**, each against git 2.54 (the tests below): Merge needs no conflicts, and nothing else: git finishes a
    merge with unstaged and untracked files around, and with nothing staged (every conflict taken from our side), where
    the merge commit still has two parents. A cherry-pick's or a revert's Commit needs no conflicts, a message that
    isn't blank (JGit commits an empty one, which git refuses) and something staged (`DoCommitGitAction` allows an empty
    commit only for a merge or an amend, so JGit refuses it there, which was an error dialog once the button was
    enabled). A rebase's Continue needs no conflicts and no unstaged changes to tracked files: git refuses those, in both
    backends, and ignores untracked ones (`has_unstaged_changes` in builtin/rebase.c; it ignores submodules, where Leaf's
    status lists a changed one as modified, so Leaf may block where git wouldn't). In the Unstaged list, `MODIFIED` and
    `REMOVED` are tracked changes and `ADDED` is an untracked file. Outside an operation, Commit needs a message, staged
    files and no conflicts (a squash merge's), and Amend a message and a commit to amend, as before.
  - **JGit's rebase gets git's rule too:** its continue goes on with tracked changes that aren't staged and commits what
    is staged. The status pane doesn't know which tool started the rebase, and a rebase should behave the same whichever
    did, so the rule is git's for both.
  - **Continue or Skip (`showsContinue`):** Continue shows when something is staged, when something blocks it (so it's
    disabled with its reason rather than turning into a Skip, which discards the changes), or at an amendable stop with
    an amended message. With only untracked files or nothing left, the commit being applied came out empty and the
    button is Skip: JGit's continue does nothing there (`NOTHING_TO_COMMIT`, which Leaf reports as a plain continue),
    git's apply backend refuses it, and git's merge backend drops the commit. Skip loses no work in that state: it
    discards tracked changes, staged or not, in both tools, and keeps untracked files.
  - **The hint** is silent outside an operation while the field rests (no message, nothing staged), which is how the
    pane sits most of the time, and says what is missing once the user has staged something or typed a message. During
    a merge, cherry-pick, revert or rebase it is there whenever the button is disabled. When several reasons apply it
    names the first: conflicts, unstaged tracked changes (rebase), a blank message, nothing staged. A revert's button
    also waits for the status to load (`isLoading`) without saying so.
  - **The key** (Ctrl or Cmd+Enter in the message field) does what the primary button does in a merge, cherry-pick or
    revert (`acceptsCommit`); it needed staged files, whatever the Merge button allowed. Elsewhere it is as before.
  - `CommitField` is `internal` for its test. `StatusPane` passes it the values that `StatusState` derives
    (`canCommit`, `canAmend`, `showsContinue()`, `finishBlocker()`, `finishHint()`), and `CommitFieldTest` repeats that.
  - Tests: `FinishBlockersTest` (the rules for every repository state, all combinations of files, message and amend
    against the rules written out, and that the reason given is true), `CommitFieldTest` (rendered from a `StatusState`:
    the buttons, what a disabled one does when clicked, the hint and where it is, the key),
    `RebaseWithUnstagedFilesTest` (what git's two backends and JGit do with each kind of unstaged file, for Continue and
    Skip) and `FinishingWithUnstagedFilesTest` (a tab commits a merge, a cherry-pick and a revert, and continues and
    skips rebases of both kinds, with an untracked and a changed file around; JGit refusing an empty cherry-pick and
    revert commit).
- **The plain draft** (fork-only, `data/git/repository/CommitDraft.kt`, 2026-10-16) is in `<git dir>/LEAF_COMMIT_DRAFT`
  (`AppStorage.COMMIT_DRAFT_FILE_NAME`), in the worktree's own git dir. Upstream kept it in git's `COMMIT_EDITMSG`,
  which git uses as scratch space: every `git commit` from a terminal (an agent's) and `git rebase --continue`, with its
  comment lines, left their message there, and a blank field took it as the draft.
  - Leaf no longer reads, writes or removes `COMMIT_EDITMSG`. JGit's commit writes the message there every time, for
    the commit-msg hook, whether there is one or not (`CommitMsgHook.canRun` only checks the file's path, see
    `hookArguments`). Leaf used to remove it right after; it now keeps the last message, as after git's commit.
  - A draft that an older Leaf left in `COMMIT_EDITMSG` isn't moved over, as nothing tells it from git's message.
  - A commit removes only the message it committed. The caller says which (`DoCommitUseCase`'s `isMergeMessage`,
    from the field's `CommitMessageSource.isMergeMessage`). A plain commit removes the draft. The commit of a merge,
    cherry-pick, revert or squash merge leaves it, as the commit removes their own files (JGit's commit,
    `CleanUpAfterCommitGitAction`). Until 2026-10-16 merges and squash merges removed the draft too, as the repository
    is SAFE after them, where `PersistCommitMessageGitAction` picks the draft.
  - **Which message, in each state** (`commitMessageSource`, `domain/models/CommitMessageSource.kt`, 2026-10-16): the
    field shows it, and `PersistCommitMessageGitAction` saves the field's edits there, as both go by it:
    - while merging, cherry-picking or reverting, `MERGE_MSG` (`MERGE`): git's message, which `git commit` takes
      then: the merge's, the picked commit's, or `Revert "…"` and `This reverts commit <id>.`. The draft waits, out of
      view, and comes back after the commit or Abort (JGit's commit and its hard reset remove `MERGE_MSG`);
    - while rebasing (`REBASE`), the message for the commit that the rebase stopped at to edit (`rebase-merge/amend`,
      which git and JGit write at an `edit` stop), in `rebase-merge/leaf-amend-message-<commit>`
      (`RebaseEditMessage.kt`, `AppStorage.REBASE_EDIT_MESSAGE_FILE_PREFIX`). Until one is saved, the field shows the
      commit's own message, HEAD's, which `git commit --amend` starts from (`headCommitMessage`). Amend is ticked at
      such a stop (upstream's default), so Continue amends with it even unchanged, a commit that the rebase rewrites
      anyway; with an empty field, the button read Skip. Continue's amend removes the file. Each stop has its own, so a
      message that a Continue from a terminal left isn't the next stop's, and the field's text is replaced when the
      stop changes (`persistedCommitMessageFlow`'s `key`). The file goes with the rebase's folder. At the other stops
      of a rebase the field can't be edited, shows the last commit's message, and nothing is saved;
    - `SQUASH_MSG` while a squash merge waits, and the draft otherwise, also while bisecting.

    Until 2026-10-16 the field showed `MERGE_MSG` only while merging (upstream's `isMerging`), and the draft
    otherwise, while its edits went to `MERGE_MSG` during a revert or a rebase and nowhere during a cherry-pick: a
    restart showed the draft as it was before, and a cherry-pick's or a revert's commit took the draft. At an edit stop
    a draft, typed for another commit, became the amended commit's message, as Amend fills only an empty field.
    `MERGE_MSG` wouldn't do for an edit stop: JGit's interactive rebase leaves a conflicted commit's `MERGE_MSG` through
    the later stops, and after the rebase.
  - **git's conflict comments** (`withoutConflictsHint`, `data/git/workspace/ConflictsHint.kt`): git and JGit add the
    conflicted files to `MERGE_MSG` as comments (`# Conflicts:`, then `#\t<path>` lines, in `core.commentChar`),
    which `git commit` strips. JGit's commit keeps a message as it is, so a merge committed in Leaf after conflicts kept
    them. `GetPersistedCommitMessagesGitAction` leaves them out, and git's scissors line with what follows it
    (`commit.cleanup=scissors`), so the field shows what its commit will have. The user's own lines stay, even ones that
    start with `#`, which `git commit` would strip. git adds them at the end, JGit before the message's footers.
- A plain merge then goes on in Leaf's merge UI: `MERGE_MSG` in the field, the Merge button, the banner.
- When a merge ends, conflicts or not, the tab selects the uncommitted changes (`worktreeResultsToShow`), where its
  result waits. A selected commit, or the review, would hide it.

**Rebasing a worktree onto the base (fork-only, Phase 3.5, `RebaseWorktreeDialog`, `WorktreeRebaseSteps.kt`):**
"Rebase onto main…" follows the merge items in a worktree row's menu, for a worktree on a branch that is neither the
base nor its local branch (`branchToRebase`): rebasing `main` onto `origin/main` would be a pull. It rebases onto the
base as chosen, `origin/main` too, which is what the row's ↑↓ count against, so it's offered without a local branch.
- `GetWorktreeRebaseUseCase` reads, when the dialog opens (`WorktreeRebase`): the worktree's status and last change,
  and how many commits the rebase replays and brings in (`GetAheadBehindGitAction`). git gets the base's short name
  unless it means something else, as with the merged branch (`refArgument`, shared with the merge): with a tag named
  `main`, `git rebase main` would rebase onto the tag.
- **Refused** (`refusal`), with the button disabled: changes to tracked files in the worktree (git refuses too, and
  Leaf doesn't stash an agent's work in progress), a status that can't be read, a missing folder, and a branch that
  already has every commit of the base. Untracked files don't stop it: git refuses to overwrite one, and changes
  nothing. An agent's recent change is a notice (`RecentChangeNotice`), as in Remove and Merge.
- **Steps** (`rebaseSteps`): `git rebase --no-autostash <base>` in the worktree, then a `SwitchTab` to it that runs only
  when the rebase stopped at conflicts. `--no-autostash` keeps a user's `rebase.autoStash` from stashing changes made
  after the dialog checked; git refuses them instead. The tab stays where it was when the rebase ends, and when it
  stops, the tab shows the worktree's conflicts, with the uncommitted changes selected (`worktreeResultsToShow`).
- The dialog's content is `RebaseWorktreeDialogContent`, which takes the state, so `RebaseWorktreeDialogTest` renders
  it offscreen.

**Merge, cherry-pick, revert and rebase with git (fork-only, `data/git/cli/operations/`):** Merge in the branch menu,
cherry-pick, revert and Rebase run the git CLI, as a terminal would: git's merge engine (ort, which follows a file that
one side renamed), git's messages, every hook that git runs (`pre-merge-commit`, `post-merge`, `post-checkout`,
`post-rewrite`, …), and the identity and signing settings that git reads. JGit ran them until 2026-10-15, and still
does when git can't.
- `GitCliOperations.run` runs `git merge --ff|--no-ff --no-edit <branch>` (`--ff` and `--no-ff` follow Leaf's
  "Fast-forward merge" setting, over `merge.ff`, as with JGit), `git cherry-pick <commit>`,
  `git revert --no-edit <commit>` or `git rebase <branch>` in the working tree, with `GIT_EDITOR=:` and no time limit,
  as git runs hooks.
- A branch is named as `refArgument` (domain, shared with the worktree dialogs) names it: its short name, so that
  git's message says "Merge branch 'feature'" or "Merge remote-tracking branch 'origin/feature'" (and " into dev" off
  `main` and `master`), unless git would take a tag of that name first. JGit's message was "Merge branch 'feature' into
  main" for both.
- They run through `GitCliRemoteCommand`, as git may have to ask: it downloads what a partial clone lacks itself (so
  they don't prefetch, see Partial clones), git-lfs's filter downloads LFS files, and ssh-keygen asks for the
  passphrase of a key that signs commits (`SshPasswordDialog`). So the processing screen has Cancel: git gets SIGTERM,
  removes its lock files, and leaves the working tree as far as it got.
- Stopped at conflicts is exit code 1 with unmerged paths (`git ls-files --unmerged`). Exit code 1 without them is a
  refusal (a rebase with local changes, a merge of something that isn't a commit, a hook that refuses), and so is any
  other code (2 for a merge that local changes stop, 128 for a merge while conflicts are left):
  `GitCliError.CommandFailed` with git's message, in `ErrorDialog`. Merge and rebase return whether they stopped, for
  the toast; cherry-pick and revert give an error, as JGit's did.
- A cherry-pick or a revert of a commit whose changes are already there stops, as in a terminal: the cherry-pick keeps
  `CHERRY_PICK_HEAD` (Abort in the banner), and the revert leaves only `MERGE_MSG`, which Leaf's commit field doesn't
  show without `REVERT_HEAD` (the repository is SAFE).
- The `Selecting…GitAction`s are bound in place of JGit's: git when `GitCliOperations.useGitCli()` (a usable git and
  the askpass helper), JGit otherwise. The "Use git for remote operations" setting doesn't apply. Pull runs
  `git pull` (see Remote operations); the interactive rebase stays with JGit.
- `MergeBranchUseCase` deletes its backup snapshot ("Automatically stash uncommitted changes before merge") after a
  merge that completed, as before, and (fork-only) after a failure that changed nothing: HEAD still at the backup's
  first parent and no merge in progress (`IsUnchangedSinceGitAction`), unless the task was cancelled. Each of git's
  refusals used to leave a stash. It's kept after conflicts and after a hook that refuses with `MERGE_HEAD` left.
- The backup's message, and the pulls', names branches, which can hold `'` and `{`. So (fork-only)
  `SnapshotStashCreateCommand` takes a message as text, and formats only its own default patterns with `MessageFormat`,
  where `'` quotes and `{` starts an argument. JGit's `StashCreateCommand` formats any message, so
  `StashChangesGitAction` passes the user's through `messageFormatText`. Tests: `StashMessagesTest`, and two in
  `MergingWithAutostashTest`.
- Tests: `GitCliOperationsTest` (each operation, conflicts, git's refusals, a renamed file, hooks, an identity from
  `includeIf`, a merge signed with an SSH key whose passphrase Leaf asks for, Continue after git's rebase stopped, and
  JGit without git or the helper).

**Changes that git sets aside (fork-only, `data/git/cli/operations/Autostashes.kt`):** Leaf's merges, rebases and
pulls run git without `--no-autostash`, so the user's `merge.autoStash` and `rebase.autoStash` apply, as in a
terminal: git sets local changes aside (a stash commit) and puts them back when the operation ends. Findings from git
2.54 are in the tests.
- A rebase's (`rebase-merge/autostash`) is git's to the end: Leaf's Continue, Skip and Abort run git for a rebase that
  git started.
- A merge's waits in `MERGE_AUTOSTASH` until the merge is committed or aborted, even after a clean `--no-commit` or
  `--squash` merge (the worktree merge dialog). `git commit` puts it back after any commit, and `git merge --abort`
  after the reset; `git reset --hard`, `git reset --merge` and `git merge --quit` store it in the stash list instead.
  Leaf commits with JGit and aborts with JGit's hard reset or `git reset --merge`, so it puts it back itself:
  - `PutBackMergeAutostashGitAction`, after every Leaf commit (`DoCommitUseCase`) and after Abort of a merge,
    cherry-pick or revert (`ResetRepositoryStateUseCase`): `git stash apply <id>`, else
    `git stash store -m autostash <id>`, then `git update-ref -d MERGE_AUTOSTASH <id>`, as git's
    `apply_save_autostash_ref`. A failure leaves the ref, so nothing is lost, and is only logged.
  - `AbortSquashMergeGitAction` takes the ref out first, as `git merge --abort` does, then runs `git reset --merge` and
    puts it back. When git refuses, the ref goes back, and the squash merge waits as before.
  - Left behind, the next `git commit` in a terminal put the changes back without a word, maybe days later and on
    another branch; `git reset --hard` or the next `git merge` stored them. Leaf left it behind until 2026-10-16.
  - git keeps it per worktree, and with the reftable backend it isn't a file, so Leaf asks git
    (`git rev-parse -q --verify MERGE_AUTOSTASH`).
- **When they don't go back** (they conflict with the result, changes made since are in the way, or an untracked file
  is where a staged new file of the user's was, which a `git rebase --abort` meets too), git stores them in
  the stash list as "autostash", and still exits with 0: after a merge or a rebase, Leaf says "Merge completed" while
  files have conflicts. So `GitCliOperations.run` compares the stash list's newest entry before and after a merge or
  rebase that exits with 0 (`noticeIfKept`: a new one named "autostash"), and the put-back reports its own.
  - Both add an `AutostashNotice` (what set them aside, when they were put back, and the files with conflicts, empty
    when the apply was refused) to `RepositoryStateRepository.autostashNotices`. The tab shows each in
    `AutostashNoticeDialog` (`Screen.ChangesInStash`), with the setting, the files and "autostash" in the monospace
    font, until OK. It isn't a toast, which would be gone before it's read, nor an `ErrorDialog`, as the commit or
    abort succeeded.
- Tests: `AutostashesTest` (each case of the put-back against `git commit` in a twin repository, a linked worktree, a
  reftable repository when git has the backend, and another tool's stash made meanwhile), `MergingWithAutostashTest`
  (Merge, Leaf's commit and Abort, the squash Abort and commit, and the backup, in a `TestTab`),
  `GitCliOperationsTest` (a rebase's notice, after Continue and Abort too, and none when the changes go back),
  `AutostashNoticeDialogTest`.

**A rebase that git started (fork-only, `data/git/rebase/GitCliRebase.kt`):** Continue, Skip and Abort, in the status
pane and the banner, run `git rebase --continue`, `--skip` or `--abort` for a rebase that git started: Rebase in the
branch menu (see above), "Rebase onto", a terminal or an agent. A rebase that JGit started (interactive ones, and Rebase
when git can't run it) stays with JGit.
- `isRebaseStartedByGit`: a `rebase-apply` folder (git's apply backend), or a `rebase-merge` folder without JGit's
  `onto_name` file, which git 2.54 doesn't write. The `Selecting…RebaseGitAction`s are bound to the domain
  interfaces, and use JGit when git can't run (`GitCliOperations.useGitCli()`).
- Neither can go on with the other's (probed on git 2.54 and JGit 7.7): JGit's continue committed git's `message` file
  as it was, with its `# Conflicts:` lines, and its continue, skip and abort left `MERGE_MSG` (the stopped commit's
  message, which a commit from a terminal would take) and `REBASE_HEAD`. JGit doesn't know git's other todo commands
  either (`exec`, `update-ref`, `label`). git refuses to continue JGit's ("could not commit staged changes").
- git runs through `GitCliOperations.run`, with `GIT_EDITOR=:`, so it keeps the stopped commit's message, without its
  comment lines, and with no time limit, as it runs hooks. A user's own `GIT_EDITOR` would otherwise open, or fail
  without a terminal.
- Continue and Skip return whether the rebase stopped again at a later commit's conflicts (exit code 1 with unmerged
  paths for git, `STOPPED` for JGit), which the toast then says.
- **Unstaged files** (probed 2026-10-19, `RebaseWithUnstagedFilesTest`): git's Continue refuses unstaged changes to
  tracked files, modified or deleted, a staged file changed again and a resolved file edited again ("You must edit all
  merge conflicts and then mark them as resolved using git add"), in the merge and the apply backend, and goes on with
  untracked files. JGit's continues with all of them. With nothing staged, git's merge backend drops the commit, its
  apply backend refuses, and JGit does nothing (`NOTHING_TO_COMMIT`). Skip keeps untracked files and discards changes
  to tracked ones, staged or not, in all three. The status pane's rules for them are in the commit field (see What
  disables the primary button).
- A rebase that git started writes `rebase-merge/interactive` even when it isn't interactive, so JGit reads it as
  `REBASING_INTERACTIVE`. Leaf shows it as processing commits (`done` exists), as it did before.
- git signs the commits that it makes during a rebase as `commit.gpgSign` says, with its own gpg or ssh-keygen rather
  than Leaf's signers. ssh-keygen asks for a key's passphrase in Leaf's dialog, through the askpass helper (checked
  with a merge, as above). Until 2026-10-15 Continue and Skip ran without it.

**Remote operations (fork-only, `data/git/cli/remote/`, `data/git/cli/askpass/`):** push, fetch, pull, clone, and
adding and updating submodules run the git CLI, and git-lfs downloads LFS files when it's installed (stages 1 to 5 of
`docs/fork/remote-operations.md`).
- Push and remote branch deletion run `git push --porcelain --progress` (`GitCliPushBranchGitAction`,
  `GitCliDeleteRemoteBranchGitAction`). JGit's deletion, the fallback, returns the push's failure and keeps the
  remote-tracking branch; it used to delete that branch and report success.
- Fetch runs `git fetch --progress --prune <remote>` for each remote in turn (`GitCliFetchAllRemotesGitAction`), so
  one that fails doesn't stop the others. The failures come back together as `FetchRemotesError`. A remote whose
  dialog the user closed isn't reported, as with JGit ("Cancelled authentication").
- Pull runs `git pull` (`GitPullGitAction`) through `GitCliOperations.run`, as Merge runs `git merge`: git fetches,
  then merges or rebases with its own engine (ort, which follows renames), its messages (`Merge branch 'main' of
  <url>`, and ` into feat` off `main` and `master`), its hooks and git-lfs's filter. A rebase starts from the fork
  point, so the commits that a force-pushed upstream replaced are left out; JGit's rebase onto the fetched commit
  replayed them, and could stop at their conflicts. JGit pulled until 2026-10-16.
  - Merging or rebasing is Leaf's choice, passed as `--no-rebase` or `--rebase`, so `pull.rebase` and
    `branch.<name>.rebase` don't apply, as with JGit. An explicit `--no-rebase` merges even with `pull.ff=only`, and
    Leaf's Pull button passes one too, so Leaf reads `pull.ff` with `git config` (for `includeIf`) and adds
    `--ff-only` when it's `only`, as JGit refused. `--rebase` ignores `pull.ff`, as JGit's rebase did.
  - What is pulled is what JGit's `PullCommand` pulls (`pullSource`, which refuses a detached HEAD, or a merge or
    rebase in progress, before git runs): with an upstream, git gets no remote and no branch, so it fetches the whole
    remote, or fetches nothing for a local upstream (remote `.`); otherwise Leaf names the chosen remote branch, or the
    branch of the same name on `origin`. A branch without commits takes the pulled commit.
  - `merge.autoStash` and `rebase.autoStash` apply (see Changes that git sets aside). When the changes that git set
    aside don't go back, git exits with 0 and the tab shows `AutostashNoticeDialog`: `GitCliOperations.run` counts
    `git pull` as a merge or a rebase by its flag.
  - Stopped at conflicts is exit code 1 with unmerged paths. git also exits with 1 for a fetch that fails and for
    local changes in the way of a fast-forward, with 2 for local changes in the way of a merge, and with 128 for a
    rebase with unstaged changes: those are `RemoteOperationError`s (`remoteOperationError`), with git's message, which
    names the files, in place of Leaf's own ("The pull didn't merge, as it would overwrite your changes to …").
  - Before a merge pull, Leaf takes its backup snapshot ("Automatically stash uncommitted changes before merge"), and
    deletes it after a pull that completed, or that failed without changing anything (`IsUnchangedSinceGitAction`, as
    Merge does). It's kept after conflicts, and after a hook that refuses with `MERGE_HEAD` left. A rebase pull takes
    none: git refuses uncommitted changes before it fetches, unless `rebase.autoStash` sets them aside.
- A repository that uses LFS, where git-lfs doesn't work, pulls with `GitCliPullBranchGitAction` instead
  (`RemoteOperationsBackend.pullNeedsBuiltinLfs`), as `git pull` would check out pointer files without a word, or fail
  with `filter.lfs.required` (which `git lfs install` sets), a rebase halfway. It fetches with git, then JGit merges or
  rebases with Leaf's built-in LFS, as its `PullCommand` does after its own fetch, and git never opens an editor.
  - A repository uses LFS when its common git dir has an `lfs` folder, or a root `.gitattributes` names `filter=lfs`:
    the working tree's, or that of the commit pulled as its remote-tracking branch has it (a file that a partial clone
    hasn't downloaded counts as no).
  - What is pulled is what `PullCommand` pulls: the chosen remote branch, or the upstream (`branch.<name>.remote` and
    `.merge`), or the branch of the same name on `origin`. An upstream is fetched with `git fetch <remote>`, which
    updates every remote-tracking branch and marks it for merge in `FETCH_HEAD`. Anything else is fetched by name.
    The commit comes from the `FETCH_HEAD` line marked for merge (`parseFetchHead`), never from a remote-tracking
    branch, which may be stale.
  - The merge message names it with git's own description from `FETCH_HEAD` (`branch 'main' of <url>`), and JGit adds
    ` into <branch>`. `pull.ff` is read as `PullCommand` reads it. An upstream that is a local branch (remote `.`) is
    merged without fetching, and a branch without commits takes the pulled commit.
  - `mergeHasConflicts` and `rebaseHasConflicts` (`remote_operations/PullOutcome.kt`) tell both pulls, JGit's and
    this one, what happened. A merge that would overwrite local changes throws `PullWouldOverwriteException` (JGit
    returns `FAILED`, or throws `CheckoutConflictException` for a fast-forward); it used to count as a pull without
    conflicts. This pull then drops its backup stash, since nothing changed. A rebase's `CONFLICTS` and `FAILED`
    throw it too (local files stopped it, and the branch is as it was); `CONFLICTS` used to count as conflicts.
  - JGit's merge, rebase and checkout run inside `useBuiltinLfs` (JGit's pull, this one, and both clones). It turns on
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
  - **A chosen branch or tag (fork-only, Gitnuro#209, 2026-10-19):** the dialog's "Branch or tag (optional)" field and
    "Only this branch" box reach `ICloneRepositoryGitAction` as `branch` (trimmed, blank is none) and `singleBranch`.
    The box needs a branch to be checked, though the action takes `--single-branch` alone too (the remote's default
    branch only). git gets `--branch=<name>`, in the `=` form so that a name that starts with `-` is a value, and
    `--single-branch` (`cloneArguments`).
    - What git 2.54 does with `--no-checkout`, and what the steps after it rely on: HEAD is `refs/heads/<branch>`, so
      the checkout, `git lfs fetch <remote> <commit>` and the `.gitmodules` check all follow the chosen branch, with
      no change. A tag leaves HEAD detached at the tag's commit (annotated tags too), and the code already took
      `origin` when HEAD has no branch. `--single-branch` sets `remote.origin.fetch` to the branch alone
      (`+refs/heads/x:refs/remotes/origin/x`; for a tag `+refs/tags/v1:refs/tags/v1` and no branch at all), so a later
      fetch, Leaf's too, brings only that. The dialog says so under the box. It limits the superproject only:
      `git submodule update` clones the submodules whole.
    - **A branch the remote lacks:** git exits with 128 and `fatal: Remote branch X not found in upstream origin`,
      having made no folder. `remoteOperationError` turns it into `RemoteOperationError.BranchNotFound` (`branchNotFound`,
      which JGit's `Remote branch 'X' not found in upstream origin` also matches), whose text says the repository has no
      branch or tag named X, then git's line. The existing cleanup leaves a chosen empty folder empty.
    - **JGit (the fallback):** `setBranch(name)` finds a branch, then a tag, and leaves HEAD detached for a tag. For
      one branch, `setCloneAllBranches(false)` alone still clones every branch: it needs `setBranchesToClone` with a
      full ref, and a list with both `refs/heads/x` and `refs/tags/x` fails when one is missing. So with
      `singleBranch`, one `Git.lsRemoteRepository()` call inside the clone's own transport block (the same credentials)
      picks the branch, else the tag, else the remote's HEAD target; a name that is neither fails as JGit's own clone
      does, as `BranchNotFound`.
    - Tests: `GitCliCloneTest` (a branch, trimming, tags, only this branch, only a tag, the default branch alone, a
      missing branch, a name starting with `-`, submodules of the branch, the clone the app runs for git and for JGit),
      `CloneRepositoryGitActionBranchTest` (the same for JGit), `GitCliLfsTest` (git-lfs fetches the branch's commit)
      and `CloneDialogTest` (the field and the box, rendered).
  - **Choose… (fork-only, 2026-10-19, follows Gitnuro#209):** a button beside the Branch field lists the remote's
    branches and tags in a popup under it. It is the only thing that asks the remote: typing in the field, opening the
    dialog and changing the URL never do, and a click asks once.
    - **git** (`GitCliListRemoteRefsGitAction`): `git ls-remote --symref -- <url> HEAD refs/heads/* refs/tags/*`
      through `GitCliRemoteCommand`, so the credential, host key and passphrase dialogs are a clone's, and cancelling
      the coroutine kills git (`GitCliHttpsTest` checks that no git is left). `--heads --tags` would drop the
      `ref: refs/heads/main<TAB>HEAD` line that names the default branch, and sends the `refs/pull/*` that GitHub has
      many of; the patterns do neither. It runs in the temp folder, which is no repository, so the user's config
      (`url.<base>.insteadOf`) applies and a repository's doesn't, and it reports no progress, so nothing reaches a
      tab's processing screen. A URL that looks like an option is a URL (`--`). Failures are `remoteOperationError`'s.
    - **JGit** (`ListRemoteRefsGitAction`, the fallback): one `Git.lsRemoteRepository()` through the transport handler.
      JGit reads HEAD's target from the server's advertisement (checked over `file://`), so the default branch is the
      same as git's. `SelectingListRemoteRefsGitAction` picks like the clone's (the setting, a usable git, the helper).
    - **Model** (`domain/models/RemoteRefs.kt`, `domain/clone/BranchChoices.kt`): `RemoteRefs(defaultBranch, branches,
      tags)` as short names in natural order; the `^{}` lines of annotated tags and every other namespace are left
      out, and the default branch only when the remote has it. `choicesFor(text)` filters what the remote sent, locally:
      trimmed, case-insensitive, anywhere in the name, the default branch first, with where the text matched.
      `remoteHostOf(url)` names who is asked ("Asking github.com…").
    - **View model** (`CloneViewModel.branchChoices`: `Closed`, `Loading(host)`, `Loaded`, `Failed`): `chooseBranch(url)`
      cancels the previous request, and a blank URL is `CloneUiError.EmptyUrl` with no request. `closeBranchChoices`
      cancels the request. Starting a clone, or a URL whose text changed (not a caret move), closes the list, which
      was of the old URL. `pickBranch(name)` fills the field with the caret at its end.
    - **The popup** (layout A of two, decided 2026-10-19; `ui/dialogs/CloneBranchChoices.kt`): a `Popup` under the row
      of the field and its button (`TextInput`'s `end` slot), as wide as the row and at most 280 dp tall. While it asks
      it says "Asking <host> for its branches and tags…" with Cancel. Then it lists BRANCHES and TAGS, the default
      branch marked, with what was typed in bold and "N of M match" under it. A failure is in the dialog's red error
      box with Try again.
      - **The field keeps the focus**, so what is typed filters the list. Hence a non-focusable popup, and rows and
        buttons that pick on a tap (`detectTapGestures`), not a `clickable`, which would take the focus. ↓ and ↑ move
        through the rows, starting from none, and Enter picks the highlighted row only: what was typed may be the whole
        name. Choose… puts the focus in the Branch field (the URL's when it's blank). Tab goes Folder, Branch,
        Choose…, Clone.
      - **Closing:** Esc closes the list before the dialog (`MaterialDialog`'s `onCloseRequested`, which sees Esc
        first), and a press anywhere in the dialog but the Branch row does too: a non-focusable popup gets no
        outside-press events, so an `Initial`-pass `pointerInput` on the dialog's column looks (the list is a layer of
        its own, so presses on it don't come there). Closing the dialog closes the list, which stops git
        (`DisposableEffect`: the view model is the tab's and outlives the dialog).
    - Tests (56): `RemoteRefsTest`, `BranchChoicesTest`, `GitCliListRemoteRefsGitActionTest` (local repositories, the
      default branch, an empty one, a HEAD that points nowhere, 3,000 tags, JGit's list equal to git's, the backend
      that the setting picks, shown by an `insteadOf` that only git reads), `GitCliHttpsTest` (the dialog, the cache,
      a wrong password, a closed dialog, Cancel), `CloneViewModelTest`, `CloneDialogTest` (nothing asks before the
      click, one request per click, the list, the filter, a pick with a real press, the keys, Esc, a press elsewhere,
      Try again, closing the dialog) and `CloneBranchChoicesTest` (the keys and the list's items). 59 mutations of the
      list action, the filter, the view model and the dialog were each caught by a named test.
- **LFS with git-lfs (`GitLfsFetch`):** before JGit checks out a clone, or merges or rebases what
  `GitCliPullBranchGitAction` fetched, Leaf runs `git lfs fetch <remote> <commit>`. JGit's checkout then finds every
  object in `<git dir>/lfs/objects` (git-lfs and JGit use the same layout), so Leaf's built-in client (`app/.../lfs/`)
  downloads nothing. `git pull` needs none of it: git-lfs's filter (`filter-process`) checks the files out.
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
- **The built-in LFS client's server (fork-only `GetLfsUrlSettingGitAction`, `data/git/lfs/`):** `GetLfsUrlGitAction`
  finds it as git-lfs 3.8 does, for a download or an upload (`IGetLfsUrlGitAction`'s `operation`: the JGit push's
  pre-push hook uploads, everything else downloads).
  - The server: `lfs.pushurl` (uploads), then `lfs.url`, then the remote's `remote.<name>.lfspushurl` (uploads), then
    its `remote.<name>.lfsurl`, then its URL (its `pushurl` for an upload) as `<url>.git/info/lfs`, or
    `<url>/info/lfs` when it ends with `.git`. An SSH URL stays as it is, for `git-lfs-authenticate`. A remote with
    none of them, such as `.` (a local upstream) or one that doesn't exist, gets `origin`'s server.
  - Each setting comes from git's config first, the repository's over the user's, then from `.lfsconfig` in the
    working tree, so an `lfs.url` in `.lfsconfig` beats a `remote.<name>.lfsurl` in `.git/config`, and an
    `lfs.pushurl` there beats an `lfs.url` in `.git/config`. Like git-lfs, Leaf ignores `remote.<name>.lfspushurl`
    and `branch.<name>.remote` in `.lfsconfig`.
  - git reads them (`git config --get`, for `includeIf`), and its answer stands: exit code 1 is "not set". JGit's
    config is read only when git can't run, or can't read its config.
  - The remote is the one asked for, or else git-lfs's pick: the current branch's `branch.<name>.remote` (without
    `.merge`, which git-lfs doesn't need), then `remote.lfsdefault`, then the only remote, then `origin`. A detached
    HEAD, as in a rebase, has no branch, even at a branch's commit, where Gitnuro's `GetCurrentBranchGitAction` finds
    that branch. So the conflict cards ask once (`DownloadLfsVersionGitAction`, which had its own `origin` fallback),
    and checkouts without git-lfs find a server with several remotes and no upstream.
  - Credential helpers are asked about that remote's URL (its push URL for an upload), as git-lfs's
    `getCredURLForAPI` does, or about the server when the remote has none.
  - **An empty value counts as not set** (decided 2026-10-15), as git-lfs's documentation has it ("Default blank
    (derive from clone URL)"), so a value in `.lfsconfig` or below it counts. git-lfs 3.8 takes it as the server: an
    empty `lfs.url` leaves it without one, and an empty `remote.<name>.lfsurl` sends it to `origin`'s.
    `GetLfsUrlSettingGitActionTest` checks git-lfs's side too, so it fails if git-lfs changes.
  - Not as git-lfs does: `url.<base>.insteadOf` on `lfs.url` and `remote.<name>.lfsurl`; `FETCH_HEAD`'s URL when
    `origin` has none; `.lfsconfig` from the index or `HEAD` when the working tree has none; the last of several
    `remote.<name>.url` values (JGit takes the first). git-lfs also counts a two-part key such as `remote.pushDefault`
    as a remote named "", so one remote and `remote.pushDefault` leave it without a server; Leaf takes the remote.
  - Until 2026-10-15 a remote asked by name (pushes, the cards' fallback, the tracked remote) got `<url>/info/lfs`,
    uploads read no push setting, and the upstream was looked up by the branch's full ref.
  - Tests: `GetLfsUrlSettingGitActionTest` compares the settings with `git lfs env`. `GetLfsUrlGitActionTest` sets up
    remotes and settings one after another and compares each step with the server git-lfs asks: `git lfs env` for a
    download from each remote, `git lfs push <remote> main` for an upload, `git lfs fetch` for the remote it picks.
    Their URLs point at a port where nothing listens, so git-lfs names the server in its error at once. Both are
    skipped without git-lfs. `LfsPrePushHookTest` runs the pre-push hook of a push through JGit against a JDK
    `HttpServer` that answers 404, and checks which server it asked.
- **The built-in LFS client's downloads (fork-only checks):** `DownloadLfsObjectGitAction` downloads to
  `<oid>.<random>.tmp` next to the object in `lfs/objects`, and renames it into place once its size and SHA-256 are
  the pointer's. Otherwise it returns `LfsError.CorruptDownload`, which fails the checkout. The temporary file is
  removed either way.
  - A dropped connection needs the check: Ktor's CIO engine ends the body without an error. The download used to be
    appended to the object's place, unchecked, and kept there truncated.
  - `LfsSmudgeFilter` downloads again when the object in place has another size than the pointer, as git-lfs does.
  - `LfsNetworkDataSource.downloadObject` streams to the file on `Dispatchers.IO` (`prepareGet().execute`): `get()`
    reads the whole body into memory first. It tells `onProgress` how many bytes it wrote, for the choice cards.
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
  push: a fetch doesn't involve git-lfs, and JGit does a clone's checkout either way. A pull in an LFS repository
  where git-lfs doesn't work takes `GitCliPullBranchGitAction` in place of `git pull` (`pullNeedsBuiltinLfs`, see
  above). A clone has no repository yet, so it asks `useGitCli()` without one.
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

**Index formats that JGit can't read (fork-only, `data/git/repository/IndexExtensions.kt`,
`domain/index/UnsupportedIndex.kt`):** git writes the index split (`git update-index --split-index`,
`core.splitIndex`) or sparse (`index.sparse` with a cone-mode sparse checkout) for large repositories. JGit 7.7 fails
at the first entry it can't read: a split index has entries without a path (`Invalid path: `), a sparse one has folders
(`Invalid path: b/`). So whatever reads the index fails: status, staging, uncommitted diffs, blame, commits,
checkouts, stashes, merges. JGit reads the index before it changes anything, so nothing is left half done. A sparse
checkout with a full index, and `feature.manyFiles` (version 4 without a checksum), are fine.
- `JGit.toGitError` turns a failure with a `CorruptObjectException` in its cause chain into `UnsupportedIndexError`
  when `unsupportedIndex` finds the reason in the index file. `indexExtensions` lists its extensions (gitformat-index
  versions 2 to 4: they come after the entries, so every entry is read past). `link` is split, `sdir` sparse, and
  another one whose signature isn't uppercase, which JGit refuses too, is `UnknownExtension`. The file is read only
  after a failure, so an index that JGit reads costs nothing. `sharedindex.*` files prove nothing: git leaves them
  after `--no-split-index`.
- Status fails with it, so `RepositoryOpenViewModel.unsupportedIndex` follows the status's `DataState.Error`, and
  `UnsupportedIndexBanner` (`ui/status/`) shows an amber strip under the toolbar, with the commands, until a status
  loads, as after converting. "Convert index…" opens `ConvertIndexDialog`, which shows the commands
  (`WorktreeCommands`) and runs them with `RunWorktreeStepsUseCase` (`TaskType.ConvertIndex`). Any other operation's
  `ErrorDialog` gives the same explanation, with the commands and their `cd` for a terminal.
- `conversionSteps`: split is `git update-index --no-split-index`, after `git config core.splitIndex false` when that
  is on (git only warns, and splits again at its next write); sparse is `git sparse-checkout reapply
  --no-sparse-index`, which keeps the patterns and sets `index.sparse=false` in the worktree's config. Both keep the
  staged changes. `UnknownExtension` has no command, and the strip no button.
- What doesn't read the index works: the log, branches, a commit's files and diffs, file history, worktree review.
  `GetDiffEntryFromDiffTypeGitAction` used to read the index for every diff, and `FormatDiffGitAction` goes on without
  submodules for a commit's diff when JGit can't list them, as it does from the index.
- Moving status and staging to the git CLI was the alternative, not built: JGit's commit, checkout, stash, merge and
  discard read the index too.
- Tests: `IndexExtensionsTest` (index files of every version that git 2.54 writes), `UnsupportedIndexTest` (Leaf's
  actions on split and sparse indexes, and the conversions), `UnsupportedIndexBannerTest`.

**Partial clones (fork-only, `data/git/cli/remote/PartialCloneFetch.kt`):** a partial clone (`git clone
--filter=blob:none`) has every commit and tree, but only the files that it checked out, and git downloads the others
from the promisor remote (`remote.<name>.promisor`, or `extensions.partialClone` from older git: `promisorRemotes`)
when it first needs them. JGit can't: it throws `MissingObjectException`, which `JGit.toGitError` turns into
`MissingObjectError`.
- `PartialCloneFetch` runs what git runs to fetch from its promisor remote (`promisor-remote.c`):
  `git -c fetch.negotiationAlgorithm=noop fetch <remote> --no-tags --no-write-fetch-head --recurse-submodules=no
  --filter=blob:none --stdin`, with the objects' ids on stdin (`GitCliRemoteCommand.run`'s `input`), so Leaf's
  credential dialogs apply. JGit's cached `Git` sees git's new pack when it next misses an object. A download that
  fails is `PartialCloneFetchError`, with git's error: offline, git says why ("Could not resolve host").
- Reading (`retryingMissing`): the diff pane (`FormatDiffGitAction`), a commit's files, worktree review, blame, the
  conflict resolver's loading and "Open in the default app" run again after downloading what they missed, up to 10
  times, each for a new object. The first download also takes what the operation reads next: both versions of a
  commit's file (`DiffType.blobIds`), and the versions of a blamed file, with, where it was added, the files that blame
  compares to find a rename (`fileVersions`). Queries show no progress.
- Changing: JGit's checkout deletes files before it writes the new ones, and a missing blob stopped it halfway, with
  HEAD where it was; a hard reset had moved HEAD. So the checkouts (branch, remote branch, commit), a hard reset, the
  interactive rebase, `GitCliPullBranchGitAction`'s merge or rebase, and JGit's merge, cherry-pick, revert and rebase
  (when git can't run them; git downloads what its own need, `git pull`'s too) download first
  (`prefetchCheckout`, `prefetchForMerge`, `prefetchForCherryPick`, `prefetchForRebase`): the blobs where the trees
  involved differ (`blobsThatDiffer`), with the processing screen's progress and Cancel. A failed download is
  `PartialCloneFetchError(nothingChanged = true)`, and nothing changes. Stashes and discards read what was checked out,
  so they aren't covered; nor is JGit's pull, the fallback (see Remote operations, Choice).
- Lists don't read blobs: `GetDiffEntryFromStatusEntryGitAction` and `unmergedDiffEntry` call `git.diff()` with
  `setShowNameAndStatusOnly(true)`. Without it, JGit formats every file's diff into a null stream: selecting a commit
  read all its changed files, in every repository. `GetCommitDiffEntriesGitAction` and `GetBranchComparisonGitAction`
  scan with a `DiffFormatter` that formats nothing, and find renames (`diffWithRenames`): exact ones read no blob, and
  the stages that compare content only look at the blobs that the clone has (see Renames in file lists).
- The diff pane (`ViewDiffResult.Failed`, `DiffLoadFailure`) and file history show a missing object's error in place,
  with Try again (`RepositoryOpenViewModel.retryDiff`); the pane used to close. A commit's file list shows it above the
  list, and a blame that fails opens `ErrorDialog`, where it used to say nothing.
- A treeless clone (`--filter=tree:0`) lacks trees too, which are downloaded one per try, so an operation can give up.
- Tests: `PartialCloneTest` (clones with `--filter=blob:none` over `file://`, from a source with
  `uploadpack.allowFilter`), `DiffLoadFailureTest`.

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
    or asks. With "Cache HTTP credentials in memory" off, it always asks and caches nothing, and so does LFS without a
    helper, as git's remote operations do without Leaf's cache helper. `reset` removes the ones it took from the cache, but only while they are still the URL's cached ones,
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

**Identity and signing settings from git (fork-only, `data/.../author/GitIdentities.kt`,
`data/.../signers/GitSigningConfigs.kt`):** JGit reads no `includeIf` (its parser expands only a section named
`include`), so an identity or signing settings in a conditionally included file, such as a work identity for
`~/work/`, didn't apply to Leaf's commits (Gitnuro#186). Leaf's own commits, tags and stashes ask git instead.
- `GitIdentities.of(gitDir)` runs `git --git-dir=<git dir> -c user.useConfigOnly=true var GIT_AUTHOR_IDENT` and
  `GIT_COMMITTER_IDENT` side by side (about 13 ms each). That's git's own answer: `includeIf` (`gitdir:`, and
  `onbranch:` per worktree), `author.*` and `committer.*`, and `GIT_AUTHOR_NAME` and the like from the login shell.
  `useConfigOnly` makes git refuse rather than make an identity up from the account and the host's name: a refusal
  (`CommandFailed`) means none, and Leaf's dialog asks. Without a usable git, it's null and JGit's identity stays.
- `DoCommitGitAction` sets git's committer, and git's author unless it amends (`withIdentities`), at the same time.
  The identity typed in `CommitAuthorDialog` is the author and the committer: the committer used to be JGit's guess,
  `<user> <<user>@localhost>`. `CreateTagGitAction` takes git's committer as the tagger, `StashChangesGitAction` as
  the stash's person.
  - A resolved cherry-pick keeps the picked commit's author and author date (`cherryPickedAuthor`, from
    `CHERRY_PICK_HEAD`), as `git commit` does, unless it amends. JGit's `CommitCommand` took the committer.
- `LoadAuthorGitAction` puts git's author in `AuthorInfo.gitIdentity`. `identityToUse()` prefers it (the bottom bar,
  the sign-off line), and `isIdentityMissing()`, which decides the dialog (`StatusViewModelExtender.getIdentity`), is
  false whenever git has one.
- `GitSigningConfigs.of(gitDir)` reads `git config --list -z` into a JGit `Config` (`configOf`), wrapped in
  `GitGpgConfig`. `DoCommitGitAction` and `CreateTagGitAction` pass it to JGit's commands (`setGpgConfig`), so
  `commit.gpgSign`, `tag.gpgSign`, `user.signingKey`, `gpg.format` and `gpg.program` come from git's view, and
  `SshProgramSigner` reads `gpg.ssh.*` from `GitGpgConfig.config` (`configOr`).
  - `configOf` writes the entries as config text, so that a key without `=` stays one, which JGit reads as true. It
    leaves out `include` and `includeIf`, whose files git has read.
  - JGit reads an empty value (`key =`) as null, where git reads "", from its own files too.
- Merge in the branch menu, cherry-pick, revert, Rebase and Pull run git (see Merge, cherry-pick, revert and rebase
  with git, and Remote operations). The commits that JGit makes itself, whose commands take no committer (the
  interactive rebase, and pull's merge and rebase and those four when git can't run them), its reflog entries and
  every other setting that JGit reads see the
  files that `includeIf` names through `IncludedConfigRepository`, and hooks are found where git finds them (see
  `JGit.provide`).
- Tests: `CommitIdentityTest`, `GitSigningConfigsTest`, the `includeIf` tests in `CreateTagGitActionTest` and
  `SshProgramSignerRealSshKeygenTest`, and `AuthorInfoTest`. git and JGit read the same user config
  (`IsolatedSystemReader.userConfigFile` as `GIT_CONFIG_GLOBAL`), and Leaf's commit is compared with `git commit`'s.
  `includeIf "gitdir:"` matches the real path, which on macOS starts with `/private` (`canonicalFile`).

**Commit and tag signing (fork-only `data/.../signers/GpgProgramSigner.kt`):** `App.start` registers the signers with
JGit's `Signers`: `GpgProgramSigner` for `openpgp`, the default `gpg.format`, and `SshProgramSigner` for `ssh`. Commits,
merges, rebases and tags all find them through `gpg.format`, and `CreateTagGitAction` leaves `tag.gpgSign` and
`tag.forceSignAnnotated` to JGit's `TagCommand`. Leaf's own commits and tags read these settings through git (see
above).
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
  actions, and the fork-only `@TabScope` on `ObserveRepositoryToRefreshUseCase` (see Refresh). `FileChangesWatcher`
  isn't: each watch gets its own.
- App-scoped: the `JGit` cache, `AppStateManager` (recent repos), settings.

**Scopes:** `TabCoroutineScope` and `TabViewModel.viewModelScope` are both `SupervisorJob() + Dispatchers.Default`.
- Never block their threads: `Dispatchers.Default` has one per core, shared by every tab. Blocking calls go to
  `Dispatchers.IO`, and a loop that blocks for as long as it runs, like the file watcher's (see Refresh), to a view of
  its own, `Dispatchers.IO.limitedParallelism(1)`, which doesn't count against IO's 64 threads.
- Wait for a state with `first { ... }` on its flow, never a loop that reads `.value`: the commit identity dialog's
  loop (`StatusViewModelExtender.getIdentity`, now `awaitAnswer`) kept a core busy while the dialog was open
  (fork-only fix).
- **Tear-down (fork-only):** closing a tab (`AppViewModel.closeTab`) or replacing it (`replaceTab`) calls
  `RepositoryTabViewModel.dispose()`. That clears every view model in `viewModelsMap` and its own (`onClear`, which
  cancels the children of `viewModelScope`), cancels `TabCoroutineScope`, which ends the file watcher's watch and
  closes its watcher, and clears the data.
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
- **Compose state made off the UI thread (fork-only, `viewmodels/ScrollStates.kt`):** a `LazyListState` that a view
  model makes on `Dispatchers.Default` is in the global snapshot, and a frame whose snapshot began earlier can't read
  it ("Reading a state that was created after the snapshot was taken"). A `StateFlow` can hand it to such a frame, and
  the diff pane crashed that way. Make them with `newLazyListState()`, which makes the state inside
  `Snapshot.withMutableSnapshot`, so every snapshot can read it. Any other Compose state that a view model makes off
  the UI thread needs the same. The crash didn't reproduce offscreen, only in a window; `ScrollStatesTest` checks it
  with an older snapshot.

**Data state:** each piece of repository data is a `Flow<DataState<T>>` (Loading/Loaded/Error) in
`InMemoryRepositoryDataRepository`. `RefreshDataUseCase` refreshes it per `DataToRefresh` value. A step that throws
ends as `DataState.Error`, logged (fork-only); it used to stay Loading, without a log line.
- Fork-only: each `update…(repositoryPath) { … }` gets the git dir its block reads, and the result is dropped when
  the tab switched to another worktree before the block ran or while it ran (`writeIfStillShown`, under the same
  lock as `switchWorktree`). Without it, a status refresh that ended after a switch showed the old worktree's files.
- `toUiDataState` keeps the previous data while Loading, so a view shows the old worktree until the new one loads.

**Dialogs** are Navigation3 destinations:
- `sealed interface Screen` in `App.kt`, `entry<Screen.X>` in `ui/AppTab.kt`.
- Opened with `backStack.add(...)`, through an `onNavigate` lambda.
- The Actions list (`QuickActionsDialog`) closes itself before it runs an action, so an action that opens a dialog only
  adds it. Closing afterwards would remove the new dialog, the top of the back stack. Upstream fixed the same bug the
  other way (1cac7a00: its `AppTab` callbacks remove the list, then add theirs). Leaf keeps its own, so its callbacks
  only add: both together close the screen under the list, the tab's repository.
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
    force push, discarding a file or a selection (the Staged and Unstaged menus), a hunk or a line (the diff), and
    resolving conflicts with ours or theirs.
    Discarding a folder has its own `DiscardChangesDialog`. The reset dialog warns when Hard would discard
    uncommitted changes.
  - Discards show what is lost: a hunk's header, the line. A file or a selection in the Staged list loses its unstaged
    changes too, as `DiscardEntriesGitAction` resets the index entry and then checks the file out. Putting back
    removed lines (a line, or a hunk that adds none) or a deleted file loses nothing, so those get no warning.
  - **Delete file on a folder that holds another repository (fork-only, 2026-10-20):** JGit lists such an untracked
    folder as one row (a linked worktree inside the repository, a nested clone). `GetStatusGitAction` sets
    `StatusEntry.nestedRepository` (`nestedRepositoryOf`: a `.git` folder or file in the folder; a `.git` file that
    points into `<common git dir>/worktrees/` is `LINKED_WORKTREE`, anything else `REPOSITORY`). A linked worktree is
    refused: `StatusPane` skips the confirmation and `DeleteFileGitAction` returns `DeleteFileError.LinkedWorktree`
    (checked again from the disk, so a stale selection is refused too), whose `ErrorDialog` points to Remove in the
    Worktrees section. Deleting the folder would leave `.git/worktrees/<id>` behind. Another repository still deletes,
    in a dialog titled "Delete repository" ("Delete repository" button). Tests: `DeleteFileGitActionTest`.
  - Discarding never touches a file with conflicts (`StatusEntry.hasConflicts`), as `git restore` refuses one ("path
    is unmerged"): resetting it took HEAD's version, ours, without saying so, and left a file that ours deleted as an
    untracked file. Its menu has no "Discard file changes", as "Use ours" and "Use theirs" name the side they take.
    A selection's and a folder's discard leave such files as they are, and their dialogs say how many
    (`DiscardedFiles`, from `selectionDiscard` and `folderDiscard`). "Discard N files" counts the files it discards
    (`discardCount`), as every count in these menus counts the files the item acts on. A selection of conflicted files
    only gets no Discard. `DiscardEntriesGitAction` reads the index first and refuses an unmerged path with
    `ConflictError.CantDiscard`, changing nothing, for a status that was stale.
  - Only hunks and lines can stop asking. The diff passes `onStopAsking`, which adds "Don't ask again for hunks and
    lines"; confirming with it checked turns off `AppConfig.ConfirmHunkAndLineDiscards` (`user_prefs.json` key
    `confirm_hunk_and_line_discards`, on by default, Settings → Layout). `DiffPane` reads it through
    `RepositoryOpenViewModel.confirmHunkAndLineDiscards`, which is true until the settings file is read. Discarding
    files always asks. A file that changes while the dialog is open is refused like any stale hunk (`StaleHunkError`,
    see Hunk and line actions).
  - `onStopAsking` is `Screen.ConfirmAction`'s last parameter, so pass `onConfirm` in the parentheses: a trailing
    lambda would be `onStopAsking`, and fail to compile without `onConfirm`.
- **Stage all and Unstage all follow the search (fork-only, 2026-10-20, `ui/status/BulkTarget.kt`):** the header button
  of Staged and Unstaged acts on what the pane shows, as a folder row does. It used to pass `null` entries unless two
  or more files were selected, and `StageAllGitAction` reads `null` as every file, so a search for "parser" staged the
  files that it hid, and one that matched nothing staged everything. Chosen from options with the user.
  - **The rule** (`StatusState.bulkTarget(entryType, selected)`, which `StatusViewModelExtender.applyToAll` and the
    header's label both use): two or more selected files win, hidden ones included (`Selected`, "Stage selected");
    otherwise no search, or a search that matches every file, is `All` ("Stage all"); a search that narrows the list is
    `Matches` ("Stage 3 matches", "Stage 1 match", "Unstage 3 matches"); a search with no match is `NoMatches`: the
    button is greyed ("Stage 0 matches", `allActionEnabled = false` in `FilesChangedHeader`) and does nothing, as
    `null` would mean every file. One selected file counts as no selection, as before.
  - Each pane has its own search. Text in a closed search box narrows nothing (`activeSearchOf`).
  - `StatusStageAllUseCase` now returns its `Job`, so a test can join it.
  - Tests: `BulkTargetTest`, `StageAllHeaderTest` (label and click, rendered), `StagingShownFilesTest` (a `TestTab`:
    `null` stages every file, the shown files stage only those).
- **Discard all (fork-only, 2026-10-19, Gitnuro#224, `domain/sorting/DiscardAll.kt`, `DiscardAllGitAction`):**
  "Discard all changes…" is the only item of a "⋯" menu in the Unstaged header (`HeaderMenuButton`, between the search
  button and Stage all, through `FilesChangedHeader.moreItems`; the Staged header has none). It sits in a menu, not
  beside Stage all, so that a click on the most used button can't reach it, and it always asks. Chosen from three
  placements (a second header button, this menu, a split button), with the user, on 2026-10-19.
  - **It never deleted a new file before, and still doesn't by default.** `DiscardEntriesGitAction` checks paths out,
    which does nothing for an untracked file (probed 2026-10-19: a selection with new files left them, and discarding a
    staged new file only unstaged it), and the folder discard says "N new files are kept". Deleting is "Delete file".
    So the dialog's box "Also delete N new files" is the one new way to lose files. It starts unticked every time
    (`ConfirmOption`, never remembered), changes the warning ("Neither the changes nor the 9 new files can be
    restored") and the button ("Discard and delete"), and with only new files and the box unticked the button is
    disabled.
  - **What it acts on:** the files that the list shows (`StatusState.entriesShown`): all of them, or only the search
    matches while the box is open and not blank, and the dialog says so ("…to 3 files matching "parser""). Stage all
    follows the search too since 2026-10-20 (see Stage all and Unstage all follow the search). The selection is
    ignored here, as it has its own "Discard N files".
  - **The plan** (`allDiscard()`, `DiscardAllPlan`): `restored` (tracked files changed or deleted), `newFiles`,
    `keptConflicted` and `keptSubmodules`. Every entry is in exactly one list. The menu says "Nothing to discard"
    when `hasWork` is false.
  - **The dialog** is `ConfirmActionDialog` with `ConfirmableAction.DiscardAll(plan, search, deleteNewFiles)`. The
    box is a `ConfirmOption`, a state holder inside the action, so that its value reaches the confirm lambda without
    changing `Screen.ConfirmAction`'s callers: `StatusPane` reads `action.deleteNewFiles.isChecked` when it confirms.
    It counts the files, names the files with conflicts in the monospace font (five, then "and N more") and counts the
    submodules that are left. No "don't ask again": only hunks and lines get that.
  - **The action** is one task (`TaskType.DiscardAllFiles`, one refresh, also after a failure). It reads the status
    again and acts only on the entries that it still lists as the dialog counted them, as `StageAllGitAction` does,
    because an agent may work in the worktree while the dialog is open. A file that was staged, committed, changed
    kind or conflicted since is left alone, and so is every file that appeared after. It restores by explicit paths
    (never `checkout .` or `reset --hard`) through `DiscardEntriesGitAction`, which refuses files with conflicts, and
    deletes the new files only after that went well.
  - **Deleting a new file** (`deleteNewFile`): a plain file or a link, deleted as a link, never a folder. An untracked
    entry that is a folder holds another repository, such as a linked worktree inside this one (agents' tools make
    them), and the files of a folder with new files in it are listed one by one. Then it removes the folders that
    this leaves empty, as `git clean -fd` does, and stops at one that has anything else in it, an ignored file
    included. Ignored files are never listed, so never deleted. The check that a file's folder is inside the working
    tree is defence only: the fresh status never lists a file behind a link.
  - **Submodules** (found by the tests): JGit's checkout of a submodule whose commit moved throws `MissingObject`
    ("Missing unknown …"), and it failed the whole discard, folder and selection discards included. git's own
    `checkout -- .` leaves submodules alone. `DiscardEntriesGitAction` skips gitlinks now, and the status flags them
    (`StatusEntry.isSubmodule`, from `gitlinkPaths`, which reads the index only when a `.gitmodules` file exists), so
    "Discard all" counts them as left ("1 submodule that moved on is left as it is").
  - Tests: `DiscardAllTest` (domain), `DiscardAllGitActionTest` (temp repos, with the result compared with `git
    checkout -- .` and `git clean -fd` on a twin), `DiscardingAllTest` (a `TestTab`), `DiscardAllMenuTest` (the
    header, rendered and clicked), the dialog cases in `ConfirmActionDialogTest`, and two in
    `DiscardEntriesGitActionTest`.

## Repository tabs

**Opening a tab:**
- Paths in: `App.start` restores tabs from prefs (`AppViewModel.loadPersistedTabs`) and handles the CLI argument;
  the Welcome page (picker or recent repos) calls `RepositoryTabViewModel.openRepository`; the menu's "open another
  repository" replaces the current tab; "Open in new tab" on a worktree (fork-only, see Identity) selects or adds
  one.
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
- Fork-only: a path that is a removed linked worktree's git dir opens its repository instead
  (`repositoryOfRemovedWorktree`): git pruned the git dir, or the worktree's folder is gone. It's found from the path
  alone, as git keeps those git dirs in `<common git dir>/worktrees/<id>`. That's how a tab restored on a worktree
  that an agent removed while Leaf was closed opens.

**State per tab:**
- `InMemoryRepositoryDataRepository`: status, local branches, current branch, tags, remotes, log, stashes,
  submodules, selection state, repo state, rebase state, author, persisted commit message.
- `InMemoryRepositoryStateRepository`: current task, completed tasks, last-operation timestamp, refresh trigger.
- UI state is mostly in one large `RepositoryOpenViewModel`: side panel, log, status, diff.

**Keys:** the tab's shortcuts are an `onPreviewKeyEvent` on `RepositoryOpenPage`'s column, which sees every key
before the views inside it and consumes the ones it matches. So a view's own Esc handler there never runs (upstream's
`FileHistory` and `Blame` have one), and a view that should close on Esc goes in the tab's EXIT branch.
- Fork-only: that branch closes file history, then an expanded blame, then the last closeable view (`CloseableView`:
  the diff and the searches, in the order they opened).
- Keys reach the tab's handler only while something in it has the focus. File history and an expanded blame take it
  while shown, so closing either, with Esc or its X (the `onClose` in `RepoContent` and `MainContentView`), gives it
  back to the tab (fork-only), as closing the diff does. Selecting a file's diff, which minimizes blame, needs
  nothing: the click focuses the file's row.
- A view that covers others is checked there by what is shown, not added to `CloseableView`: the log search adds
  itself again whenever its results change, and would then come before it.

**Identity and persistence:**
- Tabs are identified by object, not by path. Opening a repository from the Welcome page or the CLI never looks for
  a tab that has it.
- **Switching worktrees (fork-only, Phase 2b, `docs/fork/worktree-switching.md`):** a tab stands for a repository
  and switches between its worktrees in place, as a checkout switches branches. `SwitchWorktreeUseCase(folder)` runs
  as a foreground task (`TaskType.SwitchWorktree`, no toast):
  1. opens the folder (`IOpenRepositoryGitAction`); a failure leaves the tab where it was, with `ErrorDialog`. The
     tab's own worktree changes nothing;
  2. stops the file watcher's watch (`ObserveRepositoryToRefreshUseCase.stop`);
  3. `RepositoryDataRepository.switchWorktree(gitDir)` sets what belongs to a worktree to Loading (status, current
     branch, repository state, interactive rebase, draft messages, submodules), then `Open(gitDir)`. The shared data
     (branches, tags, remotes, stashes, log) stays on screen until the refresh replaces it;
  4. marks the new worktree as the tab's in the worktree list (`withCurrentWorktree`), so the picker, the Worktrees
     section and the branch badges follow right away rather than after git listed every worktree again;
  5. watches the new worktree, refreshes ALL, and waits until the status and the repository state are loaded (10 s
     at most): until then the status pane shows the old worktree's files and merge or rebase buttons, and an action
     on them would run in the new worktree.

  The tab keeps its `TabComponent` and view models. Every git action reads the git dir from the selection state when
  it runs (`UseCaseExecutor.executeTask`), so they follow. What did keep the worktree:
  - the file watcher (see Refresh) and refreshes already running (see Data state);
  - the draft commit message: it's saved a second after typing, so `persistMessage` notes the git dir when typed and
    `PersistCommitMessageUseCase` takes it. A switch leaves the waiting save to finish for the old worktree;
  - view state, reset on `worktreeSwitches()` (`RepositorySelectionState.kt`, each `Open` that follows an `Open` of
    another git dir): `StatusViewModelExtender` loads the new worktree's messages into the fields, even when they
    weren't empty, turns amend off and clears Staged's and Unstaged's collapsed folders; `RepositoryOpenViewModel`
    closes the diff of an uncommitted file, blame and file history. The selected commit, the log's scroll position
    and search, and the side panel stay;
  - the tab: `RepositoryTabViewModel` calls `AppViewModel.worktreeSwitched`, which saves the tabs (the name may not
    change, and `App.kt` saves on a name change) and closes the old git dir's `Git` unless another tab has it open.

  Entry points: a double-click on a Worktrees row, on a local branch that another worktree uses (side panel and log),
  "Switch to worktree" in those menus, the checkout refusal's button (`RepositoryTabViewModel.switchToWorktree`), and
  the toolbar's picker. A switch happens even when another tab shows that worktree.
- **A tab whose worktree was removed (fork-only):** `FollowRemovedWorktreeUseCase`, started by
  `RepositoryOpenViewModel`, checks each time the worktree list is read. When `repositoryOfRemovedWorktree` says that
  the tab's git dir or its folder is gone, the tab switches to the main worktree (`TaskType.FollowRemovedWorktree`,
  whose toast says why). The removal itself triggers the read: the file watcher watches the tab's git dir and
  `<common git dir>/worktrees`, and the list fails once the git dir is gone, or lists the worktree as prunable once
  its folder is. Another tab, a terminal or an agent may have removed it.
- **Opening a worktree in a new tab (fork-only):** "Open in new tab" in a worktree's menu calls
  `AppViewModel.selectOrOpenTab(folder)`, which selects the first tab with the same git dir, or adds a tab at the end.
  `indexOfRepository` (`domain/worktrees/RepositoryPaths.kt`) compares real paths of git dirs: `gitDirOf` turns a
  working tree into its `.git` folder or the git dir its `.git` file points to (absolute or relative), and keeps a git
  dir as it is. A tab gives `pathToPersist`, so an open tab gives its git dir and one that hasn't loaded the path it
  was created with.
- **Tab name and tooltip:** `RepositoryTabViewModel.repositoryPath` (the tooltip) is `workTreeOf` the git dir: for a
  linked worktree's git dir, the folder that its `gitdir` file names, not `<common git dir>/worktrees/<name>`;
  otherwise the git dir without `/.git`, as before. "Open another repository" compares this path with the working
  tree, so it replaces a linked worktree's tab too. The name comes from `tabNamesOf` (fork-only): a linked worktree's
  tab is named after its repository, the main worktree's folder (a bare repository's without `.git`), and
  `TabInformationProvider.worktreeName` is the worktree's folder, which the tab shows lighter after a `·`, as does the
  window title. Other tabs are named after their working tree's folder, as before.
- Persisted keys in the prefs node (`LeafConfig`, see Name): `latestRepositoriesTabsOpened` (JSON),
  `latestRepositoryTabSelected`, and
  `lastOpenedRepositoriesList`.
- What is saved: open tabs as their git dir (`Open.path`), and tabs that haven't loaded yet as the path they were
  created with (`RepositoryTabViewModel.initialPath`). Tabs on the welcome page (`None`) are skipped.
- Never save `repositoryPath.value`. It is started lazily and stays `null` until something collects it, which tabs
  scrolled out of the tab bar never do. `loadPersistedTabs` skips a `null` in the saved list (upstream's 2040386b), so
  that tab is lost; it used to throw at startup.

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
- Tooltips: `ui/components/tooltip/DelayedTooltip.kt` and `InstantTooltip.kt`. Fork-only (`TooltipLayers.kt`): a
  tooltip shows only in the topmost layer, and a press, left or right, hides an element's tooltip until the pointer
  leaves it. `MaterialDialog` is a `TooltipLayer`: a dialog takes the pointer only inside its box, so the rows beneath
  it showed their tooltips. Menus need nothing: they're focusable popups, which keep the pointer from what's beneath
  them (`TooltipLayersTest` checks both). A view that covers the content without a focusable popup should be a layer.
- Context menus: `ui/context_menu/*ContextMenu.kt`. `branchContextMenuItems` is shared with the log's `BranchChip`.

**Branches:**
- Model: `domain/models/Branch.kt` (`hash`, full ref `name`, `isLocal`, `trackingBranch`). No ahead/behind fields.
  - `trackingBranch` is `branch.<name>.remote` and `.merge`, which `refreshBranches` reads for each local branch
    (`GetTrackingBranchUseCase`, upstream's 4bc8b17d). Elsewhere it's null, Leaf's default (upstream has none). It's
    part of a branch's equality, so compare branches from different sources with `isSameBranch` or by name.
- Loaded by `GetBranchesGitAction` (`git.branchList()`) via `RefreshDataUseCase.refreshBranches`.
- Double-clicking a local branch checks it out immediately. When another worktree uses it, it switches the tab to that
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
  gets a folder icon, plus the worktree's folder name unless it repeats the branch's last part, or the branch is
  `worktree-<name>` (`nameNextTo`), as agents' folders do. Its tooltip says how the worktree uses the branch and what
  that rules out.
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
  "missing" for a prunable worktree, whose row is greyed out, or "idle 3w" (see Stale markers). The main worktree has
  the `source` icon.
- Line 2: the branch, or "rebasing x", "bisecting from x", "detached at abc1234"; a dot for uncommitted changes (the
  conflict color when there are conflicts); and `↑ahead ↓behind` versus the base branch, or "merged".
- The tooltip (`worktreeTooltip`) has the full path, the change counts, the comparison to the base and to the
  upstream, the lock reason, and for a prunable worktree git's reason and `git worktree prune`.
- A click selects the worktree's commit in the log (its branch when it's on one), and scrolls the log to it. A
  double-click switches the tab to the worktree (see Identity and persistence), as does "Switch to worktree" in its
  menu (`worktreeContextMenuItems`), followed by "Open in new tab". The menu starts with "Review changes" for a
  worktree on a branch other than the base (see Reviewing a worktree), then "Squash-merge into main…" and "Merge into
  main…" (see Merging a worktree into the base), and "Rebase onto main…" (see Rebasing a worktree onto the base).
  Switching and opening in a new tab aren't offered for the tab's own worktree, a prunable one or a bare repository's
  (`Worktree.canSwitchTo`).
- A linked worktree's menu then has, between separators, Lock… or Unlock, Remove…, Discard…, "Clean up merged
  worktrees…" when its work is in the base and something can be removed (see Cleaning up merged worktrees), and "Prune
  missing worktrees…" when its folder is missing (see Worktree actions). `WorktreeActions` carries their callbacks from
  `SidePanel`, and the header's "+" (shown on hover, as in Remotes), which creates a worktree. Every menu ends with
  "Copy path".
- The side panel's filter matches the folder name, the path, the branches and the origin's tag. When git can't list
  the worktrees, one line says so, with the error in its tooltip.
- The row's two lines are `WorktreeRowLines`, which the toolbar's picker shows too.
- The header's button (`WorktreesMenuButton`, the sort button's `SortMenuButton` with the `more_horiz` icon, ⋯) is the
  section's menu. It's muted while the base is automatic, and shows the chosen branch's name otherwise. Its menu shows
  what Automatic picks, the chosen branch (with "Not found" when it's missing), a note pointing to the branch menus,
  and "Clean up merged worktrees…" with how many can go, while any can (`WorktreesState.cleanupCount`).

**Worktree picker (fork-only, `ui/WorktreePicker.kt`):** in the toolbar, right of Open, only while the repository
has more than one worktree (`worktreePickerRows`, `RepositoryOpenViewModel.worktreePickerState`).
- The button shows the tab's worktree (the row that `isCurrent`) and what it has checked out, with a chevron. Nothing
  shows until the list has the tab's worktree.
- Its list has every worktree with the side panel's two lines and tooltip, the tab's one checked. A click, or Enter
  after the arrow keys, switches the tab and closes the list; Esc closes it. A worktree the tab can't switch to is
  greyed out. It's below the button, or above when there's no room (`BelowLeftAlignedPositionProvider`). Its last
  row, "New worktree…", opens the new worktree dialog.

**Origin labels (fork-only, Phase 4.1, `docs/fork/agent-awareness.md`):** a linked worktree that an agent's tool made
gets a small tag after its folder name (`AgentTag`: `Claude`, `Codex`, `Cursor`, …), in the side panel and the picker,
and on its branch's badge in place of a worktree name that repeats the branch.
- `WorktreeList.withOrigins` (`domain/worktrees/WorktreeOrigins.kt`) sets `WorktreeInfo.origin`: the user's rules
  first (Settings → Worktrees → "Worktree labels", `AppConfig.WorktreeLabels`, `worktree_labels` in `user_prefs.json`,
  `WorktreeLabelsSetting`), then the marker in the worktree's git dir (`Worktree.agentMarker`:
  `claude-desktop-worktree`, `CLAUDE_BASE`, `codex-thread.json`, which `GetWorktreesGitAction` reads), then
  `BUILT_IN_WORKTREE_RULES`. The main worktree gets none. `RepositoryOpenViewModel` applies it to the tab's list on
  `Dispatchers.IO`, so a rule applies as soon as it's typed.
- `folderPatternRegex`: `*`, `?`, `**`, `~/`, `<repo>`, `<repo-name>`. A pattern that starts with `**` matches
  anywhere, one without a start is in the repository, case is ignored, and paths match as git gives them or as real
  paths.
- Claude Code locks the worktree of a running session with `claude session <name> (pid <n> start <time>)`, and keeps
  the lock of a `-p` run after it ends. `agentSessionLock` (`data/.../worktrees/AgentWorktrees.kt`) checks the pid and
  its start time, as Claude Code does, into `Worktree.agentLock`. The tooltip and the Remove dialog then say whether
  the session still runs, in place of the raw reason.
- The filter matches the tag. The tooltip says who made the worktree and how Leaf knows.

**Stale markers (fork-only, Phase 4.3a, `docs/fork/agent-awareness.md`):** a linked worktree whose work is in the base
shows "merged", in the added-file green, in place of `↑ ↓`. One in which nothing changed for some days shows "idle 3w",
in amber, in place of its commit's age (darker on a light background, where the conflict color is hard to read as
text). The tooltip says how the work got there ("Its changes are in main (squash-merged)") and for how many days
nothing changed.
- **Which worktrees:** "merged" only for a linked worktree on a branch other than the base and the base's local branch
  (`branchToMarkMerged`, `domain/worktrees/StaleWorktrees.kt`). Detached, rebasing and bisecting worktrees get none.
  "idle" for linked worktrees whose folder exists.
- **Merged** (`GetMergedIntoBaseGitAction`, `WorktreeInfo.merged`):
  - With commits that the base doesn't have: when `git merge-tree --write-tree` gives the base's tree
    (`mergeChangesNothing`, shared with `GetBranchMergedGitAction`), as after a squash merge (`MergedIntoBase.CHANGES`).
    The answer is kept per (base commit, branch commit) for the tab, so merge-tree runs once per pair, not on every
    refresh.
  - Without (`COMMITS`): only when the branch had work of its own that it still has. A fresh worktree is in the base by
    ancestry too. "Rebase onto main" and an agent's `git merge main` move a fresh branch to the base's commits, so
    having moved isn't enough either.
  - Work of its own comes from the branch's reflog (`commitsOfItsOwn`): a commit made on it (`commit…`, `cherry-pick`,
    `revert`, `am`), or a rebase that kept commits on top of the commit it rebased onto. One such commit must be in the
    branch, as a reset or a rebase leaves the older ones behind. Each check walks back to where the commit and the
    branch meet, kept per (commit, branch commit), 50 commits at most.
  - Without a reflog: Claude Code's `CLAUDE_BASE` (`Worktree.startCommit`), when the branch moved from it.
- **Idle** (`WorktreeInfo.idleSince`): 3.1's last change (`GetWorktreeActivityGitAction`), read against a week ago
  (`WorktreesUntouchedDays.MIN_DAYS`). With `notBefore`, a git dir whose `index`, `HEAD` or `logs/HEAD` changed since
  is enough, and `git status` runs only for the others.
  - `RepositoryOpenViewModel` applies the setting (`withIdle`), so a change in Settings shows at once. The setting is
    Settings → Worktrees → "Mark worktrees untouched for": never, 7, 14 (the default), 30 or 90 days
    (`AppConfig.WorktreesUntouchedDays`, `worktrees_untouched_days`). Fewer days in the settings file count as 7.

**Cleaning up merged worktrees (fork-only, Phase 4.3b, `CleanUpWorktreesDialog`, `WorktreeCleanupSteps.kt`):** "Clean up
merged worktrees…" removes, in one task, the linked worktrees whose work is in the base and the ones whose folder is
missing, and their branches. It's in the header's menu, in a merged row's menu after Discard, and in the Actions list,
while any can be removed (`WorktreeList.cleanupCount`: merged or missing, not in use by an agent's running session).
Untouched worktrees that aren't merged aren't offered: removing them loses commits.
- `GetWorktreeCleanupUseCase` reads the worktrees again when the dialog opens (`GetWorktreesInfoUseCase`, so
  "merged" is 4.3a's), and the last change of each one offered (`RECENT_CHANGE_MS`, 5 minutes, which the other
  worktree dialogs' notice shares). The view model adds the tags (`withOrigins`).
- `WorktreeCleanup.candidates` lists them (`CleanupCandidate`), checked first, then the ones to look at, then the ones
  in use, each in git's order. Checked when the dialog opens (`isCheckedAtFirst`): no changes, no change in the last 5
  minutes, and no lock but an agent's session that has ended (`claude -p` keeps its lock after it ends). Changes and
  other locks are unchecked, with what checking does in amber ("3 uncommitted changes. Checking it deletes them"). A
  worktree locked by a running session has no checkbox.
- The list and the commands scroll past 300 and 160 dp (`WorktreeCommands`' `maxHeight`), each with a scrollbar inside
  its right edge, the two in line (`ScrollingBox` in `WorktreeDialogParts.kt`, a layout of its own: Leaf's
  `ScrollableColumn` fills the height it's given, and Compose's scrollbar throws when the dialog asks its intrinsic
  size).
- **Steps** (`cleanupSteps`), from the main worktree, per worktree: `git worktree unlock` when locked,
  `git worktree remove` (`--force` when it had changes, or they couldn't be read), then `git branch -D` with "Also
  delete their branches" (on by default). `SwitchTab` to the main worktree first when the tab shows one that goes.
  - A missing worktree gets its own `git worktree remove`, which git 2.54 allows, rather than `git worktree prune`,
    which would also remove the missing ones left unchecked. git refuses `branch -D` for a branch that a missing
    worktree still has, so the branch goes after its worktree.
  - Only a branch whose work is in the base is deleted (`CleanupCandidate.branch`): a missing worktree's branch with
    commits of its own stays, and its row says so.
  - A worktree that was clean when the dialog read it gets no `--force`, so git refuses it, and the steps stop, when
    an agent changed something since.

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
  is only read, as that setting's default until it's saved. A renamed file's row (commit and review lists) has a
  `← old name` hint after its name (see Renames in file lists).
- `combineStatusState` builds the two panes' lists (`StatusLists`: entries, rows, searches, view settings) in a
  `combine` of their own. The commit message, selection and the rest join them afterwards, so a letter typed in the
  commit message no longer sorts and groups every changed file again (`StatusStateTest`).
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
  - `ImageComposeScene.render()` has a fixed clock by default (`nanoTime = 0`), so animations never advance: a state
    change shows in the semantics at once, but the screenshot keeps the old pixels of an `AnimatedVisibility` or a
    check box. Pass `System.nanoTime()` and let a dozen frames run with a `delay` between them before encoding the
    PNG (found with the Clone dialog's "Only this branch" note, 2026-10-19).

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

**Ref chips (fork-only, `ui/log/LogRefChips.kt`, `domain/log/LogRefs.kt`):** a commit's branches and tags, at the start
of its Message cell.
- Order (`logRefs`): the current branch (or a detached HEAD), local branches that another worktree uses, other local
  branches, remote branches, then tags, by name within each (`naturalCompare`). IntelliJ and Git Graph also put the
  current branch first and tags last. Before, tags came first and the current branch had no place of its own.
- A remote branch with the name of a local branch on the same commit shares that branch's chip
  (`LogRef.LocalBranch.remotes`), as in IntelliJ, Git Graph and Fork: a cloud mark whose tooltip names it.
  - Upstream joins a local branch's chip with the branch it tracks (4bc8b17d: `trackingRemoteBranch`, a gold cloud
    with its own menu). Leaf keeps this rule, and `BranchChip` takes `sharedRemotes` in its place.
  - The chip's menu is the local branch's, a separator, then the remote branch's items (`sharedChipRemoteItems`):
    push, pull, delete, compare worktrees, copy, each naming the remote branch.
  - Checkout, merge and rebase are left out there: at the same commit they do what the local branch's items do.
- The chips take at most 80% of the Message column (`REF_CHIPS_SHARE`, 60% at first), and the message keeps 64 dp
  whatever the share (`MIN_MESSAGE_WIDTH`). A chip takes at most 250 dp: a longer name ends in "…", with the whole name
  in its tooltip (`Chip`), and the marks after it keep their room.
  - What doesn't fit goes into a +N chip (`fitChips`).
  - When not even the first chip fits next to +N, it still shows whole, or cut down to 120 dp at least, as long as the
    message keeps 64 dp. Otherwise +N stands for every ref.
- +N doesn't take its click, so the click also selects the commit. It opens a popup (`AllRefsPopup`) with every ref as
  a chip of its own, grouped as branches, remote branches and tags, which double-click and right-click as in the row.
  - An action in it closes it first (`LogRefActions.dismissingFirst`), so a dialog it opens isn't behind it.
  - Esc and a click outside close it too.
- A remote branch's chip has a cloud icon, on its lane's colour. Upstream's is on a fixed gold (`0xFFbd9b46`), which
  Leaf leaves out. The current branch's marker grows and shrinks on checkout (upstream's `animateFloatAsState`).
- `branchContextMenuItems` and `BranchChip` take callbacks that get the branch, as upstream's do since 4bc8b17d.
- `LogRefChips` is a `SubcomposeLayout`: every chip is composed and measured, and only the ones that fit are placed.
  The others are still in the semantics tree, so `LogRefChipsTest` checks `layoutInfo.isPlaced` up the parents.
- The Message cell is a `Row` with `weight(1f)`, so the columns after it keep their widths. Before, the chips were
  measured first, and a row with many of them squeezed the message, then Author and Date, to nothing.

**The log under the diff pane (upstream's b2f9188f):** `MainContentView` keeps the log composed while the diff pane
shows over it (`Modifier.focusable(!showDiff)`), so closing a diff doesn't compose the log again.
- Compose hit-tests the sibling below where nothing in the top one takes the pointer. So the diff pane is wrapped in
  `Modifier.coveringWhatIsBelow()` (fork-only, `ui/components/`): the diff's header has a background and no pointer
  input, so without it a click on its empty parts would select a log row under it, and hovering would show that row's
  tooltips (`CoveringWhatIsBelowTest` shows it for any view over another).
- The log scrolls to a selected branch or tag, and to a selected commit only when `SelectedItem.CommitItem.scrollToItem`
  says so: a stash from the side panel, a detached worktree's row. A commit picked in the log, blame or the rebase list
  doesn't scroll it. `LogAction.CommitSelected` says whether the commit is a stash, which the side panel highlights.
- `scrollToCommit` doesn't scroll when the commit's line is already on screen. A line's key starts with its commit's
  hash (fork-only fix: upstream looked for the commit's message, which no key has).

**File history's selected commit (fork-only, `ui/FileHistorySelection.kt`, `viewmodels/FileHistoryCommits.kt`):**
`HistoryViewModel.selectedCommit` is the commit whose diff is shown. The list highlights its row
(`backgroundSelected`, as the log does), and `FileHistoryCommitStrip` above the diff names it: subject, short hash,
author, date and time, and "3 of 12".
- File history opens on the newest commit. ↑ and ↓ (`FileHistory`'s own `onKeyEvent`, which the tab's shortcuts let
  through) and the strip's chevrons call `selectAdjacentCommit`. The list keeps the row in view (`keepInView`, as the
  status pane's lists do), and a click on "3 of 12" scrolls it back to the middle (`showInMiddle`). Not a click on the
  hash: elsewhere (`CommitChanges`) that copies it.
- `selectCommit` cancels the previous commit's load and waits for it to end, so the last commit selected is the diff
  shown however fast the keys come. `GetDiffUseCase` turns a cancellation into `DiffNotFound`, so the load checks
  `ensureActive()` before it writes, or the diff would go blank until the next one loads. Another commit's diff starts
  at its top, as in the diff pane.

**Log search (fork-only changes, 2026-10-20):** the bar is `SearchFilter` (`ui/log/Log.kt`), over
`RepositoryOpenViewModel.logSearchFilterResults`: `LogSearch.SearchResults(commits, index, totalCount, hasTerm)`, where
`index` is the counter's number, starting at 1, and 0 means no result.
- **What builds it:** typing (`searchLog`; the newest term wins). It searches the loaded commits only, and
  a refresh doesn't run it again, so the results keep the commits of the log as it was when the term was typed. An empty
  field is `hasTerm = false`: `matchingCommits` is then null and no row is dimmed. An empty list would make every row a
  non-match, and with the dim below the whole log faded as the bar opened.
- **Wrap-around:** `steppedSearchIndex` (`domain/log/SearchNavigation.kt`) gives the result after a step: next from the
  last goes to the first, previous from the first to the last, one result stays, none is 0, and an index outside the
  results counts as before the first (next) or after the last (previous). The down button and Enter step forward, the
  up button and Shift+Enter (`KeybindingOption.SEARCH_PREVIOUS_RESULT`) back, all through `stepSearchResult`.
  - It writes with `MutableStateFlow.update`, so a step can't put an older list back over the results of a search typed
    meanwhile. That is by construction: a racing test can't tell it from a plain read and write, as the window is too
    narrow (tried).
  - It scrolls to the result even when the index didn't change, so with one result Enter brings the log back to it. With
    no result it does nothing.
- **A step scrolls, it doesn't select** (decided 2026-10-20: "mark only"). The commit pane stays on the user's own
  selection. `scrollToCommit` doesn't scroll when the row is on screen, so the mark below is the only sign of which
  result is current. A click on a match (`selectCommit`) moves the counter to it.
- **Rows** (`ui/log/SearchMatchStyle.kt`): a non-match fades as a whole (`dimmedWhenNotMatching`: message, ref chips,
  avatars, author and date), to 45% in the dark theme and 55% in the light one, which leaves its text at about 4.5:1 and
  3.5:1. The graph isn't part of it, so its lines stay continuous. The old dim only changed the message and names to
  `onBackgroundSecondary`: in the dark theme 11.8:1 against a match's 19.2:1, too close to tell apart. The focused
  match (`SearchResults.focusedCommit`, the counter's) has a 2 dp outline in the matches' bar colour
  (`outlinedWhenFocusedMatch`), which keeps the selection's fill visible inside it. Every match keeps its 4 dp bar.
- Tests: `SearchNavigationTest` (domain), `LogSearchTest`, and `SearchMatchStyleTest` (pixels, both themes). The view
  model and the real `Log` ran against a demo repository through the Dagger graph in a temporary harness (wrap both
  ways, one result, none, an empty field, a click, 3,200 steps racing 150 searches, renders in both themes).

## Refresh

**The watcher (fork-only, `data/.../git/FileChangesWatcher.kt`):** the JDK's `WatchService`, since 2026-10-15. Gitnuro
moved the watcher to Rust (notify) in 2023 because Java's polled on macOS. JetBrains' JDK, which runs Leaf (packaged,
and in dev runs from IntelliJ's runtime), watches with FSEvents there (`sun.nio.fs.MacOSXWatchService`) and reports a
change about 200 ms after it. Linux uses inotify, Windows ReadDirectoryChangesW. Other JDKs poll on macOS.
- `observeEvents()` is a **blocking** loop (`poll` with a 500 ms timeout, `BATCH_TIME_MS`) on a single-thread view of
  `Dispatchers.IO` per watch (`flowOn`). It used to run on the tab's `Dispatchers.Default`, one thread per loaded tab,
  so with as many tabs as cores nothing else on Default ran.
- It batches created, modified and deleted paths, and reports them once 500 ms pass without events, or every 500 ms
  while they keep coming. A path created and deleted within one batch (a temporary file) is left out. JBR itself
  delivers events about every 500 ms.
- `FileChanged` and `FileType` are in `domain/models/FileChanged.kt` (they were uniffi records). `DIRECTORY` is a
  folder created, or a watched folder deleted: the watch checks the disk, or its keys, since `WatchService` doesn't say.
- A folder's own "modified" event is left out: its time changes with its entries, and JBR reports that when it lists
  the folder above (Windows too). What changed in it comes from its own registration, if it has one. Found in a dev
  run: after a commit, the next change in the working tree's root reported `.git` too, which refreshed ALL.
- **Recursive folders:** one registration with `ExtendedWatchEventModifier.FILE_TREE` where the JDK has it (JBR's macOS
  watcher, Windows). Elsewhere (Linux, and macOS JDKs that poll) each folder has a registration, and the watch adds
  the folders created there itself. Files written into a new folder before its registration go unreported, but the
  folder's creation is a change. A folder under a `FILE_TREE` registration isn't registered again on its own: JBR
  would drop `FILE_TREE`, and the use case does that to `worktrees` once git creates it.
- **`OVERFLOW`:** the JDK keeps 512 events per folder until they're taken, then drops them, and JBR does it for any
  large change in one folder at once (600 files written together). The watch reports `WatcherEvent.ChangesLost`, and
  the use case watches the working tree's folders again, and refreshes ALL unless a task runs or just ran.
- Each watch has its own watcher (`ObserveRepositoryToRefreshUseCase` takes a `Provider`), which closes its watch
  service when the watch ends. A cancelled watch stops within 500 ms; `close()` ends it at once. A folder that can't
  be watched (missing, or past Linux's inotify limits) is logged, and the watch goes on.
- Tests: `FileChangesWatcherTest` runs it on the machine's JDK, with and without `FILE_TREE` (`watchesTrees`), and
  `RefreshingOnChangesTest` runs a `TestTab` with it: an edit, a commit from a terminal, a change in another worktree.

**`domain/.../usecases/ObserveRepositoryToRefreshUseCase.kt`:**
- What it watches:
  - the working-tree root and the non-ignored folders under it (`workingTreeWatchPlan`, `domain/refresh/`). Fork-only
    (2026-10-19): a folder with no ignored folder, `.git` or linked worktree under it is one recursive registration,
    and only a folder that has one is watched alone, followed by the folders in it. macOS gives the JDK's watch service
    one FSEvents stream per registration, and past about 250 streams in all, shared by every tab and process, new
    ones silently deliver nothing: with a registration per folder, the 474 folders of one repository left a file
    created in a late one unreported until Cmd+R. The same plan runs for each folder created later
    (`updateWatchedDirectories`). Links aren't followed. A `getStatus` that fails leaves the working tree unwatched
    (logged), as the ignored folders aren't known. `RefreshingOnChangesTest` has the 300-folder case with the app's
    watcher.
  - **Following the ignored folders (2026-10-19, `WorkingTreeWatches`, `domain/refresh/`):** a folder that is watched
    with everything under it can't leave one out, so a folder that git starts to ignore later stayed watched, and each
    file a build wrote in it refreshed the tab, until the tab opened again. That is a rule added in Leaf (Ignore…) or in
    an editor, in `info/exclude` or the global list, and a folder that a build makes in a clean one (`target/`).
    `WorkingTreeWatches` keeps what the watcher was given for the working tree, and the use case passes it each status
    that the tab loads (`onStatus`, from `RepositoryDataRepository.status`): when the folders among its `ignored` paths
    aren't the ones the registrations were planned for, it plans the whole tree again. A registration that the new plan
    lacks, or has the other way (alone or as a whole), is removed first, so that no change is delivered twice and no
    registration counts against macOS's limit for nothing, then every folder of the plan is added: watching again
    changes nothing, and brings back a folder that was deleted and made again. A folder whose rule is removed is
    watched again, as one registration where it can. Ignored files don't count, only folders (a stat each, once the
    status's paths differ from the last). A status that arrives before the first plan is applied after it. A folder
    created under a registration for everything gets none of its own. `unwatch` takes the folders under a deleted one
    too. Tests: `WorkingTreeWatchesTest` (a fake watcher that keeps what it's given as the app's does), the real
    watcher with and without `FILE_TREE` in `FileChangesWatcherTest`, and `RefreshingOnChangesTest` (Leaf's Ignore, and
    a build folder).
  - **The limit notice (2026-10-19):** `WatchRegistrations` (app singleton, `domain/refresh/`) counts the keys of every
    `FileChangesWatcher` (`update` after each add or remove, `release` on close). On macOS, from `WATCH_WARNING_AT`
    (200, as other apps share the system's ~250) its `warning` flow gives a `WatchUsage`, which
    `RepositoryOpenViewModel.watchWarning` passes to `BottomInfoBar`'s amber `WatchLimitNotice` chip ("File watching
    near its limit", the count and the tab count in its tooltip). It is in every tab and goes when the count drops.
    The crossing is logged in both directions. It's a count, not a detection: macOS reports no failed stream. Not
    built: a hard limit where new tabs refresh on a timer instead. Fork-only: except the folders of
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
  - nothing for Leaf's `leaf` file (and `leaf.lock`) and JGit's `.probe-` files in either git dir, for any worktree's
    `LEAF_COMMIT_DRAFT` and `rebase-merge/leaf-amend-message-<commit>`, which a tab saves a second after each edit, or
    for `COMMIT_EDITMSG`, `MERGE_MSG` and `SQUASH_MSG` in the tab's git dir.

  Paths are compared as given and as real paths (macOS reports `/private/var/...`), and a folder only contains what
  is under it with a separator. The old `startsWith(gitDir)` also matched `.gitignore` and `.github/`, which
  refreshed everything.
- WORKTREES alone goes through `WorktreeChangesRefresher`: right away, then at most once every 2 s while changes keep
  coming. An agent that runs git rewrites its worktree's index all the time, and each refresh runs git in every
  worktree.
- Refreshes are dropped (not deferred) while a task runs and for 1.5 s after one. Fork-only: the watch still follows
  the folders that each batch creates or deletes, and watches the working tree again after lost changes. Before, a
  folder created by Leaf's own checkout, merge or pull, or in the 1.5 s after one, was never watched, and deleted
  folders were only unwatched in a batch that also created one. `RefreshingOnChangesTest` checks them with a
  `RecordingFileChangesWatcher`, and that nothing refreshes while the task runs.
- Fork-only: the use case is `@TabScope` and keeps its watch's job. `invoke()` stops the previous watch first, and
  `stop()` cancels it before a switch to another worktree. The cancelled watch notices within 500 ms
  (`BATCH_TIME_MS`) and closes its own watcher, so the new watch doesn't wait for it. Closing the tab cancels the
  tab's scope, which ends the watch too.

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
- **Kotlin block comments nest.** `refs/heads/*` in a KDoc or a comment opens a comment inside it, and the rest of the
  file is comment until a second `*/`. The compiler then reports something far away (KSP said that an action "could
  not be resolved"). Write the pattern in a string, or leave the `/*` out of comments.

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
- **Removed (2026-10-10):** the proxy settings and "Do not verify SSL security". Neither was ever applied: the code that
  set the JVM's proxy was commented out, and the SSL switch wrote the credential cache setting while nothing read
  `verify_ssl`. git's own `http.proxy` and `http.sslVerify` apply to the git commands Leaf runs.
  `RemovedSettingsMigration` deletes their keys from `user_prefs.json`, the plain-text proxy password included. A
  setting removed later goes into `REMOVED_SETTINGS`.
- **The System theme (fork-only, upstream issue 309, 2026-10-20):** Settings → Appearance → Theme is Light, Dark, System or
  Custom. System follows the operating system's light or dark setting and changes with it while Leaf is open.
  - `AppTheme` is the only place that turns the choice into colours. `Theme.resolved(systemIsDark)`
    (`domain/models/ui/ThemeResolution.kt`) makes System Dark or Light, and the other themes stay as they are. AppTheme
    reads `isSystemInDarkTheme()` only while System is selected, as its `systemIsDark` parameter, which tests pass.
    Everything else asks `MaterialTheme.colors.isLight` / `isDark` or the `appTheme` flow, so it follows without
    knowing about System. A custom theme's JSON has its own `isLight` and ignores the system.
  - **How it follows (Compose Desktop 1.12.1, read from the jars):** `isSystemInDarkTheme()` changes only because
    Compose asks skiko for the setting once a second (`DesktopComposeUiFlags.pollSystemTheme`, an experimental flag).
    Compose ships it on for macOS and Windows and off for Linux, where the setting is read once at startup.
    `followSystemThemeOnLinux()` (`theme/SystemThemePolling.kt`, called from `main`) turns it on there.
    `SystemThemePollingTest` fails if a Compose update changes which systems poll.
  - **Where it was tried:** macOS, with the system flipped by hand while the window was watched (see
    `docs/fork/CHANGELOG.md`). Windows is Compose's own default and Linux's flag is a guess: it depends on what skiko's
    Linux detection reads, and a setting it can't read (`UNKNOWN`) counts as light. Look at both before calling the
    System theme done there.
  - The macOS title bar follows the system whatever the choice (`-Dapple.awt.application.appearance=system` in the
    packaged app, and the default in a dev run), so Light or Dark can differ from it. System makes them agree.
  - Stored as `theme`: `light`, `dark`, `custom` or `system`. **An unknown value reads as no choice** (fork-only,
    `ThemeMapper`), so `DEFAULT_THEME` applies. It used to throw inside the settings flow, which `App.start` reads at
    launch, so a Leaf from before 2026-10-20 doesn't start on a file that says `system`: after a downgrade, delete the
    `theme` key from `user_prefs.json`.
  - The default is still Dark, for a new install too. Someone who never chose has no `theme` key, like a new install,
    so making System the default would turn their Dark into whatever their system says.
  - Tests: `ThemeResolutionTest`, `ThemeSettingTest` (the mapper and a real settings file: a restart, an unknown value),
    `AppThemeSystemTest` (a flipping system setting in `AppTheme`, and that every theme is in the drop-down),
    `SystemThemePollingTest`.

**Tests:**
- Upstream's tests were removed in the 2.0 refactor (commit `36a92c60`). The fork's tests live in
  `data/src/test/kotlin`, `domain/src/test/kotlin`, `common/src/test/kotlin` and `app/src/test/kotlin`.
- **CI runs them on Linux** (`.github/workflows/build_with_tests.yml`, `ubuntu-latest`, `build` then `test`), so they
  must pass there too (fixed on 2026-10-15, after a week of red runs):
  - Fake programs record their arguments with `printf '%s\n'`, never `echo`: dash, Ubuntu's `/bin/sh`, takes `-n` as
    `echo`'s option and prints nothing.
  - A test whose git commits (merges, rebases) gives git an identity in its global config: git makes one up from the
    account and the host on macOS, and refuses on Linux ("Committer identity unknown").
  - A PATH without git-lfs is `pathWithoutGitLfs` (`TestRemoteCommand.kt`): Linux installs git-lfs in `/usr/bin`.
  - Compose's tests render with the app's fonts, which are LFS files: the workflow restores `.git/lfs/objects` from
    the Actions cache, keyed on the LFS objects' ids, and runs `git lfs pull`, so the 9.3 MB of fonts count against
    the account's LFS bandwidth (10 GiB a month free) only when they change. It installs `libgl1` and `libegl1` too.
  - The build stops at the first module whose tests fail, so a red `:data` hides `:app`'s tests.
  - Reproducing CI: an `eclipse-temurin:25-jdk` container with git, git-lfs, OpenSSH, `libgl1`, `libegl1`,
    `libfontconfig1` and rustup, the repository copied in (not mounted, as the build writes Linux outputs) with its
    fonts, and Gradle run as a user other than root, which ignores folder permissions. A few process and login shell
    tests fail only in a container.
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
- `TestTab` (`data/src/test/kotlin/dev/app/leaf/data/tab/`) builds a tab's repositories and use cases as Dagger does,
  with the real git actions, around a temp repository, and a `RecordingFileChangesWatcher` for each watch, which
  notes the folders it was given (those that exist, as the app's watcher skips the others) and reports the `events`
  that a test sends. With `realFileWatcher`, each watch gets
  the app's `FileChangesWatcher` instead (`RefreshingOnChangesTest`). `SwitchingWorktreesTest`,
  `WatchingWorktreesTest`, `RemovingWorktreesTest` and `CreatingWorktreesTest` use it; the last two run the steps that
  the worktree dialogs show (`runWorktreeSteps`, `createWorktree`). Its `close()` cancels the tab and waits for its
  refreshes, which still run git and JGit in the temp folder that JUnit deletes.
  - A task's refresh runs after the task's job ends, so joining the task doesn't wait for it. Wait for the data with
    `awaitLoaded`'s predicate (a state other than the one before the task), not for any loaded value, which may still
    be the old one. Two tests did, and failed once the refresh ran `git var` before the repository state.
- A view model's state that `combine`s several flows can show one of them before another arrives. A test waits for a
  state that has everything it asserts, not for the first one that has part of it: `WorktreeReviewTest.loaded()` waits
  for the comparison and for the worktree list's count, which failed once in a full run.
- Shared helpers live in `data/src/test/kotlin/dev/app/leaf/data/git/TestGit.kt`:
  - `IsolatedSystemReader` keeps JGit away from the developer's `~/.gitconfig` and JGit config. Install it with
    `SystemReader.setInstance` and restore the original in `@AfterEach`.
  - JGit measures a file store's timestamps once per JVM when its config has no measurement, which an isolated config
    never has: for 3 seconds it rewrites a `.probe-<uuid>` file in the folder of the first file it reads, and creates
    that folder again when it's gone. `git worktree remove` failed to delete a linked worktree's git dir that way
    ("Directory not empty"). So `MeasureJGitFileStore`, next to `TestGit.kt`, has it measure before the first test,
    in a temporary folder with an isolated config. It's a JUnit Platform `LauncherSessionListener`, registered in
    `data/src/test/resources/META-INF/services`, and needs `junit-platform-launcher` as `testImplementation`.
  - `TestGitCli` runs the git CLI with global and system config ignored. JGit can't create linked worktrees, so use
    the CLI to set them up. `run(dir, env, args)` adds environment variables, for example `GIT_COMMITTER_DATE` to fix
    commit, tag and reflog dates (`GetRefDatesGitActionTest`). `runFailing` expects git to fail and returns its
    output in English, to compare Leaf with git's own refusals (`BranchWorktreesTest`).
  - `testGitCli(shellVariables, configuredPath)` builds a `GitCli`. Credential tests always give it the variables
    that keep git away from the developer's config, even when the helpers run without them.
    `isolatedGitCli(globalConfig)` builds one that reads `globalConfig` and no system config, as `TestGitCli` does.
    Every test that builds `DoCommitGitAction`, `CreateTagGitAction`, `StashChangesGitAction` or `LoadAuthorGitAction`
    needs one, as they run git.
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
  - `GitCliPushBranchGitActionTest`, `GitPullTest` and `GitCliFetchPullTest` (the fetch, and the pull that JGit
    merges) use bare repositories on disk, which another clone changes. `GitPullTest` checks each pull against what
    `git pull` does in a terminal, and `GitCliLfsTest` and `PartialCloneTest` have a `git pull` each.
    `GitCliCloneTest` clones, and adds and updates submodules, from repositories on disk; its global config sets
    `protocol.file.allow=always` for the local submodules.
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
