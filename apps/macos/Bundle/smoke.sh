#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Launch smoke test: starts a Sukrara executable as a dev run (SUKRARA_DEV=1, data in a fresh temp folder), checks it
# is still running after a few seconds, has the first-run setup window on screen (its text comes from the String
# Catalog: a missing resource bundle traps before it shows) and no network socket (`lsof -i`), then stops it. For a
# bundle it first checks the resource bundle (with es.lproj) and CFBundleLocalizations (en, es) are in place.
#   Bundle/smoke.sh [<executable>]   default: build/Sukrara.app/Contents/MacOS/Sukrara (Bundle/bundle.sh)
#   FIXTURE=1 Bundle/smoke.sh .build/debug/Sukrara   the debug fixture run (synthetic wearer, debug builds only)
#   SECONDS_UP=<n>                  how long it must stay up (default 8)
# Never runs the installed app: a copy outside the repo isn't a dev build and would start with the user's data.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd -P)
PKG=$(cd "$HERE/.." && pwd -P)
EXE=${1:-$PKG/build/Sukrara.app/Contents/MacOS/Sukrara}
UP=${SECONDS_UP:-8}
die() { echo "smoke.sh: $*" >&2; exit 1; }

[ -x "$EXE" ] || die "no executable at $EXE (run Bundle/bundle.sh first)"
EXE_DIR=$(cd "$(dirname "$EXE")" && pwd -P)
case "$EXE_DIR" in
  "$PKG"/*) ;;
  *) die "$EXE is not inside $PKG: only a dev build is smoke-tested" ;;
esac
case "$EXE_DIR" in
  *.app/Contents/MacOS)
    CONTENTS=$(dirname "$EXE_DIR")
    [ -d "$CONTENTS/Resources/Sukrara_SukraraApp.bundle/Contents/Resources/es.lproj" ] \
      || die "no Sukrara_SukraraApp.bundle with es.lproj in $CONTENTS/Resources"
    plutil -extract CFBundleLocalizations json -o - "$CONTENTS/Info.plist" | grep -q '"es"' \
      || die "Info.plist has no CFBundleLocalizations with es"
    ;;
esac

HOME_DIR=$(mktemp -d "${TMPDIR:-/tmp}/sukrara-smoke.XXXXXX")
LOG="$HOME_DIR/run.log"
if [ "${FIXTURE:-}" = 1 ]; then
  SUKRARA_DEV=1 SUKRARA_FIXTURE=1 SUKRARA_DEV_HOME="$HOME_DIR" "$EXE_DIR/$(basename "$EXE")" >"$LOG" 2>&1 &
else
  SUKRARA_DEV=1 SUKRARA_DEV_HOME="$HOME_DIR" "$EXE_DIR/$(basename "$EXE")" >"$LOG" 2>&1 &
fi
PID=$!
trap 'kill "$PID" 2>/dev/null || true' EXIT

i=0
while [ "$i" -lt "$UP" ]; do
  sleep 1
  kill -0 "$PID" 2>/dev/null || { cat "$LOG" >&2; die "exited after ${i}s"; }
  i=$((i + 1))
done

WINDOWS=$(swift "$HERE/smoke-windows.swift" "$PID")
[ "$WINDOWS" -ge 1 ] || die "no window on screen (the first-run setup window should be open)"
kill -0 "$PID" 2>/dev/null || { cat "$LOG" >&2; die "exited"; }

SOCKETS=$(lsof -i -a -p "$PID" 2>/dev/null || true)
[ -z "$SOCKETS" ] || { echo "$SOCKETS" >&2; die "the dev run opened network sockets"; }
echo "smoke.sh: $EXE ran ${UP}s offline (pid $PID, $WINDOWS window(s), no network socket, data in $HOME_DIR)"
