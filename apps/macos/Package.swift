// swift-tools-version: 6.2
// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import PackageDescription

/// Libraries the widget extension links are compiled extension-safe (no API marked unavailable to app extensions).
let extensionSafe: [SwiftSetting] = [.unsafeFlags(["-application-extension"])]

/// UI code: main-actor by default (SE-0466) plus the "approachable concurrency" features that make that ergonomic.
let mainActorByDefault: [SwiftSetting] = [
    .defaultIsolation(MainActor.self),
    .enableUpcomingFeature("NonisolatedNonsendingByDefault"),
    .enableUpcomingFeature("InferIsolatedConformances"),
]

let package = Package(
    name: "Sukrara",
    defaultLocalization: "en",
    platforms: [.macOS(.v15)],
    products: [
        .executable(name: "Sukrara", targets: ["Sukrara"]),
        .executable(name: "SukraraWidget", targets: ["SukraraWidget"]),
        .executable(name: "sukrara-snap", targets: ["sukrara-snap"]),
    ],
    dependencies: [
        // rust-nostr via UniFFI (prebuilt xcframework): keys, NIP-44, event ids and signatures for protocol v2.
        .package(url: "https://github.com/rust-nostr/nostr-sdk-swift", exact: "0.45.1")
    ],
    targets: [
        // Models, ids, paths, settings spec, store protocols. Nonisolated, extension-safe.
        .target(name: "SukraraCore", swiftSettings: extensionSafe),
        // The one card renderer (app, widget, snapshots). Extension-safe.
        .target(name: "SukraraGraph", dependencies: ["SukraraCore"], swiftSettings: extensionSafe),
        // The alert engine: pure, driven by contracts/alerts scenarios.
        .target(name: "SukraraAlerts", dependencies: ["SukraraCore"]),
        // Protocol v2, relays, nearby: every transport behind a protocol so tests use fakes.
        .target(
            name: "SukraraShare",
            dependencies: ["SukraraCore", .product(name: "NostrSDK", package: "nostr-sdk-swift")]
        ),
        // The menu bar app: AppDelegate, AppModel, windows, launch plan.
        .target(
            name: "SukraraApp",
            dependencies: ["SukraraCore", "SukraraGraph", "SukraraAlerts", "SukraraShare"],
            resources: [.process("Windows/Localizable.xcstrings")],
            swiftSettings: mainActorByDefault
        ),
        .executableTarget(name: "Sukrara", dependencies: ["SukraraApp"], swiftSettings: mainActorByDefault),
        // WidgetKit extension. Xcode links extensions with Foundation's _NSExtensionMain as the entry point (the Swift
        // main traps in ExtensionFoundation); `-u _main` keeps the @main WidgetBundle that nothing else references
        // from being dead-stripped. Bundle/bundle.sh wraps the binary as SukraraWidget.appex.
        .executableTarget(
            name: "SukraraWidget",
            dependencies: ["SukraraCore", "SukraraGraph"],
            swiftSettings: extensionSafe,
            linkerSettings: [
                .unsafeFlags([
                    "-application-extension", "-Xlinker", "-e", "-Xlinker", "_NSExtensionMain",
                    "-Xlinker", "-u", "-Xlinker", "_main",
                ])
            ]
        ),
        // Offline snapshot renderer for every surface (debug builds only).
        .executableTarget(
            name: "sukrara-snap", dependencies: ["SukraraApp", "SukraraCore", "SukraraGraph"],
            swiftSettings: mainActorByDefault),

        // Test support: fakes for every store / transport protocol and the contracts/ locator.
        .target(name: "SukraraTesting", dependencies: ["SukraraCore", "SukraraShare"], path: "Tests/SukraraTesting"),
        .testTarget(name: "CoreTests", dependencies: ["SukraraCore", "SukraraTesting"]),
        .testTarget(name: "GraphTests", dependencies: ["SukraraGraph", "SukraraTesting"]),
        .testTarget(name: "AlertsTests", dependencies: ["SukraraAlerts", "SukraraTesting"]),
        .testTarget(name: "ShareTests", dependencies: ["SukraraShare", "SukraraTesting"]),
        .testTarget(
            name: "AppTests",
            dependencies: ["SukraraApp", "SukraraTesting"],
            swiftSettings: mainActorByDefault
        ),
    ],
    swiftLanguageModes: [.v6]
)
