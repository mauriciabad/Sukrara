// swift-tools-version:6.0
// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import PackageDescription

let package = Package(
    name: "GlucoseBar",
    platforms: [.macOS(.v14)],
    dependencies: [
        // rust-nostr via UniFFI (prebuilt xcframework, builds with Command Line Tools only):
        // bech32 keys, NIP-44 v2 decryption, event id + signature check for the Nostr transport.
        .package(url: "https://github.com/rust-nostr/nostr-sdk-swift", exact: "0.45.1"),
    ],
    targets: [
        .executableTarget(name: "GlucoseBar",
                          dependencies: [.product(name: "NostrSDK", package: "nostr-sdk-swift")],
                          path: "Sources/GlucoseBar"),
    ],
    swiftLanguageModes: [.v5]
)
