#!/bin/sh
# Sukrara — https://github.com/mauriciabad/Sukrara
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Checks a V1 Mac bundle before it is installed or released:
#   tools/release/verify-app.sh <Sukrara.app> [--unsigned]
# - bundle id app.mauri.sukrara, version / build = tools/release/version.sh (dev builds may add .<yyMMddHHmm>)
# - the widget extension (app.mauri.sukrara.widget) is inside
# - signed (unless --unsigned): codesign --verify --deep --strict, team QWUV9H4A3G on the app and the appex
# - a release build: no debug entry points in any binary (debugsend, DebugFakes, Synthetic*, the snapshot renderer)
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/lib.sh"
app="${1:?usage: verify-app.sh <Sukrara.app> [--unsigned]}"
unsigned="${2:-}"
fail=0
bad() { echo "FAIL $*"; fail=1; }
ok() { echo "ok   $*"; }
plist() { /usr/libexec/PlistBuddy -c "Print :$2" "$1/Contents/Info.plist" 2>/dev/null || true; }
set -- $(version_info)
want_name="$1" want_code="$2"

id="$(plist "$app" CFBundleIdentifier)"
[ "$id" = app.mauri.sukrara ] && ok "bundle id $id" || bad "bundle id '$id' (want app.mauri.sukrara)"
[ "$(plist "$app" CFBundleShortVersionString)" = "$want_name" ] && ok "version $want_name" || bad "CFBundleShortVersionString isn't $want_name"
case "$(plist "$app" CFBundleVersion)" in
  "$want_code" | "$want_code".*) ok "build $(plist "$app" CFBundleVersion)" ;;
  *) bad "CFBundleVersion '$(plist "$app" CFBundleVersion)' (want $want_code[.yyMMddHHmm])" ;;
esac
appex="$app/Contents/PlugIns/SukraraWidget.appex"
if [ -d "$appex" ] && [ "$(plist "$appex" CFBundleIdentifier)" = app.mauri.sukrara.widget ]; then ok "widget extension inside"
else bad "SukraraWidget.appex (app.mauri.sukrara.widget) missing"; fi

if [ "$unsigned" != --unsigned ]; then
  if codesign --verify --deep --strict "$app" 2>/dev/null; then ok "signature verifies"; else bad "codesign --verify --deep --strict fails"; fi
  for b in "$app" "$appex"; do
    team="$(codesign -dv "$b" 2>&1 | sed -n 's/^TeamIdentifier=//p')"
    [ "$team" = QWUV9H4A3G ] && ok "team $team: $(basename "$b")" || bad "$(basename "$b") team '$team' (want QWUV9H4A3G)"
  done
fi

found=""
for bin in "$app"/Contents/MacOS/* "$appex"/Contents/MacOS/*; do
  [ -f "$bin" ] || continue
  if strings -a "$bin" | grep -Eq 'debugsend|DebugFakes|Synthetic[A-Z]|sukrara-snap'; then found="$found $(basename "$bin")"; fi
done
[ -z "$found" ] && ok "no debug entry points in the binaries" || bad "debug code compiled into:$found (build with -c release)"
exit $fail
