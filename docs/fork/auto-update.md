# Updating Leaf from Leaf

| | |
|---|---|
| Question | Can Leaf update itself in a click, without making GitHub the only thing that decides what gets installed? What does the user see on each system? |
| Verified against | `main` at `ec73cdbb`; Leaf 1.10.0 installed in `/Applications`; the files of release `leaf-1.10.0` |
| Date | 2026-11-22 |
| Status | Decided with the user on 2026-11-22: the recommended answer to each of D1 to D10, and D11, a key per computer with a signature count built into Leaf (section 9). Step 0 done the same day (section 10); nothing built in Leaf yet |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`.

## Summary

- **Today:** Leaf finds a new release on its own, every 5 minutes, and its "Update 1.10.0 available" button opens the
  release page. The user downloads the file and installs it by hand. On macOS they also run `sudo xattr` to clear the
  quarantine flag.
- **Proposed:** "Update" in Leaf downloads the file for the system that Leaf runs on, checks it, and puts it in place when
  Leaf restarts or quits. Nothing installs without a click.
- **Trust:** the builds stay on GitHub. Each release gets a manifest of its files' hashes, signed on one of the user's
  computers with that computer's own key, which GitHub never has, after a script checks GitHub's own build attestations.
  Leaf installs only what enough of the keys built into it signed: one, for now. Someone who takes over the GitHub
  account can't make Leaf install anything (section 2).
- **Per system** (section 3): macOS swaps `Leaf.app` itself, with no Finder window and no `xattr`. The Windows installer
  runs silently after one administrator prompt. The portable ZIP and the Linux jar swap themselves. The `.deb` installs
  through the desktop's password prompt (`pkexec`), or Leaf shows the `apt` command to run.
- **Cost per release:** one more command and its key's passphrase, on any computer set up to sign, between reviewing the
  draft and publishing it. The
  script downloads the release's files, about 1 GB for 1.10.0.
- **Step 0** (section 10): macOS 27 lets an app signed as Leaf is replace itself, in `/Applications` too, without a
  prompt, and the files that it downloads get no quarantine flag.
- **Open:** Windows, which hasn't been run on a real machine yet.

## 1. What Leaf has today

- **The check** (`A/updates/UpdatesRepository.kt`): reads `latest.json` from `main` every 5 minutes, and compares
  `appCode` with `AppConstants.APP_VERSION_CODE`. It goes through the proxy rules that git-lfs would use. A newer release
  shows as the bottom bar's "Update 1.10.0 available" (`A/ui/components/BottomInfoBar.kt`), and in
  `CheckForUpdatesDialog`, whose Download opens the release page (`latest.json`'s `downloadUrl`).
- **The release** (`.github/workflows/release.yml`): GitHub's own runners (`macos-latest`, `windows-latest`,
  `ubuntu-latest`, `ubuntu-22.04`, `ubuntu-22.04-arm`) build the files and attach them to a draft release, each with a
  `.sha256` file. The user reviews it and publishes it. The files of 1.10.0:

  | File | Size |
  |---|---|
  | `Leaf-1.10.0-macos-arm64.dmg` | 182 MB |
  | `Leaf-1.10.0-windows-x64-setup.exe` | 138 MB |
  | `Leaf-1.10.0-windows-x64-portable.zip` | 169 MB |
  | `Leaf-1.10.0-linux-x86_64.jar` | 108 MB |
  | `Leaf-1.10.0-linux-arm_aarch64.jar` | 120 MB |
  | `Leaf-1.10.0-linux-amd64.deb` | 147 MB |
  | `Leaf-1.10.0-linux-arm64.deb` | 146 MB |

- **The installed macOS app:** signed ad hoc (`Signature=adhoc`, no Team ID). Its `Info.plist` has no
  `LSFileQuarantineEnabled`, so the files that Leaf itself downloads get no quarantine flag (step 0).
  `/Applications/Leaf.app` belongs to the user, every folder in it can be written, 48 files (the JDK's licences) are
  read-only, and macOS gave it a `com.apple.provenance` attribute.
- **The Windows installer** (`leaf.iss`, Inno Setup): installs into Program Files, so it asks for administrator rights.
  Its `[Run]` entry starts Leaf after an interactive install only (`postinstall skipifsilent`).
- **Quitting clears `<app data>/tmp`** (`TempFilesManager.clearAll` in `App.kt`), so an update can't wait there.
- **Dev runs** are told from packaged ones already (`AppStorage`: the `jpackage.app-version` property, or the
  `Leaf-Packaged` attribute of the Linux jar).

## 2. Trust: what Leaf will install

**The rule:** Leaf installs a file only when a manifest that lists its SHA-256 is signed by enough of the keys built into
Leaf (`REQUIRED_SIGNATURES`, 1 for now). Each private key is on one of the user's computers, never on GitHub.

| Who | What they can do today | With this plan |
|---|---|---|
| Someone with the GitHub account or a token | Replace files, edit `latest.json`; users download the bad files by hand | The same, but Leaf refuses them: no signature. Users who download by hand are no worse off than today |
| Someone who changes a file after publishing | Users download it by hand | Its hash doesn't match the signed manifest: refused |
| A compromised action or dependency in the build | Users download what it built | The attestation is genuine and the user signs it. The same risk as today, but it reaches users faster. Pinned actions narrow it |
| Someone with one of the user's computers and its key's passphrase | | Can sign, but Leaf downloads only from Leaf's GitHub releases, so they also need GitHub. With 2 signatures required, they would need two computers |

**The manifest** (`leaf-update.json`, with a signature per key that signed it, `leaf-update.json.<key>.sig`, all attached
to the release):

```json
{
  "format": 1,
  "appVersion": "1.11.0",
  "appCode": 38,
  "releasePage": "https://github.com/zzhelev/Leaf/releases/tag/leaf-1.11.0",
  "files": {
    "macos-arm64": { "name": "Leaf-1.11.0-macos-arm64.dmg", "size": 182289753, "sha256": "…" },
    "windows-x64-setup": { "name": "Leaf-1.11.0-windows-x64-setup.exe", "size": 138204884, "sha256": "…" },
    "windows-x64-portable": { "…": "…" },
    "linux-x86_64-jar": { "…": "…" },
    "linux-aarch64-jar": { "…": "…" },
    "linux-amd64-deb": { "…": "…" },
    "linux-arm64-deb": { "…": "…" }
  }
}
```

A signature covers the file's bytes as uploaded, so Leaf checks it before it reads the JSON. The program writes the
manifest the same way every time (its fields in a fixed order, the files sorted by kind, no time stamps), so two
computers that check the same release write the same bytes.

**Signing** (any computer set up for it, after reviewing the draft, section 5): a program in the repository, run by
Gradle, which:

1. checks that the local tag `leaf-1.11.0` and `origin`'s are the same commit, and that the draft has every expected
   file;
2. downloads each file from the draft with `gh`, and hashes it itself, ignoring CI's `.sha256` files;
3. runs `gh attestation verify <file> --repo zzhelev/Leaf --signer-workflow
   zzhelev/Leaf/.github/workflows/release.yml --source-digest <tag's commit> --deny-self-hosted-runners` for each: the
   file was built by Leaf's release workflow, on GitHub's runners, from the commit that the user tagged;
