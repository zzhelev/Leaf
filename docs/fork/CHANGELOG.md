# Fork changelog

This file covers fork-only changes on `fork/main`. Upstream history is in git.

## Leaf identity (branch `feature/leaf-identity`)

- **Links:** "Source code", "Report a bug" and "Releases" open `github.com/zzhelev/Leaf`.
- **Update check:** reads `latest.json` from `zzhelev/Leaf` on the `fork/main` branch. `latest.json` now describes Leaf
  (2.0-beta03, app code 25). Until `fork/main` is on GitHub, the URL returns 404, the JSON parse throws, and that
  update check stops with a stack trace on stderr, as it does offline.
- **Release workflow:** publishes to `zzhelev/Leaf`. It still names upstream's self-hosted runners.
- **Renamed:** `gitnuro.iss` to `leaf.iss`, the root Gradle project to `Leaf`, `GitnuroException` to `LeafException`,
  and the Gitnuro names in the test fixtures.
- **Kotlin package** `com.jetpackduba.gitnuro` is now `dev.app.leaf`. The Gradle group, the Compose `Res` package
  (`dev.app.leaf.app.generated.resources`), the main class, the uniffi package and the GraalVM metadata (now in
  `META-INF/native-image/leaf/`) follow. Git records 72 small files as deleted and added rather than renamed, because
  most of their lines changed, so `git log --follow` may not connect their history.
- **Rust crate** `gitnuro_rs` is now `leaf_rs`: the native library is `libleaf_rs.dylib` and the bindings are
  `leaf_rs.kt`.
- **Kept on purpose:** the "based on Gitnuro" credit, the `libssh-rs` and `kotars` dependencies from JetpackDuba's
  repositories, and the bundle ID `io.github.zzhelev.leaf`.
- **Verified:** after a clean build all 45 tests pass. The packaged app was launched with a temp repo:
  - the Rust file watcher reported a file change;
  - the extracted native library is `libleaf_rs.dylib`;
  - stderr has no exceptions besides the update check.

## README and DEVELOPMENT.md for Leaf (branch `docs/leaf-readme`)

- **README.md** now describes Leaf. It opens with a "Built on Gitnuro" section that credits Gitnuro and its author,
  explains Leaf's separate roadmap, gives the fork date and the license, and welcomes sharing work in either direction.
  - Removed, because they describe Gitnuro and not Leaf:
    - the upstream release badge;
    - the Gitnuro screenshot (`res/img/cover.png` is kept);
    - the Flathub, Homebrew and release download links;
    - the Sponsors section.
  - Added a License section. Links to Gitnuro's issue tracker stay.
- **DEVELOPMENT.md** says Leaf. It also corrects the requirements: JDK 25 (and a full JDK for packaging), no
  `cargo-kotars`, and Git LFS for the fonts.
- `CLAUDE.md` and `docs/fork/` still name Gitnuro where they mean the upstream project or an installed Gitnuro.

## Own storage for Leaf (branch `feature/leaf-own-storage`)

- **Leaf no longer shares data with Gitnuro.** Every storage name now comes from the fork-only `AppStorage`
  (`common/storage/`), and none of them mention Gitnuro. On macOS, a packaged Leaf uses:
  - the prefs node `LeafConfig` for tabs, recent repos and pane widths (was `GitnuroConfig`);
  - `~/Library/Application Support/leaf/` for the settings file and `tmp/` (were `.../gitnuro/` and
    `~/Library/Application/gitnuro/`);
  - `~/Library/Logs/io.github.zzhelev.leaf/leaf.log` (was `com.jetpackduba.Gitnuro/gitnuro.log`);
  - `<git dir>/leaf` for per-repository settings such as sign-off (was `<git dir>/gitnuro`).

  Linux and Windows use `leaf` in place of `gitnuro` or `Gitnuro` in the same paths.
- **Fresh start.** Nothing is imported from Gitnuro. Tabs, recent repos, settings and per-repository settings start
  empty.
- **Dev runs are separate.** Without jpackage's `jpackage.app-version` property, Leaf uses `LeafDevConfig`, `leaf-dev`
  and `io.github.zzhelev.leaf-dev`. So `./gradlew :app:run` can't change an installed Leaf's tabs or overwrite the
  native library a running Leaf extracted. A Linux `java -jar` run also counts as a dev run.
- **Fixed in passing:** the macOS app folder was `~/Library/Application/gitnuro`, missing "Support" (an upstream bug).
- **Unchanged:** the Rust crate name, so the extracted library is still `libgitnuro_rs.dylib` (PLAN.md, rule 8).
- **Tests:** `AppStorageTest` has four tests:
  - packaged names;
  - dev and packaged runs share no name;
  - no name mentions Gitnuro;
  - the test JVM counts as a dev run.
