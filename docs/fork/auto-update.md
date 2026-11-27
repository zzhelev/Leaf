# Updating Leaf from Leaf

| | |
|---|---|
| Question | Can Leaf update itself in a click, without making GitHub the only thing that decides what gets installed? What does the user see on each system? |
| Verified against | `main` at `ec73cdbb`; Leaf 1.10.0 installed in `/Applications`; the files of release `leaf-1.10.0` |
| Date | 2026-11-22, and 2026-11-26 for where the key lives |
| Status | Decided with the user on 2026-11-22: the recommended answer to each of D1 to D10, and D11. On 2026-11-26 the user moved the signing key to GitHub (D12), and kept signing on a computer as the other way (D13, section 9). Step 0 done (section 10), step 1 built, but for the keys and the environment, which the user sets up (section 11), step 2 built on 2026-11-27 (section 12): Leaf checks and downloads, and step 3's macOS part the same day (section 13); nothing calls them yet |

Path prefixes: `A/` = `app/src/main/kotlin/dev/app/leaf/`, `D/` = `domain/src/main/kotlin/dev/app/leaf/domain/`.

## Summary

- **Today:** Leaf finds a new release on its own, every 5 minutes, and its "Update 1.10.0 available" button opens the
  release page. The user downloads the file and installs it by hand. On macOS they also run `sudo xattr` to clear the
  quarantine flag.
- **Proposed:** "Update" in Leaf downloads the file for the system that Leaf runs on, checks it, and puts it in place when
  Leaf restarts or quits. Nothing installs without a click.
- **Trust:** the builds stay on GitHub. Each release gets a manifest of its files' hashes, signed either by a job of the
  release workflow, which waits for the user's approval, with a key kept as a GitHub secret (D12), or on one of the
  user's computers with that computer's key (D13). Leaf installs only what enough of the keys built into it signed: one,
  for now. That stops anyone who can only change a release's files, but not someone who controls the GitHub account or
  the release workflow (section 2).
- **Per system** (section 3): macOS swaps `Leaf.app` itself, with no Finder window and no `xattr`. The Windows installer
  runs silently after one administrator prompt. The portable ZIP and the Linux jar swap themselves. The `.deb` installs
  through the desktop's password prompt (`pkexec`), or Leaf shows the `apt` command to run.
- **Cost per release:** an approval on GitHub, after the builds; or, instead, one command on a computer, which downloads
  the release's files (about 1 GB) and asks for its key's passphrase.
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
Leaf (`REQUIRED_SIGNATURES`, 1 for now). A release is signed by the release workflow, with a key that is a secret on
GitHub (D12, the user's choice on 2026-11-26), or on one of the user's computers, with that computer's key (D13, the same
day). A backup key is kept offline.

| Who | What they can do today | With this plan |
|---|---|---|
| Someone with the GitHub account (a session, a token that can push, phished 2FA) | Replace files, edit `latest.json`; users download the bad files by hand | Push a commit and a `leaf-*` tag, approve the sign job as the account, and every Leaf installs what it built. Replacing files alone isn't enough: their hashes don't match the signed manifest |
| Someone who changes a file after publishing (a token that can only upload) | Users download it by hand | Its hash doesn't match the signed manifest: refused |
| A compromised action or dependency in a build job | Users download what it built | The sign job signs what it built: the same risk as today, but it reaches users faster. The key isn't in those jobs. Pinned actions narrow it |
| A compromised action in the sign job, or a breach or an insider at GitHub | | Can take the key, and sign anything until a release drops it (below). The sign job runs only GitHub's own actions and the shell, to keep this narrow |
| Someone with one of the user's computers and its key's passphrase | | Can sign too. Leaf downloads only from Leaf's GitHub releases, so they also need GitHub |

With keys on the user's computers only (the plan of 2026-11-22), the first row and the fourth were refused too: a key
would have had to be stolen from a computer as well. The user chose GitHub, so that a release needs no step on a
computer, and kept signing on a computer as the other way. With one signature enough, a computer's key adds a way to
sign, not safety: an attacker takes whichever key is easier to get.

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

