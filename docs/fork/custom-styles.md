# Custom styles: a library of styles, an editor, and Solarized

| | |
|---|---|
| Question | How do custom colours work once Theme is only Light, Dark and System? |
| Verified against | `main` at `15e09df6`; built on `feature/custom-styles`, rebased onto `bd772f2c` |
| Date | 2026-10-22 |
| Status | Done 2026-10-22: every step of section 6. Section 7 has the decisions, and "As built" notes say where the work differs from the plan |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`,
`DA/` = `data/src/main/kotlin/dev/app/leaf/data/`.

**Mockups:** the Claude Design project "Leaf colour palette" (`5fe7f140-cd30-47ab-a95a-1994cadae8d9`):
`Leaf Colours.dc.html` (turn 1, the built-in styles' values, and row 1g, muted lanes), `Custom Style.dc.html` (turn 2,
options 2a and 2b, and the palette of 12 hues by 6 shades) and `Style Library.dc.html` (turn 3, the gallery 3b and the
editor 3c, with Solarized as the example).

## Summary (decided with the user on 2026-10-22)

- **Theme** keeps Light, Dark and System. Custom goes, and so does the JSON custom theme, with no migration and no
  import of old files.
- **Style** becomes a library of styles. Each style has a light side and a dark side, so System keeps working.
- **A style sets every colour Leaf draws with:** backgrounds, surfaces, text, file icons, diff and syntax colours, the
  buttons and notices that Style sets today, and the log graph's six lanes.
- **Built-in styles:** Tonal (the default), Classic, Quiet and Solarized, one style with a light and a dark side. They
  can only be duplicated. Tonal, Classic and Quiet look exactly as today, apart from Tonal's and Quiet's lanes (step 7).
  Teal joined them on 2026-10-23 (`docs/fork/CHANGELOG.md`).
- **User styles** are files: create, duplicate, edit, rename, delete, export, import.
- **Settings** shows the styles as a gallery of cards (3b), light on the left and dark on the right, then "+ New style"
  and "Import style…".
- **The editor (3c):** the button look (Classic, Tonal or Quiet), Background as a light and dark pair, Accent, Danger,
  Warning and Success, and under a folded Advanced section every colour for light and dark. Live previews of both
  sides, a palette and a hex field, contrast checks. Leaf works out every colour from the seed colours and adjusts what
  wouldn't be readable, saying so. Colours set by hand are kept and marked, and may break contrast with a warning.

## 1. What Leaf has today

| Piece | Where | What happens to it |
|---|---|---|
| Theme: Light, Dark, Custom, System | `D/models/ui/Theme.kt`, `DA/repositories/configuration/mappers/ThemeMapper.kt` | Custom removed; a stored `custom` reads as no choice (Dark) |
| The JSON custom theme | `A/App.kt` decodes `custom_theme` into `ColorsScheme` (`@Serializable`, `ColorAsStringSerializer`); Settings' text field; `AppConfig.CustomTheme`; the README's FAQ answer; `TaskType.SaveCustomTheme` (never used) | Removed; `custom_theme` joins `REMOVED_SETTINGS` |
| The scheme's values | `A/theme/Color.kt` (`lightTheme`, `darkTheme`, upstream's) | Unchanged: they are the templates (section 2.2) |
| The style: Tonal, Classic, Quiet | `A/theme/StyleColors.kt` (`styled()`, `styleColorsFor`); `D/models/ui/AccentStyle.kt`; key `accent_style` | `AccentStyle` stays, as a style's look; the key holds a style's id |
| `AppTheme` | `A/theme/Theme.kt`: picks a scheme and keeps it in a global flow that the `Colors.x` getters read | Takes a style; the scheme becomes a `CompositionLocal`, so the editor's previews can nest a theme without recolouring the app |
| The graph's lanes | `A/ui/log/Log.kt`, `private val colors` (six Material 400s, the grey written `0xFFe78909c`) | Read from the theme |

About 20 colours are written in the UI code itself (the light themes' amber text `#8A5C00`, the resolver's purple for
"theirs", the compare badges, the avatar palette). A style doesn't set them in this work (section 8).

## 2. The model

### 2.1 A style definition (domain, `D/styles/`)

