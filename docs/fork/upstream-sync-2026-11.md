# Upstream review, November 2026: what is new or fixed, and what Leaf takes

| | |
|---|---|
| Question | What did upstream change since Leaf's last sync, which of it applies to Leaf, and how should Leaf get it? |
| Range | `main..upstream/main` on 2026-11-09: 33 commits, `7d02a217` (2026-10-16) to `65ff0f91` (2026-11-05) |
| Last sync | `406f5c05` (2026-10-16) merged upstream up to `1cac7a00` (2026-10-11) |
| Releases since | 2.0.0 (2026-10-26) and 2.1.0 (2026-11-01). Everything in them is in the range above |
| Issues closed upstream since | 368 (tab bar keys), 372 (terminal in a submodule), 347 (moved files), 128 (compact view), 366 (Flatpak runtime) |
| Status | Items 1 to 9, 12 and 14 done on 2026-11-09, each written for Leaf rather than taken from upstream's code (the user's ask, so that no upstream code comes in). Item 13 done on 2026-11-12, the same way. Items 10, 11, 15 and 16 are open, and the sync isn't recorded yet |

## How it was checked

Each commit's diff was read against Leaf's code on `main` (`c4d04536`). `[code]` in a note means the Leaf side was read,
not run. Items marked "verify first" are bugs that Leaf's code very probably has, by the same cause as upstream's, but
nobody saw them happen in Leaf: the first step of their port is to make them happen in a test or a render.

A trial merge (`git merge-tree --write-tree main upstream/main`) gives about 90 conflicts: most are modify/delete, as
Leaf rewrote nearly every file that upstream touched (settings storage, status, the diff pane, tabs, blame, LFS). So
upstream's code can't come in as it is; each fix has to be ported.

## Already in Leaf: nothing to do

| Upstream | What it fixed | Leaf |
|---|---|---|
| `7d02a217` | The log scrolled to the top when switching tabs | Fixed on 2026-10-10 (`6316b58d`): the log scrolls only when the first commit changes, kept per tab (`logLastFirstCommitKey`) [code] |
| `680f9007` | Esc didn't always close blame | The tab's Esc branch closes an expanded blame, which takes the focus while shown [code] |
| `9c784b5f` | Staged and Unstaged jumped while staging | `rememberInTab` no longer keys the list states on the entries [code] |
| `f5c49f5a` | "Open another repository" replaced the first tab with the same path | `replaceTab(tab, path)` replaces the tab it is given [code] |
| `f81f5221` | Tabs weren't saved after opening another repository in a tab | `replaceTab` saves the tabs [code] |
| `7cbe7e60` | The terminal opened in `.git/modules/<name>` for a submodule | The terminal opens in `GetWorktreeUseCase`'s folder, the working tree [code] |

## Applies: port it

