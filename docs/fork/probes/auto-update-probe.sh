#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
# SPDX-License-Identifier: AGPL-3.0-only
#
# Fork-only, macOS only. Reproduces step 0 of docs/fork/auto-update.md: can an app packaged and signed as Leaf is
# (jpackage, ad hoc, hardened runtime, Leaf's three entitlements) replace itself, and do the files it writes get a
# quarantine flag? It builds a small app twice (versions 1 and 2) under $TMPDIR. Version 1 writes a file, downloads
# one, copies version 2 next to its own bundle, starts a script that swaps the two once it has exited, and exits;
# version 2 notes where it started. It never touches Leaf.app.
#
# Usage: auto-update-probe.sh [--applications] [path/to/Leaf-x.y.z-macos-arm64.dmg]
#   --applications  also run it from /Applications, as LeafUpdateProbe.app, which is removed afterwards
#   a DMG           also copy Leaf.app out of it, as the updater would, and check the copy's signature
#
# Requirements: a JDK 25 with jpackage (JPACKAGE_JDK, by default the JBR SDK in ~/Library/Java/JavaVirtualMachines).
# Set KEEP=1 to keep the work folder.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TEMPLATE="$ROOT/docs/fork/probes/AutoUpdateProbe.java"
JDK="${JPACKAGE_JDK:-$HOME/Library/Java/JavaVirtualMachines/jbrsdk-25.0.4.1/Contents/Home}"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister

IN_APPLICATIONS=0
DMG=""
for arg in "$@"; do
  case "$arg" in
    --applications) IN_APPLICATIONS=1 ;;
    *) DMG="$arg" ;;
  esac
done

[ "$(uname)" = Darwin ] || { echo "macOS only"; exit 1; }
[ -x "$JDK/bin/jpackage" ] || { echo "No jpackage in $JDK; set JPACKAGE_JDK"; exit 1; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/leaf-auto-update-probe.XXXXXX")"
RESULTS="$WORK/results"

cleanup() {
  for p in /Applications/LeafUpdateProbe.app /Applications/.LeafUpdateProbe-2.app /Applications/.LeafUpdateProbe-1-old.app \
           "$WORK/apps/LeafUpdateProbe.app" "$WORK/apps/.LeafUpdateProbe-2.app" "$WORK/apps/.LeafUpdateProbe-1-old.app"; do
    if [ -e "$p" ]; then
      "$LSREGISTER" -u "$p" 2>/dev/null || true
      /bin/rm -rf "$p"
    fi
  done
  if [ -d "$WORK/mnt" ] && mount | grep -q "$WORK/mnt"; then hdiutil detach "$WORK/mnt" -quiet || true; fi
  if [ "${KEEP:-0}" = 1 ]; then echo "Kept $WORK"; else /bin/rm -rf "$WORK"; fi
}
trap cleanup EXIT

echo "== Building the probe app ($(sw_vers -productName) $(sw_vers -productVersion))"
for v in 1 2; do
  mkdir -p "$WORK/src$v" "$WORK/classes$v" "$WORK/input$v"
  sed -e "s/%VERSION%/$v/" -e "s#%RESULTS%#$RESULTS#" "$TEMPLATE" > "$WORK/src$v/AutoUpdateProbe.java"
  "$JDK/bin/javac" -d "$WORK/classes$v" "$WORK/src$v/AutoUpdateProbe.java"
  "$JDK/bin/jar" --create --file "$WORK/input$v/probe.jar" -C "$WORK/classes$v" .
  "$JDK/bin/jpackage" --type app-image --name LeafUpdateProbe --input "$WORK/input$v" --main-jar probe.jar \
    --main-class AutoUpdateProbe --app-version "$v.0" --mac-package-identifier io.github.zzhelev.leaf-update-probe \
    --add-modules java.base,java.net.http --dest "$WORK/out$v"
  cat > "$WORK/entitlements.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key><true/>
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
    <key>com.apple.security.cs.disable-library-validation</key><true/>
</dict>
</plist>
EOF
  codesign --force --deep --options runtime --entitlements "$WORK/entitlements.plist" -s - \
    "$WORK/out$v/LeafUpdateProbe.app" 2>/dev/null
done
codesign -dv "$WORK/out1/LeafUpdateProbe.app" 2>&1 | grep -E "^(Identifier|CodeDirectory|TeamIdentifier)"

# Runs version 1 from $1 (a folder) and reports what happened
run_from() {
  local folder="$1"
  local bundle="$folder/LeafUpdateProbe.app"
  /bin/rm -rf "$RESULTS"
  mkdir -p "$RESULTS"
  echo "$WORK/out2/LeafUpdateProbe.app" > "$RESULTS/staged-v2-path.txt"
  ditto "$WORK/out1/LeafUpdateProbe.app" "$bundle"
  local start
  start="$(date '+%Y-%m-%d %H:%M:%S')"
  open "$bundle"
  for _ in $(seq 1 60); do
    if grep -q '^done' "$RESULTS/swap.log" 2>/dev/null; then break; fi
    sleep 1
  done
  # Version 2 may start some seconds after the script's open returns
  for _ in $(seq 1 20); do
    if [ -f "$RESULTS/started-v2.txt" ]; then break; fi
    sleep 1
  done
  echo "-- what version 1 did"; cat "$RESULTS/app.log" 2>/dev/null || echo "(version 1 wrote nothing)"
  echo "-- what the swap did"; cat "$RESULTS/swap.log" 2>/dev/null || echo "(no swap log)"
  echo "-- where version 1 started"; cat "$RESULTS/started-v1.txt" 2>/dev/null || echo "(version 1 didn't start)"
  echo "-- where version 2 started"; cat "$RESULTS/started-v2.txt" 2>/dev/null || echo "(version 2 didn't start)"
  echo "-- the bundle's version now: $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$bundle/Contents/Info.plist")"
  echo "-- extended attributes of the files it wrote"
  for f in plain-file.txt downloaded.json; do echo "$f: $(xattr "$RESULTS/$f" | tr '\n' ' ')"; done
  echo "-- the TCC log for the probe"
  log show --start "$start" --style compact \
    --predicate 'subsystem == "com.apple.TCC" AND eventMessage CONTAINS[c] "LeafUpdateProbe"' 2>/dev/null | tail -10 || true
  "$LSREGISTER" -u "$bundle" 2>/dev/null || true
  /bin/rm -rf "$bundle"
}