- `StyleDefinition(id, name, look: AccentStyle, light: StyleSide, dark: StyleSide)`.
- `StyleSide(seeds: StyleSeeds, setByHand: Map<StyleSlot, Argb>)`, and `StyleSeeds(background, accent, danger,
  warning, success)`. Seeds are stored per side; the editor shows Accent, Danger, Warning and Success as one colour
  each and sets both sides (a swatch is split in two when the sides differ, as in a duplicate of Tonal or Classic).
- `StyleSlot`: one entry per colour Leaf draws with, each with a stable key for files (`background`, `panels`,
  `text`, `mainAction.fill`, `warningStrip.button.text`, `lane3`, …): the scheme's 26 (`primary` and `onPrimary` are
  Main action's fill and text, as they are in every built-in), the roles' 26 (fill, text and edge of the four buttons,
  the two strips with their buttons, the warning icon), and six lanes.
- Colours are ARGB ints (`Argb`), so the model and its file format don't depend on Compose.

### 2.2 Resolving one side (app, `A/theme/styles/`)

`resolve(definition, isLight)` gives a `ResolvedStyle`: the `ColorsScheme`, the `StyleColors`, the six lanes, the
adjustments Leaf made, and the contrast checks. It lives in the app module, next to the templates, which are Compose
colours.

1. **Template.** The look's built-in palette for that side: `lightTheme.styled(look)` or `darkTheme.styled(look)`,
   `styleColorsFor(look, …)` and the look's lanes, with the seeds it is built around (Classic: accent #0070D8 light and
   #0169CB dark, danger #C93838, warning #FFB638, success #32A852; Tonal and Quiet: accent #376AAA and #3464A0, danger
   #C93838, warning #C68128 and #E6B463, success #32A852; background #FFFFFF and #0F0F0F).
2. **Each slot follows one seed or none,** per look: surfaces, scrollbars and text follow Background; Main action,
   Action, links, the modified-file icon, the selected row, the keyword and lane 1 follow Accent; Destructive, errors,
   removed lines and lane 2 follow Danger; the warning strip and icon, the conflicted-file icon, annotations and lane 4
   follow Warning; the success strip, added lines, comments and lane 5 follow Success. Classic's Unstage follows Danger
   (it is red there), Tonal's and Quiet's follow Background. The dialog overlay, the highlight colour (`secondary`), the
   white on filled buttons and lanes 3 and 6 are fixed.
3. **A slot whose seed is the template's keeps the template's value, exactly.** Otherwise, in OKLCH:
   - a slot that follows Accent, Danger, Warning or Success keeps the template's lightness, turns by the seed's change
     of hue, and scales its chroma by the seed's chroma over the template seed's, then gives up chroma until it fits
     sRGB. Keeping the lightness keeps what makes the look readable. Solarized's #268BD2 in the Tonal look gives a
     main action of about #1A6EAD, near the design's #196EA9;
   - a slot that follows Background moves with it in OKLab: the same change of lightness and the same tint for
     surfaces; text keeps its lightness and takes half the tint.

   Transparent colours (the diff's tints, the dark theme's neutral buttons) keep their alpha.
