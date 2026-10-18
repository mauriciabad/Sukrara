#!/bin/sh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Regenerates the Libre 2 vectors in app/src/test/.../direct/Libre2Test.kt from DiaBLE's Swift (MIT), compiled
# unchanged: git clone https://github.com/gui-dos/DiaBLE third_party/DiaBLE (tested at e6a909c).
# Usage: tools/libre2-vectors/gen.sh [path to DiaBLE/DiaBLE]
set -eu
here=$(cd "$(dirname "$0")" && pwd)
# this checkout's third_party/, else the main checkout's (worktrees)
main=$(cd "$(git -C "$here" rev-parse --git-common-dir)/.." && pwd)
D=${1:-$here/../../../../third_party/DiaBLE/DiaBLE}
[ -d "$D" ] || D=$main/third_party/DiaBLE/DiaBLE
out=$(mktemp -d)
{
cat <<'SWIFT'
import Foundation
typealias SensorUid = Data
typealias PatchInfo = Data
enum SensorType { case libre2, libreUS14day }
enum SensorFamily: Int { case libre1 = 0, libre2 = 3 }
enum Sensor { enum Subcommand: UInt8 { case activate = 0x1b, enableStreaming = 0x1e } }
struct CalibrationInfo: Equatable { var i1 = 0, i2 = 0, i3 = 0, i4 = 0, i5 = 0, i6 = 0; static var empty = CalibrationInfo() }
struct Glucose { var rawValue: Int; var rawTemperature: Int; var temperatureAdjustment: Int; var id: Int; var value: Int = 0; var temperature: Double = 0 }
extension UInt16 {
SWIFT
sed -n 56,58p "$D/Extensions.swift"; sed -n 66,68p "$D/Extensions.swift"; echo "}"
echo "enum Libre2 {"; sed -n 56,57p "$D/Libre2.swift"; sed -n 64,171p "$D/Libre2.swift"; sed -n 238,332p "$D/Libre2.swift"; echo "}"
sed -n 278,329p "$D/Libre.swift"
sed -n 165,453p "$D/Glucose.swift"; echo "}"
} > "$out/diable.swift"
swiftc -O "$out/diable.swift" "$here/main.swift" -o "$out/vec" 2>/dev/null
"$out/vec"