4. writes the manifest. When the draft has one already, signed from another computer, the program's own must be the same
   bytes, or it stops;
5. signs it with this computer's key (`ssh-keygen -Y sign -n leaf-release -f ~/.ssh/leaf-release`, which asks for the
   passphrase), and uploads the manifest, when it wasn't there, and `leaf-update.json.<key>.sig` to the draft.

**The keys (D1, D11):** one Ed25519 SSH key per computer that signs, used only for this, with a passphrase, whose private
half never leaves its computer. Not the GitHub key: whoever steals one would have both. One more key, kept offline (a
password manager), is the backup. Each has a short name (`mac`, `second`, `backup`), which names its signature file.

- **In Leaf** (`UpdateKeys.kt`): the list of trusted public keys with their names, and `REQUIRED_SIGNATURES`. Leaf counts
  the signatures that check out, each from a different trusted key, and installs when there are at least that many. It
  is 1 for now: a release can be signed from whichever computer the user is at. Raising it to 2 later, so that an attacker
  would need two computers, is a Leaf release, signed as the rules before it ask.
- **A computer added:** it makes its key, and a Leaf release adds its public half. From the release after that one, it
  can sign on its own. It needs the repository, JDK 25, `gh` logged in as zzhelev, and ssh-keygen from OpenSSH 8.1 or
  newer (current macOS and Linux, and recent Windows).