4. **Contrast.** The checks of `StyleColorsContrastTest` (button labels 4.5 : 1 on every surface the button is on,
   strip text and strip buttons 4.5, a line's icon-only buttons 3, the warning icon 3 on the background, links 4.5,
   toasts' text 4.5), plus text and secondary text 4.5 : 1 on the background, the panels, the headers, the selected row
   and the diff's added and removed lines. Every built-in holds them all (a test says so), except Classic's warning
   icon, which the existing test exempts too. A derived colour that fails moves in lightness, keeping its hue, just far
   enough: text away from its fill, a filled button's fill under its white text, the warning icon and links against
   the background. The editor says so, as in the design: "White text on #268BD2 is 3.7:1, so filled buttons use
   #1A6EAD (5.4:1)."
5. **Colours set by hand** are used as given and never moved. When one fails a check, the other colour of the pair
   moves if it is derived; otherwise the editor shows a warning chip. Syntax colours aren't checked (Classic's light
   keyword is 3.9 : 1 on white today).

   **As built:** text moves before the surface under it, and the surface moves when the text can't, as when the text
   was set by hand. A fill or a surface moves only away from what is drawn on it (`movedForContrast`'s `towards`), so a
   light panel under grey text gets lighter or stays, never dark; when its side can't hold the check, the check falls
   short. Before anything moves, a mid-tone background is lightened (light side) or darkened (dark side) until black or
   white holds 4.5 : 1, with 0.3 to spare, on every surface that follows it (`fittedBackground`): no text colour reads
   on both a mid-tone background and the darker or lighter surfaces next to it. The editor says nothing of what moved
   or falls short (section 5.2).
6. **Built-ins.** Classic, Tonal and Quiet are definitions whose seeds are their template's, with nothing set by hand,
   so resolving them gives today's colours by construction. A test compares every slot with `lightTheme.styled(…)`,
   `darkTheme.styled(…)` and `styleColorsFor`, and renders of the main panes are compared with `main`, pixel for
   pixel. Duplicating one copies its look and seeds, so changing Accent in a copy of Tonal moves everything that
   follows Accent.
7. **Solarized** is a built-in in the Tonal look with Solarized's seeds (background #FDF6E3 and #002B36, accent
   #268BD2, danger #DC322F, warning #B58900, success #859900). Ethan Schoonover's published values are set by hand
   where they say what a colour is: panels (base2 and base02), text (base01 and base1), keyword (blue), annotation
   (yellow), comment, the lanes (blue, red, cyan, orange, green, magenta). The buttons, strips, headers and diff tints
   are derived, with the adjustments that gives: the design's #196EA9 for filled buttons comes out about #1A6EAD.
   Some published pairs fall short of the checks (base01 text on base2 panels is 4.4 : 1, base00 on base3 4.1 : 1);
   there the derived colour of the pair moves, or the colour stays derived. Step 8 shows renders of both sides.

   **As built** (approved with renders of the tab, the gallery and the editor): text base01 and base1, comments base00
   and base0 (the "Style Library" board's; Solarized's own comment tones, base1 and base01, read at about 2.5 : 1), the
   dark panels base02, keyword blue, annotation yellow, the lanes. The light panels are worked out, #F6EFDC, as base01
   holds 4.39 : 1 on base2. Secondary text is the board's, as Leaf's worked-out colour was brighter than Solarized's
   text on the dark side: #516770, and base0 lifted to #8A9C9E, which holds 4.54 : 1 on base02 (base0 holds 4.11). The
   filled buttons come out #006EAE and #0068A5. Every check holds on both sides.

### 2.3 The file format

One JSON file per user style, `<id>.json`. The id is the file's name: it isn't written in the file, so a file can be
renamed or shared without two ids disagreeing.

```json
{
  "leafStyle": 1,
  "name": "Teal",
  "look": "tonal",
  "light": {
    "seeds": { "background": "#FFFFFF", "accent": "#007475", "danger": "#B4405A", "warning": "#E0A03A", "success": "#3F8F52" },
    "setByHand": { "text": "#1D2B2B", "mainAction.fill": "#00736F" }
  },
  "dark": {
    "seeds": { "background": "#0F0F0F", "accent": "#007475", "danger": "#B4405A", "warning": "#E0A03A", "success": "#3F8F52" },
    "setByHand": {}
  }
}
```

- Colours are `#RRGGBB` or `#RRGGBBAA` (CSS order).
- `leafStyle` is the format's version. A file without it isn't a style, and Import says so.
- Reading is lenient where it can be: an unknown look reads as Tonal, a missing seed as the look's own, an unknown key
  or a colour that doesn't parse is left out. Each is logged. A file that isn't JSON, or has no `leafStyle`, can't be
  read.

## 3. Storage (domain interface, data implementation)

- **The folder:** `styles` in Leaf's app data folder (`AppFilesManager.getAppFolder()`), named by
  `AppStorage.STYLES_FOLDER_NAME`: `~/Library/Application Support/leaf/styles/` on macOS, `leaf-dev/styles/` for dev
  runs.
- **`StyleLibraryRepository`** (`D/styles/`), implemented in `DA/styles/`: list, save (written to a temporary file,
  then moved into place), delete, import, export. A singleton keeps the list, read at start and again when Settings
  opens, so a file edited outside Leaf shows the next time.
- **The selected style** stays in `accent_style`, now an id: `tonal`, `classic`, `quiet`, `solarized`, or a file's.
  Today's stored values already are those ids. `AppConfig.AccentStyle` becomes `AppConfig.Style(id)`.
- **Falling back:** a missing or unknown id, a deleted file or a file that can't be read reads as Tonal, never throws,
  and is logged once per id. Deleting the selected style selects Tonal.
- **Ids:** made from the name (lower case, `a-z`, `0-9` and `-`, at most 40 characters), with `-2`, `-3`… when taken by
  a file or a built-in. Renaming a style keeps its id.
- **Import** copies a file into the folder, with an id from the file's name, made new when taken (`tonal.json` imports
  as `tonal-2`). **Export** writes the definition wherever the user picks, as `<name>.json`. Export needs a save dialog,
  which Leaf doesn't have: a fork-only `SaveFilePicker` next to `OpenFilePickerUseCase` (AWT's save dialog on macOS and
  Windows, zenity on Linux).