echo; echo "== Run 1: from a folder of its own"
mkdir -p "$WORK/apps"
run_from "$WORK/apps"

if [ "$IN_APPLICATIONS" = 1 ]; then
  echo; echo "== Run 2: from /Applications"
  if [ -e /Applications/LeafUpdateProbe.app ]; then
    echo "/Applications/LeafUpdateProbe.app exists already, skipping"
  else
    run_from /Applications
  fi
fi

if [ -n "$DMG" ]; then
  echo; echo "== Copying Leaf.app out of $DMG"
  mkdir -p "$WORK/mnt" "$WORK/copy"
  t0=$(date +%s)
  if diskutil image >/dev/null 2>&1; then
    diskutil image attach --mountOptions nobrowse --readOnly --mountPoint "$WORK/mnt" "$DMG" >/dev/null
  else
    hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$WORK/mnt" "$DMG" >/dev/null
  fi
  ditto "$WORK/mnt/Leaf.app" "$WORK/copy/.Leaf-update.app"
  hdiutil detach "$WORK/mnt" -quiet
  echo "mount, copy and unmount: $(( $(date +%s) - t0 )) s, $(du -sh "$WORK/copy/.Leaf-update.app" | cut -f1)"
  codesign --verify --deep --strict --verbose=2 "$WORK/copy/.Leaf-update.app" 2>&1 | sed "s#$WORK/copy/##"
  /usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" -c "Print :CFBundleShortVersionString" \
    "$WORK/copy/.Leaf-update.app/Contents/Info.plist"
  echo "extended attributes of the copy: $(xattr "$WORK/copy/.Leaf-update.app" | tr '\n' ' ')"
fi
