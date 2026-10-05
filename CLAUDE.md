# CLAUDE.md

Fork of [Gitnuro](https://github.com/JetpackDuba/Gitnuro) (Kotlin, Compose Desktop, JGit) that adds first-class git
worktree support for AI-agent workflows. The fork plan and working rules are in `docs/fork/PLAN.md`. Read them before
starting any work item. Findings about JGit and worktrees are in `docs/fork/architecture-notes.md`.

This file and everything under `docs/fork/` are fork-only. Keep them out of upstream PRs.

## Name

The fork ships as **Leaf**. The display name, packaging and storage locations changed.
- **Leaf:** `AppConstants.APP_NAME` and the user-facing strings. Also `projectName` in `app/build.gradle.kts`, which
  produces `Leaf.app`, `Leaf-*.dmg` and `Leaf-linux-*.jar`. Also the macOS bundle ID `io.github.zzhelev.leaf` and
  `gitnuro.iss`, which has its own AppId.
- **Own storage:** every storage name comes from the fork-only `AppStorage` (`common/.../common/storage/`), so Leaf
  never reads or writes an installed Gitnuro's data. On macOS, a packaged Leaf uses:
  - the `java.util.prefs` node `LeafConfig` (tabs, recent repos, pane widths);
  - `~/Library/Application Support/leaf/` (the settings file `user_prefs.preferences_pb`, and `tmp/`);
  - `~/Library/Logs/io.github.zzhelev.leaf/leaf.log`;
  - the per-repository file `<git dir>/leaf` (sign-off).

  Dev runs use `LeafDevConfig`, `leaf-dev` and `io.github.zzhelev.leaf-dev` instead (see Build gotchas).
- **Still Gitnuro:**
  - the Kotlin packages (`com.jetpackduba.gitnuro`), the Gradle `group` and the root project name;
  - the Rust crate (`gitnuro_rs`), so the native library extracted to `tmp/` is still `libgitnuro_rs.dylib`.
- **Still upstream:** the Welcome page's "Source code" and "Report a bug" links, the Releases link in the bottom bar,
  `VERSION_CHECK_URL`, and the publishing target in `.github/workflows/release.yml`.

## Branches and remotes

- `origin` = fork (`zzhelev/Leaf`), `upstream` = `JetpackDuba/Gitnuro`.
- The 2.0 rewrite lives on `upstream/main` (tags `2.0.0-beta01..03`). The other upstream branches are stale.
- Local `main` mirrors `upstream/main` (it tracks it). Never commit to it.
- `fork/main` is the integration branch (created from `upstream/main`). Work items branch off `fork/main`.

## Toolchain (verified on macOS 26.6 arm64, 2026-10-02)

- **JDK 25.** The JVM toolchain is pinned to 25 in `buildSrc` and `app`. `buildSrc` has no toolchain auto-download, so
  Gradle fails with "Cannot find a Java installation ... languageVersion=25" unless `JAVA_HOME` points at a JDK 25.
  On this machine, IntelliJ IDEA's bundled runtime works:
  `export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"` (JBR 25.0.4.1).
  DEVELOPMENT.md still says "JDK 17 or higher", which is outdated.
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
- `cargo-kotars` is **not** needed, despite DEVELOPMENT.md. Bindings are generated with uniffi
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
./gradlew :app:createDistributable -Pcompose.desktop.mac.sign=false  # .app in app/build/compose/binaries/main/app/
./gradlew :app:packageDmg -Pcompose.desktop.mac.sign=false           # .dmg in app/build/compose/binaries/main/dmg/
rm -rf /Applications/Leaf.app && ditto app/build/compose/binaries/main/app/Leaf.app /Applications/Leaf.app
```

- The last line installs without the DMG. Remove the old app first: `ditto` merges into an existing bundle, and jar
  names change between builds, so leftover jars would break the code signature.

- Without a Developer ID certificate, pass `-Pcompose.desktop.mac.sign=false`. jpackage then signs ad hoc, with the
  hardened runtime and the JIT and library-validation entitlements the JVM needs. That build runs on the machine that
  built it. Sharing it needs a Developer ID and notarization (`SIGNING_IDENTITY` and `NOTARIZATION_*` env vars).
- The app is `Leaf.app` with bundle ID `io.github.zzhelev.leaf`, so it installs alongside Gitnuro. It has its own
  storage (see Name), so it doesn't touch an installed Gitnuro's tabs, settings or logs.
- The packaged skiko jar still contains `libskiko-macos-x64.dylib`, because skiko's macos-arm64 runtime jar ships both
  architectures. Only the extracted arm64 library is loaded.

Packaging config lives in `app/build.gradle.kts` (`compose.desktop.nativeDistributions`). Do not touch it without
asking.

### Build gotchas

- **Rust builds at configuration time.** In `app/build.gradle.kts`, `tasks.register("rustTasks") { rustTasks() }`
  runs inside the configuration block. So any Gradle invocation that configures `:app` (even `help`) runs
  `cargo build --release`, then a debug build, then bindgen.
- **Rust failures do not fail the build.** `executePrintingData` sets `isIgnoreExitValue = true` and only prints
  `Code is N`. If something Rust-related looks stale, grep the Gradle output for `Code is` and `failed with exit value`.
- Generated, gitignored outputs:
  - `domain/src/main/kotlin/com/jetpackduba/gitnuro/autogenerated/` (uniffi Kotlin bindings, `gitnuro_rs.kt`)
  - `app/src/main/resources/libgitnuro_rs.dylib`
- **Dev runs have their own storage.** `AppStorage` tells them apart from packaged apps by the `jpackage.app-version`
  system property, which only jpackage's launcher sets. So `./gradlew :app:run` uses the prefs node `LeafDevConfig`,
  `~/Library/Application Support/leaf-dev/` and `~/Library/Logs/io.github.zzhelev.leaf-dev/leaf.log`. It can't touch
  the installed `/Applications/Leaf.app` or `/Applications/Gitnuro.app` on this machine.
  - A Linux `java -jar Leaf-linux-*.jar` run doesn't have the property either, so it also uses the dev names.
  - Prefs nodes live in `~/Library/Preferences/com.apple.java.util.prefs.plist` on macOS. They cannot be redirected:
    `FileSystemPreferencesFactory` is unavailable on macOS, and `-Duser.home` does not affect them.
  - Passing `-Duser.home` to `:app:run` through an init script did not take effect; the Compose plugin sets the run
    task's JVM args.
  - A dev run that restores tabs lazily can wipe the persisted tab list; this happened once, when dev runs still
    shared Gitnuro's node. It can now only wipe the dev tab list.

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
- `rs`: Rust cdylib `gitnuro_rs`, exported via uniffi.
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
- The class is a `@Singleton` cache from path to `Git`, opened with `Git.open(File(repositoryPath))`.
- Exceptions inside `provide` become `GenericError` (or come from an `errorHandle` mapper).
- `Git` instances are never closed.
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
  cached) and sets a non-interactive, `LC_ALL=C` environment.
- Use porcelain or `-z` output and parse it in the data layer.
- Never shell out to git through `ShellManager`.

**External processes (upstream code):** upstream never invokes the `git` CLI. `ProcessBuilder` is only used in `domain/.../ShellManager.kt`
(credential helpers, Windows hooks, terminals, opening a file manager) and in `FileExtensions.kt`.

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
- Only tabs in the `Open` state are persisted.

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
- Shared helpers live in `data/src/test/kotlin/com/jetpackduba/gitnuro/data/git/TestGit.kt`:
  - `IsolatedSystemReader` keeps JGit away from the developer's `~/.gitconfig` and JGit config. Install it with
    `SystemReader.setInstance` and restore the original in `@AfterEach`.
  - `TestGitCli` runs the git CLI with global and system config ignored. JGit can't create linked worktrees, so use
    the CLI to set them up.
  - See `OpenRepositoryGitActionTest` for the pattern.
