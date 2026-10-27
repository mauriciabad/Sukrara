#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Put Sukrara V1 on the owner's phone or Mac without losing anyone's merged work (docs/DEVELOPMENT.md "Install").
#   tools/install.sh phone [--dry-run]   apps/android release APK → verify-apk.sh → adb install -r (keeps data)
#   tools/install.sh mac   [--dry-run]   apps/macos bundle.sh → verify-app.sh → ~/Applications/Sukrara.app
# V1 (app.mauri.sukrara) installs NEXT TO the showcase PoC (app.mauri.sukrara.poc). On the Mac the showcase is quit,
# moved to ~/Applications/Sukrara Showcase.app (never deleted) and its login item flagged, so only V1 alerts.
# Refuses unless: the tree is clean, HEAD contains origin/main and every branch in tools/check/integration.txt,
# HEAD contains the commit installed last time (else features vanish from the device), and tools/check/run.sh passes.
# Records what it installed in <git common dir>/sukrara-installed-v1-<target> (shared by all worktrees).
# --dry-run runs every check but only prints the build / install commands.
set -eu
TARGET=${1:-}; DRY=0
[ "${2:-}" = "--dry-run" ] && DRY=1
case "$TARGET" in phone|mac) ;; *) echo "usage: tools/install.sh phone|mac [--dry-run]" >&2; exit 2 ;; esac
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT"
. tools/release/lib.sh
SERIAL=${ANDROID_SERIAL:-62240DLCH0005W}
RECORD="$(cd "$(git rev-parse --git-common-dir)" && pwd)/sukrara-installed-v1-$TARGET"
die() { echo "REFUSED: $*" >&2; exit 1; }
run() { if [ $DRY = 1 ]; then echo "would run: $*"; else echo "+ $*"; "$@"; fi; }

# 0. Is there an app to build yet?
if [ "$TARGET" = phone ] && [ ! -f apps/android/settings.gradle.kts ]; then
  echo "no apps build yet: apps/android has no Gradle build (settings.gradle.kts). Nothing to install."; exit 0
fi
if [ "$TARGET" = mac ] && { [ ! -f apps/macos/Package.swift ] || [ ! -x apps/macos/Bundle/bundle.sh ]; }; then
  echo "no apps build yet: apps/macos has no Package.swift + Bundle/bundle.sh. Nothing to install."; exit 0
fi

# 1. Everything merged is in this build.
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "uncommitted changes: commit them (or use a clean worktree)."
git fetch -q origin main 2>/dev/null || echo "note: could not fetch origin/main, using the local copy"
git merge-base --is-ancestor origin/main HEAD || die "HEAD lacks origin/main: merge it first."
grep -v '^\s*#' tools/check/integration.txt | while read -r b; do
  [ -n "$b" ] || continue
  if git rev-parse -q --verify "$b" >/dev/null; then
    git merge-base --is-ancestor "$b" HEAD || { echo "REFUSED: HEAD lacks integration branch $b: merge it first." >&2; exit 1; }
  else echo "note: integration branch $b not found locally, skipped"; fi
done
if [ -f "$RECORD" ]; then
  LAST=$(cut -d' ' -f1 "$RECORD")
  if git cat-file -e "$LAST^{commit}" 2>/dev/null; then
    git merge-base --is-ancestor "$LAST" HEAD || die "the $TARGET runs $(cat "$RECORD"), which HEAD does not contain.
  Installing would remove that work from the device. Merge $LAST first (ask the session that built it)."
  else echo "note: last installed commit $LAST is unknown here (not fetched?)"; fi
fi
tools/check/run.sh --range origin/main..HEAD || die "tools/check/run.sh failed (see above)."