- **Manual check (macOS):** with Gitnuro 1.5 running, launched and quit the packaged Leaf, then ran and stopped
  `./gradlew :app:run`.
  - Leaf created `LeafConfig` and the dev run created `LeafDevConfig`, each with its own folders and log.
  - Gitnuro's prefs node and settings file were byte-for-byte unchanged.
  - The per-repository `<git dir>/leaf` file was not exercised manually.

## macOS packaging fixes (branch `fix/macos-bundle-id`)

Both bugs come from upstream commit `a9a0f318` ("Added config to build MacOS DMG"), which landed after
`2.0.0-beta03`.

- **Bundle ID was null.** Inside `macOS { }`, `packageName` resolved to the DSL's own nullable `packageName`, not the
  script's value, so `createDistributable` failed with "bundleID is empty or null". The Leaf rename replaced it with
  the literal `io.github.zzhelev.leaf`, which fixes it in the fork, so this branch has no commit for it.
- **Signing couldn't be turned off.** `sign.set(true)` overrode the `compose.desktop.mac.sign` Gradle property. In
  Compose 1.12.0 the property only sets the default, despite what the Compose docs say. Signing stays on by default,
  and `-Pcompose.desktop.mac.sign=false` now turns it off.
- **Verified:** `:app:packageDmg` with a JBR SDK 25 produces `Leaf-2.0.0.dmg` (175 MB).
  - All 46 native binaries in `Leaf.app`, and `libgitnuro_rs.dylib` inside the app jar, are arm64.
  - `codesign --verify --deep --strict` passes (ad hoc signature).
  - The bundled runtime has no `/opt/homebrew` links, and the real fonts are packed.
- **Not yet done:** launching the packaged app. Leaf still shares `GitnuroConfig` with an installed Gitnuro.

## Renamed to Leaf (branch `feature/rename-to-leaf`)

This is one isolated commit touching only display name and packaging (PLAN.md, rule 8).

- **Display name:** the window title, Welcome page, About dialog, Settings subtitles, the settings tooltip and two
  error messages now say Leaf. The About text credits Gitnuro ("based on Gitnuro").
- **Packaging:**
  - `projectName` is `Leaf`, so the outputs are `Leaf.app`, `Leaf-<version>.dmg` and `Leaf-linux-*.jar`.
  - The macOS bundle ID is `io.github.zzhelev.leaf`, so Leaf and an installed Gitnuro are separate apps to macOS.
  - `gitnuro.iss` has a new AppId, so a Windows Leaf install doesn't replace Gitnuro. It also has new publisher, URL
    and exe name, and paths that follow the new build output.
  - In `.github/workflows/release.yml`, artifact names and paths follow `projectName`.
- **Unchanged:**
  - Kotlin package names.
  - The Rust crate.
  - Storage locations. Leaf still shares settings, tabs and logs with an installed Gitnuro.
  - The upstream links: Source code, Report a bug, Releases.
  - The update check against upstream's `latest.json`.
  - The release workflow's publishing target (`JetpackDuba/Gitnuro`, self-hosted runners).

## Leaf app icon (branch `feature/leaf-icon`)

- **New icon, "Midrib":** a lime leaf on a forest-green tile. Its veins are cut out of the leaf and branch off the
  midrib the way branches leave `main`. It was designed in the Claude Design project "Leaf app icon", which also holds
  the two rejected options.
- **Files were replaced in place**, so no config changed. `app/build.gradle.kts`, `gitnuro.iss` and the native-image
  reachability metadata still point at the same paths.
  - `icons/icon.icns`: macOS grid (an 824 px tile on a 1024 px canvas), with all ten iconset sizes from 16 to 1024.
    The old file only had 512.
  - `icons/icon.ico`: full-bleed, at 16, 24, 32, 48, 64, 128 and 256. The old file only had 256.
  - `icons/logo.svg` and `app/src/main/composeResources/drawable/logo.svg` (the window icon): full-bleed SVG.
- **The SVGs use only paths, strokes and one linear gradient.** There are no masks, transforms or clip paths; the vein
  cuts are painted with the tile's gradient. They were checked with Skia's SVG renderer, which Compose uses.
- The display name, package names and bundle ID still say Gitnuro (PLAN.md, rule 8).

## 1.1: Git CLI adapter (branch `feature/git-cli-adapter`)

The fork-only code is in `data/git/cli/` and `domain/gitcli/`.

- **`ProcessRunner`:** a coroutine-friendly process runner.
  - It reads stdout and stderr concurrently and closes stdin.
  - On timeout or cancellation it kills the whole process tree with SIGTERM, then SIGKILL, so no process outlives
    its coroutine.