- **Files that can't be read** don't show in the gallery; a line under it names them, with the reason in its tooltip.

## 4. Removing the JSON custom theme

`Theme.Custom`, the "Custom theme" field, `AppConfig.CustomTheme`, the repository's and service's `customTheme`, the
decoding in `App.kt`, `ColorsScheme`'s `@Serializable` and `ColorAsStringSerializer` (nothing else uses them), the
README's FAQ answer and `TaskType.SaveCustomTheme`. `custom_theme` joins `REMOVED_SETTINGS`, so
`RemovedSettingsMigration` deletes it. A stored `theme` of `custom` reads as no choice (Dark), as an unknown value
already does. The previews in `Notification.kt` and `WelcomePage.kt` lose their `customTheme = null`.

## 5. The UI

### 5.1 Settings → Appearance

- **Theme:** Light, Dark, System, with today's subtitle.
- **Style:** a caption, then the gallery: cards of about 176 × 96 dp, four a row in the dialog's 768 dp, scrolling past
  two rows. Each card is a mini preview drawn from the style's resolved colours, light half and dark half (the design's
  banner, two button pills, two text lines and the main action bar), its name, "built in" for the four, and a ⋯ menu:
  Edit (Duplicate for a built-in), Export…, Delete (user styles only). The selected card has a ring. A click selects.
- **"+ New style"** opens the editor on a copy of the selected style ("starts from Solarized"); **"Import style…"** opens
  a file picker. An import that fails says why in a strip under the gallery, until dismissed.
- **Delete** asks first (`Screen.ConfirmAction`, `ConfirmableAction.DeleteStyle`).

### 5.2 The editor (`Screen.StyleEditor`, a dialog over Settings)

- **Header:** the name, in a field; "A style of yours" or, for a new one, "A new style, from Tonal"; a ⋯ menu
  (Duplicate, Export…, Delete style); close.
- **Left column, scrolling:** Buttons look (a three-way switch), Colours (Background as a pair, Accent, Danger,
  Warning, Success, each with its use and its hex), the info lines for what Leaf adjusted, then Advanced (folded): a
  table of every colour, light and dark, grouped as Surfaces, Text, Diff, Graph lanes, and Buttons and notices, with a
  dot on colours set by hand and a chip where a check fails ("Text 4.3:1").
