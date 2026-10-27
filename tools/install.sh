#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Put Sukrara on the user's phone or Mac without losing anyone's merged work (NEXT.md section 5, "One build line").
#   tools/install.sh phone [--dry-run]   release APK → adb install -r (keeps data; never uninstalls / clears)
#   tools/install.sh mac   [--dry-run]   bundle.sh → ~/Applications/Sukrara.app, restart widget ext, drop stale plugins
# Refuses unless: the tree is clean, HEAD contains origin/main and every branch in tools/check/integration.txt,
# HEAD contains the commit installed last time (else features vanish from the device), and tools/check/run.sh passes.
# Records what it installed in <git common dir>/sukrara-installed-<target> (shared by all worktrees) and prints it.
# --dry-run runs every check but only prints the build / install commands.
set -eu
TARGET=${1:-}; DRY=0
[ "${2:-}" = "--dry-run" ] && DRY=1
case "$TARGET" in phone|mac) ;; *) echo "usage: tools/install.sh phone|mac [--dry-run]" >&2; exit 2 ;; esac
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT"
SERIAL=${ANDROID_SERIAL:-62240DLCH0005W}
RECORD="$(cd "$(git rev-parse --git-common-dir)" && pwd)/sukrara-installed-$TARGET"
die() { echo "REFUSED: $*" >&2; exit 1; }
run() { if [ $DRY = 1 ]; then echo "would run: $*"; else echo "+ $*"; "$@"; fi; }

# 1. Everything merged is in this build.
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "uncommitted changes: commit them (or use a clean worktree)."
git fetch -q origin main 2>/dev/null || echo "note: could not fetch origin/main, using the local copy"
git merge-base --is-ancestor origin/main HEAD || die "HEAD lacks origin/main: merge or rebase on it first."
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

HEAD=$(git rev-parse --short HEAD)
if [ "$TARGET" = phone ]; then
  # 2a. Release APK, in-place update (keeps identity, followers, settings).
  VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' poc/spikes/showcase-android/app/build.gradle.kts)
  : "${JAVA_HOME:=$(brew --prefix openjdk@17 2>/dev/null)/libexec/openjdk.jdk/Contents/Home}"; export JAVA_HOME
  APK=poc/spikes/showcase-android/app/build/outputs/apk/release/app-release.apk
  run sh -c "cd poc/spikes/showcase-android && ./gradlew -q :app:assembleRelease"
  run adb -s "$SERIAL" install -r "$APK"
  if [ $DRY = 1 ]; then GOT="$VERSION (dry run)"; else
    GOT=$(adb -s "$SERIAL" shell dumpsys package app.mauri.sukrara.poc | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')
    [ "$GOT" = "$VERSION" ] || die "phone reports versionName '$GOT', expected '$VERSION'."
  fi
else
  # 2b. Team-signed bundle with widgets, replace the user's copy, restart the widget extension (NEXT.md section 5).
  VERSION=$(sed -n 's/.*CFBundleShortVersionString<\/key><string>\([^<]*\)<.*/\1/p' poc/spikes/menubar/bundle.sh)
  DEST="$HOME/Applications/Sukrara.app"
  run sh -c "cd poc/spikes/menubar && ./bundle.sh"
  if [ $DRY = 0 ]; then   # the user's copy is team-signed with the widget; an ad hoc build would drop the widgets
    codesign -dv poc/spikes/menubar/build/Sukrara.app 2>&1 | grep -q '^TeamIdentifier=[A-Z0-9]' &&
      [ -d poc/spikes/menubar/build/Sukrara.app/Contents/PlugIns/SukraraWidget.appex ] ||
      die "the build is not team-signed with the widget (bundle.sh found no Apple Development identity?)."
  fi
  run pkill -x Sukrara || true
  run rm -rf "$DEST"
  run ditto poc/spikes/menubar/build/Sukrara.app "$DEST"
  run open "$DEST"
  run pkill -f SukraraWidget.appex || true
  # Unregister every other registered copy (worktree / test builds), or chronod may run their stale extension.
  pluginkit -mAv 2>/dev/null | grep -i 'app.mauri.sukrara' | awk -F'\t' '{print $NF}' | grep -v "^$DEST/" |
    while IFS= read -r p; do run pluginkit -r "$p"; done
  GOT=$VERSION
fi

# 3. Record and show what the device now runs.
LINE="$(git rev-parse HEAD) $(date '+%Y-%m-%dT%H:%M') $GOT $(git rev-parse --abbrev-ref HEAD)"
if [ $DRY = 1 ]; then echo "would record in $RECORD: $LINE"; else echo "$LINE" > "$RECORD"; fi
echo "$TARGET: Sukrara $GOT from $HEAD ($(git log -1 --format=%s HEAD))"
