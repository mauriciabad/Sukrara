#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Compile Trio's and LoopFollow's own glucose models (from the local third_party/ checkouts, not committed here) with
# main.swift and run them against a Nightscout.   NS_URL, NS_API_SECRET, NS_READ_TOKEN
set -e
here=${0:A:h}
tp=${here:h:h:h:h}/third_party
[[ -d $tp/Trio ]] || tp=$(git -C $here rev-parse --path-format=absolute --git-common-dir)/../third_party
build=${TMPDIR:-/tmp}/i25-followers; rm -rf $build; mkdir -p $build
cp $tp/Trio/Trio/Sources/Models/BloodGlucose.swift $build/
# LoopFollow's decoder struct, without the ShareClient (Dexcom) extension below it
{ echo "import Foundation"; sed -n '/^public struct ShareGlucoseData/,/^private var TrendTable/p' \
  $tp/LoopFollow/LoopFollow/Extensions/ShareClientExtension.swift | sed '$d'; } > $build/ShareGlucoseData.swift
echo "Trio $(git -C $tp/Trio rev-parse --short HEAD), LoopFollow $(git -C $tp/LoopFollow rev-parse --short HEAD)"
swiftc -O -module-name Trio -o $build/followers $here/Shims.swift $here/main.swift $build/BloodGlucose.swift $build/ShareGlucoseData.swift
NS_API_SECRET_SHA1=$(printf %s "$NS_API_SECRET" | shasum | cut -d' ' -f1) $build/followers