- **The palette** is a popover by the swatch clicked: 12 hues by 6 shades (the design's OKLCH grid), a hex field, the
  check the colour takes part in ("White text on Commit 5.5:1"), and, for a colour set by hand, "Use the worked-out
  colour", which takes the dot away.
- **As built, then changed (2026-10-23):** the info lines, the chips and the palette's check were built, then removed
  with the user, who found them noise in use: a copy of Solarized listed 13 colours moved, each with two hex codes and
  a ratio. The editor explains nothing; the previews show the result, and the Advanced table the colours used.
- **Right column:** the previews, light above dark: the operation banner with its buttons, a diff header with Stage
  file, a hunk header with Discard and Stage hunk, three diff lines with syntax colours, the Unstaged header with
  Unstage all, two file rows, one selected, Commit, the restored strip, and a strip of the six lanes. Each preview sits
  in its own `AppTheme` with the draft style, so it uses Leaf's own buttons and colours.
- **Footer:** Revert changes (back to the file as saved, or the starting style for a new one), Cancel, Save. Closing
  with changes asks first. Save writes the file; a new style is selected when saved.
- **Built-ins** never open the editor: Duplicate opens it on a copy named "Tonal copy".
- The editor's state is a plain reducer (`StyleEditorState`), tested without Compose.

## 6. Steps (each one committed after approval)

1. **Model and file format** (domain and data): `StyleDefinition`, `StyleSlot`, `StyleSeeds`, the codec, with
   round-trip, lenient and broken-file tests. Nothing uses them yet.
2. **Derivation:** OKLCH and contrast, `resolve()`, the built-in definitions of Classic, Tonal and Quiet; `AppTheme`
   takes a style; the scheme as a `CompositionLocal`; the lanes read from the theme with today's six for every
   built-in. Tests: identity for every slot of every built-in, every check on every built-in, 2,000 random seed sets per
   look and side holding every check, OKLCH round trips. Renders compared with `main`.
3. **Library storage:** the folder, the repository, ids, import and export, the selected id in `accent_style`, the
   fallback to Tonal, `App.kt` reading the selected style. Settings' Style drop-down lists every style meanwhile.
4. **Removing Custom and the JSON theme** (section 4).
5. **The Settings gallery** (5.1), with the save dialog.
6. **The editor** (5.2).
7. **Lanes:** per style; for Tonal and Quiet, today's six or the muted set of row 1g (#5B97D3, #CD7670, #7D97A4,
   #C87D52, #60A56B, #C4759C), decided with renders of the log in both, asked at that step. The editor's Graph lanes rows.
   Decided: the muted set (about 3 : 1 each on the light background, where today's green holds 2.4).
8. **Solarized** (2.2, point 7).
9. **Notes:** CLAUDE.md, `docs/fork/CHANGELOG.md`, this file's status.

Each step leaves Leaf working and `./gradlew test` green. Each step's new logic gets mutations, each caught by a named
test.

## 7. Open questions, decided

1. Where the code goes: the model, the file format and the storage in `domain` and `data`, the derivation and the
   resolving in `app`, next to the templates. Recommended, as the templates are Compose colours in `app`. **Decided as
   recommended.**
2. Solarized: derive its buttons, strips and tints (recommended; they come out near the design, #1A6EAD for #196EA9),
   or set the design's numbers by hand, which marks about 30 colours with a dot in a copy of it. **Decided as
   recommended**; they came out #006EAE and #0068A5.
3. Lanes for Tonal and Quiet: today's six or row 1g's muted set. Asked at step 7, with renders. **Decided: the muted
   set.**

## 8. Limits

- The ~20 colours written in the UI code (section 1) stay as they are, whatever the style.
- ~~Only three syntax colours (keyword, annotation, comment), as today.~~ Nine since 2026-10-25, in a group of their
  own (`docs/fork/syntax-highlighting.md`, step 4).
- A file edited outside Leaf shows when Settings opens next, not at once.
- Avatars keep their own palette (decided 2026-10-20).

## 9. Checks before calling it done

- `./gradlew test` green.
- Renders of the main panes in Classic, Tonal and Quiet, light and dark, identical to `main`'s (lanes included, unless
  step 7 changes Tonal's and Quiet's).
- Offscreen renders of the gallery, the editor, and the main panes in Solarized light and dark, shown for approval.
- A `:app:run` demo, with the dev `user_prefs.json` and `styles` folder backed up first and put back after.

## Appendix: the slots

| Group | Slots (file keys) |
|---|---|
| Surfaces | `background`, `panels` (surface), `boxes` (secondarySurface: hunk headers, boxes in dialogs), `headers` (tertiarySurface: the headers of the lists, the log and the diff), `selectedRow`, `dialogOverlay`, `scrollbar`, `scrollbarHover` |
| Text | `text`, `secondaryText`, `links` (primaryVariant), `highlight` and `onHighlight` (secondary), `error` and `onError` |
| Files | `addedFile`, `modifiedFile`, `deletedFile`, `conflictedFile` |
| Diff | `addedLine`, `addedWords`, `removedLine`, `removedWords`, `keyword`, `annotation`, `comment` |
| Graph lanes | `lane1` … `lane6` |
| Buttons and notices | `mainAction.fill`, `mainAction.text`; `action`, `neutral`, `destructive` each `.fill`, `.text`, `.edge`; `warningStrip` and `successStrip` each `.fill`, `.text`, `.icon`, `.edge`, `.button.fill`, `.button.text`, `.button.edge`; `warningIcon` |
