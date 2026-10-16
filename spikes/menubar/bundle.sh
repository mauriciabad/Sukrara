#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Build a release Sukrara.app ("Sukrara for Mac", formerly GlucoseBar; menubar-only, ad-hoc signed).
# The Swift package/target is still called GlucoseBar; the bundle, executable and id are Sukrara's.
# Configure:  defaults write app.mauri.sukrara.poc url http://192.168.10.127:17580
#             defaults write app.mauri.sukrara.poc unit mmol      # optional
#             defaults write app.mauri.sukrara.poc secret '...'   # if Juggluco has an API secret
#             defaults write app.mauri.sukrara.poc nostr 'cgmnostr:1?...'   # legacy v1 Nostr link
# Protocol v2 (default): menubar → "Follow someone…" (paste https://sukrara.mauri.app/join#… or cgmshare:join?…),
# or open a cgmshare:join?… link (URL scheme registered below; the web page's "Open in Sukrara" button uses it).
# First launch copies GlucoseBar's defaults + follower Keychain item (Migration.swift).
set -e
cd "$(dirname "$0")"
swift build -c release
APP=build/Sukrara.app
ICON=../brand/assets/sukrara/macos/AppIcon.icns
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp .build/release/GlucoseBar "$APP/Contents/MacOS/Sukrara"
cp "$ICON" "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>app.mauri.sukrara.poc</string>
  <key>CFBundleName</key><string>Sukrara</string>
  <key>CFBundleDisplayName</key><string>Sukrara</string>
  <key>CFBundleExecutable</key><string>Sukrara</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.10.2</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>CFBundleURLTypes</key><array><dict>
    <key>CFBundleURLName</key><string>Sukrara invite</string>
    <key>CFBundleURLSchemes</key><array><string>cgmshare</string></array>
  </dict></array>
  <key>NSBluetoothAlwaysUsageDescription</key><string>Read a FreeStyle Libre 3 sensor directly.</string>
  <key>NSLocalNetworkUsageDescription</key><string>Read glucose from Juggluco on your phone.</string>
</dict></plist>
EOF
codesign --force --sign - "$APP"
echo "built $APP"