- **A computer lost or stolen:** another computer signs the next release, which drops its key. A Leaf that hasn't
  updated yet still trusts it, so someone with that key and its passphrase who also got into GitHub could still reach
  those users, as with any key.
- The namespace `leaf-release` keeps a signature made for anything else, such as a commit, from passing as a release's.

**What Leaf checks**, in order:

1. `latest.json`'s `appVersion` is three numbers. The manifest's address is made from it:
   `https://github.com/zzhelev/Leaf/releases/download/leaf-<version>/leaf-update.json`, so `latest.json` keeps its
   format, and an older Leaf reads it as before.
2. The signatures, for each trusted key's name in turn (`leaf-update.json.<key>.sig`; a missing one counts as none),
   with the JDK's own Ed25519 (`Signature.getInstance("Ed25519")`): no new library, and no ssh-keygen on the user's
   machine. Leaf parses the SSHSIG format (OpenSSH's `PROTOCOL.sshsig`): the key in it must be the one the file is named
   after, the namespace `leaf-release`, the hash SHA-512. At least `REQUIRED_SIGNATURES` must check out.
3. `appCode` is greater than Leaf's own and equal to `latest.json`'s, so nobody can hand out an older release.
4. The file for Leaf's kind (section 4) is downloaded from the same release's folder (GitHub redirects to its file
   servers over HTTPS), no further than its size, and its SHA-256 must match.
5. On macOS, the copied `Leaf.app` must pass `codesign --verify --deep --strict` and have Leaf's bundle ID and the
   manifest's version.

Any failure stops the update, says why in `ErrorDialog`, and offers the release page. A release without a manifest (one
from before this, or one the user forgot to sign) gets today's button, which opens the release page.

**In the release workflow:** `actions/attest` after each job collects its files (with `id-token: write` and
`attestations: write`), and the third-party actions pinned to a commit hash (`softprops/action-gh-release`, which
uploads the files, and `dtolnay/rust-toolchain@stable`, which follows a branch). Attestations are free for public
repositories, and every runner is GitHub's own, which `--deny-self-hosted-runners` requires.

## 3. What the user sees

### The same on every system

1. The bottom bar says "Update 1.11.0 available", as now. "Check for updates" (the Actions list, the Welcome page, the
   Help menu on macOS) finds it too.
2. A click opens the update dialog: "Leaf 1.11.0 is available. You have 1.10.0.", a link to what's new (the release
   page), and Update or Later.
3. **Update** downloads the file, with its size and progress ("64 of 182 MB") and Cancel. The dialog can be closed: the
   download goes on, and the bottom bar shows its progress. The file goes to `<app data>/updates/`, which quitting
   doesn't clear.
4. Leaf checks it (section 2), which takes a second or two. A failure shows `ErrorDialog` with the reason, and the
   release page.
5. **Ready:** "Restart now" or "When Leaf quits". Restart now quits as any quit does: a conflict resolver with changes
   that aren't saved asks first. When Leaf quits installs at the next quit, without starting Leaf again.
6. **After the restart:** a strip under the toolbar says "Leaf was updated to 1.11.0", with what's new and ×. A failed
   install opens the old Leaf with `ErrorDialog`, which says what failed; the old Leaf was put back.

Leaf installs nothing without a click on Restart now or When Leaf quits. A setting can make it download in the
background once it finds an update, so that the bottom bar says "Restart to update 1.11.0" (D3).

### macOS (Apple Silicon)

