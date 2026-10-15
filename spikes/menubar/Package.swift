// swift-tools-version:6.0
// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "GlucoseBar",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "GlucoseBar", path: "Sources/GlucoseBar"),
    ],
    swiftLanguageModes: [.v5]
)
