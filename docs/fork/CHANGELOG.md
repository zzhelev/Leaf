# Fork changelog

This file covers fork-only changes on `main` (called `fork/main` until 2026-10-05). Upstream history is in git.

## Worktrees in the side panel (branch `feat/worktree-sidebar`)

Phase 1.4 of `docs/fork/PLAN.md`.

- **Before:** Leaf read the worktrees (Phase 1.2) but showed nothing of them. A branch that an agent's worktree had
  checked out looked like any other, and its menus offered Checkout, Delete and Rename, which the guards (Phase 1.3)
  then refused.
- **Now:**
  - A **Worktrees** section after Local branches lists every worktree, main one first, in two lines each:
    - the folder name (bold for the tab's own), a lock icon when it's locked, and the last commit's age;
    - the branch, or "rebasing x", "bisecting from x" or "detached at abc1234", a dot when it has uncommitted changes
      (in the conflict color when some are conflicts), and `↑ahead ↓behind` versus the base branch.
  - The main worktree has its own icon. A worktree whose folder was deleted is greyed out, marked "missing".
  - The tooltip has the full path, the change counts, the comparisons to the base branch and to the upstream, the lock
    reason, and for a missing folder git's reason and that `git worktree prune` would remove it.
  - A click selects the worktree's commit in the log, and the menu copies the path. The side panel's filter matches
    worktrees by folder name, path or branch. When git can't list them, one line says so, with git's error in its
    tooltip.
  - In the branch list, a local branch that another worktree uses gets a folder icon, with that worktree's folder name
    unless it repeats the branch's last part, as agents' folders do (`claude/x` in `.claude/worktrees/x`). The log's
    branch chips get the icon. The tooltip says how the worktree uses the branch and what that rules out, then
    describes the worktree.
  - The branch menus, in the side panel and in the log, leave out what the guards would refuse: Checkout while another
    worktree uses the branch, Delete while any worktree does, and Rename while one rebases it or bisects from it.
    Double-clicking such a branch still shows the checkout refusal.
- **Kept up to date:** each tab holds the worktree list and refreshes it with its branches, log or status, after
  everything else. A refresh runs git in every worktree, so a tab runs one at a time, and the requests that come
  meanwhile share the next one. What agents change in other worktrees shows up with the next such refresh (F5, any
  operation, a change in the tab's own worktree). Watching for it is Phase 1.5.
- **Fixed on the way:**
  - `git worktree list` shows a worktree that rebases or bisects as detached. Worktrees now also name that branch, read
    from their git dirs the way the guards read them, and such a worktree is compared to the base by that branch, not
    by the commit it stopped at.
  - A rebase of a detached HEAD, or a bisect started on one, has no branch. The guards compared the commit that
    `BISECT_START` then holds with branch names, and no longer do.
  - The 1.3 entry's "menus still offer Delete for a branch that another worktree uses" no longer holds.
- **Tests:** 28 new.
  - `:data` (4): `BranchWorktreesTest` reads each worktree's branch as a full name, and finds none for a rebase or a
    bisect started on a detached HEAD, as git does. `WorktreesTest` gets the rebased and bisected branches attached to
    the right worktrees, and compares those worktrees by them.
  - `:domain` (19): which worktree uses a branch and what that rules out (`WorktreeBranchUsersTest`), the refresh that
    lets one call through at a time (`ConflatedRunnerTest`), which refreshes include the worktrees, and the rows:
    order, names, labels, ages, the filter, and when the branch list names a worktree (`WorktreeRowsTest`).
  - `:app` (5): the branch menu, with its labels read in a composition (`BranchContextMenuWorktreesTest`), and the
    compact ahead/behind text.
  - **Mutation check:** 26 mutations, 24 caught. The two missed are equivalent: matching worktrees by path without
    making it canonical (git writes and lists real paths), and a shortcut for a blank filter (the filter already
    trims it), which is now gone. One mutation made `ConflatedRunnerTest` hang instead of fail; its tests now time out.
  - Checked once with a throwaway harness, deleted afterwards: the real side panel and log, rendered offscreen for a
    repository with an agent's worktree, a rebase stopped on a conflict, a locked, a detached and a deleted worktree,
    at the default width (220 dp) and at 320 dp, with tooltips, a branch's right-click menu and the filter.
  - `./gradlew build` passes, with 612 tests (21 in `:app`, 483 in `:data`, 101 in `:domain`, 7 in `:common`).
- **Not yet:**
  - A per-repository base branch setting. The 1.2 entry expected it with 1.4. The base is still origin's default
    branch, else `main`, else `master`.
  - Double-clicking a worktree or a marked branch to open that worktree (Phase 2a).
  - At the default side panel width, a worktree name next to a long branch name leaves little room for the branch.

## Author, Date and Commit columns in the log (branch `feat/log-columns`)

- **Before:** the log showed the graph, the message with its branch and tag chips, and an unnamed date. The author was
  only in the graph node's tooltip, and the hash only in the commit details. The graph column's width, set by
  dragging, was kept in memory per tab.
- **Now:** a columns menu checks Author, Date and Commit. It opens from a new button next to search in the log's
  header, or by right-clicking anywhere on the header.
  - By default only Date is on, so the log looks as before, with a "Date" header.
  - **Author** shows the avatar and the name. Its tooltip gives the email, and who committed when that's someone else.
  - **Commit** shows the short hash in monospace, with the full hash in its tooltip.
  - **Show time**, in the menu while Date is on, adds the time in Settings' date format, and widens Date to fit it.
  - **Widths:** each column's header divider resizes it, and Message takes the rest. A divider stops where Message
    would drop below 200 dp.
  - **Narrow log:** checked columns give way until the message has 200 dp, Commit first, then Author, then Date. The
    menu marks them "Needs more room". This resolves upstream's `TODO Min size for message column`.
  - **Saved:** the columns, their widths, the time and the graph's width are one setting for every tab, kept across
    restarts (`log_columns` in `user_prefs.json`). "Reset columns" restores the defaults.
  - **Graph width:** the graph is as wide as its lanes, up to the width set by dragging (120 dp by default, the old
    limit). Dragging past the lanes keeps a wider size set in a busier repository.
  - The commit row's menu has **Copy commit hash**.
- **Decided:** columns can't be reordered yet, but the setting stores them in display order, so reordering needs no
  migration. Clicking a header doesn't sort: the log stays in graph order. Graph and Message can't be hidden.
- **Tests:** 22 new in `:domain` (`LogColumnsTest` 15, `LogColumnsCodecTest` 7).
  - 20 mutations of the column rules and the codec: 19 were caught. The other removed a branch of the graph drag rule
    that couldn't change the result, so the code lost that branch, and its remaining branch's mutation is caught.
  - Checked once with a throwaway offscreen harness, deleted afterwards: the real `Log` at 900, 560, 470 and 380 dp,
    in all three row densities, light and dark, with the time, the menu from the button and from a right-click,
    unchecking a column in the menu, dragging a column and the graph, search dimming, and the row menu.
  - `./gradlew build` passes, with 647 tests (18 in `:app`, 518 in `:data`, 104 in `:domain`, 7 in `:common`).
- **Not checked offscreen:** hover tooltips, which don't show in an `ImageComposeScene` (not even the graph node's
  existing one).

## libssh is gone: SSH runs OpenSSH's programs (branch `feat/retire-libssh`)

Stage 5 of `docs/fork/remote-operations.md`.

- **Before:** after stages 1 to 4, the Rust library still carried libssh-rs, with its vendored libssh and OpenSSL, for
  three things: JGit's SSH transport (the fallback when git isn't used), the built-in LFS client's
  `git-lfs-authenticate` over SSH (without git-lfs), and SSH commit signing.
