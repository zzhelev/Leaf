# Window chrome: tabs in the title bar, a compact toolbar, quieter headers, and a Leaf style

| | |
|---|---|
| Question | Can Leaf's window look less dull without changing how it works? |
| Verified against | `main` at `6cd2994b` (Leaf 1.7.0) |
| Date | 2026-11-04 |
| Status | Built on 2026-11-05: steps 0 to 7 on macOS; step 8, Windows, built but not yet run on Windows. Directions A and C, and the decisions in section 8, chosen with the user on 2026-11-04 |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`.

**Mockups:** the Design artifact "Leaf window chrome review" (https://claude.ai/artifact/F95UMW4amkER3X2F47eGnj).
It has screenshots of today's window in light and dark (a dev run of 1.7.0, Tonal), a critique, and directions A, B and
C, each in light and dark. All three are one component, `Window.dc.html`, with a `variant` and a `theme`.

## Summary

- **A, for every style:**
  - The tabs move into the title bar, next to the window buttons. macOS first.
  - The toolbar becomes one compact row: a repository switcher, Pull and Push as one group, Branch, Stash and Pop as
    one group, an Actions button, and icon buttons for Terminal and Settings.
  - The panes get layered surfaces: the frame, the side panes and the content each have their own shade.
  - Headers lose their fill and the log header loses its column lines. Tonal's dark navy headers go.
- **C, as an option:** a new built-in style, "Leaf", with the frame in the logo's forest green. Tonal stays the default.
- **Not B.** The floating panes on a tinted background cost about 16 dp of width and add outlines.

## 1. What Leaf has today

| Piece | Where | Today |
|---|---|---|
| Title bar | `A/App.kt`, Compose's `Window(title = "Leaf - <tab>")` | The system's own bar, about 28 pt tall. Its title repeats the tab's name. |
| Tab bar | `A/ui/components/RepositoriesTabPanel.kt` (`TabsRow`, `Tab`), `App.AppTabs` | A 36 dp row under the title bar. Each tab is at least 200 dp wide, with a 3 dp colour stripe (`TAB_COLOR_STRIPE_HEIGHT`) and a 3 dp underline when selected. The conflict resolver's Focus hides the row (`AppChrome.tabBarHidden`). |
| Toolbar | `A/ui/Menu.kt` (`Menu`, `MenuButton`, `ExtendedMenuButton`), shown by `RepositoryOpenPage` | One row, icons above labels. Open and the worktree picker sit on the left, Pull/Push/Branch/Stash/Pop in the middle, Terminal/Actions/Settings on the right, spread out with weights. |
| Surfaces | `A/theme/Color.kt` | Light: `background` #FFFFFF (the log); `surface` #F8F8F8 (the side panel, the status pane and the toolbar); `tertiarySurface` #E9ECEF (headers). Dark: #0F0F0F, #1F1F22, and headers in navy #0A335C. |
| Headers | `FilesChangedHeader` (34 dp, `tertiarySurface` fill), `SideMenuHeader`, `GraphHeader` (column rules), and 47 uses of `tertiarySurface` in 20 files | Each header is a filled band. |
| Style slots | `D/styles/StyleSlot.kt`, `A/theme/styles/StylePalette.kt`, `StyleDerivation.kt` | 64 slots. Headers are `HEADERS` (`tertiarySurface`). There is no slot for the frame. |

## 2. The title bar (A1)

**The API.** JetBrains Runtime (JBR) lets an app draw its own title bar through `com.jetbrains.JBR.getWindowDecorations()`:

- `createCustomTitleBar()` and `setCustomTitleBar(frame, bar)`;
- on the bar: `setHeight`, `putProperty("controls.dark", …)`, `getLeftInset()` and `getRightInset()` (the room the
  window buttons take), and `forceHitTest(Boolean)`.

Probed on 2026-11-04 with this Mac's JBR 25.0.4.1 (API implementation 1.10.1): `isWindowDecorationsSupported()` is
true and `isWindowMoveSupported()` is false.

**The dependency.** `org.jetbrains.runtime:jbr-api:1.9.0` already ships with Leaf: Compose's `foundation-desktop`
1.12.1 depends on it with runtime scope. Leaf adds it as `implementation` in `app`, at the same version, so no new
library ships.

**Not Jewel.** Jewel's `DecoratedWindow` does this, but it needs Jewel's own theme (`IntUiTheme`, `ComponentStyling`),
which replaces Material. Leaf uses Material, so it calls the same JBR API itself.

**How it works** (a new `A/ui/window/`, fork-only):

- **Creation.** The `Window` content in `App.start` creates the bar once for its `ComposeWindow`.
- **Size.** The bar is 40 dp tall, times the UI scale (`scaleUi`), and is set again when the scale changes.
- **Window buttons.** `controls.dark` follows how light the frame is, which matters for the Leaf style.
- **Clicks.** By default the bar's area belongs to macOS: dragging moves the window, and a double-click zooms or
  minimizes it, as System Settings says. Leaf's controls in the bar (a tab, its ×, the + button) claim the pointer
  with `forceHitTest`, as Jewel's `Modifier.clientRegion` does. The exact way (a pointer-input pass on the bar, or a
  marker modifier on each control) comes out of the spike (step 0).
- **Insets.** The left padding is `leftInset`, the traffic lights' room, which is 0 in full screen. The right padding
  is `rightInset`, Windows' caption buttons.
- **The window's title** stays for macOS's Window menu, the Dock and Mission Control, but isn't drawn.
- **Fallback.** Where the API is missing (another JDK) or the platform is left out (decision D1), today's system bar
  stays and the tab row sits under it, in the new look.

**Platforms.**

- **macOS:** first, and tested here.
- **Windows:** JBR supports it, as IntelliJ uses it, but Leaf has never been run on Windows here. It came after the
  macOS work (D1, step 8).
- **Linux:** JBR draws no custom bar there: its `Window.CustomTitleBar` refuses anything but Windows and macOS ("Only
  supported on Windows and macOS", JBR 25.0.4.1). Jewel makes its own window on Linux (undecorated, with its own
  buttons, and moved through `WindowMove`), which brings resizing and Wayland problems. Linux kept the system bar until
  2026-11-06, and has Leaf's own frame since as an option, off by default (section 10).

**What the tabs keep:**

- each tab works as today: rename in place (double-click), drag to reorder, the right-click menu, the colour, the
  tooltip with the path;
- the keys: Cmd+T, Cmd+W, Ctrl+Tab;
- the conflict resolver's Focus hides the tabs, and the empty bar stays.

**A full tab bar** (option 1 of 3, chosen with the user on 2026-11-04, after the spike showed no way to move the window
once tabs filled the bar):

- The bar's last 110 dp at the right never hold a tab (`TITLE_BAR_FREE_STRIP`), so the window can always be moved from
  there. macOS still does the moving, so snapping to screen edges and tiling keep working.
- Tabs first share the bar equally, down to 120 dp each (`titleBarTabWidth`). Below that they scroll.
- In the bar, ‹ and › arrows (`TabScrollArrow`) replace the scrollbar that the tab row shows above itself elsewhere.
- Turned down: (2) the toolbar's empty space moving the window too, which Leaf would have to do itself below the 40 pt
  bar, losing edge snapping from there; (3) leaving only the strip around the window buttons.

**The tabs' look** (built 2026-11-05, step 2: `TitleBarTab` in `A/ui/window/TitleBarTabs.kt`, which upstream's `Tab`
hands over to with `inTitleBar`):

- Mockup A's sizes: 28 dp tall, 168 dp wide when there's room, corners of 7 dp, 4 dp apart. The repository's icon
  (`topic`), 15 dp, comes before the name; a Welcome page's tab has none.
- The selected tab is filled with `frame.control`, outlined with `frame.control.edge`, and on a light frame has a 1 dp
  shadow. Its name is `frame.text`, medium, and its icon `frame.icon`.
- The others are plain: name and icon in `frame.secondaryText`. A hover fills them with `frame.control` at half its
  strength.
- A tab with a colour keeps its 3 dp stripe along its top, cut to the corners. The underline that marked the selected
  tab is gone, as the fill marks it.
- Its × shows on hover and on the selected tab, as before. The rename field writes in `frame.text`.
- The + button is 28 dp, in `frame.secondaryText`, 4 dp after the tabs, and the arrows are 28 dp tall.
- The width rule counts the gaps: each tab's share is the bar less the + button and the gaps.

**Dialogs.** A `MaterialDialog` draws its scrim over the bar as well. While a dialog is open, a click on a tab under the
scrim must not select it.

## 3. The toolbar (A2)

One 48 dp row in the frame's colour (`frame`), with a hairline below. From left to right:

1. **Switcher**, "Leaf / main ⌄": the repository's name, then the tab's branch. With linked worktrees it shows the
   worktree and its branch. One popup holds today's two:
   - the worktrees, with the picker's two-line rows, when there are more than one;
   - the recent repositories, with "Open…".

   It replaces the Open button and the worktree picker. ⌘O opens it (D2).
2. A divider.
3. **Pull ⌄ | Push ⌄** as one outlined group. The label and the chevron are separate clicks, as in
   `ExtendedMenuButton` today.
4. **Branch**.
5. **Stash ⌄ | Pop** as one group.
6. A spacer.
7. **Actions…**, a button that looks like a field, showing ⌘K at its right. ⌘K (Ctrl+K elsewhere) is a new binding;
   nothing uses K in `Keybinding.kt` today (D3). Built as a plain button instead (see As built).
8. **Terminal** and **Settings** as icon buttons. Their tooltips name them and give their shortcuts.

**Buttons.** 32 dp tall, the icon left of the label, 13 sp, medium weight.

**A narrow window.** Labels drop from the right first (Branch, Stash and Pop, then Pull and Push), leaving their icons.
The switcher keeps at least the repository's name. The row is rendered at 800, 1100 and 1440 dp.

**Code.** The new row goes in a fork-only file (`A/ui/toolbar/`). Upstream's `Menu.kt` is deleted rather than left
unused, as was done with the interactive rebase list. Its Open popup (the `RecentRepositoriesList` part) moves into the
switcher. An upstream sync will then show `Menu.kt` as modify/delete: keep the deletion and port what upstream changed.

**As built** (2026-11-05, step 3):

- `A/ui/toolbar/Toolbar.kt`: `Toolbar` reads the tab's state, and `ToolbarRow` draws it from a `ToolbarState` and
  `ToolbarActions`, which its tests render. Frame colours throughout: groups, Branch and Actions are filled
  with `frame.control` and outlined with `frame.control.edge`; Terminal and Settings are icons in
  `frame.secondaryText`, filled on hover; a disabled part is at 45%. The row has `separators` along its bottom.
- The switcher (`A/ui/toolbar/RepositorySwitcher.kt`) shows the repository and the linked worktree
  (`RepositoryTabViewModel.repositoryNames`, so a renamed tab's name isn't used), "/", then what is checked out:
  the worktree list's words ("rebasing x") when there's one, else the branch, else "detached at 0123456"
  (`switcherBranchLabel`). Its popup has the worktrees with the picker's rows (`WorktreePickerRow`, `NewWorktreeRow`,
  now `internal` in `A/ui/WorktreePicker.kt`, whose button and list went), then "Open a repository…" and the recent
  repositories, whose search field has the focus. ⌘O opens it, as it opened the Open popup.
- The labels' rule (`toolbarLabelsFor`): Branch, Stash and Pop show theirs from 1,150 dp, Pull and Push from
  1,000 dp. Actions keeps its label at every width.
- ⌘K (Ctrl+K elsewhere) is `KeybindingOption.QUICK_ACTIONS`, which the tab's keys open the Actions list with.
- **Actions is a button** (`ActionsButton`), outlined as Branch is: the bolt, "Actions" (upstream's `menu_actions`) and
  the shortcut after it in the frame's muted text (`KeybindingHint`, "⌘+K"), as wide as they are, which is narrower than
  the field was at its narrowest. It was first built as the mockup had it, a button that looked like a search field,
  reading "Actions…" with the shortcut in a chip, 248 dp wide from 900 dp and 160 dp below. The user found that it read
  as a search box. From renders of the field, this button and a field reading "Find an action…", in light and dark at
  1,280 and 900 dp, the user chose the button (2026-11-05): nothing else in the toolbar looks like a field, and the
  Actions list's own field is where one types.
- `Menu.kt` isn't deleted: upstream's `KeybindingHint` and `getParts` in it are used by the pane toggles' tooltips
  and the Actions list. `Menu`, `MenuButton` and `ExtendedMenuButton` went. An upstream sync that changes them shows
  a conflict there: keep them out.
- `InstantTooltip` puts its popup next to its content, in the layout around it, where it takes a place of no size.
  The toolbar's rows space their children, so a showing tooltip got a gap of its own and moved the controls after it
  (Stash and Pop by 10 dp when Branch was hovered, found by the user). `ToolbarTooltip` puts it in a box of its own.
- Tests: `ToolbarTest` (the rule, the labels at 1,440, 1,100 and 800 dp, nothing overlapping or past the edge, each
  button's action, disabled Stash and remotes, a hovered button's tooltip moving nothing, Actions' label and shortcut
  and the same width at 1,440 and 800 dp; PNGs in five styles at 1,440 and 900 dp with `LEAF_RENDER_DIR`) and
  `RepositorySwitcherTest` (the worktree picker's tests, moved: the label, the list, switching, a missing folder, New
  worktree; and a repository with one worktree, Open, a recent repository, the label's rule; PNGs of the open popup).

## 4. Surfaces and headers (A3)

**Seven new slots**, in a group of their own, Window frame (built 2026-11-05, step 1). The plan had six; mockup C needs
the seventh, `frame.icon`, for its leaf green on the frame:

| Slot | Key | Used for |
|---|---|---|
| `FRAME` | `frame` | the title bar and the toolbar |
| `FRAME_TEXT` | `frame.text` | text in the frame |
| `FRAME_SECONDARY_TEXT` | `frame.secondaryText` | muted text in the frame |
| `FRAME_ICON` | `frame.icon` | icons that carry the frame's colour, as the repository's |
| `FRAME_CONTROL` | `frame.control` | the selected tab, the switcher, button groups |
| `FRAME_CONTROL_EDGE` | `frame.control.edge` | their outlines |
| `SEPARATORS` | `separators` | the hairlines between the panes and under the toolbar and the headers |

- A style file without these keys reads its look's values, as it already does for any missing slot.
- The theme provides them as `FrameColors` (`A/theme/FrameColors.kt`, `MaterialTheme.frameColors`). The title bar draws
  its background with `frameColors.background`.
- The style editor's Advanced table has them under Window frame, after Surfaces: "Title bar and toolbar" (fill, text,
  icon), "Muted text in the frame", "Selected tab and buttons" (fill, edge), "Lines between panes".

**The looks' values** (`frameColorsFor`):

| | Classic | Tonal and Quiet, light | Tonal and Quiet, dark |
|---|---|---|---|
| `frame` | the panels' colour (`surface`), as its toolbar has | #F1F2F4 | #1D1E21 |
| `frame.text` | the text colour | the text colour | the text colour |
| `frame.secondaryText` | the secondary text colour | #5B616B | #A3A8B0 |
| `frame.icon` | the links' colour | the links' colour | the links' colour |
| `frame.control` | the background, as its selected tab has | #FFFFFF | #2A2C30 |
| `frame.control.edge` | #D3D7DD, dark #3A3D43 | #D3D7DD | #3A3D43 |
| `separators` | #E3E6EA, dark #2B2D31 | #E3E6EA | #2B2D31 |

Step 4 changes the panels and the content of Tonal and Quiet to mockup A's:

| | Light | Dark |
|---|---|---|
| Side panes (`panels`) | #F7F8F9 | #18191C |
| Content (`background`) | #FFFFFF | #121315 |

**Seeds.** The frame, its controls and the separators follow Background, its text follows Background as text does, and
its icon follows Accent. Step 5's Frame seed (D6) takes Background's place for them.

**Contrast checks.** `frame.text` and `frame.secondaryText` hold 4.5 : 1 on `frame` and on `frame.control` over it, and
`frame.icon` 3 : 1, moved by the existing rounds like any other colour. Separators are decoration and aren't checked.
Solarized's dark muted frame text held 3.98 on its controls without the check, and is moved now.

**Headers.**

- Tonal and Quiet (so Solarized and Teal too): `HEADERS` takes the panel's colour and a hairline goes below each
  header. The dark navy goes.
- `SideMenuHeader`: an 11 sp label with its count, as in the mockup.
- `GraphHeader`: the column lines go. The dividers you drag show on hover.
- Classic keeps its header colours (D4).

**The branches pane's selected row:** an inset highlight with rounded corners, 4 dp in from each side. The log keeps
its full-width rows.

**As built** (2026-11-05, step 4):

- **Colours:** `ColorsScheme.styled` gives Tonal and Quiet mockup A's panels (#F7F8F9, dark #18191C), and headers of
  the same colour. The dark background stays #0F0F0F: on mockup A's #121315 the code's annotation colour held 4.47 : 1
  on the added lines, and five code colours would have moved a shade in every style, Classic's included.
- **Headers follow the panels:** in Tonal and Quiet, the headers take the panels' worked-out colour unless a style sets
  them by hand (`sharedColourOf` in `StyleDerivation.kt`), so they match where the panels are set by hand too, as on
  Solarized's dark side (base02). The headers' checks are some of the panels', so they hold. Teal's dark headers set
  by hand (#003B3A) went; its boxes stay.
- **The line:** `Modifier.headerBackground()` (`A/theme/HeaderBackground.kt`) fills a header and, when the headers'
  colour is the panels', draws a line in `separators` along its bottom. Classic's filled headers get none. Used by the
  status pane's headers and the commit message's title, the log's header, the diff's header and its conflict bar, the
  followed-file placeholder, blame's and file history's headers, the selected commits' header, the comparison's
  headers, and the conflict resolver's headers and legend. The commit details footer, the comparison's footer, and the
  resolver's file list and its edge keep a plain fill.
- **The branches pane** is on the panels' colour, with its pinned section headers. It was on the background, not on the
  panels as section 1 said.
- **Section headers** (`SideMenuHeader`): a 14 dp chevron, the label in capitals at 11 sp, semibold with 0.06 em
  between letters, in the secondary text colour, and the count at 11 sp. The sections' icons went, as in the mockup.
- **The log's column lines** (`DividerLog`) show while the pointer is on them, in the header and along the graph's
  edge.
- **The selected row** (`insetSelection`, `A/ui/components/InsetSelection.kt`): rounded 6 dp, 4 dp in from the pane's
  sides and 1 dp from the rows above and below, across the pane whatever the row's depth.
- In Classic, the headers keep their fill and get no line; the section headers, the log's lines, the selected row and
  the branches pane's colour change as in the other looks.
- Tests: `StyleDerivationTest` (the headers are the panels' in every built-in style but Classic and for 2,000 random
  seed sets per look and side; headers set by hand stay; a surface that text set by hand fails on moves, the headers
  with it), `CommitMessageFieldTest` (the line under the status pane's header and the commit message's title in Tonal,
  none in Classic), `BuiltInStylesTest` (Teal's colours set by hand).

## 5. The Leaf style (C)

**What it is:** a built-in style, id `leaf`, with the Tonal look. It comes after Teal in the gallery and is not the
default.

**Its colours:**

| | Light | Dark |
|---|---|---|
| Frame | #0F3B27 | #0E2419 |
| Frame text, muted text | #E8F3EC, #A9CDB7 | (same) |
| Controls in the frame | #1A4D34 | (same) |
| Selected tab | #1F5238 | (same) |
| Selected tab's mark | leaf green #8EDC72 | (same) |
| Side panes | #F3F6F3 | #101A15 |
| Selected row | #DCEBDD | worked out from the accent |
| Content | #FFFFFF (Tonal's) | #0C120F |

`controls.dark` is true on both sides, as the frame is dark in both.

**The accent (D5):**

- **(a) Green everywhere, as in the mockup:** main action #1F6B45, and links, selection and lane 1 #3E8E63.
- **(b) Recommended: a green frame and main action, with Tonal's blue for selection, links and lane 1.** Green also
  means "added" in the file lists and diffs. A green selection next to green "added" icons reads as one thing.

**A Frame seed (D6, recommended):** a sixth seed in the style editor that the seven frame slots follow. Then:

- Leaf is simply Tonal with a green frame;
- any user style can colour its frame with one swatch;
- a frame equal to the look's own keeps the look's values exactly, as the other seeds do.

Without the seed, Leaf sets its frame slots by hand, and user styles change them one by one in Advanced.

**As built** (2026-11-05, step 5):

- **The Frame seed** is `StyleSeeds.frame`, one per side and optional. Without it, the frame's colours follow the
  background as before, so the other built-in styles and every user file stay as they were. It isn't a `StyleSeed`, as
  each of those always has a colour. Files write it as `"frame"` in a side's seeds only when it's set; one that isn't a
  colour follows the background, and a see-through one is made opaque. An older Leaf reads such a file and leaves the
  frame out.
- **What it moves** (`FrameStart` in `StyleDerivation.kt`): the frame, its text and muted text, its controls and their
  edge start from the look's frame on the side whose frame is as dark as the seed (luminance 0.179, where black and
  white text hold the same contrast), and move as far as the frame moved from that side's own. So Leaf's green on the
  light side starts from the dark side's light text and dark controls. A frame equal to the look's own keeps the look's
  colours exactly. The frame's icon follows the accent. A mid-tone frame is first made lighter or darker until black or
  white text holds on it and its controls (`fittedFrame`, as the background is fitted); 2,000 random seed sets per look
  and side, a third with a frame, found the case.
- **The separators keep following the background**, not the frame: since step 4 they draw the headers' lines in the
  content too. So the toolbar's divider after the switcher takes `frame.control.edge`, and the ⌘K hint in the Actions
  field `frame.secondaryText` (`KeybindingHint` has a colour parameter, whose default the tooltips keep): on Leaf's
  light side both were the content's colours, pale and dark on dark green.
- **The Leaf style** (`BuiltInStyles.LEAF`, after Teal): Tonal's seeds with the frame #0F3B27 (dark #0E2419) and the
  dark background #0C120F. Set by hand: the main action #1F6B45 (dark #2F7D52), mockup C's panels #F3F6F3 (dark
  #101A15), the leaf green #8EDC72 of the frame's icon, and the frame's muted text #B4D6C2. Selection, links and lane 1
  are Tonal's blue (D5). The rest of the frame is worked out: controls #1E4B37 and edges #305D4A against the mockup's
  #1A4D34 and #2C6247, and white text.
- **The muted text** (inactive tabs, a worktree's name in the selected tab, "/" in the switcher, the Actions field's
  text and key, now the Actions button's key) was worked out as #9AB5AE, which held only the minimum 4.5 : 1 on the
  frame's controls, where Tonal has 6.2. Chosen with the user from renders of the tab bar and the toolbar with four
  greens (2026-11-05): #B4D6C2, 6.3 : 1 on the controls and 8.0 on the frame, still clearly dimmer than the white text.
  The mockup's #A9CDB7 held 5.7, and a lighter #C8E3D3 made the other tabs look as bright as the selected one.
- **`controls.dark`**, which the plan listed, is a property of JBR's custom title bar that only Windows reads. Step 8
  sets it from the frame's darkness.
- **The editor:** a Frame row after Background, with a swatch per side showing the worked-out colour until one is
  picked, and "Use the worked-out colour" in the picker to follow the background again (`StyleEditorState.withFrame`).
  The preview has no frame: a strip of it at the top of each side pushed the dark side's files and Commit out of the
  dialog, which is 680 dp tall to fit Leaf's default window. The Advanced table's Window frame rows show its colours.
- **Settings' gallery** needed nothing: its cards don't show the frame, and Leaf's stands apart by its green main
  action. Import moved to a third row, which `StyleGalleryTest` now allows, and `CodeColoursRowTest` looks for its
  drop-down's "Leaf" right of the row's title, as the style's card above has the name too.
- Tests: `BuiltInStylesTest` (Leaf's place, seeds and colours set by hand; only Leaf has a frame), `StyleFileCodecTest`
  (the key written only when set, read back, broken and see-through frames, frames in the random round trips),
  `StyleDerivationTest` (a frame equal to the look's own, the other side's frame giving that side's colours, no frame
  following the background, Leaf holding every check with light text on its frame, 6 : 1 for its muted text on the
  controls and Tonal's blue, random frames),
  `StyleEditorStateTest` (the frame per side, opaque, and back to the background). `ToolbarTest` and `TitleBarTabsTest`
  render Leaf too.

## 6. Smaller changes from the mockup (D7, each on its own)

- ⌘K for Actions (section 3).
- The Commit button says "Commit to main".
- Empty lists say so: "Nothing staged", "Working tree clean".
- The Open button goes. "+" in the title bar opens a new tab with the Welcome page, as today, and the switcher has
  "Open…".

**As built** (2026-11-05, step 6; ⌘K and the Open button went in step 3):

- **"Commit to main":** `UncommittedChangesButtons` names the branch that the commit goes on, which `RepositoryOpen`
  passes down (`StatusPane`'s and `CommitField`'s `branchName`, from `branchesState.currentBranch`), on a second,
  smaller line under "Commit" (`ConfirmationButton`'s `secondLine`, the caption style at 85% of the label's colour):
  "Commit" over "to main", and "Amend" over "on main" while amending, so that switching Amend doesn't move the field.
  The button is 46 dp then (`TWO_LINE_BUTTON_HEIGHT`), 10 dp taller, and takes them from the message field. On a
  detached HEAD, whose branch is named HEAD, it's "Commit" on one line, 36 dp, as before. A merge's, a cherry-pick's
  and a rebase's buttons are as they were.
  - **A long name** (chosen with the user from renders at 330 dp: cut at the end, cut in the middle, or this): one
    line on its own leaves the name about 45 characters at the pane's default width, where "Commit to" on one line
    left about 30, and an agent's branch name often didn't fit. A name that still doesn't fit is cut in the middle
    (`MiddleCutText`, `middleCut`), keeping its start, a ticket key included, and its end, where agents' names often
    differ (`…-on-safari`, `…-on-firefox`); its tooltip has the whole name. Compose's `TextOverflow.MiddleEllipsis`
    cuts at the end on desktop (1.12.1).
  - At a bigger text size (merged from `main` on 2026-11-05) the 46 dp grows by both its lines' growth
    (`twoLineButtonHeight`), and so do the title bar, its tabs, the toolbar's row and controls, and the switcher.
- **Empty lists** (`EmptyListNote.kt`): in the middle of an empty list, in the secondary text colour, "Nothing staged"
  for Staged, and for Unstaged "Working tree clean" with a check when Staged is empty too, else "No unstaged changes",
  as files are still to be committed. A search that hides every file says "No files match". Nothing is said before a
  status has been read (`StatusState.hasStatus`; `isLoading` turns on during any refresh longer than 300 ms, and the
  note would blink), nor in Staged while Amend lists the last commit's files under it.
- Tests: `EmptyListNoteTest` (each note's rule, a search, before a status, Amend's files, and the notes in the lists;
  PNGs in both themes with `LEAF_RENDER_DIR`), `CommitFieldTest` (the branch, a detached HEAD, Amend keeping the
  height, a name that fits whole, a long one cut in the middle), `MiddleCutTextTest` (the cut keeps both ends and
  always fits, and never splits a character outside the BMP).

**The commit message in the centre pane** (2026-11-05, asked for after step 6): on a laptop the changes pane's message
field is small, and a message that an agent wrote is long. Chosen with the user from previews of three ways: growing
the field in the changes pane, which stays 330 dp wide, so a 72-column line still wraps; a dialog, which covers the
diff and the files that the message is about; and this one.

- **Opening it:** a button in the "Commit message" title row, before ⋯ (`MessageSizeButton`, Material's `open_in_full`
  and `close_fullscreen`; "Write in a larger view"). Not on a rebase stop that can't be amended, whose field can't be
  edited. `StatusAction.ExpandMessage` sets `StatusViewModelExtender.isMessageExpanded`, which `RepositoryOpenViewModel`
  hands on; not in `StatusState`, whose `combine` is near the 30 flows that `common` has overloads for.
- **Where it goes:** `MainContentView` puts `CommitMessageEditor` in the log's place in `LogAndDiffSplit`, so a file
  that is clicked in Staged or Unstaged shows its diff under the message, with the split's sizes. A diff never covers
  the message there (`keepsLog` is on), and the diff's Hide log button isn't shown. At 1440 pt with the side panes at
  their defaults, the field is about 870 dp wide where the changes pane's is 330, so a 72-column line fits.
- **The two parts** (`CommitFieldPart`): the centre has the title row, with the subject's count, ⋯ and the button that puts it
  back, the field with its suggestions, and the hints about the message (`MESSAGE`). The changes pane keeps the title row
  with the button back and no menu, "Being written in the larger view" in the field's place, the line that says what
  stops the button, and the buttons (`FINISH`). Its lists get the field's room: the section is the smallest that
  `StatusSectionSizes` allows while the message is away, and its divider doesn't move or save then. Both parts are
  `StatusCommitField`, which builds `CommitField`'s parameters from the state, as `StatusPane` did, so they are one
  message.
- **The focus:** the centre's field takes it when it opens, with the caret where it was. When the message goes back,
  however it goes, the changes pane's field takes it (`ChangesPaneCommitField`).
- **Going back:** the button in either title row, or Esc (`CloseableView.COMMIT_MESSAGE_EDITOR`, which comes after
  the views that were open before it, and before a diff or the suggestions that open after it: Esc closes a file's
  diff first). A commit that went through puts it back too, as the message is gone. So does anything else that takes
  the centre pane or the changes pane: a selected commit, a comparison, the commit editor, expanded blame, or folding
  the changes pane. File history, the conflict resolver and a comparison's full view cover the whole tab, and the
  message is in the centre again when they close.
- Tests: `CommitMessageEditorTest` (the two panes rendered together with the state that the view model keeps: opening
  it with the focus and the caret; one field, the centre's menu, the changes pane's buttons and line; what is typed in
  the centre committed from the changes pane; going back from either title row with the focus; no button on a field
  that can't be edited). Four mutations (the focus on going back, the menu in the changes pane, the buttons in the
  centre, the button on a read-only stop) were each caught by a named test. The real tab ran through the Dagger graph
  in a temporary harness against a demo repository, in both themes: opening it, typing, a file's diff under the
  message, Esc closing the diff then putting the message back with the focus, and ⌘↵ in the centre committing and
  putting it back. The view model's part (`expandMessage`, Esc, the commit) has no kept test, as it needs the graph.

## 7. Steps

0. **Spike (macOS).** A dev run with the JBR title bar and the real tab row in it. Check:
   - clicks, the × and +, drag to reorder, rename, the menu;
   - dragging the window from empty space, double-click to zoom;
   - full screen;
   - an open dialog;
   - scale 1.25;
   - window buttons in light and dark.

   If it can't be made to work, A goes ahead without the unified title bar: the tab row stays under the system bar,
   restyled.

   **Spike, 2026-11-04** (`A/ui/window/WindowTitleBar.kt`, `TitleBarTabs.kt`):
   - The user tried it in a dev run: clicks, reordering, renaming, moving the window, and full screen all worked.
   - JBR reports an inset of 80 pt for the window buttons, and 0 in full screen.
   - The bar is set again only when its height or the display's scale changes. Setting it again as full screen started
     made JBR drop a resize event ("invokeAndWait is discarded as the EventDispatch thread is currently blocked").
     Full screen now only removes the left padding.
   - A full tab bar left no room to move the window, which led to the free strip above.
1. **Style slots.** Done 2026-11-05: the seven slots of section 4, their keys, derivation rules, contrast checks, and the
   Window frame group in the editor's Advanced table. Nothing on screen changes but the title bar's background, which is
   the frame's colour.
2. **Title bar with the tabs.** macOS, with the fallback for every other case. Built 2026-11-05: the spike above, and the
   tabs' look. `TitleBarTabsTest` renders them in Tonal, Classic, Solarized and Teal, light and dark, when
   `LEAF_RENDER_DIR` is set.
3. **Toolbar** (section 3). Built 2026-11-05.
4. **Surfaces and headers** (section 4). Built 2026-11-05.
5. **The Leaf style** (section 5), and the Frame seed if D6 is yes. Built 2026-11-05, with the seed.
6. **The smaller changes** that D7 keeps. Built 2026-11-05.
7. **Docs:** CLAUDE.md, `docs/fork/CHANGELOG.md`, and the README's screenshots if they show the window. Done
   2026-11-05: CLAUDE.md's "The window" and its Styles, the changelog, and the README's two screenshots, taken again
   from a dev run's window at 1600 × 900 (how: CLAUDE.md, Offscreen UI checks) against a demo like the old one's.
8. **Windows** (D1), once steps 0 to 7 are approved on macOS: the same title bar, with the caption buttons on the right
   (`rightInset`), checked on a Windows machine. Built 2026-11-05, and tested on macOS only (`TitleBarInsetsTest`):
   - `windowTitleBarOf` gives Windows the JBR bar too.
   - The content ends `TITLE_BAR_FREE_STRIP` before `rightInset`, Windows' buttons, so the window still moves from the
     110 dp next to them. It starts at least 12 dp from the left edge (`TITLE_BAR_EDGE_PADDING`, the toolbar's), as
     Windows leaves no buttons there; macOS in full screen gets the same, where the first tab touched the screen's edge.
   - `controls.dark` follows the frame (`hasDarkBar`: its text lighter than it), so the buttons are light on dark
     themes and on the Leaf style's green. JBR sets the bar again when the value changes; on macOS nothing is set.

   **Still to check on a Windows machine** (Windows 10 and 11, at 100% and 150% scaling, light and dark, and the Leaf
   style):
   - the buttons: drawn at the right, light or dark as the frame needs, hover and the red close, Windows 11's snap
     layouts on the maximize button;
   - the bar: dragging from the free strip and from the empty bar, a double-click maximizing and restoring, dragging to
     the screen's top or sides to snap, a right-click or Alt+Space giving the system menu;
   - the tabs: clicks, the × and +, reordering, renaming, the menu, the arrows with many tabs;
   - a maximized window: the bar's top not cut off, and the buttons in place;
   - an open dialog's scrim over the bar, and a click on a tab under it doing nothing;
   - the window's title in the taskbar and Alt+Tab.

9. **Linux, as an option** (section 10). Built 2026-11-06, after the user asked how much it would be and how the Linux
   code would be kept apart.

**Tests:**

- The JBR calls sit behind a small interface (`WindowTitleBar`), so a fake can test which controls take the pointer.
- The toolbar is rendered at the three widths above.
- `StyleDerivationTest` covers the new slots across its 2,000 random seed sets per look and side.
- `StyleFileCodecTest` reads a file from before the new slots.
- The whole tab is rendered in all six built-in styles, light and dark, before and after, through the Dagger graph in a
  temporary harness, as for the styles work.
- The real window is captured in a dev run in light and dark, as in the review.

## 8. Decisions

All decided with the user on 2026-11-04: D1 as below, the others as recommended.

| | Question | Decision |
|---|---|---|
| D1 | Which platforms get tabs in the title bar? | macOS now. Windows once the macOS work is done and approved (step 8). Linux keeps the system bar; since 2026-11-06 it has Leaf's own frame as an option, off by default (section 10). |
| D2 | One switcher for the worktrees and recent repositories, replacing Open and the worktree picker? | Yes |
| D3 | ⌘K for Actions? | Yes |
| D4 | Does Classic keep its filled headers? | Yes: it's Leaf's old look |
| D5 | The Leaf style's accent? | (b): green frame and main action, blue selection and links |
| D6 | A Frame seed in the style editor? | Yes |
| D7 | The smaller changes in section 6? | "Commit to main" and the empty texts yes; Open goes once the switcher has "Open…" |

## 9. Risks

- **Windows and Linux** can't be tried here. Hence D1. Linux was tried in a container since (section 10), not on a real
  desktop.
- **Click handling** depends on how JBR asks Compose about a click. That's the spike's job.
- **Sessions running now:**
  - "Renames in the Staged list" touches the status pane's rows;
  - "Undo a discard" touches the status pane;
  - "Find and go to line" touches the diff's and blame's headers.

  Step 4 touches `FilesChangedHeader` and the diff's header, so it goes after those land, or rebases onto them.
- **Upstream files:** `App.kt`, `RepositoriesTabPanel.kt`, `SideMenuEntry.kt`, `FilesChangedHeader.kt` and `Log.kt`
  all come from upstream. Keep their changes small and put new code in fork-only files.
- **Tests that read header colours or pixels:** `StyleRenderTest`, `StyleColorsContrastTest`, `AppThemeStylesTest`,
  `CommitMessageFieldTest` (which compares its title row with `FilesChangedHeader`), `TabLookMenuTest` (the tab bar)
  and `LogBranchFilterControlTest`. They change in the step that changes what they check.

## 10. Linux: Leaf's own frame, as an option

Built 2026-11-06. The user asked first what it would take, and how the Linux code would be kept apart from the rest.

**Why it isn't the same as on macOS and Windows.** There JBR draws the title bar, and Leaf puts the tabs in it. On
Linux JBR offers only `WindowMove.startMovingTogetherWithMouse`, which asks the window manager to move the window
(`_NET_WM_MOVERESIZE` on X11). So the window is undecorated, and Leaf does the frame's work, as IntelliJ does behind
its "Merge main menu with window title" option and Jewel's `DecoratedWindow` does.

**The setting.** Settings → Appearance → "Tabs in the title bar", on Linux only, off by default
(`AppConfig.LinuxTitleBar`, key `linux_title_bar`). A window's frame can't come or go while it's open, so it's read when
Leaf starts (`LinuxFrame.atStart`), and the row says "Restart Leaf to apply it." while the setting and the window
differ. Where the desktop doesn't allow it, the row is greyed out and says why.

**Where it's allowed** (`linuxFrameSupport`): JBR's `WindowMove` (a `.jar` run on another Java has none), AWT's X11 or
Wayland toolkit, not WSL, no tiling window manager, and a desktop known to stack its windows, by `XDG_CURRENT_DESKTOP`,
`XDG_SESSION_DESKTOP` and `DESKTOP_SESSION`. IntelliJ leaves out the same cases.

**What Leaf does:**

- **Moving** (`LinuxWindowTitleBar`): a drag from the empty bar, once it passes 4 px, starts the window manager's move,
  so snapping to edges and dragging to another screen keep working. A double-click maximizes or restores. Starting the
  move on the press, as Jewel does, failed in the container: after a quick click, whose release came before the window
  manager's grab, xfwm4 went on moving the window until the next click.
- **The window buttons** (`WindowButtons`): in the desktop's own layout, read once at start: GNOME's, Cinnamon's and
  MATE's through `gsettings`, KDE's through `kreadconfig6` or `kreadconfig5`, Xfce's through `xfconf-query`, and
  otherwise minimize, maximize and close at the right. GNOME's own default is close alone. Leaf's look: plain glyphs in
  the frame's text colour, filled as the tabs are on hover, and a red close button.
- **The frame** (`LinuxWindowFrame`): a 1 dp line around the window, none while maximized, and a smallest size of
  640 × 400. Resizing from the edges is Compose's own: an undecorated `ComposeWindow` has `UndecoratedWindowResizer`,
  a layer over the content. Leaf's first version had resize handles of its own, which the container showed were never
  reached. Without the smallest size, a drag on the bottom edge left the window 1 px tall.

**How it's kept apart.** Everything Linux-only is in `ui/window/linux/`. The shared title bar got two hooks whose
defaults do nothing, so macOS and Windows run as before (their tests pass unchanged): `pointerOnBar` (the press, moves
and release of a press that began off the controls) and `startButtons` and `endButtons` (drawn in the bar's row around
the content). `App.kt` decides the frame before the window exists, passes `undecorated`, takes the Linux bar when
there's no JBR one, and wraps the content in `LinuxWindowFrame`, which shows the content alone without a Linux frame.

**Checked in a container** (Docker on this Mac: Ubuntu 24.04 arm64, Xvfb at 1600 × 1000, JBR 25.0.4.1 for Linux,
Leaf's jars with skiko's Linux runtime, driven with xdotool, screenshots taken with ImageMagick). Under xfwm4 (Xfce),
Mutter (GNOME) and KWin (KDE), all on X11:

- Leaf starts maximized, with the tabs in its bar and the desktop's buttons (three under xfwm4 and KWin, close alone
  under GNOME's default);
- a double-click on the bar restores it, and a click alone moves nothing;
- a drag on the bar moves the window;
- the right edge, the bottom edge and the top-left corner resize it, and the bottom edge stops at 400 px. Fine,
  mouse-like drags (40 steps of 3 px) follow within 3 px on every edge;
- maximize, restore, minimize and close work, and close quits as the window's close does;
- with the setting off, or with the setting on and `XDG_CURRENT_DESKTOP=i3`, the system's frame stays.

**Not checked:** a Wayland session (Leaf runs under XWayland there, as it doesn't ask for JBR's Wayland toolkit),
real scaling such as 200%, and how it feels on a real desktop. The user's Linux machine, or a virtual machine, is the
next check before calling it done.

**Lost against the system's frame:** the window menu on a right-click on the bar, the desktop's own button look, and
rounded corners and the shadow (an undecorated window has neither).

