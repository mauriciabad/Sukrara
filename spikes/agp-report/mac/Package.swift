// swift-tools-version:6.0
// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import PackageDescription

// Investigation 26, the Mac side: the AGP engine ported to Swift (checked against the shared fixtures) and a
// CoreGraphics painter for the page's display list (vector PDF + PNG).
let package = Package(
    name: "AgpMac",
    platforms: [.macOS(.v14)],
    targets: [.executableTarget(name: "AgpMac", path: "Sources/AgpMac", swiftSettings: [.swiftLanguageMode(.v5)])]
)
