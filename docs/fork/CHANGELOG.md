# Fork changelog

This file covers fork-only changes on `main` (called `fork/main` until 2026-10-05). Upstream history is in git.

## Credential helpers run the way git runs them (branch `fix/credential-helpers`)

- **Before:** Leaf ran the `credential.helper` value of an HTTPS remote as a program. On macOS and Linux:
  - `osxkeychain`, the default of Apple's and Homebrew's git, failed with `Cannot run program "osxkeychain"`, as did
    any helper given by name. This happened from a terminal too.
  - `!gh auth git-credential`, which `gh auth setup-git` writes, failed on the `!`.
  - `manager` was looked up with `which` on the inherited PATH. Opened from the Finder or the Dock, Leaf has launchd's
    PATH (`/usr/bin:/bin:/usr/sbin:/sbin`), so a credential manager in `/usr/local/bin` wasn't found, and the fetch
    failed with "Could not find git credentials manager path".
  - A helper given by its path started, but couldn't run tools from Homebrew or nvm, so Leaf asked for the password.
- **Now:** on macOS and Linux Leaf builds the command as git does (`credential.c`). `!command` is a shell command, an
  absolute path runs as it is, and a name runs as `git credential-<name>`. The command runs through `/bin/sh -c` with
  the login shell's environment (`LoginShellEnvironment`).
  - The shell is needed: Java looks a program name up on the PATH Leaf started with, not on the PATH given to the
    process.
  - `IShellManager.runCommandProcess` takes an `environment` parameter. `FlatpakShellManager` ignores it, as the command
    runs on the host and the variables come from the sandbox.
- **Also fixed:** like git, Leaf ignores a helper that exits without reading its input. Writing to it could fail with
  "Broken pipe" and end the fetch, depending on timing; now Leaf asks for the credentials.
- **Unchanged:** Windows, where `WindowsGitCredentialsManagerProvider` still finds `manager`.
  `NixGitCredentialsManagerProvider` is no longer called; it stays to keep upstream merges simple. `store` and
  `cache` are still refused, so Leaf asks for the password itself. Terminals still get the inherited environment.
- **Tests:** `HttpCredentialsProviderTest` (8). Without the login shell's environment 5 fail, with the old command
  building 6 fail, with the old `manager` lookup 1 fails, and without the broken pipe handling 1 fails. All 180 tests
  pass.
- **Verified** in a Finder-like environment: no `TERM` and launchd's PATH.
  - Before: `osxkeychain`, `!<path>/gh auth git-credential` and `!gh auth git-credential` failed, as from a terminal.
    With a fake credential manager on the terminal's PATH, `manager` failed only in the Finder-like environment, and
    so did a helper given by its path that runs a tool from that PATH.
  - After: the login shell's 20 variables were read in 1.3 s. The real `git credential-osxkeychain get` ran (the test
    host had no entry, so Leaf asked), and the real `!gh auth git-credential` returned the github.com credentials.

## Hooks run on Windows again (branch `claude/funny-vaughan-00e1d8`)