| | |
|---|---|
| File | `Leaf-1.11.0-macos-arm64.dmg`, 182 MB |
| Prepared while Leaf runs | Mounted out of sight (`diskutil image attach --mountOptions nobrowse --readOnly`; `hdiutil attach -nobrowse -readonly` where macOS has no `diskutil image`, as macOS 27 calls it deprecated), `Leaf.app` copied with `ditto` next to the installed one as `.Leaf-1.11.0.app` (hidden, and on the same volume, so the swap is a rename), unmounted, checked (section 2, step 5). The DMG is deleted. About 5 seconds for 1.10.0, whose app takes 294 MB |
| Installed | Yes, by Leaf. At restart, Leaf starts a small script and quits. The script waits for Leaf to end, renames `Leaf.app` to `.Leaf-1.10.0-old.app` and the new copy to `Leaf.app`, registers it with LaunchServices (as `installMacApp` does) and opens it. The new Leaf deletes the old copy once it has started; when it hasn't started within 30 seconds, the script puts the old copy back and opens that. A rename that fails puts the old copy back too. Deleting the old copy while macOS still looked at it made a restart late once in step 0 (section 10) |
| Prompts | None. Step 0's test app, signed as Leaf is, replaced itself in `/Applications` with no prompt and nothing in the TCC log (section 10). A later macOS, or a Developer ID signature, could bring macOS's App Management check in |
| The user sees | Leaf's window closes and opens again a few seconds later, on 1.11.0, with the same tabs. No Finder window, no dragging to Applications, no password, no `xattr` |
| When it can't | `Leaf.app`'s folder can't be written (installed by another user), macOS refuses (App Management), or Leaf runs from a translocated copy (a quarantined app opened from Downloads): "Open the update" shows the DMG in Finder. The user drags Leaf to Applications as before, without the `xattr` step, as Leaf downloaded the DMG |

Where Leaf is installed doesn't matter: it updates the bundle that runs it, found from `jpackage.app-path`, which
jpackage's launcher sets to `<bundle>/Contents/MacOS/Leaf`, or else from `java.home`
(`<bundle>/Contents/runtime/Contents/Home`).

### Windows: the installer

| | |
|---|---|
| File | `Leaf-1.11.0-windows-x64-setup.exe`, 138 MB |
| Installed | Yes, by the installer, without its pages. At restart, Leaf starts a helper (PowerShell) and quits. The helper waits for Leaf to end, then runs the installer with `/SILENT /SUPPRESSMSGBOXES /NORESTART`, and a parameter that has it start Leaf when it's done, as the user rather than as administrator (`runasoriginaluser`, a change to `leaf.iss`, D7) |
| Prompts | One: Windows' administrator prompt (UAC), as the installer writes to Program Files. It says the publisher is unknown, as the installer isn't signed. No SmartScreen page, which shows only for files that a browser downloaded (to check on Windows) |
| The user sees | Leaf closes, the UAC prompt, Inno Setup's small progress window for a few seconds, then Leaf opens on 1.11.0 |
| When it can't | No to the UAC prompt: the helper starts the old Leaf, which says the update wasn't installed, with Try again |

"When Leaf quits" shows the UAC prompt after Leaf's window has closed. The dialog says so.

### Windows: the portable ZIP

| | |
|---|---|
| File | `Leaf-1.11.0-windows-x64-portable.zip`, 169 MB |
| Prepared while Leaf runs | Unpacked by Leaf next to its folder, as `<folder>.update` |
| Installed | Yes, by Leaf. At restart, the helper waits for Leaf to end, renames Leaf's folder to `<folder>.old` and the new one to Leaf's folder's name, starts `Leaf.exe`, then deletes the old folder |
| Prompts | None |
| The user sees | Leaf closes and opens again a few seconds later, on 1.11.0 |
| When it can't | The folder can't be written (unpacked into Program Files): the release page, as today. A file in the folder that something holds open (another program, a virus scan): the helper tries again for 30 seconds, then puts the old folder back and starts it, and Leaf says why |

Leaf tells the two apart by Inno Setup's uninstaller, `unins000.exe`, which only an installed copy has next to `Leaf.exe`
(`jpackage.app-path`).

### Linux: the `.deb`

| | |
|---|---|
| File | `Leaf-1.11.0-linux-amd64.deb` (or `arm64`), 147 MB |
| Installed | By apt, as root, as the package belongs to the system (`/opt/leaf`) and Leaf runs as the user. Install runs `pkexec apt-get install -y <file>` while Leaf is open, then restarts Leaf |
| Prompts | The desktop's password prompt ("Authentication is required…") |
| The user sees | The password prompt, a few seconds of installing, then Leaf restarts on 1.11.0 |
| When it can't | No `pkexec` (WSL, a minimal system): the dialog shows `sudo apt install <path of the downloaded file>` with Copy |

