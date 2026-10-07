# Fork changelog

This file covers fork-only changes on `main` (called `fork/main` until 2026-10-05). Upstream history is in git.

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
