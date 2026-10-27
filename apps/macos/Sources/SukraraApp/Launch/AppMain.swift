// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import os

/// The app's entry point (`main.swift` calls `run()`): decide the launch plan, then start AppKit or refuse visibly.
public enum AppMain {
    private static let log = Logger(subsystem: "app.mauri.sukrara", category: "launch")

    public static func run() -> Never {
        let process = ProcessInfo.processInfo
        let plan: LaunchPlan
        do {
            plan = try LaunchPlan.parse(
                Array(process.arguments.dropFirst()),
                build: currentBuild,
                env: process.environment,
                home: FileManager.default.homeDirectoryForCurrentUser
            )
        } catch {
            refuse(error)
        }
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let delegate = AppDelegate(model: AppModel(plan: plan))
        app.delegate = delegate
        app.run()
        exit(0)
    }

    static var currentBuild: BuildInfo {
        #if DEBUG
            let debug = true
        #else
            let debug = false
        #endif
        return BuildInfo(
            isDebugBuild: debug,
            bundlePath: Bundle.main.bundlePath,
            isBundle: Bundle.main.bundleIdentifier != nil
        )
    }

    /// A refused launch never exits silently: stderr, the system log and, when started from a bundle (Finder /
    /// Dock / login item, where nobody sees stderr), an alert. A silent exit would look like a running CGM app.
    private static func refuse(_ error: LaunchError) -> Never {
        FileHandle.standardError.write(Data("Sukrara: \(error.message)\n".utf8))
        log.error("launch refused (exit \(error.exitCode)): \(error.message, privacy: .public)")
        if Bundle.main.bundleIdentifier != nil {
            NSApplication.shared.setActivationPolicy(.regular)
            let alert = NSAlert()
            alert.alertStyle = .critical
            alert.messageText = "Sukrara can't start"
            alert.informativeText = error.message
            alert.addButton(withTitle: "Quit")
            NSApplication.shared.activate()
            alert.runModal()
        }
        exit(error.exitCode)
    }
}