There is no "When Leaf quits" here: a password prompt after the window has closed would make no sense. An apt repository,
so that `apt upgrade` updates Leaf with the system, is the usual way on Debian and Ubuntu, and would be work of its own
(D5).

### Linux: the jar

| | |
|---|---|
| File | `Leaf-1.11.0-linux-x86_64.jar` (or `arm_aarch64`), 108 to 120 MB |
| Prepared while Leaf runs | Saved next to the running jar, as a hidden file |
| Installed | Yes, by Leaf. At restart, Leaf renames the new file over the running jar (Linux lets a running program's file be replaced), starts it with the same `java` and options, and quits |
| Prompts | None |
| The user sees | Leaf closes and opens again on 1.11.0 |
| When it can't | The jar's folder can't be written: the release page, as today |

The file keeps its path, so the launchers and scripts that start it still work, though its name keeps the old version
(D6).

### Not offered

Dev runs (`./gradlew :app:run`), and a system that the manifest has no file for (such as an Intel Mac): the button opens
the release page, as today.

## 4. How Leaf knows what it is

| Kind | How Leaf knows | Manifest entry |
|---|---|---|
| macOS app | macOS, packaged, `os.arch` `aarch64` | `macos-arm64` |
| Windows, installed | Windows, packaged, `unins000.exe` next to `Leaf.exe` | `windows-x64-setup` |
| Windows, portable | Windows, packaged, no `unins000.exe` | `windows-x64-portable` |
| Linux `.deb` | Linux, `jpackage.app-version` set (only the `.deb`'s launcher sets it on Linux) | `linux-amd64-deb`, `linux-arm64-deb` |
| Linux jar | the `Leaf-Packaged` jar, found from the code's location | `linux-x86_64-jar`, `linux-aarch64-jar` |
| Dev run | anything else | none |

## 5. The release steps after this

`CLAUDE.md`'s Releases, steps 3 and 4, become:

3. The Release Build workflow builds everything, attests each file and creates a draft release. Review the draft and
   write the notes.
4. **New:** on any computer set up for signing, `./gradlew signRelease -Pversion=1.11.0`, which asks for that computer's
   key's passphrase, checks and signs the draft's files (section 2), and attaches `leaf-update.json` and the computer's
   signature. It stops, attaching nothing, when a file is missing, an attestation doesn't check out, the tags differ, or
   the draft's manifest isn't the one it wrote. With 2 signatures required, the second computer runs it too.
5. Publish, then update `latest.json` as now.

The download is the slow part: about 1 GB for 1.10.0's seven files, a few minutes on a fast connection. Checking the
attestations by their hashes, without downloading, would need more than `gh` offers today (D10).

Once, before the first signed release: make a key on each computer that will sign and the offline backup, put their
public halves into Leaf, and release a Leaf that checks manifests. That release is installed by hand; the ones after it
update themselves.

## 6. What gets built

- **The manifest and its rules** (`D/updates/` or `common`, D2): the model, which file a kind takes, the address checks.
  Shared with the signing program, so the two can't drift apart.
- **The signature** (`A/updates/ReleaseSignature.kt`): SSHSIG parsing and Ed25519 with the JDK.
- **The download** (`A/updates/`): Leaf's Ktor clients (`HttpClients`, proxies as for the check), progress, Cancel, the
  size limit, SHA-256, a `.part` file renamed once it checks out.
- **Installing** (`A/updates/`): one per kind, the helper scripts (shell on macOS, PowerShell on Windows) written into
  `<app data>/updates/` when needed, and an outcome file that the next start reads for the strip or `ErrorDialog`.
- **Quitting:** `App.kt`'s quit starts the helper before `exitApplication`, after the quit dialog's answers.
- **The UI:** the update dialog's states, the bottom bar's ("Downloading 37%", "Restart to update"), the strip after an
  update, and the setting. Mockups first (step 4).
- **The signing program** and the Gradle task, `signRelease`.
- **The workflow:** attestations and pinned actions.

## 7. Steps

0. **Spikes on this Mac**, before anything is built: (a) an app signed ad hoc as the release is replaces itself from a
   script that it started, first in a folder of its own, then in `/Applications`, with App Management as macOS ships it;
   (b) a file that the app writes has no `com.apple.quarantine`; (c) `java.home` gives the bundle; (d) mounting, `ditto`
   and `codesign --verify` on the 1.10.0 DMG. Done on 2026-11-22, with a test app rather than Leaf, so that the user's
   install stayed as it was (section 10).
1. **The release side:** the keys, the manifest, the signing program and its tests (real ssh-keygen, a tampered manifest,
   a missing file, a failed attestation through a stand-in for `gh`, a second computer's run that writes the same
   manifest, and one that finds a different one), and the workflow's attestations and pins. It can
   ship with the next release: the manifest is one more file, which older Leafs don't read.
2. **Checking and downloading,** without a UI: the signatures and their count, the manifest's checks, the download, the
   kind. Tests with a local server (as `FakeLfsServer`) and signatures from real ssh-keygen, with 1 and 2 required, a
   signature file named after another key, and the same key twice; mutations of each check.
3. **Installing,** one kind at a time: macOS first (the script tested against a bundle in a temporary folder, then end to
   end with a test build, D9), then the jar and the `.deb` in the Linux container used for the window frame, then
   Windows: the portable ZIP and the installer on GitHub's Windows runner (install the previous release silently, update
   it, check the version), and on a real Windows machine for the UAC prompt and SmartScreen.
4. **The UI:** mockups of the dialog, the bottom bar and the strip; the user picks; then build it, with the quit and the
   setting.
5. **Docs:** `CLAUDE.md` (Releases, and a section of its own), the README's Download/Install, `docs/fork/CHANGELOG.md`.

Each step is done as `PLAN.md` defines it: built, tested, run on macOS, logged in the changelog.

## 8. Risks

- **App Management** didn't stop an ad hoc signed app from replacing itself on macOS 27.2 (section 10), but a later macOS,
  or a Developer ID signature, may bring it in. Then macOS keeps "Open the update", which still saves the `xattr` step,
  unless the user allows Leaf under Privacy & Security. `docs/fork/probes/auto-update-probe.sh` checks it again on a
  new macOS.
- **Windows hasn't run Leaf** on a real machine yet: the UAC prompt's wording, SmartScreen, Defender scanning a new
  `.exe`, files held open.
- **A bad release reaches everyone faster.** The way back is a newer release; older ones stay on the releases page.
- **Size:** 108 to 182 MB per update, with no smaller patches.
- **A swap that stops halfway:** the scripts rename, which is all or nothing on one volume, and delete the old copy only
  once the new one is in place.
- **The keys:** one lost, another computer or the backup signs; one stolen with its passphrase, the attacker still needs
  GitHub. Each computer that can sign is one more place to steal a key from, while one signature is enough.

## 9. Decisions

Each with the recommended answer first. The user took every recommended answer on 2026-11-22.

- **D1. The signing keys and format:** dedicated Ed25519 SSH keys and `ssh-keygen -Y sign`, which macOS ships and Leaf
  can check with the JDK alone; a backup key offline. Or minisign, which needs installing on each computer and its own
  parser in Leaf.
- **D2. Where the signing program and the manifest live:** a Kotlin program in the repository, run by a Gradle task,
  sharing the manifest's model with Leaf, in `common` if its dependencies allow, else a small module of its own (a build
  change, so asked here). Or a shell script with `jq`, which would describe the manifest a second time.