set -- $(version_info)
VERSION=$1
HEAD=$(git rev-parse --short HEAD)
if [ "$TARGET" = phone ]; then
  # 2a. Signed release APK, verified, in-place update (keeps identity, followers, settings).
  APK=apps/android/app/build/outputs/apk/release/app-release.apk
  MAPPING=apps/android/app/build/outputs/mapping/release/mapping.txt
  run sh -c "cd apps/android && ./gradlew -q :app:assembleRelease"
  run tools/release/verify-apk.sh "$APK" "$MAPPING"
  run adb -s "$SERIAL" install -r "$APK"
  if [ $DRY = 1 ]; then GOT="$VERSION (dry run)"; else
    GOT=$(adb -s "$SERIAL" shell dumpsys package app.mauri.sukrara | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
    [ "$GOT" = "$VERSION" ] || die "phone reports versionName '$GOT' for app.mauri.sukrara, expected '$VERSION'."
  fi
else
  # 2b. Team-signed bundle with the widget; the showcase steps aside first.
  DEST="$HOME/Applications/Sukrara.app"
  SHOWCASE="$HOME/Applications/Sukrara Showcase.app"
  BUILT=apps/macos/build/Sukrara.app
  run sh -c "cd apps/macos && Bundle/bundle.sh"
  run tools/release/verify-app.sh "$BUILT"
  id_of() { /usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$1/Contents/Info.plist" 2>/dev/null || true; }
  # The showcase must not alert next to V1 (double alarms): quit it, keep it as the fallback under another name.
  if pgrep -f 'Sukrara.app/Contents/MacOS/' >/dev/null && [ "$(id_of "$DEST")" = app.mauri.sukrara.poc ] || \
     osascript -e 'application id "app.mauri.sukrara.poc" is running' 2>/dev/null | grep -q true; then
    run osascript -e 'tell application id "app.mauri.sukrara.poc" to quit'
  fi
  if [ -d "$DEST" ] && [ "$(id_of "$DEST")" = app.mauri.sukrara.poc ]; then
    [ -e "$SHOWCASE" ] && die "$SHOWCASE already exists and $DEST is also the showcase: keep one of them (never deleted here)."
    run mv "$DEST" "$SHOWCASE"
  elif [ -d "$DEST" ] && [ "$(id_of "$DEST")" != app.mauri.sukrara ]; then
    die "$DEST is neither V1 nor the showcase ($(id_of "$DEST")): move it away by hand."
  fi
  # Its login item (SMAppService of the showcase) can only be turned off by the showcase or in System Settings.
  if sfltool dumpbtm 2>/dev/null | grep -q 'app.mauri.sukrara.poc'; then
    echo "ACTION: the showcase is still a login item. Turn off 'Sukrara' (Sukrara Showcase) in System Settings ›"
    echo "        General › Login Items & Extensions, or it opens at login and alerts next to V1."
    run open "x-apple.systempreferences:com.apple.LoginItems-Settings.extension"
  fi
  run pkill -x Sukrara || true
  run rm -rf "$DEST"
  run ditto "$BUILT" "$DEST"
  run /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$DEST"
  run swift tools/release/url-handler.swift "$DEST" || echo "WARN: sukrara: links may still open the showcase (see above)."
  run open "$DEST"
  run pkill -f SukraraWidget.appex || true
  # Unregister every other registered V1 copy (worktree / test builds), or chronod may run their stale extension.
  pluginkit -mAv 2>/dev/null | grep 'app.mauri.sukrara.widget' | awk -F'\t' '{print $NF}' | grep -v "^$DEST/" |
    while IFS= read -r p; do run pluginkit -r "$p"; done
  GOT=$VERSION
fi

# 3. Record and show what the device now runs.
LINE="$(git rev-parse HEAD) $(date '+%Y-%m-%dT%H:%M') $GOT $(git rev-parse --abbrev-ref HEAD)"
if [ $DRY = 1 ]; then echo "would record in $RECORD: $LINE"; else echo "$LINE" > "$RECORD"; fi
echo "$TARGET: Sukrara $GOT from $HEAD ($(git log -1 --format=%s HEAD))"
