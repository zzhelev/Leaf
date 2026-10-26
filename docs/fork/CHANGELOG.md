# Fork changelog

This file covers fork-only changes on `main` (called `fork/main` until 2026-10-05). Upstream history is in git.

## Syntax highlighting with TextMate grammars (2026-10-23 to 2026-10-26)

- **Request:** colour code by file type, in as many formats as possible. Leaf had upstream's highlighter, which split
  each line on spaces and checked the words against a keyword list, in 17 languages: no strings, numbers or block
  comments, and no colours for JSON, XML, YAML, Markdown, HTML, CSS or TOML. Planned with the user in
  `docs/fork/syntax-highlighting.md`, from a probe of four engines and a board of palettes (Claude Design, "Leaf colour
  palette").
- **The engine:** TextMate grammars, the format of VS Code, GitHub and shiki, run by a copy of kotlin-textmate kept in
  Leaf (the `textmate` module), with three fixes for speed and memory that measuring it found. 82 grammars from shiki's
  collection, about 60 languages, tokenized as VS Code's engine tokenizes them (a test per language). A file's language
  comes from its name, its extension or its `#!` line.
- **Where:** the diff pane (unified and split, file history, a comparison's full view), blame, and the conflict
  resolver's rows and editor. Each version of a file is coloured whole, once, in the background, so a comment or a
  string that starts above a hunk colours the lines in it; the text shows at once and the colours follow. A diff that a
  refresh loads again keeps its colours. Lines typed in the resolver are coloured after the lines before them.
- **Colours:** nine where Leaf had three: keyword, annotation, comment, string, number, type, function, tag and
  attribute, in every style (palette 1a of the board; Solarized and Teal set their own), each holding 4.5 : 1 on the
  background and both line tints. The light keyword, annotation and comment got darker. A new setting, Code colours
  (Settings → Appearance), draws code in Leaf's, VS Code's or IntelliJ's colours, in any style.
- **Limits:** 2 s a file and 10,000 characters a line are coloured, and the rest stays plain. Stepping through the log
  with a diff open is as fast as before, on Leaf's history and on TensorFlow's (about 10 ms a step).
- **Removed:** upstream's highlighter (`ui/diff/syntax_highlighter/`, 2,000 lines).
- **A demo:** `textmate/tools/demo/make-demo.sh` makes a repository with a file in each language, diffs that change
  every file, and a merge stopped at conflicts.
- Details: CLAUDE.md, "Syntax highlighting"; `textmate/README.md`.

## Cancel during a push, and Stop waiting (2026-10-23)

- **Request:** the follow-up left from deleting a remote's branches: the processing screen's Cancel during a push or a
  remote branch's delete stopped git, not the server. Planned with the user, from a probe and a mockup, and built as
  recommended.
- **What a probe showed** (git 2.54, `git daemon`): a push stopped while git still uploads, or while git-lfs uploads in
  the pre-push hook, leaves the server as it was. One stopped after git's last upload line (`Writing objects: 100%`,
  `Total N`) is taken anyway while the server's hook runs, and Leaf then said nothing, showed the branch as not pushed
  and set no upstream.
- **Cancel until then:** the button stays Cancel while git uploads, and now does what it says. Once git has sent
  everything, the screen says "Waiting for origin", the button goes, and after 10 s it comes back as "Stop waiting", as
  in the dialog that deletes several branches. A remote branch's delete is only a command, so it gets Stop waiting
  after 10 s from the start.
- **After Stop waiting,** a strip under the toolbar asks origin whether it took the push (or deleted the branch), again
  every 2 s for up to 30 s, and then says so: "origin took the push: main is at 7b6ee23.", "origin hasn't taken the push
  of main: it's still at 8180de8." with Fetch, or that it couldn't ask, with Try again. When origin took it, Leaf does
  what git would have: `origin/main` moves to the pushed commit and a new branch gets its upstream. A real Cancel shows
  nothing, as nothing changed.
- **Asking the push URL** (found while planning): checking a remote branch after a refused delete, or after a stop,
  asked the remote's fetch URL, which is another server when `pushurl` points at a fork or a mirror. It now asks the
  push URL; with several, it says it can't tell, and the single delete shows git's refusal.
- Details: CLAUDE.md, "Remote operations", "Stop waiting, for a push or a remote branch's delete".

## Squash, fix up and reword a branch's commits, also onto the latest main (upstream issue 73, 2026-10-23)

- **Request:** tidy a feature branch's commits in Leaf, such as an agent's branch of small `wip` commits, without
  finding where it left main or fearing a rebase. Leaf had only upstream's interactive rebase, which starts before its
  list is edited. Planned with the user from a working mockup (Claude Design, "Leaf squash and interactive rebase"): part
  A of `docs/fork/squash-and-rebase.md`. Part B, an editor that plans first, comes next.
- **A range in the log:** Shift-click a second row, or Shift+↑↓, selects the rows between. The right pane lists them,
  and their menu copies their hashes. ⌘-click (Ctrl-click) still compares.
- **The items:** a commit's menu has "Edit message…", "Squash into parent…" and "Fixup into parent"; a range's menu
  "Squash N commits…"; a local branch's, in the side panel, on its log label and in a worktree's menu, "Squash branch
  into one commit…". An item that can't be used is there, faded, with why: "On main", "Its parent is on main", "Includes
  a merge", "Not one after another", "In a rebase". "Fixup into parent" on a `fixup!` commit whose title names another
  commit than its parent says "Names “Add the parser”": the fix would go into the wrong commit. Leaf finds the commit
  named as `git rebase --autosquash` does.
- **Nothing is replayed:** Leaf makes the new commits from the trees the branch already has, with git (identity, signing,
  `post-rewrite` as after `git rebase`), so it never stops at conflicts, and the working tree and uncommitted changes
  stay as they are. A branch checked out in an agent's worktree can be tidied from any tab. A branch that moved meanwhile
  is refused, changing nothing.
- **The message first:** squashing and rewording open a dialog with the commits and the message, focused after its
  subject. A squash starts from the first subject that says something (not `wip` or `fixup!`) with a list of the
  others; "Start from" switches to all the messages or the oldest one. It says how many pushed commits a force push will
  have to replace. Fixup into parent runs at once, with the parent's message.
- **On top of the latest main:** "Squash branch into one commit…" can put the one commit on main's newest commit. Leaf
  checks the merge when the dialog opens: the box is checked when it's clean, and off with the files when they conflict
  or uncommitted changes are in their way, an ignored file included (git would overwrite it). A checked-out branch's
  files take main's changes and keep every other uncommitted change, staged ones staged.
- **Undo:** a strip under the toolbar says what was done, with Undo, which puts the branch (and its files) back, and
  "Push with force…" when pushed commits changed. It stays until closed.
- Details: CLAUDE.md, "Squashing, fixing up and rewording a branch's commits" and "A range of commits in the log".

## Deleting branches: their copies on origin, after a Stop, and without git (follows upstream issue 116, 2026-10-23)

- **Request:** the three follow-ups left after deleting a remote's branches, explored with the user and built as
  recommended, with a fourth found on the way.
- **A local branch and its copy on origin together:** "Delete branches" and "Delete merged branches" get a switch, "Also
  delete their copies on origin (2)", off each time the dialog opens. A copy is the branch's upstream, and it goes with
  its branch, but origin's HEAD and default branch, which servers refuse. One that isn't merged into origin's default
  branch, or named like `develop` or `release/…`, is marked in amber: Leaf points out the risk and leaves the choice to
  you (a first version kept them, which overruled an explicit choice). With the switch on, each row says what happens to
  its copy, the button is red and says "Delete 3, and 2 on origin", the local branches go first, and a copy goes only
  when its local branch did, with the lease. The result says what happened to each copy. The single Delete branch dialog
  gets "Also delete origin/x on origin", unchecked, marked when risky.
- **"Delete merged branches…" moved** out of the Local branches sort menu, which is about how they're listed, into the
  header's own ⋯ menu, as Worktrees has, and onto a right-click of the header.
- **Delete anyway:** a branch, or copy, that someone pushed to since the fetch says the commit origin has now, and the
  result offers "Delete it anyway", which deletes that commit (it's the new lease, so a further push still stops it). A
  branch already gone counts as deleted. The single remote delete's refusal is a question: "Delete origin/x anyway?",
  Cancel or Delete anyway.
- **Stop is now "Stop waiting", after 10 s:** stopping git doesn't stop a server that already has the request, which git
  sends within a second of connecting. Probed with git 2.54 against a `git daemon`: the server deleted the branches,
  Leaf kept its copies of them, and nothing changed locally for the file watcher to see, so the side panel showed them
  until the next fetch. So while git deletes on a server, there's no button at first, and after 10 s "Stop waiting",
  for a slow or hung connection. After it, the dialog asks the server (`git ls-remote`) and shows what happened:
  "Stopped waiting: origin deleted 2 of 3", each one's commit, and "Still on origin." for the others. A server still in
  its hook deletes them a moment later, so while it still has some, the dialog asks again every 2 s for up to 30 s, and
  the rows follow ("Stop checking" ends it). Leaf's copies of the deleted ones go.
- **Without git:** no JGit fallback, which would only help on HTTPS with the setting off. The dialog's button fixes the
  cause instead: "Turn it on" for "Use git for remote operations", "Open Settings" when git isn't found.
- **The single remote delete gets the lease** (found while exploring): right-click `origin/x` → Delete pushed without
  one, so a commit someone pushed since the last fetch was deleted without a word. Now it's refused with
  "origin/x wasn't deleted: someone pushed to it since you last fetched", which then offers to delete it anyway. A
  branch already gone still only loses Leaf's copy, as before. It also deletes a branch under its name on the server
  when the fetch refspec maps it elsewhere, where it deleted a branch of the wrong name.
- Details: CLAUDE.md, "Deleting several branches", "A remote's branches".

## Fetch all shows where the remote's branches are now (2026-10-23)

- **Request:** a screenshot of the log after a pull request was merged on Bitbucket and Fetch all ran twice: the merge
  commit was in the log, but `origin/develop`'s label was still on the commit before it, and the merged branch kept its
  cloud mark, though the fetch had pruned its remote branch.
- **Cause:** Fetch all, in the toolbar's Pull menu, refreshed only the log (upstream's code since its refresh rework of
  2026-07-06, still on its `main`). The log walks from the refs on disk, so the new commit showed, but its labels and the
  side panel's Remotes come from the remote branches, which nothing read again. The file watcher saw the refs change,
  but it ignores changes during a task and for 1.5 s after, and the second fetch changed nothing. Fetch in a remote's own
  menu refreshes everything, and showed them right.
- **Fix:** Fetch all also refreshes the remote branches and the tags, as a fetch brings new tags too.
- Tests: `FetchingAllRemotesTest` runs Fetch all in a tab whose remote merged a branch, deleted it and tagged the merge,
  and compares the tab's remote branches with `git for-each-ref`, also when another remote fails to fetch. Putting the
  log-only refresh back failed both.

## The update button can be read again (2026-10-23)

- **Request:** a screenshot of the bottom bar, where "Update 1.4.0 available" couldn't be read.
- **Cause:** since the Style setting (2026-10-22), a `SecondaryButton`'s text takes its role's colour, and the bar still
  gave the button a fill of `primary`: the action role's dark blue on the muted blue, 1.35 : 1 in Tonal's light theme,
  and under 4.5 : 1 in seven of the built-in styles' ten themes. Classic drew it as before, and Quiet's dark theme
  white on blue.
- **Fix:** the action role's tint and text, as Stage's buttons, 6 to 15 : 1. Classic is unchanged, and Quiet draws it as
  a plain button, as it draws every button but the main action. Option B of three, chosen with the user from renders of
  the bar in every style; the others were the main action's fill, a second filled button under Commit, and a link.
- Tests: `BottomInfoBarTest` renders the bar in every built-in style and theme, and checks the button's fill and that
  its text holds 4.5 : 1. Putting the fill of `primary` back, or giving the text the fill's colour, each failed it.

## Delete several of a remote's branches at once (follows upstream issue 116, 2026-10-23)

- **Request:** the remote part of upstream issue 116, left out of the local one on 2026-10-22 and planned with the user
  on 2026-10-23. Agents push branches that pile up on the server after their pull requests are merged.
- **Selecting:** the same keys as for local branches, in the Remotes section: Cmd-click (Ctrl-click on Windows and Linux),
  Shift-click and a folder's "Select its N branches". A selection holds one remote's branches; a click among the local
  ones or another remote's starts over. The chip shows in the Remotes header. Remote branch rows now show as selected.
- **Deleting:** right-click a selected branch → "Delete 4 branches on origin", or the remote's own menu → "Delete merged
  branches on origin…". The dialog shows the remote's URL and when it was last fetched, and for each branch who made its
  tip and when, and what deleting loses, as for local branches: merged into the remote's default branch (squash merges
  too), on other branches, or commits only there. The remote's default branch and its HEAD are refused. Only merged
  branches start checked, and names that look long-lived (`develop`, `staging`, `release/…`, `hotfix/…`) start unchecked
  even when merged. The button is red and says "Delete 3 on origin". With an unmerged branch checked, it warns that a
  server can close its open pull requests.
- **Safe on the server:** each branch is deleted only if the server still has the commit the dialog showed
  (`--force-with-lease`), so a branch someone pushed to since the last fetch stays. A branch the server refuses, such as a
  protected one, never stops the others, and what the server said is shown. Cancel stops git while it deletes, and the
  dialog then says some may already be gone.
- **The result stays**, with each deleted branch's commit and the command that pushes it back:
  `git push origin <commit>:refs/heads/<name>`.
- **Decided with the user:** no typed confirmation, no "remove only my remote-tracking copies" (the next fetch brings them
  back), one remote at a time, no Fetch button in the dialog. Without a usable git the dialog says what to turn on, as only
  git deletes them.
- Details: CLAUDE.md, "Deleting several branches", "A remote's branches".

## Teal, a built-in style (2026-10-23)

- **Request:** the Teal style from the styles demo, built in, previewed first.
- **Teal:** Tonal's look with a teal accent, a raspberry danger, an amber warning and a green success, on white and
  #0F0F0F, listed after Solarized. Refined from renders of the tab, the graph and the gallery, as the demo's file had
  two flaws: in dark, the section headers, the log's header and the diff toolbar stayed Tonal's navy next to teal
  buttons, so they're set by hand in Teal's hue (#003B3A, #152F2D); and lane 2, which follows the danger colour, came out
  a rose next to lane 6's pink, so lanes 2 to 6 are set by hand to orange, blue, pink, green and violet. Every check holds
  on both sides.
- **The id `teal`** is the built-in's: a file of one's own named Teal now gets `teal-2`, as Solarized's does.
- Tests: Teal's definition, its checks on both sides, its dark headers' hue and its lanes' spacing. The tests that used
  "Teal" as a user's style use "Lagoon".
- Details: CLAUDE.md, Settings, "Styles".

## Styles: a library, an editor, and Solarized; the JSON custom theme goes (2026-10-22)

- **Request:** custom colours that cover the whole app, made in Leaf rather than in a JSON field, and Solarized among
  them. The plan is `docs/fork/custom-styles.md`, from the "Style Library" and "Custom Style" boards in Claude Design.
- **Theme** is Light, Dark or System. The Custom theme and its JSON field are gone, with no migration: `custom_theme`
  is deleted from the settings file, and a stored `custom` theme reads as Dark.
- **Style** is a library. A style has a light and a dark side, and sets every colour Leaf draws with: backgrounds,
  surfaces, text, file icons, diff and syntax colours, buttons and notices, and the graph's six lanes. Settings →
  Appearance shows the styles as cards, light half and dark half, with "+ New style" and "Import style…". A card's menu
  duplicates, exports and, for your own styles, edits and deletes. Deleting the selected style selects Tonal.
- **Built in:** Tonal (the default), Classic and Quiet, as before, and Solarized. They can be duplicated, not changed.
- **The editor:** the button look (Classic, Tonal or Quiet), the background as a light and a dark colour, and Accent,
  Danger, Warning and Success. Leaf works out every other colour from them, keeping each look's lightness, and moves
  what wouldn't read at 4.5:1. Advanced lists every colour, light and dark; any can be set by hand from a palette of 12
  hues by 6 shades or a hex code, which a dot marks. Live previews of both sides use Leaf's own buttons. The editor
  doesn't explain what it moved or warn about colours set by hand: the previews show it (decided with the user, after
  notes, warnings and contrast ratios turned out to be noise).
- **Files:** a style is a JSON file in Leaf's settings folder (`styles/`, next to `user_prefs.json`), with its name, look,
  seeds and the colours set by hand. Export writes one where you pick (a new save dialog), Import adds one.
- **Lanes:** Tonal and Quiet draw the graph in muted lanes (row 1g of the "Leaf Colours" board, chosen with the user from
  renders): about 3:1 on the light background, where Classic's green holds 2.4. Classic keeps Leaf's old lanes.
- **Solarized:** Tonal's look with Solarized's own seeds, and its published colours set by hand where they hold the checks.
  The light panels are worked out (#F6EFDC), as base01 text holds 4.39:1 on base2; secondary text is the board's
  (#516770, and base0 lifted to #8A9C9E on the dark side); comments are base00 and base0. Chosen with the user from
  renders of the tab, the gallery and the editor.
- **Unchanged:** Classic, Tonal and Quiet, apart from Tonal's and Quiet's lanes, render byte for byte as before, compared
  with `main` at each step.
- Tests: the model and its file format, the colour maths, the working out (every built-in exactly, 2,000 random seed sets
  per look and side holding every check, colours set by hand), the library on disk, the gallery, the editor and the
  graph rendered. 115 mutations, each caught by a named test.
- Details: CLAUDE.md, Settings, "Styles"; `docs/fork/custom-styles.md`.

## Skip a rebase's commit with conflicts, with guards (2026-10-22)

- **Request:** Skip for a commit that a rebase stopped at with conflicts, as for a cherry-pick. Asked whether it could make a
  mess first: git's and JGit's skip reset every tracked file, so an edit to another file, in no commit, would be lost; and
  the commit leaves the branch itself. Built with the five guards proposed then.
- **What it does:** the buttons are Abort, Skip and Continue at such a commit, for rebases that git or Leaf started.
  - Skip is disabled while other tracked files have uncommitted changes, and its tooltip says to stash or discard them.
  - It asks first, naming the commit and the branch, how many files' changes go, and that Abort before the end brings
    everything back.
  - Not at an edit stop, where the commit is already applied.
- **After the rebase:** when it ends without commits that you skipped, a dialog lists them, hashes and subjects, with
  "Cherry-pick them back". After an abort it says nothing, as they're back on the branch.
- Tests: both engines' stops and skips, the notice and the abort in a tab with real git and JGit; the rule, the block, the
  buttons, the confirmation and the notice rendered. 8 mutations, each caught by a named test.
- Details: CLAUDE.md, "Skipping a rebase's commit with conflicts".

## Delete several local branches at once (upstream issue 116, 2026-10-22)

- **Request:** upstream issue 116: "When I have many local branches left from previous work and I want to clean them up, I need
  to right-click every single branch and select Delete branch." Agents leave many branches behind, so this is a clean-up flow.
- **Selecting:** Cmd-click (Ctrl-click on Windows and Linux) adds a branch to the selection or takes it out, Shift-click adds
  the branches shown between the last one clicked and this one, and a folder row's menu has "Select its N branches". A
  "4 selected ✕" chip in the Local branches header shows the count. Esc, the chip's ✕ and a plain click on a branch clear it.
  Releasing the key, or clicking in the log, keeps it. A collapsed folder's branches are never selected unseen.
- **Deleting:** right-click a selected branch → "Delete 4 branches". The dialog lists each branch with a checkbox and what
  deleting it loses: merged into the base (also squash-merged), not merged but all its commits on other branches or tags, not
  merged with N commits only here, or can't be deleted (the current branch, a branch a worktree uses, with that worktree's
  folder). The branches that lose nothing start checked. "Check: all, safe, none".
- **The result stays:** after Delete the dialog lists what it deleted with the commit each branch pointed to (`was a1b2c3d`,
  "Copy list" copies the whole hashes), and what it left and why. One branch that stays never stops the others. A branch that
  moved since the dialog opened, or is gone, is left, and a different commit is never deleted: the delete carries the commit
  it expects.
- **Delete merged branches…** in the Local branches sort menu (since 2026-10-23 its header's ⋯ menu) opens the same dialog with every branch merged into the base
  checked, the branch-side twin of "Clean up merged worktrees". Branches that a worktree uses are listed dimmed.
- **Not done, decided with the user:** remote branches. Deleting one is a push that affects everyone, so it is planned on its own.
- Details: CLAUDE.md, "Deleting several branches". Mockups: the side panel with a selection and the dialog in two layouts, picked
  on 2026-10-22 (one list).


## Skip a cherry-picked commit with conflicts, and each stop's own message (2026-10-22)

- **Request:** Skip for a commit that a cherry-pick of several commits stopped at with conflicts. It was only offered once
  the commit had come out empty, so leaving out a conflicted commit meant taking your side for its files first, or aborting.
- **What it does:** the status pane's buttons are Abort, Skip and Continue while git waits at such a commit, conflicts open
  or resolved. Skip asks first and says how many files' changes are lost, then leaves the commit out and picks the rest.
  Not offered for a pick as one commit, where git's skip would also drop the changes staged from the commits before it,
  nor after local changes stopped a pick, where it would leave out a commit you haven't seen.
- **Fixed:** when git stopped a second time, at a later commit's conflicts, the commit field kept the first stopped commit's
  message, and Continue committed the later commit with it. The field now starts over with each stopped commit's message.
- Tests: Skip with the conflicts open and each stop's message in a tab with real git; the rule, the buttons and the
  warning rendered. 6 mutations, each caught by a named test.
- Details: CLAUDE.md, "Cherry-picking a branch's commits".

## Lists spacing out of beta, Compact at 32 dp, and the comparison's commit rows follow it (2026-10-22)

- **Request:** drop "(Beta)" from Settings → Appearance → "Lists spacing", and see whether another value would help.
- **No new value** (decided with the user): rows can't go below Dense's 26 dp, where a hovered file's 24 dp Stage button
  still fits, and Scale (75–300%) already makes everything bigger.
- **Compact is 32 dp** (was 34): it was 4 dp from Spaced's 38 (2 dp in the branches pane) and 8 dp from Dense's 26, so it
  looked like Spaced. The steps are now 38, 32 and 26. Chosen with the user from renders of the tab at both heights.
- **The subtitle** says what the setting does: "Row height in the log, the file lists and the branches pane" (was
  "Spacing around lists items").
- **An unknown value no longer stops Leaf at launch:** a `lines_height` that this version doesn't know reads as the
  default, as the theme does since 2026-10-20. 1.3.0 and earlier still throw on one.
- **The comparison pane's commit rows** follow the setting, as its file rows do. They were 28 dp at every spacing, so at
  Spaced, the default, they grow to 38 dp.
- Tests: `LinesHeightSettingTest`, and the row heights in `ComparisonPaneTest`. Putting back the old behaviour failed
  each.
- Details: CLAUDE.md, Settings, "Lists spacing".

## Cherry-pick from a branch's menu, and finish a cherry-pick of several commits that git stopped (2026-10-22)

- **Request:** right-clicking a branch in the log or the branches pane offered no cherry-pick, to copy a worktree's fix onto
  the feature branch checked out in the main worktree. Cherry-pick was only on a commit row's menu, and a branch label's own
  menu covers the row's.
- **What it does:** a branch's menu, after Merge and Rebase, has "Cherry-pick commit a4880d1" when the branch has one commit
  that the current branch lacks, which runs at once as the commit row's item does, and "Cherry-pick 3 commits…" when it has
  several, which opens a dialog. The dialog lists the commits, oldest first, each ticked, with merge commits and those already
  applied greyed and left out; "As one commit" stages their changes together with their messages ready in the commit field;
  the commands it runs show below. It refuses staged changes and an operation in progress, and notes unstaged changes.
- **Interrupted cherry-picks of several commits:** git keeps the commits left in its sequencer when it stops at a conflict.
  Leaf's Commit used to finish the stopped commit and leave the rest stuck, and Abort kept the commits already picked. Now the
  status pane has Continue (or Skip, when the commit came out empty) and Abort, which run git's own `--continue`, `--skip` and
  `--abort`, and the banner says to continue. This also covers such cherry-picks started in a terminal or by an agent.
- **Reworded:** the banner and Abort of changes staged as one commit, which a squash merge and a pick as one commit both leave:
  "Changes staged as one commit. Review them, then commit." and "Undo staged changes" (were "Squash merge staged…" and "Abort
  squash merge").
- **Chosen with the user** from a Claude Design board ("Leaf cherry-pick from a branch"): one commit at once and several in a
  dialog (1b), with check boxes (2b). A plain "Cherry-pick" on a branch was turned down, as git takes a branch as its tip alone.
- Tests: counts, commits and the sequencer against git's own answers, a tab that picks, continues, skips and aborts with real
  git, the dialog, the menus and the status pane rendered. 19 mutations, each caught by a named test.
- Details: CLAUDE.md, "Cherry-picking a branch's commits".

## More ways to compare: a commit's own changes, two versions in file history, any ref, the working tree (2026-10-22)

The four "not yet" items of the comparison below, option a of each from mockups (`Compare Step 7.dc.html`), chosen with
the user.

- **A commit's own changes inside a comparison:** a click on a commit in the comparison's commit lists shows that commit's
  changes (against its first parent) in place of the comparison's, under a strip "Changes of e701c86 · 2 of 5" with ‹ ›
  and "All changes". A second click and Esc go back. In the full view, the parent and the commit name the diff's sides.
  An open diff follows the file list when it changes, and a file whose versions didn't change keeps its diff, scroll
  included, when the comparison is read again.
- **Two versions of a file in file history:** ⌘-click a second row, or "Compare with selected" in a row's menu. The older
  row is A, the newer B, with badges on both rows and a strip above the diff, swap and "Back to one commit". The same
  version in both says so. A click or the arrows go back to one commit; Esc too, before it closes file history.
- **Any branch, tag or commit:** A's and B's names in the comparison pane open a list of the tab's branches, tags and
  remote branches, filtered as you type; a hash or a revision such as `HEAD~2` shows as a commit. "Compare with…" in the
  commit, branch and tag menus, and "Compare…" in the Actions list, open the pane with that ref, or the current branch, as
  A and B's list open. Nothing asks a remote.
- **The working tree:** ⌘-click between a row and "Uncommitted changes", or "Compare with working tree" in the menus,
  compares with the files on disk, uncommitted changes and untracked files included (tagged "untracked"; ignored files and
  folders left out). The working tree is always B, its row in the log gets the B badge, and it reads again as files
  change. Since they split shows what the branch and its uncommitted work would bring into A.
- **Later:** a worktree's review with its uncommitted changes, a pinned base in file history, and "Compare with working
  tree" in file history.
- Tests: the file between two commits, the working tree comparison (on a twin copy with everything added, against
  `git diff --cached` and `git ls-files --others`) and revisions against git; the view models with fake git actions; the
  pane, the full view, file history and the menus rendered. 51 mutations, each caught by a named test.
- Details: CLAUDE.md, "Comparing two commits or branches" and "File history's two versions", and `docs/fork/compare.md`,
  step 7.

## Fold the changes pane away: a bottom bar button, Ctrl or Cmd+Shift+C, and the Actions list (2026-10-22)

- **Request:** after the branches pane's fold (next entry), the same for the right-hand panes, "Staged, Unstaged and the commit
  area". With both folded the log and the diff have the whole window to step through commits, the diff following.
- **What it does:** a button at the right end of the bottom bar, after the version, folds the pane away and shows it again. The
  log and the diff take its 330 dp: about 1212 dp wide in a 1440 dp window instead of 874, and all 1440 with the branches pane
  folded too. Its icon is a panel on the right, filled while the pane shows. Ctrl+Shift+C (Cmd+Shift+C on a Mac) does the same, as
  the branches pane's Ctrl+Shift+B does for the left, and the Actions list has "Hide changes pane" and "Show changes pane".
- **Kept:** as for the branches pane, the choice is the same in every tab, survives a restart, and the pane's width is as it was
  when it comes back.
- **Held open:** the pane can't stay folded while a merge, rebase, cherry-pick, revert or squash merge waits for the user, as
  Merge, Continue and Commit are in its commit box and the conflicted files in its Unstaged list. It shows until the operation
  ends, then folds again. A comparison or a worktree review, which show only in this pane, hold it the same way. The icon shows
  an amber dot then, its tooltip says why ("Changes pane: shown while a merge is in progress"), and a click, the shortcut and the
  Actions entry do nothing. Selecting the uncommitted changes while it is folded leaves it folded.
- **Chosen with the user** from two placements on a Claude Design board: the bar's right end, as the left icon is at its left
  end, under the pane it folds (this), and both icons together at the left edge. The name "changes pane" and the rules for
  holding the pane open were chosen there too, and the shortcut was left to Leaf: the branches pane's with the pane's initial.
- **Checked in the real tab,** with real clicks and keys on a scene, against a demo repository: both panes folded and a diff
  followed from commit to commit, the focus after folding from the commit message field, a merge stopped at conflicts, and a
  comparison.
- Details: CLAUDE.md, "The changes pane's fold toggle".

## Fold the branches pane away: a bottom bar button, Ctrl or Cmd+Shift+B, and the Actions list (upstream issue 136, 2026-10-22)

- **Request:** upstream issue 136 asked for the diff to use more than the middle pane, "usually you don't need to know which
  branches you have when you examine diffs". With the log above the diff (previous entry), the width left to ask for was the
  branches pane's.
- **What it does:** a button at the left of the bottom bar, before the git user, folds the pane away and shows it again. The
  log, the diff and the rest take its 220 dp: 1102 dp wide in a 1440 dp window instead of 874. The icon is a panel, filled
  while the pane shows. Ctrl+Shift+B (Cmd+Shift+B on a Mac) does the same, and the Actions list has "Hide branches pane" and "Show
  branches pane", with the shortcut shown at the end of its row (any Actions entry can show one now).
- **Kept:** the choice is the same in every tab and survives a restart, and the pane's width and its search text are as they
  were when it comes back. While it's folded the worktree list stops polling, as it does when the pane is out of sight.
- **A bug the real tab showed:** folding with the focus in the pane's search field left the tab with no focus, so the next
  shortcut (New branch, Refresh) did nothing until a click. The tab now takes the focus a frame after a fold when nothing in it
  has it. Folding with the focus in the log or the diff leaves it there, so the log's arrow keys go on.
- **Chosen with the user** from five options, drawn at the real size on a Claude Design board: the bottom bar (this), a toolbar
  button (rejected: the toolbar is for actions on the repository, and each view control makes it busier), a chevron in the filter
  row, a tab on the divider, and no button at all. The shortcut and the Actions entry came with the choice. Ctrl or Cmd+B is New
  branch, so Shift keeps them apart.
- **Not built:** folding by itself while a diff is open (the literal request), and a double-click on the divider.
- Details: CLAUDE.md, "The branches pane's fold toggle".

## Compare any two commits, branches or tags (upstream issues 206 and 283, 2026-10-21)

- **Request:** see what differs between two commits or branches, not only a commit against its parent. Upstream issue
  206 asked for it and was closed as a duplicate of 283, "Ctrl-click two commits in the graph". Leaf had one comparison,
  a worktree row's "Review changes", which only compared the worktree's branch with the base.
- **Starting one:** ⌘-click (Ctrl-click on Windows and Linux) a second row in the log, as other git clients do. Also
  right-click items, so it can be found without being told: "Compare with selected (5be239a)" on a commit, with
  "⌘-click" at its right, and "Compare with main (current)" on commits, branches (in the side panel and on the log's
  chips) and tags, or "Compare with HEAD (detached)".
- **A and B:** the two sides get an amber A and a light blue B badge, on their rows in the log (both drawn as
  selected), in the pane, in the full view and above each side of a file's diff. A is the older side, and the ancestor
  whenever one contains the other, so the changes read forwards; from a menu, the current branch comes first unless the
  other side is its ancestor. A branch side follows its branch, so the comparison updates when an agent commits; a
  commit or a tag stays where it is. ⌘-click on A or B drops it, on another row it replaces B, and the swap button turns
  them around.
- **Two modes:** "Since they split" (`git diff A...B`, what merging B would bring) and "All differences"
  (`git diff A B`). Two branches start in the first, anything else in the second. The switch shows only when the two
  have diverged; otherwise a line says how they meet ("main and gh-pages share no commit, so this shows every
  difference between them.").
- **Where it shows (layout 1c, of three):** in the right pane next to the log, where the two picks stay in sight: A and
  B, the switch and a line that says what the mode leaves out, the commits only in B and only in A, and the files, as in
  a commit (views, sort, renames). A file opens a full view of the whole tab, as file history and the conflict resolver
  do: the commits and files on the left, the diff on the right with "‹ 2 of 5 ›", and ↑ ↓ through the files. The first
  Esc goes back to the log with the comparison in the pane, the second ends it, selecting A.
- **The review is one comparison now:** "Review changes" opens the same pane, with A the base and B the worktree's
  branch, and gains the full view, the swap and the switch. It keeps its own line about the worktree's uncommitted
  changes, and still asks what a merge would bring.
- **Chosen with the user, from mockups:** the entry points, the default modes, A and B rather than From and To, and
  layout 1c over the diff over the log (1a, 640 px for the diff) and the whole tab straight from ⌘-click (1b, which hid
  the picks at once).
- **Not yet:** a "Compare with…" picker for any branch, tag or hash; comparing with the working tree (⌘-click on the
  uncommitted changes, which does nothing while comparing); two versions in file history; a commit's own diff from the
  commits lists.
- Tests: every pair of sides of temp repositories in both modes against `git diff`, `git rev-list` and
  `git merge-base`; the view model's order, modes, swaps, clicks and Escs with a fake git action; the pane, the full
  view, the badges, the log's rows and the menus rendered in both themes. The log and the menus ran against a real
  repository through the Dagger graph, and the full view with the real diff pane in a dev run.
- Details: CLAUDE.md, "Comparing two commits or branches", and `docs/fork/compare.md`.

## The log stays above an open diff, and the diff follows the selected commit (upstream issues 343, 136 and 128, 2026-10-21)

- **Request:** selecting a file in a commit's list swapped the log for the diff, so checking several commits meant closing
  the diff, picking the next commit, and opening the file again. Upstream issue 343 asked for the file changes in a panel of
  their own, 136 for the diff to use more than the middle pane, and 128 for the history to stay visible beside the diff.
- **What it does:** the centre pane splits, the log above and the diff below. The branches pane and the file list stay
  where they are. A divider between them drags (double-click resets), and the log's share of the height is kept after a
  restart. A button in the diff's header, before the X, gives the diff the whole pane, as before, and brings the log back.
  A pane too short for both (under 318 dp) shows the diff over the log, as before.
- **The diff follows the selection.** With the log's arrow, page, Home and End keys, or a click, the next commit shows the
  same file's diff, or the move that renamed it. A commit that doesn't change the file keeps the diff area, with the file's
  path and "Log.kt has no changes in a4fefd8", so the log doesn't jump while stepping, and the next commit that changes it
  shows its diff again. Esc, the X or the button closes it. The commit's file list keeps its own arrows, so two axes are on
  the keyboard: the log steps commits, the file list steps files.
- **Closing the diff gives the focus to the log**, so the arrow keys go on working. The log keeps its selected row in
  view when the diff opens under it.
- **A diff belongs to the list it was opened from.** The uncommitted changes close a commit's diff, and selecting a commit
  closes an uncommitted file's diff (the Staged and Unstaged lists are gone). A diff opened from a worktree's review doesn't
  follow, and closes with the review. With the log hidden, a commit without the file closes the diff and shows no placeholder.
- **Chosen with the user** from three mockups at the real size: A (this), B (the branches pane folds and the log moves
  left of the diff, which is narrower than today in every window, and wraps nearly every line in a 1100 dp one) and C (a
  setting for both, 1.6 times the work). The placeholder was picked over closing the diff. No row in Settings.
- **Cost:** on a repository of 5,000 commits with a 10,000-line file, 60 steps 33 ms apart took 9.3 ms each, and scrolling the
  diff in the split 4.8 ms a frame. The log was composed under the diff before too, so nothing new is composed.
- **A race, found by that run:** the diff's slot recomposes before its parent's new content at measure time, so for one
  frame the diff pane saw "no selection", closed itself, and forgot the file. Its "not found" path no longer forgets.
- **Not built:** the branches pane's own toggle (upstream issue 136's width, three places were mocked up), the interactive
  rebase's list above a diff, and following a file across a rename into older commits.
- Details: CLAUDE.md, "The diff above the log".

## Style: Tonal, Classic and Quiet colours for buttons and notices (2026-10-21)

- **Request:** the button blue (#0070D8) and red (#C93838) felt too bright next to the diff tints. Reviewed with the user
  on the "Leaf Colours" board in Claude Design, which compared six options in both themes.
- **What it does:** Settings → Appearance → Style, under Theme: **Tonal** (the default, for new installs and for anyone
  who hasn't chosen) gives buttons a pale tint with dark text, in the diff tints' family, and only Commit, Merge and
  Continue stay filled; **Classic** is Leaf as it was; **Quiet** makes most buttons neutral, with red only in the text of
  destructive ones. Unstage loses nothing, so in Tonal and Quiet it's neutral rather than red.
- **Strips and notices:** the conflict banner, the index banner, the file watching chip, the worktree dialogs' notices
  and the resolver's counter take a pale amber with a line along their edge, and the restored strip a pale green, in
  place of the full amber and green with near-black text. The conflicted file's icon and the hints' icons take a darker
  amber, which holds 3 : 1 on white (the old one held 1.75 : 1).
- **Everywhere else:** in Tonal and Quiet the accent blue is a muted #376AAA (#3464A0 in the dark theme): toasts,
  switches, links, the tab underline. The resolver's amber arrows and conflicts, the diff, the log and the file status
  icons keep their colours.
- **Custom themes** keep their own colours, laid out as Classic; the Style row is greyed out for them.
- Classic renders byte for byte as before. Every style's text holds 4.5 : 1 where its buttons and strips are (a test).
- Details: CLAUDE.md, "The Style setting".

## Wrap body at 72, a ticket key from the branch name, and the commit template (2026-10-21)

- **Request:** the last three items of the commit message mockups, built with the defaults proposed to the user.
- **Wrap body at 72 columns** (the commit message's ⋯ menu): wraps the body's paragraphs that have a line past 72
  characters, joining and breaking their lines between words. The subject's paragraph, a trailer block, comment lines,
  indented and fenced code, and what follows a scissors line stay as they are, and a list item's next lines line up
  after its marker. A word that would make a line a comment (`#123`), a list item or a fence stays on the line before.
  Greyed out ("Every line fits") when nothing would change, and Cmd+Z undoes it.
- **A ticket key from the branch name:** on a branch such as `feature/PROJ-482-login`, the empty field shows
  `PROJ-482: ` while it has the focus, with "Tab adds PROJ-482 from the branch name" under it, and Tab writes it. The
  form follows the newest subjects (`[PROJ-482] `, `PROJ-482 `), else `PROJ-482: `. Only uppercase keys: `hotfix-12`
  would read as one. Where the field suggests Conventional Commits types, the key is the first scope instead.
- **`commit.template`:** the file it names (from git's config, `includeIf` included; `~/`; a relative path from the
  working tree's root) shows in the empty field the same way, after the key when there is one, and Tab writes it. Only
  for a plain commit, as git uses it, not an amend or a merge's message. Committing the template unedited is refused,
  as git refuses it, and so is the key with nothing after it.
- Details: CLAUDE.md, "Wrap body, ticket keys and the commit template".

## Conventional Commits suggestions, and the commitlint config (2026-10-21)

- **Request:** the Conventional Commits completion from the commit message mockups, with a setting, and types and scopes
  from the repository's commitlint config. The mockup's defaults, chosen with the user.
- **What it does:** at the start of the subject the commit field suggests a type (`feat`, `fix`, `docs`, …, and the
  history's own), the most used first, with what each usual one is for; after `type(` it suggests that type's scopes.
  A pick writes `type: ` or `type(scope): `. The keys are the co-authors' list's: ↓ ↑, Enter, Tab, a click, Esc.
- **Where:** Settings → Git → Commits → "Conventional Commits suggestions": Automatic (the default) suggests where at
  least half of the newest 50 subjects use a type, or a commitlint config is set up; Always; Off. A note under the list
  says why it shows ("44 of the last 50 subjects here start with a type", "Types from commitlint.config.js").
- **commitlint:** the first config in commitlint's order (`package.json`'s `commitlint` key, `.commitlintrc*`,
  `commitlint.config.*`). Its `type-enum` and `scope-enum` lists narrow the suggestions and add their own; a config that
  only extends `config-conventional` or `config-angular` gets the preset's types. Leaf reads the lists written out in
  the file, and doesn't run JavaScript: a list that code builds, or YAML's block style, narrows nothing. Leaf suggests,
  it doesn't refuse a commit.
- Details: CLAUDE.md, "Conventional Commits suggestions".

## The diff's View menu: ignore different amounts of whitespace, `git diff -b` (upstream issue 139, 2026-10-21)

- **Request:** "Ignore whitespace" hid every whitespace difference, which is too much in code where the indent carries
  meaning (Python, YAML, Makefiles): a block's first indent, or a space that appears or goes, was hidden with the noise.
- **What it does:** the menu's switch became a "Whitespace" group of three rows with a check on the one that is on: **Show
  all changes**, **Ignore different amounts of whitespace** (`git diff -b`) and **Ignore all whitespace** (`-w`, as before). `-b` hides a
  different amount of whitespace (4 spaces to 8, tabs to spaces, trailing whitespace, CRLF to LF) but still shows
  whitespace that appears where there was none or goes: `f(a,b)` to `f(a, b)`, or an indent where a line had none.
- **The rest follows the mode:** the strip says "Ignoring different amounts of whitespace.", the button's tooltip says "ignoring
  different amounts of whitespace", a file with nothing else changed says "Only whitespace changed", hunk and line buttons are
  off, and file history, commits and worktree review follow, as with `-w`.
- **Saved settings:** stored as `ignore_change`. A saved "ignore all" is unchanged, and an older Leaf reads the new value as
  showing all changes.
- **Chosen with the user:** `-b` yes, a radio group over a second dependent switch; the end-of-line modes no. Trailing-only
  (`--ignore-space-at-eol`) and CR-only (`--ignore-cr-at-eol`) hide less than `-w` and `-b` already hide, and JGit has no
  comparator for the second.
- JGit's `WS_IGNORE_CHANGE` finds the edits of `git diff -b`, and its hunks, context lines included, are equal to git's on
  the same 134 pairs of texts in Unstaged, Staged and a commit (a test).
- Details: CLAUDE.md, "The diff's View menu".

## Co-authors, Add Signed-off-by in the menu, and a sign-off that joins the trailers (2026-10-21)

- **Request:** the co-author trailers from the commit message mockups. Option C, chosen with the user: suggestions while a
  `Co-authored-by` line is typed, and "Add co-author" in the commit message's ⋯ menu, which adds the line where git puts
  trailers and opens the same suggestions.
- **Whom it suggests:** authors of the commits that the log has loaded, newest first, and the co-authors their messages
  name; one per email; not you; committers left out. ↓ ↑ and Enter or Tab pick, or a click; Esc closes the list. The
  list sits above the field and leaves the focus in it. Someone not in the history is typed as `Name <email>`.
- **Placement:** `git interpret-trailers` places the line: with the other trailers, before trailing comment lines, or
  after a blank line, never twice. An empty `Co-authored-by:` line is never committed.
- **Several co-authors:** Enter at the end of a `Co-authored-by` line starts the next one and opens the list; Enter on an
  empty one ends the list. Nothing is highlighted until something is typed or ↓ is pressed, so Enter there isn't a pick.
- **Add Signed-off-by in the ⋯ menu:** a switch for the repository's sign-off setting (the same as the Actions list's
  "Signoff config"), and a muted "· with Signed-off-by" in the title row while it's on, as the line is only added when
  committing. Named as git's `--signoff` describes it: "Sign off" read as an action.
- **Sign-off fix:** Leaf's sign-off was added as a paragraph of its own, which left a `Co-authored-by` line before it out
  of the trailer block that git hosts read. It now joins the trailers as `git commit -s` puts it, checked against git
  on eight messages.
- Details: CLAUDE.md, "Co-authors".

## The diff's View menu: ignore whitespace, show whitespace characters (upstream issues 138, 139, 2026-10-21)

- **Request:** reviewing an agent's diff is half whitespace noise: re-indented files, trailing spaces, tabs for spaces, CRLF
  for LF. The diff showed every such line as changed, and nothing showed what kind of whitespace a line had.
- **What it does:** one "View options" button in the diff's header, after the Unified and Split icons, with a menu of
  three switches: **Ignore whitespace** (lines that only differ in whitespace are equal, as `git diff -w` compares them),
  **Show whitespace characters** (a dot in each space, an arrow across each tab, a red tint on trailing whitespace, and `CR`
  where a line ends with CRLF) and **Wrap long lines**, which moved in from its icon. The button is lit while a switch
  differs from how a diff first looks, and its tooltip says which. All three are one setting for every tab, kept after a
  restart; an older settings file reads as "show everything, no marks, wrap".
- **The diff follows it** everywhere a file's diff shows: Unstaged, Staged, a commit, file history (which has the menu in
  its commit strip) and a worktree's review. Turning it on or off keeps the line that was at the top of the list at the top.
- **While whitespace is ignored,** a strip under the header says so, with "Show them" to turn it off in one click, and
  hunks and lines in Staged and Unstaged have no Stage, Unstage or Discard buttons: their patches can't carry the context lines
  that ignoring leaves, so git would refuse them or apply them to the wrong text. The actions refuse such a hunk too.
- **A file that only changed whitespace** is still in the Staged and Unstaged lists. Its diff says "Only whitespace changed
  in this file", with a button to show the changes, where it would have been blank.
- **Not copied:** the marks are drawn over the text, so selecting and copying a line gives the line.
- **Chosen with the user:** the menu (B) over three more header icons (A): a laptop's diff pane left the path 50 dp with
  the icons and 130 dp without. Wrap moves into it; at first only "ignore all" (`-w`), with `-b` added later the same day
  (the entry above); kept after a restart, with no Settings entry for the two new switches.
- JGit's comparators find the same edits as git's `-w`, `-b` and `--ignore-space-at-eol` on 134 pairs of texts (a test);
  `--ignore-cr-at-eol` has none. Context lines show the new version's text, as git does.
- Details: CLAUDE.md, "The diff's View menu".

## Commit message conventions: subject length, a blank line after it, comment lines, a ruler (2026-10-21)

- **Request:** guidance on how a commit message should look, without enforcing anything. Chosen with the user from
  mockups: a count with a tint for the subject's length (A + B), a hint for a subject that runs on, git's handling of
  comment lines, and a column ruler as an option.
- **Subject length:** past 50 characters the title row counts the subject, muted; past 72 it shows "87 / 72" in amber
  and the characters past 72 are tinted (git tools and servers often cut subjects there). The subject is the first line git keeps. Nothing
  is blocked. Settings → Commits → "Subject length hint", on by default.
- **A subject that runs on** into the next line gets a hint under the field, "No blank line after the subject. Insert
  one", with the link adding it and the reason in a tooltip.
- **Comment lines:** Leaf now commits what `git commit` with an editor commits: `commit.cleanup` (strip by default,
  whitespace, verbatim, scissors) and `core.commentChar` / `core.commentString`, `auto` included. Lines that git leaves
  out are dimmed and slanted in the field. Until now JGit committed the message as typed, `#` lines and trailing blank
  lines included. Leaf's commits now end with a line feed, as git's do. A message of only comments counts as empty
  (Commit and Merge say so).
- **Why not JGit's own clean-up:** it reads an indented `  # x` as a comment, takes only the first character of a longer
  comment string, and doesn't strip anything for `auto`. Leaf's follows git's `strbuf_stripspace`, checked byte for byte
  against `git stripspace` and `git commit`.
- **Ruler:** Settings → Commits → "Column ruler" (off, 50, 72, 80, 100). It switches the field to the monospace font and
  draws a line at that column, once the pane is wide enough (about 600 points for 72); the text keeps wrapping at the
  pane (option R1 of two).
- Details: CLAUDE.md, "Commit message conventions".

## The diff pane can turn line wrapping off (2026-10-21)

- **Request:** long lines in the diff always wrapped, with no way to turn it off, while the conflict resolver already had
  a switch.
- **What it does:** a wrap icon in the diff's header, after the Unified and Split icons, and "Wrap long lines in diffs" in
  Settings → Layout, which are one setting for every tab, on by default. Turned off, each line stays on one row and the
  lines scroll sideways together: Shift and the wheel, a sideways swipe, or the scrollbar under the lines. The line
  numbers and the line's Stage and Discard buttons stay where they are; both sides of a split diff scroll together.
  Another file starts at its left edge.
- **Chosen with the user:** the header icon and the setting, global, sideways scroll.
- The resolver's sideways scrolling moved to `ui/components/SidewaysScroll.kt` for both to use; the resolver is unchanged.
- Details: CLAUDE.md, "Wrapping in the diff pane".

## The log's rows take the arrow keys (2026-10-20)

- **Request:** the log was mouse-only: after a click on a commit, the keys did nothing.
- **What it does:** Up and Down select the previous or next row, and the commit pane follows, as for a click. Page Up
  and Page Down move by what the list shows, less one; Home and End go to the first and last row. The uncommitted
  changes' row is the first row when the log shows one. A selected branch or tag counts as its commit. With nothing
  selected, or a selected commit that isn't loaded, the first key selects the top row (End the last).
- **The list takes the focus when a row is clicked,** as the changed-files lists do, so the keys do nothing until the
  first click. Alt+arrows still change the tab, and Shift, Ctrl or Cmd with a key is left to other shortcuts.
- **Left and Right do nothing**, decided with the user: a row has nothing to step into. Not checked what other git
  clients do with them.
- **No wrap-around:** the selection stops at the first and last loaded commit (the log can hold tens of thousands).
  The list scrolls the least that shows the row, a row at a time; pages, Home and End jump. A key that can't move is
  consumed, so the list doesn't scroll under the selection.
- **Search is separate:** Enter and Shift+Enter still step through matches (they only scroll); the arrows select.
- Details: CLAUDE.md, "Log keyboard".

## Double-click on the file list's column separator fits the file column (2026-10-20)

- Staged, Unstaged and Files changed in split columns: a double-click on the separator sizes the file column to the longest
  file name shown, within the 15 to 85% a drag keeps, and saves it. Not added to the log's columns.

## The commit message has a title row and a hint, and Amend is in its menu (upstream issue 344, 2026-10-20)

- **Request:** upstream issue 344: the commit message field "looks like text inside an input box" where the two panels
  above have title rows. The reporter's sketch: a "Commit Message" row like "Staged" and "Unstaged", then the text area, with
  "Write your commit message here" as a placeholder at 30 to 50% transparency.
- **What it does:** a row "Commit message" above the field, as tall and coloured as the Staged and Unstaged headers (34 dp,
  the same surface, text and left edge; a test compares them). The field under it has no floating label: the hint "Write your
  commit message here" shows while it is empty, at 60% of the text colour (4.1:1 on white, 7.2:1 on the dark theme's
  field), and goes with the first letter. The field has the lists' background, and an underline only while it has the focus,
  where the grey one at rest made it look like an input box.
- **What the row says:** "Commit message"; "Merge message" while merging; a muted "· read-only" after the title at a rebase
  stop that can't be amended, which replaces the old label's "(read-only)"; and "· amending last commit" in the accent
  that the Amend checkbox had when ticked, while Amend is on (the button reads Amend too).
- **Amend moved into a "⋯" menu at the end of the row,** the Unstaged header's menu button, with one item, "Amend last
  commit", checked while on. The checkbox under the field is gone, so the field has room for 22 dp more at the same saved
  height, and the Commit button has the 8 dp above it that Merge's and the others have. At an amendable rebase stop the
  menu is the rebase's amend switch (Amend is on there at first, so the tag shows). During a merge, a cherry-pick, a revert,
  and at the rebase's other stops, there is nothing to amend and no menu. It takes a click more than the checkbox did.
  **Chosen with the user, on 2026-10-20, from mockups:** a title row only, Amend as a checkbox in the row, and Amend in a
  menu (picked); the hint at 50, 60 or 70% opacity (60% picked); one title per state (as in "What the row says", picked).
- **Sizes:** the row is a fixed extra on top of the saved commit field height, which still counts what is under it, so a
  saved height needs no migration and gives the same field. The lists give up 34 dp in total (17 each at the defaults). `fitTo`
  and both drag handles take the row off the pane first, the minimums are unchanged, and the sections add up to the pane at
  any size (5,000 random panes, sizes and drags).
- **Unchanged:** Cmd+Enter, undo, the focus, the draft file (`LEAF_COMMIT_DRAFT`) and a custom theme's text colours. The
  message is still drawn in the typography's color, `onBackground`, also when it can't be edited: the scheme's disabled
  color never applied. The Files changed pane's commit message is a different, read-only thing, and wasn't touched.
- **Found on the way:**
  - Leaf's typography has a color of its own, so neither a `TextField`'s placeholder color nor its `textColor` applies;
    the hint passes its color itself.
  - The hint fades in over 83 ms when the field empties (Material's own), so a test has to let the scene's clock run.
  - A `Popup` isn't drawn by the offscreen scene, though its items are in the semantics.
- **Checked:**
  - Tests: `StatusSectionSizesTest` (18), `CommitMessageFieldTest` (11), `CommitFieldTest` (19); the suites of `:domain` (443)
    and `:app` (529) pass.
  - 24 mutations: 22 caught by a named test. The two others: an equivalent one (`textColor`, as above) and
    `heights.commitSection` in `StatusPane`, which needs a tab.
  - A temporary harness on the real Dagger graph, on a demo repository with a linked worktree in each state (normal, a merge,
    a rebase at an edit stop and at conflicts, a cherry-pick): the pane's geometry against `fitTo`, the hint going when
    something is typed, the draft file holding the message, Amend through the menu, and Cmd+Enter in the field amending the
    last commit with the staged change. The demo is `commit-message-title-demo.sh` in the session notes.

## The log starts from the commits that other worktrees have detached (2026-10-20)

- **Found, not requested:** while answering whether other worktrees' HEADs should get a mark (HEAD's row, upstream issue
  255), a probe showed commits missing from the log. A `TestTab` on temp repositories: `main` with two commits,
  `git worktree add --detach ../other`, a commit made in `other`, the tab on `main`: two rows. `GetLogUseCase` walked from
  the branches (the tab's own detached HEAD comes in as the branch named `HEAD`), stashes, tags and remote branches, and
  another worktree's HEAD is no ref. A detached worktree at an older, reachable commit showed that commit, which hid the
  bug. So an agent's work on a detached HEAD, which Claude Code, Codex and Conductor leave worktrees in, was not in Leaf's
  log until something named it. `git log --all` shows it. The user chose to fix it and not to add any mark (2026-10-20).
- **The fix:** the walk also starts from the commit of each **other** worktree whose HEAD is detached
  (`IGetOtherWorktreeHeadsGitAction`). A rebase stopped at a conflict and a bisect have a detached HEAD, so they count.
  Left out: the tab's own worktree (already the branch `HEAD`), a worktree on a branch (its tip is the branch's, an unborn
  one included), one whose folder or `.git` file is missing (git's prunable, or a locked one that is as missing), a `HEAD`
  file that can't be read or whose commit the repository lacks, and a bare repository's own HEAD. Each commit once, and
  not again if a ref has it. If reading fails the log is what it was. Loading more commits starts from them too. **No
  look change:** the commit has no chip and no mark.
- **Where the HEADs come from, settled with measurements** (git 2.54, macOS, a clone of this repository, 1,803 commits, 20
  worktrees): `GetLogUseCase` alone takes about 42 ms (39 to 62) and starts no process; `git worktree list --porcelain
  -z` takes 18 ms (29 at most; a bare `git rev-parse` 11 ms), which would make each log refresh 43% slower; reading the
  20 `worktrees/*/HEAD` files takes 0.7 ms. So `OtherWorktreeHeads.kt` reads the files, with the folder list that the
  branch guards use. Not `RepositoryDataRepository.worktrees`, which `RefreshDataUseCase` refreshes after the log.
- **Reftable:** in a reftable repository every `HEAD` file is `ref: refs/heads/.invalid`, the main worktree's too, so the
  files can't tell a detached worktree (probed with git 2.54). Then, and only then, `git worktree list` answers through
  `parseWorktreeList`, with the same rules. The files are right for every other repository, so the common case starts no
  process.
- **Refresh:** a probe with the app's real watcher (macOS, FSEvents) shows what each git action in another worktree
  reports under the common git dir. A commit on a detached HEAD, a checkout, a switch and a rebase's steps report
  `worktrees/<name>/HEAD`; `git add` and `git status` only `index`; a commit on a branch the branch's ref, `logs/HEAD`,
  `index` and `COMMIT_EDITMSG`, never `HEAD`; adding or removing a worktree its folder and its `HEAD`. So
  `WatchedRepository` has a new change, `OTHER_WORKTREE_HEAD`: another worktree's `HEAD` (and the main worktree's, in a
  linked worktree's tab), or a direct child of `worktrees/` created or deleted (on Linux the files of a new folder can go
  unreported before it is watched). It refreshes `LOG + WORKTREES`; the log's refresh lists the worktrees again after it,
  so it costs no extra refresh. Not `index`, `logs/HEAD`, `HEAD.lock` or `ORIG_HEAD`.
- **The same throttle:** `WorktreeChangesRefresher.onChanged(includesLog = true)` makes the next refresh a log refresh,
  right away or after the 2 s gap, and keeps the request until a refresh takes it, so index churn after a HEAD change
  doesn't lose it. Cost, tested: 300 `index` events in 1.5 s refresh the worktrees and never the log; 300 `HEAD` events
  refresh the log once at the start and then once per gap.
- **Tests (46):** `GetOtherWorktreeHeadsGitActionTest` (19: git's `rev-parse HEAD` as the reference; detached, from the main
  worktree and a linked one, the main worktree's from a linked tab, a branch, an unborn branch, two worktrees at one
  commit, a rebase stopped at a conflict, a bisect, a missing folder locked or not, a folder whose `.git` file is gone
  (git calls it prunable), an unreadable `HEAD` and a commit that isn't there, no `gitdir` file, a bare repository, 20
  worktrees, and a reftable repository: stubs, the filter, a prunable one, a bare one), `GetLogStartCommitsTest` (5: the
  hashes the walk starts from, with a recording walker), `OtherWorktreeHeadsInLogTest` (13: a `TestTab` on real
  repositories, the log compared with `git rev-list` over the refs plus those commits, each commit checked to be on no
  ref; detached, rebase, bisect, a linked tab, a branch, a missing folder, the tab's own HEAD marked once and no chip, 20
  worktrees, and the refresh by the watcher's events: a commit, a worktree added, one removed, an index storm, a HEAD
  storm), `WatchedRepositoryTest` (4 new), `WorktreeChangesRefresherTest` (4 new), and a real-watcher test in
  `RefreshingOnChangesTest` (two commits on a detached HEAD show with no refresh from the test). All 20 mutations were
  caught by named tests: no extra heads, no dedupe against the refs, a missing folder not skipped, a worktree whose `.git`
  file is gone not skipped (the first try of this one survived: the folder check hid it, and the files and git paths
  disagreed about such a worktree until `.git` became the check), the tab's own worktree not skipped, an unknown commit not
  skipped, the reftable stub not seen, the git fallback taking branches, taking the tab itself and taking a prunable
  worktree, no `HEAD` rule, the rule widened to every file of the worktree's git dir, no rule for a worktree added or
  removed, none for the main `HEAD` in a linked tab, a HEAD change refreshing the list only, the refresher never asking
  for the log, always asking, losing the request when another call waits, the use case ignoring the flag, and the log
  refresh not throttled. The suites: `:domain` 476 tests and `:data` 1,435, none failing, before the last two tests
  were added; after them, the data packages `tab`, `git.worktrees` and `repositories` (293 tests) passed again.
- **Rendered and run:** the real `Log` on a demo repository (`main` with a tag, a worktree on `feature/export`, three
  agents on detached HEADs with two, one and three commits of their own), light and dark, before and after: 7 rows
  before, 13 after, the agents' lanes in the graph and no chip on their rows. The harness then made a commit in an agent's
  worktree while the tab was open, and the log showed it with no refresh from the harness (real watcher). The dev app ran on
  it once, its window captured with `screencapture -l` and nothing clicked: the commit appeared within seconds, and
  `agent-tests` went from `↑1` to `↑2` in the Worktrees section.
- **Found while testing:** synthetic watcher events in a `TestTab` must carry real paths. On macOS the tab's git dir is
  `/var/...` and the common git dir `/private/var/...`, as the real watcher reports it; a first version of the tests sent
  `/var/...` paths, which matched the tab's git dir, refreshed everything, and failed the "index only" tests. The tests now
  send canonical paths.
- **Noticed, not changed:** a newer agent commit takes the first lane, so `main` and HEAD's ring move to the next lane
  and the chip changes colour, as for any branch newer than the checked out one (the first lane is reserved for the current
  branch only while there are uncommitted changes). The parent row's "Not in the log: no branch, tag or stash reaches it"
  tooltip is a little short now: another worktree's HEAD reaches it too. And `refreshData(LOG)` on the 20-worktree clone
  took 2 to 46 s a call, as it also runs `git status` in each (erratic, a busy machine; not investigated).

## An avatar colour per person, and a mark on HEAD's row in the log (upstream issues 231 and 255, 2026-10-20)

- **Requests:** upstream issue 231, "the avatars of different people are highlighted in the same colour", and upstream
  issue 255, "it's hard to locate the current HEAD on the graph". Both look changes, so each went through options with
  real renders of the log (ten authors, a case variant of one email, a bot, GitHub as committer, a linked worktree, HEAD
  in the middle, both themes) before any code; the user picked B and the ring with bold, and agreed the bar below.
- **What the task text had wrong:** the log didn't paint every avatar in one colour. Its Author and Committer columns and
  the node's tooltip passed the row's **lane colour**: one person changed colour from lane to lane, different people on a
  lane shared one, and the white initial held 2.4 to 3.8 : 1 on the six lane colours (2.65 on the main lane's blue). The
  commit footer and file history had the theme's primary for everyone. And **a detached HEAD does have a `currentBranch`**,
  a branch named `HEAD` at its commit, so one rule finds HEAD's row in every state.
- **Avatar colours (231):** an avatar without a picture is the initial on the person's own colour, the same in the log,
  the details footer, file history and the node's tooltip. It comes from a hash of the email (trimmed, NFC, lower case; the
  name when there is no email) into 12 fills, Tailwind's 600 and 700 tones. Someone with no name and no email keeps the
  theme's primary, and the committer's grey (the badge, and the Committer column when someone else committed) is left
  alone. The bar, tested: white initial 4.5 : 1 on every fill (lowest 4.60), each fill 3 : 1 against the plain row of the
  light and dark themes (lowest 3.05), no two within 18 in CIELAB (closest 18.8). A custom theme can't break the first and
  can't be promised the second. Not the six lane colours darkened (the other option: six colours, so five authors share
  one 90% of the time, against 62% for twelve, and an avatar would match an unrelated graph line), and not theme colours
  (the dark theme's secondary is yellow, 1.65 : 1 with white; green, red and amber mean added, deleted, conflicting).
  One person with two emails has two colours, as in git.
- **HEAD's row (255):** the commit that the tab's worktree has checked out gets a 2 dp ring in the text colour around its
  node and its subject in bold. Chosen from a ring, a ring with bold, bold alone and a row tint: a tint looks like the
  selection (pale blue in the light theme, and the dark theme's selection covers it), and a ring in the lane's colour
  blends into the node. The mark leaves the selection, the search's bar and outline, and the dim of non-matching rows
  alone, and was checked in the worst case: HEAD selected, matching and focused.
  - Which row: `headCommitHash(currentBranch)`. A branch gives its tip; a detached HEAD (also in a rebase or a bisect) its
    commit, once, not also for the branch at that commit; a linked worktree's tab only that worktree's HEAD; an unborn
    branch or an empty repository nothing; a HEAD older than the log's loaded commits no row.
  - The ring is 14 dp, the lane's cell: a bigger one would be cut to it, and its inside is the 10 dp node's size.
- **Not done:** other worktrees' HEADs get no mark, and the ring has no gap around the node, which would need a cell
  wider than the lane.
- **How:** `domain/avatars/AvatarColors.kt` (`avatarKey`, `avatarPaletteIndex`, `AVATAR_PALETTE`), `AvatarImage(color =
  null)`, `LogColumnCells` without `nodeColor`, `domain/log/HeadCommit.kt`, `ui/log/HeadMark.kt` (`HeadRing`,
  `boldWhenHead`), and the `isHead` parameter through `CommitsList`, `CommitLine`, `CommitsGraph`, `CommitNode` and
  `CommitMessage`.
- Tests (43): `AvatarColorsTest` (12: five people's colours worked out apart from the code, case, spaces, email over name,
  blank email, accents, no identity, 5,000 emails over 12 colours, neighbours, UTF-8), `AvatarPaletteTest` (4: the bar),
  `AvatarImageTest` (6: the fill read off a rendered avatar), `HeadCommitTest` (5), `MarkingHeadTest` (10: a real tab on
  real repositories, each against `git rev-parse HEAD`: a branch and a checkout from a terminal, detached at a tip and at
  a commit no branch has, a linked worktree both ways, an unborn branch, an empty repository, a rebase stopped at a
  conflict, a bisect, and a 2,100-commit repository from `git fast-import` with HEAD at its root), `HeadMarkTest` (6:
  the ring's pixels in both themes, selected too, and the bold). 20 of 21 mutations were caught by a named test; the
  21st, the ring drawn over the node instead of under it, shows the same picture (the ring is hollow and its inside is the
  node's size). The whole suite: 2,382 tests, none failing, on `016a73cd` plus this work.
- **Run manually:** the dev app on a temp repository with ten authors (a case variant of one email, a bot, GitHub as
  committer on the merges, a linked worktree on its own branch, HEAD in the middle), in the light theme, its window
  captured: each person keeps a colour from row to row, the grey badges are grey, and `wip/search-polish`'s row has the
  ring and the bold subject. The dark theme and a detached HEAD were rendered offscreen from the same `Log`.
- **Found while testing:** the first renders of the ring were 14 dp, not the 18 dp that the code asked for: the lane's cell
  is 14 dp wide and Compose cut the ring to it. The code now says 14. The real `Log` was rendered against a demo
  repository through the Dagger graph in a throwaway harness that found the ring by its pixels (one, on HEAD's row, in
  both themes and for a detached HEAD) and the bold through the text layouts (HEAD's subject only).

## Parents in the commit details: see them, and jump to them (upstream issue 270, 2026-10-20)

- **Request:** upstream issue 270, "display parent commit": see the parent of the selected commit and jump to it, and a
  merge commit should show its two parents (the reporter's model is SmartGit's right panel).
- **What it does:** the commit's footer lists each parent under the hash: an arrow, the short hash and the subject. A
  merge numbers its rows (1, 2, in git's order). A click selects that parent in the log and scrolls to it, as a click on
  its row would. A right-click opens **Select in the log** and **Copy commit hash**, and the tooltip has the message, the
  author and date, and the whole hash (for a merge, also which parent it is). It shows for log commits and for a branch or
  tag picked in the side panel; worktree review and file history have their own views and are unchanged.
- **Chosen with the user from a mockup (2026-10-20):** option A of three (rows inside the footer; the others were hash chips
  and a block of its own between the message and the footer), click selects, load until found.
- **One parent, or none, costs no height:** the footer is still 72 dp for a normal commit and for a root commit ("Root
  commit, no parent"). A merge adds 20 dp. A stash lists only its first parent, the commit it was made on: its other parents
  are the index and untracked-files commits, which the log skips.
- **More than 4 parents** (an octopus merge) show 3 rows and "Show N more parents", which opens the rest in place, 7.5 rows
  at most before the block scrolls (the row cut in half says there are more), and "Show fewer parents" closes it. Selecting
  another commit closes it too. Every parent stays reachable, hidden ones by the same click and menu. Asked by the user
  after the mockup: the first plan ended in "and N more parents" with no way to open them.
- **A parent older than the commits the log has loaded** (it loads 2,000 and then 500 per page; the first parent of the last
  loaded commit is always one) is loaded by the click. The row shows a spinner and "Looking, click to stop"; the search loads
  a page, then twice as many commits at each step, 25,000 more at most, off the UI thread. A second click, or selecting
  another commit, stops it when the running step ends. When it ends without the commit the row says why and stays so until
  another commit is selected, in amber ("Not in the log": no branch, tag or stash reaches it; "Not in the next 25,000";
  "Couldn't load the log"), and a click tries again, and after the limit goes 25,000 further.
- **Why doubling steps:** the log's walk is `RevSort.TOPO`, so each page costs about the whole history whatever it loads. A
  probe on a repository of 66,000 commits with merges: 155 ms for a page of 500, 190 ms for a step of 10,000. A parent just
  past the loaded log costs one page, and one far away a few steps.
- **Found while rendering:** the first wording of the rows ("Not loaded yet, click to load it", "Not found in 25,000 more
  commits. Click to look further") was cut at the pane's default width of 330 dp, right where it said what to do. The
  rows now have short texts (about 22 characters fit) and the explanation is in the tooltip. And the select waits for the
  log's state to have the loaded commit: `Log` scrolls with the list it had when the selection changed, so a selection that
  comes first scrolls nowhere.
- **A merge whose parent is far:** the log keeps a merged branch's commits together, so which parent is far depends on the
  walk, and the data test uses two long branches to have one either way.
- **How:** `parentLinks` (`domain/log/ParentLinks.kt`) picks the rows; `LoadLogUntilCommitUseCase` loads the log
  until a commit is in it (on `IncreaseLogCountUseCase`); `CommitParentsController` (`repositoryopen/`, made by
  `RepositoryOpenViewModel`) holds the jump and the rows; `ParentRows` (`ui/`) draws them in `CommitFooter`, which takes
  them as a slot. `CommitChanges.kt` changed by 26 lines, the view model by 20. A new drawable (`arrow_upward`),
  strings, and `TestTab` and `TestGitCli.runWithInput` in the tests.
- **Tests (52 new):** `ParentLinksTest` (13: one parent, a merge, root, stash, four and five and 66 parents, hidden
  ones loaded or not, blank and repeated hashes, and which hashes the lookup is asked for), `LoadingLogUntilCommitTest` (8,
  on repositories of up to 5,200 commits made with `git fast-import`: a loaded commit, just past a page, several pages
  away, a merge with a far parent, a parent of the last loaded commit, a commit no ref reaches, the limit, and a cancelled
  search that goes on again), `CommitParentsControllerTest` (14, with a fake search: a click on a loaded parent, the
  search and its progress, a log state that never has the commit, every way a search can end, a second click, another
  selection, a step that ends late, another parent clicked meanwhile, a stash, the hidden rows), `CommitFooterParentsTest`
  (17, rendered at 330 px: every state, the heights, the numbers, a click, the menu and its copy, the links that open and
  close the hidden rows, and the scroll of an opened octopus; set `LEAF_RENDER_DIR` for a PNG of each state in both themes).
  **Full run** on `016a73cd` plus this change (not committed at the time), 2026-10-20: domain 451, data 1,389, app
  544, common 7 tests, all passing. The first full run had one failure, `WorktreeReviewTest` "showing the uncommitted changes
  closes it for the status" (a 5 s wait that timed out in a 14-minute run on a busy machine, in a test that doesn't touch
  this work); it passes 5 of 5 alone and in a run of the app module by itself. After the rebase onto `c1a50aa1` (the
  avatar colours, which the footer draws, and HEAD's mark in the log) every module compiled and the four new test classes
  passed again.
  **20 mutations** of the pure function, the loading, the controller and the rows: 19 caught, each by a named test (the
  stash, repeated and blank hashes, 4 against 5 parents, numbering, doubling steps, the limit, stopping a search, a late
  step, the click on a loaded parent, the reset of the opened rows, the selection of a found parent, the numbers, the
  search on one row, the root row, the copied hash, the footer's height). One survived and stays: the `ensureActive()` at
  the top of the search's loop, as the next `awaitLoadedLog()` sees a cancellation too; it states the contract.
  What the mutations found: a removed limit made the search spin without ever suspending, which hung the build rather than
  failing a test, so `LoadingLogUntilCommitTest` runs on a thread of its own with a timeout (JUnit's interrupt can't stop
  a loop that never suspends); a duplicate "is it loaded" check before the loop was an equivalent mutant, and was removed;
  and a baseline run failed once on "selecting another commit stops the search": the reset for a new selection ran in a
  collector of its own and could land after an immediate click. It now runs in the state's own pipeline, before the state
  for the new commit exists, and the tests wait for that state (16 runs in a row pass, 6 of them under CPU load).
- **Run against the real graph** (a temporary harness, not kept; its copy and the renders are in the session notes): on a demo
  repository of 4,237 commits with a merge, an octopus merge, a stash, and a merge with a parent 2,100 commits down. The view
  model listed the parents, a click selected them, the far parent was loaded (501 more commits) while the row showed
  the search, and the real `Log` scrolled to its row (2115) with it selected.
- **Not done:** no way back to the child after a jump (select it in the log, as before), and a parent that isn't loaded shows
  no subject until it is (it would take a git read per parent). Shallow clones weren't looked at.


## A menu on the rows of file history: restore a version, copy the hash and the message (upstream issues 149 and 146, 2026-10-20)

- **Requests:** upstream issue 149, "checkout a file to a previous version in file history" (the report needs the terminal for
  `git checkout <sha> -- <path>`), and what was left of upstream issue 146, "copy commit details, at least the sha-1", and no way to
  see the whole commit message.
- **What it does:** a right-click on a row of file history opens **Restore this version of the file**, then **Copy commit
  hash** (the whole hash) and **Copy commit message** (the whole message). The row's subject and the strip's show the whole
  message as a tooltip, and a click on the hash in the strip copies it ("Copied"), as in the commit's footer. No "Copy file
  path": it is the same for every row. Items, wording and the rest were chosen with the user from a mockup.
- **Restore writes the working file only** (`git restore --source=<commit> --worktree`), where the report's
  `git checkout <sha> -- <path>` also stages it. What was staged stays staged, and nothing is committed or staged for the
  user. A confirmation says what is lost: the unstaged changes, or an untracked or ignored file's content, or nothing for
  a clean file, and that a missing file is created. No "don't ask again".
- **The commit that removed the file** (deleted, or moved away) keeps the item. The dialog says that the commit has no
  version of the file, with a disabled Restore.
- **File history doesn't follow renames,** which the task text assumed. The history of `new.txt` starts at the rename
  commit, which has the file, and the history of `old.txt` ends with it as the newest row, which hasn't. So the one rename
  case is that boundary row, handled as a removal. Following renames in file history would be a feature of its own.
- **After a restore** a green strip under the header of file history says "Restored to its version in e4f5a6b. The file is
  now changed in your working tree." with "Show changes", which closes file history and shows the uncommitted changes. File
  history covers the status pane, and a toast is gone before it's read. A failure opens `ErrorDialog`.
- **What plain `git restore --source` does that Leaf doesn't** (probed on temp repos with git 2.54 and git-lfs 3.8; the
  probe notes are `file-history-menu-149-146-findings.md` in the session notes):

  | Case | git alone | Leaf |
  | --- | --- | --- |
  | A folder where the file goes, with untracked files | deletes the folder and them, exit 0 | refuses |
  | A file where a folder of the path goes (with unsaved edits) | replaces it with a folder, exit 0 | refuses (a link too) |
  | The file has conflicts | overwrites the markers, exit 0 | refuses |
  | A partial clone can't download the blob | empties the file to 0 bytes, exit 128 | puts the file back, shows git's error |
  | A Git LFS smudge fails (object missing, no git-lfs) | deletes the file, exit non-zero | puts the file back, shows git's error |
  | The commit has no file at the path | `pathspec did not match`, nothing changed | says so before, in the dialog |

  The last two rows are why the working file is moved aside while git runs and moved back when git fails or is cancelled
  (`WorkingFileBackup`). A rename keeps content, mode and link target, and costs nothing for a big file.
- **How:** `FileRestorePlanner` (what the file is now, and the refusals, read for the dialog and again just before the
  restore), `GetFileRestorePlanGitAction`, `RestoreFileVersionGitAction` (through `GitCliRemoteCommand`, so a partial clone's
  download and Git LFS get the credential dialogs, progress and Cancel), `RestoreFileVersionUseCase` (returns the
  result for the strip), `RestoreFileError`, `ConfirmableAction.RestoreFileVersion`, `fileHistoryContextMenu`,
  `RestoredVersionStrip`. `HistoryViewModel` knows whether the repository has a working tree: a bare one gets no item.
- **Not done:** a sparse checkout's path outside the cone is written anyway, as git does. Files with Git LFS need git-lfs
  to be installed, as for any git command that checks them out.
- Tests: `RestoreFileVersionGitActionTest` (24: every case against plain git on a copy of the repository, a failing
  filter, Cancel, Git LFS, a partial clone online and offline, a link in the file's place), `RestoringFileVersionTest`
  (a `TestTab`: the result, the refresh, the failure), `FileRestorePlanTest`, the menu, the copies and the strip's hash
  in `FileHistoryTest`, `HistoryViewModelTest`, and the dialog in `ConfirmActionDialogTest`. 24 mutations were each
  caught by a named test: 11 of the restore, the planner and the backup, and 13 of the menu, the view model, the
  tooltip and the dialog. The backup that copies back instead of moving back survived until the test for a link.
- **Found while testing:** the view model's restore, with the strip's state, ran on the real Dagger graph against a temp
  repository in a throwaway harness (a unit test's `UseCaseExecutor` can't refresh, and `RefreshDataUseCase` can't be
  mocked on JDK 25), and the menu, the strip and the dialog were rendered offscreen and looked at.

## The original project's and its author's names are out of the repository's text (2026-10-20)

- **Why:** the user wants Leaf decoupled from the names. New text had followed that rule since the same morning (the log
  search entry below has none); this sweeps the old text, with the user's yes, in two steps: the project's name, then
  its author's.
- **What:** 165 lines in 28 files for the project's name, and 6 lines in 3 files for the author's: `CLAUDE.md`,
  `docs/fork/` (this file, the plan, the notes, the probes), code comments and test comments.
  - An issue is "upstream issue 186" where it was the project's name and a number.
  - Its code, tags and `main` are "upstream's"; an installed copy of the app, or its prefs and folders, is "the original
    app" (`AppStorage`, the storage entries below, the manual check of the own-storage entry). Its libraries are "the
    original author's".
  - `CLAUDE.md` opens with "Fork of an open-source git client" and points to the README's License section for its name.
    The `upstream` remote is described as "the original project" (`git remote -v` has its URL).
  - **The merge checklist's check no longer spells either name.** It was `git ls-files` on the author's name, to find
    files that landed under upstream's own package folder. It now reads the owner and the repository from
    `git remote get-url upstream` and lists the paths and the files that have either: the paths must be none, and the
    files the README and `AppStorageTest` (see Branches and remotes in `CLAUDE.md`). Tried on the current tree: no
    path, and those two files once the text was swept. The tree has no such folder today; the notes only described one.
  - The Phase 0 probe: the command that opened a folder the way the original app does is now `original-open`, in
    `WorktreeProbe.java` and `worktree-probe.sh`, with the log prefix `[original]` and the temp folder
    `worktree-probe.XXXXXX`.
  - Lines that grew past 120 columns were wrapped again, and nothing else in their paragraphs changed.
- **Stays, on purpose:**
  - The README's License section (GPL-3.0 section 5a): the one sentence that names the project and links its
    repository, whose address has the author's name, and which files keep its license.
  - `AppStorageTest`'s check that no storage name contains the original app's name (`no name refers to the original
    app`): it needs the string to compare with, and it protects an installed copy's data.
  - The address of the `upstream` remote in `.git/config`, which isn't tracked and which merging needs.
  - Commit messages already pushed: `main` is published, so history isn't rewritten.
- **Checked:** the search that the merge checklist now holds finds the names only in the README and `AppStorageTest`,
  and no tracked path has either. A word-by-word comparison with the old text shows only the intended changes. The tests
  of the code files that changed still pass.

## A System theme that follows the operating system (upstream issue 309, 2026-10-20)

- **Request:** the reporter's desktop environment turns dark mode on at sunset and light mode at dawn, and the theme had
  to be changed by hand to match.
- **What it does:** Settings → Appearance → Theme has a fourth choice, **System**, between Dark and Custom. Leaf draws the
  light or the dark theme as the operating system is set, and switches while it is open, with no restart. Its subtitle:
  "Select the UI theme between light and dark mode. System follows your operating system and switches with it."
- **Decided with the user:** the default stays Dark, for a new install too. A new install and someone who never chose a
  theme both have no `theme` key, so making System the default would turn that person's Dark into whatever their
  system says. System is opt-in. Linux's polling is switched on without trying it (below).
- **How:**
  - `Theme.System` (last in the enum) and `Theme.resolved(systemIsDark)` (`domain/models/ui/ThemeResolution.kt`), which
    makes System Dark or Light and leaves the other themes alone.
  - `AppTheme` is the one place that turns the choice into colours. It reads `isSystemInDarkTheme()` only while System
    is selected, through a `systemIsDark` parameter that tests set. Everything else asks `MaterialTheme.colors.isLight`
    or the `appTheme` flow, so the syntax colours, the diff, the conflict resolver and the popups follow with no change.
    A custom theme's JSON has its own `isLight` and ignores the system.
  - **Following the system (read from the Compose 1.12.1 and skiko 0.150.1 jars, then run on macOS):**
    `isSystemInDarkTheme()` changes only because Compose asks skiko for the setting once a second
    (`DesktopComposeUiFlags.pollSystemTheme`). Compose ships that on for macOS and Windows and **off for Linux**, where
    the setting is read once at startup and System would never follow. `followSystemThemeOnLinux()`
    (`theme/SystemThemePolling.kt`, called from `main`) turns it on there. Nothing changes on macOS and Windows.
- **Found on the way:** `ThemeMapper` threw `IllegalStateException` for a stored value it didn't know, inside the
  settings flow that `App.start` reads at launch, so such a value stopped Leaf from starting. An unknown value now
  reads as no choice, which gives the default. A Leaf from before this one still stops on `"system"`: after a downgrade,
  delete the `theme` key from `user_prefs.json`.
- **Verified:**
  - **macOS, by the user flipping System Settings → Appearance** while a watcher logged the setting (`defaults read -g
    AppleInterfaceStyle`) and the brightness of Leaf's window, sampled about twice a second. A dev run with System
    selected: the window was light with the system on Light, dark in the same sample as the system turned Dark
    (10:27:42), and light again in the same sample as it turned Light (10:28:02). Compose's own poll is once a second.
    The native title bar followed the system as before.
  - Two things went wrong in that check, neither in Leaf: the first watcher hung while the user flipped (a missing
    timeout on its own `screencapture`), so the flip was done twice, and it picked a sibling session's Leaf window
    (the same process name) for some samples, which showed as flips of the brightness with the system unchanged.
  - 24 new tests, and 17 deliberate breaks of the code were each caught by a named test.
  - The Theme row, rendered offscreen with the subtitle in both themes at 640 and 420 dp: the subtitle wraps and the
    drop-down keeps its room.
- **Not verified:** Windows (Compose's own default, which polls), and Linux, where the flag is a guess. It depends on
  what skiko's Linux detection reads (the report is from a Linux desktop environment), and a setting it can't read is
  reported as light. Someone with a Linux desktop should flip it there before this counts as done on Linux.
- Tests: `ThemeResolutionTest`, `ThemeSettingTest` (every value stored and read back through a real settings file and
  a restart, an unknown value reads as the default, files from earlier versions still read), `AppThemeSystemTest` (a
  system setting that flips in `AppTheme`, including the palette the rest of the app reads, and that every theme is in
  the drop-down), `SystemThemePollingTest` (which systems poll, so that a Compose update that changes it shows).

## Log search: wrap-around, a clearer dim, a mark on the current match (2026-10-20)

- **Reported:** the up and down buttons stopped at the first and the last result, the commits that don't
  match were hardly dimmer than the ones that do ("at least in the Dark theme"), and with several matches in a row
  there was no sign of which one the counter pointed at.
- **Probe:** a step only scrolls the log, and only when the row is off screen, so with the next match in view the counter
  changed and nothing else did. Shift+Enter did nothing. The old dim was the text colour `onBackgroundSecondary` on the
  message and the names: 11.8:1 against a match's 19.2:1 in the dark theme (7.1:1 against 14.7:1 in the light one), and
  avatars, ref chips and dates weren't dimmed. A refresh doesn't run the search again (not changed).
- **Chosen with the user, from renders of the real rows (options A to E for the dim, F1 to F3 for the mark):**
  - **Both directions wrap.** Down from the last result goes to the first, up from the first to the last, in the
    buttons and in Enter; Shift+Enter goes back. One result stays at 1/1 and Enter scrolls back to it; no result does
    nothing. The counter reads "34/34" after the wrap.
  - **Non-matches fade as a whole row** (message, chips, avatars, author, date), to 45% in the dark theme and 55% in
    the light one. The graph isn't faded.
  - **The current match has a 2 dp outline** in the colour of the matches' bar (gold in dark, purple in light), which
    stays readable on the selected row. A step still only scrolls: it doesn't select the commit, so the commit pane
    keeps the user's own selection.
- **Also fixed:** an empty search field counted every row as a non-match. With the old dim nobody saw it; with the new one
  the whole log would have faded as the bar opened. `SearchResults.hasTerm` says that nothing is searched for.
- **How:** `steppedSearchIndex` (`domain/log/SearchNavigation.kt`) and `stepSearchResult` (it uses `update`, so a step
  can't put an older list back over a new search); `ui/log/SearchMatchStyle.kt` for the dim and the outline;
  `KeybindingOption.SEARCH_PREVIOUS_RESULT`. `LogColumnCells` lost its `isDimmed` parameter.
- **Not done:** searching commits that aren't loaded yet, and running the search again after a refresh.
- Tests: `SearchNavigationTest`, `LogSearchTest`, `SearchMatchStyleTest`; 19 mutations were each caught by a named test.
  A temporary harness ran the real view model and `Log` against a demo repository: wrap both ways, one result, none, an
  empty field, a click, and 3,200 steps racing 150 searches (no index outside its list).


## "Delete file" on a folder that holds another repository (2026-10-20)

- **Bug:** JGit lists an untracked folder that holds another repository as one row, so "Delete file" on an agent's
  linked worktree inside the repository (`.claude/worktrees/<name>`, `.agent/wt`) or on a nested clone deleted the
  whole folder, `.git` included, after a confirmation that only said an untracked file can't be restored. For a linked
  worktree, git's `.git/worktrees/<id>` was left behind (prunable). Found while building Discard all (follow-up 1).
- **Probe (temp repo):** a linked worktree inside and a nested clone are one row each; a folder with only new files is
  listed file by file; an empty folder isn't listed; a linked worktree outside the repository isn't listed.
- **Decided with the user (option C of A refuse, B warn, C hybrid):**
  - **A linked worktree of this repository is refused**, with an `ErrorDialog` that names it and points to Remove in
    the Worktrees section (`DeleteFileError.LinkedWorktree`). Nothing to confirm first, so the menu item goes
    straight to the explanation. `DeleteFileGitAction` looks at the folder again, so a stale selection is refused too.
  - **Any other nested repository** (a clone, or a worktree of another repository) can still be deleted, in a dialog
    titled "Delete repository" that says it's another git repository whose history and uncommitted work can't be
    restored, with a "Delete repository" button.
  - Plain files and folders of new files delete as before; a link is deleted as a link.
- **Not done:** the file count that the mockup showed ("1,284 files"). It needs a walk of the folder when the dialog
  opens, which the status can't afford for every untracked row; the warning doesn't need it.
- **How:** `StatusEntry.nestedRepository` (`NestedRepository.LINKED_WORKTREE`, `REPOSITORY`), set by
  `GetStatusGitAction` from a `.git` folder or file in each untracked folder (`nestedRepositoryOf`, one stat each; a
  `.git` file that points into `<common git dir>/worktrees/` is a linked worktree).
- **Also fixed:** `DeleteFileUseCase` ran as `TaskType.DiscardFile`, so a failed delete said "Discard file failed".
- Tests: `DeleteFileGitActionTest` (linked worktree refused and whole, stale path, nested clone, another repository's
  worktree, new files), the dialog cases in `ConfirmActionDialogTest`.
## Stage all and Unstage all follow the search (2026-10-20)

- **Bug:** with a search open in Unstaged or Staged, the header button still acted on every file, including the ones the
  search hid, and a search that matched nothing staged everything. Only folder rows followed the search. Found while
  building "Discard all", which acts on what the list shows.
- **Fix, chosen with the user from options and a rendered mockup:** the button acts on what the pane shows. While a
  search narrows the list it reads "Stage 3 matches" ("Stage 1 match", "Unstage 3 matches" in Staged); with no search,
  or one that matches every file, it still reads "Stage all"; with no match it is greyed ("Stage 0 matches") and does
  nothing. Two or more selected files still win ("Stage selected"), hidden ones included.
- Logic in `StatusState.bulkTarget` (`ui/status/BulkTarget.kt`), shared by the button's action and its label.
  Tests: `BulkTargetTest`, `StageAllHeaderTest`, `StagingShownFilesTest`.

## Choose… for the Clone dialog's Branch field (follows upstream issue 209, 2026-10-19)

- **Request:** option C of the clone dialog, with the rule that nothing asks the server until the user clicks the
  button. "Typing will not care about the git server, only the Choose… button will care."
- **What it does:** "Choose…" sits beside the Branch field and lists the remote's branches and tags in a popup under
  it. A click asks once (`git ls-remote`); typing in the field, opening the dialog and changing the URL never do. What is
  typed filters the list that the remote sent, locally. A blank URL says "Invalid empty URL" and asks nothing.
- **The popup (decided 2026-10-19, layout A of two):** a grouped list (BRANCHES, TAGS) with the default branch first and
  marked, what was typed in bold, "N of M match" under it, at most 280 dp tall and as wide as the field and the button.
  B was a flat list with an icon per row, which the groups make unnecessary.
  - While it asks: "Asking github.com for its branches and tags…", a spinner and Cancel (git is killed).
  - A failure is in the popup, in the dialog's red error box, with Try again.
  - The field keeps the focus: ↓ and ↑ move through the rows, starting from none, and Enter picks the highlighted row
    only, as what was typed may be the whole name. A click picks too. Esc closes the list before the dialog, and so does
    a press anywhere in the dialog but the Branch row. Tab goes Folder, Branch, Choose…, Clone.
- **git:** `git ls-remote --symref -- <url> HEAD refs/heads/* refs/tags/*`, run in the temp folder through the same
  runner as a clone, so the credential, host key and passphrase dialogs are the clone's. `--heads --tags` was the plan, and
  it leaves out the `ref: refs/heads/main<TAB>HEAD` line that names the default branch. The patterns keep it, and keep
  GitHub's many `refs/pull/*` from being sent. JGit does the same through its transport handler when git can't run.
- **Found while building:**
  - Kotlin block comments nest: `refs/heads/*` in a KDoc opened a comment that swallowed the file, and KSP said an action
    "could not be resolved" (now in CLAUDE.md, Code style).
  - A non-focusable popup gets no outside-press events and a focusable one takes the field's focus, so a press elsewhere
    is found by an `Initial`-pass `pointerInput` on the dialog's column. The popup's rows pick on a tap, as a
    `clickable` would take the focus.
  - The first dialog test of closing the dialog passed without the dialog's code: the test also cleared the view model,
    which cancels the request by itself. The view model is the tab's and outlives the dialog, so the test now closes the
    dialog alone. The same check found a JGit test that accepted any default branch, and a selector test that couldn't
    tell git from JGit (an `insteadOf` in git's config alone does).
- **Tests (56 new):** `RemoteRefsTest` (6), `BranchChoicesTest` (7), `GitCliListRemoteRefsGitActionTest` (12),
  `GitCliHttpsTest` (3: Basic authentication through Leaf's dialog and the cache, a wrong password, a closed dialog,
  and Cancel with no git left running), `CloneViewModelTest` (11), `CloneDialogTest` (12 new, rendered offscreen, one
  with a real pointer press on a row) and `CloneBranchChoicesTest` (5).
  - Mutation checks: 59, each caught by a named test. The list action (each pattern, `--symref`, `--`, the exit code, the
    parser, JGit's default branch and transport, the selector either way), the filter (case, trimming, anywhere, default
    first, the total, a tag as the default), the view model (a second click, a blank URL, the trimmed URL, closing,
    picking, a new URL, starting a clone, the host, a failure, asking on typing or on creation) and the dialog (asking
    on typing in either field, Esc, closing the dialog, a pick, a press elsewhere or on the row, the filter, the focus,
    Enter and the arrows, the highlight after typing, the count, a tap, Try again, the default mark).
- **Manual run** (the real dialog from the real Dagger graph with the real git, offscreen, in a temporary harness),
  against `/tmp/leaf-choose-demo` (51 branches, 123 tags, `main` the default):
  - a local path and `http://demo:secret@127.0.0.1:8765/…` (a `git http-backend` behind Basic authentication) both list
    174 refs, with `main` first and marked; "agent/task-1" leaves 11, "release" leaves 4; a pick fills the field and
    closes the list;
  - a path that isn't a repository shows "The remote refused access to the repository, or the repository doesn't exist."
    and git's lines, with Try again; a blank URL says "Invalid empty URL".
  - Then run by the user in the real window (2026-10-20) against the same demo, with a local path and the Basic-auth
    server: the list, the credentials dialog (a wrong password, a closed dialog, then the right one), a pick and a
    clone of the picked branch. The first try never asked: the harness's credentials in the URL had been stored in
    the user's keychain by git's `credential approve` (`osxkeychain`), and the real Leaf used them. The entry was
    removed with `git credential reject`, and the second try asked. A harness on the real graph uses the user's own git
    config, so it must not put credentials in a URL.


## Discard all in the Unstaged pane (upstream issue 224, 2026-10-19)

- **Feature:** "Discard all changes…" in a new "⋯" menu of the Unstaged header, between the search button and Stage all.
  Throwing away what an agent left in a worktree took a selection, or one file at a time. Always asks first
  (`ConfirmActionDialog`), says what is lost and that it can't be restored, counts the files, names the files with
  conflicts that it leaves (five, then "and N more"), and counts the submodules left.
- **Decided with the user (2026-10-19), from three options each:**
  - **New files:** a box "Also delete N new files", unticked every time and never remembered. It changes the warning
    ("Neither the changes nor the 9 new files can be restored") and the button ("Discard and delete"). With only new
    files and the box unticked, the button is disabled.
  - **Search:** it acts on the files that the list shows, so a search for "parser" discards its matches and the dialog
    says so. Stage all followed on 2026-10-20 (see below).
  - **Placement:** a menu, not a second button beside Stage all, so that a click on the most used button can't reach
    it. Not offered in Staged. The selection is ignored: it has its own "Discard N files".
- **The brief's premise was wrong, found by probing a temp repository:** discarding an untracked file never deleted it
  (`DiscardEntriesGitAction` checks paths out, which does nothing for them, and the folder discard says "N new files are
  kept"). Deleting is the separate "Delete file". So tracked-only was already the behaviour, and deleting new files is
  the one new way to lose work, behind the box.
- **Safe against a worktree that changes under the dialog** (agents write while it's open): `DiscardAllGitAction` reads
  the status again and acts only on entries that it still lists as counted, as `StageAllGitAction` does. A file staged,
  committed, changed kind or conflicted since is left alone, and so is every file that appeared after. It restores by
  explicit paths, never `checkout .` or `reset --hard`, then deletes the new files, and stops before deleting when the
  restore fails (files with conflicts are refused, as in the other discards).
- **Deleting new files:** plain files and links (deleted as links), never a folder. An untracked entry that is a folder
  holds another repository, such as a linked worktree inside this one (what agents' tools make), so it stays. Then the
  folders that this leaves empty go, as `git clean -fd` removes them, but never one with anything else in it, an
  ignored file included. Ignored files are never listed, so never touched. The result equals `git checkout -- .` and
  `git clean -fd` on a twin repository (a test).
- **Bug found by the tests, fixed:** JGit's checkout of a submodule whose commit moved throws `MissingObject` ("Missing
  unknown …") and failed the whole discard, so the folder discard and the selection discard failed too whenever a
  submodule that had moved on was among the files. git's `checkout -- .` leaves submodules alone. `DiscardEntriesGitAction`
  now skips gitlinks, and the status flags them (`StatusEntry.isSubmodule`, from the index, read only when a
  `.gitmodules` file exists), so "Discard all" counts them as left.
- **Tests:** 51 new, each run on temp repositories where git is involved.
  - `DiscardAllTest` (10, domain): what each list holds, the order, a search narrowing it, submodules, no work.
  - `DiscardAllGitActionTest` (13): restore and keep, delete and the folders left empty, an ignored file keeping its
    folder, the twin comparison with git, a half staged file, conflicts (kept, and refused when the status was stale),
    stale entries (staged, committed, changed kind and created since), a new file staged since, a link to a folder
    (deleted as a link, its target kept), a repository and a linked worktree inside, a submodule that moved on, and an
    empty plan.
  - `DiscardingAllTest` (3, a `TestTab`): the task records its outcome and the status refreshes, with and without the new
    files, and a folder that can't be written fails the task after the restore, with the pane refreshed.
  - `DiscardAllMenuTest` (10): the menu in Unstaged and not in Staged, nothing runs before the dialog confirms, the plan
    and the box reach the action, a search, text left in a closed search box, only new files matching, and "Nothing to
    discard".
  - `ConfirmActionDialogTest` (13 new): the wording in each state, the box, the disabled button, five names and "and N
    more", the submodule note, no "don't ask again". `DiscardEntriesGitActionTest` (2 new): a submodule that moved on,
    and no flag without `.gitmodules`.
  - The whole suite (`./gradlew test`, 2,129 tests, none failing, none skipped) ran on this change.
  - 25 mutations are each caught by a named test: restore, delete, and both unchecked against the fresh status; folders
    deleted recursively; emptied folders kept; non-empty folders deleted too; the checkout of submodules not skipped,
    and that together with the action's own filter removed; no gitlink flag in the status; new files deleted before
    the restore; the use case ignoring the box; no refresh after a failure; the plan keeping new files in `restored`,
    not listing conflicts, and `hasWork` ignoring new files; the dialog with the box ticked by default, the button
    enabled with nothing to do, deleting without the box, every conflicted file named, the search missing from the
    subtitle, and the box shown without new files; the menu in Staged too, ignoring the search, losing the box's value,
    asking nothing, and a closed search box still narrowing. One survives on purpose: removing only the action's own
    submodule filter, because `DiscardEntriesGitAction` skips submodules as well (both removed is caught).
  - Two mutations did not compile at first and the harness reported them as surviving (Kotlin's errors go to stderr,
    which it didn't read): "emptied folders kept" and "search missing from the subtitle". Both were redone as
    mutations that compile, and then caught.
- **Manual run:** against `/tmp/leaf-discard-all-demo`, which has a conflict, a submodule that moved on, changed and
  deleted files, new files in a new folder, ignored files, and a linked worktree inside the repository. A dev run
  showed the "⋯" between the search button and Stage all, and the linked worktree listed as one untracked entry
  (`wt`), the case that the folder guard is for. No click was made in the real window: other apps were on top of it.
  The menu, the dialog and the confirm are clicked in the rendered tests instead (`DiscardAllMenuTest`,
  `ConfirmActionDialogTest`), and `DiscardingAllTest` runs the task on a real repository. The panes were also
  rendered at 400 px with long paths: the header and menu, and the dialog in each state.
- **Not covered:** the check in `deleteNewFile` that a file's folder is inside the working tree is defence only: the
  fresh status never lists a file behind a link, so no test reaches it.
- **Left for later (see the notes folder):** "Delete file" on an untracked folder entry deletes it recursively, a linked
  worktree inside the repository included; Stage all stages files that a search hides; the folder and selection discard
  dialogs count submodules as files.

## Clone a chosen branch or tag (upstream issue 209, 2026-10-19)

- **Request:** "I want to clone specific branches only through the UI, instead of cloning the whole repo." The Clone
  dialog had a URL, a directory, a folder and "Clone submodules", and always cloned every branch.
- **The dialog (decided 2026-10-19, layout A of three):** "Branch or tag (optional)" below Folder, with "Default branch
  of the repository" as its hint, and a box "Only this branch" under it. The box needs a branch (it is dimmed and does
  nothing without one), is off by default, and when it is on a line says what it does: "Fetches only this branch. A
  later fetch brings only this branch too." Tab goes Folder, Branch, Clone (it went from Folder back to Directory).
  - The other layouts: B put Folder and Branch on one row and the two boxes on another (80 dp shorter, but Folder
    shrinks to half the width); C added a "Choose…" button that lists the remote's branches, with the rule that nothing
    asks the server until the user clicks the button, and typing never does. C is built (see the entry above).
  - Tags are accepted (`git clone --branch v1.0` does too): the clone ends with a detached HEAD.
- **git** gets `--branch=<name>` and `--single-branch` (`cloneArguments`). The `=` form makes a name that starts with
  `-` a value. What git 2.54 does with `--no-checkout`, so the steps after it needed no change: HEAD is
  `refs/heads/<branch>`, so JGit's checkout, `git lfs fetch <remote> <commit>` and the `.gitmodules` check all use the
  branch. A tag leaves HEAD detached at the tag's commit (annotated tags too). `--single-branch` sets
  `remote.origin.fetch` to the branch alone (`+refs/heads/x:refs/remotes/origin/x`, for a tag `+refs/tags/v1:refs/tags/v1`
  and no branch), so a later fetch in Leaf brings only that. Only the superproject is limited: submodules are cloned
  whole.
- **A branch that the remote lacks:** git exits with 128 and `fatal: Remote branch X not found in upstream origin`, and
  has made no folder. It is `RemoteOperationError.BranchNotFound` now (`branchNotFound` in `RemoteErrors.kt`, which
  JGit's `Remote branch 'X' not found in upstream origin` matches too), and the dialog says "The repository has no
  branch or tag named "X"." with git's line under it. Before, it would have read "Git failed with exit code 128", and
  "upstream origin" in a clone dialog means nothing. A chosen empty folder stays empty, as for any failed clone.
- **JGit, the fallback** (`setBranch`, `setBranchesToClone`): found by probing JGit 7.7 before writing it.
  - `setBranch(name)` takes a short name and tries a branch, then a tag.
  - `setCloneAllBranches(false)` alone still clones every branch. For one branch it needs `setBranchesToClone` with a
    full ref, and a list holding both `refs/heads/x` and `refs/tags/x` fails when either is missing.
  - So with "Only this branch", one `Git.lsRemoteRepository()` call inside the clone's own transport block (the same
    credentials, no second question) picks the branch, else the tag, else the remote's HEAD target for a clone without
    a branch. A name that is neither fails as JGit's own clone would.
- **Tests (28 new):**
  - `GitCliCloneTest` (15): a branch (HEAD, tracking, every branch fetched), trimming and a blank branch, annotated and
    lightweight tags, only this branch (and that a later fetch leaves a new branch out), only a tag, the default branch
    alone, a missing branch (readable, no folder, with and without single-branch), an empty chosen folder left empty, a
    name that starts with `-`, access denied still being access denied, the submodules of the chosen branch (and that
    single-branch leaves them whole), the arguments, and the clone that the app runs through `SelectingClone…` with git
    and with JGit.
  - `CloneRepositoryGitActionBranchTest` (7): the same cases for JGit.
  - `GitCliLfsTest` (1): git-lfs fetches the objects of the chosen branch's commit.
  - `CloneDialogTest` (5, rendered offscreen): the field and the box, what is sent, that the box waits for a branch
    and goes when the branch is cleared, and the error in the dialog.
  - Mutation checks: 16, all caught. Without `--branch`, without `--single-branch`, an untrimmed or a blank branch, the
    selector dropping the branch (git) or the single-branch switch (JGit), the error not recognized, JGit without
    `setBranchesToClone`, without its error mapping, without the tag lookup, without `setBranch`, without the default
    branch, and in the dialog the box sending the raw check, never turning on, enabled without a branch, or the branch
    not sent.
- **Manual run** (the real dialog, rendered offscreen from the real Dagger graph with the real git, in a temporary
  harness; no window was clicked), against `/tmp/leaf-clone-demo/source`, which has the branches `feature/clone-branch`,
  `release/1.0` and `docs`, the tag `v0.9` and the annotated tag `v1.0`:
  - `feature/clone-branch`: HEAD on it, every branch fetched, `feature.txt` there, clean status;
  - `release/1.0` with "Only this branch": the only remote ref is `origin/release/1.0`, and the refspec names it;
  - `v1.0`: detached HEAD at the tag's commit;
  - `nope`: the dialog says `The repository has no branch or tag named "nope".`, then git's line, and no folder is left;
  - no branch: `main`, as before.
  - A first demo had a submodule on the feature branch, and its clone stopped with git's `transport 'file' not allowed`
    (git 2.38.1 and later refuse a submodule from a local path unless `protocol.file.allow=always`, as in a terminal;
    the dialog then keeps the repository and says so). The submodule is out of the demo, and the tests cover it.
- **Found while building:** `ImageComposeScene.render()` has a fixed clock by default, so a screenshot of an
  `AnimatedVisibility` or a check box kept the old pixels while the semantics already showed the new state (now in
  CLAUDE.md, Offscreen UI checks).

## Terminal button on macOS opens one instance (upstream issue 208, 2026-10-19)

- **Bug:** every click on Terminal started another Terminal.app process (iTerm2 warned about a second instance). The
  provider ran `open -a <app> -n --args <repo>`: `-n` starts a new instance even when one runs, and `--args` hands the
  path to the app as an argument rather than as a folder to open.
- **Reproduced** on Terminal.app, which wasn't running: two clicks gave two processes, each with two windows (one at the
  repo, one at `~`).
- **Fix** (`MacTerminalProvider`): `open -a <app> <repo>`, the folder as a document, as when it is dropped on the app.
  Checked by hand: Terminal and iTerm2 open it in their running instance, one window at the repo per click, also for a
  path with spaces and non-ASCII characters (`space ünï repo 日本`). The path is one element of the argument list, and
  a JVM started without `LANG` still passes it as UTF-8.
- **Which apps:** the built-in Terminal, and a custom path (Settings → Terminal) whose app is `Terminal`, `iTerm` or
  `iTerm2`, as a name or a path, with or without `.app`. **Any other custom path keeps the old command**, `-n --args
  <repo>`: Ghostty, kitty, Alacritty and WezTerm weren't installed, so none was checked. They still start a new
  instance per click. Giving every custom path the folder form was offered and declined (2026-10-19): an emulator that
  needs its arguments could stop working.
- **Not changed:** when Terminal isn't running, it opens its own startup window (Terminal's "On startup" preference) next
  to the repo's. The old command did the same.
- **Tests:** `MacTerminalProviderTest` has a fake `IShellManager` that records the command: the built-in command, a
  path with spaces and unicode, custom paths for both apps (name, `.app`, full path, trailing slash), and the
  unchanged command for others. Three mutations (always the old command, always the new, `iterm2` left out) each failed
  the tests they should.


## Renames in the commit and review file lists (upstream issue 347, list part, 2026-10-19)

- **Bug:** a commit that git shows as `R old -> new` listed a deletion and an addition, in the commit's file list and
  in a worktree review's. `git.diff()` has no switch for renames and finds them only when `diff.renames` is set, as JGit
  reads the unset key as off, where git's default is on. Reviewing an agent's branch is Leaf's core flow, and a move
  read as two unrelated changes. The Staged and Unstaged lists are not part of this (they need a rename status type, and
  every stage, unstage, discard and diff action keyed by path to handle it).
- **Fix:** `Repository.diffWithRenames` (`data/git/diff/RenameDetection.kt`), which `GetCommitDiffEntriesGitAction` and
  `GetBranchComparisonGitAction` call. Stages, each on what the one before left, at git's score of 50 (JGit's is 60):
  exact renames (same blob, no read), files of the same name (how a refactor that moves files and edits their `package`
  line goes), then the rest up to `diff.renameLimit`. `diff.renames=false` turns it off.
- **Why not JGit's limit (probe, JGit 7.7):** its content stage re-reads the added file's blob for every deleted file
  it compares, so it costs the square of the files per side. Moved and edited files on loose objects, which an agent's
  fresh commits are: 150 files 2.7 s, 400 files 27 s, 400 files of 100 KB 120 s (packed: about 20 times faster). So
  Leaf's default is 100 files per side, an explicit `diff.renameLimit` is honoured, and stages 2 and 3 have a 2 s budget
  and stop when the coroutine is cancelled, keeping what the earlier stages paired. The brief had said to keep 400.
- **Partial clones:** a list downloads nothing. Exact renames read no blob; the stages that compare content only look
  at blobs the clone has. A commit made in the clone itself pairs its edited moves too.
- **Found by the tests:** `RenameDetector` changes the entries that it pairs (a deleted file's type became `RENAME` in
  the list), so the unchanged entries are taken before it runs; JGit reports the second of two identical added files as
  a `COPY`, where git shows an addition; and `DiffFormatter` returns an empty list when its progress monitor cancels,
  which would have blanked the list (the reason for using `RenameDetector` directly).
- **The rows (decided 2026-10-19, option B of three):** a renamed row shows `← label` right after its name, in split
  columns at the start of the directory column, with "Renamed from <old path> (92% similar)" as its tooltip. The label
  is only the part of the old path that differs, as git's `{old => new}`: `Lexer.kt`, `…/legacy/`, `…/parse/Lex.kt`.
  The rename icon is an arrow (`Refresh` read as "reload"). A search matches the old path too.
  - The first layout put the hint after the directory. The user pointed out that directory names are long and the pane
    is narrow, so it was cut off in most rows (rendered with Kotlin-style paths at 380 px). Now the directory is what
    gives way: the hint comes before it, the name takes at most 55% of the row and the hint 80% of the rest, and the
    label drops the folders that both paths share, which had made it as long as the directory.
- **The diff pane:** the header adds `← old path · 92% similar` (the path is cut first when narrow). A rename with no
  change has no hunks and left the pane blank; it says "Renamed from X. The content is unchanged." now. A rename with
  edits shows the diff from the old file. File history finds the commit that moved a file under its old name too (it
  closed the diff there: it was a deletion before).
- **Tests:** 37 new, each run on temp repositories where git is involved.
  - `RenameDetectionTest` (18): a move that changes nothing (the same blob, score 100), one with edits (score under 100),
    a rename in its folder, files half alike (git's score of 50, where JGit's 60 misses them, compared with `git diff
    -M`), a file copied from (a rename and an addition, as git shows it), `diff.renames` false, true and `copies`, a
    refactor of 150 files found by name over the limit, 101 renamed and edited files over the default limit (with and
    without `diff.renames` set) and found with `diff.renameLimit`, 1,500 exact renames, no time, out of time half way,
    a cancelled call, the first commit, and three on a `--filter=blob:none` clone: nothing downloaded for a list, the
    files whose blobs it has paired in the same stage as ones it lacks, and a commit made in the clone.
  - `GetBranchComparisonGitActionTest` (a review of moved files, the diff of a moved file with edits, and that an
    unchanged rename has no hunks, with the full file shown or not), `RenamedFromTest` (7: the label rules),
    `CommitChangesRenamesTest` (2), `ChangedFilesListRenamesTest` (4: every view, the order, none without renames),
    `RenamedDiffTest` (4) and `HistoryViewModelTest` (the old path's history at the commit that moved the file).
  - The whole suite (`./gradlew test`, 2,076 tests, none failing, none skipped) ran on `3c22b181` with this change,
    before the last two tests were added (the mixed partial clone one and the order one); those and the classes above
    ran after, 50 tests, all passing.
  - 22 mutations are each caught by a named test: JGit's own detection left on, its limit of 400 and its score of 60,
    copies kept, the unchanged entries read after the detector, every blob counted as readable, the same-name stage
    skipped, no `ensureActive`, `diff.renames=false` and `diff.renameLimit` ignored, a time budget that never ends, the
    exact stage running the content stage, the history ignoring the old path, the search ignoring it, file items without
    `renamedFrom`, the label keeping its shared folders or always showing the whole path, the empty state for any empty
    diff, the similarity always shown, and no hint in the folder tree, in split columns, or ahead of the directory.
    Three needed work: the first form of the `readableBlobs` mutation survived, because the `MissingObjectException`
    safety net gave the same result when all of a commit's blobs were missing (a test with a readable pair and a
    missing one in the same stage catches it); the hint's place was only caught because the mutation dropped it (an
    order test does now); and one mutation didn't compile (it removed a variable that was still used), so it was
    written again.
- **Run by hand:** the offscreen render of the real graph, as `CLAUDE.md` describes it (a throwaway test through
  `DaggerAppComponent`, deleted, with the dev prefs key that it writes put back as it was): `/tmp/leaf-renames-demo`
  has a commit with every kind of move. Its list read 128 entries, 125 of them renames: the 120 files moved with their
  `package` line edited (found by name), an exact move (100), a move with an edited line (97), a rename in a folder with
  a few edits (89), a move up to the top folder, and one in a long path; and one addition, one deletion and one change.
  The rows were rendered at 380 and 520 px in all four views, with Kotlin-style paths. The diff pane showed the edited
  one's single changed line with `← src/ui/old/Card.kt` in the header, and the unchanged one's "Renamed from … The
  content is unchanged." under its header. A window wasn't driven: selecting a commit takes a click, and clicks go to
  whatever is on top. `/tmp/leaf-renames-demo-agent` is a linked worktree on `agent-moves` for "Review changes".

## Merge, Commit and Continue with files that aren't staged, and why a button is disabled (upstream issues 120 and 124, 2026-10-19)

- **Bug:** during a merge, a cherry-pick or a rebase, any file in the Unstaged list disabled Merge, Commit and Continue,
  a stray untracked file or a build output as much as a change that matters (`StatusPane` passed
  `haveConflictsBeenSolved = !hasUnstagedFiles`). git lets `git commit` finish a merge and a cherry-pick with such files
  around, and with nothing staged at all (upstream issue 120's report: every conflict taken from our side, and a merge
  tool's `.orig` file left in the folder). Agents leave untracked files in their worktrees, so finishing a merge in Leaf
  was blocked. And nothing said why a button was disabled (#124).
- **Fix:** `StatusState.finishBlocker()` (`ui/status/FinishBlockers.kt`) is one small function that returns the first
  thing that stops the primary button, or null:
  - Merge: conflicts only.
  - A cherry-pick's or a revert's Commit: conflicts, a blank message, and nothing staged. Without the last, enabling the
    button with only untracked files around would have made an empty commit, which JGit refuses (an error dialog).
  - A rebase's Continue: conflicts, and unstaged changes to tracked files (modified or deleted), which `git rebase
    --continue` refuses in both backends. Untracked files don't count.
  - Commit and Amend outside an operation: as before (a message, staged files, no conflicts), now with their reasons.
- **Continue or Skip:** a rebase with only untracked files left, or nothing, now offers Skip. Continue did nothing there
  with JGit's rebase (`NOTHING_TO_COMMIT`, reported as a plain continue), git's apply backend refuses it, and Skip loses
  no work in that state. A blocked Continue stays Continue, disabled, as Skip would discard the changes.
- **The hint (decided 2026-10-19, option B of three):** `FinishHint` is a line between the message field and the
  buttons: "Resolve the conflicts in 2 files first.", "Stage or discard the changes to 3 tracked files to continue.",
  "Write a commit message.", "Stage some changes to commit." (a tooltip on the button, and a hint only during an
  operation, were the other options). It names the first reason, and outside an operation it is silent while the field
  rests, with no message and nothing staged.
- **The key:** Ctrl or Cmd+Enter in the message field does what the primary button does in a merge, cherry-pick or
  revert. It needed staged files, whatever Merge allowed, and committed a merge that the button had refused.
- **JGit's rebase gets git's rule:** its continue goes on with tracked changes that aren't staged. The pane doesn't
  know which tool started the rebase, and one rule is easier to trust.
- **Not changed:** Merge isn't disabled by an empty message (as before); a changed submodule counts as a tracked change
  where git's check ignores submodules, so Leaf may block where git wouldn't; Skip's dialog still doesn't say that it
  discards changes, which it can't in the state where Skip is offered.
- **Tests:** `FinishBlockersTest` (the rules for every repository state, all combinations of files, message and amend
  against the rules written out, and that the reason given is true), `CommitFieldTest` (the field rendered from a
  `StatusState`: buttons, what a disabled one does when clicked, the hint and its place, the key),
  `RebaseWithUnstagedFilesTest` (what git's merge and apply backends and JGit do with each kind of unstaged file, for
  Continue and Skip) and `FinishingWithUnstagedFilesTest` (a tab commits a merge, a cherry-pick and a revert, and
  continues and skips both kinds of rebase, with files around that aren't staged, and JGit refuses the empty ones).
  The whole suite (`./gradlew test`, 2,041 tests, none failing) ran on `861e0768` with this change. 14 mutations are each caught by a named test: the rebase counting untracked files, Merge blocked by tracked changes,
  Continue shown for any unstaged file, the cherry-pick forgetting "nothing staged", conflicts always counted as one,
  Commit not needing staged files, the hint not shown or speaking while the field rests, Continue, Merge and the
  cherry-pick's Commit ignoring their blockers, the key keeping the old rule, a revert not waiting for the status, and
  JGit allowing an empty cherry-pick commit.
- **Run by hand** with `:app:run` on demo repositories under `/tmp/leaf-demo-finish` (the window captured, nothing
  clicked): a merge and a cherry-pick stopped at a conflict, and a rebase stopped with its conflict resolved and staged,
  each with an untracked `build.log` and a changed, unstaged `README.md` (the merge also with a `notes.txt.orig`). With
  the conflict, the hint says "Resolve the conflicts in 1 file first." and Merge and Commit are disabled. After
  resolving it with git in a terminal (the watcher refreshed the pane), Merge and Commit are enabled with those files
  still in Unstaged. The rebase with README unstaged: Continue disabled, "Stage or discard the changes to 1 tracked file
  to continue."; staged, Continue enabled with `build.log` left. Clicking the buttons, and Skip (only untracked files
  left), were checked by the tests and an offscreen render only.

## Ignore from the Unstaged menus (upstream issue 175, 2026-10-19)

- **What:** "Ignore…" in the Unstaged menus of an untracked file (after "Delete file"), of a selection (`Ignore N
  files…`, counting its untracked files) and of a folder row of the tree view (`Ignore folder…`). It opens
  `IgnoreDialog`: what to ignore (the file, its folder, its extension, or a custom pattern typed in a field that starts
  from the selected file's rule), where to write it (`.gitignore` at the root of the tab's working tree, `info/exclude`
  in the common git dir, or the global ignore list), and the lines it adds, with those the file has already left out.
  The place used last is kept per repository (`ignore.place` in `<common git dir>/leaf`), except the global list, which
  affects every repository and isn't remembered.
  - Only untracked files and folders that show one: ignoring a tracked file changes nothing until `git rm --cached`.
    The Staged pane has none.
  - A selection offers its files, each folder once (a folder inside another one is covered by it) and each extension
    once. A file name with a line break can't be written to a pattern file, so it is left out, and the dialog says so.
  - The custom pattern is written as typed, one rule per line, comments and negations included. Cmd+Enter adds.
- **How:** `IgnoreRules.kt` (domain) builds the rules, `AddIgnoreRulesGitAction` appends them (`IgnoreFiles.kt`),
  `AddIgnoreRulesUseCase` runs it as a task that refreshes the status (`TaskType.AddIgnoreRules`), and
  `GetIgnoreContextGitAction` reads both files' lines and the place used last when the dialog opens.
  - A file is `/path/to/file`, a folder `/path/to/folder/`, an extension `*.ext`. Backslashes go before `\ * ? [` and
    before the white space that ends a name.
  - **JGit differs from git here, found by the tests:** git drops only trailing spaces from a pattern line, JGit all
    trailing white space, and JGit also drops it from a folder's name before the folder rule's slash. A file named
    with a trailing tab was ignored by git and still listed by Leaf, and so was a folder named with a trailing space.
    So white space that ends a name is always escaped. The tests run every rule through `git ls-files --others
    --exclude-standard` and through Leaf's own status, which must list the same files.
  - The rules are appended as bytes: a file's encoding, byte order mark and line endings stay. New lines get the file's
    first line ending (LF for a new or one-line file), a last line without one gets it first, and a rule that the file
    has already, byte for byte without its `\r`, isn't added again.
  - Nothing is written through a symbolic link, which git 2.32 and later ignores as `.gitignore` anyway, nor to a
    folder: `IgnoreError.FileNotWritable`, shown in the dialog and in `ErrorDialog`. Only `<working tree>/.gitignore`,
    `<common git dir>/info/exclude` and the global list are ever written.
  - **The global list** (`GlobalIgnoreFile`, SourceTree's "Global ignore list"): the file that `core.excludesFile` names
    in the user's own git config (`git config --global --type=path`, so `~` and `includeIf` work), else git's default,
    `$XDG_CONFIG_HOME/git/ignore` or `~/.config/git/ignore` with the login shell's `HOME` and `XDG_CONFIG_HOME`. Only
    the user's config counts: a repository's own `core.excludesFile` could point Leaf at any file, so when it differs
    the place is refused (`IgnoreError.GlobalListOverridden`, as git doesn't read the user's list there either). A link
    to a file, as dotfile managers make, is written through, as it is the user's own file; a link to nothing, a folder
    and a relative path are refused. JGit reads git's default file too (the tests check).
- **Not done:**
  - The file watcher kept watching a folder that was ignored until the tab reopened. Done later the same day (see
    "File watcher follows the folders that get ignored").
- **Tests:** `IgnoreRulesTest` (domain: the rules, the options and which menus offer them),
  `AddIgnoreRulesGitActionTest` (temp repositories against git and JGit: each kind of rule, 120 random names, names with
  special characters and white space, a missing, unterminated, CRLF, byte-order-marked and non-UTF-8 `.gitignore`,
  symbolic links, `info/exclude`, a linked worktree, the place used last), `AddGlobalIgnoreRulesGitActionTest` (the
  global list: the configured file, `~`, git's default, a link, the refusals, a repository that sets it itself),
  `IgnoringFilesTest` (a tab: the task refreshes
  the status and records its outcome), `IgnoreContextMenuTest` and `IgnoreDialogTest` (the three menus, and the dialog
  from its state). Mutations of the rules and the writer (8), the dialog and the menus (6), what the menus offer (3) and
  the global list (6) are each caught by a named test.
- **Run by hand** on a demo repository with `:app:run` (before the global list was added): the menu, the dialog, a folder rule on an untracked `output/`
  folder in a repository whose `.gitignore` has no final newline (the rule went on its own line, the files left
  Unstaged, a toast said so). The selection and folder-row menus and the symbolic `.gitignore` error were checked by the
  tests only.

## File watcher follows the folders that get ignored (2026-10-19)

- **Bug:** the follow-up of Ignore…. The watcher read the ignored folders once, when it started. A folder that became
  ignored afterwards, by Ignore… or by an edit of `.gitignore`, `info/exclude` or the global list, stayed watched until
  the tab was opened again, and since the folders without an ignored folder under them are one recursive registration
  (below), the whole of that registration kept reporting it, so a build that wrote into the folder refreshed the tab's
  status, log and worktree list over and over. A folder that a build made inside a clean one (`target/`) did the same,
  and a folder whose rule was removed was never watched.
- **Fix:** `WorkingTreeWatches` keeps what was registered for the working tree. Each status that a tab loads goes to it,
  and when the ignored folders in it aren't those the registrations were planned for, it plans the working tree again:
  what the plan lacks is removed (before anything is added, so nothing is delivered twice), then the plan is added.
  Ignored files don't change it. A folder that stops being ignored is watched again, as one registration where it can.
- **Tests:** `WorkingTreeWatchesTest`, the real watcher with and without `FILE_TREE` in `FileChangesWatcherTest`, and in
  `RefreshingOnChangesTest` Leaf's Ignore… and a build folder, each failing without the fix.

## File watcher: one registration per clean folder (2026-10-19)

- **Bug:** a file created in a large repository's working tree, such as a migration under
  `application/src/main/resources/db/migration`, didn't show until Cmd+R. The packaged Leaf's watcher was alive (thread
  dump), but its registrations past about 250 delivered nothing: macOS gives the JDK's `MacOSXWatchService` one
  FSEvents stream per registered folder, the limit is shared by every tab and process, and nothing reports a stream
  that failed. A probe on the repository (474 non-ignored folders) saw events for the first 238 and none for the
  rest; a fresh process on 700 empty folders stopped at about 265.
- **Fix:** `workingTreeWatchPlan` registers a folder with nothing left out under it (ignored folders, `.git`, linked
  worktrees) once, recursively. The recollection repository needs about a dozen registrations instead of 474.
  `ObserveRepositoryToRefreshUseCase` uses it for the whole tree and for each folder created later. Links aren't
  followed.
- **Notice:** `WatchRegistrations` counts the watchers' folders across tabs. On macOS, from 200 of the ~250 limit the
  bottom bar shows an amber "File watching near its limit" chip in every tab, with the count and what to do in its
  tooltip (`WatchLimitNotice`). A count, not a detection: macOS gives no error. No fallback to timed refreshes yet.
- **Tests:** `WorkingTreeWatchPlanTest`, `WatchRegistrationsTest`, `WatchLimitNoticeTest`, the count in
  `FileChangesWatcherTest`, and in `RefreshingOnChangesTest` an edit in 12 of 300 folders with the app's
  watcher, which fails on the old code (15 s timeout) and passes now.

## Upstream sync: stash highlighting, shared log chips, quick actions (branch `sync/upstream-2026-10-16`)

- **Merged:** `upstream/main` up to `1cac7a00`, the second sync (the first was `f4f39528`, merge base `415b3729`). Six
  commits:
  - `00b25acf` bumped upstream's version to 2.0-beta04. Leaf keeps its own version (1.1.1, code 28).
  - `b2f9188f`: a stash selected in the log is highlighted in the side panel (`LogAction.CommitSelected` carries
    `isStash`), and `SelectedItem.CommitItem` says whether the log scrolls to it (`scrollToItem`). The log stays
    composed under the diff pane, so closing a diff doesn't compose it again. `@Immutable` on `SelectedItem` and the
    side panel's states.
  - `4bc8b17d`: a local branch's log chip joins the remote branch it tracks on the same commit. With it come
    `Branch.trackingBranch`, which `refreshBranches` reads for local branches, branch menu callbacks that get the
    branch, a cloud icon for remote branch chips, an animated current-branch marker, and a branch refresh after a push.
  - `f5cafbb7`: upstream's `bundleID = packageName` inside `macOS {}` read that block's own `packageName`. Leaf's bundle
    ID is written out, so only the rename of the top-level val to `appPackageName` came over. It sets `group`, which is
    unchanged.
  - `2040386b`: `loadPersistedTabs` skips `null`s. Leaf never saves one (`pathToPersist`, first sync), and a bad entry
    can't stop Leaf from starting now either.
  - `1cac7a00`: the Actions list's Clone and Signoff, which Leaf had fixed its own way (`df7bb3ee`).
- **Conflicts:** imports and packages from the rename. `AppViewModel.kt`, `SidePanel.kt` and `SidePaneStates.kt`, which
  the fork rewrote, came up as modify/delete and were ported by hand. Content conflicts in `Log.kt` (the chips),
  `BranchContextMenu.kt` (Leaf's worktree items), `RefreshDataUseCase` (Leaf's runners), `RepositoryOpen.kt` and the
  version files.
- **Kept Leaf's:**
  - Shared chips: `LogRefChips` (a remote branch of the same name, from any remote, with its items in the chip's menu)
    over upstream's loop in `CommitMessage` and its gold cloud with a menu of its own. `BranchChip` takes
    `sharedRemotes` in place of upstream's `trackingRemoteBranch`.
  - Quick actions: git merged upstream's `QuickActionsDialog` change into Leaf's without a conflict, which gave "Open
    repository in file manager" and "Refresh repository data" a second `onDismiss()`, and upstream's `AppTab` callbacks
    remove the list before adding their dialog. Either would have removed the screen under the list, the tab's
    repository. Both are left out. `Screen.Clone`, unused, is gone.
- **Changed from upstream** (previewed offscreen, and chosen by the user):
  - A remote branch's chip has the cloud icon on its lane's colour, as the rest of the chip. Upstream's is on a fixed
    gold (`0xFFbd9b46`), which isn't a theme colour.
  - `Branch.trackingBranch` defaults to `null`, as Leaf's code and tests build branches without it. It's part of a
    branch's equality, and Leaf compares branches from different sources by name.
- **Fixed on top:**
  - The log under the diff: Compose hit-tests the sibling below where nothing in the top one takes the pointer, and
    the diff's header has only a background there. A click on its empty parts would have selected a log row under it.
    `Modifier.coveringWhatIsBelow` (fork-only) wraps the diff pane.
  - `scrollToCommit` skips the scroll when the commit's line is on screen, but upstream looked for the commit's message
    in the lines' keys, which hold its hash. It never matched, and always matched a commit with an empty message,
    which then never scrolled. It looks for the hash.
  - Upstream's `InstantTooltip(AnnotatedString)` overload passes Leaf's `leadingContent` and `maxLines` on, which the
    `String` one now forwards to it.
- **Changed:** a detached worktree's row scrolls the log to its commit, as a branch's row does. Selecting a branch, tag
  or stash whose line is already on screen no longer scrolls the log.
- **Tests:** `CoveringWhatIsBelowTest` (3): without the modifier, a click and hover on an empty spot of a view drawn
  over another reach the one below; with it, neither does, and what's inside still gets its clicks.
  `TrackingBranchesTest` (2, in a `TestTab`): local branches carry the branch they track, and a branch refresh reads an
  upstream set since. Full build on the merge, before the lane colour: 1,870 tests (app 320, common 7, data 1,195,
  domain 348), all passing; after it, the log, context menu and component tests again.
- **Manual run:** the user's dev run on `/tmp/leaf-sync-demo-setup.sh`'s repository (two remotes, stashes, a branch
  worktree and a detached one, an uncommitted change), with the Actions list. The remote chips' colours and the
  marker's animation were rendered offscreen from the real chip code before and after.

## The commit field during a cherry-pick, a revert and a rebase (branch `fix/commit-field-during-operations`)

- **Before:** the field showed `MERGE_MSG` only while merging (upstream's `isMerging`), and the draft during a
  cherry-pick, a revert or a rebase, while `PersistCommitMessageGitAction` saved its edits nowhere during a cherry-pick
  and in `MERGE_MSG` during a revert or a rebase. So a restart or a worktree switch in the middle of one showed the
  draft as it was, and the edits were gone. A cherry-pick's or a revert's commit took the draft and removed it, and
  git's own message (the picked commit's, or `Revert "…"`) was never offered. At a rebase's edit stop, a draft typed
  for another commit stayed in the field, Amend (which fills only an empty field) left it there, and Continue amended
  the commit with it. A cherry-pick could be committed with an empty message, which JGit allows and git refuses. And a
  merge committed in Leaf after conflicts kept git's `# Conflicts:` comment lines in its message: `git commit` strips
  them, JGit's commit keeps a message as it is. (Found, not changed, in `fix/commit-clears-own-message`'s entry; all
  upstream's behaviour, the last one with JGit's merge too.)
- **Now** (options picked with the user on 2026-10-16: git's message, a file of the stop's own with the commit's
  message to start from, and stripping):
  - One rule, `commitMessageSource` (moved from the app to `domain/models/CommitMessageSource.kt`), says what the field
    shows and where `PersistCommitMessageGitAction` saves its edits, so the two can't disagree again: `MERGE_MSG` while
    merging, cherry-picking or reverting; while rebasing, the message for the commit that the rebase stopped at to
    edit; `SQUASH_MSG` while a squash merge waits; the draft otherwise, also while bisecting, which saved nothing.
  - A cherry-pick or a revert shows git's message, as `git commit` takes it then, and keeps the edits there. Its commit
    counts as a merge message (`CommitMessageSource.isMergeMessage`, which `StatusViewModelExtender.commit` passes in
    place of `source != NORMAL`), so it leaves the draft, which comes back after the commit or Abort. Its Commit button
    is disabled with an empty message, as the revert's was.
  - A rebase's edit stop has a message of its own, in `rebase-merge/leaf-amend-message-<commit>`
    (`RebaseEditMessage.kt`), for the commit in `rebase-merge/amend`, which git and JGit write at an `edit` stop. The
    field shows the commit's own message (HEAD's) until one is saved, as `git commit --amend` starts from it: Amend is
    ticked at such a stop (upstream's default), and only fills an empty field when ticked again, so an empty field read
    Skip until something was typed. Continue amends with it even unchanged, a commit that the rebase rewrites anyway.
    Continue's amend removes the file
    (`isMergeMessage = false`, as before: it removed `MERGE_MSG`). Each stop has its own, so a message that a Continue
    from a terminal left behind isn't the next stop's, and the view model replaces the field's text when the stop
    changes (`persistedCommitMessageFlow`'s new `key`). The file goes with the rebase's folder. At the other stops of
    a rebase, the field can't be edited, as before, and nothing is saved.
    - Not `MERGE_MSG`: JGit's interactive rebase leaves a conflicted commit's `MERGE_MSG` through the later stops and
      after the rebase ends (checked with JGit 7.7), so a later edit stop would have shown, and amended with, another
      commit's message. git's rebase removes it.
    - The file watcher ignores the file in any worktree's git dir: in a linked worktree, the recursive watch of the
      worktrees' git dirs saw each save, and refreshed everything.
  - `GetPersistedCommitMessagesGitAction` leaves out the conflicts that git and JGit list as comments in `MERGE_MSG`
    (`withoutConflictsHint`, `ConflictsHint.kt`), and git's scissors line with what follows (`commit.cleanup=scissors`),
    so the field shows what its commit will have, for merges too. git adds them at the end, JGit before the message's
    footers, in any `core.commentChar`. The user's own lines stay, even ones that start with `#`, which `git commit`
    would strip.
- **Found, not changed:**
  - JGit's interactive rebase leaves `MERGE_MSG` after a conflicted step, also once the rebase is over. Leaf's field
    doesn't show it any more, but a `git commit` in a terminal takes a leftover `MERGE_MSG` as its message (checked
    with git 2.54) until Leaf's next commit removes it (`CleanUpAfterCommitGitAction`).
  - Emptying the field during a merge, a cherry-pick or a revert removes `MERGE_MSG`, as `PersistCommitMessageUseCase`
    saves a blank message as none (upstream's behaviour during a merge).
- **Tests** (on `0c330b21`):
  - `CommitMessageSourceTest`, moved to `:domain` (+3): `MERGE` during a cherry-pick or a revert, `REBASE` during every
    rebase state, the draft while bisecting or applying, and which messages the commit removes itself.
  - `DraftingCommitMessagesTest` (+3, and two rewritten, in a `TestTab`): a cherry-pick and a revert that stopped at
    conflicts show git's message without its comments, keep the edits in `MERGE_MSG`, and their commits
    (`isMergeMessage = true`) commit the edits and leave the draft. A merge's message is committed without the
    comments. Leaf's interactive rebase (JGit's), with a conflicted commit, then three to edit: the conflicted commit's
    `MERGE_MSG` stays, each stop starts from its commit's message, the edit stop's message goes to its own file,
    Continue with Amend commits it and removes it, a message left by a Continue elsewhere isn't the next stop's, and the
    draft is there all along. At a conflict stop, nothing is saved.
  - `ConflictsHintTest` (5): `withoutConflictsHint` against `git commit`'s message after a merge, a cherry-pick and a
    revert stopped at conflicts, with the default settings, `core.commentChar=;` and `commit.cleanup=scissors`;
    JGit's `MergeMessageFormatter` with footers; a squash merge's empty message; the user's lines that look like
    comments.
  - `CommitDraftTest` (+1): the draft is saved while bisecting. `WatchedRepositoryTest` (+1): the stop's file needs no
    refresh in any worktree's git dir.
  - **Mutation check:** 13 mutations, all caught, each by a failing test: cherry-pick and revert showing the draft, a
    rebase showing the draft, the stop's message counted as a merge message, a rebase saving in `MERGE_MSG` (the old
    behaviour), no stripping, no scissors, a block without the blank line before it, a word as the comment string, one
    file for every stop, a file written at a stop without `amend`, the watcher's rule gone, no commit's message to
    start from, and the draft saved only in a SAFE repository (the old behaviour). A file written at a stop without
    `amend` and the draft survived a first run, and got their tests.
    Not covered by a test: the view model (the field's text replaced when the stop changes, `isMergeMessage` passed
    from the source) and the cherry-pick's Commit button. The manual run checked the first, not the button with an
    empty message.
  - The full suite passes (`./gradlew build`): `:app` 317 tests (3 moved to `:domain`), `:domain` 348, `:data` 1193,
    `:common` 7.
  - **Manual run** (dev run on macOS, 2026-10-16, `/tmp/leaf-commit-field-demo-setup.sh`), with a draft in the field
    and git run in a terminal, as an agent would: a cherry-pick that stopped at conflicts showed the picked commit's
    message without `# Conflicts:`, with Commit disabled until the conflict was resolved, and `git cherry-pick --abort`
    brought the draft back. A revert showed `Revert "…"`. At the edit stops of a `git rebase -i`, the field could be
    edited, showed a message saved for that stop, and was replaced at the next stop after a `git rebase --continue`
    from the terminal; with the commit's message to start from (run again after adding it), each stop showed its
    commit's message, with Continue. The draft came back once the rebase ended. Typing in the field and Leaf's own
    buttons weren't driven: the tests cover the saving, the commit and the amend.

## A commit removes only the message it committed (branch `fix/commit-clears-own-message`)

- **Before:** after every commit from the commit field, `DoCommitUseCase` removed "the field's saved message", which
  `PersistCommitMessageGitAction` picks from the state *after* the commit. A merge's commit and a squash merge's
  (whose state is SAFE throughout, as git records none) both leave the repository SAFE, where that's the plain draft.
  So committing a merge, or a squash merge from "Squash-merge into main…", deleted the draft in `LEAF_COMMIT_DRAFT`
  that the merge had kept out of view. The field still showed it, but a restart or a worktree switch lost it unless it
  was edited first (found, not changed, in `fix/commit-draft-file`'s entry; upstream's behaviour with
  `COMMIT_EDITMSG`).
- **Now:** the caller says which message it committed. `DoCommitUseCase.invoke` and `commit` take `isMergeMessage`,
  and remove the saved message only when it's false. `StatusViewModelExtender.commit` passes
  `source != CommitMessageSource.NORMAL`, read before the commit: the commit itself removes `MERGE_MSG` and
  `SQUASH_MSG` (JGit's commit and `CleanUpAfterCommitGitAction`). `ContinueRebaseUseCase`'s amend passes false, so it
  still removes `MERGE_MSG`, where the field's edits go during a rebase.
  - Reading the state before the commit was the other way. It would have missed the squash merge, and kept the draft
    after a cherry-pick's or a revert's commit, which commit the draft: the field shows `MERGE_MSG` only while merging
    (upstream's `isMerging`), so it shows the draft during them.
- **Found, not changed:** during a cherry-pick, the field's edits aren't saved (`PersistCommitMessageGitAction` writes
  nothing in `CHERRY_PICKING`), and during a revert or a rebase they go to `MERGE_MSG`, which the field doesn't read.
  So a restart in the middle of one shows the draft as it was before. The field doesn't offer git's own message
  there either ("Revert …", or the picked commit's). Upstream's behaviour.
- **Tests** (on `ff6e2b84`, and again after rebasing onto `54c19696`):
  - `DraftingCommitMessagesTest` (+4, in a `TestTab`): a merge's commit (`--no-ff --no-commit`) and a squash merge's
    leave the draft, as a tab opened afterwards reads it, and their message files go. A cherry-pick's and a revert's
    commit, after conflicts, take the draft.
  - `ContinueRebaseAmendTest`: the amend removes the saved message between amending and continuing.
  - `CommitMessageSourceTest`: the field shows the draft during a cherry-pick or a revert.
  - `MergingWorktreesTest` and `MergingWithAutostashTest` commit their merges with `isMergeMessage = true`.
  - **Mutation check:** 6 mutations, all caught, each by a failing test: removing the saved message always (the old
    behaviour, which fails the merge and squash merge tests), never, or the other way round; `invoke` not passing the
    flag on; the rebase's amend keeping `MERGE_MSG`; the field showing `MERGE_MSG` during a cherry-pick or a revert.
    Not covered by a test: the view model's `source != CommitMessageSource.NORMAL`, which the manual run checked.
  - **Manual run** (dev run, 2026-10-16, `/tmp/leaf-draft-demo-setup.sh`): a draft in main's field, then "Squash-merge
    into main…" and "Merge into main…" from two worktree rows, each committed from the field, then a switch to a
    worktree and back. The draft stayed in `LEAF_COMMIT_DRAFT`, and `MERGE_MSG` and `SQUASH_MSG` were gone.

## Pull runs git pull (branch `fix/git-cli-pull`)

- **Before:** Pull fetched with git, then JGit merged or rebased what `FETCH_HEAD` marked
  (`GitCliPullBranchGitAction`), so a pull didn't get what Merge and Rebase got with item 4: git's merge engine, which
  follows renames, git's messages, and the hooks that only git runs (`pre-merge-commit`, `post-merge`,
  `post-rewrite`). JGit's rebase onto the fetched commit replayed the commits that a force-pushed upstream had replaced,
  which could stop at their conflicts. `merge.autoStash` and `rebase.autoStash` didn't apply, and refusals had Leaf's
  own message.
- **Now** (`GitPullGitAction`, `data/git/cli/remote/`): Pull runs `git pull --progress` with `--no-rebase --no-edit`
  or `--rebase`, through `GitCliOperations.run`, as in a terminal. The user decided on 2026-10-16
  (`docs/fork/remote-operations.md`, stage 6):
  - Merging or rebasing stays Leaf's setting and its menu: git's `pull.rebase` doesn't decide.
  - `pull.ff=only` refuses a merge, even one chosen from the menu: Leaf reads it with `git config` (so `includeIf`
    applies) and adds `--ff-only`, as an explicit `--no-rebase` merges otherwise.
  - `merge.autoStash` and `rebase.autoStash` apply. Changes that don't go back get `AutostashNoticeDialog`, as with
    Merge: `GitCliOperations.run` counts `git pull` as a merge or a rebase by its flag. A merge's `MERGE_AUTOSTASH`
    waits for Leaf's commit or Abort, which put it back.
  - Stopped at conflicts is exit code 1 with unmerged paths; any other failure is a `RemoteOperationError` with git's
    message, local changes in the way included.
  - The backup snapshot stays for merge pulls only, and is deleted after a pull that completed, or that failed
    without changing anything (`IsUnchangedSinceGitAction`, as Merge does).
  - With an upstream, git gets no remote and no branch, and pulls it; otherwise Leaf names the chosen remote branch,
    or the branch of the same name on `origin` (`pullSource`, moved to `PullSource.kt`). Leaf's own checks for a
    detached HEAD and an operation in progress run first.
  - A repository that uses LFS, where git-lfs doesn't work, keeps the old pull (`pullNeedsBuiltinLfs`): `git pull`
    would check out pointer files, or fail with `filter.lfs.required`. LFS counts from the `lfs` folder, or
    `filter=lfs` in the root `.gitattributes` of the working tree or of what is pulled, as its remote-tracking branch
    has it. `SelectingPullBranchGitAction` chooses among the three pulls.
  - Not changed: the domain, the app, the DI modules, and upstream's files. `GIT_SEQUENCE_EDITOR`, which the plan
    added, isn't needed: `--rebase` overrides `pull.rebase=interactive`, the only way to an interactive rebase here.
- **Tests:**
  - `GitPullTest` (31): fast-forwards, `pull.ff` (`false`, `only` from an `includeIf` file, `only` with a
    fast-forward and with a rebase), git's merge messages on `main` and off it, rebasing, a force-pushed upstream
    (fork point), merge and rebase conflicts, local changes and an untracked file in the way, a rebase refused before
    fetching, `rebase.autoStash` and `merge.autoStash` (put back, kept with a notice, and `MERGE_AUTOSTASH` during
    conflicts), hooks (`post-merge`, a refusing `pre-merge-commit`, `post-rewrite`), a renamed file, a chosen remote
    branch, no upstream, a local upstream, a missing upstream branch, a fetch that fails, a detached HEAD, and a branch
    without commits.
  - `RemoteOperationsBackendTest` (+6), `GitCliLfsTest` (+1: git-lfs's filter checks the files out),
    `PartialCloneTest` (+1). `GitCliFetchPullTest` stays, for the old pull.
  - **Mutation check:** 13 mutations, all caught by named tests: `pull` not counted as a merge or a rebase for the
    notice, and the two swapped; no `--ff-only` from `pull.ff`; `--no-autostash` on merge pulls; the backup rule each
    way (kept after success, deleted after conflicts, kept after any failure, deleted after any failure); no remote
    and branch without an upstream; `GitCliError` in place of `RemoteOperationError`; and the LFS choice (never the
    old pull, git-lfs not checked, the pulled commit not read). Not checked: a cancelled pull keeping its backup.
  - The full build passes: `:app` 320 tests, `:domain` 341, `:data` 1173, `:common` 7.
  - Manual run: `/tmp/leaf-pull-demo-setup.sh` makes a demo repository (a renamed file, `merge.autoStash` with an
    uncommitted edit, hooks that log, and a force-pushed branch in a linked worktree), checked with plain git. Run in a
    window on 2026-10-16: a merge pull made git's merge commit ("Merge branch 'main' of /tmp/leaf-pull-demo/origin")
    and followed the rename, with `post-merge`; rebase pulls were refused with git's message while the edit was
    uncommitted, then rebased with `post-rewrite`. The force-pushed worktree wasn't pulled in a window.

## Stashes keep the message they're given (branch `fix/backup-stash-message`)

- **Before:** `SnapshotStashCreateCommand` (upstream's, copied from JGit's `StashCreateCommand`) ran its message through
  `java.text.MessageFormat`, as a pattern for the branch, the commit and its subject. Its callers give a finished
  sentence that names branches: Merge's backup (`Automatic snapshot stash before merge of "feature" to "main"`) and
  both pulls'. git allows `'` and `{` in a branch's name (`git check-ref-format --branch` takes `it's`, `a{b}`, `a{`
  and `x{1}`), and `MessageFormat` reads `'` as a quote and `{` as an argument. So, with uncommitted changes and
  "Automatically stash uncommitted changes before merge" on:
  - an `'` was dropped from the backup's message (`it's` gave `its`, seen in a test on 2026-10-16);
  - a `{` failed the backup with `IllegalArgumentException` (`can't parse argument number: b`, `Unmatched braces in the
    pattern.`), so Merge and pull failed before they started, with that text in `ErrorDialog`;
  - `{1}` became the commit's short id.

  A stash that the user names ("Stash with message") went through JGit's own `StashCreateCommand`, which does the
  same: "don't" was saved as "dont", and a `{` failed the stash.
- **Now:**
  - `SnapshotStashCreateCommand` takes `indexMessage` and `workingDirectoryMessage` as text. They're null by default,
    and then it formats its default patterns as before. Its callers didn't change.
  - `StashChangesGitAction` passes the user's message through the fork-only `messageFormatText`
    (`MessageFormatText.kt`), a pattern that JGit formats back to the text. A quote holds a `{` and every `{` and `'`
    that follow it: a quote that ends where the next one starts, or before a `''`, pairs up as a `'` of the text (the
    first version gave `{'{` for `{{`, which the test below caught).
- **Tests** (on `ff6e2b84`):
  - `MergingWithAutostashTest` (+2): Merge of a branch `it's` and of a branch `a{b}`, with uncommitted changes, stops
    at a conflict, and the backup's stash list entry and commit message are the sentence as given. Before the fix the
    first gave `its`, and the second failed with `can't parse argument number: b`. Its `BACKUP_MESSAGE` has its
    apostrophe back ("Leaf's backup before merging"), which three more tests then failed on.
  - `StashMessagesTest` (5, new): a backup's message and the user's stash message, both with `{0} a{b} it's {1} ''`,
    are kept as they are (both failed with `can't parse argument number: b` before). The default messages
    (`Snapshot WIP on`, `index on`, `untracked files on`, and JGit's `WIP on`) still fill in a branch named `it's{0}`
    and a subject with `{1}` as they are. `messageFormatText` gives back each of the 19,530 texts of up to 6 of
    `'{}0a`.
  - `:data` passes (1,141 tests, none skipped), and `:app` compiles. The other modules' tests don't reach this code,
    and weren't run.
  - Run in a window (a dev run on 2026-10-16, with the demo repository that `/tmp/leaf-stash-message-demo-setup.sh`
    makes): Merge of `a{b}` and of `it's`, with an uncommitted change, stopped at the conflict, and the stash list had
    each backup with its branch's name as it is. "Stash with message" wasn't tried there.

## Changes that git sets aside come back (branch `fix/merge-autostash`)

- **Before:** since Merge runs `git merge` (item 4), a user's `merge.autoStash=true` set local changes aside in
  `MERGE_AUTOSTASH`. So did the worktree merge dialog's `--squash` and `--no-commit` merges, and a merge from a
  terminal or an agent. git keeps them there until the merge is committed or aborted.
  - Leaf's commit (JGit) and Leaf's Abort (JGit's hard reset) left the ref, and the changes were gone from the working
    tree. The next `git commit` in a terminal put them back without a word, maybe days later, and `git reset --hard`
    or the next `git merge` stored them in the stash list (git 2.54 probed).
  - The squash merge's Abort (`git reset --merge`) stored them in the stash list, where its dialog says unstaged
    changes are kept.
  - When putting them back conflicted, git stored them as "autostash" and exited with 0. Leaf said "Merge completed"
    (or "Rebase completed", with `rebase.autoStash`) while files had conflicts, and deleted its backup snapshot.
  - Leaf kept its backup snapshot after every failed merge, git's refusals included, so each refusal left a stash.
- **Now** (`Autostashes.kt`, `data/git/cli/operations/`):
  - Merge honours `merge.autoStash`, as the user decided: a merge with local changes in the files it merges goes
    through, where git refused before (and JGit before item 4).
  - Leaf puts the changes back as git does: after every Leaf commit, as `git commit`, and after Abort of a merge,
    cherry-pick or revert, as `git merge --abort`. That's `git stash apply`, or `git stash store -m autostash`, then
    the ref goes. The squash merge's Abort takes the ref out before `git reset --merge`, as `git merge --abort` does,
    and puts it back when git refuses.
  - When they don't go back, a dialog says so (`AutostashNoticeDialog`): what set them aside, when, the files with
    conflicts, and that they're in the stash list as "autostash" (the side panel shows "On main: autostash"). It
    isn't a toast, which would be gone before it's read. For git's own merges and rebases, `GitCliOperations.run`
    notices a new "autostash" entry after an exit code of 0, Continue and Abort of a rebase included.
  - Leaf's commit refreshes the stash list.
  - `MergeBranchUseCase` deletes its backup after a failure that changed nothing: HEAD where it was, no merge in
    progress, and not cancelled (`IsUnchangedSinceGitAction`).
- **Tests** (on `4a7df80f`):
  - `AutostashesTest` (7): each put-back case against `git commit` in a twin repository, a linked worktree, a
    reftable repository (skipped where git has no reftable backend), and another tool's stash made meanwhile.
  - `MergingWithAutostashTest` (10, in a `TestTab`): Merge, Leaf's commit and Abort, the squash merge's Abort and
    commit, a refused squash Abort, the notice, the stash list's refresh, the backup.
  - `GitCliOperationsTest` (+4): a rebase's notice, after Continue and Abort too, and none when the changes go back.
  - `AutostashNoticeDialogTest` (5).
  - **Mutation check:** 20 mutations, all caught: apply versus store, removing the ref, the squash Abort taking it out
    and putting it back, the notice's conditions (exit code 0, a new entry, named "autostash", merge and rebase,
    Abort), the put-back after a commit and after Abort, the stash list's refresh, the backup rule both ways and its
    repository state, the files listed, and the dialog's texts. The name check and the Abort case needed two more
    tests, added. Not checked: a cancelled merge keeping its backup, and a failing `git stash store`.
  - The full suite passes: `:app` 320 tests, `:domain` 340, `:data` 1128, `:common` 7.
  - The dialog was rendered offscreen and looked at. Not yet run in a window (`/tmp/leaf-autostash-demo-setup.sh`
    makes a demo repository).
## The commit field's draft is Leaf's own (branch `fix/commit-draft-file`)

- **Before:** the commit field kept its draft in git's `.git/COMMIT_EDITMSG` (upstream's behaviour), and filled from it
  whenever the field was blank. git writes that file as scratch space: every `git commit` from a terminal, which agents
  run all the time next to Leaf, and `git rebase --continue`, which Leaf runs since the operations moved to git. So a
  blank field showed someone else's last message, and the user's next commit could take it. Seen in a manual run on
  2026-10-16: a demo script's `git commit -m "Add topic.txt"` became the field's text. After `git rebase --continue`
  the field took the message with git's whole comment block (`# Conflicts: …`, `# Please enter the commit message…`).
- **Now:** the draft is in `<git dir>/LEAF_COMMIT_DRAFT` (`AppStorage.COMMIT_DRAFT_FILE_NAME`, `CommitDraft.kt`), in
  each worktree's own git dir, as `COMMIT_EDITMSG` was. Leaf no longer reads, writes or removes `COMMIT_EDITMSG`.
  JGit's commit still writes the message there every time, for the commit-msg hook even when there is none
  (`CommitMsgHook.canRun` only checks the file's path), and Leaf no longer removes it afterwards, so it keeps the last
  message, as after git's commit. `MERGE_MSG` and `SQUASH_MSG` work as before.
  - Not the `leaf` config file: in the main worktree it is shared by every worktree's tab, and holds the base branch
    and the folder state, which a save every second while typing would race.
  - No migration (decided 2026-10-16): nothing tells a draft that an older Leaf left in `COMMIT_EDITMSG` from git's
    message, and taking one over would bring the bug back once.
  - The file watcher ignores any worktree's draft. Another worktree's `COMMIT_EDITMSG` refreshed the worktree list, so
    typing in one tab made the others run git in every worktree.
- **Found, not changed:** a merge's commit in Leaf clears the plain draft after the merge is over, so the draft that
  the merge kept out of view is removed from disk (upstream's behaviour, with `COMMIT_EDITMSG` before). The field still
  shows it until a restart or a worktree switch. Fixed in `fix/commit-clears-own-message`.
- **Tests:** `CommitDraftTest` (5, temp repositories with git as the reference): a draft saved, read back and removed,
  with `COMMIT_EDITMSG` untouched; a terminal `git commit -m` and a `git rebase --continue` after conflicts, whose
  messages (the second with git's comments) aren't taken, nor replace a draft; a linked worktree's draft in its own git
  dir; `MERGE_MSG` during a merge, as before. `DraftingCommitMessagesTest` runs a tab: a draft survives an agent's
  commit and a refresh, Leaf's commit removes it, and the next terminal commit leaves the field's message empty.
  `WatchedRepositoryTest` checks the draft in each git dir, `SwitchingWorktreesTest` reads the feature worktree's draft
  from the new file, and `AppStorageTest` checks the new name.
  - **Mutation check:** 7 mutations, all caught, each by a failing test: reading `COMMIT_EDITMSG` again, writing it
    again, the watcher's rule gone, the rule for the tab's own git dir only, no draft read, clearing that keeps the
    file, and the draft in the common git dir.
  - The full suite passes on `4a7df80f` with these changes: `:app` 315 tests, `:domain` 341, `:data` 1113, `:common` 7.
  - Manual run on macOS (2026-10-16), on a demo repository whose last commit left its message in `COMMIT_EDITMSG`: a
    draft, a commit from a terminal, a rebase that stopped at a conflict and went on with `git rebase --continue` in a
    terminal, then a commit in Leaf, which removed the draft and left its message in `COMMIT_EDITMSG`.

## `WorktreeReviewTest` waits for the worktree list (branch `fix/worktree-review-test-wait`)

- **Before:** the class's first test failed once in a full `./gradlew test` (`expected: <3> but was: <null>`, the
  count of the worktree's uncommitted changes), and passed in three runs alone.
- **Cause:** the test's `loaded()` took the first state whose comparison had loaded. The review's state combines the
  comparison with the worktree list, which comes from a flow of its own and is null until it arrives, so a state with
  the comparison and no list can come first. The view model is right to show it: the count is unknown until then,
  and the next state has it. No production code changed.
- **Now:** `loaded()` also waits for the count. Every test sets the worktree list before it opens the review.
- **Tests** (on `1649446d`): a temporary test that gave the review the worktree list 300 ms after the comparison failed
  with the old wait, with the same message, and passed with the new one. With the new wait, the class passed 30 runs
  in a row (`--rerun`) with a CPU burner on each of the 14 cores, and `:app`'s 315 tests passed 5 runs in a row.
  - The same 30 runs with the old wait passed too, so that load doesn't bring the failure back: the temporary test is
    what shows the fix.

## Merge, cherry-pick, revert and rebase run git (branch `fix/git-cli-operations`)

- **Before:** JGit ran Merge in the branch menu, cherry-pick, revert and Rebase.
  - Its merge doesn't follow renames: a file that one side renamed and the other changed stopped at a conflict
    (deleted by us), where git merges the change into the renamed file.
  - JGit knows four hooks (pre-commit, commit-msg, post-commit, pre-push), so `pre-merge-commit`, `post-merge`,
    `post-checkout`, `post-rewrite` and `pre-rebase` never ran.
  - Its merge message was "Merge branch 'feature' into main", for a remote branch too.
  - A cherry-pick or a revert of a commit whose changes were already there did nothing, and said it was done.
- **Now** (`GitCliOperations`, `data/git/cli/operations/`): they run `git merge --ff|--no-ff --no-edit <branch>`,
  `git cherry-pick <commit>`, `git revert --no-edit <commit>` and `git rebase <branch>`, as a terminal would, with
  git's merge engine, hooks, messages ("Merge branch 'feature'", "Merge remote-tracking branch 'origin/feature'"), and
  the identity and signing settings that git reads.
  - They run through `GitCliRemoteCommand`, as git may have to ask: it downloads what a partial clone lacks, git-lfs
    downloads LFS files, and ssh-keygen asks for the passphrase of a key that signs commits, in Leaf's dialog. The
    processing screen can cancel them. Continue, Skip and Abort of a rebase that git started go through the same
    runner, so they ask for passphrases too (before, ssh-keygen ran without Leaf's askpass, and couldn't ask).
  - Conflicts are found as for the worktree merge: exit code 1 with unmerged paths. Merge and rebase say they stopped
    at conflicts, as before, and cherry-pick and revert stop with an error, as before (the revert's now says "Revert
    stopped with conflicts", as the cherry-pick's does, where JGit's said "Revert failed"). Any other failure shows
    git's message: local changes in the way, a hook that refuses, a commit whose changes are already there (the
    cherry-pick then waits in progress, to abort in the banner).
  - A branch is named by its short name unless git would take a tag of that name first (`refArgument`, now shared by
    the domain's worktree dialogs and the data layer).
  - JGit runs them when there is no usable git or no askpass helper. Pull still merges and rebases with JGit, and so
    does the interactive rebase.
- **Tests:** `GitCliOperationsTest` (23): each operation and git's message, fast-forward or not, a remote branch, a
  branch with a tag of the same name, conflicts for each, git's refusals (local changes, a merge while conflicts are
  left, a commit already there), a renamed file merged into, `post-merge`, a `pre-merge-commit` that refuses, an
  identity from `includeIf`, a merge signed with an SSH key whose passphrase Leaf asks for, Continue after git's
  rebase stopped, each `Selecting…GitAction` running git, and JGit without git or the helper. `RebasingWorktreesTest`
  runs Continue, Skip and Abort through the new runner.
  - JGit's results above (the rename's conflict, the empty cherry-pick and revert) were checked with a probe on JGit's
    actions, not kept.
  - **Mutation check:** 15 mutations, all caught: what counts as conflicts (exit code 1, unmerged paths), the
    fallback without git or the helper, `refArgument` both ways, `--ff` against the setting, the branch's name, the
    conflict errors of cherry-pick and revert, each `Selecting…GitAction`, and Continue's stop. Not checked:
    `GIT_EDITOR=:`, as a run without it would start the developer's editor, and `--no-edit` (merge, revert), which
    only repeats it.
  - The full suite passes on `ea42dbf7` with these changes: `:app` 315 tests, `:domain` 340, `:data` 1107, `:common` 7.
    `WorktreeReviewTest`'s first test failed once in the full run and passed in three runs alone: the test waits for
    the comparison, and the count of uncommitted changes can come after it.

## The tests pass on Linux, as CI runs them (branch `fix/ci-linux-tests`)

- **Before:** the Test Build workflow failed on every push to `main` since at least 2026-10-08. 19 of `:data`'s tests
  failed on Ubuntu, and passed on macOS:
  - the worktree merge and rebase tests (10) gave git an empty global config. git makes an identity up on macOS, and
    refuses on Linux ("Committer identity unknown");
  - the fake `ssh-keygen` of the signing tests (7) recorded its arguments with `echo`, which in dash, Ubuntu's
    `/bin/sh`, takes `-n` as its own option and prints nothing, so every argument after it moved up a line;
  - two LFS tests took `/usr/bin:/bin` for a PATH without git-lfs, where Linux installs it.
  - The build stopped there, so CI had never run `:app`'s tests. They render with the app's fonts, which are LFS
    files, and the checkout had only their pointers: a conflict resolver test that measures lines failed with the
    fallback font.
- **Now:** those tests give git an identity, fake programs record their arguments with `printf '%s\n'` (a fake ssh
  that passed by luck too), and `pathWithoutGitLfs` puts a `git-lfs` that fails, as git without it does, first on the
  PATH.
  - The workflow checks the fonts out with `git lfs pull`, from `.git/lfs/objects` restored from the Actions cache, so
    a run downloads them (9.3 MB, from the account's 10 GiB of LFS bandwidth a month) only when they change, and
    installs `libgl1` and `libegl1`, which Compose renders with. The app is unchanged.
- **Tests:** in an Ubuntu 26.04 container (Temurin 25, git 2.53, git-lfs 3.7, OpenSSH 10.2, a user other than root),
  the same 19 tests failed before and pass now. `:app`'s 315 tests pass there with the fonts, and one fails with their
  pointers. Five process and login shell tests fail only in the container; CI passed them. On macOS, the 7 classes
  changed pass.

## JGit reads the files that `includeIf` names, for its own commits and settings (branch `fix/includeif-config`)

- **Before** (upstream issue 186): JGit's config parser expands only `[include]`.
  - The commits that JGit makes itself took the identity and signing settings of the user's config, without the work
    identity that a file included for `~/work/` sets: Merge in the branch menu, pull's merge and rebase, rebase and
    interactive rebase, cherry-pick and revert. So did their reflog entries, and the backup stashes before a merge or
    a pull.
  - JGit's other settings in such files didn't apply either (`core.autocrlf`, `merge.conflictStyle`, `pull.rebase`,
    `core.hooksPath` as JGit reads it, …).
- **Now** (`IncludedConfigRepository`, `GitIncludedConfig`): JGit opens repositories with a layer of what git reads
  only through `includeIf`, between the user's config and the repository's own file.
  - Leaf finds it in `git config --list -z --show-origin --show-scope`: the entries of the files reached through an
    `includeIf`, and of what those files include.
  - It reads them again when one of the files that they depend on changes: the files that git read, the includes'
    targets, the repository's HEAD for `onbranch:`, and the config files that git would read if they appeared.
    Otherwise Leaf only checks those files' state.
  - A key that the user's config sets again after the include keeps git's last value. A key that the repository's own
    file sets stays the repository's in JGit, even where an `includeIf` in that file comes after it. JGit reads no
    `author.*` or `committer.*`, so its merges and reverts take `user.*` for both.
  - Saving the repository's config writes only the repository's own entries. Without a usable git, or where no
    `includeIf` applies, JGit reads as before.
- **Tests:** `IncludedConfigTest` (8) checks the entries against git's own list: a file that `includeIf` names and
  not one that `[include]` or an unmatched `includeIf` names, an include inside such a file, `onbranch:` in the
  repository's own config, `~/` and a relative path next to a config file that is a symbolic link, a value set again
  after the include, the files watched, and the fallback without git.
  - `IncludedConfigRepositoryTest` (15) compares with git: Merge in the branch menu (its reflog entry too), revert,
    cherry-pick, rebase, interactive rebase, a pull's merge and rebase, a snapshot stash, a merge signed as the
    included file says, a hook from its `core.hooksPath`, saving the repository's config, reading again after the
    file or the branch changed, a later value, a repository without `includeIf`, and the fallback without git.
  - **Mutation check:** 14 mutations, all caught. The first run missed two: comparing the includes' real paths, which
    git never needs, so the paths are now compared as git prints them, and reading origins that git prints relative,
    which a test now checks.
  - The full suite passes on `63f86d02` with these changes: `:app` 315 tests, `:domain` 340, `:data` 1084, `:common` 7.

## A resolved cherry-pick keeps the picked commit's author (branch `fix/cherry-pick-author`)

- **Before:** when a cherry-pick stopped at conflicts and the resolution was committed in Leaf, the commit's author
  was its committer, with the commit's time. JGit's `CommitCommand` doesn't read the author of `CHERRY_PICK_HEAD`,
  which `git commit` keeps, with its date. Upstream did the same.
- **Now:** `DoCommitGitAction` sets the picked commit's author and author date (`cherryPickedAuthor`) when it commits
  during a cherry-pick without amending. The committer is git's, as for Leaf's other commits.
- **Tests:** `CommitIdentityTest` (+1): Leaf's cherry-pick stops at a conflict and Leaf commits the resolution; the
  same resolution committed with `git commit --no-edit` has the same author, author date and committer.
  - Mutation check: without the line that sets the picked commit's author, that test fails.
  - The full suite passes on `24fb0e3d` with finding hooks where git does and these changes: `:app` 315 tests,
    `:domain` 340, `:data` 1075 (14 skipped), `:common` 7.

## Hooks are found where git finds them, `includeIf` and `~/` included (branch `fix/hooks-from-git`)

- **Before** (upstream issue 186): JGit read `core.hooksPath` from its own config, which skips the files that
  `includeIf` names, and took the value as it is.
  - A hooks folder set for `~/work/` in such a file didn't apply to Leaf's commits: their pre-commit, commit-msg and
    post-commit hooks didn't run, where `git commit` ran them.
  - `core.hooksPath = ~/hooks` was a folder named `~` in the working tree.
- **Now** (`GitHooksDirectory`): each time JGit looks for a hook, Leaf asks git where the hooks are, with
  `git rev-parse --path-format=absolute --git-path hooks`. That covers Leaf's commits and amends, the commits that
  JGit's merges and reverts make, and JGit's pushes.
  - A relative path is from the working tree's root, as with git, and a linked worktree without `core.hooksPath`
    runs the common git dir's hooks. Without a usable git, JGit's own lookup stays.
  - A commit runs git three times more (pre-commit, commit-msg, post-commit), about 13 ms each.
- **Tests:** `GitHooksDirectoryTest` (7), each compared with `git commit` running the same hooks: a folder that an
  `includeIf` file sets, `~/`, a relative path from an `includeIf` file in a linked worktree, a linked worktree
  without `core.hooksPath`, a hook that isn't executable, a new instance of the file system, and the fallback without
  git. `WindowsFsTest` (+1) runs a hook from the folder that git names, through a new instance.
  - **Mutation check:** 10 mutations, all caught. The first run missed two, and a test was added for each: without
    `--path-format=absolute`, a relative path set where JGit also reads it was found by JGit's fallback, and
    `PosixFs`'s copy could lose the lookup.
  - The full suite passes on `87905b90` with these changes: `:app` 312 tests, `:domain` 339, `:data` 1005,
    `:common` 7.
  - Run by hand in a dev run, on a repository whose own `.git/config` includes, with `includeIf`, a file that sets
    `core.hooksPath` to a `~/` folder: Leaf's commit ran that folder's pre-commit hook, and its commit-msg hook's text
    was in the commit's message.

## JGit measures the file system before the tests, not in their repositories (branch `claude/infallible-yalow-2d8d93`)

- **Before:** `CleaningUpWorktreesTest."removes the checked worktrees and their branches, and keeps the rest"` failed
  about one run in four when its class ran alone (2 of 10 here, 2 of 7 on `9e41ded5`): `git worktree remove
  ../squashed` exited with 255 and "failed to delete '.git/worktrees/squashed': Directory not empty".
- **Cause:** watching that git dir while the cleanup ran showed one file that wasn't git's: `.probe-<uuid>`. It was
  there before the cleanup started, and was back less than a millisecond after git deleted it.
  - JGit 7.7 measures a file store's timestamp resolution once per JVM, the first time it takes a snapshot of a file on
    a file store that its config has no measurement for (`FS.FileStoreAttributes`). For the last 3 seconds of it, it
    writes a `.probe-<uuid>` file again and again in that file's folder, and creates the folder again when it's gone.
    Then it saves the result in the JGit config that is current at that moment. Other callers don't wait for those 3
    seconds.
  - Tests point JGit's config at an empty file (`IsolatedSystemReader`), so every test JVM measured again, in the
    first git dir that a test read with JGit. When the class runs alone, this test runs first, and its tab opens the
    `squashed` worktree. So the probe was writing in the git dir that the cleanup removed a second or two later.
  - A measurement that ended after its test had put the previous `SystemReader` back would have been saved in the
    developer's `~/.config/jgit/config`.
- **Not the app's:** Leaf saves the measurement in `~/.config/jgit/config`, one per Java vendor, Java version and
  volume, and this machine's has JBR 25.0.4.1's. So the app measures only on its first launch with a new runtime, or
  for a volume it hasn't measured, and then for 3 seconds in the first git dir that it reads. Clean up, Remove and
  Discard can't be confirmed that soon after launch. An agent's `git worktree remove` in those seconds could fail the
  same way, so the app is unchanged.
- **Now** (`MeasureJGitFileStore`, a JUnit Platform `LauncherSessionListener` registered in `:data`'s test resources):
  before the first test of each test JVM, JGit measures in a temporary folder of its own, with an isolated JGit config.
  The listener waits until JGit has saved the measurement there, then deletes the folder. The tests' folders are on the
  same file store, so JGit doesn't measure again. `junit-platform-launcher` is now `testImplementation`, for the
  listener's interface.
  - The 3 seconds come before the first test now: in this class, the first test went from 4.6 s to 2.3 s.
  - `TestTab.close()` said that it waits because JGit writes `.probe-` files into the git dir, which was this
    measurement. It still waits for the tab's refreshes, which run git and JGit in the test's folder.
- **Tests** (on `87905b90`): the class passed 20 runs in a row (`--rerun`), against 2 failures in 10 runs before,
  and `:data`'s 997 tests passed. A poll of the git dir during the cleanup saw no `.probe-` file, and the developer's
  `~/.config/jgit/config` didn't change. Rebased onto `12a929ec` (the file watcher on the JDK), `:data`'s 1,050 tests
  passed.

## The built-in LFS client picks its server as git-lfs does, uploads too (branch `claude/amazing-lichterman-7bdace`)

- **Before:** the lookup (`GetLfsUrlGitAction`) still differed from git-lfs 3.8 in the cases that the branch
  `fix/lfs-url-git-config` left out:
  - A remote asked by name got `<url>/info/lfs` (`https://host/org/repo/info/lfs`), where git-lfs, and Leaf's own
    path for the only remote, use `<url>.git/info/lfs`. Pushes through JGit, the cards' fallback to `origin` and the
    tracked remote took that path.
  - Uploads through JGit's pre-push hook read neither `lfs.pushurl`, `remote.<name>.lfspushurl` nor the remote's
    `pushurl`.
  - Without an upstream, it took the only remote, and stopped there: no `remote.lfsdefault`, no `origin`. With several
    remotes and no upstream, a checkout without git-lfs failed with "LFS Url not found". A detached HEAD at a
    branch's commit took that branch's upstream (upstream's `GetCurrentBranchGitAction`), and an upstream counted only
    with `branch.<name>.merge` too.
- **Now:** the server is `lfs.pushurl` (uploads), `lfs.url`, the remote's `lfspushurl` (uploads), its `lfsurl`, then
  its URL (`pushurl` for uploads) as `<url>.git/info/lfs`; a remote with none of them, such as `.` for a local
  upstream, gets `origin`'s. The remote is the one asked for, or else the current branch's `branch.<name>.remote`,
  `remote.lfsdefault`, the only remote, then `origin`; a detached HEAD has no branch. `IGetLfsUrlGitAction` takes the
  operation, and the pre-push hook asks for an upload. The conflict cards ask once, as the lookup falls back to
  `origin` itself. Credential helpers are asked about the remote's push URL for an upload, as git-lfs does.
- **Decided:** an empty value still counts as not set, as git-lfs's documentation says ("Default blank (derive from
  clone URL)"). git-lfs 3.8 takes it as the server: none for an empty `lfs.url`, `origin`'s for another remote's empty
  `lfsurl`. A test checks git-lfs's side too, so it fails if git-lfs changes.
- **Not done** (listed in CLAUDE.md): `url.<base>.insteadOf` on `lfs.url`, `FETCH_HEAD`'s URL when `origin` has none,
  `.lfsconfig` from the index or `HEAD`, the last of several remote URLs, and git-lfs counting `remote.pushDefault` as
  a remote named "".
- **Tests:** `GetLfsUrlGitActionTest` (10, 5 new) sets up remotes and settings in 16 steps and compares each with
  what git-lfs asks: `git lfs env` for a download from each remote, `git lfs push` for an upload to each, and
  `git lfs fetch` for the remote it picks, against a port where nothing listens (skipped without git-lfs). Its other
  new tests cover the URL, the upload order, the remote's choice and `origin`'s server. `GetLfsUrlSettingGitActionTest`
  (11, 4 new) reads the new settings from git's config and `.lfsconfig`, and empty values against `git lfs env`.
  `LfsPrePushHookTest` (new) runs the pre-push hook against a JDK `HttpServer`. `DownloadLfsVersionGitActionTest` lost
  its two fallback tests, as the fallback moved into the lookup, and checks that the card asks for a download.
  - **Mutation check:** 18 mutations, 18 caught, each by a failing test: the `.git` left out of the URL, a trailing
    `/` kept, the push URL, `lfs.pushurl` and the remote's `lfspushurl` ignored or put after `lfs.url`, `lfs.pushurl`
    read for downloads, `lfspushurl` read from `.lfsconfig`, `lfsdefault` not read from it and the branch's remote
    read from it, no `origin` fallback, no `lfsdefault`, no only remote, no upstream, no remote URL for credentials,
    an empty value counted as set, and the pre-push hook and the card asking for the wrong operation. The pre-push
    hook's first got through, and `LfsPrePushHookTest` was added for it.

## The watch follows new and deleted folders during Leaf's own operations too (branch `claude/zealous-tesla-59b911`)

- **Before:** `ObserveRepositoryToRefreshUseCase` watched the folders that a batch of changes created only when it
  could refresh: not while a task ran, nor for 1.5 s after one. So a folder created by Leaf's own checkout, merge or
  pull was never watched, and edits in it didn't refresh the status until the tab watched again (a worktree switch,
  or reopening). Deleted folders were only unwatched in a batch that also created one. Both came from upstream. Lost
  changes (new with the JDK watcher) didn't watch the working tree again during a task either.
- **Now:** every batch registers its new folders and unregisters its deleted ones, and lost changes watch the working
  tree again, whatever runs. Only the refresh waits for the task, as before.
- **Tests:** `RefreshingOnChangesTest` (7, 3 new), with a `RecordingFileChangesWatcher`: a folder created while an
  operation runs is watched and nothing refreshes, a deleted folder is unwatched in a batch that created none, and
  lost changes while an operation runs watch the folders created meanwhile (`.git/worktrees` included) and refresh
  nothing. The recording watcher now skips a folder that doesn't exist, as the app's does: it noted the missing
  `.git/worktrees` at the watch's start, which hid whether lost changes watched it.
  - **Mutation check:** 8 mutations of the use case, 7 caught: new folders registered behind the gate again, deleted
    ones unwatched only with a new folder or not at all, the working tree or `worktrees` re-watched after lost changes
    behind the gate, and a refresh during an operation, after a batch or after lost changes. Registering after the
    "nothing to refresh" return survives: such batches (Leaf's `leaf` file, JGit's probe files, commit messages) have
    no folders.

## The file watcher runs on the JDK, and Leaf's Rust is only `leaf-askpass` (branch `claude/jolly-davinci-5d25f1`)

- **Before:** the file watcher was Rust (notify 8), the only export of the `leaf_rs` cdylib, called through uniffi's
  generated bindings and JNA. Upstream moved it there in 2023 because Java's watcher polled on macOS. Every build ran
  uniffi-bindgen, every launch extracted and loaded the library (`extractNativeLibrary`), and dev runs of builds from
  before 2026-10-13 killed each other over it.
- **Now:** `FileChangesWatcher` runs on the JDK's `WatchService`. JetBrains' JDK, which runs Leaf, watches with FSEvents
  on macOS (`MacOSXWatchService`), with a change reported about 200 ms after it; Linux uses inotify, Windows
  ReadDirectoryChangesW. It keeps what the Rust watcher did: the blocking loop on a thread of its own, batches of
  changes once 500 ms pass without events or every 500 ms while they keep coming, temporary files left out, folders
  created or deleted told apart, a cancelled watch stopping within 500 ms, and a watcher per watch, closed when it
  ends.
  - Recursive folders (`refs`, `modules`, `worktrees`) are one `FILE_TREE` registration where the JDK has it (JBR on
    macOS, Windows), and a registration per folder elsewhere (Linux), with the folders created later added by the
    watch.
  - New: lost events (`OVERFLOW`, past 512 waiting events in one folder, which a large checkout reaches) become
    `WatcherEvent.ChangesLost`, which watches the working tree's folders again and refreshes everything.
  - A folder's own "modified" event (its time changed with its entries) is left out: JBR reports it when it lists the
    folder above. Found in a dev run, where the next change in the root after a commit reported `.git` too, and
    refreshed everything.
  - New: a folder watched with its tree isn't registered again on its own. JBR then drops `FILE_TREE`, and the refresh
    did that to `worktrees` once git created it (the Rust watcher, through notify, likely lost its recursion the same
    way).
  - `FileChanged` and `FileType` moved from the bindings to `domain/models/FileChanged.kt`.
- **Removed:** the cdylib (`rs/src/lib.rs`), uniffi and `uniffi-bindgen`, `uniffi.toml`, the bindings folders, JNA,
  `generateRustBindings`, `App.initNativeDependencies` and `extractNativeLibrary` with its test. `rs/` builds
  `leaf-askpass` alone, with no dependencies, and `:domain` builds without cargo. Older checkouts' gitignored leftovers
  go by themselves: `removeRustBindings` deletes the bindings, which no longer compile, and `copyRustBuild` deletes
  `libleaf_rs.*` from the app's resources. `--enable-native-access=ALL-UNNAMED` stays, for skiko.
- **Tests:** `FileChangesWatcherTest` (16) runs the watcher on the machine's JDK: the collecting thread left free,
  batching and the 500 ms rate, folders created and deleted, a subfolder whose entries changed, a folder alone versus
  its tree with and without `FILE_TREE`, a tree added again on its own, lost events, removal, cancelling and closing,
  and the batch's rules.
  `RefreshingOnChangesTest` (4) runs a `TestTab` with it: an edit refreshes the status, a commit from a terminal the
  branch, staging in another worktree the worktree list, and lost changes everything, with the new folders watched.
  - **Dev run** (JBR 25, macOS 26.6), against a temporary repository with a linked worktree: an edit refreshed the
    status, a commit from a terminal everything, staging in the other worktree the worktree list, and a folder created
    after the watch started was watched (a file in it was reported).
  - **Mutation check:** 18 mutations of the watcher and the use case, 16 caught. Taking out the 500 ms rate survives
    on macOS, where JBR delivers FSEvents about every 500 ms anyway; on Linux, inotify reports each write and the rate
    test fails. Reporting on silence only once 500 ms passed since the last report is equivalent, as the poll's
    timeout is 500 ms.

## The Rust build runs only when `rs/` changes, and its failures fail the build (branch `claude/jolly-davinci-5d25f1`)

- **Before:** `app/build.gradle.kts` ran cargo three times (a release build, a debug build, uniffi-bindgen) inside the
  configuration block of `rustTasks`, whenever an app build, run or test had it in its graph, whether `rs/` had
  changed or not. Failures were ignored: `executePrintingData` printed `Code is 101` and the build went on with the
  library from the build before, or none. `:domain` alone failed in a fresh worktree, which had no bindings yet.
  `rs/rust-toolchain.toml` pinned `nightly`, a different compiler every day, and `rs/Cargo.lock` was gitignored.
- **Now:** three tasks, which Gradle skips while their inputs don't change: `cargoBuild` (`cargo build --release`, or
  `cross` for the Linux jars), `copyRustBuild` (into the app's resources, before `processResources`) and
  `generateRustBindings` (before `:domain`'s `compileKotlin` and `kspKotlin`). Their inputs are `rs/src`, the `.rs`
  files, `Cargo.toml`, `Cargo.lock`, the toolchain file and `uniffi.toml`. A cargo failure fails the build. The
  toolchain file pins Rust 1.99.0 (minimal profile), and `Cargo.lock` is tracked. `:data:test` depends on
  `copyRustBuild`, as its tests run `leaf-askpass`.
- **Checked:** a second `:app:rustTasks` runs no cargo; a syntax error in `leaf-askpass.rs` fails `processResources`
  with cargo's error; `:domain:compileKotlin` regenerates deleted bindings first. The release workflow's steps are
  unchanged: `dtolnay/rust-toolchain` installs rustup's stable without setting `RUSTUP_TOOLCHAIN`, so cargo in `rs/`
  installs the pinned version from the toolchain file, as it installed nightly before.

## Split indexes, sparse indexes and partial clones (branch `claude/strange-lewin-b68009`)

- **Before** (found with a probe on git 2.54 and JGit 7.7):
  - In a repository whose index git wrote split (`git update-index --split-index`, `core.splitIndex`) or sparse
    (`index.sparse` with a cone-mode sparse checkout), JGit fails at its first entry: "Invalid path: ", or
    "Invalid path: b/". Status, staging, uncommitted diffs, blame and checkouts failed with that message, and the log
    stayed empty, as it loads the status first. Viewing a file in a commit failed too, as
    `GetDiffEntryFromDiffTypeGitAction` read the index for every diff. Every `git status` that the worktree list runs
    touches the split index's `sharedindex.<id>` file, which the file watcher took for a change: the tab refreshed
    everything twice a second.
  - In a partial clone (`git clone --filter=blob:none`), JGit can't download the files that the clone hasn't, and
    throws `MissingObjectException`. Selecting an older commit failed (JGit formatted every changed file's diff to
    list them, which nothing read), and so did its diffs, blame and worktree review. Checking out another branch
    deleted a file, then failed with HEAD where it was; a hard reset moved HEAD, then failed with a file staged.
- **Now, split and sparse indexes** (picked from options and a mockup: a strip, with a Convert button):
  - An amber strip under the toolbar says what git did to the index and what doesn't work until it's converted, with
    the commands, which can be selected: `git update-index --no-split-index` (after `git config core.splitIndex false`
    when that is on), or `git sparse-checkout reapply --no-sparse-index`, which keeps the sparse checkout. "Convert
    index…" shows them in a dialog and runs them. The strip goes once a status loads, whoever converted the index.
  - Any other operation that fails on the index says the same in `ErrorDialog`, with the commands and their `cd`.
  - The log, branches, a commit's files and diffs, file history and worktree review work with such an index.
  - The index file is read only after JGit failed on it: its extensions say which format it is (`link`, `sdir`, or
    another that readers must know).
  - The watcher ignores `sharedindex*` files in the git dirs, which ends the refresh loop.
  - Moving status and staging to the git CLI was the alternative; it isn't built, as commit, checkout, stash, merge and
    discard read the index through JGit too.
- **Now, partial clones:**
  - Leaf has git fetch what JGit misses, as git does for its promisor remote: one `git fetch <remote>
    --filter=blob:none --stdin` with the objects' ids, through Leaf's credential dialogs, then runs the diff, blame,
    file list, review or conflict loading again. The first download takes what it will read next too: both versions
    of a diff's file, and every version of a blamed file.
  - Checkouts, hard reset, merge, cherry-pick, revert, rebase, interactive rebase and pull download the files they
    will write before JGit starts, with progress and Cancel on the processing screen. A failed download changes
    nothing, and `ErrorDialog` says why.
  - A diff that can't be downloaded, offline for example, says so in the diff pane (and in file history), with git's
    output and Try again, as picked from the mockup. The pane used to close. A blame that fails opens `ErrorDialog`;
    it used to say nothing.
  - Lists of a commit's files no longer format each file's diff, which nothing read: selecting a commit reads no file
    contents, in every repository.
- **Tests,** each written first and seen to fail:
  - `UnsupportedIndexTest` (9, data): status, staging, checkout and blame on split and sparse indexes give the
    explanation and the commands; a commit's diff and a tab's log work; running the conversions keeps staged changes
    and the sparse checkout, and status works again.
  - `IndexExtensionsTest` (9, data): index files that git 2.54 wrote in versions 2, 3 (extended flags) and 4, split
    and sparse, `feature.manyFiles`, an unknown extension, and damaged files.
  - `PartialCloneTest` (13, data), clones with `--filter=blob:none` over `file://`: a commit's files without
    downloading, a diff that downloads its two versions and nothing else, blame, review, a download that fails, and
    checkout, a checkout whose download fails (nothing changes), merge with conflicts, hard reset, cherry-pick, revert,
    pull, and two rebases, one of which needs a version that only a replayed commit has.
  - `WatchedRepositoryTest` (domain): the shared index needs no refresh. `UnsupportedIndexBannerTest` (5) and
    `DiffLoadFailureTest` (2) render the strip, the error text and the pane offscreen.
  - **Mutation check:** 21 mutations, all caught. One survived at first, the rebase's download of what the replayed
    commits have, as the first rebase test replayed a single commit: the second rebase test catches it.
  - The full suite passes on `9e41ded5` with these changes: `:app` 282 tests, `:domain` 335, `:data` 996, `:common` 7.
- **Manual run** (`:app:run` on demo repositories, screenshots of the window): the strip on a split and a sparse index
  over the log, with no refresh loop; converting from a terminal removed the strip and showed the changes. The partial
  clone opened without downloading anything. A temporary harness on the real view model then showed an old commit's
  diff after one download, the pane's error with the remote gone, Try again loading it once the remote was back, and a
  checkout of `origin/feature` that left a clean working tree.

## The built-in LFS client reads `lfs.url` and `remote.<name>.lfsurl` from git's config (branch `fix/lfs-url-git-config`)

- **Before:** Leaf's built-in LFS client, which a conflict's card and checkouts without git-lfs download with, took
  `lfs.url` only from `.lfsconfig` in the working tree. An `lfs.url` set in `.git/config` or the user's config, and
  any `remote.<name>.lfsurl`, were ignored, and the client asked the remote's own LFS server instead.
- **Now** (`GetLfsUrlSettingGitAction`): `lfs.url`, then the remote's `remote.<name>.lfsurl`, then the remote's URL.
  Each setting comes from git's config first, the repository's over the user's and with `includeIf`, then from
  `.lfsconfig`: the order that git-lfs 3.8's `git lfs env` showed, where an `lfs.url` in `.lfsconfig` beats a
  `remote.<name>.lfsurl` in `.git/config`. git reads them; JGit's config only when git can't run. They apply to pushes
  through JGit's pre-push hook too, as git-lfs uses both for uploads when no push URL is set.
- **Fixed on the way:** the lookup asked for the current branch's upstream by its full ref
  (`branch.refs/heads/main.remote`, never set), so the current branch's remote was never found: with several remotes,
  checkouts without git-lfs had no LFS server unless `lfs.url` was set, and the cards fell back to `origin`. It now
  passes the branch, as git-lfs takes the branch's remote.
- **Not done:** `lfs.pushurl` and `remote.<name>.lfspushurl` for uploads, `remote.lfsdefault`, and git-lfs's fallback
  to `origin` when the branch has no upstream and there are several remotes. An empty value counts as not set, where
  git-lfs is left without a server.
- **Tests:** `GetLfsUrlSettingGitActionTest` reads both settings from the repository's config, the user's, a file that
  `includeIf` adds and `.lfsconfig`, in their order, for a remote whose name has dots too, and from JGit's config
  without a usable git. It sets them one after another, for `origin` and for the remote that the branch tracks, and
  compares each step with `git lfs env` (skipped without git-lfs). `GetLfsUrlGitActionTest` checks that the server
  lookup uses them, for the only remote, the remote asked for and the remote tracked, with the remote's URL still
  asked for credentials.
  - **Mutation check:** 15 mutations, 15 caught, each by a failing test: in the data action, git's config not read,
    `.lfsconfig` first, JGit read when git says "not set", no JGit fallback, and the remote's name left out of git's
    key, of JGit's lookup and of `.lfsconfig`'s, or `.lfsconfig` not read for remotes; in the server lookup, the
    remote's `lfsurl` ignored or put before `lfs.url` (for the only remote and for a named one), `lfs.url` ignored,
    `origin`'s `lfsurl` taken for another remote, and the upstream looked up by full ref again. The `lfsurl` put
    before `lfs.url` for the only remote first got through, and a test was added for it.

## Commits, tags and stashes take git's identity and signing settings, `includeIf` too (branch `fix/includeif-identity`)

- **Before** (upstream issue 186): JGit reads no `includeIf`. With a personal identity in `~/.gitconfig` and a work
  identity in a file included for `~/work/`, Leaf committed in `~/work/` as the personal identity, where git commits as
  the work one.
  - That went for Leaf's commits and amends, and for the squash merges and merges of agents' branches from the
    worktree dialogs, where git merges and Leaf commits. Also for tags and stashes, the bottom bar and the sign-off
    line.
  - An identity set only in such a file made Leaf ask for one ("Your Git configuration does not have a user identity
    set"), and the commit then had JGit's guess as its committer, `<user> <<user>@localhost>`.
  - Signing settings there (`commit.gpgSign`, `user.signingKey`, `gpg.format`, …) didn't apply either, so the
    commits weren't signed.
- **Now:**
  - Leaf asks git who commits: `git var GIT_AUTHOR_IDENT` and `GIT_COMMITTER_IDENT` (`GitIdentities`), with
    `user.useConfigOnly`, so that git refuses rather than make one up. Leaf's commits take git's committer, and git's
    author unless they amend. Tags take git's committer as the tagger, and stashes as their person.
  - The bottom bar and the sign-off line show git's author, and the identity dialog asks only when git has none. The
    identity typed there is the committer too.
  - git's answer also has `author.*` and `committer.*`, and `GIT_AUTHOR_NAME` and the like set in the login shell, as
    in a terminal. Without a usable git, JGit's identity stays.
  - The signing settings of Leaf's commits and tags come from `git config --list -z` (`GitSigningConfigs`), and the SSH
    signer reads `gpg.ssh.program` and `gpg.ssh.defaultKeyCommand` from there too.
  - A commit runs three git commands more, about 13 ms each, the two `git var` side by side.
- **Still from JGit's config:** the commits that JGit makes itself, whose commands take no committer (Merge in the
  branch menu, pull, rebase and interactive rebase, cherry-pick, revert), reflog identities, hooks from
  `core.hooksPath`, and JGit's other settings. Putting git's config under JGit's repository config needs a
  `FileRepository` subclass that overrides `getConfig()`, which JGit's constructor calls, so it was left out. Moving
  those operations to the git CLI would cover them.
- **Tests:** git and JGit read the same user config, and JGit skips its `includeIf`.
  - `CommitIdentityTest` (10): a commit as `git commit` makes it, an amend, a squash merge and a merge from the
    worktree dialog, the dialog's check, a typed identity, `onbranch:` in a linked worktree, a tag and a stash,
    `author.email` and `GIT_AUTHOR_NAME` from the login shell, and the fallback without git.
  - `GitSigningConfigsTest` (3): every kind of value, as git and JGit read it, and commits signed with gpg and with an
    ssh program that a file that `includeIf` names gives. `CreateTagGitActionTest` (+1) signs a tag that way, and
    `SshProgramSignerRealSshKeygenTest` (+1) signs a commit with the real ssh-keygen, which git verifies.
  - `AuthorInfoTest` (5, domain): which identity shows, and when the dialog asks.
  - **Mutation check:** 22 mutations, 21 caught. The one that survived took out `configOf`'s escape for tabs: JGit
    keeps a tab inside double quotes as it is, so that escape went, with the one for backspaces.
  - Two tab tests read what a task's refresh writes without waiting for it, as that refresh runs after the task ends.
    The refresh now runs `git var` before it reads the repository state, and they failed every time:
    `MergingWorktreesTest` now waits until the state isn't `SAFE` any more, and `RemovingWorktreesTest` until the
    task that the tab started itself is recorded.
  - The full suite passes on `9e41ded5` with these changes: `:app` 275 tests, `:domain` 339, `:data` 980, `:common` 7.
  - Run by hand in a dev run, on a repository whose own `.git/config` includes a work identity and SSH signing with
    `includeIf`: a commit and an amend made in Leaf were the work identity's, the amend kept the author's date, and
    git verified both signatures.

## The diff pane's scroll state can't crash Leaf (branch `feature/discard-line-button`)

- **Before** (found in the manual run of the Discard button): discarding a file's last unstaged line crashed the diff
  pane with "Reading a state that was created after the snapshot was taken". The view model makes a new
  `LazyListState` for each diff on `Dispatchers.Default` (upstream code), which puts its states in the global
  snapshot, and a frame whose snapshot began earlier can't read them. The pane gets it from a `StateFlow`, which
  doesn't go through snapshots, so such a frame could. File history makes its diff's state the same way.
- **Now** (`newLazyListState`, `ScrollStates.kt`): both view models make the state inside
  `Snapshot.withMutableSnapshot`, which gives each of its states a record that every snapshot can read.
- **Tests:** `ScrollStatesTest` makes a state on another thread and reads it in an older snapshot, and shows that a
  plain `LazyListState` fails there with the crash's message. The crash itself didn't reproduce offscreen: a harness
  on the real Dagger graph repeated the manual run's steps (staging and discarding every line, tooltips showing)
  without an error, so the cause is a race with the window's frames.
  - **Mutation check:** making the state the plain way again fails the test.

## Discard a line from its own button (branch `feature/discard-line-button`)

- **Before:** in the Unstaged diff, the pointer over a changed line showed a button to stage it, and nothing to
  discard it: "Discard line" was in the line's right-click menu, which nothing pointed to. The menu had no Stage or
  Unstage. The line's button and its menu also stayed during a merge, rebase, cherry-pick or revert, when the hunk
  header has no buttons.
- **Now** (`DiffLineActions.kt`): a red Discard button follows Stage, and the menu has the buttons' items: Stage line
  and Discard line in Unstaged, Unstage line in Staged. Each button has a tooltip. The text starts further right in
  Unstaged to leave room for the two buttons. Discarding asks first, as before. Neither buttons nor menu show while the
  hunk buttons are hidden.
- **Tests:** `DiffLineActionsTest` checks which actions each line gets, then renders the unified and split diffs
  offscreen: it hovers a line, clicks its buttons, opens its menu and clicks an item, and checks that the buttons don't
  cover the text. A menu ignores a right-click within 20 ms of the last one, in any test, so the test waits first.
  - **Mutation check:** 7 mutations, 7 caught: no Discard in Unstaged, no gating, the text at 16 dp, an empty menu,
    Discard staging in either view, and buttons without the pointer over the line.

## The conflict resolver's LFS cards download their object (branch `claude/pensive-bhaskara-bd6e77`)

- **Before:** a Git LFS conflict's card whose object wasn't in the local LFS store said "Not downloaded, so it can't be
  shown", and offered neither the image nor "Open in the default app". Usually theirs: ours was checked out.
- **Now** (option A of three mocked up, `conflict-resolver.md` section 9): the card says "Not downloaded yet" and
  "Download 4.2 MB". A click downloads that object alone with Leaf's built-in LFS client, from the server that the
  checkout's client uses, then `origin`; the card shows the bytes received, a bar and Cancel, then the image or "Open
  in the default app". A failure stays in the card with Try again. A side picked stays picked, and a download goes on
  when another file opens.
- **Also:** the checkout's smudge filter downloads with the same code (`FetchLfsObjectGitAction`). When the LFS server
  refused an object (`404 Object does not exist`), it used to skip the download, then fail reading a file that
  wasn't there; it now says what the server answered. Downloads report the bytes written.
- **Tests:** `DownloadLfsVersionGitActionTest` downloads from a `FakeLfsServer`: an image, a video, an object already
  stored, one the server doesn't have, no server, `origin` and the only remote as fallbacks, a closed credentials
  dialog, and a cancelled download, which leaves nothing next to the store. `DownloadLfsObjectGitActionTest` checks the
  progress, `LfsDownloadsTest` the view model's updates, and `ConflictResolverTest` each state of the card. A temporary
  harness ran the view model through the Dagger graph against a local LFS server. An existing test of the cards waited
  for one image to decode and checked the other, and failed in 2 of 4 runs; it now waits for both.
  - **Mutation check:** 14 mutations, 14 caught, each by a failing test: the server's refusal, the credentials
    dialog, the remote fallbacks, an object already stored, the image check, the progress, the base's card, and the
    card's links and bar. The view model's guard that keeps a late progress update from bringing back a cancelled
    download has no test of its own.

## Quitting asks about the conflict resolver's unsaved changes (branch `claude/zealous-herschel-b15367`)

- **Before:** closing a tab, switching its worktree or opening another repository in it asked about the resolver's
  picks and lines typed that weren't saved, but quitting Leaf didn't. On macOS, Cmd+Q, the Dock's Quit and logging out
  didn't even reach Leaf's close request: the JDK's default quit handler calls `System.exit` at once.
- **Now:** closing the window, Cmd+Q, Quit in the app menu and in the Dock, and logging out ask in one dialog while any
  tab's resolver has changes that aren't saved: "Quit Leaf?", each file and its tab, then Cancel, Discard and quit, and
  Save and quit. Enter saves and quits, Esc stays. Save and quit writes the files one after another, then quits; a save
  that fails stops the quit and selects its tab, whose strip says why. Leaf comes to the front first, and Cancel stops
  a logout. Without unsaved changes, quitting is as before, except that Cmd+Q now clears Leaf's temp files, as the
  close button did.
- **Tests:** `QuitRequestsTest` (each answer, the saves one after another, a save that fails, Keep mine after it, a
  second quit while one waits) and `QuitDialogTest` (offscreen: the texts, each button, Enter and Esc). The view
  models' part (`saveBeforeQuit`, `AppViewModel.quit`) ran against a real repository through the Dagger graph in a
  temporary harness: Cancel, Discard, a save refused because the file changed on disk (Leaf stays and selects the tab,
  and Keep mine then writes the file without quitting), and Save and quit.
  - **Mutation check:** 12 mutations, all caught once the Keep mine test had two files. With one, a save that ended
    after the quit was cancelled could still have gone on to save the next file.


## Tooltips stay away from dialogs, menus and clicks (branch `fix/scrollbars-tooltips`)

- **Before:** after choosing a menu item that opens a dialog, the row under the pointer, beneath the dialog, showed
  its tooltip as the pointer moved: a dialog takes the pointer only inside its box. A right-click's tooltip came back
  with the next move too: Compose's `TooltipArea` hides a tooltip on a press, then shows it again at the next move.
- **Now** (`TooltipLayers.kt`): a dialog is a layer over the window, and only the topmost layer shows tooltips, so a
  dialog's own still do. A press, left or right, hides an element's tooltip until the pointer leaves it, as on macOS
  and Windows. Both tooltip components follow it, so every tooltip in Leaf does. Menus needed nothing: they're
  focusable popups, which already keep the pointer from what's beneath them.
- **Tests:** `TooltipLayersTest` hovers offscreen: an instant and a delayed tooltip beneath a layer and in one, after
  a click and after leaving, and a dialog, a context menu and a header menu covering them.
  - **Mutation check:** 10 mutations, 8 caught. The two that survived took the layer out of the context menu and the
    header menu, and showed that they didn't need one, so menus aren't layers.

## The cleanup dialog's lists show that they scroll (branch `fix/scrollbars-tooltips`)

- **Before:** with many worktrees, "Clean up merged worktrees" cut its list off at 300 dp and its commands at 160 dp,
  and nothing showed that they scrolled.
- **Now:** both have a scrollbar, in the colours of Leaf's other scrollbars, which shows only when they scroll: inside
  their right edge, the commands' inside their box, the two in line. They stay as tall as their content below the
  limit.
- **Tests:** in `CleanUpWorktreesDialogTest`, a list of 12 worktrees and its commands scroll and end at the same edge,
  and a short list doesn't scroll. It fails without the limit, and with the list only as wide as its widest row, which
  put its scrollbar out of line in a first version. The dialog's other tests caught an earlier try, which crashed:
  Compose's scrollbar can't be asked its intrinsic height.

## The conflict resolver's gutters keep its rows even (branch `claude/resolver-model-f2582e`)

- **Before** (found in the manual run): with theirs taken, ours' gutter stacked its add-after arrow and the cross that
  opens the conflict again on the conflict's first row, which pushed the rows below it down by a button's height in
  all three columns. With ours taken, the two were in different gutters and nothing moved.
- **Now:** the cross goes where the side taken had its arrow: theirs' gutter after taking theirs, ours' otherwise. A
  pick leaves each gutter one button, one-line conflicts included. A gutter that still has two (the base or lines
  typed taken, or a change's ✓ and × next to lines typed) puts the second on the chunk's second row, which is then
  as tall as a button, as the first already is: 4 px over a line, not 24. A one-line chunk still stacks them.
- **Tests:** in `ResolverColumnsTest`, where the cross goes after each pick and which buttons each row shows. In
  `ConflictResolverTest`, the cross where theirs' arrow was with the next line where it was, and the base taken in a
  two-line conflict. 4 mutations, all caught (one by the domain test alone, as no view test has a one-row chunk
  with two buttons). The full suite passes on `179ff537` with these changes: `:app` 264 tests, `:domain` 326,
  `:data` 960, `:common` 7.

## Cleaning up merged worktrees (branch `feat/cleanup-merged`)

- **Before:** worktrees whose work had reached the base were marked "merged" (4.3a), but each had to be removed one at
  a time, with Remove or Discard, and missing ones pruned separately.
- **Now** (Phase 4.3b, `docs/fork/agent-awareness.md`): "Clean up merged worktrees…" removes them in one go, with their
  branches. It's in the Worktrees header's menu, now ⋯ (the base choice moved into it), in a merged row's menu after
  Discard, and in the Actions list, while there's something to remove.
  - The dialog reads the worktrees again, and lists the merged and the missing ones. Checked at first: those that lose
    nothing. Unchecked, with what checking does in amber: changes ("3 uncommitted changes. Checking it deletes them"),
    a change in the last 5 minutes, where an agent may still work, and a lock other than an ended agent session's,
    which is unlocked first. A worktree that a running Claude Code session has locked is listed without a checkbox.
  - "Also delete their branches", on by default, runs `git branch -D`: squash-merged branches fail `-d`. A missing
    worktree's branch with commits of its own stays.
  - The commands show, as in the other worktree dialogs, and the button says what it does ("Remove 3 worktrees").
  - A worktree that an agent changed after the dialog read it gets no `--force`: git refuses it and the cleanup stops,
    losing nothing.
- **Refinements to the design note,** from git 2.54: a missing worktree gets its own `git worktree remove`, which git
  allows, rather than one `git worktree prune`, which would also remove the missing ones left unchecked; and each
  branch goes after its worktree, as git refuses `branch -D` while a missing worktree still has the branch.
- **Tests:** 8 in `WorktreeCleanupTest` (domain): which worktrees are offered and in what order, which start checked
  (changes, unreadable changes, a recent change, locks, an ended and a running session), which branches go, how many
  the menus count, and the steps. 5 in `CleaningUpWorktreesTest` (data), a tab on a repository with squash-merged,
  merged with changes, fresh and missing worktrees: what the dialog offers, removing the checked ones from the
  worktree that goes (the tab switches to main), with changes once checked, a change after the dialog read them that
  stops the cleanup, and a recent change. 5 in `CleanUpWorktreesDialogTest` (app), rendered offscreen, and the row's
  menu item in `WorktreesSectionTest`.
  - **Mutation check:** 20 mutations, all caught.
  - The full suite passes on `7cea9da6` with these changes: `:app` 266 tests, `:domain` 326, `:data` 965, `:common` 7.

## The conflict resolver marks the words each side changed (branch `claude/resolver-model-f2582e`)

- **Before:** the resolver tinted a changed line whole, so seeing what changed in it meant comparing it with the base,
  which is hidden unless Base is on. The part being edited lost the rows' syntax colours.
- **Now** (step 6 of `docs/fork/conflict-resolver.md`, section 11, picked from options and a mockup):
  - The words that each side changed are marked inside its lines, against the base, in a stronger shade of the line's
    colour: `5`, `POST` and `60_000` in a conflict, `subtotal` and `lines` in ours' change. The result's lines keep
    the marks of the side they come from. Whole words, not characters, and a short stretch alike between two changes
    joins them, so `1.2.3` to `1.3.0` marks `3.0`. A line added or changed whole gets none, as in the diff pane.
  - The part being edited keeps the rows' colours for keywords, annotations and comments as it's typed.
  - A chunk of more than 2,000 words gets no marks, as its diff runs while the rows are drawn: the worst case took
    11 ms. Scrolling a 2.5 MB lock file took the same time with marks as without.
- **Found on the way:** the marks first reached the cells through a `CompositionLocal`, and opening another file then
  froze the view in the offscreen harness ("pending composition has not been applied", from the list's scroll in a
  `LaunchedEffect`). The cells take them as a parameter, as they take the syntax highlighter.
- **Tests:** 6 in `ChangedWordsTest` (domain): the mockup's lines, whole words and joining, lines changed whole, line
  endings and tabs, the word limit, and the marks that each cell of ours, theirs and the result gets. In
  `ConflictResolverTest`, the marks drawn in both layouts and their colours, and the field's keyword colour. 2 in
  `EditorColoursTest`.
  - **Mutation check:** 24 mutations, all caught, 3 of them once a test was added: indentation that changed, a line
    found in its own side's chunks when the sides' lines are numbered apart, and the text after a line's last mark.
  - **Harness:** the view model on a demo repository, shot in both layouts, with a conflict taken, while editing, and
    after opening another file, which found the frozen view above. Scrolling timed with marks and without.
  - The full suite passes on `7cea9da6` with these changes: `:app` 263 tests, `:domain` 324, `:data` 960, `:common` 7.

## The conflict resolver's size limit, measured (branch `claude/resolver-model-f2582e`)

- **Before:** text over 10 MB on a side got the choice cards, a guess from step 1. Below it, any text was merged
  however long that took: a 10 MB lock file showed "Loading…" for about 30 s, with a core busy.
- **Now** (step 6 of `docs/fork/conflict-resolver.md`, section 10, which has the measurements):
  - 10 MB stays: below it, a pick, Undo and switching layouts took under 100 ms, and the resolver held under 200 MB.
  - Comparing the versions gets 2 seconds. Past it, the file gets the cards, which say "Too many changes to compare
    here: comparing the versions took over 2 seconds." The merge's comparator reads the clock and stops JGit's diffs,
    so nothing keeps running. Most files take a fraction of it (a 10 MB source file 0.15 s); dense changes, as in a
    lock file, take time that grows with the square of the size. A deleted file's diff on its card stops the same way,
    and the card shows the sizes alone.
  - Opening is twice as fast: the merge diffed each side twice, once in JGit's merge and once for the parts, and now
    keeps JGit's diffs.
  - Opening a saved file with thousands of parts (a 2.5 MB lock file has 3,899) overflowed the stack, which left the
    resolver on "Loading…": finding the picks recursed once per part. It's a loop now.
- **Measured:** three kinds of conflicted text from 1 to 40 MB (generated code, a minified bundle, a lock file), with a
  temporary test that timed each step of the load and a temporary harness that ran the view model offscreen through
  Leaf's Dagger graph: opening, scrolling, picks, Undo, editing, both layouts and Save. Neither is kept.
- **Tests:** in `TextMergeChunksTest`, a merge within its time limit is the merge without one (1,000 random merges per
  algorithm), a 5 MB lock file stops within 500 ms of a 100 ms limit (both algorithms), and a merge diffs each side
  once. In `GetConflictedFileGitActionTest`, a file that takes too long gets the cards, and the same file within the
  limit opens. In `ConflictPreviewsTest`, a deleted file's slow diff is left out and the versions still show. In
  `ResolverEditingBytesTest`, picks saved in a file with 8,000 parts come back; that test overflowed the stack before
  the fix. In `ConflictResolverTest`, the cards' line for the time limit.
  - **Mutation check:** 17 mutations, 16 caught. The one that survived, the clock not read while hashing lines, showed
    that reading it there did nothing: a diff hashes each line once, first, and spends its time comparing them. The
    check went.
  - The full suite passes on `976c96c5` with these changes: `:app` 249 tests, `:domain` 304, `:data` 950, `:common` 7.

## Worktrees show when they're merged, and when they're idle (branch `feat/stale-markers`)

- **Before:** a worktree whose work had reached the base still showed its `↑ ↓` against the base: a squash-merged
  branch looked unmerged (`↑3 ↓1`). Nothing told an agent's forgotten worktree from one in use.
- **Now** (Phase 4.3a, `docs/fork/agent-awareness.md`):
  - A linked worktree whose work is in the base shows "merged", in green, in place of `↑ ↓`. The tooltip says how: "Its
    commits are in main" after a merge or a fast-forward, "Its changes are in main (squash-merged)" after a squash
    merge. Only a worktree on a branch other than the base and its local branch is marked.
  - A worktree that just started is in the base too, by ancestry, and isn't marked: the branch must have had work of
    its own, a commit made on it that it still has, as its reflog tells. The design note said "moved since the
    worktree started"; a probe on git 2.54 showed that "Rebase onto main" on a fresh worktree, or an agent's
    `git merge main`, moves a branch that had no work. Without a reflog, Claude Code's `CLAUDE_BASE` says where it
    started.
  - A linked worktree in which nothing changed for 14 days (no commit, checkout, staging or edited file) shows
    "idle 3w", in amber, in place of its commit's age, and its tooltip "Nothing changed in it for 21 days". Settings
    → Worktrees → "Mark worktrees untouched for": never, 7, 14, 30 or 90 days. A change there shows at once.
  - Cost: `git merge-tree` runs once per base commit and branch commit, not on every refresh. `git status` runs again
    only for a worktree whose git dir hasn't changed for a week.
- **Tests:** 6 in `StaleWorktreesTest` (domain): the reflog messages of git and JGit that count as work of its own and
  those that don't, which worktrees are marked, the settings' days and their floor of 7, and the rows. 5 in
  `StaleWorktreesTest` (data), from real worktrees: fast-forwarded, merged, squash-merged, rebased then merged
  (merged); fresh, unmerged, reset away, rebased while fresh, caught up with main, detached and main (not merged);
  `CLAUDE_BASE` without a reflog; merge-tree run once per pair; idle by the git dir's and a changed file's times, and
  `git status` left out while the git dir is recent. `WorktreesTest` reads `CLAUDE_BASE`. 4 in
  `StaleWorktreeMarkersTest` (app): "merged" and "idle" in place of `↑ ↓` and the age, "missing" over "idle", and the
  tooltips.
  - **Mutation check:** 20 mutations, all caught.
  - `./gradlew test` passes, with 1,536 tests (259 in `:app`, 952 in `:data`, 318 in `:domain`, 7 in `:common`).

## Worktrees show which agent's tool made them (branch `feat/agent-awareness`)

- **Before:** an agent's worktree was a folder name like any other. A worktree that Claude Code had locked said
  `Locked: claude session fix-login (pid 48472 start …)`, whether the session still ran or had ended long ago.
- **Now** (Phase 4.1 and 4.2, `docs/fork/agent-awareness.md`):
  - A linked worktree that an agent's tool made gets a small tag after its folder name: `Claude`, `Codex`, `Cursor`,
    `OpenCode`, `Conductor`, `Copilot`, `Gemini`, `Cline` or `Junie`, in the side panel and the toolbar's picker. A
    branch's badge shows it where the worktree's name would repeat the branch, as agents' names do (a
    `worktree-<name>` branch, as Claude Code's CLI names it, counts as repeating its folder's name). The tooltip says
    who made it and how Leaf knows. The side panel's filter finds worktrees by their tag.
  - Leaf knows from the file the tool leaves in the worktree's git dir (Claude Code's `CLAUDE_BASE`, the Claude desktop
    app's `claude-desktop-worktree`, Codex's `codex-thread.json`), wherever the worktree is, or else from the folder
    the tool puts its worktrees in. Two of the markers aren't documented: a probe run of Claude Code 2.1.287 and this
    repository's own worktrees showed them.
  - Settings → Worktrees → "Worktree labels": the user's own rules, a label and a folder pattern each (`*`, `**`, `?`,
    `~/`, `<repo>`, `<repo-name>`), tried before the markers and the built-in folders, which the list shows after them.
    A pattern that starts with `**` matches anywhere. The manual run found that `**/aider-*` was read as inside the
    repository, as a pattern without a start is, and missed a worktree next to it.
  - A lock that a Claude Code session holds says whether the session still runs: the pid and its start time are
    checked as Claude Code checks them (`LC_ALL=C TZ=UTC ps -o lstart=`, found in its binary). The tooltip and the
    Remove and Discard dialogs say so in place of the raw reason, and the box reads "Remove it although its Claude
    Code session is still running" while it runs.
  - 4.2 needed no code: Leaf already copies the files `.worktreeinclude` lists, as Claude Code does (3.2). The note
    says which tools set up worktrees their own way.
- **Tests:** 8 in `WorktreeOriginsTest` (domain): the patterns, the order of rules, markers and folders, the main
  worktree, real paths, the filter, the lock's reason and the stored rules. 4 in `AgentWorktreesTest` (data): the
  session's process, a pid that another process got later, and the JVM's start time against `ps`'s. 1 new in
  `WorktreesTest` reads the markers and a lock from real worktrees. 7 in `AgentTagsTest` (app): the tag's place,
  the tooltips, the badge, and adding and removing rules in Settings.
  - **Mutation check:** 17 mutations, all caught, the last for `**`. Two survived at first: the filter's test used a folder that already
    said "codex", and the tag's place was measured from the name's node, which widened with it. Both tests were fixed.
  - `./gradlew test` passes, with 1,521 tests (255 in `:app`, 947 in `:data`, 312 in `:domain`, 7 in `:common`).

## The conflict resolver shows a card per side for what three panes can't show (branch `claude/resolver-model-f2582e`)

- **Before:** a deleted file, a binary, a Git LFS file, a submodule, a symbolic link or text over the size limit got a
  sentence and "Use ours" / "Use theirs", which asked first and staged at once. Nothing showed what each side had. A
  file renamed differently on each side was three conflicts, which the resolver sent to the terminal.
- **Now** (step 5 of `docs/fork/conflict-resolver.md`, section 9):
  - A card per side, ours left and theirs right, says what that side has. A click on a card, or ← and →, picks it, and
    Mark resolved (⌘↩) stages the pick, as for text: no dialog, and the same pause and Next file follow.
  - A file that one side deleted: the other side's card shows its diff against the base, with +/− counts. Keep stages
    the working file as it is, as `git mergetool` does, so edits made since the merge stay; a note says when there
    are some. "Use theirs" used to check theirs out over them.
  - Binary: each side's size; images also as an image with their size in pixels; "Open in the default app" for any.
  - Git LFS: each object's id and size, and the image or "Open in the default app" once it's downloaded.
  - Submodule: each commit's subject, author and date, which contains the other, and what the submodule has checked
    out. Symbolic link: each target, and whether it points to something. Too large: each side's size and line count.
  - Base adds the base's card between the sides.
  - Mark resolved is on the card picked, next to the pick, and once the file is resolved that button is Next file, in
    the same place. A text file's Mark resolved, in the toolbar, turns into Next file in place too. There's one Next
    file, where Mark resolved was; the strip says what ⌘⏎ opens. Picking, resolving and moving on used to cross the
    window three times, and a first try at this showed three Next file buttons (both found in the manual runs).
  - A file renamed differently on each side is one entry of the list, and opens as one from any of its paths: a card
    per new name, and "Keep both names". git merged the content into both names, so only the name is left; Leaf runs
    the `git rm` and `git add` that git suggests. When the content conflicts too, the name picked becomes a conflict
    of its content and opens next in the three panes.
  - Other conflicts at several paths, such as a file against a folder (`d~HEAD`), get a card per side: keep the file
    there, or leave it out.
- **Tests:** 18 in `ConflictPreviewsTest` (data), each kind from real merges: a submodule with its own repository, the
  LFS store, CRLF, a `filter` attribute, and the hunks checked against `git diff`. 15 in `RenamedFilesTest`: pairing
  one or two renamed files, content that conflicts or that git rewrote too much to match, a blob that two paths share,
  a file against a folder, a rebase, each name kept, the content conflict's stages and markers, markers fixed or
  edited by hand, a missing name, no theirs' commit, and a stale group. 2 in `WriteConflictVersionGitActionTest`, 3 new
  in `ResolveConflictGitActionTest` (Keep, a missing working file, a folder in its place), 1 in `FileSizesTest`, and 16
  new in `ConflictResolverTest` for the cards, the renamed file, the list and the buttons in place, in place of the
  panel's 2.
  - Reading the cards over found that a card said "The file is gone" whenever the preview was missing, which reading
    it may leave; the index now says which side deleted the file. A submodule that one side deleted is described as
    deleted too.
  - **Mutation check:** 61 mutations, all caught: 28 of the previews, Keep and the copy for opening (`:data`), 2 of the
    sizes (`:domain`), 28 of the cards and the toolbar (`:app`), and 3 of the view model through the harness. Three
    checks went before the run, as nothing could tell them from what follows them: a link or LFS pointer kept skipped
    the diff its card doesn't show, a deletion was checked for a base it always has, and Mark resolved checked the
    pick's kind, which only cards can make.
  - Renamed files and the buttons in place: 31 mutations. 29 caught, four of them once a test was added (a blob that
    two paths share, a rebase, a lone opening marker, a resolved card's click). One filter went, as the lookup after it
    already kept renames alone. One survives because the next check raises the same error: a commit that isn't
    theirs has no file at theirs' name either.
  - `:app:test` passes with 238 tests, `:domain:test` with 285, and `:data`'s conflict tests with 165.
- **Harness:** the view model and the keys against a real repository with one conflict of each kind: the image,
  binary, deleted, link, submodule, LFS, too-large and text files, two renamed files (one with conflicting content) and
  a file against a folder. ← and → picking, Cmd+Enter doing nothing without a pick, then staging the right blob, link,
  commit or deletion; the card's Mark resolved, then its Next file; an edited working file staged as it is; the
  submodule's checkout left alone; a renamed file listed once and opened from a new name, theirs' name staged, and a
  name with conflicting content opening in the three panes; and a resolved file taking no picks.

## The banner opens the merge tool on every conflicted file (branch `feat/worktree-mergetool`)

Phase 3.6 of `docs/fork/PLAN.md`, as designed in `docs/fork/worktree-lifecycle.md`. Most of it came with the conflict
work of 2026-10-12: "Open in external merge tool" on one file, in its menu and the diff's conflict bar.

- **Before:** with Settings → Conflicts on External merge tool, the banner's "Resolve conflicts" opened the tool on the
  first conflicted file only. The next file took another click, after the status refreshed.
- **Now:** it opens the tool on every conflicted file that the tool can take, one after another, in path order, as
  `git mergetool` does in a terminal, in one task with Cancel. The processing screen says `meld: src/app.txt (2 of 3)`.
  - Closing the tool without saving a file stops there, as `git mergetool` does when it can't ask whether to go on.
    `ErrorDialog` names the file and how many files were resolved before it, and the next click starts again from it.
  - Files that the tool can't take (a deletion, a symbolic link, a submodule, a name with `*`, `?` or `[`) wait for
    the next click, which opens them in the built-in resolver. A file that something else resolved meanwhile is
    skipped.
  - With the built-in resolver picked, or no merge tool set, the button opens the resolver, as before.
- **Kept as it was:** with no `merge.tool`, "Open in external merge tool" stays enabled, and a click explains in
  `ErrorDialog` how to set one, with the command to copy. The design said disabled items with a tooltip.
- **Tests:** 15 new.
  - `:domain` (10): `ResolveInTurnTest`: the turns, the files skipped, and where it stops, with the count before;
    `ConflictsToOpenTest`: what the banner opens with either setting, and once the tool can take none of the files.
  - `:data` (5): `ResolvingConflictsInMergeToolTest` runs the files that `conflictsToOpen` picks from a tab's status
    through a fake tool: every file it can take, a file closed without saving, markers left by a trusted tool, a file
    resolved meanwhile, and the processing screen's count while the tool is open.
  - **Mutation check:** 14 mutations, all caught.
  - `./gradlew test` passes, with 1,448 tests (234 in `:app`, 904 in `:data`, 303 in `:domain`, 7 in `:common`).

## Rebase a worktree onto the base (branch `feat/worktree-rebase`)

Phase 3.5 of `docs/fork/PLAN.md`, as designed in `docs/fork/worktree-lifecycle.md`.

- **Before:** bringing an agent's branch up to date with main took a terminal in its worktree: `git rebase main`. Leaf's
  own Rebase rebased the tab's branch, so it worked only from the agent's tab, with JGit.
- **Now:** "Rebase onto main…" follows the merge items in a worktree's menu (for a worktree on a branch other than the
  base and its local branch). The dialog says how many commits it replays on how many new ones, warns about an agent's
  recent change, and shows the commands. On confirm, `git rebase --no-autostash main` runs in that worktree:
  - when it ends, the tab stays where it was;
  - when it stops at conflicts, the tab switches to that worktree and shows them, with the banner.

  It's refused, with the reason, when the worktree has changes to tracked files (Leaf doesn't stash an agent's work in
  progress, and `--no-autostash` keeps a user's `rebase.autoStash` from doing it either), and when the branch already
  has every commit of the base. The base is rebased onto as chosen, `origin/main` too, and named in full when a tag
  has its short name.
- **Fix found on the way:** Continue, Skip and Abort ran JGit, which can't go on with a rebase that git started from a
  terminal, an agent, or now this dialog: it committed git's message file with its `# Conflicts:` lines, and its
  continue, skip and abort left `MERGE_MSG` behind, which a commit from a terminal would take as its message. git
  can't go on with JGit's either. So a rebase that git started now goes on with `git rebase --continue`, `--skip` or
  `--abort`, and Leaf's own rebases stay with JGit. Continue and Skip also say when the rebase stopped again at a
  later commit's conflicts, rather than "Rebase continued".
- **Tests:** 37 new.
  - `:domain` (9): `WorktreeRebaseStepsTest`: which worktrees get the item, the steps with and without the tab's
    switch, the commands' comment, and every refusal.
  - `:data` (18): `RebasingWorktreesTest` runs the dialog's steps in a tab on a repository made with git: a rebase
    that ends with the tab where it was, one that stops at conflicts and switches the tab (or stays, from the branch's
    own tab), Continue that ends or stops again, Skip and Abort through git, a rebase that JGit started going on with
    JGit, a worktree with changes, changes made after the dialog checked with `rebase.autoStash` on, a branch that is
    up to date, a tag named like the base, and a remote base. `RebaseStartedByGitTest` tells git's rebases (both
    backends) from JGit's (plain and interactive). `GitOnlyConfigValuesTest` now checks what JGit's continue returns.
  - `:app` (10): the dialog, rendered from its state (`RebaseWorktreeDialogTest`), the menu item, and when the tab
    shows the uncommitted changes.
  - **Mutation check:** 22 mutations, all caught. Two survived at first, and their tests got stricter: the test tab
    now has a user's `GIT_EDITOR` (git fell back to vi, which quit at once without a terminal), and Skip and Abort must
    leave no `MERGE_MSG`. A third didn't compile, and was rewritten.
  - `./gradlew test` passes, with 1,433 tests (234 in `:app`, 899 in `:data`, 293 in `:domain`, 7 in `:common`).

## The conflict resolver edits the result in place (branch `claude/resolver-model-f2582e`)

- **Before:** the resolver only picked: a side, both, the base, or a change applied or not. A conflict that needed
  parts of both sides, or a fix next to it, went to an editor or the external merge tool.
- **Now** (step 4 of `docs/fork/conflict-resolver.md`, section 8):
  - A double-click on a line of the result, its Edit button, "Edit these lines" in its menu, or Enter for the current
    conflict turns that part of the result into a text field, in place, in both layouts. Ours and theirs get fillers
    as it grows, so the rows stay lined up. An open conflict starts from the base's lines.
  - Esc, or a click anywhere else in the resolver, finishes. The part's lines are then the typed ones, marked ✎; a
    conflict counts as resolved. A text that didn't change leaves the part as it was.
  - Saved with the file's charset and line endings: CRLF stays CRLF, tabs stay tabs (the field shows them as four
    spaces, as the rows do), and an ISO-8859-1 file stays ISO-8859-1.
  - Undo and Redo, in the toolbar and with ⌘Z and ⇧⌘Z, go back and forth through the picks and the edits of the file.
  - Runs of unchanged lines are edited in parts of 200 lines at most. The list stays still while a part turns into its
    field and back, also when the part starts above the top of the list.
  - Closing the tab, switching it to another worktree from the picker, or opening another repository in it now asks
    first when the file has changes that aren't saved. They used to drop the picks.
  - Mark resolved can be clicked while typing in the last open conflict: the click finishes editing, then marks.
  - A file saved from the resolver opens again as it was saved: its picks, the lines typed, and the conflicts still
    open. It used to open as git's merge left it, without what was saved, and Save would have written over it (found
    in the manual run: a line typed with the conflict still open was gone when the file was opened again). Only a
    state that writes the file byte for byte is kept.
- **Tests:** 13 in `ResolverEditingTest` (domain): the parts, what editing starts from, typed lines in the rows and
  their numbers, line endings, charsets, and giving a part's bytes back unchanged. 4 in `ResolverEditingBytesTest`
  (data): what Save writes; 2,000 random merges (CRLF, ISO-8859-1, missing last line endings) where editing a part
  without changing it writes the same file; 2,000 random saved states, picks and lines typed, found again in their
  bytes; and lines saved where a change meets unchanged lines. 3 in `GetConflictedFileGitActionTest`: a saved file
  with a pick, a conflict open and a line typed; the same through `core.autocrlf`; and markers edited by hand. 16 new in `ConflictResolverTest`: starting with a double-click and Edit,
  the field, its line numbers and the rows lined up around it, typing and a press outside, the legend, the gutter of
  an edited part, Undo and Redo, the stacked layout, the list kept still, a resolved file, and Mark resolved while
  typing.
  - The random test found that a typed part ending without a line ending, followed by more lines, got LF in a CRLF
    file. `TypedLines` now keeps its line ending.
  - **Mutation check:** 33 mutations of the model, the bytes written and the view: 29 caught. Two survived in code that
    turned out redundant, which went. Two survive because a resolved file is kept from editing twice over: its hover
    is off, and its presses stop before the rows. 8 more of opening a saved file: 7 caught, and one survived in a
    check that the byte comparison already makes, which went.
  - `:app:test` passes with 225 tests, `:domain:test` with 284, and `:data`'s conflict tests with 122.
- **Harness:** the view model and the tab's keys against a real repository in the middle of a merge: Enter, typing,
  Esc, Cmd+Z and Shift+Cmd+Z, a press outside, Cmd+S and Cmd+Enter, the bytes written for a Kotlin file, a Makefile
  with tabs and a CRLF file, the prompt when leaving with typing, one click on Mark resolved while typing, and a line
  typed, saved with the conflict open, and found again after closing and opening the file.

## Two Leafs built from different sources run side by side (branch `claude/inspiring-bouman-f4c4e9`)

- **Before:** `App.initNativeDependencies` (upstream's) copied the Rust library to `<app data>/tmp/libleaf_rs.dylib`,
  writing into the file that was there. When another Leaf had it loaded, macOS killed the Leaf that was starting, in
  `dlopen` (exit 137, `SIGKILL (Code Signature Invalid)`, no log line). So two dev Leafs from different worktrees
  couldn't run at once (each cargo build embeds its path), and a new version of the packaged app started while an old
  one runs could hit the same.
- **Now:** `extractNativeLibrary` (fork-only, `NativeLibraryExtraction.kt`) copies it to
  `<app data>/tmp/leaf_rs-<first 16 hex digits of its SHA-256>/libleaf_rs.dylib`, as `AskpassHelper` does for
  `leaf-askpass`.
  - A file already there is used when its size and hash match.
  - Otherwise the library goes to a temporary file next to it, which is renamed into place: a new file, never new bytes
    in a file that a process may have loaded. When the rename fails because another Leaf put the file there meanwhile
    (Windows can't replace a loaded DLL), that file is used.
  - The file keeps its name, as Windows matches an already loaded DLL by its file name when JNA loads `leaf_rs`.
  - The old `tmp/libleaf_rs.dylib` is left alone, for older Leafs.
- **Tests:** 10 in `NativeLibraryExtractionTest`: the folder's name, the same bytes giving the same file without
  writing it again, other bytes giving another folder, a file in the way (same size or truncated) replaced while a hard
  link to it keeps its old bytes, the old file left alone, no temporary file left behind, a failed rename with and
  without another Leaf's file in place, and 8 threads extracting at once.
  - **Mutation check:** 5 mutations (writing in place, checking the size only, no fallback after a failed rename,
    keeping the temporary file, always writing), all caught.
  - `:app:test` passes, with 208 tests.
- **Manual check (macOS 26):** a dev Leaf built from `main` at another path (other bytes, old code) and one from this
  branch, started in both orders, then two builds with the fix: they all stayed up, each mapped its own file, and the
  file watchers of both kept refreshing. Rewriting the old Leaf's file in place and loading it, as the old code did, got
  `SIGKILL (Code Signature Invalid)`.
- Unchanged: on macOS, JNA extracts its own copy of the library to `~/Library/Caches/JNA/temp`, and uniffi calls that
  one, so the library is loaded twice.

## The conflict resolver waits after Mark resolved (branch `claude/resolver-model-f2582e`)

- **Before:** Mark resolved opened the next conflicted file at once. The view showed "Loading…" for a moment, then the
  next file, and nothing said that it had moved on, or to which file. In Focus, without the file list, that was a
  blink.
- **Now:**
  - The resolved file stays, dimmed and read-only (it still scrolls), with "✓ Resolved" in the toolbar in place of
    Save and Mark resolved. A strip says "src/OrderService.kt is resolved and staged." and has "Next file:
    docs/guide.md (⌘⏎)". The button, or ⌘⏎ again, opens it. After the last file, "All conflicts resolved" shows as
    before.
  - A file that something else resolved (a terminal, an agent, the external merge tool) waits the same way: it "has no
    conflicts any more".
  - Opening the next file keeps the last one on screen while it loads, fading out, with clicks going nowhere, and fades
    the next one in (90 and 210 ms). A load that takes over 400 ms says "Loading…" over it.
  - In Focus, "1 of 3 files" next to the file menu counts the files, those resolved here first.
  - A first try moved on by itself, with a strip naming the file it moved to. In the user's manual run the files still
    changed too fast to follow, so the resolver now waits, as IntelliJ's and VS Code's merge tools do.
- **Tests:** 3 new in `ConflictResolverTest`, and one changed in `ConflictResolverStatusTest`.
  - A resolved file: the strip and its two wordings, "Resolved", no Save, Mark resolved or External merge tool, no
    clicks on the arrows while the list still scrolls, the Next file button, and the dimming, from the pixels'
    contrast before and after it. None of it once another file shows.
  - The counter in Focus only, and not for one file, before and after the file shown is resolved.
  - The next file's load, which keeps the last file on screen with its clicks going nowhere, fades it out and the next
    one in (checked by the pixels' colours), and says "Loading…" only after a while.
  - `afterStatus` gives `Resolved` with the first conflicted file next, in place of opening it.
  - **Mutation check:** 14 mutations of the pause, the strip, the counter and the fades, all caught. An earlier round
    on the loading cover (5 more) caught all but the cover's `consume()`, which changes nothing: the file under the
    cover gets none of its events anyway. The fades were only caught once the tests read pixels, as the semantics tree
    has no opacity.
  - A temporary harness ran the view model from the real Dagger graph on a demo repository with two conflicted files:
    in Focus, picking every conflict and Mark resolved left the file on screen with the strip, "Resolved" and "1 of 2
    files", a pick then changed nothing, and Mark resolved again opened the next file. The view model's guards
    (picks, Save and leaving while paused) were only checked there, as its use cases are final classes that MockK
    can't mock.
  - `:app:test` passes, with 190 tests.

## Closing the conflict resolver keeps the shortcuts working (branch `claude/resolver-model-f2582e`)

- **Before:** the resolver takes the focus when it opens, for its own keys (F7, Cmd+Enter, ⇧⌘F). Once it closed, by
  Close, Esc, a worktree switch or with nothing left to resolve, nothing in the tab had the focus, so Esc, F5 and the
  tab's other shortcuts did nothing until a click. Closing file history or blame had the same gap (the entries below).
- **Now:** the tab takes the focus back whenever the resolver closes, however it closed.
- **Tests:** none kept, for the reason given in the Esc entry below. A temporary harness rendered `RepositoryOpenPage`
  from the real Dagger graph on a demo repository, and listed the focused nodes: after Close and after Esc, none before
  the fix, and the tab's after it. `:app:test` passes.
## Squash-merge or merge a worktree into the base (branch `feat/worktree-merge`)

Phase 3.4 of `docs/fork/PLAN.md`, as designed in `docs/fork/worktree-lifecycle.md`.

- **Before:** bringing an agent's branch into main took a terminal: `git merge --squash agent1` in main's worktree,
  then a commit message to write. Leaf's own merge merged the clicked branch into the tab's branch, so it worked
  only from the tab that had main.
- **Now:** "Squash-merge into main…" and "Merge into main…" follow "Review changes" in a worktree's menu. The dialog
  says how many commits the branch brings, what may stop the merge, and shows the commands. On confirm:
  - the tab switches to the worktree that has main checked out (or checks main out in its own worktree when none
    has it, and that worktree has no changes to tracked files);
  - `git merge --squash agent1`, or `git merge --no-ff --no-commit agent1`, runs there;
  - for a squash, Leaf writes its own message to `SQUASH_MSG`: a single commit's message, or the branch's name and
    one `- subject` line per commit. The commit field shows it, and a commit from a terminal gets it too;
  - the tab shows the uncommitted changes, where the result waits. The banner says that a squash merge is staged, or
    stopped at conflicts, with Abort (`git reset --merge`, which keeps unstaged changes). A plain merge goes on in
    the existing merge UI, with git's "Merge branch 'agent1'".

  It's refused, with the reason, when there's nothing to merge, when main's worktree has staged changes or conflicts
  (`git merge --squash` takes staged changes into the squash without a word), when a worktree rebases main, and for a
  squash, when the branch's changes are already in main. An agent that changed its worktree in the last few minutes,
  and changes that the merge leaves out, get a warning.
- **Fixes found on the way:**
  - After Leaf's commit, Leaf removes `SQUASH_MSG`, `MERGE_MSG`, `MERGE_MODE` and `AUTO_MERGE`, as git's commit does
    and JGit's doesn't. A `MERGE_MSG` left by a squash that had conflicts was the next terminal commit's message.
  - A commit clears the commit field it committed from. It used to clear the field shown after the commit's
    refresh, the plain draft after a merge, so the next merge showed the old merge message. A merge's or a squash's
    field is also cleared once its file is gone.
  - Commit is disabled while files have conflicts, as git refuses then. JGit's commit failed with an error.
  - git exits with 1 both when a merge stops at conflicts and when it refuses a squash merge, so a merge counts as
    stopped at conflicts only when the index has them. With a tag named like the branch, `git merge agent1` merges
    the tag, so Leaf names the branch in full then.
- **Tests:** 44 new.
  - `:domain` (21): `WorktreeMergeStepsTest`: the target (a local or remote base, a remote name with a `/`), which
    worktrees get the items, every refusal, the steps in each case, the squash message, and when a squash merge is
    in progress.
  - `:data` (15): `MergingWorktreesTest` runs the dialog's steps in a tab on a repository made with git: a squash from
    the branch's tab, Leaf's commit ending it, a squash that stops at conflicts and its abort, a commit after
    resolving them, a plain merge and its commit, a merge that git refuses, checking main out first, and a tag named
    like the branch. `CleanUpAfterCommitGitActionTest` and `UnmergedPathsTest`.
  - `:app` (8): the commit field's source, the banner and its abort for a squash, the menu items, and when the tab
    shows the uncommitted changes.
  - **Mutation check:** 18 mutations, all caught.
  - A harness drove a real tab, through Dagger, through the dialog, a squash, editing the message (saved to
    `SQUASH_MSG`), the commit, a second squash, abort, a squash with conflicts and a plain merge, and rendered the
    dialogs and the status pane. It found the stale merge message above.
  - Manual run on macOS by the user, against a demo repository with three agents' worktrees: a squash merge that
    stopped at a conflict, resolved with "Use theirs", its message edited and committed (one commit, Leaf's message,
    and no `SQUASH_MSG` or `MERGE_MSG` left); a squash merge that stopped at conflicts and was aborted (main clean);
    a plain merge, committed as "Merge branch 'agent3'" with two parents; and the dialogs of a branch already
    squash-merged. Two dev Leafs from different checkouts couldn't run at once: macOS killed the second at start, as
    both load the Rust library from one file in `leaf-dev/tmp`. That's for its own fix.
  - `./gradlew test` passes, with 1,223 tests (165 in `:app`, 802 in `:data`, 249 in `:domain`, 7 in `:common`).

## The conflict resolver's Focus (branch `claude/resolver-model-f2582e`)

- **Before:** the resolver took the tab, but Leaf's tab bar, the repository's toolbar, the operation banner, the
  bottom bar and the file list stayed around it.
- **Now:** Focus hides them, for reviewing with the whole window. The button at the right end of the resolver's
  toolbar, or ⇧⌘F (Ctrl+Shift+F on Windows and Linux), turns it on and off.
  - The resolver's toolbar and legend stay. The file name in the toolbar becomes a menu of the conflicted files, as
    the file list is hidden.
  - Esc leaves Focus first; a second Esc closes the resolver. Closing it shows everything again.
  - Focus is kept until Leaf quits, so the resolver opens in it again.
  - The window itself doesn't go full screen; macOS's own full screen works with Focus.
- **Tests:** 3 new in `ConflictResolverTest`: the file list hidden and the file menu switching files (a file resolved
  here is disabled), the Focus button both ways, and the shortcut on each platform. 7 mutations, all caught. A
  temporary harness ran the repository page from the real Dagger graph: Focus hid the toolbar, the banner and the
  bottom bar and asked `App` to hide the tab bar, Esc left Focus and then closed the resolver, and closing it while in
  Focus brought everything back.
  - `./gradlew build` passes, with 1,262 tests (159 in `:app`, 850 in `:data`, 246 in `:domain`, 7 in `:common`).

## The built-in conflict resolver's Stacked layout (branch `claude/resolver-model-f2582e`)

Step 3 of the build order in `docs/fork/conflict-resolver.md`: Option B, and the switch between the two layouts.

- **Before:** the resolver showed ours, the result and theirs in three columns, a third of the width each, so long
  lines wrapped.
- **Now:** a toolbar switch, Columns or Stacked, and "Resolver layout" in Settings → Conflicts (Columns by default),
  one setting for the whole app. Stacked puts ours and theirs side by side on top and the result below at the full
  width, which suits a narrow or portrait screen.
  - The top pair lines up as the columns do. Each chunk's first row ends with chips in place of the arrows: "Take ↓",
    then "Add ↓" once the other side is taken, and "Taken" once a side's lines are in the result, which takes them
    out again. A change one side made is "Applied", which undoes it, or "Apply".
  - The result's lines have a bar on their left in the colour of where they come from: blue for ours, purple for
    theirs, green for a resolved conflict. An open conflict is the hatched placeholder, or the base's lines with Base,
    and keeps its right-click menu. Deleted lines leave a thin line across.
  - The top pair follows the result, chunk by chunk, and shows the conflict that F7 moves to.
  - A handle between the two parts resizes them (45% for the top at first, double-click to reset). It keeps its place
    until Leaf quits.
  - Switching keeps the picks, the current conflict, Base and the place in the file.
- **Widths and long lines,** in both layouts:
  - The Columns layout's gutters, in the headers and in every row, drag the edge between their column and the result.
    The file list's edge drags too. A double-click puts either back.
  - "Wrap lines" in the toolbar, on by default. Turned off, each line stays on one row and every column scrolls sideways
    together, as in Meld: swipe sideways, use Shift and the wheel, or drag the scrollbar under the lines. The line
    numbers and the gap before the text stay put.
  - The widths are kept until Leaf quits, as the handle is. Wrap lines is saved for the whole app: Settings →
    Conflicts has it as "Wrap long lines", and the toolbar's toggle changes that setting.
- **Fixed:**
  - double-clicking a file, while the resolver was still waiting for the status, could open the first conflicted file
    instead. The file now loads before the status arrives;
  - the hatching of an open conflict and of the fillers drew past their cells, into the gutter or the file list.
- **Tests:** 24 new.
  - `:domain` (14): `ResolverStackedTest` (10), the top pair's and the result's rows, what the Taken chip does, and
    places mapped between the layouts' rows; `ResolverSizesTest` (4), the columns' widths as the gutters move them.
  - `:app` (10, in `ConflictResolverTest`): the stacked view's headers, chips and legend, where the result is, what the
    chips call, the toolbar's switch, and two scrolling checks over several frames: switching keeps the place, the top
    pair follows the result's line with its offset, and moving to a conflict shows it on top past lines that only the
    top pair has. Then lines without wrapping (one row high, every column moved by a sideways swipe and by Shift and
    the wheel, the list staying put), the Wrap lines toggle, and drags with real pointer events on the gutters in
    the headers and the rows, the file list's edge and the stacked layout's handle, and a double-click.
  - **Mutation check:** 41 mutations, all caught: 17 in the rows, places and widths (`:domain`) and 24 in the view
    (`:app`). The first scrolling test found that the top pair stopped following after its first scroll: it scrolled
    with `scrollToItem`, which throws when it runs during a layout, and the exception ended it without a trace. It now
    uses `requestScrollToItem`. Two mutations of the widths and the sideways scrolling survived at first, a
    double-click that didn't reset and a swipe that let the list drift, which made two tests stricter.
    The first scrolling test found that the top pair stopped following after its first scroll: it scrolled with
    `scrollToItem`, which throws when it runs during a layout, and the exception ended it without a trace. It now uses
    `requestScrollToItem`.
  - **Manual run:** a temporary harness opened a merge with conflicts in a tab built from the real Dagger graph,
    switched to Stacked from the toolbar (the setting was saved), took ours, added theirs, took ours out, undid and
    applied a change, saved, closed and opened the resolver again (Stacked, with the handle where it was left), marked
    the file resolved (stage 0), showed Base on the next file, and switched back to Columns. It found the double-click
    race above. A second run opened a 760-line Kotlin file and a Markdown guide with a 1,544-character line, with and
    without wrapping, scrolled sideways in both layouts, and with a wide result column. Its screenshots showed the text
    scrolling up to the line numbers, which now keep their gap. Hover and the keys in a real window are left to a run
    of the app.
  - `./gradlew build` passes, with 1,259 tests (156 in `:app`, 850 in `:data`, 246 in `:domain`, 7 in `:common`).

## The built-in conflict resolver: ours, the result and theirs side by side (branch `claude/resolver-model-f2582e`)

Step 2 of the build order in `docs/fork/conflict-resolver.md`: the read-only Columns view, from the approved mockup.

- **Before:** a conflicted file could go to the external merge tool, or take one side whole. Double-clicking it staged
  it with its conflict markers.
- **Now:** the resolver takes the whole tab, as file history does, with the conflicted files on the left and three
  columns: "Ours: main", the result, and "Theirs: feature". The headers' tooltip says what ours and theirs mean in the
  merge, rebase, cherry-pick or revert in progress.
  - The rows line up, with hatched fillers. Changes that one side made are in the result already, and can be undone
    (×) or applied again (✓). A conflict waits as a hatched placeholder, or shows the base's lines with Base; ▶ takes
    ours, ◀ takes theirs, and once one is taken the other side's arrow adds its lines after it. Its right-click menu
    has every resolution, the base's lines and opening it again included.
  - The toolbar has the conflict counter with previous and next (F7, Shift+F7), Base, External merge tool…, Save
    (Cmd+S, which is Stash elsewhere) and Mark resolved (Cmd+Enter), which stages the file and opens the next one.
    Esc closes the view.
  - Leaving a file with picks that aren't saved asks in a strip: Save, Discard or Cancel. A file that changed on disk
    since it was opened is noticed when Save writes: Reload, or Keep mine.
  - A file without conflict markers (resolved by hand, an agent or rerere) offers "Mark resolved as it is" or
    "Resolve again". One whose markers don't match the merge says so above the columns.
  - Deletions, binary files, Git LFS pointers, submodules, symbolic links and text over 10 MB get a panel with each
    side, named ("Use ours (main)", "Delete the file, as theirs (feature) did"), and the external tool where it can
    take the file. The cards with previews are step 5.
- **Where it opens:** "Resolve…" first in a conflicted file's menu, a "Resolve…" button in the diff's conflict bar,
  "Resolve conflicts" before Abort on the banner, and double-click on a conflicted file.
- **Settings → Conflicts** (new, under GIT): "Resolve conflicts with" the built-in resolver (the default) or the
  external merge tool, for double-click and the banner. A file that the tool can't take, or a repository without one,
  opens in the built-in resolver.
- **Tests:** 25 new.
  - `:domain` (8): `ResolverColumnsTest`, the rows of the three columns and what the arrows do.
  - `:app` (17): `ConflictResolverTest` (11) renders the view offscreen from a state and clicks it: the headers, the
    counter, the arrows and undo buttons and where they are, Mark resolved while a conflict is open, the panels, the
    unsaved-picks strip, and each operation's explanation. `ConflictResolverStatusTest` (4) checks which file the view
    shows after a status. `ConflictContextMenuTest` and `OperationInProgressBannerTest` (1 each) check the new entry
    points.
  - **Mutation check:** 17 mutations, all caught. The first pass missed two, the deletion panel's wording and an undo
    button in the wrong gutter, which gave the last two tests.
  - **Manual run:** a temporary harness opened a repository with four kinds of conflicts in a tab built from the real
    Dagger graph, double-clicked the Kotlin file, took theirs then ours, saved, marked it resolved (stage 0, then the
    next file opened), left a file with unsaved picks, and opened the binary, deleted and Base views. Its screenshots
    showed the panel's title unreadable on the dark theme, which was fixed. Hover, the keys and the banner in a real
    window are left to a run of the app.
  - `./gradlew build` passes, with 1,235 tests (146 in `:app`, 850 in `:data`, 232 in `:domain`, 7 in `:common`).

## The built-in conflict resolver's model (branch `claude/resolver-model-f2582e`)

Step 1 of the build order in `docs/fork/conflict-resolver.md`: what the resolver loads, merges and writes, with its git
actions and use cases. No UI yet.

- **Loading a conflicted file** (`GetConflictedFileGitAction`, `ConflictedFile`): its versions from the index (through
  the shared `stagesOf`), which branch or commit ours and theirs are (`ConflictSides`), and what the resolver can show:
  three panes for text, or a choice for rename conflicts, submodules, deletions, symbolic links, Git LFS pointers,
  binary files and text over 10 MB (provisional), checked in `git mergetool`'s order.
- **The merge** (`TextMerge`): JGit's `MergeAlgorithm` with the repository's `diff.algorithm`, the content merge of
  Leaf's own merges, in chunks that each know their lines in the base, ours and theirs. A change that doesn't conflict
  can be applied or not; a conflict can be left open or resolved with ours, theirs, both in either order, the base, or
  typed lines. JGit's chunks only give their lines in one version, and cut a conflict's base oddly (`[1,-1)` for a file
  that both added), so the merge's parts are found again from the two diffs that it walks.
- **The working file** is compared with the merge before the resolver opens it, by counting its conflict markers as
  `git rerere` does: no markers left means someone resolved it already (by hand, an agent or rerere).
- **Save and Mark resolved** (`WriteConflictResultGitAction`, `SaveConflictResolutionUseCase`,
  `MarkConflictResolvedUseCase`) write the result as git writes a merge: each line with its own bytes and line ending,
  open conflicts as markers in `merge.conflictStyle`'s style with git's labels and CRLF where git uses it, the
  `conflict-marker-size` attribute, and a checkout's line ending conversion. They write nothing when the index or the
  working file changed since loading (unless the user keeps the result), or when a folder or a symbolic link is in the
  file's place. Mark resolved is refused while a conflict is open, then stages the file. Four new `ConflictError`
  cases, with their texts.
- **Found on the way:**
  - JGit's formatter can drop a file's last line ending, and writes LF markers into CRLF files. The resolver follows
    git.
  - git joins conflicts three lines apart or less and splits ones with four alike lines in their middle; JGit does
    neither. So a file nobody touched can have another number of conflicts than the merge.
  - JGit's merge threw `IllegalArgumentException` on `merge.conflictStyle=zdiff3` and on `diff.algorithm=patience`
    (also `minimal`, `default`). That was fixed in its own change (`GitCompatibleConfigGetter`, below), and the
    resolver reads `diff.algorithm` as `ResolveMerger` does, so it merges with the merge's algorithm.
- **Docs:** the design note's sections 2 and 4: what was built and found, the requirement that every ours and theirs
  in the UI names its branch or commit, and the Settings → Conflicts entry (built-in resolver or external merge tool,
  Columns or Stacked).
- **Tests:** 72 new, in `:data`.
  - `TextMergeChunksTest` (11): the chunks of hand-made merges, and 5,000 random merges per diff algorithm against
    JGit's own merge (its formatter in both styles, and its OURS and THEIRS strategies), with each version given back
    byte for byte by keeping only its changes. A one-off run of 60,000 per algorithm passed too.
  - `ConflictResultTest` (12) against `git merge-file`, `ConflictMarkerScanTest` (9), `ConflictAttributesTest` (5).
  - `GetConflictedFileGitActionTest` (14) and `WriteConflictResultGitActionTest` (19) on conflicts that git's merge,
    rebase, cherry-pick, revert and stash leave: each kind of conflict, a resolution by `git rerere`, Save giving back
    git's file with nothing resolved (both conflict styles), CRLF with `core.autocrlf`, and each refusal.
    `ResolvingConflictsTest` (2) runs Save and Mark resolved in a `TestTab`.
  - **Mutation check:** 54 mutations, 53 caught. The other can't change a result: a marker on a last line without a
    line ending can only be `=======` or `|||||||`, which can't close a conflict. The first pass missed a base marker
    inside theirs, and two searches for small examples gave the tests for lines alike next to a conflict and for
    Myers' touching edits, which the random merges couldn't tell apart.
  - `./gradlew build` passed with 1,136 tests (129 in `:app`, 776 in `:data`, 224 in `:domain`, 7 in `:common`), and
    `:data:test` and `:domain:test` again after the last three tests (779 in `:data`). No manual run: there's no UI
    yet.

## Review a worktree's changes against the base (branch `feat/worktree-review`)

Phase 3.3 of `docs/fork/PLAN.md`, as designed in `docs/fork/worktree-lifecycle.md`.

- **Before:** seeing what an agent's branch changed took a terminal (`git diff main...agent1`), or clicking through
  its commits one by one in the log.
- **Now:** "Review changes", first in a worktree's menu when it's on a branch other than the base, shows in place of
  Files changed:
  - "agent1 compared with main", with the commits that each has and the other doesn't (`↑2 ↓1`);
  - the commits that the base doesn't have, oldest first, by subject and short hash;
  - the changed files since the branch left the base (`git diff main...agent1`), with Files changed's views and
    sorting. A file opens in the diff pane, like a commit's file;
  - the worktree's uncommitted changes, which a merge leaves out, as a count with a link that switches the tab to the
    worktree.

  It follows the branch: when the agent commits, the review reads it again. Selecting anything else closes it, and
  its close button or Esc go back to what was selected before. Branches without a commit in common, a branch that
  doesn't exist and a branch with nothing new say so.
- **Tests:** 17 new.
  - `:data` (7): `GetBranchComparisonGitActionTest` against a repository whose base moved on after the branch left
    it: the commits oldest first, the counts, the files changed since the merge base and not the base's own, a file's
    diff from the merge base (through the diff pane's `FormatDiffGitAction`), a remote base, a merged branch,
    branches without a commit in common, a branch that doesn't exist, and more than 1,000 commits.
  - `:domain` (2): which worktrees get the item.
  - `:app` (8): `WorktreeReviewTest` runs the review on the tab's data repository: opening it in place of the
    selection, closing it by selecting something else, or with Close, which selects again what was selected,
    reading it again when the branch moves (and not when a refresh finds the same commits), with the last comparison
    shown meanwhile, and the uncommitted changes. `WorktreesSectionTest` checks the menu item.
  - **Mutation check:** 15 mutations, all caught in the end. Two weren't at first: reading the comparison again on
    every refresh, and counting a refresh's Loading state as data. The test's refreshes ended at once, so the data
    never showed Loading; they now take a moment, as git's do.
  - The pane was rendered offscreen in four states (loaded, no commit in common, nothing new while loading, an
    error). That wrapped the title on narrow panes, and put a gap between the review's header and Commits.
  - Manual run on macOS by the user, against a demo repository with an agent's worktree whose base moved on after
    it, a merged branch and a branch without a commit in common. A commit made in the agent's worktree while the
    review was open showed in it within seconds, with the counts and the uncommitted line updated (seen on the
    window). The tab ended on the agent's worktree, through the uncommitted line's link. The user then asked for
    the commits.
  - `./gradlew test` passes, with 1,012 tests (120 in `:app`, 664 in `:data`, 221 in `:domain`, 7 in `:common`).
    After the rebase onto the conflict resolution, file history and Esc changes, which conflicted only in this
    changelog, `:app`, `:domain` and `:common` pass again (392 tests), and so do the worktree and comparison tests
    in `:data` (115).

## File history shows which commit its diff is (branch `feat/file-history-selection`)

- **Before:** a click on a commit in file history showed its diff, and nothing said which commit that was: the list
  had no selected row and the diff no header. In a long list, it was easy to lose track. File history also opened on
  an empty diff, and had no keys to step through the commits.
- **Now:**
  - The list highlights the selected commit's row, in the log's selected color.
  - A strip above the diff names the commit: avatar, subject, short hash, author, date and time, and where it is in
    the list ("3 of 12"). A click on "3 of 12" scrolls the list back to the row.
  - ↑ and ↓, and the strip's two chevrons, select the next newer or older commit, and the list keeps its row in view.
    File history opens on the newest commit.
  - Another commit's diff starts at its top, as in the diff pane. It used to keep the previous diff's scroll position.
  - Each commit's load cancels the previous one and waits for it, so the diff shown is always the last commit
    selected, however fast the keys come. A cancelled load doesn't blank the diff for a moment either.
- **Tests:** `FileHistoryCommitsTest` (positions and either end), `HistoryViewModelTest` (opens on the newest, steps,
  the last commit selected wins, no blank diff, the diff's scroll) and `FileHistoryTest` (rendered offscreen: the
  highlight by pixel color, the strip's text and buttons, the row kept in view over 30 steps down and back up,
  "3 of 12" scrolling back, and ↑ and ↓ on the real `FileHistory`, also after clicking a row or a button). 12 mutations
  of the code were each caught. `:app:test` passes.

## Closing file history or blame with X keeps the shortcuts working (branch `claude/x-button-focus`)

- **Before:** file history and an expanded blame take the focus while shown. Their X buttons closed them and left
  nothing in the tab with the focus, so Esc, F5 and the tab's other shortcuts did nothing until a click. Esc already
  gave the focus back (the entry below).
- **Now:** both X buttons give the focus back to the tab, as closing the diff does. Nothing else needed it: selecting
  a file's diff, which minimizes blame, focuses the file's row, and the minimized blame's X is clicked while the
  focus is elsewhere.
- **Tests:** none kept, for the reason given below. The temporary harness clicked each X: before the fix the next
  Esc wasn't consumed, and after the fix it was. It also clicked a changed file while blame was expanded, which kept the
  focus. `:app:test` passes.

## Esc closes file history and blame (branch `claude/cranky-lamport-ef720e`)

- **Before:** Esc closed neither file history nor an expanded blame. Both have their own Esc handler, but the tab's
  shortcuts (`onPreviewKeyEvent` in `RepositoryOpen.kt`) see every key first, and their EXIT branch consumed Esc
  with `closeLastView()`, which only knows the diff and the searches. So Esc in blame closed whatever was last in
  that list instead, such as the side panel's search next to it. Upstream has the same code.
- **Now:** the tab's EXIT branch closes file history, then an expanded blame, then the last closeable view, as before.
  File history opened over blame takes two presses of Esc. Closing either gives the focus back to the tab, as closing
  the diff does, so the next Esc, F5 or other shortcut works without a click first.
- **Tests:** none kept. The bug was in which handler gets the key, which only shows with the whole tab rendered from
  the real Dagger graph, and that writes the dev prefs node. A temporary offscreen harness, `RepositoryOpenPage` on
  a temp repository, confirmed it before the fix (Esc consumed, both views still open) and after (both close, history
  over blame in two presses, and the next Esc reaches the tab). `:app:test` passes.

## Discard leaves conflicted files alone (branch `claude/amazing-chandrasekhar-6b63e0`)

- **Before:** "Discard file changes" on a conflicted file, "Discard N files" on a selection with one, and a folder's
  "Discard changes" reset it to HEAD's version, which quietly took ours and marked the conflict resolved. Where ours
  had deleted the file, it stayed in the working tree as an untracked file with theirs' content. git itself refuses
  (`git restore`: "path is unmerged"), and "Use ours" did the same thing with the side named.
- **Now:**
  - A conflicted file's menu has no "Discard file changes": "Use ours" and "Use theirs" are right above it.
  - "Discard N files" and a folder's "Discard changes" leave conflicted files as they are, and their dialogs say so:
    "1 file with conflicts is left as it is." N counts the files it discards, as every count in these menus counts
    the files that the item acts on: 4 selected with 1 conflicted gives "Stage 4 files" and "Discard 3 files". A
    selection of conflicted files only gets no Discard, but "Use ours for N files" and "Use theirs for N files".
  - If the list is stale (an agent or a terminal stopped at conflicts after it was read), the discard reads the index,
    refuses with a dialog naming the files, changes nothing, and the list refreshes.
- **Tests:** 10 new, and the one that checked the old behavior replaced.
  - `:data` (3): `DiscardEntriesGitActionTest` discards a folder during a merge and leaves its conflicted file with its
    stages and markers. Given each kind of conflict as a stale status lists it (both changed, deleted by us, deleted
    by them, both added), staged or not, the git action refuses, and the index, the status and every file stay as
    they were. Discarding no files changes nothing: JGit's reset without paths resets the whole index.
  - `:domain` (2): `StatusFileItemsTest`, what a folder's and a selection's discard keep and count.
  - `:app` (5): the menus and their counts (`ConflictContextMenuTest`), the selection's dialog
    (`ConfirmActionDialogTest`) and the folder's (`DiscardChangesDialogTest`).
  - **Mutation check:** 11 mutations, all caught. Not covered by a test: the view model dropping conflicted files from
    the selection. Without it, the git action would refuse the whole discard and say why.

## Merges and diffs with zdiff3 or patience set (branch `claude/fix-jgit-conflictstyle-diffalgorithm-e355f0`)

- **Before:** JGit 7.7's enums lack values that git accepts: `merge.conflictStyle=zdiff3`, and `diff.algorithm` set
  to `patience`, `minimal` or `default`. Its `Config.getEnum` threw "Invalid value" for them, whether they came from
  `~/.gitconfig` or the repository's config.
  - JGit reads both keys in every merge: `ResolveMerger`'s constructors, and the `MergeConfig` that `MergeCommand`
    builds even when it's given a conflict style. So merges, rebases (interactive, continuing and skipping too),
    cherry-picks, reverts, stash applies and both pulls failed before they started. A command's `setConflictStyle`
    comes too late.
  - `DiffFormatter` reads `diff.algorithm` too, so with it set the diff pane, a commit's file list and staging
    untracked files failed as well.
- **Now:** every `Config.getEnum` goes through the one `TypedConfigGetter` that JGit keeps. `main.kt` installs the
  fork-only `GitCompatibleConfigGetter` there (`readGitOnlyConfigValues`), which reads these values as the closest
  ones JGit has:
  - `zdiff3` as `diff3`. JGit's diff3 already leaves the lines that both sides have alike at the start and end of a
    conflict outside of it, as zdiff3 does.
  - `default` and `minimal` as `myers`, and `patience` as `histogram`. git 2.54's merges use `diff.algorithm` (a merge
    stops at `bogus`). On contents where myers and histogram merge differently, git's `default` and `minimal` gave
    myers' merge, and `patience` gave histogram's. JGit's Myers finds a shortest diff, which is what `minimal` asks of
    git's. The brief had suggested histogram for every value JGit lacks.
  - Other values go to JGit's reader, so values that git refuses, such as `bogus`, still fail. `zdiff3` is read in
    any case, as JGit reads `diff3`, where git takes it in lowercase only.
  - Nothing is written to any config. An in-memory override, as `useBuiltinLfs` does, would have needed a dozen call
    sites, could reach `.git/config` through an action's `config.save()`, and would be lost when JGit reloads a config
    file that changed on disk, as an agent's `git config` does.
  - Not fixed: JGit's diff3 leaves out as many lines of the conflict's base as it leaves out of the sides, where git's
    zdiff3 shows them all. It did the same with `diff3` before.
  - The built-in conflict resolver's model (branch `claude/resolver-model-f2582e`) reads `diff.algorithm` as
    `ResolveMerger` does (`mergeDiffAlgorithm`), so that it merges with the merge's algorithm.
- **Tests:** 71 new, in `:data`, on temporary repositories.
  - `GitCompatibleConfigGetterTest` (16): each value, in any case; values that git refuses; other keys, subsections and
    enum types left to JGit's reader. It also checks that JGit's own reader still refuses the values, which fails once
    JGit reads them itself.
  - `GitOnlyConfigValuesTest` (39) and `GitOnlyConfigValuesPullTest` (16) run Leaf's git actions with the setting in
    `.git/config`: merge, rebase, continuing a rebase, interactive rebase, cherry-pick, revert, stash apply, and
    JGit's pull and the CLI pull, merging and rebasing.
    - With zdiff3, each stops at conflicts with diff3's markers in zdiff3's layout. That's the conflict that git's
      zdiff3 merge writes, but for the base's lines.
    - With patience, default and minimal, each merges. A file's diff and a commit's files show.
    - Both settings in the user's config instead stop a merge at zdiff3's conflict.
    - On contents that myers merges and histogram doesn't, in JGit and in git (found by trying random ones), `default`
      and `minimal` merge as `git merge` does, and `patience` stops at git's conflict.
    - The config file is unchanged after each.
  - **Mutation check:** 17 mutations of the getter, all caught. Without it, 62 of the 71 tests fail (the other 9
    check what JGit's own reader does). Each value left out, read as another value or matched in one case only; the
    enum type, the subsection or the key not checked, or keys matched in one case only; and values that git refuses
    read as the default. Reading `default` and `minimal` as histogram fails 5, among them both comparisons with
    `git merge`.
  - `:data:test` passes (716 tests), and `:app` compiles.
  - Manual run on macOS by the user, with a demo repository that sets both in its config: a commit's diff showed, a
    branch merged cleanly, and another stopped at conflicts, with the banner, and the conflicted file's diff showed
    zdiff3's layout.

## Toasts stay long enough to read (branch `claude/zen-volhard-54d7bb`)

- **Before:** every toast went after 2 s, usually before a longer message such as "Merge stopped at conflicts, fix
  them to continue" could be read.
- **Now:** a toast stays 2 s to be noticed plus 60 ms a character, between 4 and 12 s, and a warning or an error at
  least 6 s. While the pointer is over it, it stays (`notificationDuration`, `AppTab`).
- **Tests:** `NotificationDurationTest` (3). Mutation check: 4 mutations, all caught. Hovering isn't covered by a test.

## Conflicts: the external merge tool, ours and theirs (branch `claude/zen-volhard-54d7bb`)

Phase 3, item 6 of `docs/fork/PLAN.md`. Findings about `git mergetool` are in `CLAUDE.md` (Resolving conflicts).

- **Before:** Leaf listed conflicted files and showed them against ours, but couldn't resolve them. The file had to be
  edited elsewhere, then staged. "Discard file changes" on a conflicted file quietly took ours.
- **Now:**
  - A conflicted file's Unstaged menu starts with **Open in external merge tool**, **Use ours** and **Use theirs**. A
    selection of conflicted files gets "Use ours for N files" and "Use theirs for N files".
  - Its diff has a strip under the header, with the same buttons and which branch is which: "Ours: main · Theirs:
    feature/retry". The banner adds the same while files have conflicts, and the buttons' tooltips and the dialog
    name the sides too. In a rebase, ours is `main (onto)` and theirs the commit replayed, with its branch.
  - **Open in external merge tool** runs `git mergetool` with the tool that git picks for `--gui` (`merge.guitool`,
    then `merge.tool`), so the user's Meld or other tool opens with their own settings. The tab waits behind the
    processing screen, "Waiting for the merge tool", with Cancel. It also works in a linked worktree.
  - Leaf adds `-c mergetool.keepBackup=false` and `-c mergetool.writeToTemp=true`, unless the user set them. Without
    them, `<file>.orig` showed up in Unstaged, and git left its `_BASE_`, `_LOCAL_` and `_REMOTE_` copies in the
    working tree whenever it stopped early.
  - Without a merge tool set, Leaf says so and how to set one, and doesn't run git, which would guess one, ask before
    starting it, and leave its copies behind.
  - A conflict that the tool can't take is left to ours and theirs: a deletion, a symlink or a submodule on a side, and
    a name with `*`, `?` or `[`. git asks on a terminal about the first three, even with `--no-prompt`. For the last,
    git-mergetool lets the shell expand the name, and merged `a.txt` in place of `[ab].txt`.
  - When the tool closes without saving (its exit code untrusted and the file unchanged), git asks "Was the merge
    successful?", which reads the end of input: the file stays conflicted, and the error shows what git and the tool
    printed. When the exit code is trusted and the file still has conflict markers, git stages it anyway. Leaf
    then puts the conflict back with `git update-index --unresolve`, keeping the file as the tool left it, and says so
    in a dialog. A tool started with `open -a` on macOS, as the user's Meld was, runs from `/`, can't write git's
    relative path, and exits with 0 all the same.
  - **Use ours** and **Use theirs** ask first. They run `git checkout --ours|--theirs` and `git add`, `git rm` where
    the chosen side deleted the file, and `git update-index --cacheinfo` for a submodule, as `git add` would stage
    the commit the submodule has checked out.
  - Not in this change: double-click and the banner keep what they did. The built-in resolver that another session is
    designing adds them, with a setting to pick it or the external tool.
- **Tests:** 69 new.
  - `:data` (50): `OpenMergeToolGitActionTest` runs `git mergetool` with a fake tool set up through
    `mergetool.<name>.cmd`: a tool that saves, one that closes without saving (exit code trusted or not), one that
    fails, no tool, an unknown tool, a missing one, `merge.guitool` first, the user's own `keepBackup`, `writeToTemp`
    and `prompt`, each refusal, a name with spaces, a linked worktree, markers left (the conflict comes back, and a
    partial resolution stays), and Cancel, which kills the tool.
    `ResolveConflictGitActionTest` compares ours and theirs with git's own commands, deletions and a submodule
    included. `ConflictStatusTest`, `GetConflictSidesGitActionTest` (merges, rebases by git and by JGit,
    cherry-picks, reverts, a stash, a linked worktree) and `MergeToolConfigTest`.
  - `:domain` (5): `FileConflictTest`, which conflicts the merge tool takes.
  - `:app` (14): the menus (`ConflictContextMenuTest`), the side names (`ConflictSideLabelsTest`), the banner and the
    dialog.
  - **Mutation check:** 39 mutations. The first pass missed two: no test set `mergetool.prompt`, which `--no-prompt`
    overrides, and the test of a name such as `[ab].txt` didn't check that `a.txt`'s file was left alone, which
    `checkout` without `--literal-pathspecs` overwrote. Both are caught now.
  - Offscreen, through the real Dagger graph, on a repository with a merge stopped at conflicts: the banner, the
    strip and the dialog rendered, the fake tool resolved one file and "Use ours" the other. The diff header had no
    room for the buttons in a narrow pane, hence the strip.
  - By hand, with the user's Meld: it opened, and once closed, git staged the file with its markers (see the `open -a`
    note above), which is why Leaf now puts the conflict back. The user's git config now uses git's own Meld support.
  - `./gradlew build` passes, with 1067 tests (129 in `:app`, 707 in `:data`, 224 in `:domain`, 7 in `:common`), on
    `main` at `3a7666d9` (creating worktrees), the toast change above included.

## Create worktrees, with the ignored files they need (branch `feat/worktree-create`)

Phase 3.2 of `docs/fork/PLAN.md`, as designed in `docs/fork/worktree-lifecycle.md`.

- **Before:** worktrees came from a terminal or an agent. A fresh one has none of the gitignored files that the main
  worktree has, so a build that needs `local.properties` or `.env` failed there.
- **Now:** "New worktree…" opens a dialog from the Worktrees header's "+", the toolbar picker's last row, a branch's
  menu (side panel and log) and a commit's menu ("New worktree from commit…"). It shows the commands it runs.
  - **Branch:** a new one from the base branch (or the branch or commit it was opened on), named by the user. git
    checks the name as it's typed, and a name that a branch has is refused. A local branch that no worktree uses can
    be checked out instead. A remote branch suggests its name, and the new branch tracks it; a branch made from a
    remote base doesn't.
  - **Folder:** `<repository>-<branch>` next to the repository, or in a folder chosen for the repository, and
    editable. The repository remembers the folder and whether the tab switches to new worktrees. Paths in the home
    folder show as `~/…`.
  - **Ignored files:** Leaf reads the repository's `.worktreeinclude`, the file that Claude Code, Codex and Conductor
    already read, and copies the files that it lists and git ignores, never over a file or through a link. Without
    the file, the dialog says what it's for and creates one with examples.
  - **Switch:** the tab switches to the new worktree, unless unchecked.
- **Tests:** 36 new.
  - `:data` (12): `CreatingWorktreesTest` creates worktrees in a tab (`TestTab`) as the dialog does: a branch from
    the base, with the ignored files copied and the tab switched; an unused branch checked out in a chosen folder,
    which the repository remembers; and a folder git refuses. `NewWorktreeGitActionsTest` checks what
    `.worktreeinclude` lists against git (ignored and matched only, no tracked file, no nested worktree, a folder
    pattern), the copy (folders made, nothing overwritten, no link followed, nothing outside either worktree), branch
    names, the settings and the file Leaf creates.
  - `:domain` (11): `NewWorktreeStepsTest`: the commands for each start point and branch, tracking, the copy and
    switch steps, and the folder's name.
  - `:app` (13): `NewWorktreeStateTest` (what the dialog lets the user create: names git rejects or a branch has,
    folders that are taken or relative, `~`), `NewWorktreeMenusTest` (the branch and commit menus) and
    `WorktreePickerTest` (the picker's new row).
  - **Mutation check:** 22 mutations, 21 caught and one equivalent: removing the copy's first check that a path
    stays in its worktree, which the check of its real path makes anyway. A first try at the branch menu's mutation
    didn't compile, and three others replaced it.
  - The dialog was rendered offscreen against a demo repository with a `.worktreeinclude`, a remote branch and an
    unused branch, and one without the file. That made the folder field show the home folder as `~`: a long path
    hid the worktree's own folder at its end.
  - Manual run on macOS by the user, against a demo repository with a `.worktreeinclude`, a remote branch and an
    unused branch, and one without the file. It left five worktrees, all next to their repository: `agent1` checked
    out, `feature/login` tracking `origin/feature/login`, `review` without the ignored files and
    `zz/worktree-branch` with them, and `review` in the repository without the file. The user approved it for
    commit.
  - `./gradlew test` passes, with 970 tests (100 in `:app`, 654 in `:data`, 209 in `:domain`, 7 in `:common`).
    After the rebase onto the log's ref chips and the ProcessRunner fix, which conflicted only in the log's branch
    chip menu, `:app`, `:domain` and `:common` pass again (338 tests), and so do the worktree, `ProcessRunner` and
    `GitCli` tests in `:data` (133).

## Log rows with many branches and tags (branch `feat/log-ref-chips`)

- **Before:** a commit's chips took all the width they asked for, tags first, then local branches by name, then remote
  branches.
  - Past the row's end they were cut off without a sign, and the message, then Author and Date, got what was left,
    down to nothing.
  - An agent run leaves just that: every worktree's branch starts on `main`, which is often tagged and pushed too.
- **Now:** option 1c of the mockups (Claude Design project "Leaf log ref chips overflow"), picked after comparing
  IntelliJ, GitKraken, GitLens, Fork, Git Graph, gitk and Magit.
  - The current branch comes first, then branches that other worktrees use, other local branches, remote branches,
    then tags.
  - The chips take at most 80% of the Message column, and the message keeps 64 dp. A chip takes at most 200 dp (a
    longer name ends in "…", with a tooltip). The rest go into a +N chip, which opens a list of every ref as a chip of
    its own, grouped, with its double-click and menu.
  - The first chip shows even when it alone doesn't fit that way, as long as the message keeps 64 dp.
  - A remote branch with the name of a local branch on the same commit shares its chip, with a cloud mark. The chip's
    menu adds the remote branch's push, pull, delete, compare and copy, each naming it.
  - Author, Date and the other columns keep their widths on every row.
  - The share started at 60%, which left room for one worktree branch next to `main` in a 1300 px window; the user
    chose 80%. At 80%, the message's 64 dp decide below a Message column of 320 dp.
- **Tests:** 22 new.
  - `:domain` (10): `LogRefsTest`, the order, a shared chip, natural order, a detached HEAD, and `fitChips`.
  - `:app` (12): `LogRefChipsTest` renders the chips offscreen in a row with a Date column: all of them, +N with
    Date's width kept, a sweep of Message widths from 200 to 900 (the message keeps 64, the chips after the first stay
    within 80%), the first chip cut next to +N, a long name's ellipsis that leaves the marks their room, the list and
    Esc, and an action that closes the list first. `SharedChipRemoteContextMenuTest` checks the remote branch's items.
  - **Mutation check:** 23 mutations in three rounds, all caught in the end.
    - The chip's name not giving way to the marks after it survived the first round; the ellipsis test now compares
      the current branch's name with another's.
    - At 80%, the message's 64 dp decide the narrow test's layout, so a share of 100% passed it; the sweep catches
      that, and a missing or wrong message minimum.
  - The real `Log` was rendered offscreen against a demo repository at 1300, 1100, 900 and 720 px, at 60% and at
    80%. A click on +N selected the commit and opened the list, and Esc and a click outside closed it. At 60%, at
    720 px with all four columns on, the Message column was at its 200 dp minimum and the top row showed +6 alone.
  - `:app:test`, `:domain:test` and `:common:test` pass (99, 208 and 7 tests). `:data` wasn't run, as nothing in it
    changed.
  - Manual run on macOS by the user, with the demo repository: 80% looks good, and the tooltips look right.

## A git command that times out is reported as timed out (branch `fix/process-runner-timeout-race`)

- **Before:** on a timeout, `ProcessRunner` destroys the process, and the JDK's `Process.destroy` closes its streams.
  A reader still reading stdout or stderr then failed with `IOException: Stream closed`, and that failure ended the
  run. `GitCli` took it for git failing to start: it returned `StartFailed("Stream closed")` instead of `TimedOut`, and
  dropped the git it had located, so the next command looked for it again. A cancelled command ended the same way,
  rather than as cancelled. It depended on timing: `GitCliTest`'s timeout test failed once in a full `./gradlew test`
  run (2026-10-12), and passed on its own.
- **Now:** `ProcessRunner` notes that it's destroying the process before it does, and a read that fails after that
  only ends that stream's text, which nothing uses. A read that fails while the process runs, or after it exited on
  its own, still fails the run.
- **Tests:** 3 new in `ProcessRunnerTest`. A shell loop writes to both streams faster than slow callbacks pass the
  output on, so the readers still have output to read when the streams close. A timeout and a cancellation there
  failed with `Stream closed` on every run of the old code. The third checks that a read error while the process runs
  still fails the run.
  - **Mutation check:** swallowing every read error fails the third test. Setting the flag after destroying the
    process, rather than before, failed in 1 of 2 runs: the readers fail during or after the destroy, by timing.
  - `:data:test` passed, with 622 tests, before the rebase onto the worktree removal. After it, `ProcessRunnerTest`
    and `GitCliTest` pass again.

## Remove, discard, prune, lock and unlock worktrees (branch `feat/worktree-remove`)

Phase 3.1 of `docs/fork/PLAN.md`. The design of Phase 3, with the options weighed and the probes, is in
`docs/fork/worktree-lifecycle.md`.

- **Before:** Leaf only listed worktrees. Removing an agent's worktree took a terminal, and a tab whose worktree was
  removed kept its git dir, so every refresh failed.
- **Now:** a linked worktree's menu has Lock… or Unlock, Remove…, Discard…, and "Prune missing worktrees…" when its
  folder is missing. They run the git CLI, and each dialog shows the commands it runs, as lines that can be pasted into
  a terminal: what the dialog shows is the list that runs.
  - **Remove…** checks the worktree again when it opens: its changes, when it last changed, and whether its branch is
    merged into the base. A change in the last 5 minutes warns that an agent may still be working in it. A worktree
    with changes needs "Delete its uncommitted changes too" (`--force`), and a locked one "Remove it although it's
    locked", which unlocks it first. "Also delete the branch" adds `git branch -D`, saying whether the branch is merged
    into the base or how many commits the base doesn't have. A squash-merged branch counts as merged.
  - **Discard…** removes the worktree with its changes and deletes its branch. When that loses work, it asks a second
    time ("Discard anyway").
  - **Prune** lists what `git worktree prune` would remove, in git's words, first. **Lock** takes an optional reason.
    **Unlock** runs at once.
  - Removing the tab's own worktree switches the tab to the main worktree first.
  - A tab whose worktree is removed by another tab, a terminal or an agent switches to the main worktree by itself,
    and says why. This closes the item that Phase 2b left open.
- **Tests:** 49 new.
  - `:data` (23): `RemovingWorktreesTest` runs the dialogs' steps in a tab (`TestTab`) against worktrees made with
    `git worktree add`: removing, git refusing changes and then forcing, the tab's own worktree, a locked one, lock and
    unlock, prune, and a tab following its worktree's removal or its folder's deletion. Each checks `git worktree list`
    for stale entries. `WorktreeLifecycleGitActionsTest` checks merged branches (fast-forward, squash, conflicting,
    moved on since), a worktree's last change, prune's dry run and the lines it keeps, and a refused command.
  - `:domain` (13): `WorktreeStepsTest`: the commands of each action, relative paths, shell quoting, the `cd` lines.
  - `:app` (13): `RemoveWorktreeStateTest` (what each dialog lets the user confirm, and when discarding asks twice) and
    `WorktreesSectionTest` (the menu of the main, the tab's own, a locked and a missing worktree).
  - **Mutation check:** 16 mutations, all caught. The first pass missed three. Two were the script's: it named
    `:data:test` twice, and Gradle kept only the last `--tests` filter. The third, prune's filter for git's lines,
    wasn't tested, as git only printed those lines; the test with a warning line came from it.
  - The dialogs were rendered offscreen against a demo repository (the dirty, locked, missing and agent worktrees),
    which quoted the branch names and gave Remove the trash icon: the minus sign showed as a bare bar.
  - Manual run on macOS by the user, against a demo repository with a squash-merged, a dirty, a locked, a missing and
    an agent's worktree, with steps for each action and for a removal made in a terminal. The user reported that it
    looks good.
  - `./gradlew test` passes, with 934 tests (87 in `:app`, 641 in `:data`, 198 in `:domain`, 7 in `:common`). The test
    of prune's lines came after that run, and passes with its class; `:app:test` passed again after the strings
    changed.
  - `GitCliTest`'s timeout test failed once under the full suite's load and passed alone three times: a timeout can
    close the process's streams while they're read (`ProcessRunner`), unrelated to this branch.

## A tab switches between its repository's worktrees (branch `feat/worktree-switching`)

Phase 2b of `docs/fork/PLAN.md`. The design, with the options weighed, is in `docs/fork/worktree-switching.md`.

- **Before:** a tab showed one worktree. Switching to another (Phase 2a) selected the tab that had it, or opened a new
  one, which loaded the repository again and started with a fresh log, selection and side panel.
- **Now:** a tab stands for its repository, and switches between its worktrees in place, as a checkout switches
  branches.
  - Double-clicking a worktree's row, or a local branch that another worktree uses, switches the current tab, as do
    "Switch to worktree" in their menus and the checkout refusal's button. GitKraken and Tower do the same.
  - The worktree's menu has "Open in new tab", which does what switching did in Phase 2a.
  - A picker in the toolbar, right of Open, shows the tab's worktree and what it has checked out. Its list has every
    worktree, with the side panel's lines and tooltips, and switches to the one picked. It only shows for a repository
    with linked worktrees.
  - A linked worktree's tab is named after its repository, then the worktree in a lighter color (`demo · fix-auth`),
    as is the window. Other tabs keep their names.
  - What a switch keeps: the log, its scroll position and search, the selected commit, the side panel's sections,
    filter and folders. What it changes: the status, the commit message (each worktree has its own), the current
    branch, merge and rebase states, and the file watcher. It closes the diff of an uncommitted file, blame and file
    history, and turns amend off. Input stays blocked until the new worktree's status has loaded.
  - A tab restored on a worktree that was removed while Leaf was closed opens its repository, instead of an error.
- **Fixed on the way:**
  - A refresh still running when the tab switched used to be able to show the old worktree's data. Each refresh now
    says which git dir it read, and its result is dropped once the tab shows another one.
  - A commit message is saved a second after typing; it's now saved to the worktree it was typed in.
  - The file watcher can watch again: closing it destroyed its Rust object, so each watch now gets its own.
- **Tests:** 27 new.
  - `:data` (14): `SwitchingWorktreesTest` and `WatchingWorktreesTest` run a tab's real repositories and use cases
    (`TestTab`) against worktrees made with `git worktree add`: the status, branch, message and worktree list after a
    switch and back, a switch that fails, one to the tab's own worktree, the watcher of each worktree, and tabs
    restored on removed worktrees. `TestTab` can make the status slow, as in a large worktree, which shows that a
    switch waits for it. `InMemoryRepositoryDataRepositoryTest` checks that a refresh's result is dropped when the tab
    switched while it ran, or before it started, and what a switch resets.
  - `:domain` (9): `RepositoryPathsTest` (a removed worktree's repository, the tab's names, marking the new worktree),
    `RepositorySelectionStateTest` (switches) and `WorktreeRowsTest` (the picker's rows).
  - `:app` (4): `WorktreePickerTest` renders the picker offscreen and clicks it: the tab's worktree, the list, a
    switch, and none to a missing worktree. `WorktreesSectionTest` checks the new menu item.
  - **Mutation check:** 23 mutations, all caught. The first pass missed three: the status reset and the wait for the
    status were covered only by timing (a tiny repository's status loads before a test looks), and one mutation
    didn't compile. The slow status and the reset test came from them.
  - Each commit's tests ran on their own, in a separate checkout.
  - Manual run on macOS, with a repository that has a sibling worktree, an agent's in `.claude/worktrees/`, and one
    whose folder was deleted: the picker and its list, every double-click and menu item, the tab names, each
    worktree's draft message, and the file watcher following the switch. A tab restored on a removed worktree wasn't
    reached by hand: on the branch's first base, the folder given on the command line replaced that tab at startup,
    which `main` has fixed since (`3ae60969`, `replaceTab`); the tests cover it.
  - `./gradlew build` passes, with 886 tests (75 in `:app`, 619 in `:data`, 185 in `:domain`, 7 in `:common`), after
    rebasing onto the other sessions' work on `main`: a tab's refreshes run one at a time (`DataRefreshRunner`, which a
    switch's refresh waits behind), the file watcher has a thread of its own (now one per watch), and closed and
    replaced tabs are torn down. `TestTab` gets the refresh runner, and two of their tests the new signatures.

## The banner's Abort button follows its text (branch `fix/banner-button`)

- **Before:** the banner's text was on the left and its Abort button at the far right, so on a wide window the two
  were most of the screen apart.
- **Now:** the button comes right after the text, as in JetBrains' notification strips and the browsers' info bars.
  When the window is narrow, the text wraps and the button stays in view.
- **Tests:** 1 new in `OperationInProgressBannerTest`, which renders the banner 2400 and 500 px wide and checks where
  the button is. It fails with the old layout. `:app:test` passes, with 71 tests.

## A banner while a merge, rebase, cherry-pick or revert is unfinished (branch `fix/conflict-banner`)

- **Before:** a merge or rebase that stopped at conflicts showed a warning toast for 2 seconds, small and near the
  bottom, and it was easy to miss. Afterwards only the log's top row ("Pending changes to merge"), the status pane's
  buttons and the files' conflict marks said that the repository was in the middle of something. Conflicts from git in
  a terminal or from an agent showed no toast at all.
- **Now:** an amber strip under the toolbar says what's unfinished and what to do: "Merge stopped at conflicts in 1
  file. Resolve them, then commit.", or "Rebase in progress. Continue it to finish." once the conflicts are resolved.
  Its button aborts the operation, after the same confirmation as the status pane's Abort.
  - It follows the repository's state, so it shows however the operation started, after switching tabs and after a
    restart, and goes once the operation is committed, continued to the end or aborted. The toast stays as the first
    cue.
  - Not while the interactive rebase's list is open, which has its own buttons. Bisect and `git am` get no banner.
  - Chosen over a toast that stays until closed, and over a dialog after the operation, which only Leaf's own
    operations would show.
- **Tests:** `OperationInProgressBannerTest` (`:app`, 5) checks when the banner shows and what it counts, that Abort
  asks first and then aborts the right operation, and renders the banner offscreen to read its texts and click Abort.
  - **Mutation check:** 10 mutations, all caught. Not covered by a test: the banner's place in the repository view,
    and the status pane's Abort calling the shared function.
  - `./gradlew test` passes, with 849 tests (70 in `:app`, 596 in `:data`, 176 in `:domain`, 7 in `:common`), on
    `608d19d4`. The conflicted file's diff fix that came after changed only the data layer's diff code.

## A conflicted file's diff compares it to ours (branch `claude/exciting-euclid-f40264`)

- **Before:** the diff of a file with conflicts (in Unstaged, while a merge, rebase or cherry-pick is stopped at
  conflicts) compared the working file with the common ancestor. So it showed the ancestor's lines as removed, lines
  that neither side has. Such a file has stages 1 to 3 in the index and no stage 0, and JGit's diff of the index
  against the working tree gives one entry per stage. Leaf took the first one, stage 1. A working file set back to
  ours showed as the ancestor replaced by ours, and one set back to the ancestor as ours deleted.
- **Now:** it compares the working file, conflict markers included, with ours (stage 2), the version of the commit
  that the operation stopped on (fork-only `UnmergedDiffEntry.kt`). For a file that both sides changed, that's what
  `git diff --ours` shows.
  - When ours has no file (deleted by us), the whole working file is added. git shows nothing for it.
  - When the working file is deleted, ours is removed.
  - When the working file is ours as it is, as git leaves it when they deleted the file, the pane shows the file with
    no lines instead of closing.
- **The `NullPointerException`s in the log:** the two logged on 2026-10-10 came from aborts, not from selecting the
  file. At 04:23:27, the demo repository's reflog has `rebase: aborting` in the same second. At 12:46:52, the error
  comes right before a refresh that no poll explains. Aborting while a conflicted file's diff is open reloads that
  diff, and the file has no changes left. Since "A diff with no changes left logs one line" below, that's one log
  line, and the diff pane closes. The conflicted file's diff itself didn't fail in any of the cases tested: merge,
  rebase and cherry-pick with git and with JGit, add/add, and deleted by us or by them.
- **Tests:** `ConflictDiffTest` (`:data`, 9) makes real conflicts, with the git CLI and with Leaf's JGit merge, and
  formats the diff as the pane gets it. It covers merge, rebase and cherry-pick, deleted by us, deleted by them, the
  working file deleted or set back to the ancestor, and the abort.
  - **Mutation check:** 6 mutations, all caught: no conflict handling (8 of the 9 tests fail, as on `main`), comparing
    with stage 1 or with stage 3, no entry for an unchanged file, an unchanged entry with the same id on both sides,
    and handling files without conflicts as conflicted.
  - `./gradlew test` passes, with 853 tests (65 in `:app`, 605 in `:data`, 176 in `:domain`, 7 in `:common`).

## Settings that did nothing, and UI bugs from upstream's code (branch `fix/settings-and-ui`)

From the review of the files that come from upstream (2026-10-09), which found them worth fixing rather than rewriting.

- **Removed settings:** Proxy (Settings → Network) and "Do not verify SSL security" (Security). Neither ever applied:
  the proxy code was commented out, and the SSL switch turned off credential caching instead. git's `http.proxy` and
  `http.sslVerify` apply to the git commands Leaf runs. `RemovedSettingsMigration` deletes their saved values, the
  proxy password included, which was stored as plain text in `user_prefs.json`.
- **"Cache HTTP credentials in memory"** now applies to JGit's HTTPS fallback and to the built-in LFS client without a
  credential helper. Off, they asked once and cached the credentials anyway.
- **Settings and dialogs:**
  - the default clone directory was saved but never read: the clone dialog now starts from it;
  - Appearance: the custom theme was saved at every keystroke, as its one-second delay never cancelled, and the Scale
    dropdown kept showing the old value;
  - the error dialog's Copy button copies the stack trace shown, not one made at the click;
  - the sign-off dialog can't save before it has read the config, which a failed read used to overwrite;
  - the primary button of confirm-style dialogs (`IconBasedDialog`) had the Cancel button's focus requester.
- **Diff and rebase:**
  - a line of a diff is drawn up to 10,000 characters, then "… N more characters", and syntax highlighting covers its
    first 1,000: a minified file could freeze the view;
  - a large image is scaled down to fit the pane, keeping its shape, instead of being cut off;
  - in an interactive rebase, a squash or fixup that becomes the first step (moved there, or after the steps before it
    are dropped) turns into a pick, as git refuses it (`withFirstStepPicked`).
- **Lists and scrolling:**
  - staging or unstaging a file no longer scrolls Staged and Unstaged to the top;
  - closing a diff no longer scrolls the log to the top; only a new commit at the top does;
  - another file's diff starts at its top, while a refresh of the same diff keeps its scroll;
  - the graph scrolled twice as far as its scroll bar on a 2x screen (pixels taken as dp);
  - each letter typed in the commit message sorted and grouped every changed file again; the lists are now built
    apart (`StatusLists`);
  - a log search no longer lets an earlier, slower search overwrite the results of the last term typed.
- **Smaller fixes:**
  - an image that Skia can't decode, such as HEIC, shows as binary instead of ending the app;
  - an error opened twice when coming back to its tab;
  - a failed file history showed a blank pane, and now shows the error;
  - Blame is no longer offered for a file the commit deletes (the condition was always true);
  - Python comments start with `#`, SQL keywords are found in lower case, and every line of a diff no longer builds
    its own highlighter with its keyword list;
  - the context menu wrapper wrote a state at every layout, which recomposed every visible diff row at each frame of a
    scroll;
  - the clone dialog and the diff view ran their closing side effects during composition.
- **Tests:** 15 new. `RemovedSettingsMigrationTest` (2), the cache setting in `HttpCredentialsProviderTest` and
  `ProvideLfsCredentialsGitActionTest` (1 each), `StatusStateTest` (2), `SyntaxHighlighterTest` (3),
  `FormatDiffTest` (3), `RebaseStepsTest` (3).
- **Plan:** `docs/fork/PLAN.md` no longer asks for upstreamable changes: Leaf sends no pull requests to upstream, and
  keeps its changes to upstream's files small so that merging upstream stays cheap.

## A diff with no changes left logs one line (branch `claude/dazzling-liskov-e3958a`)

- **Before:** `GetDiffUseCase` took the format action's result with `okOrNull()!!`. When the action failed, the `!!`
  threw a `NullPointerException`, which was logged with an empty message and its stack trace twice (`printError`,
  then `ex.printStackTrace()`), and the diff pane closed. The action fails every time a file has no changes left on
  the diff's side, as after discarding its last hunk or line: `GetDiffEntryFromStatusEntryGitAction` throws
  `MissingDiffEntryException`, which `JGit.provide` returns as a `GenericError`. The real cause of any other failure
  was never logged.
- **Now:** an error result still gives `DiffNotFound`, but without throwing. A missing diff entry gets one `[LOG]`
  line (`No diff to show for <file>: Diff entry not found`). Any other error is logged with its message, and with its
  exception's stack trace once. An exception thrown by the action is still caught, and logged once.
- **Tests:** `GetDiffUseCaseTest` (4, `:domain`) mocks the format action and reads what the use case prints.
  - **Mutation check:** 3 mutations, all caught: throwing on an error result again, printing the stack trace twice
    again, and logging a missing diff entry as an error.
  - `./gradlew build` passes, with 829 tests (57 in `:app`, 592 in `:data`, 173 in `:domain`, 7 in `:common`).

## Merge, rebase and pull warn when they stop at conflicts (branch `fix/conflict-toasts`)

- **Before:** a merge, rebase or pull that stopped at conflicts showed "Merge completed", "Rebase completed" or "Pull
  completed". The use cases knew (their git actions return whether there were conflicts), but a task that didn't
  fail was always recorded as completed. The pull built a warning ("Pull produced conflicts, fix them to continue")
  and dropped it, left over from an older way of showing toasts.
- **Now:** they show the warning toast, with its warning icon: "Merge stopped at conflicts, fix them to continue", and
  the same for Rebase and Pull. `UseCaseExecutor.executeLaunch` takes `stoppedAtConflicts`, which tells it from the
  task's result, and records `CompletedTask.Success(stoppedAtConflicts = true)`. Everything else about the task is as
  before: it still refreshes, and isn't an error.
- **Tests:** 5 new. `StoppedAtConflictsTest` (`:data`, 3) runs the merge, rebase and pull use cases through a real
  `UseCaseExecutor` with their git actions faked, with and without conflicts. `CompletedTaskNotificationTest`
  (`:app`, 2) checks the toasts.
  - **Mutation check:** 6 mutations, all caught.
  - `./gradlew test` passes, with 825 tests (57 in `:app`, 592 in `:data`, 169 in `:domain`, 7 in `:common`).

## Discards ask first (branch `claude/sad-grothendieck-100370`)

- **Before:** "Discard hunk" and "Discard line" in the diff, and "Discard file changes" and "Discard N files" in the
  Staged and Unstaged menus, discarded right away. None of it can be restored. Only discarding a folder asked.
- **Now:** they open `ConfirmActionDialog`, which shows what is lost:
  - a hunk: its header, and how many added lines can't be restored. A hunk that only removes lines gets no warning,
    as discarding it puts them back;
  - a line: the line, and whether it's removed from the file or put back;
  - a file or a selection: in the Staged list, that the unstaged changes go too, as `DiscardEntriesGitAction` resets
    the index entry and then checks the file out. Discarding a deleted file brings it back, with no warning.
- **"Don't ask again":** the hunk and line dialogs have a "Don't ask again for hunks and lines" box. Confirming with
  it checked turns off the new setting "Ask before discarding a hunk or a line" (Settings → Layout, key
  `confirm_hunk_and_line_discards`, on by default), where it can be turned back on. Discarding files always asks, as a
  slip there loses whole files. `Screen.ConfirmAction` got an `onStopAsking` parameter for it.
- A file that changes while the dialog is open makes git refuse the discard (`StaleHunkError`, from the hunk and line
  actions entry below), so the dialog can't discard lines that are no longer where it showed them.
- Chosen over a dialog for every discard, which gets in the way when discarding lines one after another, and over an
  Undo in place of a dialog: notifications have no buttons yet, and an agent changing the file would block the Undo.
- **Tests:**
  - `ConfirmActionDialogTest` (10, `:app`) renders the dialog offscreen and clicks through its semantics: the box,
    Discard and Cancel, and the texts of each discard.
  - `ConfirmHunkAndLineDiscardsSettingTest` (2, `:domain`): the setting asks until the user turns it off.
  - **Mutation check:** 8 mutations of the dialog, all caught. Not covered by a test: `DiffPane` asking or not, and
    the Staged and Unstaged menus opening the dialog.
  - `./gradlew build` passes, with 820 tests (55 in `:app`, 589 in `:data`, 169 in `:domain`, 7 in `:common`).

## Hunk and line actions apply patches with git (branch `claude/funny-bhaskara-df3d47`)

The code review of 2026-10-09 found these the only upstream code whose approach needed replacing rather than fixing.

- **Before:** staging, unstaging and discarding a hunk or a line edited the file's text with JGit.
  - The text was decoded as UTF-8 and written back as UTF-8. In a Latin-1 or Windows-1252 file, which JGit shows as
    text, every non-ASCII byte became U+FFFD (`EF BF BD`), in the index or in the working file.
  - Discarding inserted or removed lines by number, without checking that they were still the lines the diff showed,
    and wrote the whole file back. Once the file had changed (agents edit all the time, and the watcher drops events
    for 1.5 s after a task), the wrong line went, without a word and without a way back.
  - No test covered them.
- **Now:** they build a patch of the hunk or the line, and apply it with `git apply`, as `git add -p`,
  `git reset -p` and `git checkout -p` do: `--cached` to stage, `--cached -R` to unstage, `-R` to discard.
  - The patch has the bytes that the diff compared, each line with its own line ending. Encodings, CRLF and a last
    line without a line ending stay as they are.
  - git checks that the index or the file still has the lines that the diff showed. When it doesn't, nothing changes,
    and Leaf says that the file changed after the diff was shown, with git's message, and reloads the diff. Lines added
    or removed elsewhere in the file don't stop it: git finds the hunk where it moved, and when a block repeats in the
    file, it takes the copy that the diff showed.
  - A line is selected as in `git add -p`'s hunk editing. Staging an added line keeps the removed lines around it in
    the index, and discarding a removed line puts it back before the added ones. When the file's last line has no line
    ending, a line staged after it goes before it, as the index can't give it one without staging that change too.
  - Staging a hunk of an untracked file stages the file with its mode, and unstaging the whole of an added file leaves
    it untracked. The diff pane only offers these actions for modified files, as before.
  - The user's `apply.whitespace` and `apply.ignoreWhitespace` don't apply: lines go in as they are, and must match
    exactly.
  - They need a usable git. Without one, they fail with the git CLI's error and change nothing.
- **Removed:** `HunkEdit`, `GetLinesFromRawTextGitAction`, `GetLinesFromTextGitAction` and their interfaces.
- **Model:** `Hunk` has `oldText` and `newText`, the diff's two `RawText`s, which `FormatHunksGitAction` sets. A line's
  `text` is decoded and can't give its bytes back.
- **Tests:** 20 new in `:data` (`HunkActionsTest`), against repositories that the git CLI made, with the hunks that
  `FormatDiffGitAction` gives the diff pane, and the index read back with `git cat-file`, byte for byte.
  - UTF-8, Latin-1, CRLF, `core.autocrlf`, and last lines without a line ending: staging a hunk and a line, unstaging
    a hunk and a line, discarding a hunk and a line.
  - A file that changed after the diff was loaded, in the working tree or in the index, is refused and left as it is,
    also when the change is only whitespace and `apply.ignoreWhitespace` is `change`. `apply.whitespace=fix` doesn't
    change the staged lines.
  - A hunk that moved down the file is discarded where it is now, and a repeated block is staged or discarded at the
    copy that the diff showed.
  - An untracked file, a file name with spaces, accents and a tab, and a missing git.
  - **Mutation check:** 16 mutations, 14 caught. The two left can't change what git does: the start of an empty side
    in the header (git reads 0 and 1 alike there), and context from the old side reversed (an exact diff's context is
    the same bytes on both sides).
  - `./gradlew build` passes, with 808 tests (45 in `:app`, 589 in `:data`, 167 in `:domain`, 7 in `:common`), after
    rebasing onto the fixes for the failures that the data code dropped and for many tabs.
- **Not done:** discarding a hunk or a line still doesn't ask first (a UI decision, left for later).

## Many tabs no longer stall each other (branch `fix/many-tabs-concurrency`)

Five concurrency and lifecycle bugs in code from upstream, which hurt most with one tab per agent's worktree. A review
on 2026-10-09 found them.

- **File watcher:** each loaded tab's watch loop, which blocks, ran on `Dispatchers.Default`, where tabs and view
  models do their work and which has one thread per core. With as many loaded tabs as cores, nothing else on it ran.
  The loop now runs on a single-thread view of `Dispatchers.IO` per tab, which doesn't take IO's threads either.
- **Tabs torn down:**
  - Closing a tab cancelled only the tab's scope. The view models' own scopes kept running: `RepositoryOpenViewModel`
    (its `onClear` was an empty override) and the file history's, which collected a setting in the tab's scope, once
    more per file history opened. Now `dispose` clears every view model, and a file history stops when it closes.
  - "Open another repository" replaced the tab without disposing it, and found it by path. A tab without a path
    matched too: the Welcome page, or a tab whose path nothing had shown yet, such as one scrolled out of the tab bar.
    Opening a folder from the command line, a submodule, or a worktree with "Switch to worktree" could replace such a
    tab. Now `replaceTab` gets the tab itself and disposes it as closing it does, and adding a tab only adds.
- **Commit identity dialog:** committing without `user.name` or `user.email` asks for them. While the dialog was open,
  a loop read the answer without pause, taking a whole core, and forever if the tab closed. It now waits without a
  thread.
- **Amending while continuing a rebase:** "Continue" on a step marked to amend started the amend as a task of its own
  and didn't wait: `git rebase --continue` raced it, a failed amend was ignored, and the amend's end took the
  processing screen away while the rebase still ran. The amend now runs within the rebase's task, and the rebase stops
  with the amend's error when it fails.
- **Refreshes:**
  - Every refresh ran at once. While an agent wrote in the tab's worktree, the watcher's refreshes overlapped: two
    walks of the log shared the tab's graph generator, which isn't thread-safe, and the older result could land last.
    Now a tab's refreshes take turns (`DataRefreshRunner`). A call made while one waits joins it with what it asks
    for, so a burst of changes queues one refresh at most. Loading more commits runs between refreshes.
  - A refresh step that threw (the log's walk when an object goes missing, for example) left its data Loading, with
    nothing logged. It now ends as an error, with a log line, and `UseCaseExecutor` logs the throws it turns into
    errors.
- **Tests:** 15 new.
  - `:domain` (5): `DataRefreshRunnerTest`: one refresh at a time, calls that join a waiting one, a waiting call
    cancelled, a refresh that fails, and work that runs alone.
  - `:data` (7): `FileChangesWatcherTest` runs the real watcher, through the Rust library in the app's resources, and
    checks that other work runs on the thread that collects it, and that a change is reported. `ContinueRebaseAmendTest`
    checks the amend's order and task, and a failed amend. `OverlappingRefreshesTest` sends ten refreshes at once
    through a real `RefreshDataUseCase`. `RepositoryDataLoadingTest` covers a step that throws, fails, succeeds or is
    cancelled.
  - `:app` (3): `HistoryViewModelTest` and `CommitterDataRequestTest` (the dialog's wait leaves its thread free).
  - The tabs' tear-down has no unit test: `RepositoryTabViewModel` and `RepositoryOpenViewModel` are final classes
    with many dependencies, which MockK can't mock on JDK 25.


## Failures that the data code dropped (branch `claude/festive-lamport-994739`)

Six fixes from the 2026-10-09 review of the code that comes from upstream, where a failure was ignored or left bad
state behind.

- **LFS downloads** (the built-in client, which runs when git-lfs isn't installed):
  - **Before:** a download was appended to the object's place in `lfs/objects`, unchecked. A connection that dropped
    left the object truncated (Ktor's CIO engine ends the body without an error), the checkout used it, and the smudge
    filter took it as complete from then on. The next download appended to it. Its result was dropped too, and the
    whole object was read into memory before it was written.
  - **Now:** `DownloadLfsObjectGitAction` downloads to `<oid>.<random>.tmp` next to the object, and renames it into
    place once its size and SHA-256 are the pointer's. Otherwise the checkout fails with `LfsError.CorruptDownload`.
    The temporary file is removed either way. The download streams to the file on `Dispatchers.IO`.
  - Like git-lfs, the smudge filter downloads again when the object in place has another size than the pointer,
    which repairs the objects that the old download truncated.
- **`filter.lfs.useJGitBuiltin` written to `.git/config`:** `useBuiltinLfs` turns it on in the cached repository's
  in-memory config while JGit merges, rebases or checks out, and put it back only when that worked. A pull that would
  have overwritten local changes left it on, and the next action that saves the config (setting a branch's upstream,
  saving the author) wrote it to the file. It's put back in `finally` now, for both pulls and both clones.
- **Cherry-pick:** JGit reports a cherry-pick that local changes stop (`FAILED`) and one that stops at conflicts
  (`CONFLICTING`) in its result, which was ignored, so both said "Commit cherry-picked". They're errors now, as a
  revert's are: "The cherry-pick didn't happen, as it would overwrite your changes to … Commit or stash them, then
  cherry-pick again." and "Cherry-pick stopped with conflicts. …". The status refreshes after either.
- **Rebase:** JGit's `CONFLICTS` means local files stopped the checkout, and `FAILED` that they stopped a commit. In
  both, JGit puts the branch back. They counted as a rebase with conflicts and one that completed, and `ABORTED` as
  completed. They're errors now, naming the files; merge conflicts are `STOPPED`. The pull with rebase read
  `CONFLICTS` the same way (`rebaseHasConflicts`), and now fails with the files too. A check that was there twice is
  gone.
- **Deleting a remote branch** (JGit, when git isn't used): the push's result was dropped, so when the remote refused
  (for example with `receive.denyDeletes`), the remote-tracking branch was deleted anyway and Leaf said "Remote branch
  deleted", until the next fetch brought it back. The failure is shown now, and the remote-tracking branch stays.
- **Start a local repository:** `InitLocalRepositoryGitAction` threw, and nothing caught it, so on a folder that
  can't be written nothing happened and no message appeared. It returns the error now, which opens the error dialog
  ("Could not create the repository", a new `TaskType.RepositoryInit`). It also closes the repository it opened,
  which it used to leak.
- **Tests:** 22 new in `:data`.
  - `DownloadLfsObjectGitActionTest` (5) downloads from `FakeLfsServer`, which can now drop the connection midway:
    a download that breaks off, one of another size, one of the right size with other content, and an object that
    an earlier download truncated. `HttpClientsTest`'s download now uses the content's real SHA-256.
  - `UseBuiltinLfsTest` (3) and one `GitCliFetchPullTest` case (a pull that would overwrite local changes, then
    `config.save()`).
  - `CherryPickCommitGitActionTest` (3), `RebaseBranchGitActionTest` (5), one `PullOutcomeTest` case,
    `DeleteRemoteBranchGitActionTest` (2, against a bare repository with `receive.denyDeletes`) and
    `InitLocalRepositoryGitActionTest` (2, a read-only folder; skipped on Windows).
  - **Mutation check:** 13 mutations, all caught. Not covered by a test: the smudge filter's size check, and the
    tab showing the init error.
  - `./gradlew test` passes, with 773 tests (42 in `:app`, 562 in `:data`, 162 in `:domain`, 7 in `:common`).


## Switching to a worktree opens its tab (branch `feat/open-worktree-tabs`)

Phase 2a of `docs/fork/PLAN.md`, and the "Switch to that worktree" action that 1.3 left for Phase 2.

- **Before:** the Worktrees section and the branch badges only showed the other worktrees. Double-clicking a branch
  that another worktree had checked out showed the checkout refusal, and the worktree had to be opened from the
  Welcome page or the menu, as a new tab even when one already had it.
- **Now:**
  - Double-clicking a worktree's row switches to it: Leaf selects the tab that has it, or opens it in a new tab at
    the end. The first click still selects its commit in the log. The row's menu has "Switch to worktree" above "Copy
    path".
  - Double-clicking a local branch that another worktree uses (checked out, rebased or bisected from there) switches
    to that worktree, in the side panel and on the log's chips. Their menus have "Switch to worktree" where Checkout
    was left out.
  - The checkout refusal ("can't be checked out here, as it's checked out in the worktree at …") has a "Switch to
    worktree" button. It shows when the worktree list was stale, and after double-clicking `origin/x` while another
    worktree has `x`, which stays a refusal, as the remote branch may have other commits.
  - Nothing is offered for the tab's own worktree, a worktree whose folder is missing (prunable), or a bare
    repository's. A branch whose worktree is missing gets the refusal, without the button.
  - A tab is found by its git dir, whichever path it was opened from (a working tree or a git dir, a relative or
    absolute `.git` file, `/var` or `/private/var`), also when it hasn't loaded yet. The Welcome page and the CLI
    argument still open a second tab for a repository that has one.
- **Tab name and tooltip:** a linked worktree's tab is named after its folder, and its tooltip is the folder's path.
  Before, they came from its git dir: the name was git's id for the worktree (the folder's name unless git added a
  number), and the tooltip `<repo>/.git/worktrees/<id>`. "Open another repository" from such a tab now replaces it,
  as for other tabs, instead of adding one.
- **Recent repositories:** linked worktrees no longer go into the Welcome page's list, however they're opened. Their
  repository does. Agents' worktrees come and go, so every one opened left an entry that later pointed nowhere.
  Entries already in the list stay.
- **Tests:** 16 new.
  - `:domain` (11): `RepositoryPathsTest` finds a repository's git dir from a working tree, a git dir, absolute and
    relative `.git` files and a symbolic link, picks the first tab that has it, and gives a tab's working tree.
    `WorktreeBranchUsersTest` picks the worktree to switch to, and none for the tab's own, a missing or a bare one.
  - `:data` (1): `WorktreesTest` checks the same against worktrees that `git worktree add` made, `--relative-paths`
    included: the folders that `git worktree list` gives find the git dirs that opening them gives.
  - `:app` (4): the branch menu's "Switch to worktree", and the worktree row's menu.
  - **Mutation check:** 17 mutations, all caught.
  - Manual run on macOS, with a repository that has a sibling worktree, an agent's in `.claude/worktrees/`, one made
    with `--relative-paths`, one whose folder was deleted, and a remote: every double-click and menu item, the tab
    that was already open being selected, the refusal's button for `origin/feature` and none for the deleted
    worktree's branch, the tabs' names after a restart, and the Welcome page's recent list.
  - `./gradlew build` passes, with 751 tests (42 in `:app`, 540 in `:data`, 162 in `:domain`, 7 in `:common`), after
    rebasing onto the fix for the side panel rows' names.

## Remote branch, tag and submodule rows show their names again (branch `fix/side-panel-row-names`)

- **Before:** since `6c0498a2` ("Gave the branch name the room before the worktree's name"), the side panel showed
  remote branches, tags and submodules as their icon and their age or state letter, without their names. That commit
  added the `textContent` slot as the last parameter of `SideMenuSubentry`, so the trailing lambda these rows pass for
  `additionalInfo`, upstream's last parameter, went to `textContent` and replaced the name. It was in no release,
  and was found while checking the worktrees' base branch (next entry).
- **Now:** `textContent` comes before `additionalInfo`, which is last again, as in upstream. The rows' calls didn't
  change, and an upstream call merged later that passes a trailing lambda reaches `additionalInfo` too.
- **Tests:** 2 new in `:app` (`SideMenuSubentryTest`), which render a row offscreen and read its texts from the
  semantics: a trailing lambda adds to the name, and `textContent` still replaces it. The first failed before the fix,
  with the age label alone. Checked once with a throwaway harness, deleted afterwards: the real side panel, rendered
  offscreen for a temporary repository with a remote, tags and a submodule, before and after the fix.
  `./gradlew build` passes, with 735 tests (38 in `:app`, 539 in `:data`, 151 in `:domain`, 7 in `:common`), after
  rebasing onto the worktrees' base branch.

## A base branch for the worktrees, chosen per repository (branch `feat/worktree-base-branch`)

Closes the "Not yet" item of 1.4: PLAN.md 1.2 asked for a base branch "configurable per repo".

- **Before:** every worktree was compared to the base that Leaf picked: origin's default branch, else `main`, else
  `master`. A repository whose agents branch off `develop`, or a user who wanted to see what isn't on `origin/main`
  yet, had no way to change it.
- **Now:**
  - Any local or remote branch can be the base. The Worktrees section's `↑ahead ↓behind`, its tooltips and the branch
    badges' tooltips use it, and the tooltips name a remote base with its remote (`origin/main`).
  - A branch's right-click menu, in the side panel (local and remote) and on the log's chips, has "Compare worktrees
    to this branch", and "Compare worktrees automatically" on the chosen one.
  - The Worktrees header has a button with the compare icon. It is muted while Leaf picks the base, and shows the
    chosen branch's name otherwise. Its menu shows what Automatic picks, the chosen branch, and how to choose another.
  - The menus are left out while the repository has only one worktree. Its row is still compared to the base.
- **Stored** in `<common git dir>/leaf` as `worktrees.baseBranch`, a full ref, next to the side panel's folders, so
  every worktree's tab compares to the same branch. It's read on every worktree refresh, so another tab of the same
  repository follows at its next refresh.
- **Deleted or renamed:**
  - A chosen branch that doesn't exist stays chosen, and the worktrees are compared to the automatic base until it
    exists again. The header's menu says "Not found". This covers branches that agents or the CLI delete, a remote
    branch that a fetch prunes, and deleting it in Leaf, which doesn't clear the choice.
  - Renaming the branch in Leaf moves the choice to the new name, as git moves `branch.<name>` config.
- **Found here, fixed in the entry above:** since `6c0498a2`, remote branch, tag and submodule rows in the side panel
  showed no name. `SideMenuSubentry`'s new last parameter, `textContent`, took their trailing lambda, which was meant
  for `additionalInfo`. Fixed in `4b192ae9`.
- **Tests:** 26 new.
  - `:data` (15): `WorktreeBaseBranchGitActionTest` saves and reads the choice, local or remote, as `git config`
    reads it. Going back to Automatic removes it, header included, and keeps sign-off and the folders. A missing
    branch falls back, a value that isn't a branch is ignored or refused, an unreadable file is replaced, and a linked
    worktree's tab shares the choice. `WorktreesTest` compares every worktree to a chosen branch from any tab, to
    `origin/main`, and to the automatic base after Leaf deletes the chosen branch until it's back.
    `RenameBranchGitActionTest` moves the choice along, from a linked worktree's tab too, and leaves it alone when
    another branch is renamed.
  - `:domain` (4): which branch the worktrees are compared to (`WorktreeBaseBranchTest`).
  - `:app` (7): the branch menu's compare items, local and remote (`BranchContextMenuWorktreesTest`), when the menus
    offer a choice (`BaseChoiceTest`), and the base's name in tooltips.
  - **Mutation check:** 9 mutations, all caught.
  - Checked once with a throwaway harness, deleted afterwards: the real side panel, rendered offscreen for a
    repository with an agent's worktree. Choosing `develop` and `origin/main` from their right-click menus, the
    header's menu with Automatic, the chosen branch and "Not found" after git deleted it, and going back to Automatic.
  - `./gradlew build` passes, with 733 tests (36 in `:app`, 539 in `:data`, 151 in `:domain`, 7 in `:common`), after
    rebasing onto the worktree refresh (Phase 1.5), whose watcher already leaves out Leaf's own file.

## Changes in other worktrees show up by themselves (branch `feat/worktree-refresh`)

Phase 1.5 of `docs/fork/PLAN.md`.

- **Before:** a tab refreshed its worktree list only with its own refreshes (F5, an operation, a change in its own
  worktree). What an agent did in another worktree showed up only when its commit moved a ref, and only in a tab on the
  main worktree: a tab on a linked worktree didn't watch the refs it shares. A tab on the main worktree also watched
  every folder of an agent's worktree nested inside it, unless a gitignore covered the folder, so each file the agent
  or its build wrote refreshed the tab's status and log.
- **Now:**
  - Each tab watches `<common git dir>/worktrees/`, so git working in another worktree (staging, committing,
    switching, rebasing, adding or removing a worktree) refreshes the worktree list within about a second. Only the
    list: a full refresh comes only when the shared refs change. While an agent keeps running git, the list
    refreshes at most every 2 s.
  - A tab on a linked worktree also watches the shared `refs/`, `packed-refs` and `config`: commits in the main
    worktree or another one refresh it, as they did a tab on the main worktree.
  - Files changed in the other worktrees, which nothing watches, show up through a refresh every 10 s while the
    Worktrees section is shown, also while another app has the focus, as agents run in a terminal next to Leaf. Not
    while the section is collapsed, the tab is in the background or the window is minimized. Settings → Git →
    Worktrees sets it: never, every 5, 10, 30 seconds or every minute.
  - When the window gets the focus, the list refreshes right away.
  - The main worktree's tab no longer watches the folders of the repository's worktrees inside it, such as
    `.claude/worktrees/<name>`: the worktree list follows them instead. Submodules are watched as before.
- **Fixed on the way:**
  - Editing `.gitignore`, `.gitattributes` or anything in `.github/` refreshed everything, because their paths start
    like the git dir's. They now refresh the status, the log and the repository state, as other files do.
  - Saving Leaf's per-repository settings (`<git dir>/leaf`, written when a side panel folder opens or closes)
    refreshed everything. It now refreshes nothing.
  - A setting whose subtitle wrapped left no room for its control, so the "Use git for remote operations" switch
    (Settings → Git → Remote actions) didn't show. The title and subtitle now take what the control leaves.
- **Tests:** 25 new.
  - `:domain` (21): what each changed path refreshes, for a tab on the main worktree and on a linked one, with real
    paths through a link and a git dir given with `..` (`WatchedRepositoryTest`); which folders are the repository's
    linked worktrees, from their `.git` files; the 2 s gap between the watcher's refreshes; when the poll refreshes
    (shown or hidden, off, a refresh from elsewhere, the window getting the focus, a tick during an operation); and
    when a run ended.
  - `:data` (2): the common git dir of the main and of linked worktrees, and the linked worktrees' folders as git
    creates them, with absolute and relative paths (`WorktreesTest`).
  - `:app` (2): a settings row keeps its switch or drop-down on screen next to a long subtitle, and at the end of the
    row next to a short one, rendered offscreen (`SettingsRowsTest`).
  - **Mutation check:** 30 mutations, 29 caught. The one missed is equivalent: making JGit's common git dir a plain
    path, which JGit 7.7 already does. That code is gone.
  - Checked in a dev run against a temporary repository with an agent's worktree nested in it, another in a folder
    that no gitignore covers, and a sibling one, by making the changes from a terminal and reading what each refreshed
    (a debug line now says). Staging in another worktree refreshed only the list, ten git commands in 1.5 s refreshed
    it twice, commits refreshed everything, and the nested worktrees' files refreshed nothing until the next poll. A
    tab on the sibling worktree refreshed for the main worktree's commits and packed refs. A worktree refresh took
    80 to 150 ms. With the nested-folder check turned off, an edit in the unignored nested worktree refreshed the
    main tab's status and log, as before.
  - `./gradlew build` passes, with 707 tests (29 in `:app`, 524 in `:data`, 147 in `:domain`, 7 in `:common`), after
    rebasing onto the Committer column.
- **Not yet:**
  - A tab doesn't watch the other worktrees' files, so their changes wait for the next poll, up to the interval.
  - Opening a worktree from the list (Phase 2a).

## Who committed a commit, next to who wrote it (branch `feat/log-columns`)

- **Before:** the log's Author column showed who wrote each commit, and only its tooltip said when someone else had
  committed it. The Date column shows the commit date, so after another person's rebase a row paired one person with
  the date of the other's rebase.
- **Now:**
  - When someone else committed a commit (a rebase, cherry-pick or amend by another person, or a pull request merged
    on GitHub's website, which records GitHub), the author's avatar carries the committer's smaller avatar, in grey.
    Its tooltip names both, with the date each one wrote or committed it.
  - A **Committer** column, off by default, comes after Author in the columns menu. Its avatar is grey when the
    committer isn't the author. It gives way first when the log is narrow.
  - The Date column keeps the commit date, which orders the log. Its tooltip adds the date the change was written
    when that's different, as after a rebase or an amend.
  - People are matched by email, ignoring case, so a name spelled differently with the same email doesn't count as
    someone else.
  - Saved column settings get Committer right after Author, hidden, and keep everything else.
- **Tests:** 4 new in `:domain` (`LogColumnsTest` 17, `LogColumnsCodecTest` 8). 11 mutations of the new rules (the
  matching of people, where a new column goes, the order in which columns give way) were all caught. Checked once with
  a throwaway offscreen harness: the badge and the Committer column in dark and light, on a selected row, the names
  lined up from row to row, and the menu. `./gradlew test` passes, with 682 tests (27 in `:app`, 522 in `:data`, 126
  in `:domain`, 7 in `:common`).
- **Not checked offscreen:** the tooltips, which don't show in an `ImageComposeScene`.

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
  - The branch name comes first in its row: it ends in "…" when the panel is too narrow for it and the icon. The
    worktree's name gets what's left, and is left out below about five characters. The icon always shows.
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
- **Tests:** 32 new.
  - `:data` (4): `BranchWorktreesTest` reads each worktree's branch as a full name, and finds none for a rebase or a
    bisect started on a detached HEAD, as git does. `WorktreesTest` gets the rebased and bisected branches attached to
    the right worktrees, and compares those worktrees by them.
  - `:domain` (19): which worktree uses a branch and what that rules out (`WorktreeBranchUsersTest`), the refresh that
    lets one call through at a time (`ConflatedRunnerTest`), which refreshes include the worktrees, and the rows:
    order, names, labels, ages, the filter, and when the branch list names a worktree (`WorktreeRowsTest`).
  - `:app` (9): the branch menu, with its labels read in a composition (`BranchContextMenuWorktreesTest`), how the
    branch name and the worktree's name share a row (`BranchWorktreeBadgeTest`), and the compact ahead/behind text.
  - **Mutation check:** 29 mutations, 27 caught. The two missed are equivalent: matching worktrees by path without
    making it canonical (git writes and lists real paths), and a shortcut for a blank filter (the filter already
    trims it), which is now gone. One mutation made `ConflatedRunnerTest` hang instead of fail; its tests now time out.
  - Checked once with a throwaway harness, deleted afterwards: the real side panel and log, rendered offscreen for a
    repository with an agent's worktree, a rebase stopped on a conflict, a locked, a detached and a deleted worktree,
    at the default width (220 dp) and at 320 dp, with tooltips, a branch's right-click menu and the filter. Three
    ways of showing a worktree's name next to its branch were rendered side by side, and the branch-first one was
    built.
  - `./gradlew build` passes, with 679 tests (27 in `:app`, 522 in `:data`, 123 in `:domain`, 7 in `:common`), after
    rebasing onto the log columns and libssh's removal.
- **Not yet:**
  - A per-repository base branch setting. The 1.2 entry expected it with 1.4. The base is still origin's default
    branch, else `main`, else `master`.
  - Double-clicking a worktree or a marked branch to open that worktree (Phase 2a).
  - Marks for the agent that made a worktree (Phase 4), next to the folder icon.

## Author, Date and Commit columns in the log (branch `feat/log-columns`)

- **Before:** the log showed the graph, the message with its branch and tag chips, and an unnamed date. The author was
  only in the graph node's tooltip, and the hash only in the commit details. The graph column's width, set by
  dragging, was kept in memory per tab.
- **Now:** a columns menu checks Author, Date and Commit. It opens from a new button next to search in the log's
  header, or by right-clicking anywhere on the header.
  - By default only Date is on, so the log looks as before, with a "Date" header.
  - **Author** shows the avatar and the name. Its tooltip gives the email, and who committed when that's someone else.
  - **Commit** shows the short hash in monospace, with the full hash in its tooltip.
  - **Show time**, in the menu while Date is on, adds the time in Settings' format at the end of the Date cell, a shade
    lighter, so that the times line up in a column of their own, and widens Date to fit it. Dates and times use
    digits of equal width, so numbers line up too. A time that doesn't fit is left out rather than cut; the tooltip
    still has it.
  - The three columns use the message's 13 sp text, Date and Commit in grey. Dates were 11 sp before.
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
- **Upstream:** `main` there has the same code in both actions and in `TempFilesManager`. Worth reporting there.
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

Stage 0b of `docs/fork/remote-operations.md`, and the message part of upstream issue 294.

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
  (upstream issue 137). "Delete tag" deleted at once too.
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
  (`use-keyboxd`, GnuPG 2.4's default) and made ED25519 signatures that failed to verify (upstream issues 194 and 293).
  Tag signing also threw "Unsupported gpg format: null" unless `gpg.format` was `gpg`, a value git doesn't have.
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
  now returns a `HelperAnswer` instead of filling JGit's credential items. As upstream's code, the file stays
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

- **Before:** `WindowsFs`, from upstream's c43e8e26 (upstream issue 314), ran hooks with Git Bash but returned
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
  `command not found` and blocked the commit (upstream issues 236, 321 and 298). The same went for post-checkout hooks
  and LFS filters run by `git worktree add`.
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
  `data/services/GitProviderService.kt` landed under upstream's own package folder without a conflict and
  was moved.
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
- **Tags:** `origin` fetches only `leaf-*` tags, and upstream's tags are fetched into `refs/upstream-tags/*`. The copies
  of upstream's tags in local `refs/tags` were removed, and all 24 copied upstream tags were deleted from
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
- **GPL-3.0-only:** files that come from upstream stay under `LICENSE`, even where the fork edited them.
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
- **Renamed:** the installer script to `leaf.iss`, the root Gradle project to `Leaf`, the exception class to
  `LeafException`, and the old names in the test fixtures.
- **Kotlin package:** the old one is now `dev.app.leaf`. The Gradle group, the Compose `Res`
  package (`dev.app.leaf.app.generated.resources`), the main class, the uniffi package and the GraalVM metadata (now in
  `META-INF/native-image/leaf/`) follow. Git records 72 small files as deleted and added rather than renamed, because
  most of their lines changed, so `git log --follow` may not connect their history.
- **Rust crate:** the old `…_rs` is now `leaf_rs`: the native library is `libleaf_rs.dylib` and the bindings are
  `leaf_rs.kt`.
- **Kept on purpose:** the credit to the original project, the `libssh-rs` and `kotars` dependencies from the original author's
  repositories, and the bundle ID `io.github.zzhelev.leaf`.
- **Verified:** after a clean build all 45 tests pass. The packaged app was launched with a temp repo:
  - the Rust file watcher reported a file change;
  - the extracted native library is `libleaf_rs.dylib`;
  - stderr has no exceptions besides the update check.

## README and DEVELOPMENT.md for Leaf (branch `docs/leaf-readme`)

- **README.md** now describes Leaf. It opens with a "Built on" section that credits the original project and its author,
  explains Leaf's separate roadmap, gives the fork date and the license, and welcomes sharing work in either direction.
  - Removed, because they describe the original app and not Leaf:
    - the upstream release badge;
    - the original app's screenshot (`res/img/cover.png` is kept);
    - the Flathub, Homebrew and release download links;
    - the Sponsors section.
  - Added a License section. Links to the original project's issue tracker stay.
- **DEVELOPMENT.md** says Leaf. It also corrects the requirements: JDK 25 (and a full JDK for packaging), no
  `cargo-kotars`, and Git LFS for the fonts.
- `CLAUDE.md` and `docs/fork/` still named the original project where they meant upstream or an installed copy of it
  (swept on 2026-10-20).

## Own storage for Leaf (branch `feature/leaf-own-storage`)

- **Leaf no longer shares data with the original app.** Every storage name now comes from the fork-only `AppStorage`
  (`common/storage/`), and none of them mention it. On macOS, a packaged Leaf uses:
  - the prefs node `LeafConfig` for tabs, recent repos and pane widths (was the original app's node);
  - `~/Library/Application Support/leaf/` for the settings file and `tmp/` (were the original app's folders, one
    of them missing "Support");
  - `~/Library/Logs/io.github.zzhelev.leaf/leaf.log` (was a log in the original app's own folder);
  - `<git dir>/leaf` for per-repository settings such as sign-off (was a file named after the original app).

  Linux and Windows use `leaf` in place of the original app's name in the same paths.
- **Fresh start.** Nothing is imported from the original app. Tabs, recent repos, settings and per-repository settings
  start empty.
- **Dev runs are separate.** Without jpackage's `jpackage.app-version` property, Leaf uses `LeafDevConfig`, `leaf-dev`
  and `io.github.zzhelev.leaf-dev`. So `./gradlew :app:run` can't change an installed Leaf's tabs or overwrite the
  native library a running Leaf extracted. A Linux `java -jar` run also counts as a dev run.
- **Fixed in passing:** the macOS app folder was `~/Library/Application/<app name>`, missing "Support" (an upstream
  bug).
- **Unchanged:** the Rust crate name, so the extracted library kept its old name (PLAN.md, rule 8).
- **Tests:** `AppStorageTest` has four tests:
  - packaged names;
  - dev and packaged runs share no name;
  - no name mentions the original app;
  - the test JVM counts as a dev run.
- **Manual check (macOS):** with the original app (1.5) running, launched and quit the packaged Leaf, then ran and
  stopped `./gradlew :app:run`.
  - Leaf created `LeafConfig` and the dev run created `LeafDevConfig`, each with its own folders and log.
  - The original app's prefs node and settings file were byte-for-byte unchanged.
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
  - All 46 native binaries in `Leaf.app`, and the native library inside the app jar, are arm64.
  - `codesign --verify --deep --strict` passes (ad hoc signature).
  - The bundled runtime has no `/opt/homebrew` links, and the real fonts are packed.
- **Not yet done:** launching the packaged app. Leaf still shares its prefs node with an installed copy of the original
  app.

## Renamed to Leaf (branch `feature/rename-to-leaf`)

This is one isolated commit touching only display name and packaging (PLAN.md, rule 8).

- **Display name:** the window title, Welcome page, About dialog, Settings subtitles, the settings tooltip and two
  error messages now say Leaf. The About text credits the original project.
- **Packaging:**
  - `projectName` is `Leaf`, so the outputs are `Leaf.app`, `Leaf-<version>.dmg` and `Leaf-linux-*.jar`.
  - The macOS bundle ID is `io.github.zzhelev.leaf`, so Leaf and an installed copy of the original app are separate apps
    to macOS.
  - The installer script has a new AppId, so a Windows Leaf install doesn't replace the original app. It also has new
    publisher, URL and exe name, and paths that follow the new build output.
  - In `.github/workflows/release.yml`, artifact names and paths follow `projectName`.
- **Unchanged:**
  - Kotlin package names.
  - The Rust crate.
  - Storage locations. Leaf still shares settings, tabs and logs with an installed copy of the original app.
  - The upstream links: Source code, Report a bug, Releases.
  - The update check against upstream's `latest.json`.
  - The release workflow's publishing target (the original project's repository, self-hosted runners).

## Leaf app icon (branch `feature/leaf-icon`)

- **New icon, "Midrib":** a lime leaf on a forest-green tile. Its veins are cut out of the leaf and branch off the
  midrib the way branches leave `main`. It was designed in the Claude Design project "Leaf app icon", which also holds
  the two rejected options.
- **Files were replaced in place**, so no config changed. `app/build.gradle.kts`, the installer script and the
  native-image reachability metadata still point at the same paths.
  - `icons/icon.icns`: macOS grid (an 824 px tile on a 1024 px canvas), with all ten iconset sizes from 16 to 1024.
    The old file only had 512.
  - `icons/icon.ico`: full-bleed, at 16, 24, 32, 48, 64, 128 and 256. The old file only had 256.
  - `icons/logo.svg` and `app/src/main/composeResources/drawable/logo.svg` (the window icon): full-bleed SVG.
- **The SVGs use only paths, strokes and one linear gradient.** There are no masks, transforms or clip paths; the vein
  cuts are painted with the tile's gradient. They were checked with Skia's SVG renderer, which Compose uses.
- The display name, package names and bundle ID still said the original name (PLAN.md, rule 8).

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