A signature covers the file's bytes as uploaded, so Leaf checks it before it reads the JSON. The script writes it the
same way every time: its fields in a fixed order, the files sorted by kind, no time stamps.

**Signing on GitHub** (the `sign` job of `.github/workflows/release.yml`, on `leaf-*` tags, after every build job):

1. It runs in the `release-signing` environment: only `leaf-*` tags may use it, and it waits for the user's approval
   (the run's Review deployments). The environment's secret `LEAF_RELEASE_KEY` is the private key.
2. It checks out only the script, the trusted keys file and `AppConstants.kt`, at the tag, and takes the files from the
   run's own artifacts, as the build jobs left them, not from the draft.
3. It checks that `APP_VERSION` is the tag's version, and takes `APP_VERSION_CODE`.
4. `release-manifest/sign-manifest.sh` hashes each file itself (the `.sha256` files aren't read), writes the manifest,
   signs it (`ssh-keygen -Y sign -n leaf-release`), and checks the signature with `ssh-keygen -Y verify` against the key
   that Leaf trusts as `github`. A missing file, or a key that isn't that one, stops it, and it writes nothing.
5. The manifest and `leaf-update.json.github.sig` go as an artifact to the `attach` job, which attaches them to the
   draft (`release-manifest/attach-manifest.sh`) once the `release` job has made it. The draft doesn't wait for signing.

Nothing that could read the key runs in the sign job but GitHub's own actions (`checkout`, `download-artifact`,
`upload-artifact`, pinned to commits) and the script, which uses the shell's tools and ssh-keygen: no Gradle, no
dependency, no third-party action. The key is in one step's environment only, written to a file that only the runner's
user can read, and deleted when the step ends. The job's token can only read; the attach job's can write, and has no
key.

**Signing on a computer** (`release-manifest/sign-release.sh 1.11.0`, after rejecting the sign job, or instead of
waiting for it; needs git, ssh-keygen and `gh` logged in as zzhelev, nothing else):

1. The key is `~/.ssh/leaf-release` (`--key` names another), and its name is the one that `trusted-release-keys` gives
   its public half, so nothing is set up per computer but the key. A key that isn't there stops it, with the line to add.
2. It checks that the tag is the same commit here and on GitHub, and that `APP_VERSION` at the tag is the version.
3. It downloads the release's files from the draft (about 1 GB for 1.10.0) and checks GitHub's attestation of each:
   `gh attestation verify <file> --repo zzhelev/Leaf --signer-workflow zzhelev/Leaf/.github/workflows/release.yml
   --source-ref refs/tags/leaf-1.11.0 --source-digest <the tag's commit> --deny-self-hosted-runners`, so a file that
   someone put on the draft, which Leaf's workflow didn't build from that commit, stops it.
4. `sign-manifest.sh`, as on GitHub, writes the manifest and signs it; ssh-keygen asks for the key's passphrase.
5. `attach-manifest.sh` attaches them. When the draft has a manifest already, signed on GitHub or another computer, it
   must be the same bytes, and only the signature is added: the script writes the same bytes for the same files.

Nothing is attached unless every check passes. The downloads go to a temporary folder, deleted at the end (`--keep`
keeps them).

**The keys (D1, D12, D13):** Ed25519 SSH keys, used only for this. Each has a short name, which names its signature
file.

- `github`: made once, without a passphrase (the workflow can't type one), stored as the `LEAF_RELEASE_KEY` secret, and
  deleted from the computer that made it. GitHub never shows a secret again.
- **A computer's** (`mac`, and one per computer that signs): made on that computer, with a passphrase, as
  `~/.ssh/leaf-release`; its private half never leaves it. A release adds its public half; it signs from the release
  after that one. A computer lost or stolen: the next release drops its key.
- `backup`: made once, with a passphrase, kept offline (a password manager), never on GitHub. It signs only when no
  other key can: `sign-release.sh 1.11.0 --key <backup key>`.
- **In Leaf:** `trusted-release-keys`, a resource of `release-manifest` that the sign job reads too, lists each key's
  name and public half; `REQUIRED_SIGNATURES` is in code. Leaf counts the signatures that check out, each from a
  different trusted key, and installs when there are at least that many.
- **The `github` key leaked** (a compromised action, an incident at GitHub): make a new one, replace the secret, and
  release a Leaf whose `trusted-release-keys` drops the old key and adds the new one. A computer's key signs that
  release too (`sign-release.sh`), as Leafs before it don't trust the new key. A Leaf that hasn't installed that release
  still trusts the old key, so whoever has it can reach those users until they update.
- **The secret lost** (the environment deleted): the same, without the leak.
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

**In the release workflow:** the sign job above; `actions/attest` after each build job collects its files (with
`id-token: write` and `attestations: write`), which nothing in Leaf checks, but anyone can with `gh attestation verify`,
for free on a public repository; and the third-party actions pinned to a commit hash (`softprops/action-gh-release`,
which uploads the files, and `dtolnay/rust-toolchain@stable`, which follows a branch).

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

3. The Release Build workflow builds everything, attests each file and makes the draft release. The sign job waits:
   approve it (the run's Review deployments, `release-signing`), and the draft gets `leaf-update.json` and its
   signature. Or reject it, and sign on a computer: `release-manifest/sign-release.sh 1.11.0`. Review the draft and
   write the notes.
4. Publish, then update `latest.json` as now.

Once, before the first signed release: the keys, the environment and its secret (section 11), the public halves in
`trusted-release-keys`, and a Leaf release that checks manifests. That release is installed by hand; the ones after it
update themselves.

## 6. What gets built

- **The manifest, the trusted keys and the signature check** (the module `release-manifest`, built in step 1): the
  model, which file a kind takes, the addresses, SSHSIG parsing and Ed25519 with the JDK, and the count. The signing
  script reads the same keys file, and its tests check that Leaf reads and accepts what it writes.
- **The download** (`A/updates/`): Leaf's Ktor clients (`HttpClients`, proxies as for the check), progress, Cancel, the
  size limit, SHA-256, a `.part` file renamed once it checks out.
- **Installing** (`A/updates/`): one per kind, the helper scripts (shell on macOS, PowerShell on Windows) written into
  `<app data>/updates/` when needed, and an outcome file that the next start reads for the strip or `ErrorDialog`.
- **Quitting:** `App.kt`'s quit starts the helper before `exitApplication`, after the quit dialog's answers.
- **The UI:** the update dialog's states, the bottom bar's ("Downloading 37%", "Restart to update"), the strip after an
  update, and the setting. Mockups first (step 4).
- **The workflow:** the sign and attach jobs, and `release-manifest/sign-manifest.sh` and `attach-manifest.sh`,
  attestations and pinned actions (step 1).
- **Signing on a computer:** `release-manifest/sign-release.sh` (step 1).

## 7. Steps

0. **Spikes on this Mac**, before anything is built: (a) an app signed ad hoc as the release is replaces itself from a
   script that it started, first in a folder of its own, then in `/Applications`, with App Management as macOS ships it;
   (b) a file that the app writes has no `com.apple.quarantine`; (c) `java.home` gives the bundle; (d) mounting, `ditto`
   and `codesign --verify` on the 1.10.0 DMG. Done on 2026-11-22, with a test app rather than Leaf, so that the user's
   install stayed as it was (section 10).
1. **The release side:** the manifest, the signing scripts and their tests (the real ssh-keygen, Leaf's own reading and
   check of what they write, a missing file, a key that Leaf doesn't trust, a failed attestation and the tags through a
   stand-in for `gh`), the workflow's sign and attach jobs, attestations and pins. It can ship with the next release:
   the manifest is one more file, which older Leafs don't read. Built on 2026-11-22 to sign on the user's computers,
   changed on 2026-11-26 to sign on GitHub or on a computer; the keys are the user's (section 11).
2. **Checking and downloading,** without a UI: fetching the manifest and its signatures (checked as step 1 built it),
   the manifest's checks, the download, the kind. Tests with a local server (as `FakeLfsServer`) and signatures from
   real ssh-keygen; mutations of each check. Built on 2026-11-27 (section 12).
3. **Installing,** one kind at a time: macOS first (the script tested against a bundle in a temporary folder, then end to
   end with a test build, D9), then the jar and the `.deb` in the Linux container used for the window frame, then
   Windows: the portable ZIP and the installer on GitHub's Windows runner (install the previous release silently, update
   it, check the version), and on a real Windows machine for the UAC prompt and SmartScreen. macOS built on 2026-11-27,
   but for the end to end run (section 13).
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
- **The key is in GitHub** (D12): whoever controls the account, the release workflow or the sign job can sign an
  update that every Leaf installs. The environment's approval slows that down, and a computer's key or the backup lets
  a release drop a leaked key, but a Leaf that hasn't updated yet still trusts it. Each computer that can sign is one
  more place to steal a key from (D13).

## 9. Decisions

Each with the recommended answer first. The user took every recommended answer on 2026-11-22, and on 2026-11-26 chose
D12, which replaces D2's program and D11, and D13.

- **D1. The signing keys and format:** dedicated Ed25519 SSH keys and `ssh-keygen -Y sign`, which macOS ships and Leaf
  can check with the JDK alone; a backup key offline. Or minisign, which needs installing on each computer and its own
  parser in Leaf.
- **D2. Where the signing program and the manifest live:** a Kotlin program in the repository, run by a Gradle task,
  sharing the manifest's model with Leaf, in `common` if its dependencies allow, else a small module of its own (a build
  change, so asked here). Or a shell script with `jq`, which would describe the manifest a second time. With D12, the
  signing is a shell script after all (without `jq`), so that no build tool runs where the key is; its tests check it
  against Leaf's own reading.
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
  come later. With D12 the sign job hashes its own run's files; signing on a computer (D13) still downloads them.
- **D11. A second computer** (asked by the user on 2026-11-22): a key per computer, each private key staying on its own,
  and any one signature enough, with the count built into Leaf from its first version (`REQUIRED_SIGNATURES`, 1), so that
  requiring 2 later is a Leaf release. The others: both computers must sign (an attacker needs both, every release needs
  both, and the backup as a third key keeps a lost computer from stopping releases); the same key copied to both (it then
  exists twice, and one computer's copy can't be dropped alone); a hardware key that goes from computer to computer
  (ssh-keygen's `ed25519-sk`, a slightly different signature for Leaf to check, a touch at each release). Superseded
  by D12.
- **D12. Where the key lives** (asked by the user on 2026-11-26, after the recommendation to keep it on their
  computers): in GitHub, as the secret of an environment that only `leaf-*` tags may use and that waits for the user's
  approval, used by a job that runs no build tool and no third-party action; a backup key offline, to sign the release
  that drops a leaked key. The user chose it knowing that whoever controls the GitHub account or the release workflow
  can then sign an update, which a key on their computers prevented at the cost of one command and a 1 GB download per
  release. The count stays (`REQUIRED_SIGNATURES`, 1).
- **D13. Signing on a computer as well** (asked by the user on 2026-11-26, after D12): either way signs a release, as
  Leaf counts a signature from any trusted key. The draft no longer waits for the sign job, and `sign-release.sh` signs
  on a computer, with the same script as the workflow and the checks of the program of 2026-11-22 (the tags,
  `AppConstants`, every file's attestation), in shell rather than Kotlin, so a computer needs no JDK. The user was told
  that with one signature enough this adds a way to sign, not safety; requiring both would, at the cost of a computer's
  step at every release.

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

## 11. Step 1 (2026-11-22, changed on 2026-11-26)

Built on the branch `feature/auto-update`. The first version signed on the user's computers only, with a Kotlin program
(`release-signing`); on 2026-11-26 the key moved to GitHub (D12) and signing on a computer stayed as the other way (D13),
in shell. The Kotlin program was removed before it was committed.

- **`release-manifest`** (`dev.app.leaf.releasemanifest`, kotlinx.serialization only), a module of its own (D2), as
  `common` has no serialization. Leaf will use it in step 2:
  - `ReleaseManifest.kt`: the manifest (`ReleaseManifest`, `ReleaseFile`, `parse`), `ReleaseFileKind` with each kind's
    key and file name as the release workflow names it, the addresses (`releaseFileUrl`, `releasePage`,
    `signatureFileName`), and `isReleaseVersion`.
  - `SshSignatures.kt`: `Ed25519PublicKey` from a `.pub` line, and `verifySshSignature`, the SSHSIG check with the JDK's
    Ed25519: the signer's key, the namespace `leaf-release`, SHA-512, and the signature.
  - `ReleaseSignatures.kt`: `TRUSTED_RELEASE_KEYS`, read from the resource `trusted-release-keys` (no keys until the
    user makes them; a file that can't be read trusts none), `REQUIRED_SIGNATURES` (1), and `checkReleaseSignatures`,
    which counts each different trusted key whose file holds its valid signature, and never takes fewer than one.
- **The scripts,** next to the module's build file, POSIX shell:
  - `sign-manifest.sh` writes and signs the manifest (`sha256sum` or `shasum`, `printf`, `awk`, ssh-keygen). It checks
    the version, the code and the key name before they go into file names and the manifest, and writes nothing unless
    every file is there and the signature checks out against `trusted-release-keys`.
  - `attach-manifest.sh` attaches a signed manifest through `gh`, only the signatures when the release has the same
    manifest, and nothing when it has another.
  - `sign-release.sh` signs on a computer (section 2), with the other two.
  - Each makes its temporary folder from `TMPDIR` itself: macOS's `mktemp -d` ignores it.
- **The workflow:** the `sign` job and the `attach` job (section 2); `release` makes the draft without waiting for them,
  from the build jobs' artifacts only (`leaf-*`). Each build job attests its files before writing the `.sha256` files
  (`actions/attest@v4`, `id-token: write` and `attestations: write` for those jobs only). `dtolnay/rust-toolchain`,
  `softprops/action-gh-release`, and the sign and attach jobs' actions are pinned to commits.
- **Tests** (44): `ReleaseManifestTest`, `SshSignaturesTest`, `ReleaseSignaturesTest` (signatures from the real
  ssh-keygen, with `ssh-keygen -Y verify` as a second opinion: another namespace, another key, SHA-256, an RSA key,
  broken armour, 1 and 2 required, a key under two names, a signature in another key's file; the keys file, and that
  it's the one Leaf reads), `SignManifestScriptTest` (the script as the workflow runs it: Leaf reads its manifest and
  accepts its signature, a missing file, a key that Leaf doesn't trust under that name, no key, versions, codes and
  names that can't go into a file name, Leaf's own keys file by default) and `SignReleaseScriptTest` (signing on a
  computer against a release in a folder, through a stand-in for `gh`, and a repository with the tag: a release without
  a manifest, one that the workflow signed, where both signatures count, one with another manifest, a failed
  attestation, a file the workflow didn't build, a missing file, the tags, `APP_VERSION`, an untrusted key, the
  downloads deleted or kept, a signature file not named after a key). 25 mutations of the checks: 24 were each caught by a
  named test; the 25th took out the parser's skipping of comments, which changed nothing, as `#` starts no key name,
  and that code went.
- **Not tried yet:** the jobs on GitHub, which need the keys, the environment and a tag.

### Setting up the keys and the environment

Once, by the user, as the private keys go through nobody else:

1. **The environment**, before the secret: GitHub → zzhelev/Leaf → Settings → Environments → New environment,
   `release-signing`. Required reviewers: zzhelev. Deployment branches and tags: Selected branches and tags, with a tag
   rule `leaf-*`. A workflow that names an environment that doesn't exist creates it without these rules.
2. **The `github` key**, without a passphrase, in a folder of your own:
   `ssh-keygen -t ed25519 -N '' -C 'leaf-release github' -f <folder>/leaf-release-github`.
3. **The secret:** `gh secret set LEAF_RELEASE_KEY --env release-signing --repo zzhelev/Leaf <
   <folder>/leaf-release-github`, then delete `<folder>/leaf-release-github`, keeping its `.pub`.
4. **Each computer that signs**, with a passphrase: `ssh-keygen -t ed25519 -f ~/.ssh/leaf-release -C
   'leaf-release mac'`.
5. **The backup**, with a passphrase: `ssh-keygen -t ed25519 -C 'leaf-release backup' -f <folder>/leaf-release-backup`,
   then keep the private key in a password manager and delete it from the disk.
6. **Leaf's trusted keys:** add a line per key to
   `release-manifest/src/main/resources/dev/app/leaf/releasemanifest/trusted-release-keys`: `github`, `mac` (a name per
   computer) and `backup`, each followed by its `.pub` line. Public halves are safe to share. From the commit that adds
   them, a tag's sign job signs, and so does `sign-release.sh` on those computers.

## 12. Step 2 (2026-11-27)

Built on `feature/auto-update`, in `A/updates/`, next to the update check. Nothing calls it yet: step 4's UI will.
`app` now depends on `release-manifest`.

- **Which file** (`ReleaseFileKinds.kt`: `RunningLeaf`, from the JVM's properties and the jar that Leaf's classes come
  from, and `releaseFileKindOf`), section 4's rules: a packaged macOS app on Apple Silicon takes the DMG; a packaged
  Windows app on x64 the installer when `unins000.exe` is next to its launcher (`jpackage.app-path`), else the portable
  ZIP; on Linux, jpackage's launcher (the `.deb`) or the packaged jar, for its CPU (`amd64` or `x86_64`, `aarch64`).
  A dev run, an Intel Mac, another CPU, and a Windows launcher without a folder get none.
- **Checking** (`ReleaseUpdates.verify`, from `latest.json`'s release), in order:
  1. a file for this Leaf, or `ByHand(NOT_FOR_THIS_LEAF)`, and nothing is fetched;
  2. `appVersion` three numbers, or refused, and nothing is fetched;
  3. a trusted key at all, or `ByHand(NO_TRUSTED_KEYS)`: until `trusted-release-keys` has keys, every release is
     downloaded by hand, as today;
  4. the manifest: none (404) is `ByHand(NO_MANIFEST)`, over 64 KB refused;
  5. each trusted key's signature file: a missing one counts as none, and so does one over 8 KB; then
     `checkReleaseSignatures`, which must find `REQUIRED_SIGNATURES`;
  6. a later `format` is `ByHand(NEWER_FORMAT)`;
  7. the manifest's version and version code are `latest.json`'s, and the code is newer than this Leaf's;
  8. the kind's file, or `ByHand(NO_FILE_FOR_THIS_LEAF)`: its name must be the kind's for that version, as it goes
     into the address and the updates folder, its SHA-256 64 lower-case hex digits, its size from 1 byte to 1 GiB.

  It ends as `Verified` (the manifest, the kind, the file and its address), `ByHand(reason)` (today's button, which
  opens the release page), `Refused(reason)` (the user will be told why, and offered the release page) or
  `Failed(reason)` (the network, a proxy, the server: try again).
- **Downloading** (`ReleaseUpdates.download`): into `<app data>/updates/`, which keeps only that file, deleting what an
  earlier update left there; a complete file already there isn't downloaded again. It's written as `<name>.part`, at
  most one byte past the manifest's size, so that an answer that doesn't end can't fill the disk, hashed as it's
  written, then: larger, or another SHA-256, is refused and deleted; shorter is a failure (a dropped connection, which Ktor's CIO engine ends without an error), and deleted too;
  otherwise it's renamed into place. Cancelling deletes the `.part`. Progress is the bytes written and the size.
- **Requests** go through Leaf's own clients, with the proxy that git-lfs would use for each address
  (`ProxySettings.forRequestWithoutRepository`, as the update check), TLS certificates checked. GitHub answers a release
  file with a redirect to its file servers, which the same client follows, so a redirect to a host that `NO_PROXY`
  lists still goes through the proxy, as with LFS.
- **Tests** (26): `ReleaseUpdatesTest` (a local server that serves release folders, with releases signed by
  `sign-manifest.sh` and keys that the real ssh-keygen makes: each check, each kind's file, the download and its
  progress, a changed, larger, shorter or missing file, an answer that doesn't end, one downloaded before, what an
  earlier update left, a redirect, Cancel, the proxy asked for each address) and `ReleaseFileKindsTest`. 23 mutations
  of the checks were each caught by a named test, one after a test was added: reading on past the manifest's size,
  which only an answer that doesn't end shows.
- **Not tried yet:** a real release, which needs the keys, and Windows, where `jpackage.app-path` and `unins000.exe`
  are still to be seen.

## 13. Step 3, macOS (2026-11-27)

Built on `feature/auto-update`, in `A/updates/MacUpdates.kt`, as section 3 has it, with what step 0 proved. Nothing
starts an install yet: step 4's Restart now and When Leaf quits will.

- **Where Leaf is** (`macBundleOf`, `macInstallPlace`): the bundle from jpackage's layout only, the launcher
  `<bundle>/Contents/MacOS/Leaf` (`jpackage.app-path`), else `java.home`, `<bundle>/Contents/runtime/Contents/Home`. A dev
  run on IntelliJ's runtime (`IntelliJ IDEA.app/Contents/jbr/Contents/Home`) has none. A translocated copy and a folder
  that can't be written are installed by hand, from the DMG.
- **Preparing** (`MacUpdates.prepare`): mounts the DMG out of sight, at a folder of its own in the updates folder
  (`diskutil image attach --mountOptions nobrowse --readOnly --mountPoint`, or `hdiutil attach -nobrowse -readonly` where
  macOS has no `diskutil image`), copies its Leaf.app with `ditto` next to the installed one as `.Leaf-<version>.app`,
  and checks the copy: `codesign --verify --deep --strict`, the bundle ID `io.github.zzhelev.leaf` and
  `CFBundleShortVersionString`. It unmounts (`hdiutil detach`, forced when it must) whatever happens, deletes the DMG
  once the copy checks out, and otherwise keeps no copy.
- **Installing** (`MacUpdates.startInstall`): writes `install.json` (both versions and the paths) and
  `install-macos.sh` into the updates folder, and starts the script with Leaf's process; the caller then quits. The
  script, each step in `install.log`:
  1. waits for Leaf to quit, 2 minutes at most; after that it says that Leaf didn't quit, and moves nothing;
  2. renames the bundle to `.Leaf-<from>-old.app` and the copy into its place; a rename that fails puts the old one
     back and opens it;
  3. `lsregister -f`, then `open`;
  4. waits for the new Leaf to delete the old copy: 30 seconds, then for as long as anything runs from the new bundle
     (`pgrep`), 2 minutes more, so that a slow start isn't undone. When nothing runs from it, the new app goes to
     `.Leaf-<to>-failed.app`, the old one comes back, and it's opened.

  A line `failed: <reason>` in the log is what the next start tells the user.
- **The next start** (`MacUpdates.finishAtStart`, early in `App.start`, on macOS): the new version, from the same
  bundle, deletes the old copy, which tells the script that it runs, and a failed copy; the old version, back, deletes
  the prepared and the failed copies and takes the reason from the log. Both keep what came of it in
  `install-outcome.json`, which `takeOutcome` gives once, for step 4's strip or `ErrorDialog`. Another version, or
  another copy of the same one, leaves the install as it is.
- **Tests** (11): `MacUpdatesTest`. Preparing runs against DMGs that `hdiutil create` makes, holding an app signed ad
  hoc as jpackage signs Leaf: the copy and its checks, another bundle ID, another version, a resource changed after
  signing, a DMG without Leaf.app, a file that isn't a DMG, nothing left mounted. The script runs against app folders in
  a temporary folder, with stand-ins for `open` and `lsregister`, so that LaunchServices never sees a test's app, and
  the test playing the new Leaf: updated, a new Leaf that doesn't start, one that starts slowly, a bundle that can't be
  moved, a Leaf that doesn't quit; and the next start's records. 19 mutations of the checks and the script were each
  caught by a named test.
- **Gatekeeper and fake apps:** nothing may run from inside a folder named `*.app` that isn't a signed app. Gatekeeper
  kills such a program and tells the user that "“Leaf” is damaged" and should go to the Trash, as it did three times on
  2026-11-27 while the slow start's stand-in ran a launcher inside its fake bundle, and a manual check ran a copy of
  `sleep` inside one. The stand-in now starts a shell whose command line only names the launcher's path, which is what
  the script's `pgrep` looks for; a run of all the tests then had no Gatekeeper rejection.
- **Not yet:** the end to end run with a test build that trusts a test key and reads a local `latest.json` (D9), which
  wants step 4's way to start an update; then the Linux jar and `.deb`, and Windows.