- **D3. When Leaf downloads:** after the user clicks Update. A setting, off by default, downloads in the background once
  an update is found. The files are 108 to 182 MB, and connections can be metered.
- **D4. When Leaf installs:** Restart now, or When Leaf quits. Never on its own.
- **D5. The `.deb`:** `pkexec apt-get install`, with the `sudo apt` command to copy where there's no `pkexec`. An apt
  repository later, as work of its own: it needs hosting (GitHub Pages) and a GPG key, also kept off GitHub.
- **D6. The jar:** keep its path, with the old version in its name. Or save the new file under its own name, which breaks
  whatever starts the old one.
- **D7. `leaf.iss`:** start Leaf after a silent install, as the user (a change to packaging, so asked here).
- **D8. The workflow:** attestations, and third-party actions pinned to commit hashes (CI config, so asked here).
- **D9. Testing end to end:** a test build that trusts a test key and reads a local `latest.json`, through Gradle
  properties that the release workflow refuses, and that Leaf logs at start.
- **D10. Checking attestations:** by downloading the files (about 1 GB a release). Checking them by hash alone could
  come later.
- **D11. A second computer** (asked by the user on 2026-11-22): a key per computer, each private key staying on its own,
  and any one signature enough, with the count built into Leaf from its first version (`REQUIRED_SIGNATURES`, 1), so that
  requiring 2 later is a Leaf release. The others: both computers must sign (an attacker needs both, every release needs
  both, and the backup as a third key keeps a lost computer from stopping releases); the same key copied to both (it then
  exists twice, and one computer's copy can't be dropped alone); a hardware key that goes from computer to computer
  (ssh-keygen's `ed25519-sk`, a slightly different signature for Leaf to check, a touch at each release).

## 10. Step 0 (2026-11-22)

On macOS 27.2.1, with `docs/fork/probes/auto-update-probe.sh`: a small Java app, packaged and signed as Leaf is
(jpackage from the JBR SDK 25.0.4.1, ad hoc, hardened runtime, Leaf's three entitlements, no Team ID), built as version
1 and version 2. Leaf's own install wasn't touched.

- **(a) It replaces itself, in `/Applications` too.** Version 1 copied version 2 next to its own bundle with `ditto`,
  started a shell script and exited. The script waited for it to end, renamed the bundle aside and version 2 into its
  place, ran `lsregister -f` and `open`, and removed the old copy. Every step succeeded each time, and macOS showed no
  prompt: from a folder of its own, and from `/Applications`.
  - Version 2 started from the same path in 5 of 6 runs in `/Applications`, and in every run from the folder. The sixth
    run looked for it only 2 seconds after the script ended, and its TCC log had `open` refused Apple Events: the old
    copy, still responsible for the script, has the hardened runtime without `com.apple.security.automation.apple-events`,
    and TCC couldn't read its binary ("spooky magic") while the script deleted it. In no other run did TCC check Apple
    Events. So the new Leaf deletes the old copy once it runs, and the script puts the old copy back when the new one
    hasn't started within 30 seconds (section 3).
  - Not tried: a bundle that Finder copied from a DMG, as users install it (the test bundles were copied by `ditto`), one
    that another user installed, and a build signed with a Developer ID, where App Management compares Team IDs.
- **(b) No quarantine.** A file that the app wrote and one that it downloaded over HTTPS (`java.net.http`) have
  `com.apple.provenance` only, no `com.apple.quarantine`, as its `Info.plist` has no `LSFileQuarantineEnabled`. The
  flag depends on the app, not on the HTTP library, so a DMG that Leaf downloads with Ktor gets none either, and the
  `xattr` step isn't needed.
- **(c) The bundle.** `java.home` is `<bundle>/Contents/runtime/Contents/Home`, and jpackage's launcher sets
  `jpackage.app-path` to `<bundle>/Contents/MacOS/<name>`, though `Leaf.cfg` doesn't list it. Leaf takes
  `jpackage.app-path`, and `java.home` without it. jpackage's Windows launcher should set it to `Leaf.exe`, which section
  4 needs: to check on Windows.
- **(d) The release DMG.** 1.10.0's DMG mounted out of sight, its `Leaf.app` copied with `ditto` and unmounted in about
  5 seconds; the copy takes 294 MB, passes `codesign --verify --deep --strict` ("valid on disk", "satisfies its
  Designated Requirement"), and has the bundle ID `io.github.zzhelev.leaf` and the version 1.10.0. macOS 27 warns that
  `hdiutil attach -nobrowse -readonly -mountpoint` is deprecated, for `diskutil image attach --mountOptions nobrowse
  --readOnly --mountPoint`, which works the same; Leaf uses `diskutil image` where macOS has it.
- **Room on the disk:** the prepared copy (294 MB) sits next to the installed app until the swap, and the old copy until
  the script removes it.

So macOS needs no fallback in the usual case. "Open the update" stays for a folder that can't be written and a
translocated copy.
