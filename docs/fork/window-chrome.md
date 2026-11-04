# Window chrome: tabs in the title bar, a compact toolbar, quieter headers, and a Leaf style

| | |
|---|---|
| Question | Can Leaf's window look less dull without changing how it works? |
| Verified against | `main` at `6cd2994b` (Leaf 1.7.0) |
| Date | 2026-11-04 |
| Status | Plan. Directions A and C, and the decisions in section 8, chosen with the user on 2026-11-04 |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`.

**Mockups:** the Design artifact "Leaf window chrome review" (https://claude.ai/artifact/F95UMW4amkER3X2F47eGnj).
It has screenshots of today's window in light and dark (a dev run of 1.7.0, Tonal), a critique, and directions A, B and
C, each in light and dark. All three are one component, `Window.dc.html`, with a `variant` and a `theme`.

## Summary

- **A, for every style:**
  - The tabs move into the title bar, next to the window buttons. macOS first.
  - The toolbar becomes one compact row: a repository switcher, Pull and Push as one group, Branch, Stash and Pop as
    one group, an "Actions…" field, and icon buttons for Terminal and Settings.
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
- **Windows:** JBR supports it, as IntelliJ uses it, but Leaf has never been run on Windows here. It comes after the
  macOS work is done and approved (D1, step 8), and keeps the system bar until then.
- **Linux:** JBR draws no custom bar there. Jewel makes its own window on Linux (undecorated, with its own buttons, and
  moved through `WindowMove`), which brings resizing and Wayland problems. Linux keeps the system bar.

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

**The tabs' look.** Each tab is a pill 28 dp tall. The selected one is filled (`chrome.control`) and keeps its 3 dp
colour stripe. The others are plain text.

**Dialogs.** A `MaterialDialog` draws its scrim over the bar as well. While a dialog is open, a click on a tab under the
scrim must not select it.

## 3. The toolbar (A2)

One 48 dp row in the frame's colour (`chrome`), with a hairline below. From left to right:

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
   nothing uses K in `Keybinding.kt` today (D3).
8. **Terminal** and **Settings** as icon buttons. Their tooltips name them and give their shortcuts.

**Buttons.** 32 dp tall, the icon left of the label, 13 sp, medium weight.

**A narrow window.** Labels drop from the right first (Branch, Stash and Pop, then Pull and Push), leaving their icons.
The switcher keeps at least the repository's name. The row is rendered at 800, 1100 and 1440 dp.

**Code.** The new row goes in a fork-only file (`A/ui/toolbar/`). Upstream's `Menu.kt` is deleted rather than left
unused, as was done with the interactive rebase list. Its Open popup (the `RecentRepositoriesList` part) moves into the
switcher. An upstream sync will then show `Menu.kt` as modify/delete: keep the deletion and port what upstream changed.

## 4. Surfaces and headers (A3)

**Six new slots** in the Surfaces group:

| Slot | Used for |
|---|---|
| `chrome` | the title bar and the toolbar |
| `chrome.text` | text and icons in the frame |
| `chrome.secondaryText` | muted text in the frame |
| `chrome.control` | the selected tab, the switcher, button groups |
| `chrome.controlEdge` | their outlines |
| `separators` | the hairlines between the panes and under the headers |

A style file without these keys reads its look's values, as it already does for any missing slot.

**Worked out from Background**, with values from mockup A:

| | Light | Dark |
|---|---|---|
| Frame (`chrome`) | #F1F2F4 | #1D1E21 |
| Side panes (`panels`) | #F7F8F9 | #18191C |
| Content (`background`) | #FFFFFF | #121315 |

**Contrast checks.** `chrome.text` and `chrome.secondaryText` must hold 4.5 : 1 on `chrome` and on `chrome.control`,
moved by the existing rounds like any other text. Separators are decoration and aren't checked.

**Headers.**

- Tonal and Quiet (so Solarized and Teal too): `HEADERS` takes the panel's colour and a hairline goes below each
  header. The dark navy goes.
- `SideMenuHeader`: an 11 sp label with its count, as in the mockup.
- `GraphHeader`: the column lines go. The dividers you drag show on hover.
- Classic keeps its header colours (D4).

**The branches pane's selected row:** an inset highlight with rounded corners, 4 dp in from each side. The log keeps
its full-width rows.

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

**A Frame seed (D6, recommended):** a sixth seed in the style editor that the six chrome slots follow. Then:

- Leaf is simply Tonal with a green frame;
- any user style can colour its frame with one swatch;
- a frame equal to the look's own keeps the look's values exactly, as the other seeds do.

Without the seed, Leaf sets its chrome slots by hand, and user styles change them one by one in Advanced.

## 6. Smaller changes from the mockup (D7, each on its own)

- ⌘K for Actions (section 3).
- The Commit button says "Commit to main".
- Empty lists say so: "Nothing staged", "Working tree clean".
- The Open button goes. "+" in the title bar opens a new tab with the Welcome page, as today, and the switcher has
  "Open…".

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
1. **Style slots.** The six slots, codec keys, derivation rules, contrast checks, and a "Window" group in the editor's
   Advanced table. Nothing on screen changes yet.
2. **Title bar with the tabs.** macOS, with the fallback for every other case.
3. **Toolbar** (section 3).
4. **Surfaces and headers** (section 4).
5. **The Leaf style** (section 5), and the Frame seed if D6 is yes.
6. **The smaller changes** that D7 keeps.
7. **Docs:** CLAUDE.md, `docs/fork/CHANGELOG.md`, and the README's screenshots if they show the window.
8. **Windows** (D1), once steps 0 to 7 are approved on macOS: the same title bar, with the caption buttons on the right
   (`rightInset`), checked on a Windows machine.

**Tests:**

- The JBR calls sit behind a small interface (`TitleBarHost`), so a fake can test which controls take the pointer.
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
| D1 | Which platforms get tabs in the title bar? | macOS now. Windows once the macOS work is done and approved (step 8). Linux keeps the system bar. |
| D2 | One switcher for the worktrees and recent repositories, replacing Open and the worktree picker? | Yes |
| D3 | ⌘K for Actions? | Yes |
| D4 | Does Classic keep its filled headers? | Yes: it's Leaf's old look |
| D5 | The Leaf style's accent? | (b): green frame and main action, blue selection and links |
| D6 | A Frame seed in the style editor? | Yes |
| D7 | The smaller changes in section 6? | "Commit to main" and the empty texts yes; Open goes once the switcher has "Open…" |

## 9. Risks

- **Windows and Linux** can't be tried here. Hence D1.
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