- **Now:** all three run OpenSSH's programs, with the user's ssh config, agent, known_hosts and security keys. Leaf has
  no SSH code of its own.
  - **SSH signing** runs ssh-keygen as git does (`SshProgramSigner`). Keys held by ssh-agent, `key::` public keys,
    `gpg.ssh.program` (such as 1Password's `op-ssh-sign`), `gpg.ssh.defaultKeyCommand` and `~/` paths now work.
    - Leaf's dialog asks for a passphrase once per key file for the session, shared with push and fetch, and again
      after a wrong one. libssh's signer asked at every signature, and showed the passphrase dialog again for any
      failure, a missing key file included.
    - Failures show ssh-keygen's own message.
  - **The built-in LFS client over SSH** runs the system's ssh with git-lfs's command and with the ssh that git-lfs
    picks (`core.sshCommand`, `includeIf` included), and asks with Leaf's dialogs. A warning from ssh no longer fails
    it.
  - **JGit and SSH remotes:** when JGit runs (the setting off, no usable git, or an LFS push that git wouldn't upload),
    it refuses an SSH remote before connecting, and the error says what makes git run. HTTPS, `file://` and local
    remotes keep JGit's fallback.
  - **Build:** `rs/Cargo.toml` lost `libssh-rs` and `libssh-rs-sys`, and `kotars` and `jni`, which nothing used. Perl
    is no longer needed.
    - Here, a cold release build of `rs/` went from 97 s to 72 s, the debug build from 88 s to 18 s, and
      `libleaf_rs.dylib` from 4,154,464 to 595,072 bytes.
    - The About dialog no longer credits LibSSH.
- **Lost:** SSH remotes without git, or with "Use git for remote operations" off, and LFS uploads over SSH without
  git-lfs.
- **Fixed on the way:**
  - JGit's fetch of all remotes reported nothing when a remote failed, as `HandleTransportGitAction` returns failures
    instead of throwing them. It now names each remote that failed (upstream code).
  - For a repository with one remote and no upstream, the built-in LFS client asked an SSH remote about
    `<path>.git/info/lfs`, not the repository's path, which git-lfs sends.
- **Tests:** 50 new, 9 removed (`SshRemoteSessionTest`, with the code it tested). None are skipped here.
  - `SshProgramSignerTest` (18): a fake ssh-keygen checks git's arguments, public keys with `-U`, `~/` and relative
    paths, `defaultKeyCommand`, the program from `gpg.ssh.program` and not `gpg.program`, the errors, and an older
    ssh-keygen's prompt without a key path.
  - `SshProgramSignerRealSshKeygenTest` (8): the real ssh-keygen signs commits and tags that `git verify-commit` and
    `verify-tag` accept, with a key file, a `.pub` path and a key only an ssh-agent has. It asks for a passphrase once,
    again after a wrong one, and cancels when the dialog is closed. A signed commit is byte for byte `git commit -S`'s.
  - `AuthenticateLfsServerWithSshGitActionTest` (8) and `GitCliSshTest` (3 new): the command matches what git-lfs 3.8
    sends, for scp-like and `ssh://` URLs, and the real sshd gets the same command from Leaf as from git-lfs. Also:
    which ssh runs, `includeIf`, a refusing host, an unknown host key and a passphrase through the dialogs.
  - `JGitWithoutSshTest` (7) and `RemoteOperationsBackendTest` (1 new): each reason, scp-like and `insteadOf` URLs,
    a fetch of several remotes, a clone, a push, and a `file://` remote that still works.
  - `GetLfsUrlGitActionTest` (2, `:app`), `AskpassPromptTest` (1 new), `AskpassAnswersTest` (2 new).
  - 38 mutations were each caught. One for the JGit fetch didn't compile and was rewritten. One planned for
    `core.sshCommand` couldn't change the behavior, so the code lost that branch instead.
  - Checked once with throwaway tests, deleted afterwards: the rebuilt library loads and its file watcher reports a
    change. A dev run (`:app:run`) started cleanly.
  - `./gradlew build` passes, with 625 tests (18 in `:app`, 518 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** Windows (Git for Windows' ssh, ssh-keygen and Git Bash), a security key, `op-ssh-sign`, a real LFS host
  over SSH.

## LFS files are downloaded with git-lfs when it's installed (branch `feat/git-lfs-transfers`)

Stage 4 of `docs/fork/remote-operations.md`.

- **Before:** clone and pull always downloaded LFS files with Leaf's built-in client, which asks the server about one
  file at a time, finds the server only through `.lfsconfig` or the remote's URL, and reaches SSH remotes through
  libssh. Uploads already went through git-lfs (stage 1), and switching branches ran git-lfs once per file.
- **Now:** when git-lfs is installed, `git lfs fetch` downloads a clone's or a pull's LFS files before JGit checks them
  out, and the built-in client only reads them from the repository.
  - git-lfs asks the server about all of them at once, follows the LFS settings in git's config, asks for credentials
    through git's helpers and Leaf's dialogs, and reaches SSH remotes with the system's ssh.
  - The clone dialog and the processing screen show "Downloading LFS objects", and Cancel stops it.
  - It only runs for a commit whose `.gitattributes` uses LFS, so pulls in repositories without LFS don't pay for it.
  - When the download fails, the pull or the clone stops before anything is checked out or merged, with git-lfs's
    message.
  - Without git-lfs, the built-in client downloads as before.
- **Fixed on the way:**
  - A clone made by Leaf had none of git-lfs's hooks, since `git clone --no-checkout` doesn't run git-lfs, so its
    pushes fell back to JGit's upload. A clone that uses git-lfs now gets them (`git lfs update`).
  - When no credential helper applies to the URL (the cache setting off, no helper of your own), git-lfs asks for
    credentials in its own words, `Username for "https://host"`, which Leaf showed in its generic prompt dialog. It's
    now the usual credentials dialog, one for both questions.
  - git-lfs's progress, which it only shows on a terminal unless told otherwise, and writes to stdout, now shows on the
    processing screen too, also while a push uploads ("Uploading LFS objects").
- **Tests:** 12 new, all in `:data`, none skipped here.
  - `GitCliLfsTest` (8) runs git-lfs against `FakeLfsServer`, a Git LFS server on 127.0.0.1: a clone that asks through
    the dialog and gets the hooks, a clone without git-lfs (left to the built-in client), a repository whose
    attributes don't use LFS (git-lfs never runs), a clone whose files can't be downloaded (no folder left),
    git-lfs's own prompts, a pull's new files downloaded before the merge, a pull whose files can't be downloaded
    (nothing changes), and a push's upload progress.
  - `GitCliSshTest` (8, 1 new): LFS files go up and down over SSH, where git-lfs asks the host for the LFS server
    through the system's ssh.
  - `AskpassPromptTest` (1 new), `GitOutputParsersTest` (1 new, the porcelain with git-lfs's progress in it) and
    `ProcessRunnerTest` (1 new, both streams passed on as written).
  - Eleven mutations were each caught, among them every commit or none counted as using LFS, git-lfs assumed
    installed, the pull or the clone going on after a failed download, no hooks, no forced progress, stdout's
    progress ignored, and git-lfs's prompts not understood. Two were only caught after adding the tests for
    attributes without LFS and for a clone whose download fails.
  - Checked once with throwaway tests, deleted afterwards:
    - through the app's real Dagger graph, `CloneViewModel` and Leaf's own LFS filter, against a local LFS server:
      the clone dialog showed "Downloading LFS objects", the file was right, `git status` was clean, git-lfs's four
      hooks were there, and only git-lfs asked the server for the file;
    - JGit's ordinary checkout writes pointer files without git-lfs's filters set up, and the content with them.
  - `./gradlew build` passes, with 584 tests (16 in `:app`, 479 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** Windows, and a real LFS host (GitHub, GitLab).

## A linked worktree's HEAD reflog stays in the linked worktree (branch `fix/worktree-head-reflog`)

- **Before:** in a tab on a linked worktree, JGit 7.7 logged every move of HEAD in the main worktree's `logs/HEAD`
  (`architecture-notes.md` §6). That covered commit and amend, every checkout, reset, merge, pull, rebase, cherry-pick,
  revert, and renaming the current branch.
  - In the main worktree, `git reflog`, `HEAD@{1}` and `@{-1}` followed the linked worktree's history, so
    `git checkout -` there switched to an agent's branch.
  - In the linked worktree, `git reflog` showed none of what Leaf did.
  - Leaf's "last checked out" order (`GetRefDatesGitAction`) missed a linked worktree's own checkouts, and the main
    worktree's tab counted them.
- **Now:** the entries go to the linked worktree's own `logs/HEAD`, as with git, and JGit reads them back from there.
  Branch reflogs are shared and stay in the common git dir. Entries written in the wrong place before stay where they
  are: an entry doesn't say which worktree wrote it.
- **How:** JGit's `RefDirectory` takes the folder for HEAD's log from `FS.resolve(<common git dir>, "logs")`. For a git
  dir with a `commondir` file, `JGit.open` gives `PosixFs` or `WindowsFs` a fork-only `LinkedWorktreeLogs`, and their
  `resolve` answers that call with `<git dir>/logs`.
  - JGit 7.7.1, 7.8.0 and master have the same bug, reported as
    [eclipse-jgit/jgit#306](https://github.com/eclipse-jgit/jgit/issues/306) (`architecture-notes.md` §6). Drop the
    override once a JGit release fixes `RefDirectory.logFor`.
  - Not fixed: an interactive rebase that squashes, in a linked worktree, still leaves the main worktree's
    `ORIG_HEAD` in it (§6, side finding).
- **Tests:** 12 new, in `:data`, with the git CLI as the reference for where each entry lands.
  - `LinkedWorktreeLogsTest` (11): the operations in the table of §6 through Leaf's `JGit`, then `git reflog` in each
    worktree and the main worktree's `logs/HEAD` unchanged. Also `@{-1}`, JGit reading the entries back, branch logs
    staying shared, the main worktree's own tab, a nested worktree, a bare repository's worktree, `WindowsFs`, and
    `newInstance` copies.
  - `GetRefDatesGitActionTest` (1): a checkout made through Leaf in a linked worktree counts for its tab only.
  - Six mutations were each caught: no redirect in `PosixFs`, none from `JGit.open`, copies that drop it (`PosixFs`
    and `WindowsFs`), redirecting every name, and comparing paths without making them canonical.
  - `./gradlew build` passes, with 572 tests (16 in `:app`, 467 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** Windows, and the running app: no screen changes, and the tests open the repositories through Leaf's
  own `JGit`, as a tab does.

## Worktree folders in error messages stand out and can be copied (branch `feat/worktree-error-paths`)

A follow-up to the worktree guards, from their manual check.

- **Before:** the checkout, delete and rename refusals named the worktree's folder in quotes in the middle of a
  sentence, which long agent paths made hard to read. The red message box in dialogs couldn't be selected, so the
  folder couldn't be copied.
- **Now:**
  - Those messages put the folder on a line of its own, between blank lines, in the monospace font
    (`getStyledErrorText` and `monospaceParts` in `Errors.kt`). The rename that couldn't move a worktree's HEAD shows
    its `git switch` command the same way.
  - `DialogWarning`, the red box in the delete, rename, reset, confirmation and Check for updates dialogs, can be
    selected and copied, with a selection color that shows on red. `ErrorDialog`, where checkout refusals appear, was
    selectable already and now shows the styled text too.
- **Tests:** none new, as this is display only. Offscreen renders of the delete and rename dialogs, with a selection
  dragged over the delete message, and of `ErrorDialog` with a checkout refusal.

## A branch that a worktree uses can't be deleted, and renaming it moves the worktree along (branch `feat/worktree-delete-guard`)

The deletion half of Phase 1.3 in `docs/fork/PLAN.md`, and the same fix for renaming, which PLAN.md doesn't list.

- **Before:**
  - JGit deleted a branch that another worktree had checked out (`architecture-notes.md` §4), even without force once
    it was merged. That worktree was then on a branch that doesn't exist: git showed `No commits yet` there, with every
    file staged as new. A branch that the tab's own worktree was rebasing could be deleted too: HEAD is detached
    during a rebase, so the menu offered Delete.
  - JGit's rename moved only the HEAD of the worktree the tab shows. Another worktree on the branch was left on the old
    name, which no longer exists, with the same result.
- **Now:**
  - Deleting is refused, with or without force, when a worktree uses the branch, the tab's own included, as git refuses
    `-d` and `-D` ("cannot delete branch 'x' used by worktree at '<path>'"). A worktree uses a branch as for the
    checkout guard: checked out, rebasing it, or bisecting from it, each with its own message.
    `DeleteBranchError.BranchUsedByWorktree` isn't a `DeleteRefError`, so the dialog says why and disables Delete,
    rather than offering "Delete anyway".
  - Renaming moves the HEAD of every other worktree that has the branch checked out to the new name, as git's
    `branch -m` does. Uncommitted changes there stay as they were, and a worktree whose folder was deleted is moved
    too.
  - Renaming is refused while a worktree, the tab's own included, rebases the branch or bisects from it, as git refuses
    ("branch refs/heads/x is being rebased at <path>"). Finishing the rebase would bring the old name back.
  - When a worktree's HEAD can't be moved, because another program holds `HEAD.lock`, the branch stays renamed, as
    with git ("branch renamed to x, but HEAD is not updated"). `RenameBranchError.WorktreeHeadNotMoved` names the
    worktree and says to run `git switch <new name>` there, which keeps its changes.
  - The rename dialog shows errors under the field. Before, a failed rename only enabled the field again. After these
    refusals, and after a HEAD it couldn't move, Rename branch is disabled, as Delete is: no other name would help.
- **How:**
  - `worktreesUsing` (`data/git/worktrees/BranchWorktrees.kt`) lists every worktree that uses a branch, the current
    one included, with its git dir. The checkout guard's `findOtherWorktreeUsing` now filters that list.
  - `refuseDeletingIfUsedByWorktree` runs in `DeleteBranchGitAction` before the merge check, as in git, so force
    doesn't get past it.
  - `RenameBranchGitAction` checks before JGit renames. Then it opens each other worktree's git dir and points its
    HEAD to the new branch with a `RefUpdate.link`, as git's `replace_each_worktree_head_symref` does after renaming.
  - That `link` writes no reflog entry. git writes "Branch: renamed ..." to the worktree's own reflog, but JGit 7.7
    writes a linked worktree's HEAD reflog to the main worktree's `logs/HEAD`, as if the main worktree had moved.
- **Tests:** 7 more in `DeleteBranchGitActionTest`, 2 more in `BranchWorktreesTest` (whose other tests now also check
  each worktree's git dir), and the new `RenameBranchGitActionTest` (10).
  - Each refusal is compared with git's: the path in "used by worktree at" from `git branch -D`, and in "is being
    rebased at" and "is being bisected at" from `git branch -m`. Renames are compared with `git branch -m` on an
    identical repository: the worktree's HEAD and its uncommitted change, and the result when HEAD is locked.
  - Cases: from the main and from a linked worktree, the tab's own branch, a rebase in the tab's own worktree, a
    bisect, a deleted folder, two worktrees on one branch, a worktree that left the branch, and the main worktree's
    reflog.
  - Mutation check: 10 of 11 mutants caught, among them the guard dropped, the guard only without force, the tab's own
    worktree left out, a rebase or bisect let through, no HEAD moved, the reflog entry kept, and a lock failure counted
    as moved. The one left, moving the tab's own HEAD again, is equivalent: JGit has moved it already.
- **Not handled:**
  - Refs in a reftable, as for the checkout guard.
  - PLAN.md wants "Remove worktree and delete branch" in place of the disabled button. That comes with Phase 3.
  - The menus still offer Delete for a branch that another worktree uses. It's refused once confirmed. The branch list
    learns which worktree has a branch in Phase 1.4.
  - JGit's misplaced HEAD reflog in general: every commit or checkout in a tab on a linked worktree is logged in the
    main worktree's reflog, not its own. Found here; `architecture-notes.md` §6 has the details and fix options.

## Deletes no longer follow symbolic links (branch `fix/delete-without-following-links`)

- **Before (from upstream):** Kotlin's `File.deleteRecursively` follows symbolic links to folders, even when called on
  the link itself: it deletes the files in the folder that a link points to, then the link. Leaf used it on user data:
  - **Delete** in the Status pane (`DeleteFileGitAction`). Deleting an untracked link to a folder, or an untracked
    repository with such a link in it (the Status pane lists it as one entry), emptied the folder the link pointed to,
    outside the repository too.
  - **Delete submodule** (`DeleteSubmoduleGitAction`), for the submodule's folder and its git dir in `.git/modules`.
    git checks out a link committed in the submodule's repository as it is, whatever it points to, so deleting the
    submodule emptied that folder. So did a link the user made, such as `hooks` linked to a shared hooks folder.
- **Now:** both use JGit's `FileUtils.delete` with `RECURSIVE` and `SKIP_MISSING`, as the clone cleanup already did. It
  checks for folders without following links, and deletes a link as a link.
  - Delete still reports a failure, now at the first file it can't remove. A file that's already gone still counts as
    deleted.
  - Delete submodule ignored the files it couldn't remove. It now reports them as a failed task.
  - Leaf's own temporary folders use it too, still best effort (`IGNORE_ERRORS`): `TempFilesManager.clearAll` when the
    window closes, and the askpass socket's folder. Nothing puts links there, but Leaf's code no longer calls
    `deleteRecursively` at all. `CLAUDE.md` now says not to.
- **Upstream:** Gitnuro's `main` has the same code in both actions and in `TempFilesManager`. Worth reporting there.
- **Tests:** 6 new, all in `:data`, skipped on Windows, where creating links needs Developer Mode or admin rights.
  - `DeleteFileGitActionTest` (4) takes its entries from the status, as the Status pane does: a link to a folder outside
    the repository, and an untracked repository with such a link in it. Also a file that's already gone, and a file
    that can't be removed (an error).
  - `DeleteSubmoduleGitActionTest` (2): a submodule with a link committed in its repository, an untracked link, and a
    folder with a link; and a submodule whose git dir has `hooks` linked to a shared folder.
  - Seven mutations were each caught: `deleteRecursively` back in each of the three places, ignoring errors, not
    skipping missing files, and not deleting the submodule's folder or its git dir.
  - `./gradlew build` passes, with 541 tests (16 in `:app`, 436 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** Windows.

## A branch that another worktree uses can't be checked out (branch `feat/worktree-checkout-guard`)

The checkout half of Phase 1.3 in `docs/fork/PLAN.md`.

- **Before:** JGit's checkout knows nothing of other worktrees (`architecture-notes.md` §3). Double-clicking a local
  branch that another worktree had checked out put both worktrees on it, and a commit in either then looked, in the
  other, like uncommitted changes that undo it. Double-clicking `origin/develop` did the same with a local `develop`
  in another worktree, and its fast-forward moved `develop` under that worktree's files.
- **Now:** both are refused, as git refuses them ("'develop' is already used by worktree at '<path>'"), and nothing
  changes. `CheckoutBranchError.BranchUsedByWorktree` names the branch and the worktree's folder.
  - A worktree uses a branch when it has it checked out, or when its HEAD is detached while it rebases the branch or
    bisects from it (`WorktreeBranchUse`). git refuses all three, and each has its own message.
  - `origin/develop` gets the error straight away, without the fast-forward question: `GetRemoteBranchCheckoutGitAction`
    refuses too, so the view model checks out without a fast-forward, which reports it.
  - The branch that the tab's own worktree has checked out still works: checking it out, and fast-forwarding it from
    its remote branch, even when another worktree has it too, as in git.
  - Creating a local branch from a remote branch is unchanged.
- **How:** `findOtherWorktreeUsing` (fork-only `data/git/worktrees/BranchWorktrees.kt`) reads the worktrees' git dirs
  as git's `die_if_checked_out` does: the main worktree's (not when the repository is bare), then each
  `<common git dir>/worktrees/<name>`.
  - HEAD comes from the `HEAD` file, a rebase from `rebase-merge/head-name` or `rebase-apply/head-name` (not for
    `git am`), and a bisect from `BISECT_START` when `BISECT_LOG` exists.
  - The folder comes from the `gitdir` file, also when git wrote it relative (`worktree.useRelativePaths`). A worktree
    whose folder was deleted still counts, as in git.
- **Why not `git worktree list --porcelain -z`:**
  - Checkout runs JGit and works without git, so the guard should too. Through the CLI, a missing or misconfigured git
    would either block every checkout or quietly drop the guard.
  - `worktree list` doesn't say which branch a worktree is rebasing or bisecting.
  - No process per double-click: without linked worktrees, the check is one directory listing.
- **Tests:** `BranchWorktreesTest` (14), the new `CheckoutBranchGitActionTest` (6), 3 more in
  `CheckoutRemoteBranchGitActionTest` and 2 in `GetRemoteBranchCheckoutGitActionTest`.
  - Each refusal is compared with git's: the path in "already used by worktree at", and
    `git fetch . origin/develop:develop` for the fast-forward. `TestGitCli.runFailing` runs git expecting it to fail.
  - Cases: sibling worktrees and nested ones (`.claude/worktrees/agent-1`), from the main and from a linked worktree,
    rebases with both backends, a bisect, a deleted folder, relative paths, a bare main repository, and the tab's own
    branch when two worktrees have it.
- **Not handled:**
  - Refs in a reftable (`git init --ref-format=reftable`, opt-in before Git 3.0). A worktree's `HEAD` file is then a
    stub, and JGit 7.7 reads every linked worktree's HEAD from the shared reftable, so it reports the main worktree's
    branch for all of them. Leaf doesn't see their branches, in the tab or in this guard. `BranchWorktreesTest` pins
    this, so a JGit that reads them will fail the test.
  - PLAN.md wants a "Switch to that worktree" action instead of a plain error. That comes with Phase 2, and the
    error has the path for it.
  - Deleting a branch that a worktree uses, the other half of Phase 1.3. It's refused now (see the entry above).

## Clone and submodules run the git CLI (branch `feat/git-cli-clone`)

Stage 3 of `docs/fork/remote-operations.md`.

- **Before:** clone, and adding and updating submodules, ran JGit over Leaf's own SSH and HTTPS code.
- **Now:** they go through the git CLI and the system's ssh, with the askpass dialogs of stage 1 and the same "Use git
  for remote operations" setting.
  - **Clone** runs `git clone --no-checkout`, then JGit checks the files out with Leaf's built-in LFS, as it did after
    its own clone, so git-lfs isn't needed. The clone dialog shows git's progress ("Receiving objects"), then JGit's
    ("Checking out files"). An empty repository is cloned without a checkout.
  - **Clone submodules** now clones them, with the submodules inside them (`git submodule update --init --recursive`).
    With JGit it did nothing: the checkout came after JGit's own clone, which ignores submodules then, and Leaf only
    registered them in the config, whether the box was ticked or not.
  - **Initialize** and **Update** in the Submodules section run `git submodule update --init --recursive` for that
    submodule, so nested submodules come too. **Add submodule** runs `git submodule add`. git checks submodules out
    itself, with git-lfs when it's installed.
  - **A clone that fails or is cancelled leaves nothing behind.** The clone dialog used to create the folder first,
    so a failed or cancelled clone left an empty folder behind. Now the folder is removed, or emptied when it was an
    empty folder that the user chose. A folder with files in it is refused by git and left as it is. Only a
    clone whose submodules failed is kept, as with `git clone --recurse-submodules`, and the error says where it is.
    Leaf removes the clone without following symbolic links in it.
  - **Errors** use stage 1's explanations with git's output: no access, no connection, rejected credentials, a host
    key problem. The clone dialog's error box now scrolls once it's 200 dp tall, so a long output no longer pushes
    the Clone and Cancel buttons out of the dialog.
- **Also fixed:** `GitCli` logs every git command line, and a clone's URL may hold a password
  (`https://user:token@host/...`). The password is now hidden there and in errors.
- **Different from JGit:** since git 2.38.1, a submodule from a local path or a `file://` URL is refused unless
  `protocol.file.allow=always` is set, as in a terminal. JGit had no such check.
- **Tests:** 21 new, all in `:data`, none skipped here.
  - `GitCliCloneTest` (12) clones from repositories on disk: the checkout and git's progress, an empty repository, a
    missing one (no folder left), a chosen empty folder (left empty), a folder with files (left as it is), nested
    submodules, submodules not asked for, submodules that fail (the repository is kept), and updating and adding a
    submodule, with their failures.
  - `GitCliHttpsTest` (10, 3 new): a clone asks through the dialog; cancelling while git waits for the dialog leaves
    no folder; cancelling while the submodules are cloned removes the clone but not the files that a symbolic link in
    it points to.
  - `GitCliSshTest` (stage 1's `GitCliSshPushTest`, renamed; 7, 1 new): a clone over SSH asks about an unknown host key.
  - `RemoteOperationsBackendTest` (12, 3 new) covers the choice for a clone, and `GitCliTest` (10, 2 new) the hidden
    password.
  - Fourteen mutations were each caught, among them a cleanup that follows symbolic links (Kotlin's
    `deleteRecursively`), no cleanup, removing a kept clone with failed submodules, a folder that had files or was
    chosen empty, checking out an unborn HEAD, skipping the checkout, `--recursive`, `--init` and `--name` dropped,
    submodules cloned when not asked for, progress sent to the processing screen, and no password hiding.
  - Checked once through the app's real Dagger graph and `CloneViewModel` (a throwaway test, deleted): a clone with
    the git CLI, checked out and clean; one whose `file://` submodules the developer's git refused ("transport 'file'
    not allowed"), kept, with the error rendered in the clone dialog; and a missing repository, which left no folder.
    The same throwaway test confirmed what JGit did: "Clone submodules" cloned nothing, JGit's update skipped nested
    submodules, and its failed clone left no folder.
  - `./gradlew build` passes, with 510 tests (16 in `:app`, 405 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** Windows, and LFS objects in a clone's checkout or in submodules.

## 1.2: Worktree model and status (branch `feature/worktree-model`)

Phase 1.2 of `PLAN.md`. This is data only: nothing shows it yet (that's 1.4), and nothing refreshes it (1.5).
- **Models** (`domain/models/Worktree.kt`):
  - `Worktree`, with the plan's fields. `branch` is a full ref, as in `Branch.name`. `locked` and `prunable` are
    null when not set, and git's reason (maybe empty) otherwise.
  - `WorktreeStatus`: staged, unstaged, untracked and conflicted counts, plus the upstream and how far ahead and behind
    it the branch is.
  - `AheadBehind`, `WorktreeInfo` and `WorktreeList`.
- **Git actions** (`data/git/worktrees/`), bound in the new `WorktreeGitActionsModule`:
  - `GetWorktreesGitAction` runs `git worktree list --porcelain -z` from the tab's git dir, and marks the tab's
    worktree as current by comparing canonical paths.
  - `GetWorktreeStatusGitAction` runs `git status --porcelain=v2 --branch -z --no-renames` in the worktree. Without
    rename detection, a rename counts as a deletion and an addition.
  - `GetAheadBehindGitAction` runs `git rev-list --left-right --count <base>...<target>`.
  - `GetDefaultBaseBranchGitAction` picks the local branch that `origin/HEAD` points to, then `main`, then `master`.
  - `GetCommitTimesGitAction` reads commit times with JGit, as the worktrees share their objects.
- **`GetWorktreesInfoUseCase`** puts it together. It runs at most 4 worktrees' git processes at once, and skips status
  for bare and prunable worktrees. A part that fails for one worktree is logged and left null, rather than failing the
  list.
- **Parsers** (`WorktreeParsers.kt`) ignore attributes they don't know, so newer git versions don't break them. The
  fixtures were copied from git 2.54's output for main, linked, detached, locked (with and without a reason),
  prunable, bare, renamed, conflicted and untracked entries.
- **Tests:**
  - `WorktreeParsersTest` (9 tests) runs on those fixtures.
  - `WorktreesTest` (7 tests) runs on a temp repository with six worktrees: main, ahead-and-behind with changes,
    detached, locked, prunable, and nested in `.claude/worktrees/`. Commit dates are fixed. Git runs with the
    developer's global and system config switched off, since settings such as `status.showUntrackedFiles` would
    change the counts.
  - **Mutation check:** 9 of 10 mutations were caught. The one missed is equivalent: reading a prunable worktree's
    status already gives null, since `GitCli` refuses a missing working directory, so skipping it only saves a call.
- **Not yet:** a per-repository base branch setting needs UI and storage, and comes with 1.4.

## Check for updates by hand (branch `feat/check-for-updates`)

- **Before:** Leaf only checked by itself, every 5 minutes, and said nothing unless it found an update. There was no
  way to check right away, or to tell that the checks were failing.
- **Now:** "Check for updates" is in the toolbar's Actions list, on the Welcome page under Additional information, and
  on macOS in a Help menu in the system menu bar ("Check for Updates…", added in `App.kt`). Windows and Linux get no
  menu bar, as Compose would draw a Swing one inside the window.
  - Each opens `CheckForUpdatesDialog`, which checks right away (`UpdatesRepository.checkNow`) and shows "Checking for
    updates…", then one of:
    - "Leaf 1.2.0 is available. You have 1.1.1.", with Download, which opens the release page;
    - "Leaf 1.1.1 is up to date.";
    - "Couldn't check for updates.", with the reason and Try again. The reason is `<url> answered 404 Not Found`,
      `<url> didn't answer with a release`, `Couldn't find the address of <host>` (also when offline) or
      `Couldn't reach <host>: <the error>`.
  - An update found by hand also appears in the bottom bar of every tab, like one found by the 5-minute check, which
    goes on as before.
- **Fixed on the way:** "Clone new repository" and "Signoff config" in the Actions list did nothing. Their callbacks
  closed the Actions list and opened their dialog, and then the list closed the top dialog again, which was the new
  one. The list now closes first, and the callbacks only open their dialog. "Clone new repository" also opened
  `Screen.Clone`, which has no destination; it now opens the Welcome page's Clone dialog. Upstream has the same code.
- **Tests:** 7 more in `UpdatesRepositoryTest` (13 now), and `CheckForUpdatesViewModelTest` (3).
  - A check by hand: each of the three results; the reasons for 404, a 503 holding a release, a body that isn't
    JSON, a dropped connection, a closed port and an unknown host (`.invalid`); a found update reaching the shared
    state, also when no tab was looking yet; a failure keeping the update found before.
  - The dialog checks when it opens, starts no second check while one runs, and checks again on Try again.
  - Ten mutations were each caught: `checkNow` not sharing its update, or sharing it with no replay; no unknown-host
    reason; no status check; no JSON catch; a failure counted as up to date; a version off by one; no guard against a
    second check; no "Checking" state on Try again; no check when the dialog opens.
  - `./gradlew build` passes, with 473 tests (16 in `:app`, 368 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Verified:**
  - Offscreen, the dialog in each state, against a local server: checking, failed, checking again after Try again,
    available, up to date. Close and Try again worked.
  - Offscreen, a real tab from the Dagger graph: the Welcome page's link and the Actions list's item each opened the
    dialog, which checked Leaf's real `latest.json` ("Leaf 1.1.1 is up to date."), and Close went back to the page.
    "Clone new repository" opened the Clone dialog, and "Signoff config" put the sign-off dialog on the back stack (not
    rendered: it needs an open repository). The same clicks on `main`'s code left only the Welcome page.
  - In a dev run on macOS, the Help menu showed in the system menu bar, and "Check for Updates…" opened the dialog
    (clicked by hand).

## A remote branch checkout uses the local branch, and offers to fast-forward it (branch `feat/remote-checkout-fast-forward`)

- **Before:** double-clicking a remote branch such as `origin/develop` (in the log or the side panel) always created a
  local branch from it, so with a local `develop` it failed with `CheckoutBranchError.LocalBranchAlreadyExists`.
- **Now:** it checks out the local `develop`, like `git switch develop`, which never creates a second branch.
  - Behind `origin/develop`, with no commits of its own: `FastForwardOnCheckoutDialog` says how many commits behind it
    is and offers Fast-forward (move it to `origin/develop`, then check it out), Check out only, or Cancel. When
    `develop` is already checked out, only Fast-forward and Cancel.
  - At the same commit, ahead, or diverged: checked out as it is, without a question. Nothing is moved or merged.
  - No local `develop`: created to track `origin/develop` and checked out, as before.
  - The comparison is with the double-clicked branch, whatever `develop` tracks.
- **Why ask:** git's own `git switch develop` checks out the branch and only says it "can be fast-forwarded". Fork
  (1.0.80, "Propose to fast-forward on remote branch checkout") asks. GitKraken (10.5.0) fast-forwards without asking,
  and SourceGit (PR #1416) does too. Asking keeps git's behaviour one click away, and the fast-forward is the default
  button.
- **How:** `GetRemoteBranchCheckoutGitAction` compares the branches (`RemoteBranchCheckout`, ahead and behind counted
  like `git rev-list --left-right --count`), then `CheckoutRemoteBranchGitAction` checks out. The view model asks
  through `fastForwardOffers`, which `RepositoryOpen` turns into `Screen.FastForwardOnCheckout`.
  - A branch that isn't checked out moves first, as `git fetch . origin/develop:develop` would, with the reflog
    message of `git merge --ff-only`. If uncommitted changes then block the checkout, it stays moved: that loses
    nothing, and the error is JGit's checkout conflict.
  - The current branch moves with JGit's `merge --ff-only`, which leaves everything as it was when uncommitted changes
    are in the way.
  - If the branches diverged between the question and the click, the fast-forward is refused with
    `CheckoutBranchError.CannotFastForward`, which replaces `LocalBranchAlreadyExists`, and nothing changes.
  - `CheckoutBranchGitAction` is back to upstream's version. Only local branches reach it now.
  - `IconBasedDialog` has an optional second action, for "Check out only".
- **Tests:** `CheckoutRemoteBranchGitActionTest` (13) and `GetRemoteBranchCheckoutGitActionTest` (8), on temp clones:
  each case above, the working tree and reflog after each kind of fast-forward, uncommitted changes that do and don't
  block it, branch names with folders, and the counts against `git rev-list --left-right --count`.
- **Verified:** a throwaway harness on the real Dagger graph opened a temp clone. Double-clicking `origin/develop` with
  `develop` 3 commits behind emitted the offer and changed nothing; Fast-forward then left `develop` checked out at
  `origin/develop`; a diverged `feature` was checked out without an offer and didn't move. Offscreen renders of the
  dialog (both variants, a long agent branch name) and of the new error.
- **Not handled:** a `develop` that is checked out in another worktree. Leaf checks it out here too, as it already does
  when a local branch is double-clicked, and a fast-forward moves it under the other worktree's files. git refuses
  both. That waits for the Phase 1.3 guard (`docs/fork/architecture-notes.md` §3), which now refuses both (see the
  entry above).

## Fetch and pull run the git CLI (branch `feat/git-cli-fetch-pull`)

Stage 2 of `docs/fork/remote-operations.md`.

- **Before:** fetch and pull ran JGit over Leaf's own SSH and HTTPS code, like push before stage 1.
- **Now:** both go through the git CLI and the system's ssh, with the askpass helper, the progress and the Cancel button
  of stage 1, and the same "Use git for remote operations" setting.
  - **Fetch** runs `git fetch --prune` for each remote in turn. A remote that fails no longer hides the others: they
    are still fetched, and the error lists each failure with git's output. A remote whose dialog was closed isn't
    reported, as with JGit.
  - **Pull** fetches with git, then JGit merges or rebases, as it did after its own fetch. So the automatic stash, the
    conflict handling and the built-in LFS are unchanged, and git never opens an editor. It pulls the same branch as
    before: the chosen remote branch, the upstream, or the branch of the same name on `origin`. The commit comes from
    `FETCH_HEAD`, as with `git pull`, and the merge message names it as git does (`branch 'main' of <url>`). An
    upstream that is a local branch is merged without fetching, and a branch without commits takes the pulled commit.
- **Fixed for both pulls:** a merge that would overwrite uncommitted changes was reported as "Pull completed", though
  nothing was merged, and so was a pull that `pull.ff=only` stopped because the branches had diverged. The first now
  fails with the files to commit or stash (the CLI pull also removes the backup stash it made, since nothing
  changed), and the second says that the branches diverged.
- **Tests:** 24 new, all in `:data`, none skipped here.
  - `GitCliFetchPullTest` (18) fetches and pulls from bare repositories on disk, which another clone changes:
    - fetch: two remotes, with a branch deleted and one added; a remote that fails among others; a chosen remote;
    - pull: a fast-forward that also updates the other remote branches, a merge with git's description of the
      upstream, a rebase, merge and rebase conflicts (the backup stash is kept), local changes that a fast-forward or
      a merge would overwrite (nothing changes, no stash is left), `pull.ff` set to `false` and to `only`, a chosen
      remote branch, a branch without upstream, a local upstream, an upstream missing on the remote, a failing fetch,
      and a branch without commits.
  - `GitCliHttpsTest` (stage 1's `GitCliHttpsPushTest`, renamed; 7, 2 new): a fetch asks through the same dialog and
    the pull then uses the cached credentials, and a fetch whose dialog is closed reports nothing.
  - `PullOutcomeTest` (4) checks what both pulls report, with JGit's own merge and rebase results.
  - Fifteen mutations were each caught, among them dropping `--prune`, the `FETCH_HEAD` merge mark, the upstream
    fetch, `pull.ff`, the rebase, the local upstream or the old "Pull completed" bug.
  - `./gradlew build` passes, with 439 tests (350 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** the running app, Windows, and pulling submodules (`fetch.recurseSubmodules` is left to git).

## installMacApp cleans up LaunchServices (branch `fix/install-launchservices`)

- **Before:** an installed `/Applications/Leaf.app` showed the leaf icon until it launched, then the Dock showed the
  generic "exec" icon. The bundle was fine. LaunchServices had six records for `io.github.zzhelev.leaf`, and four of
  them were copies that no longer existed: three from `packageDmg`'s temporary `dmg-workdir` and one from a scratch
  build. Unregistering them, registering `/Applications/Leaf.app` again and restarting the Dock fixed it.
- **Now:** after copying, `installMacApp` does the first two itself. It reads `lsregister -dump Bundle`, unregisters
  each record with Leaf's bundle ID whose path is gone (printing it), then runs `lsregister -f` on the installed copy.
  The bundle ID comes from `nativeDistributions.macOS.bundleID`. As the app is installed by then, a failing
  `lsregister` only warns.
- **Verified,** with `-PinstallDir` set to a scratch folder:
  - A copy registered and then deleted was unregistered and named in the output. `/Applications/Leaf.app` and the
    build folder's copy, which exist, were kept, and the installed copy was registered.
  - A second run found nothing to unregister. The installed copy passes `codesign --verify --deep --strict`.
  - The warning paths weren't run.

## The update check survives failures, and runs once for the app (branch `fix/update-check`)

- **Before:** each tab's two view models (the Welcome page's and the open repository's) ran their own check of
  `latest.json` every 5 minutes, so several open tabs meant several requests. A check that failed (offline, DNS, an
  error page, a file that doesn't parse) threw out of the loop: that coroutine ended with only a stack trace on stderr,
  and the tab never checked again, so the update banner never appeared until the tab was reopened. Upstream has the
  same code.
- **Now:** `UpdatesRepository.update` is one `StateFlow` for the whole app, in the repository's own scope. It starts
  when the first tab shows it, checks every 5 minutes, and every tab reads it.
  - A failed check is logged as one line (`Checking for updates failed: ...`) and keeps the last answer, so a banner
    already shown stays. The next check runs 5 minutes later.
  - An answer that isn't 2xx counts as a failure, even when its body would parse.
- **Tests:** `UpdatesRepositoryTest` (6), the first tests in `:app`. They run the real Ktor client against a JDK
  `HttpServer` on 127.0.0.1: a newer release, one that isn't newer, a dropped connection, 500, 404, an error page
  holding a valid release and a body that isn't JSON before a release, a failure after an update was found, a later
  release, and six collectors sharing one request.
  - Six mutations were each caught: no catch, a failure resetting the update, `>=` for the version, a flow per
    collector, no status check, and stopping after the first update.
  - `./gradlew build` passes, with 421 tests (6 in `:app`, 326 in `:data`, 82 in `:domain`, 7 in `:common`).
  - A dev run of the app checked once at startup, with no errors.
- **Not tried:** going offline and back while the app runs.

## Checking out a remote branch whose local branch exists says so (branch `fix/remote-checkout-message`)

- **Before:** double-clicking a remote branch such as `origin/develop` while a local `develop` existed showed JGit's
  own text, "Ref develop already exists". Checking out a remote branch always creates a local branch from it.
- **Now:** `CheckoutBranchGitAction` turns JGit's `RefAlreadyExistsException` into
  `CheckoutBranchError.LocalBranchAlreadyExists`, and the dialog names both branches in two paragraphs: checking out
  "origin/develop" would create a local "develop", which already exists; to bring "develop" up to date, check it out
  and pull, or merge "origin/develop" into it. The texts are in `strings.xml`.
- **Unchanged:** the checkout still fails, and the title is still "Branch checkout failed", as remote checkouts run as
  `TaskType.CheckoutBranch`.
- **Tests:** `CheckoutBranchGitActionTest` (3, on temp clones) checks that a remote branch becomes a local branch that
  tracks it, and that an existing local branch gives the new error with both names, also for a name with folders
  (`origin/feature/login`).
- **Verified:** an offscreen render of `ErrorDialog` with the old and the new error.

## Push runs the git CLI (branch `feat/git-cli-push`)

Stage 1 of `docs/fork/remote-operations.md`.

- **Before:** push and remote branch deletion ran JGit, over Leaf's own SSH (libssh) and HTTPS code. It didn't follow
  the user's ssh setup (`ProxyCommand`, `Include` with wildcards, FIDO2 keys, the Windows agent), showed no progress,
  and the processing screen couldn't be cancelled.
- **Now:** both run `git push --porcelain --progress`, so git and the system's ssh do what they do in a terminal:
  ssh_config, the agent, known_hosts, `core.sshCommand`, credential helpers, hooks and git-lfs.
  - **Prompts:** git and ssh ask through `leaf-askpass`, a small Rust program (`rs/leaf-askpass.rs`, standard library
    only) set as `GIT_ASKPASS` and `SSH_ASKPASS`. It passes each prompt to Leaf over a Unix socket that only the user
    can open (a loopback port on Windows), with a random token, and Leaf shows a dialog:
    - HTTPS: one dialog for git's user name and password prompts, or for the password alone when git knows the user;
    - an unknown SSH host: the host key dialog of stage 0c, and ssh adds the key to known_hosts;
    - a key's passphrase: the SSH password dialog. Leaf keeps it for the session, per key file, as it did with JGit,
      and asks again when ssh does;
    - anything else (a password for SSH password authentication, a security key's PIN): a new dialog that shows the
      prompt, and one that asks to allow or deny for `SSH_ASKPASS_PROMPT=confirm`.
  - **Leaf's credential cache** is git's last credential helper (the same program, with `credential`), while "Cache
    HTTP credentials in memory" is on: git stores in it what the server took, and erases what it refused.
  - **Progress:** the processing screen shows git's stage and percentage, with a bar.
  - **Cancel:** a push can be cancelled from the processing screen. Leaf stops git, ssh and the hooks, and shows no
    error. The Cancel button only appears for tasks that can stop (those that run git); for the others it did
    nothing before and isn't shown.
  - **Errors** say what happened, then show git's own output, which often has the server's message: refs the remote
    refused (behind the remote, a stale lease, a `pre-receive` hook), rejected credentials, access to the repository
    refused, a changed or unverified host key, no connection, a certificate problem.
  - **JGit** still pushes when the new setting "Use git for remote operations" (Settings > Remote actions, on by
    default) is off, when no usable git is found, or for an LFS repository whose objects git wouldn't upload (its
    `pre-push` hook doesn't run git-lfs, or git-lfs isn't installed). JGit uploads them itself.
- **Build:** `rs/Cargo.toml` has a second binary, `leaf-askpass`, and `copyRustBuild` copies it into the app's
  resources next to `libleaf_rs`. Leaf extracts it to its temp folder the first time it pushes. The packaging config
  and the release workflow are unchanged: each release job builds the binary for its own platform.
- **Also fixed:**
  - "Push to remote branch" pushed to `refs/heads/refs/remotes/<remote>/<branch>` on the remote, which created a
    branch of that name. Both paths now push to `<branch>`.
  - Deleting a remote branch reported "Branch deleted" instead of "Remote branch deleted".
- **Closing a dialog** stops git, and the error says so ("Git stopped, as its question was closed without an
  answer"), followed by git's output. git's own message ("terminal prompts disabled") would mislead.
- **Tests:** 74 new, all in `:data`, none skipped here.
  - `GitCliPushBranchGitActionTest` (14) pushes to a bare repository on disk: a new branch gets its upstream, a push
    behind the remote, a stale and a current lease, tags, a `pre-receive` refusal, a failing `pre-push` hook, pushing
    to a chosen remote branch, a detached HEAD, cancelling (git and the hook are gone), and deleting remote branches,
    including one already gone and one outside a single-branch fetch refspec.
  - `GitCliHttpsPushTest` (5) pushes to `git http-backend` behind Basic authentication: one dialog for both prompts
    and the cache answering the next push, a user name in the URL, wrong credentials, a closed dialog, and the cache
    setting turned off.
  - `GitCliSshPushTest` (6) pushes through the system's ssh to a local sshd whose keys act like accounts: an unknown
    host key trusted and not trusted, a changed one, another account's key (the server's message is shown), and a
    passphrase that is asked for once and then kept, or asked for again when it's wrong.
  - `AskpassServerTest` (10) runs the built helper as git and ssh do. `AskpassAnswersTest` (11), `AskpassPromptTest`
    (9) and `GitOutputParsersTest` (7) use what git 2.54 and OpenSSH 10.3 wrote. `RemoteOperationsBackendTest` (9)
    covers the choice of git or JGit, and `TaskCancellationTest` (3) the Cancel button.
  - Sixteen mutations were each caught, among them dropping `SSH_ASKPASS_REQUIRE=force`, the upstream, the lease,
    the token check, the kept passphrase or the `\r\n` handling.
  - The new dialogs and the processing screen were rendered offscreen in the dark and light themes.
  - `./gradlew build` passes, with 412 tests (323 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** the dialogs and the Cancel button in the running app, Windows (the helper, the loopback port, Git
  Bash quoting of the credential helper), security keys, and a notarized build. The helper sits in the jar, signed
  ad hoc like `libleaf_rs`, so a notarized build would have to sign both.

## SSH checks the server's host key (branch `fix/ssh-host-key-check`)

Stage 0c of `docs/fork/remote-operations.md`.

- **Before:** Leaf's SSH transport (libssh) never compared the server's key with known_hosts. It connected to any
  server, unknown or with a changed key, and sent it the user's credentials. Upstream has the same code.
- **Now:** `SshRemoteSession` checks the key after connecting and before authenticating, as ssh does with its default
  `StrictHostKeyChecking ask`. Push, fetch, pull, clone, submodules and LFS over SSH all go through it.
  - **Known key:** Leaf connects.
  - **Unknown host, or no known_hosts file:** the new `SshHostKeyDialog` shows the host and its key's fingerprint as
    ssh prints it (`SHA256:...`). Once the user trusts it, libssh adds the key to the user's known_hosts file
    (`UserKnownHostsFile`, `~/.ssh/known_hosts` by default), which ssh shares. Cancel stops the connection with "Host
    key verification failed".
  - **Changed key, or a key of another type than the known one:** Leaf refuses, explains that someone may be
    intercepting the connection, shows the new fingerprint, and points to `ssh-keygen -R`. There is no way to
    connect anyway, as with ssh.
  - Failures are JGit `TransportException`s: JGit reports anything else from the session as "remote hung up
    unexpectedly", which hid the reason.
- **Code:**
  - `rs/src/lib.rs`: `Session.check_host_key` (libssh's `ssh_session_is_known_server`, and the fingerprint from
    `ssh_get_fingerprint_hash`) returns a `HostKeyCheck` with a `HostKeyState`. `Session.accept_host_key` runs
    `ssh_session_update_known_hosts`. `Session.setup` takes an optional known_hosts file, which only tests pass.
  - `CredentialsStateManager.requestSshHostKeyTrust`, `CredentialsRequest.SshHostKeyRequest` and
    `CredentialsAccepted.SshHostKeyTrusted`, shown as `Screen.SshHostKey`.
- **Unlike ssh:** Leaf doesn't read `StrictHostKeyChecking` from ssh_config (libssh can't report it), so it always
  asks about an unknown host and never connects to a changed one. libssh-rs doesn't give the key's type, so the
  dialog shows only the fingerprint.
  - libssh reads hashed known_hosts entries, but not `@cert-authority` or `@revoked` lines (from its source, not
    tested). A host that ssh trusts through a certificate authority is asked about, and a revoked key counts as
    unknown.
- **Tests:** `SshRemoteSessionTest` (9, 4 new) gives Leaf a known_hosts file of its own, so the developer's isn't read
  or written.
  - An unknown key that the user trusts is added, with the host as `[127.0.0.1]:<port>` and the fingerprint that
    ssh-keygen shows, and isn't asked about again.
  - An unknown key that the user doesn't trust stops the connection, and no known_hosts file is created.
  - A changed key, and a known ECDSA key where the server has ED25519, are refused without asking.
  - Five mutations were each caught: skipping the check, not saving a trusted key, connecting despite a changed key
    or a key of another type, and dropping the explanation when the user doesn't trust the key.
  - The dialog was rendered offscreen in the dark and light themes.
  - `./gradlew build` passes, with 331 tests (242 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** the dialog in the running app, and Windows.

## Credentials are stored at the first request that succeeds with them (branch `feature/approve-on-first-success`)

Closes the "Still unlike git" item in "Credentials are stored with every helper once the server takes them".

- **Before:**
  - Over HTTPS, Leaf approved the helpers' credentials only once the whole operation had succeeded. JGit doesn't tell
    its `CredentialsProvider` when credentials work, so the end of the operation was the only signal. A pull whose
    fetch worked but whose merge threw approved nothing.
  - Git approves at the first request that succeeds with them (`handle_curl_result` in http.c calls
    `credential_approve`).
  - Typed credentials went to the helpers before the server had answered. If it rejected them, `reset` had to wait
    for that `store` before the `erase`. Git stores nothing until a request succeeds.
  - Without helpers, typed credentials reached Leaf's in-memory cache only at the end of the operation.
- **Now:**
  - `HandleTransportGitAction` wraps each `TransportHttp`'s connection factory (fork-only `AcceptedCredentials.kt`).
    A 2xx answer to a request that carried an `Authorization` header calls the new
    `HttpCredentialsProvider.credentialsAccepted`.
  - `credentialsAccepted` stores the credentials that `get` last gave with every helper, from the helpers or typed,
    or caches typed ones when there is no helper. It does this once per `get`, like git's `approved` flag. A later
    rejection still erases them, or removes them from the cache.
  - Typed credentials are no longer stored before the server accepts them, so `erase` no longer waits for a
    `store`. `CredentialHelpers.send` is private now.
  - At the end of the operation, `cacheCredentialsIfNeeded` approves too, in case no request reported it.
  - The wrapper hands the factory's sessions (`HttpConnectionFactory2`) the connection the factory created, because
    JGit's JDK session refuses any other class, and it's the session that turns off TLS verification for
    `http.sslVerify = false`. A plain factory stays plain, so JGit's own fallback still applies.
  - LFS already approved right after the request that the server took, as git-lfs does, and is unchanged.
- **Tests:**
  - `HttpCredentialsProviderTest` (+5), with a real JGit fetch through `HandleTransportGitAction` against a fake
    smart HTTP server on 127.0.0.1. The server wants Basic credentials, advertises a branch, and fails the
    `git-upload-pack` POST with a 500.
    - A helper's credentials reach git's `store` even though the fetch then fails, after the requests
      `GET 401`, `GET 200`, `POST 500`.
    - Credentials that `store` gave and the server rejected are erased, and the typed ones that the server took are
      stored.
    - Without a helper, typed credentials are cached even though the fetch fails.
    - New credentials are stored after a rejection, and cached typed credentials leave the cache when a later request
      rejects them.
    - The tests that expected typed credentials to be stored at once now have the server accept them first, and
      check that nothing is stored before.
  - `AcceptedCredentialsTest` (new, 2):
    - Only a 2xx answer to a request with credentials is reported, and a plain factory stays plain.
    - JGit's own factory still configures its connections through a session.
  - `CredentialHelpersTest`: the store-and-erase test goes through `approve` and `erase`.
  - Mutation check: 15 mutations of the new code, all caught.

## Credentials are stored with every helper once the server takes them (branch `feature/credential-approve`)

Closes the last gap listed under "Still unlike git" in "Only the password is asked for when the user name is
known", and the one noted for LFS ("Unlike git-lfs, credentials from the helper aren't stored back").

- **Before:**
  - When a helper's credentials worked, Leaf stored them nowhere else. Git (`credential_approve` in credential.c)
    sends `store` to every helper once the server takes them, the helper that gave them included. So with
    `osxkeychain` and `cache`, git fills the cache from the keychain, and Leaf didn't.
  - Git-lfs does the same with `git credential approve`.
  - Leaf read only `username`, `password` and `quit` from a helper. Git also keeps `oauth_refresh_token` and
    `password_expiry_utc`, passes them to the next helpers, and sends them back with `store` and `erase`.
  - Git drops a password whose expiry has passed and asks the next helpers. Leaf used it.
- **Now:**
  - `HelperCredential` (in `CredentialUrl.kt`) is what git keeps between helpers: the user name, the password, the
    refresh token and the expiry. `credentialHelperInput` writes the parts that are known, in git's order, and
    refuses newlines in all of them.
  - `get` merges each answer field by field, as `credential_read` does. It drops an expired password with its expiry,
    keeping the user name and the refresh token, as `credential_fill` does. `gitExpiry` reads the expiry as git's
    `parse_timestamp`: the leading number, with 0 or no number meaning none.
  - The new `CredentialHelpers.approve` sends `store` to every helper and waits for them, as git does. It stores
    nothing without a user name and a password, or once the password has expired.
  - HTTPS approves the helpers' credentials in `cacheCredentialsIfNeeded`, which runs once the operation succeeds.
    Typed credentials still go to the helpers when the user gives them, and aren't approved again.
  - LFS approves whatever the server took: the helpers' credentials, typed ones, or a mix. It used to `store` only
    typed credentials.
  - Typed parts are stored with whatever the helpers gave besides, such as a refresh token, as git keeps it.
- **Still unlike git:** git approves after the first request that succeeds. Leaf approves once the whole operation
  succeeds, so a pull whose fetch worked but whose merge throws approves nothing.
- **Tests:** git 2.54 is the reference: its `credential.c` for the rules, and `git credential fill` and
  `git credential approve` with recording helpers for the behaviour.
  - `CredentialHelpersTest` (+4):
    - A refresh token and an expiry from one helper reach the next. `approve` gives both helpers the same input that
      `git credential approve` gives them.
    - An expired password is dropped, and the next helper reads what git sends it.
    - Incomplete or expired credentials aren't approved, as with git.
    - Eight `password_expiry_utc` values, read as git reads them.
  - `HttpCredentialsProviderTest` (+3):
    - After a success, a helper's credentials, with their refresh token and expiry, reach git's `store` and come
      back to the helper.
    - A typed password is stored with the helper's refresh token.
    - Typed credentials are stored once.
  - `ProvideLfsCredentialsGitActionTest` (+3, one existing test changed):
    - The helper's credentials reach git's `store` once the server takes them, and aren't stored after a 500.
    - The helper's extras are stored with a typed user name or password.
    - The test where the helper gives the credentials now expects the `store` that follows the `get`.
  - Mutation check: 22 mutations of the new code, all caught.

## SSH errors show the server's message (branch `fix/ssh-server-messages`)

Stage 0b of `docs/fork/remote-operations.md`, and the message part of Gitnuro#294.

- **Before:** when an SSH server accepted the key and then refused the command, push, fetch and clone failed with
  "Something failed writing to channel STDIN: … Remote channel is closed". GitHub does that for another account's
  key ("ERROR: Permission to … denied to …"), and so does any server for a repository the user can't reach. Three
  bugs hid the server's message:
  - **stderr was never read.** `SshChannelInputErrStream` returned the end of the stream whenever no byte was there
    yet. It asked through libssh-rs's `poll_timeout`, which passes `is_stderr` and the timeout to
    `ssh_channel_poll_timeout` in the wrong order, so it waited 1 ms. JGit's thread that copies stderr stopped before
    the server wrote anything.
  - **The cleanup error replaced the real one.** After the failed read, JGit closes the connection, which writes a
    flush packet to the closed channel. `SshChannelOutputStream` threw an `SshException`, which isn't an
    `IOException`, so JGit's `endOut` didn't catch it, and it replaced JGit's own exception.
  - **`exitValue` broke after closing.** JGit 7.7 reads `exitValue()` after closing the connection, to report exit
    status 127 as "cannot execute". `SshProcess` asked the destroyed channel whether it was open, and threw
    `IllegalStateException`. It also always returned 0.
- **Now:**
  - `rs/src/lib.rs`: `Channel.read_available` (libssh-rs's `read_nonblocking`), `is_eof` and `exit_status` replace
    `poll_has_bytes`. The fork of libssh-rs needs no patch.
  - `SshChannelInputErrStream` polls every 20 ms without holding the session, until the server's output has ended.
    `ChannelWrapper.close` first keeps what stderr has received, and the exit status once the output has ended, so
    JGit reads both after closing. Closing doesn't wait for a command that is still running, and logs a failure
    instead of throwing it.
  - The streams throw `IOException`s. `SshChannelOutputStream` writes a whole buffer in one native call instead of
    one byte per call, and `SshChannelInputStream.read()` returns bytes above 127 as positive values, as
    `InputStream` requires.
  - `SshProcess.exitValue` returns the real exit status, or -1 when the server sent none, and throws
    `IllegalThreadStateException` while the command runs, as `Process` requires.
  - LFS over SSH (`AuthenticateLfsServerWithSshGitAction`) gets the server's error output too.
- **Tests:** `SshRemoteSessionTest` (5) runs JGit over Leaf's libssh transport against a local sshd.
  - The sshd's forced command serves one repository, refuses others with a message on stderr, and exits with 127 for
    another. A temporary ssh-agent holds the key, set as `SSH_AUTH_SOCK` in the native environment through JNA,
    as libssh reads it there.
  - It covers a push that succeeds, a refused push, fetch and clone, which now show the server's message, and the
    127 case, which shows JGit's "cannot execute" with the server's output.
  - It is skipped without sshd, ssh-agent or the native library (`./gradlew :app:rustTasks`), and on Windows.
  - Five mutations were each caught: throwing `SshException` from writes, ending stderr when it's empty, closing
    without keeping stderr, closing without the exit status, and `exitValue` always 0. The server waits 0.2 s
    before it refuses, so that ending stderr early is caught every time.
  - Logging a failure to close the channel, instead of throwing it, isn't tested: closing never failed here.
  - `./gradlew build` passes, with 317 tests (228 in `:data`, 82 in `:domain`, 7 in `:common`), on `main` at the time.
- **Not tried:** the running app, Windows, and the #294 reporter's setup (Windows, two GitHub accounts).

## Other actions that can lose work ask first (branch `claude/dazzling-leakey-b0b642`)

- **Before:** these ran on the first click: deleting a submodule (its folder and its repository in `.git/modules`),
  "Delete file" in Unstaged (also offered for tracked files with changes), aborting a merge, cherry-pick or revert (a
  hard reset that discards every uncommitted change, not only the merge's), aborting a rebase, skipping a rebase
  commit, dropping a stash, deleting a remote branch on the server, force push and deleting a remote. The reset dialog
  offered Hard with no warning.
- **Now** each opens a confirmation dialog first, from every place that offers it (side panel, log, status pane, push
  menu):
  - One destination, `Screen.ConfirmAction(action, onConfirm)`, renders `ConfirmActionDialog`. `ConfirmableAction`
    describes what to say, and `onConfirm` is the code the button ran before, so the actions themselves are
    unchanged. The abort buttons still clear the commit message, now only once the user confirms.
  - The aborts say how many files have uncommitted changes that will be discarded (`StatusState.changedFilesCount`).
    "Delete file" says whether the file is untracked, so nothing can restore it, or tracked, so its unstaged changes
    are lost.
  - The reset dialog shows a warning with the same count while Hard is selected (`ResetBranchViewModel` reads the
    status).
  - The warning box is the shared `DialogWarning`, also used by `DeleteRefDialog`.
- **Not changed:** discarding a file, a selection, a hunk or a line still runs at once. The interactive rebase's
  Cancel, before anything is applied, doesn't ask either.
- **Checked offscreen:** each dialog variant rendered with the real resources (Cancel only dismisses, the primary
  button only confirms), the reset dialog with Hard selected, and the status pane during a real merge conflict:
  Abort asked first and kept the merge, and confirming ended it.

## Deleting a branch or a tag asks first, and keeps unmerged work (branch `claude/dazzling-leakey-b0b642`)

- **Before:** "Delete branch" in the side panel and on the log's branch chips deleted at once, and always with force
  (`setForce(true) // TODO Should it be forced?`), so one misclick lost a branch whose commits were on no other ref
  (Gitnuro#137). "Delete tag" deleted at once too.
- **Now** both open a confirmation dialog (`Screen.BranchDelete`, `Screen.TagDelete`, `DeleteRefDialog.kt`):
  - A branch is deleted without force first. When JGit refuses because it isn't merged into HEAD (as `git branch -d`
    checks), the dialog says so, with the number of commits that are on no other branch, tag or HEAD, and offers
    "Delete anyway". An unborn HEAD counts as not merged; JGit threw a NullPointerException there.
  - A tag that is the only ref on some commits (for example a backup tag after a reset) is kept the same way, with
    the count, until "Delete anyway".
  - Other errors show in the dialog. The use cases now return their result (`useCaseExecutor.execute`, like rename),
    so there is no "Branch deleted" or "Tag deleted" toast any more.
- **Counting:** `Repository.countCommitsOnlyOn` (fork-only `CommitsOnlyOnRef.kt`) walks the ref's commits, excluding
  everything every other ref and HEAD reach: remote-tracking branches, tags (annotated ones by their commit) and the
  stash. Symbolic refs to the ref itself are skipped.
- Deleting a remote branch still deletes its remote-tracking ref with force, like `git branch -d -r`.
- **Not covered:** a branch checked out in another worktree can still be deleted if it is merged (Phase 1.3).
- **Tests:** `DeleteBranchGitActionTest` (7), `DeleteTagGitActionTest` (5), `CommitsOnlyOnRefTest` (5). Each new guard
  was broken once to check that a test fails.

## BouncyCastle is removed (branch `claude/dreamy-bun-29d0bf`)

- **Before:** `app`, `data` and `domain` depended on `jgit-gpg` (JGit's BouncyCastle signer) and `bcpg`, and `main.kt`
  registered BouncyCastle's JCE provider. Upstream added the provider and `bcpg` with JGit 7.0.0 (`398d7265`), for
  `AppGpgSigner`, which extended JGit's BouncyCastle signer. Since `GpgProgramSigner` (entry below), nothing used them.
- **Now:** both dependencies, their catalog entries and the provider are gone. So are the 104 BouncyCastle entries in
  the GraalVM reachability metadata: the provider's algorithm classes and JGit's BouncyCastle signer factory.
- **Size:** the packaged macOS app loses five jars (`bcprov`, `bcpkix`, `bcpg`, `bcutil`, `jgit-gpg`).
  `Contents/app` goes from 130.2 MB to 118.6 MB, and `Leaf.app` from 303 MB to 292 MB. The Linux launcher's
  classpath, which `packageDeb` keeps under 7 KB, gets five names shorter.
- **Why nothing else needs them:**
  - Leaf's own JCE calls are AES/CBC (the credentials cache) and SHA-256, both in the JDK.
  - Git over HTTPS uses JGit's JDK `HttpURLConnection`, LFS and the update check use Ktor CIO, and avatars use OkHttp.
    All of them take their crypto from the JDK's providers. BouncyCastle was registered last, so it could only have
    supplied an algorithm no JDK provider has. OkHttp's BouncyCastle support needs `bctls` and BCJSSE as the first
    provider, which Leaf never had.
  - SSH and SSH signing run in the Rust library, which has its own vendored OpenSSL.
  - Nothing verifies signatures. In JGit core only `VerifySignatureCommand` (`Git.verifySignature()`) reaches the
    verifier that `jgit-gpg` registered, and Leaf never calls it.
  - Nothing else brought BouncyCastle in (`:app:dependencies`). Of the other runtime jars, only OkHttp and JGit core
    mention it, for the cases above.
- **Checked:** `./gradlew build` passes with the same 279 tests. A throwaway test on the app's classpath, with no `BC`
  provider and no BouncyCastle classes, ran against the live servers:
  - `ls-remote` of Leaf's repository over HTTPS;
  - `latest.json` through Leaf's Ktor client and through a plain CIO client;
  - Leaf's own LFS batch request and download of one of its LFS fonts, whose SHA-256 matched;
  - a Gravatar request through OkHttp.

  A dev run started, restored its tab and checked for updates without errors.
- **Not tried:** SSH against a server (the JVM takes no part in it), a GraalVM native image, and the Windows and Linux
  packages.

## On Windows, gpg is found where Git for Windows' git finds it (branch `claude/gracious-booth-8370d2`)

- **Before:** on Windows, `GpgProgramSigner` gave the `gpg.program` name (`gpg` by default) to `ProcessBuilder`, so
  Windows looked for `gpg.exe` in Leaf's own folder, the system folders and Leaf's PATH. Git for Windows bundles gpg in
  `usr\bin`, which its installer doesn't put on PATH by default. So Leaf said gpg wasn't found, or ran Gpg4win's gpg
  with its own keyring, where `git commit -S` runs the bundled gpg.
- **How git finds it** (read on 2026-10-08):
  - Git for Windows' git puts `<Git>\ucrt64\bin` (`mingw64\bin` before 2.56) and `<Git>\usr\bin` ahead of PATH before
    it runs anything: `setup_environment` in `mingw-w64-git/git-wrapper.c` (git-for-windows/MINGW-packages) for
    `cmd\git.exe`, and `append_system_bin_dirs` in `compat/mingw.c` (git-for-windows/git) for a `git.exe` started
    without `MSYSTEM`.
  - `path_lookup` in `compat/mingw.c` then tries `<name>.exe`, and then the name as it is, in each folder.
  - Nothing prefers Gpg4win. Its installer adds `GnuPG\bin` to PATH, after Git's folders, so git runs Gpg4win's gpg
    only when `gpg.program` points to it.
  - The full installer ships the `gnupg` package, MinGit doesn't (`make-file-list.sh` in git-for-windows/build-extra).
  - Git for Windows 2.56 moved from MINGW64 to UCRT64 (its release notes).
- **Now:**
  - On Windows, `locateGpgProgram` searches like git: the MSYS2 `bin` folders (`ucrt64`, `mingw64`, `clangarm64`,
    `mingw32`) and `usr\bin` of the Git for Windows install, then PATH, with `<name>.exe` before the name.
  - The install is the one `WindowsFs` runs hooks with. The new `findGitForWindows` in `GitBash.kt` takes the first
    install on PATH, or in the default folders, that has Git Bash. `gitBashCandidates` became
    `gitForWindowsInstalls`, which returns the installs, and `ucrt64\bin` on PATH now counts as a Git folder.
  - A `gpg.program` with `\` or `/` is used as it is. When gpg is found nowhere, the error is still `ProgramNotFound`.
- **Still unlike git:**
  - Git also puts `%HOME%\bin` on PATH, which Leaf leaves out.
  - Git sets `HOME`, `MSYSTEM` and its longer PATH for gpg. Leaf doesn't. Without `HOME`, the bundled gpg takes the
    Windows home folder (`db_home: env windows` in Git for Windows' `nsswitch.conf`), which is git's `HOME` in usual
    setups.
  - A file without an extension is found, as git finds it, but Java can't start it. Git runs it with the interpreter
    of its `#!` line.
  - With several installs, git uses its own, and Leaf the first one with Git Bash.
- **Tests:** 5 new in `GpgProgramSignerTest` (22 now). They lay out a Git install and PATH folders under `@TempDir`
  and pass `OS.WINDOWS` in, so they run on macOS and Linux. `GitBashTest` (8) covers `gitForWindowsInstalls`,
  `ucrt64\bin` and `findGitForWindows`. Nine mutations were each caught, among them searching PATH before Git's
  folders, `usr\bin` before `ucrt64\bin`, the name before `<name>.exe`, `exists` for `isFile`, and an install without
  Git Bash. `./gradlew build` passes, with 295 tests (206 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not run on Windows.** Nothing tests that `GpgProgramSigner` passes the install in, as it reads the real OS.

## LFS and the update check verify TLS certificates (branch `fix/lfs-tls-verification`)

Stage 0a of `docs/fork/remote-operations.md`.

- **Before:** the Ktor client from `NetworkModule` had an `X509TrustManager` that accepted every certificate. Git
  LFS batch requests, uploads, downloads and verifications, the credentials sent with them, and the update check
  (`latest.json`) could be read or changed by anyone between Leaf and the server. Upstream has the same code.
- **Now:** the new fork-only `data/.../network/HttpClients.kt` builds the clients. `createHttpClient` checks
  certificates against the JVM's trust store, as JGit's HTTPS transport does, and `NetworkModule` provides it.
- **`http.sslVerify`:** git-lfs skips the check for a request whose URL has `http.sslVerify` false
  (`http.<url>.sslVerify` for the best match, then `http.sslVerify`). JGit's transport already honours it, so someone
  with a self-signed server has set it, and LFS would otherwise stop working for them.
  - Each LFS action reads it for its request's URL from the repository's config, through JGit's `HttpConfig`
    (`Config.isSslVerify`). Download, upload and verify URLs can be on another host than the LFS server.
  - `LfsRepository`'s methods take `sslVerify`. When it's false, `LfsNetworkDataSource` uses a second client that
    accepts any certificate, made the first time it's needed.
  - Like JGit, Leaf ignores `GIT_SSL_NO_VERIFY`, which git and git-lfs honour.
- **Tests:** `HttpClientsTest` (8) runs a local HTTPS server with a self-signed certificate made by keytool.
  - The default client refuses the certificate and the other client accepts it. LFS batch requests check it unless
    `sslVerify` is false.
  - `isSslVerify` follows `http.sslVerify` and `http.<url>.sslVerify`.
  - `GetLfsObjectsGitAction` and `DownloadLfsObjectGitAction` read the setting for their request's URL from the
    repository's config. A setting for the LFS server's URL doesn't apply to a download from another path.
  - Six mutations were each caught: trusting every certificate by default, `GetLfsObjectsGitAction` ignoring the
    config, the server's URL in place of the download URL, `LfsNetworkDataSource` or `NetworkLfsRepository` dropping
    the flag, and `isSslVerify` always true.
  - `./gradlew build` passes, with 290 tests (201 in `:data`, 82 in `:domain`, 7 in `:common`), on `main` at the time.
- **Verified:** the verifying client gets `latest.json` from GitHub (status 200).
- **Not tried:** LFS against a real server in the running app, or Windows.

## Only the user name is asked for when a helper gave the password (branch `feature/helper-password-only`)

Closes the gap listed under "Still unlike git" in "Only the password is asked for when the user name is known".

- **Before:** a helper could answer `get` with a password but no user name, when no user name was known. Leaf then
  asked for both and dropped the helper's password. Git (`credential_getpass`) asks only for the user name
  (`Username for 'https://host': `), and uses the helper's password with it.
- **Now:**
  - `HelperAnswer.NotStored` carries that password. `HttpCredentialsRequest` and `LfsCredentialsRequest` gain
    `askPassword`, which is false in this case.
  - The dialog then shows only the user name field, and Enter accepts it. The HTTPS subtitle says that the credential
    helper gave the password.
  - `requestHttpCredentials(user, password)` and `requestLfsCredentials(user, password)` answer with the helper's
    password, whatever the dialog sends. The password never enters `credentialsState`, which the UI reads.
  - HTTPS stores the typed user name and the helper's password with every helper at once, as it does typed
    credentials. If the server rejects them, `reset` erases them, as git's `credential_reject` does.
  - LFS tries them once, like a helper's credentials. They're stored with the helpers if the server takes them. If
    it rejects them, they're erased, and Leaf asks for both.
- **Tests:**
  - `HttpCredentialsProviderTest` (+1): the comparison with git now covers what git asks for, not only the user
    name it shows, and has an eighth case, a helper that gives only the password. There, git asks only for
    `Username`, and Leaf asks only for the user name and answers with the helper's password. The new test stores
    the typed user name with the helper's password in git's `store` and a second helper, then erases them from both.
  - `ProvideLfsCredentialsGitActionTest` (+2): the helper's password with the typed user name is sent and stored once
    the server takes it. Rejected, it's erased, and then both are asked for.
  - Both classes now record the whole request, not just the user name.
  - The dialog was checked offscreen: in the new mode it has one field, which has the focus, and Enter accepts it.
    The other two modes are unchanged.
  - Mutation check: 13 mutations of the new code, all caught.

## Commits and tags are signed by running gpg (branch `claude/dazzling-murdock-cc2402`)

- **Before:** OpenPGP signing used JGit's BouncyCastle signer (`AppGpgSigner`). It couldn't find keys kept by keyboxd
  (`use-keyboxd`, GnuPG 2.4's default) and made ED25519 signatures that failed to verify (Gitnuro#194, #293). Tag
  signing also threw "Unsupported gpg format: null" unless `gpg.format` was `gpg`, a value git doesn't have.
- **Now:** the new `GpgProgramSigner` runs gpg as git does: `gpg.program` (default `gpg`) with
  `--status-fd=2 -bsau <key>`, the data on stdin, and `[GNUPG:] SIG_CREATED` required. The key is `user.signingKey`, or
  else the committer's identity. gpg gets the login shell's environment, and a program name is looked up on that PATH.
  gpg-agent's pinentry asks for passphrases. `canLocateSigningKey` runs `gpg --list-secret-keys`.
- **Errors:** the new `GpgSigningError` (not found, start failed, timed out after 2 minutes, pinentry needs a terminal,
  gpg's own messages). `GpgSigningException` is a `CanceledException`, so JGit doesn't hide it behind a generic
  message, and `JGit.provide` maps it for every operation that signs.
- **Tags:** `CreateTagGitAction` leaves signing to JGit's `TagCommand` (`tag.gpgSign`, `tag.forceSignAnnotated`,
  the signer of `gpg.format`). `SshSigner` falls back to `user.signingKey`, as `TagCommand` passes no key.
- `ProcessRunner.run` takes an optional `input` for stdin, written while the output is drained.
- **Removed:** `AppGpgSigner`, with no BouncyCastle fallback: anyone with OpenPGP keys has gpg, which made them, and a
  fallback would silently bring back the bad signatures. Also Leaf's own GPG passphrase dialog, as gpg-agent's
  pinentry asks now, as it does for the git CLI: `GpgCredentialsProvider`, `GpgPasswordDialog`, `Screen.GpgCredentials`,
  the GPG request and answer in `CredentialsStateManager`, and the `key.svg` icon only that dialog used. Someone whose
  only pinentry is `pinentry-curses` and who opens Leaf from the Finder now gets an error that suggests pinentry-mac,
  where Leaf used to ask.
- **Tests:** `GpgProgramSignerTest` (17, fake gpg), `GpgProgramSignerRealGpgTest` (3, real gpg in a throwaway
  `GNUPGHOME` with keyboxd and an ED25519 key, checked with `git verify-commit` and `verify-tag`),
  `CreateTagGitActionTest` (3) and 3 new `ProcessRunnerTest` tests. Eleven mutations were each caught, among them
  dropping the `SIG_CREATED` check, the PATH lookup, the `user.signingKey` and identity fallbacks, and the mapping
  in `JGit.provide`. `./gradlew build` passes, with 279 tests (190 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried:** a commit from the running app, the pinentry error as the dialog shows it, or Windows.

## Only the password is asked for when the user name is known (branch `feature/prompt-known-username`)

Closes the first gap listed under "Still unlike git" in the entry below.

- **Before:**
  - When no helper had credentials, or none was set, Leaf's dialog asked for a user name and a password, even when it
    already knew the user name. The name could come from the URL (`https://alice@host/...`), from
    `credential.username`, or from a helper that gave only a user name.
  - Git (`credential_getpass`) asks only for the password then, and the prompt shows the name:
    `Password for 'https://alice@host': `.
  - Without a helper, Leaf didn't apply `credential.username` at all, as `find` returned null when no helper was set.
  - A user name typed in place of the known one was stored with the helpers, which git and Leaf then never asked
    for it. So Leaf asked again every time.
- **Now:**
  - `HttpCredentialsRequest` and `LfsCredentialsRequest` carry `user`, the name git knows, or null.
    `requestHttpCredentials(user)` and `requestLfsCredentials(user)` return that name with the typed password,
    whatever the dialog sends.
  - When the name is known, the dialog shows it in a disabled field and starts in the password field. The subtitle
    asks only for the password.
  - The name is read-only, as in git. The helpers store credentials under that name and look them up by it, so a
    different name would end up where nothing looks. To use another name, change the remote's URL or
    `credential.username`.
  - `CredentialHelpers.find` always returns the settings, with no helpers when none applies, so `credential.username`
    counts without a helper too. `HelperAnswer.NotStored` carries the user name known after the helpers.
  - LFS asks the same way. After the server rejects a helper's credentials, the prompt shows the user name git knows
    (the URL's or `credential.username`), not the rejected one. Git-lfs does the same, as it asks git again.
- **Still unlike git:** a helper may give a password without a user name, when no name is known. Git then asks only
  for the user name and keeps the helper's password. Leaf asks for both.
- **Tests:**
  - `HttpCredentialsProviderTest` (+2):
    - Seven configs and URLs, compared with `git credential fill`, which asks through a `GIT_ASKPASS` script. Leaf
      must ask for the user name exactly when git does, and show the same name that git's password prompt shows.
    - The cases: no name known, the URL's, `credential.username`, the URL's ahead of `credential.username`, a
      `credential.<url>.username` that applies and one that doesn't, and a helper that gives only the user name.
    - With `credential.username`, typed credentials go into git's `store` under that name, and
      `git credential fill` then gives them back without asking.
  - `ProvideLfsCredentialsGitActionTest` (+4):
    - A user name in the remote's URL is shown, sent to the server and stored, whatever the dialog sends.
    - A user name a helper gives is shown.
    - After a rejection, the prompt shows `credential.username`, not the helper's user name.
    - Without a helper, `credential.username` is shown and cached.
  - The dialog was checked offscreen in an `ImageComposeScene` harness that isn't committed. With a known user, the
    disabled field shows the name and can't take focus, the password field has the focus, and Enter accepts the known
    name with the typed password. Without one, the dialog is unchanged.
  - Mutation check: 15 mutations of the new code, all caught.

## Every matching credential helper runs, with the user name (branch `feature/credential-helper-list`)

Closes the first three gaps listed under "Credential helpers get the host and path that git gives them".

- **Before:**
  - Leaf ran one credential helper: the last `helper` value of the most specific `credential.<url>` subsection, or of
    `credential`. Git (`credential_apply_config` in `credential.c`) collects every `helper` that applies, in config
    order, and an empty value clears the ones before it. It runs `get` with each in turn until one gives a user name
    and a password, and `store` and `erase` with all of them.
  - So with `osxkeychain` from the system config and `store` from `~/.gitconfig`, Leaf asked only `store`, and stored
    and erased only there. An empty `helper =`, written to drop the helpers set before it, still ran
    `git credential- get`.
  - Leaf sent no user name. Git sends the URL's (`https://alice@host/...`), or else `credential.username`.
    `osxkeychain` then answers with the password alone. Leaf counted that as no credentials and asked the user.
  - JGit reads no `includeIf`, so settings from conditionally included files never applied.
  - Without a repository (cloning), Leaf read only `~/.gitconfig`, from the JVM's `user.home`, not the XDG or system
    config.
- **Now:**
  - `CredentialHelpers.find` lists the config with `git config --list -z` through `GitCli`. That gives every entry in
    the order git reads them, includes and `includeIf` too, with `--git-dir` set to the repository's. Without a
    repository, `--git-dir` names one that doesn't exist, so git reads only the global and system config, as `git
    clone` does.
  - `credentialSettings` (in `CredentialUrl.kt`) then applies them as git does: the helper list with empty values
    clearing it, the last `useHttpPath` and `username`, and the URL's user name ahead of `credential.username`.
  - If git can't be run, Leaf falls back to JGit's config. JGit doesn't keep the order across subsections, so the
    fallback takes `credential.*` first, then the matching subsections from the least specific to the most. JGit gives
    `null` for `helper =` and `""` for a bare `helper`, the other way round from git, so the fallback swaps them back.
  - `get` asks each helper in turn, sending the user name known so far and any password an earlier helper gave. It
    stops at the first helper that completes both, so a password alone is enough when the user name is known. A
    helper that can't be started is skipped, as in git. One that answers `quit=1` stops the search with no
    credentials, where git dies.
  - Leaf fills JGit's `Username` item with the user name it has, and the LFS action does the same.
  - `store` and `erase` go to every helper, the HTTPS provider's and LFS's alike. `reset` waits for every `store`
    before erasing.
- **Still unlike git:**
  - When Leaf asks the user, the dialog still asks for the user name too. Git asks only for the password once it
    knows the user name.
  - Credentials that one helper gave aren't stored in the others after a success, as `credential_approve` does.
  - A setting without a value is skipped; git stops with an error. A helper that runs more than a minute still ends
    the search with no credentials; git waits.
- **Tests:** git 2.54 is the reference wherever it can be.
  - The new `CredentialHelpersTest` has 7 tests. Its helpers are all `!leaf-helper <name>`, which records that it
    ran and what it read.
    - One config spreads helpers, resets, `useHttpPath` and `username` over the global and local files, with an
      `includeIf`. For four URLs, Leaf's helpers and their order, and what the first one reads, must equal what
      `git credential fill` runs. One URL has a port, one a user name, and one has no repository.
    - Helpers that give the user name and the password separately get the same answer and input as with git, and so
      does a password-only helper for a URL with a user name.
    - Also: `quit` stops the search, a helper that can't start is skipped, `store` and `erase` reach every helper,
      and the JGit fallback's order.
  - `HttpCredentialsProviderTest` (+3): a password-only helper for `https://alice@...`, a clone that finds its helper
    in the global config, and typed credentials stored with both git's `store` and a second helper, then erased from
    both.
  - `CredentialUrlTest` (+2): `git config --list -z` output with empty, bare, quoted and multi-line values, and
    settings without a value.
  - The existing credential and LFS tests pass unchanged, apart from building `CredentialHelpers` with a `GitCli`.
    That `GitCli` always gets the variables that keep git off the developer's config.
  - 22 mutations were each caught, across the settings, the config listing, the JGit fallback, `get`, `store` and
    `erase`, and the provider. All 247 tests pass (158 in `:data`, 82 in `:domain`, 7 in `:common`), and `:app`
    compiles.
- **Not tried** on Windows, where the helpers go through the same list, or with a real `osxkeychain` or credential
  manager.


## LFS over HTTPS uses the credential helper and caches credentials (branch `claude/lfs-credentials`)

- **Before:** after a 401 from an LFS server, `ProvideLfsCredentialsGitAction` looked in Leaf's in-memory cache
  (`isLfs = true`) and then asked the user until the server took the answer. It never ran a credential helper, and
  nothing has cached LFS credentials since upstream's 7277d40c replaced `GLfsFactory`, the one place that did. So
  Leaf asked for every LFS request that needed credentials, which is each file a checkout downloads, even with
  `osxkeychain` or gh holding the remote's credentials.
- **Now,** like git-lfs (`lfsapi/auth.go`), Leaf still sends each request without credentials first, and after a 401:
  - with a `credential.helper`, it asks the helper. It asks about the remote's URL when the LFS server has the
    remote's scheme, host and port (`getCredURLForAPI`), so LFS gets the credentials git uses for the remote, and
    about the server's URL otherwise. The helper's credentials are tried once, and erased if the server rejects them.
    Then Leaf asks the user until the server takes the answer, and stores that with the helper.
  - without one, it tries the cached credentials for the LFS server's URL, and removes them if the server rejects
    them. Then it asks the user and caches the answer that the server takes.
  - Only a request that succeeds stores or caches anything. Typed credentials that the server rejects were never
    stored, so nothing is erased for them.
- **Shared helper code:** the new `CredentialHelpers` finds the helper for a URL and runs `get`, `store` and `erase`,
  for both `HttpCredentialsProvider` and LFS. The code moved out of `HttpCredentialsProvider` almost unchanged: `get`
  now returns a `HelperAnswer` instead of filling JGit's credential items. As Gitnuro's code, the file stays
  GPL-3.0-only and has no SPDX header.
- **Remote URL:** `GetLfsUrlGitAction` now returns an `LfsServer`, a new domain model with the LFS URL and the remote's
  URL. The URL from `.lfsconfig` still comes first, but the remote is now looked up too. Its "couldn't obtain the
  remote" errors are logged only when there is no URL at all. The LFS git actions take the repository, for its
  config, and the `LfsServer`.
- **Unlike git-lfs:**
  - credentials from the helper aren't stored back after a request succeeds;
  - for object URLs on another host, Leaf asks about the LFS server, where git-lfs asks about the object's URL;
  - Leaf doesn't remember that a server needs credentials (git-lfs saves `lfs.<url>.access`);
  - helper answers aren't kept in memory, so each request that needs credentials runs `get` again.
- **Tests:** `ProvideLfsCredentialsGitActionTest` has 11 tests, run against a fake LFS server and a helper that
  records what it gets. They cover:
  - the helper is asked about the remote's URL, with `useHttpPath`;
  - rejected helper credentials are erased, and typed ones are stored once taken;
  - typed credentials the server rejects are neither stored nor erased;
  - the cache is left alone when there is a helper;
  - without a helper, cached credentials are used, removed when rejected (even when the user then cancels), and
    typed ones are cached only after a success;
  - a URL that git refuses gets no credentials and no prompt;
  - when the remote's URL is used.

  `HttpCredentialsProviderTest` passes unchanged apart from building `CredentialHelpers`. Fourteen mutations were
  each caught: twelve in the LFS code, and two in the moved helper code (`erase` not waiting for `store`, a cut
  password from `get`). All 235 tests pass (146 in `:data`, 82 in `:domain`, 7 in `:common`), and `:app` compiles.
- **Not tried** against a real LFS server, or on Windows.

## Clicking a branch or tag in the log selects its commit (branch `fix/log-chip-click`)

- **Before:** a single click on a branch or tag chip in the log did nothing. `Chip` used
  `combinedClickable(onDoubleClick = checkout, onClick = {})`, which took the click away from the commit line, so the
  commit stayed unselected.
- **Now:** `Chip` has no `clickable`, only the `onDoubleClick` modifier, which lets the first click through. A single
  left click on a chip reaches the line and selects the commit at once, like a click on the message. A double click
  selects the commit and then checks out the branch or tag, as before. Right click still opens the chip's own menu.
  The checkout lambda goes through `rememberUpdatedState`, because selecting the commit recomposes the line between
  the two clicks, and a new key would restart the double click detection (the same as `ChangedFileRow`).
- **Verified:** an offscreen harness rendered `Log` for a temp repository and sent mouse events to its chips. A left
  click on a local branch, a tag and the current branch selected that commit within 50 ms, and left HEAD alone. Middle
  and right clicks changed nothing (right click showed the branch menu). A double click on a branch checked it out, and
  on a tag detached HEAD there. Without the change, single clicks on chips left the previous commit selected.

## In-memory credential cache forgets rejected credentials (branch `claude/infallible-hodgkin-b61517`)

- **Before:** without a `credential.helper`, Leaf keeps the HTTPS credentials the user types in an in-memory cache
  (`CredentialsCacheRepository`, an app singleton) once the operation succeeds. Nothing ever removed an entry, and
  caching never replaced one (`if (!previouslyCached)`). After a password change on the server, `get` gave the old
  password from the cache on each of JGit's 3 attempts, and every fetch, pull, push or clone of that URL failed with
  "not authorized" until Leaf restarted.
- **Now:** `HttpCredentialsProvider.reset` removes the credentials that `get` took from the cache (the new
  `CredentialsRepository.removeCachedHttpCredentials`), so the next `get` asks the user. Like
  `git credential-store erase`, it removes them only while they are still the URL's cached ones, so it doesn't drop
  credentials that another tab cached meanwhile. Caching after a successful operation replaces the URL's entry. Entries
  are now replaced by URL and `isLfs`, the same key `getCachedHttpCredentials` looks up.
- **Typed credentials that the server rejects are never cached.** `reset` drops them. Otherwise, now that caching
  replaces, an attempt that then succeeded with credentials another tab had cached would swap those for the rejected
  ones.
- **LFS needs nothing:** `ProvideLfsCredentialsGitAction` reads the cache with `isLfs = true`, but nothing has cached
  LFS credentials since upstream's 7277d40c replaced `GLfsFactory`, the one place that did. So it asks after every
  401, and has no rejected entry to keep. If LFS caching comes back, it should cache only what the server accepted,
  and replacing on success then covers a rejected entry.
- **Helpers unchanged:** with a `credential.helper`, the in-memory cache is neither read nor written.
- **Tests:** `HttpCredentialsProviderTest` now uses the real `CredentialsCacheRepository` instead of a stub that
  cached nothing, and has three new tests:
  - a rejected cached password is dropped, and the new one cached;
  - typed credentials that the server rejected aren't cached when another tab's credentials then succeed;
  - with a helper, the in-memory cache is left alone.

  The new `CredentialsCacheRepositoryTest` (5 tests) covers replacing, keeping LFS and git entries apart, and removing
  only matching credentials. Ten mutations were each caught by one of these tests: not removing, not dropping typed
  credentials, not recording where `get` got them, removing whatever is cached for the URL, keeping the old entry,
  replacing by URL alone, and removing without matching the URL, `isLfs`, user or password. All 224 tests pass
  (135 in `:data`, 82 in `:domain`, 7 in `:common`), on top of the host and path change below.
- **Not tried against a real server:** the tests call `get` and `reset` in the order JGit's `TransportHttp` does.

## Credential helpers get the host and path that git gives them (branch `claude/epic-newton-39ddf6`)

- **Before:** for `get`, `store` and `erase`, Leaf wrote `host=${uri.host}` and, with `credential.useHttpPath`,
  `path=${uri.path}`. Git (`credential_from_url` in `credential.c`) writes the host with its port
  (`host=example.com:8443`) and the path without its leading and trailing slashes (`path=team/project.git`, where
  Leaf wrote `/team/project.git`). So for a remote with a port, or with `useHttpPath`, Leaf didn't find what the git CLI
  had saved, the git CLI didn't find what Leaf had saved, and `erase` missed the entry. For a remote on port 8443, Leaf
  could also get the credentials saved for the same host on the default port.
- **Newlines:** `URIish.path` is already decoded, so a `%0A` in the path became a line of its own. With `useHttpPath`,
  a remote such as `https://evil.example/x%0Ahost=github.com%0Apath=org/repo.git` made git's `store` give Leaf the
  credentials saved for `github.com/org/repo.git`, to send to `evil.example` (checked with `git credential-store`). Git
  refuses such URLs since CVE-2020-5260.
- **Config lookup:** `getExternalCredentialsHelper` read `credential.<scheme>://<host>.helper` and `.useHttpPath` by
  that exact name, without the port. `[credential "https://example.com"]` applied to `https://example.com:8443`, and
  `[credential "https://example.com:8443"]` never applied. Keys with a trailing slash, other letter case, a path, `*`
  or a user name never applied either, and `useHttpPath = yes`, `on` or `1` under a URL counted as false.
- **Now:** the fork-only `CredentialUrl.kt` has both parts.
  - `credentialHelperInput` builds what every helper operation reads, on every OS: the protocol; the host, with the
    port when the URL has one; and with `useHttpPath`, the path from `URIish.rawPath`, trimmed and then decoded like
    git's `url_decode`. That leaves `%00`, invalid escapes and everything before the first colon as they are:
    `a%20b:c%20d/` becomes `a%20b:c d`.
  - Like git, Leaf runs no helper for a URL with a newline in it, or for a value to send with a newline or a carriage
    return (git's `credential.protectProtocol`, on by default). `get` then gives no credentials, so the fetch fails
    as it does with git, and `store` and `erase` are skipped and logged.
  - `credentialConfigSubsections` picks the `credential.<url>` subsections that apply, using the rules of git's
    `urlmatch.c` and `match_partial_url`:
    - The scheme, host and port must match, ignoring case and the scheme's default port. A `*` stands for one host
      label.
    - The key's path must be the remote's or a folder above it, and a user name in the key must be the remote's.
    - A key that isn't a URL, such as `example.com:8443` or `https://`, matches when the parts it has equal the
      remote's.

    Git applies every match in config order. Leaf takes `helper` and `useHttpPath` each from the most specific match
    that sets it (longest host, then longest path, then a user name, then partial URLs), then from `credential.*`.
    `useHttpPath` is read with JGit's `getBoolean`, so `yes`, `on` and `1` count.
- **Tests:** the expected results come from git 2.54 itself, not from Leaf's reading of it.
  - `CredentialUrlTest` (4, new) runs the git CLI. For 13 URLs, with and without `useHttpPath`, Leaf's input must
    equal what `git credential fill` (get) and `git credential approve` (store) write to a helper. The URLs cover
    ports, IPv6, extra slashes, escapes before and after a colon, `%00`, UTF-8, a query and a newline. For 31 pairs of
    URL and key, Leaf must apply `credential.<key>.helper` where git runs it. A further test checks the order of
    matches, and one checks the newline and carriage return refusals.
  - `HttpCredentialsProviderTest` (+5) uses git's real `store` with `useHttpPath`. Leaf finds what `git credential
    approve` saved for `https://example.invalid:8443/team/project.git`, ahead of newer entries for the same path
    without the port and for another path. `git credential fill` finds what Leaf stored, and Leaf's `erase` removes
    git's entry and nothing else. Settings under `https://example.invalid` don't apply on port 8443, while
    `https://EXAMPLE.invalid:8443/team` and `https://*.invalid:8443` do. A URL with `%0A` in its path runs no helper
    and asks nothing.
  - Each of 21 mutations broke at least one test: in the input (no port, slashes kept, not decoded, decoded before
    the colon, JGit's path, `%00` decoded, no newline or carriage return check, store and erase without the path), in
    the matching (port, default port, `*`, folder boundary, user name, letter case, partial URLs, order), and in the
    provider (only `scheme://host`, generic `useHttpPath` only, asking the user for a refused URL).
  - All 216 tests pass (127 in `:data`, 82 in `:domain`, 7 in `:common`). Not tried on Windows, where the same code
    builds the input.
- **Gaps left (follow-ups):**
  - Leaf runs one helper. Git runs every `credential.helper` that applies, in config order, until one gives
    credentials, and an empty value clears the ones before it. Leaf takes the last value of the chosen key, and an
    empty one still runs `git credential- get`.
  - Leaf sends no `username` in `get`. Git sends the URL's user name, or `credential.username`, which Leaf doesn't read.
    A helper with several accounts for one host may give Leaf a different one than git gets.
  - `.` and `..` in a key's path aren't resolved, and a URL with a port and a `%XX` in its host differs from git in
    the host it sends. Neither should come up in practice.
  - Without a repository (cloning), Leaf still reads only `~/.gitconfig`, not the XDG or system config.

## Debian packages for Linux (branch `feature/linux-deb`)

- **New files:** releases get `Leaf-<version>-linux-amd64.deb` and `Leaf-<version>-linux-arm64.deb`, each with a
  `.sha256`. The `build_linux_deb` job runs `./gradlew :app:packageDeb` natively on `ubuntu-22.04` and
  `ubuntu-22.04-arm`. jpackage can't build for another CPU, and the package only works on systems as new as the one it
  was built on: its dependencies are the build system's package names (24.04's renamed `libasound2t64` and others
  don't exist on 22.04 or Debian 12, while 24.04 still accepts the old names), and the Rust library needs glibc 2.34.
- **Package:** `nativeDistributions` adds `TargetFormat.Deb`, a `vendor` and a `linux {}` block: package `leaf`,
  installed to `/opt/leaf`, maintainer `Zhelyazko Zhelev <zzhelev@gmail.com>`, section `vcs`, menu group
  `Development;RevisionControl;`, and the new 512 px `icons/icon.png`. The launcher sets `jpackage.app-version`, so a
  `.deb` install uses Leaf's real storage (`~/.config/leaf`, `~/.local/state/leaf/logs/`).
- **Menu entry without a desktop:** jpackage's install scripts register the menu entry with `xdg-desktop-menu`, which
  exits with code 3 where there is no `/etc/xdg/menus`. GNOME, KDE and Xfce provide one; WSL, minimal installs and
  bare window managers don't. There dpkg left `leaf` half configured, and removing it failed the same way. Compose
  clears jpackage's resource folder before packaging, so the scripts can't be swapped. Instead `packageDeb` repacks the
  finished `.deb` with `dpkg-deb`: `leaf-Leaf.desktop` moves to `/usr/share/applications` as an ordinary packaged
  file, and the two `xdg-desktop-menu` lines go. The build fails if jpackage's scripts change shape.
- **`libegl1` on arm64:** skiko's arm64 library needs `libEGL.so.1`; the x64 one doesn't. jpackage only lists the
  libraries it finds installed, and the arm64 runner has no `libegl1`, so the package didn't depend on it and Leaf
  crashed at launch with an `UnsatisfiedLinkError` where it was missing. The job installs `libegl1` before packaging
  and checks that the arm64 package depends on it.
- **Launcher crash on a busy system:** jpackage's Linux launcher forks, and the child sends the launch data back through
  a pipe that the parent reads with a single `read()` ([JDK-8380085](https://bugs.openjdk.org/browse/JDK-8380085), fixed
  in JDK 27, not in any JBR 25). Once the user has about 1,024 pipes open (`pipe-user-pages-soft`), Linux gives new
  pipes 2 pages (8 KB). Leaf's launch data was 9.9 KB, so the launcher got part of it and segfaulted in `setenv` before
  starting the JVM. 3.7 KB of it was the 32-character hash Compose adds to each of the 117 jar names. The repack drops
  those hashes, keeping 8 characters for the 6 jars that would otherwise share a name (two each of `library-desktop`,
  `runtime-desktop` and `runtime-saveable-desktop`), and rewrites `Leaf.cfg` to match. The classpath is now 5.8 KB, and
  the build fails if it passes 7 KB. Once JBR has the fix, this step can go.
- **README:** the Download section lists the files for every platform, in place of "no published releases yet". The
  `.deb` files start with the release after 1.1.1.
- **Verified:** Release Build run 37610735694 passed every job. Its two packages were installed in Debian 12, Ubuntu
  22.04 and Ubuntu 24.04 containers (arm64 natively, amd64 under emulation) with no desktop environment, as root on a
  Docker VM where root has enough pipes open to get throttled 8 KB pipes. Each one installs with the menu entry in
  place, Leaf starts under Xvfb and is still running after 45 s (90 s on amd64), it writes to `~/.config/leaf` and
  `~/.local/state/leaf` rather than `leaf-dev`, and `apt remove` takes away the entry and `/opt/leaf`. Before the fixes,
  all six failed to install, Ubuntu 22.04 arm64 crashed on `libEGL.so.1`, and the launcher segfaulted under the same
  pipe pressure, while a fresh user started it fine.
- **Not covered:** a real desktop session (Xvfb has no GPU, so skiko drew in software), and the Start menu entry under
  WSLg.

## Credential helpers forget rejected credentials (branch `claude/priceless-kepler-d25412`)

- **Before:** `HttpCredentialsProvider` didn't override JGit's `CredentialsProvider.reset`, which does nothing. After
  a 401, JGit 7.7 calls `reset` and then `get` again, up to 3 attempts. So a wrong password saved in `osxkeychain`,
  `store`, `cache`, gh or a credential manager was sent 3 times, the fetch or push failed with "not authorized", and
  every later one failed the same way until the user deleted it by hand.
- **Now:** `reset` runs the helper with `erase`, as git does after a 401 (`credential_reject`, called from
  `handle_curl_result` in `http.c`). It writes what `store` writes: protocol, host, the path with `useHttpPath`,
  username and password. The next `get` finds nothing, so Leaf asks, and stores what the user types. The helper runs
  through `startCredentialsHelper`, so macOS and Linux get the login shell's variables and Windows is unchanged.
- **Typed credentials are erased too.** Git erases with every helper whatever the credentials came from, and stores
  only once the server accepts them. Leaf stores typed credentials right away, since JGit tells the provider nothing
  about success. If the server rejects them, the erase takes them back out, so the next attempt asks again instead of
  sending them from the helper. `reset` waits for that `store` before erasing, and for the `erase` before returning
  (up to a minute each, like `get`). A helper that can't be started is logged and skipped, as git skips it.
- **Unchanged without a helper**, as in git: credentials from the prompt or Leaf's in-memory cache go to no helper.
  The in-memory cache still keeps rejected credentials until Leaf restarts.
- **Tests:** three in `HttpCredentialsProviderTest`. A fake helper checks that `erase` gets what `get` sent (with
  `useHttpPath`) plus the credentials, once; git's real `store` forgets a rejected password containing `=`, then
  stores the one Leaf asks for; and a fake helper with a slow `store` checks that typed credentials are erased after
  they're stored. Each of these breaks at least one of them: no erase, typed credentials not erased, not waiting for
  the `store`, not waiting for the `erase`, erasing twice, and no password in the `erase` input. All 207 tests pass
  (118 in `:data`, 82 in `:domain`, 7 in `:common`).
- **Not tried on Windows**, or against a real server: the tests call `get` and `reset` in the order JGit's
  `TransportHttp` does.

## `store` and `cache` credential helpers (branch `feature/credential-store-cache`)

- **Before:** Leaf refused `credential.helper` set to `store` or `cache`, logged "not yet supported", and asked for
  the password itself, keeping it only in memory. With options, such as `store --file <path>`, they weren't refused,
  and they ran since `fix/credential-helpers`.
- **Now:** on macOS and Linux they run as `git credential-store` and `git credential-cache`, like any helper given by
  name. Leaf gets saved credentials from them, and stores the ones it asks for: in `~/.git-credentials`, or in git's
  cache daemon, which the first store starts.
- **Windows** still refuses them. There Leaf runs a helper as a program, not through git, and Git for Windows has no
  `credential-cache`.
- **Also fixed:** Leaf read a helper's `username=` and `password=` lines only up to the next `=`, so a password or
  token containing `=` (base64 padding, for example) was cut short. It now keeps everything after the first `=`. This
  applied to every helper.
- **Tests:** three in `HttpCredentialsProviderTest`, with git's real `store` and `cache` and with `HOME` and the XDG
  folders in the test's folder. Refusing `store` and `cache` again makes all three fail, and the old parsing fails
  the `=` test. All 197 tests pass. The credential tests also pass in a Finder-like environment.
- **Not covered:** Leaf never runs a helper's `erase` when credentials are rejected, as git does. A wrong saved
  password is given again on each of JGit's 3 attempts, and the fetch fails with "not authorized". It has to be
  removed by hand, from `~/.git-credentials` or with `git credential-cache exit`. This applies to every helper.

## Resizable commit message in Files changed (branch `feature/commit-message-resize`)

- **Before:** the commit message below the Files changed list was fixed at 120 dp, so a long message had to be read
  through a small scrolling box.
- **Handle:** an 8 dp handle between the files list and the message, with the north–south resize cursor, like the
  status pane's. Dragging it up grows the message; a double-click resets it to 120 dp. The author footer below the
  message keeps its size.
- **Limits:** the files list keeps 100 dp (its header, the column header of Split columns and a row) and the message
  40 dp (one line). A shorter window shrinks the message without changing what's saved, and a taller one brings it
  back.
- **Saved globally**, in the prefs node next to the pane widths (`commitMessageHeight`), when a drag ends or the
  handle is reset. Every tab shares it. A damaged value falls back to 120 dp.
- **Spacing:** the 8 dp handle replaces the 4 dp gap below the files list. The message and the footer keep their
  rounded corners, now drawn as the message's top and the footer's bottom.
- **Shared handle:** `SectionDivider` moved from `StatusPane.kt` to `ui/components/`, and
  `STATUS_SECTION_DIVIDER_HEIGHT` became `SECTION_DIVIDER_HEIGHT`. The status pane is unchanged.
- **Tests:** `CommitChangesSectionSizesTest` (10) covers the default, fitting, both minimums, a pane too short for
  them, dragging both ways, moving back at once after hitting a limit or after the window shrank the message, and
  damaged values.
- **Verified:** an offscreen harness on the real Files changed pane, with a temporary repository whose commit has a
  42-line message. It dragged the handle up and down past both limits, double-clicked the reset, shrank and restored
  the window, scrolled the message to its end, and checked the prefs node. A second JVM read the saved height back.

## commit-msg gets its message file in linked worktrees (branch `claude/inspiring-hopper-5659c3`)

- **Before:** in a linked worktree, JGit gave the commit-msg hook an empty `$1`, on every system. `CommitMsgHook`
  makes the message file's path relative to the working tree with `Repository.stripWorkDir`, which returns `""` for a
  file outside it, and a linked worktree's `COMMIT_EDITMSG` is in `<main>/.git/worktrees/<name>`. The same went for
  submodules, whose git dir is `<parent>/.git/modules/<name>`. A hook that reads or rewrites `"$1"`, as message checks
  such as `commitlint --edit "$1"` (husky) do, had no file to work on, and one that failed on that blocked the commit.
- **Now:** when JGit passes commit-msg an empty path, `PosixFs` and `WindowsFs` pass the absolute path of
  `<git dir>/COMMIT_EDITMSG` instead (`hookArguments`). JGit writes the message to that file and reads it back after
  the hook, and the git CLI gives the hook the same absolute path in a linked worktree. `WindowsFs` writes it with
  forward slashes, like the hook's own path.
- **Unchanged:** in a regular repository the hook still gets `.git/COMMIT_EDITMSG`, relative to the working tree,
  which is also what the git CLI passes.
- **Tests:** 4 new. In `PosixFsTest`: a regular repository, a linked worktree and a submodule. In `WindowsFsTest`: a
  linked worktree (its regular repository test was already there). Each hook writes the `$1` it gets to a file and
  appends ", checked" to the message in it. Putting back the old behavior makes the linked worktree and submodule
  tests fail with `cat: : No such file or directory`, while the regular repository tests still pass. All 184 tests
  pass (112 in `:data`, 72 in `:domain`).
- **Not tried on Windows.** On macOS and Linux the path has no backslashes, so the switch to forward slashes isn't
  exercised.

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
- **Found on the way, fixed separately (see above):** in a linked worktree JGit gives commit-msg an empty `$1`, on
  every system.
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