| # | Upstream | The problem in Leaf | Port | Size |
|---|---|---|---|---|
| 1 | `4f0ae9ec` (issue 368) | Ctrl+Tab and Ctrl+Shift+Tab can select a tab that the tab bar has scrolled out of sight. Leaf still has upstream's old effect, which only scrolls to the end when a tab is added (`RepositoriesTabPanel.kt`) [code] | Scroll the selected tab into view whenever it changes, fully (upstream only checks its left edge), in the plain tab row and in the title bar's row with ‹ › | S |
| 2 | `7c5994bb` | A click on a blame line's author does nothing: `Blame.kt` still has `clickable { /* TODO Reenable ... */ }` [code] | Map JGit's blame to a commit per line and select it in the log, as upstream does | S |
| 3 | `98ddedce` | Verify first. `StatusPane` draws `CommitAuthorDialog` itself, so the dialog is laid out inside the 330 dp changes pane, without the dialog layer's scrim, and cut off [code] | A `Screen.CommitAuthor` dialog entry in `AppTab`, fed by a tab-scoped holder of the request, as upstream does | S |
| 4 | `60c3fb48` | Verify first. The Welcome page draws `AppInfoDialog` inside its own layout, the same way [code] | A `Screen.AppInfo` dialog entry | S |
| 5 | `f816d061` | The bottom bar never says "Name not set" or "Email not set": `AuthorInfo.identityToUse()` gives `""` where JGit has no value [code] | Keep the nulls, and check its two other users (the co-authors' "not the user", the sign-off line) | S |
| 6 | `cd03135c`, `6c790499` | Verify first. `StatusPane` asks for the tab's focus when a Staged or Unstaged search opens, which can take it from the search field; and when Esc closes those searches, nothing gives the tab the focus, so the next Esc or shortcut does nothing until a click [code] | Drop the request on open; give the tab the focus after Esc closes a search. Not after Esc closes a diff, where Leaf gives it to the log's list | S |
| 7 | `6bba1dc2` | The repository switcher lists the tab's own repository among the recent ones; picking it opens the same repository again [code] | Leave out the main worktree of the tab's repository | S |
| 8 | `51756519` | `AppTab`'s screen effect is keyed on the selection state only, and every tab shares one `AppTab`: two tabs of one worktree, which Leaf allows, have equal states. Not seen in Leaf [code] | Key it on the tab's view model too (one line) | S |
| 9 | `9c36b80c` | Leaf's refreshes take turns, but the file watcher reads the status on its own when it starts and when folders appear, at the same time as a refresh [code] | A mutex around `GetStatusGitAction`, as upstream | S |
| 10 | `df42f6c4` (its window part) | Leaf always opens maximized: `App.kt` sets `WindowPlacement.Maximized` and nothing saves the window's placement [code] | Save the placement in the prefs node, and the size and position of a floating window, which upstream doesn't | S |
| 11 | `15f34ea6` | Leaf keeps ssh key passphrases for the session whatever "Cache HTTP credentials in memory" says (`AskpassAnswers`) [code] | Decision: let the setting cover passphrases too, renamed "Cache credentials in memory" (recommended), or leave passphrases always cached | S |
| 12 | `587bd58d` | Compose 1.12.1. 1.12.2's only desktop fix is a shadow flicker; it doesn't touch the "RootNodeOwner is already disposed" crash of 2026-11-06 | Take it with the rest, for parity, and run every test | S |
| 13 | `7c861511` | Settings' subtitles are in the text colour, as strong as the titles | Done (`a894ba55`): `FieldTitles` draws them in the secondary text colour, 4.5 : 1 or more in every built-in style, chosen with the user from renders over the text colour at 70%, which fell to 2.7 : 1 in Solarized | S |
| 14 | `483a1c0d` | `OS.kt` doesn't log the OS (a TODO) | Done as one line at the top of each run's log: Leaf's version, the system with its version and CPU, and the Java runtime (`runtimeDescription`) | S |

## Applies, in Leaf's own way

### 15. A proxy for Leaf's own HTTP requests (`af7e7488`)

Upstream restored its proxy settings, which set the JVM's proxy. Leaf removed those settings on 2026-10-10, as they were
never applied, and git's own `http.proxy` covers everything git does. What is left without a proxy is Leaf's own HTTP:
the Ktor CIO clients (`HttpClients.kt`) of the built-in LFS client and of the update check. CIO uses no proxy unless
given one, and the built-in LFS client is now git's LFS filter wherever git-lfs isn't set up, so behind a proxy a clone,
pull or merge with LFS files fails there, and the update check never answers.

Plan: give each request the proxy that git-lfs would use for its URL: `http.<url>.proxy`, then `http.proxy` (through
`git config`, for `includeIf`), then `HTTPS_PROXY`, `HTTP_PROXY` and `ALL_PROXY` with `NO_PROXY`, from the login shell's
environment; the update check, which has no repository, reads the user's config and the environment. No settings UI.
Tests against a local proxy (a JDK `HttpServer` that forwards). Size M.

### 16. Renames in Staged and Unstaged (`65ff0f91`, issue 347)

Leaf shows renames in a commit's list, a comparison and the review (2026-10-19), with a time budget and a limit; Staged
and Unstaged show a deletion and an addition. Upstream now pairs them in the status, on both sides:

- it runs JGit's `RenameDetector` over the added and deleted files of each side at every status, without a limit or a
  budget, reading every untracked file's content;
- in Unstaged it pairs a deleted tracked file with an untracked one, which `git status` doesn't do (git pairs them only
  for an intent-to-add file);
- Stage and Unstage of a rename act on both paths; the diff pane diffs the old path against the new one.

For Leaf it's the L part of the 2026-10-16 triage: every action keyed by a path has to handle a rename (stage, unstage,
discard, discard all, hunk and line patches through `git apply`, ignore, delete, the Untracked group, the amend group,
blame and file history, the bulk targets, the file watcher's status). Plan: a design doc and a mockup first, then build.
Decisions to make there:

- Staged only, as `git status` does (recommended first), or Unstaged too, as upstream does. In Unstaged a rename would
  also take the new file out of the Untracked group.
- The cost: `diffWithRenames`' stages and its 2 s budget, and no content reading of untracked files above a size, as
  the status refreshes while agents write.

## Skip

| Upstream | Why not |
|---|---|
| `c3c95d84`, `df42f6c4` (storage), `d438f28a` | Upstream moved its settings, tabs, recent repositories and window state into several DataStore files, renamed the settings dialog, and reset every 1.x setting. Leaf has its own storage (`AppStorage`, the JSON settings file, the `LeafConfig` prefs node); taking this would reset Leaf users' tabs and settings |
| `910fa361` | It brings back a line under the processing screen's title: "This could take a few seconds", or for Pull the branch pulled. Leaf shows git's own progress there |
| `e54af0c1` | A setting to stop sharing one log chip between a local branch and its remote branch. Leaf's shared chips work differently (by name, with the remote's own menu); nobody asked for a way out. Easy to add later |
| `282d6869`, `3a1ac69d`, `a84e1b61`, `6b57766d`, `72265c61`, `955c51b5` | Upstream's version, `latest.json` and README image |

To watch: upstream's open pull request 373 groups tabs ("repository groups"). Leaf has tab names and colours.

## How to sync

Recommended: write the items above as Leaf commits of their own, each with its tests and without upstream's code, then
record the sync with
`git merge -s ours upstream/main`, whose message lists each upstream commit and what Leaf did with it. The next review
then starts after `65ff0f91`, and `git log main..upstream/main` lists only what is new.

The alternative, a real merge that resolves the ~90 conflicts with Leaf's side and the ports, gives the same tree in one
commit that is much harder to review and test. Upstream's later changes to its new settings files come as modify/delete
conflicts either way.

## Order

1. Items 1 to 9, 12 and 14 on one branch: small fixes, each with a test. Items 3, 4 and 6 start by showing the bug.
2. Items 10, 11 and 13: behaviour or look the user decides (renders for 13, the setting's name for 11).
3. Item 15, the proxy.
4. Item 16, renames in Staged, design first.
5. The `-s ours` merge, with the CHANGELOG entry.
