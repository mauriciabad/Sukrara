#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Package build/Sukrara.app (run ./bundle.sh first) as the user-facing installer:
# build/Sukrara-<version>.dmg, a window with the app, an arrow and an Applications shortcut to drag it onto.
# The layout needs dmgbuild (`uv tool install dmgbuild`); without it you get a plain image with the same two items.
# The dmg is signed with the app's identity when it has one (a Developer ID one can then be notarized).
set -e
cd "$(dirname "$0")"
APP=build/Sukrara.app
[ -d "$APP" ] || { echo "no $APP: run ./bundle.sh first" >&2; exit 1; }
VER=$(plutil -extract CFBundleShortVersionString raw "$APP/Contents/Info.plist")
OUT=build/Sukrara-$VER.dmg
W=build/dmg; rm -rf $W "$OUT"; mkdir -p $W
if command -v dmgbuild >/dev/null; then
  swift dmg/background.swift $W
  tiffutil -cathidpicheck $W/background.png $W/background@2x.png -out $W/background.tiff >/dev/null
  dmgbuild -s dmg/settings.py -D app="$APP" -D background=$W/background.tiff Sukrara "$OUT"
else
  echo "dmgbuild not found: plain image without the window layout (uv tool install dmgbuild)" >&2
  mkdir -p $W/root && cp -R "$APP" $W/root/ && ln -s /Applications $W/root/Applications
  hdiutil create -volname Sukrara -srcfolder $W/root -ov -format UDZO "$OUT" >/dev/null
fi
# Same identity as the app (bundle.sh's choice: TeamIdentifier set → that certificate, else none).
ID=$(codesign -dvv "$APP" 2>&1 | sed -n 's/^Authority=//p' | head -1)
if [ -n "$ID" ]; then codesign --force --sign "$ID" "$OUT"; fi
echo "built $OUT${ID:+ (signed: $ID)}"