- **Before:** `WindowsFs`, from upstream's c43e8e26 (Gitnuro#314), ran hooks with Git Bash but returned
  `ProcessResult(Status.OK)`, whose exit code is -1. JGit counts an OK result with a non-zero exit code as a failed
  hook, so any pre-commit, commit-msg or pre-push hook rejected every commit or push, even one that exited 0. It also:
  - ignored the hook's exit code;
  - passed no arguments (commit-msg's message file, pre-push's remote) and no input (pre-push's refs), and never
    closed the input, so a hook that read it waited forever;
  - read the output only after the hook ended, so a hook that printed more than a pipe holds (about 64 KB) hung;
  - looked only in `<git dir>\hooks`. It ignored `core.hooksPath`, the setup in #314, and in a linked worktree it
    missed the main repository's hooks and skipped them without a message;
  - went through `cmd /C`, which splits a path at `&`.
- **Now:** the hook is found with JGit's `findHook`, so `core.hooksPath` and the common git dir count. It runs with Git
  for Windows' `bin\bash.exe` in the working tree, with JGit's `GIT_DIR`, `GIT_COMMON_DIR` and `GIT_WORK_TREE`, its
  arguments and its input. JGit's `runProcess` reads both output streams while it runs. The result carries the real
  exit code.
- **Git Bash** is looked for in each Git for Windows folder on PATH (`cmd`, `bin`, `mingw64\bin`, `usr\bin`), then in
  the default install folders that are searched for the git CLI too. When a hook exists and Git Bash doesn't, the
  commit fails with "Git Bash was not found. It is needed to run the hook '…' on Windows". Without hooks, Git Bash
  isn't needed.
- **Arguments** are quoted for MSYS2 the way Git for Windows quotes them, so a path with spaces, a `'`
  (`C:\Users\O'Brien`), `{`, `*`, `?` or `~` arrives intact. The hook path gets forward slashes, so a hook that runs
  `dirname "$0"` (husky) works.
- **Unchanged:** bash reads the hook as a shell script and ignores its `#!` line, as before, so a
  `#!/usr/bin/env node` hook doesn't run with node. Clean and smudge filters and diff tools still run through
  `cmd.exe` (`FS_Win32.runInShell`).
- **Tests:** `GitBashTest` (8) and `WindowsFsTest` (10). `WindowsFsTest` runs `WindowsFs` on macOS and Linux, with
  `/bin/sh` in place of Git Bash, through JGit's own commit and push. Putting back each old behavior makes the
  matching tests fail:
  - the -1 result: 8 tests, and a hook that exits 0 gives "Rejected by "pre-commit" hook";
  - no arguments, or no input: the commit-msg and pre-push tests;
  - reading the output after the hook ends: the large output test times out;
  - the `<git dir>\hooks` lookup: the `core.hooksPath` and linked worktree tests.

  All 172 tests pass (100 in `:data`, 72 in `:domain`), and `:app` compiles.
- **Not tried on Windows.** The quoting follows Git for Windows' `quote_arg_msys2` and Java's command line rules.
- **Found on the way, not fixed:** in a linked worktree JGit gives commit-msg an empty `$1`, on every system.
  `CommitMsgHook` makes the message file's path relative to the working tree with `Repository.stripWorkDir`, which
  returns `""` for a file outside it, and a linked worktree's `COMMIT_EDITMSG` is in `.git/worktrees/<name>`.

## Hooks get the login shell's PATH (branch `worktree-hooks-shell-path`)

- **Before:** Leaf opened from the Finder or the Dock inherits launchd's PATH (`/usr/bin:/bin:/usr/sbin:/sbin`). A
  hook that runs node, npx, lefthook, husky or pre-commit, or anything else from Homebrew or nvm, failed with
  `command not found` and blocked the commit (Gitnuro#236, #321, #298). The same went for post-checkout hooks and LFS
  filters run by `git worktree add`.
- **Now:** at startup `LoginShellEnvironment` runs the user's shell once in the background, as an interactive login
  shell (`$SHELL -i -l -c`), and reads its environment. Hooks, clean and smudge filters and diff tools run by JGit
  (through the new `PosixFs`) and every git CLI run get the new and changed variables. JGit's `GIT_DIR` and GitCli's
  `LC_ALL=C` still win.
- **Skipped** on Windows, and when `TERM` is set, as an app started from a terminal already has the shell's
  environment. A failing shell, or one that takes more than 10 s, leaves the inherited environment and logs why. The
  log names only how many variables were added, never their values.
- **Startup files** can check `LEAF_RESOLVING_SHELL_ENVIRONMENT=1` to skip slow work.
- **Also fixed:** `JGit.provideOptional`, which fetch, pull and push use, opened repositories with JGit's default
  `FS`. When it was the first to open a repository, the cached `Git` never used Leaf's own `FS`, on Windows either.
  Both paths now share `JGit.open`.
- **Tests:** `LoginShellEnvironmentTest` (14), `PosixFsTest` (4) and two in `GitCliTest`. Removing the fix from
  `JGit`, `GitCli` or `provideOptional` makes the matching tests fail. All 154 tests pass.
- **Verified** in a Finder-like environment: no `TERM` and launchd's PATH, with this machine's real zsh and startup
  files.
  - Without the fix, a pre-commit hook running `node -v` was rejected with `node: command not found`.
  - With it, the shell's 20 variables were read in 0.9 s, the commit went through and the hook printed v26.10.0.
  - A `git worktree add` through `GitCli` ran its post-checkout hook with node too.
  - The dev app started in that environment logged the shell lookup at startup (0.8 s).
- **Not covered:** credential helpers and terminals, which `ShellManager` starts, still get the inherited environment.

## Dense lists spacing and a compact commit graph (branch `feature/dense-lists-spacing`)

- **Dense:** a third "Lists spacing" option, after Spaced (38 dp rows, 36 dp in the side panel) and Compact (34 dp).
  File, commit and side panel rows are 26 dp, the lowest height that still fits a hovered file's 24 dp Stage/Unstage
  button. Branch and tag chips in the log shrink from 26 to 22 dp (`LinesHeight.refChipIconPadding`). The setting is
  stored as `dense` under `lines_height`.
- **Going back to an older Leaf:** 1.1.1 and earlier throw at startup on a `lines_height` value they don't know. Switch
  to Spaced or Compact before installing one.
- **Uncommitted changes row:** its 8 dp padding is now horizontal only. The vertical part only shrank the centered
  content, and in a 26 dp row it left 10 dp for the text.
- **Commit graph:** dots instead of avatars, in every spacing mode. Lanes are 14 dp instead of 30 dp, and commits are
  10 dp dots: circles, small squares for merges, rings for stashes. Nine lanes take 140 dp instead of 300 dp.
- **Author on hover:** the dot's tooltip shows the avatar (Gravatar, or the colored initial) before the name and email.
  The hover area is the dot's lane for the full row height. Avatars load only on hover now. `InstantTooltip` gained a
  `leadingContent` slot, and its content is now vertically centered.
- **Graph column:** it fits the lanes instead of always starting at 120 dp: at least 56 dp, so the "Graph" header
  fits, and at most 120 dp unless dragged wider. A repository with 3 lanes went from 120 to 56 dp. The column can
  widen while scrolling, up to the cap, when older history brings more lanes. The dragged width is still not saved.
- **Verified:** offscreen renders of the real repository tab in all three modes, on a demo repository, a clone of Leaf
  and a repository with 9 active branches. Also a hovered file's Stage button in Dense, the dot tooltip on a commit and
  on a merge, and a toolbar tooltip with its keybinding hint. The 141 existing tests pass; the change is UI only and
  adds no tests.

## Resizable Staged, Unstaged and commit field (branch `feature/status-resizable-sections`)

- **Before:** Staged and Unstaged always split their space 50/50, and the commit field was fixed at 192 dp.
- **Handles:** one between the two lists and one above the commit field, with the north–south resize cursor. A
  double-click resets that handle: the lists to 50/50, the commit field to 192 dp. The lists divider moves the two
  lists. The commit divider moves the bottom list and the commit field, and the top list gives way only once the
  bottom one is at its minimum.
- **Limits:** each list keeps 100 dp (its header, the column header of Split columns and a row) and the commit field
  140 dp (a one-line message box, the Amend checkbox and the buttons). A shorter window shrinks the sections without
  changing what's saved, and a taller one brings them back.
- **Swap setting:** the lists divider keeps Staged's share, so the sizes follow the sections when Unstaged is shown on
  top.
- **Saved globally**, in the prefs node next to the pane widths (`statusStagedShare`, `statusCommitFieldHeight`), when
  a drag ends or a handle is reset. Every tab shares them. Damaged values fall back to the defaults.
- **Spacing:** the 8 dp handles replace the 4 dp gaps below each list.
- **Tests:** `StatusSectionSizesTest` (13) covers fitting, the minimums, a pane too short for them, both dividers in
  both orders, moving back at once after hitting a limit, and damaged values.
- **Verified:** an offscreen harness on the real status pane, with a temporary repository that has staged and
  unstaged files. It dragged both handles, hit every limit, double-clicked both resets, flipped the order, shrank and
  restored the window, and checked the prefs node. A second JVM read the saved sizes back.

## Discard changes on folders (branch `fix/discard-folder`)

- **Before:** "Discard changes" in an Unstaged folder's right-click menu did nothing. Upstream wired it to an empty
  lambda.
- **Now:** it asks first, then discards the unstaged changes of the files the folder row stands for. That's every
  file under it, including those in closed subfolders, or only the search matches during a search. Modified and
  deleted files go back to their staged or committed version, and a conflicted file to the current branch's version.
- **New files are kept**, as a single new file offers "Delete file" rather than "Discard". The dialog says how many are
  kept, and a folder holding only new files doesn't offer the item.
- **Dialog:** `Screen.DiscardFolderChanges` opens `DiscardChangesDialog`, and its `DiscardChangesViewModel` calls the
  existing `DiscardEntriesUseCase`. The texts are in `strings.xml`, with plural forms.
- **Unchanged:** discarding a single file or a selection still has no confirmation.
- **Tests:** `DiscardEntriesGitActionTest` (2, on temp repos) checks that a folder's tracked files come back while new
  files and other folders are kept, and that a conflicted file gets the current branch's version. `StatusFileItemsTest`
  gained 2 tests, for `inFolder` and `discardable`.
- **Verified:** offscreen renders of the folder menu with and without the item, and of the dialog for one file, for
  several files with new files kept, and during a search. Also a confirmed discard on a demo repository, which
  restored the folder's two modified files and kept its new file.

## Movable dialogs (branch `feature/draggable-dialogs`)

- **Why they couldn't move:** Navigation3's `DialogSceneStrategy` shows a dialog with Compose's common `Dialog`, which
  on desktop draws on the main window's canvas (`compose.layers.type` is unset, so `LayerType.OnSameCanvas`). There is
  no OS window or title bar, and `MaterialDialog` had no drag handling.
- **Drag strip:** the top 16 dp of every `MaterialDialog` move the dialog, with a move cursor and a small grip that
  brightens on hover. Every current dialog leaves that band free of controls. The dialog stays inside the window and
  is centered again each time it opens.
- **More handles:** `MaterialDialogScope.dialogDragHandle()` makes any element a handle. The Settings title uses it.
- **Layout:** a dialog layer only takes clicks inside its content's bounds, so a dialog that was only offset stopped
  taking clicks wherever it left its centered place. `MaterialDialog` now fills the space it's given and places the
  dialog itself. Two dialogs aren't Navigation3 destinations and are drawn inline: `AppInfoDialog` on the Welcome page
  and `CommitAuthorDialog` in the status pane. They are now centered in the area they're drawn in.
- **Verified:** an offscreen harness with a real Compose `Dialog` and Leaf's dialog properties. It checked centering,
  dragging by the strip, a click on a button moved fully outside the original bounds, that dragging the content doesn't
  move the dialog, clamping at both corners with no hidden overshoot, and the real Settings dialog dragged by its title
  and by the strip, with Accept still working.

## Compose Multiplatform 1.12.1 (branch `chore/compose-1.12.1`)

- **Bump:** `compose` in `gradle/libs.versions.toml`, from 1.12.0 to 1.12.1. That covers the Gradle plugin and every
  Compose library. Material icons stay on 1.7.3, which is versioned separately. Navigation3 (1.1.1) and Lifecycle
  (2.10.0) are unchanged, although the 1.12.1 release lists 1.1.2 and 2.11.0 alongside it.
- **Why:** 1.12.0 can throw "LayoutNode … not found in RectList" when a node is placed while its parent works out
  alignment lines. 1.12.1 merges androidx.compose 1.12.1, which fixes that in `MeasurePassDelegate` and adds
  `testRectListDuringAlignment_withLayoutModifier`. It also fixes a crash when selecting text with the mouse in a
  `SelectionContainer` (the commit message uses one) and one when Skia returns a null `ColorFilter`.
- **Verified:**
  - The full build passes with 124 tests, and every Compose artifact on the runtime classpath resolves to 1.12.1.
  - A dev run showed the log, side panel and status panes normally.
  - `createDistributable` with the JBR SDK built a bundle with the 1.12.1 jars that passes
    `codesign --verify --deep --strict`.
  - The offscreen harness ran the side panel and Files changed 8 times off the Swing thread without the crash. 1.12.0
    also passed 8 times, though, so the harness no longer reproduces it.

## Sort and view menu in Staged and Unstaged (branch `feature/status-sort-view`)

- **Menu:** the Staged and Unstaged headers have the Files changed sort and view button in place of the list/tree
  toggle: sort by Path, File name or Change type, the order, and Flat list, Split columns or Folder tree. The three
  panes share one setting.
- **Old toggle:** `AppConfig.ShowChangesAsTree` is gone, but its stored `show_changes_as_tree` is still read: if it was
  on and no files view has been saved yet, the panes start in Folder tree.
- **Staging works as before:** the hover Stage/Unstage button, double-click, Stage all and Stage selected, Ctrl/Cmd and
  Shift selection, and the right-click menus. Shift ranges now follow the order on screen. In the tree, folder rows
  also get the hover button. During a search it stages or unstages only the folder's matching files; the right-click
  menu's "Stage changes in the directory" still takes the whole folder.
- **Conflicts:** sorting by Change type lists conflicting files first, in both orders (`FileChangeKind.Conflicting`).
- **Tree:** single-child folder chains merge into one row with a file count, Up/Down and Left/Right move through it,
  and search opens the folders with matches. Each pane keeps its own closed folders while the tab is open.
- **Fixed along the way:**
  - A sort or view change left the list scrolled partway, because the list keeps its first visible row by key. It now
    scrolls to the selected file, or to the top. Files changed had this too.
  - Shift-clicking while the last selected file was hidden by the search threw an exception. It now adds the clicked
    file to the selection.
  - Closing a folder dropped its files from the selection, and its conflicts stopped counting as unsolved.
- **Removed:** `ui/tree_files/Tree.kt`, `ui/components/FileEntry.kt`, and the `list` and `tree` icons.
- **Tests:** `StatusFileItemsTest` (4) covers the change kinds, repeated paths, conflicts in a tree, and folders closed
  during a search. `FileRowsTest` and `SortSettingsCodecTest` gained one test each, for the conflict order and the old
  toggle.
- **Verified:** offscreen renders of both panes in all three views, against a repository with staged, unstaged,
  untracked and conflicting files. Checked the hover buttons, double-click and folder staging (on the real index),
  Shift and Ctrl selection, both right-click menus, search, the tree keys, a 290dp pane, the light theme, and Files
  changed. Also a run of the app.

## Upstream sync: repository cleanup and tab saving (branch `sync/upstream-2026-10-07`)

- **Merged:** `upstream/main` up to `415b3729`, the first sync since the fork point `62442f26`. It brings two commits:
  - `721611cd`: closing a tab closes the cached JGit repositories that no other tab uses, and opening a repository
    closes the one it opened only to check the path.
  - `415b3729`: tabs that haven't loaded yet are saved too, so restoring tabs lazily no longer drops them.
- **Conflicts:** imports and packages from the rename, and `GitCliModule` next to the new `ServicesModule`.
  `OpenRepositoryGitAction` came up as modify/delete, because the fork rewrote it, so its `use {}` was ported by hand.
  `data/services/GitProviderService.kt` landed under `com/jetpackduba/gitnuro/` without a conflict and was moved.
- **Fixed on top:**
  - Upstream picked the repositories to keep as each tab's working tree + `/.git`. A linked worktree's git dir is
    `<main>/.git/worktrees/<name>`, a submodule's is under `.git/modules/`, and Windows uses `\`. So closing any tab
    closed every open linked worktree's repository, which was then reopened on its next use. The keep list is now the
    tabs' `Open.path`, which is the cache key.
  - The JGit cache is a `ConcurrentHashMap`, because the cleanup iterates it while other tabs may add to it.
  - Upstream saved a tab that hadn't loaded as `repositoryPath.value`, which is `null` until something collects it.
    Tabs scrolled out of the tab bar never do. Launching with 20 saved tabs wrote 11 `null`s, and the next launch died
    in `loadPersistedTabs` with a `JsonDecodingException`, as would every launch until the saved tab list was
    cleared. Such tabs are now saved as the path they were created with, and a new empty tab isn't saved
    (`RepositorySelectionState.pathToPersist`).
- **Changed:** open tabs are saved as their git dir (`/repo/.git`) instead of the working tree. Both restore the same
  way. Linked worktree tabs were already saved as their git dir.
- **Tests:** `GitProviderServiceTest` (2) opens a main repository, a linked worktree and a submodule, and checks that
  cleanup keeps exactly the open ones. It fails on the linked worktree with upstream's key mapping.
  `RepositorySelectionStateTest` (4) covers the saved path per tab state. 118 tests in total.
- **Verified:** the merge alone builds and passes the 112 existing tests. With the fix, the 20-tab launch saves all 20
  paths, and the next launch starts and restores them. The dev prefs were backed up before these runs and restored
  after.

## Real storage for the Linux jar (branch `fix/linux-jar-storage`)

- **The problem:** `AppStorage` only counted a run as packaged when jpackage's launcher set `jpackage.app-version`.
  The Linux fat jar runs with `java -jar`, so it kept its settings under the dev names (`leaf-dev`, `LeafDevConfig`).
  That was the known issue in the 1.0.0 release notes.
- **The fix:** `fatJarLinux` writes `Leaf-Packaged: true` into the jar's manifest. `AppStorage` also counts a run as
  packaged when the jar it was loaded from has that attribute. `./gradlew :app:run` and IDE runs load module jars or
  class folders without it, so they keep the dev storage.
- **Tests:** `AppStorageTest` gained three tests. A jar with the attribute counts as packaged. Jars without it, or with
  another value, don't. Neither do folders, missing files, or files that aren't jars.

## Settings stored as JSON (branch `feature/datastore-json`)

- **Why:** DataStore Preferences' file format goes through its bundled protobuf, which calls `sun.misc.Unsafe`. JDK 25
  printed "A terminally deprecated method in sun.misc.Unsafe has been called" whenever Leaf read its settings, and a
  later JDK will remove the method. Upgrading DataStore didn't help (1.3.0-alpha11 still calls it).
- **JSON format:** `JsonPreferencesSerializer` (fork-only) stores the settings in `user_prefs.json`, each key with its
  type and value. It's plugged into DataStore with `PreferenceDataStoreFactory.create(storage = OkioStorage(...))`, so
  `DataStoreAppSettingsRepository` and the rest of the settings code are unchanged.
- **Migration:** `ProtobufPreferencesMigration` (fork-only) moves an existing `user_prefs.preferences_pb` into the
  JSON file once, then renames it to `user_prefs.preferences_pb.migrated`. Values already in the JSON file win. The
  `Unsafe` warning shows only on the launch that migrates.
- **Damaged file:** a `user_prefs.json` that can't be read is logged and replaced with the defaults.
- **Tests:** `JsonPreferencesSerializerTest` (9) covers every value type, special floating point values, empty files
  and the corruption cases. `ProtobufPreferencesMigrationTest` (3) covers migrating, no migration, and JSON values
  winning.
- **Verified:** a dev run migrated a copy of the real settings into `leaf-dev`, keeping all six of them and showing the
  warning once. The next run showed no `Unsafe` warning and didn't rewrite the file.

## Install task and unsigned default (branch `feature/install-task`)

- **`./gradlew :app:installMacApp`** (macOS only) builds `Leaf.app` and installs it into `/Applications`, or into
  `-PinstallDir=<dir>`.
  - If Leaf is running from there, it waits up to 5 s, then stops. It only counts processes whose command line starts
    with the app's executable.
  - It removes the old copy, then copies the new one with `ditto`.
- **Ad hoc signing by default:** `gradle.properties` sets `compose.desktop.mac.sign=false`, so builds no longer need
  `-Pcompose.desktop.mac.sign=false`. Signing with a Developer ID takes `-Pcompose.desktop.mac.sign=true`.
- **DataStore:** I tried 1.3.0-alpha11 to get rid of JDK 25's `sun.misc.Unsafe` warning. It still warns, because its
  bundled protobuf calls `Unsafe`, so the bump was reverted. CLAUDE.md lists the warning as known.
- **Verified:**
  - The default build came out ad hoc signed, and the 45 tests pass.
  - `installMacApp` refused while Leaf ran from `/Applications`, naming only the Leaf process.
  - It waited for a Leaf that had just quit, then installed a bundle that passes `codesign --verify --deep --strict`.

## Sorting and grouping in the side panel and Files changed (branch `feature/sort-and-group`)

- **Side panel:** Local branches, Remotes and Tags each have a sort button left of the count. It opens a menu:
  - Sort by Name (natural order, so `3.9.0` < `3.10.0`), Last commit, Last checked out (local branches, from the
    HEAD reflog) or Tag date (tagger date, or the commit date for lightweight tags).
  - Order: A → Z / Z → A, or Newest / Oldest first. Ties go by name; refs without a date go last.
  - Keep current branch on top (local branches, on by default).
  - Group by prefix, one setting for all three sections. Folders use the text before the first `/`; inside Remotes,
    each remote groups its own branches (`origin` → `feature`).
- The button is muted at the defaults and accent-colored otherwise. It shows the sort's name when that fits next to
  the section title, and a folder icon while grouping is on. Date sorts show compact ages (`now`, `3d`, `2w`, `5mo`).
- **Section headers** of the side panel are sticky.
- **Folder state** is saved per repository in `<common git dir>/leaf` (section `sidePanel`), so linked worktrees share
  it. By default only the folder of the current branch is open. Searching opens every folder with matches and hides
  the rest; clearing the search brings the saved state back. Saving sign-off settings now keeps the file's other
  sections.
- **Files changed:** the list/tree toggle became a sort and view menu:
  - Sort by Path, File name or Change type (Added → Modified → Renamed → Deleted, or "Modified first").
  - Show as Flat list, Split columns (file | directory, with a draggable divider; the directory drops its start,
    `…/screen/settings/memory`) or Folder tree (single-child folder chains merged, recursive file counts).
  - Up/Down move the selection in every view; Left/Right close, open and move between folders in the tree.
  - Default: Split columns by file name. Staged/Unstaged keep their old list/tree toggle.
- **Settings:** the side panel sorts and the Files changed view are JSON values in the DataStore settings file.
- **Code:** the sorting, grouping and tree logic is pure Kotlin in `domain/.../sorting/`. `GetRefDatesGitAction`
  reads the dates once per refresh and caches commit times by object id.
- **Tests:** 44 domain tests (sorting, grouping, tree, ages, reflog parsing, settings round-trip) and 8 temp-repo
  tests for the dates and the per-repository folder file, including an unreadable file, which is now replaced on the
  next save instead of blocking it.
- **Verified:** rendered offscreen against a temp repo with prefixed branches, a remote and tags, in both themes, with
  scripted clicks and keys. 1,500 refs sort and group in about 1 ms; one layout of 6,000 changed files takes about
  10–15 ms.


## Version 1.0.0 and the release workflow (branch `feature/release-workflow`)

- **Version 1.0.0** (app code 26) in `app/build.gradle.kts` and `AppConstants.kt`. `latest.json` stays at code 25
  until 1.0.0 is published, so installed copies don't announce a release that doesn't exist yet.
- **Release workflow rewritten:**
  - Runs on GitHub's own runners: `macos-latest` (Apple Silicon), `windows-latest`, and `ubuntu-latest` with `cross`
    for x86_64 and aarch64.
  - Uses the JetBrains JDK 25.
  - Fails early when the fonts are Git LFS pointers, when the Rust library is missing, or when the tag doesn't match
    `projectVersion`.
  - Names files `Leaf-<version>-<platform>`, each with a `.sha256`.
  - Creates a draft release with the built-in `GITHUB_TOKEN`.
  - Triggers on `leaf-*` tags only, plus manual build-only runs.
- **Windows installer:** `leaf.iss` now names it `Leaf-<version>-windows-x64-setup.exe`.
- **Tags:** `origin` fetches only `leaf-*` tags, and Gitnuro's tags are fetched into `refs/upstream-tags/*`. The copies
  of Gitnuro's tags in local `refs/tags` were removed, and all 24 copied Gitnuro tags were deleted from
  `zzhelev/Leaf`, which now has only `leaf-*` tags.
- **`leaf-0.1.0`:** published by hand as a pre-release with the macOS DMG.
- **Verified locally:** the 1.0.0 DMG packages, the app reports 1.0.0, the tests pass, and the workflow YAML parses.
  The workflow hasn't run on GitHub yet.

## Native access for the Rust library and JNA (branch `chore/native-access`)

- **Launch option:** `--enable-native-access=ALL-UNNAMED` is in `compose.desktop.application.jvmArgs`, so packaged
  apps on every platform and `./gradlew :app:run` start with native access.
- **Linux fat jar:** its manifest gets `Enable-Native-Access: ALL-UNNAMED`, for `java -jar`.
- **Why:** JDK 25 printed "A restricted method in java.lang.System has been called" when Leaf loaded the Rust library,
  and a later JDK will refuse to load it at all.
- **Verified:** the packaged app was launched with a temp repo. stderr stayed empty, and the Rust file watcher reported
  a change.

## Leaf on `main` (branch `chore/use-main`)

- **`main` is Leaf's branch.** The local `fork/main` became `main`, tracking `origin/main`, which is the default branch
  of `zzhelev/Leaf`. The local mirror of upstream's `main` was dropped. Syncing goes through `upstream/main` and is a
  merge, because `main` is published.
- **Update check:** reads `latest.json` from `refs/heads/main`.
- **Before the first push**, the fork's commits were rewritten to the identity `Zhelyazko Zhelev <zzhelev@gmail.com>`.
  Only the author and committer emails changed: trees, messages and author dates are the same. The repo's own
  `.git/config` now sets that identity.
- **Funding:** `.github/FUNDING.yml` points the Sponsor button at `zzhelev`.
- **CLAUDE.md** records the branch model, the commit identity, and how to push: over HTTPS through `gh`, because the
  machine's SSH key belongs to another account.

## AGPL for Leaf's own code, and the Leaf name (branch `feature/leaf-identity`)

- **AGPL-3.0-only:** the 18 files written for Leaf carry SPDX headers, and the license text is in
  `LICENSES/AGPL-3.0-only.txt`. The files are the git CLI adapter, the JGit auto-gc guard, the storage names, and the
  fork's tests.
- **GPL-3.0-only:** files that come from Gitnuro stay under `LICENSE`, even where the fork edited them.
- **Combined work:** GPL-3.0 section 13 lets Leaf be distributed as a combined work. AGPL-3.0 section 13, on network
  interaction, then applies to the whole.
- **README:** the License section explains the split, and the credit's license sentence matches it. A new "Name and
  logo" section declines trademark rights to the Leaf name and logo (section 7(e) of both licenses).
- **CLAUDE.md:** documents the header rule for new files.

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
