#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
# Build a release GlucoseBar.app (menubar-only, ad-hoc signed).
# Configure:  defaults write dev.spike.GlucoseBar url http://192.168.10.127:17580
#             defaults write dev.spike.GlucoseBar unit mmol      # optional
#             defaults write dev.spike.GlucoseBar secret '...'   # if Juggluco has an API secret
set -e
cd "$(dirname "$0")"
swift build -c release
APP=build/GlucoseBar.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp .build/release/GlucoseBar "$APP/Contents/MacOS/"
cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>dev.spike.GlucoseBar</string>
  <key>CFBundleName</key><string>GlucoseBar</string>
  <key>CFBundleExecutable</key><string>GlucoseBar</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSBluetoothAlwaysUsageDescription</key><string>Read a FreeStyle Libre 3 sensor directly.</string>
  <key>NSLocalNetworkUsageDescription</key><string>Read glucose from Juggluco on your phone.</string>
</dict></plist>
EOF
codesign --force --sign - "$APP"
echo "built $APP"