- **`GitExecutableLocator`:** finds git.
  - Search order: the configured path, then `/opt/homebrew/bin/git`, `/usr/local/bin/git`, `/usr/bin/git`, then
    PATH. Linux and Windows have their own orders.
  - It skips the macOS `/usr/bin/git` shim when the Command Line Tools are missing, which avoids the install popup.
  - It verifies `git --version` ≥ 2.36 and caches the result.
- **`GitCli.run(workingDirectory, args, timeout)`:** returns stdout, or a `GitCliError`.
  - It runs git with `LC_ALL=C`, `GIT_TERMINAL_PROMPT=0` and `GIT_OPTIONAL_LOCKS=0`. The last means polling never
    takes `index.lock` while agents work.
  - It strips inherited repo-locating `GIT_*` variables.
- **Setting:** "Git executable" in Settings → Environment, stored in DataStore as `git_executable_path`. It shows the
  detected path and version, or the error. An invalid configured path is reported, never silently replaced.
- **Errors:** `GitCliError` (in `domain/errors`), with user-facing text in `ui/Errors.kt`.
- **Tests:** `ProcessRunnerTest`, `GitExecutableLocatorTest`, `GitCliTest` and `GitVersionTest`. Script-based tests
  are skipped on Windows. They cover:
  - output and exit codes;
  - large output;
  - stdin;
  - killing the process on timeout and on cancellation;
  - candidate order per OS;
  - version checks;
  - configured-path errors;
  - caching;
  - the environment.
- There is no consumer yet; 1.2 is the first. Flatpak (`flatpak-spawn --host`) is not handled.

## Open linked worktrees (branch `fix/open-linked-worktree`)

- **Linked worktrees now open.** `OpenRepositoryGitAction` used to treat every `.git` file as a submodule. Now, when
  the `gitdir:` target contains a `commondir` file, it opens the folder directly as a linked worktree. Absolute and
  relative `gitdir:` paths both work. This covers sibling worktrees (`../repo-feature`), which used to fail silently,
  and nested ones (`.claude/worktrees/<name>`), which used to work only by accident.
- **Failures surface.** Opening a repository no longer throws. Failures come back as `OpenRepoError`, so the error
  dialog appears instead of the open dying silently.
- **"Open in terminal"** now uses the working tree instead of the parent of the git dir, which for a linked worktree
  was `<common>/.git/worktrees`.
- **Tests:** `OpenRepositoryGitActionTest` covers seven cases: a regular repo, sibling, nested and relative-path
  worktrees, a submodule, a non-repo folder, and a broken `.git` file.
  - Against the old code, the sibling, relative-path and broken-file cases fail, and the other four pass. So the fix
    is what makes them pass, and existing behaviour is unchanged.
- **Shared test helpers:** `IsolatedSystemReader`, plus `TestGitCli`, which runs git with the developer's global and
  system config ignored.
- **Manual check:** on macOS, both a sibling and a nested temp worktree open in the app, with no errors, and both are
  added to recent repos.
- **Still open:**
  - Tab subtitle and persisted path show `<common>/.git/worktrees/<name>` (Phase 2b).
  - The watcher doesn't cover the common `refs/` (Phase 1.5).

## 1.0: Turn off JGit auto-GC (branch `fix/jgit-autogc`)

- JGit's automatic gc is now off by default. JGit runs it after merges, rebases, fetches and pushes, and it is not
  worktree-aware: objects referenced only by another worktree's detached HEAD, index or reflog get pruned.
- How it works: `NoAutoGcSystemReader` (in `data/git/`) injects `gc.auto=0` and `gc.autoPackLimit=0` underneath
  every config file. It is installed in `main.kt` before any repository is opened.
  - Explicit user or repository settings still take precedence.
  - Nothing is written to disk.
  - The git CLI's own `gc --auto` is unaffected.
- Tests: the first in the codebase, `NoAutoGcSystemReaderTest`. They run against temp repos with isolated config files
  and cover:
  - the effective values;
  - that explicit config takes precedence;
  - that nothing is written to disk;
  - that auto-gc is skipped, with a control case showing it would otherwise run.
- Manual check: the app started and opened a temp repo on macOS, with no errors in the log.
- The fix commit contains no fork-only files, so it can be cherry-picked onto upstream.

## Phase 0: setup and orientation (2026-10-02)

- Created `fork/main` from `upstream/main` (`62442f26`, `2.0.0-beta03-4`).
- Added `CLAUDE.md`, which covers the verified toolchain and commands, the module map, the architecture, and the
  conventions.
- Added `docs/fork/PLAN.md`, the fork plan and working rules.
- Added `docs/fork/architecture-notes.md`, which records:
  - the JGit version;
  - linked-worktree opening;
  - the checkout and branch-deletion guards;
  - the gc risk.
- Added `docs/fork/probes/`, a reproducible JGit-versus-git-CLI probe for those findings. It runs against temp repos
  only.
