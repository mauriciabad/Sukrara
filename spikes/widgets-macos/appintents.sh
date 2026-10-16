#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# App Intents metadata for the widget extension without an Xcode project (investigation 16: per-widget settings).
# WidgetKit only offers "Edit Widget" for an AppIntentConfiguration whose intent is described in the bundle's
# Resources/Metadata.appintents, which Xcode's "Extract App Intents Metadata" build phase writes. Same two steps:
#   1. swiftc with -emit-const-values-path (+ the protocol list) → <module>.swiftconstvalues   (the caller's swiftc)
#   2. appintentsmetadataprocessor (ships with Xcode, not the Command Line Tools) → Metadata.appintents
#   appintents.sh flags <workdir>              print the swiftc flags for step 1
#   appintents.sh extract <workdir> <module> <binary> <resources-dir> <sources…>
set -euo pipefail
mode=$1; W=${2:A}; mkdir -p $W
if [[ $mode == flags ]]; then
  echo '["AppIntent","EntityQuery","AppEntity","TransientEntity","AppEnum","AppShortcutProviding","AppShortcutsProvider","AnyResolverProviding","AppIntentsPackage","DynamicOptionsProvider","WidgetConfigurationIntent"]' > $W/protocols.json
  echo "-wmo -emit-const-values-path $W/module.swiftconstvalues -Xfrontend -const-gather-protocols-file -Xfrontend $W/protocols.json"
  exit 0
fi
module=$3; binary=$4; res=$5; shift 5
P=$(xcrun --find appintentsmetadataprocessor 2>/dev/null) || { echo "appintents: no appintentsmetadataprocessor (needs Xcode); the widget gets no Edit Widget settings"; exit 0; }
for f in "$@"; do echo ${f:A}; done > $W/sources.txt
echo $W/module.swiftconstvalues > $W/constvals.txt
mkdir -p $res
$P --output $res --toolchain-dir ${$(xcrun --find swiftc):h:h:h} --module-name $module \
  --sdk-root "$(xcrun --show-sdk-path --sdk macosx)" --xcode-version "$(xcodebuild -version | awk '/Build version/{print $3}')" \
  --platform-family macOS --deployment-target 15.0 --target-triple arm64-apple-macos15.0 \
  --source-file-list $W/sources.txt --swift-const-vals-list $W/constvals.txt --binary-file $binary \
  --force --deployment-aware-processing --no-app-shortcuts-localization --quiet-warnings 2>&1 | grep -v "Starting\|Writing\|Metadata root" || true
[[ -d $res/Metadata.appintents ]] && echo "appintents: $res/Metadata.appintents"
