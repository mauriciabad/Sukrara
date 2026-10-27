// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Sandbox probe: the widget's exact data-loading code (Shared/Loader.swift), in a command-line tool
/// signed ad-hoc with the widget's entitlements. Running it answers "which transports can a sandboxed,
/// ad-hoc-signed widget read?" without needing chronod to launch the real extension.
let sema = DispatchSemaphore(value: 0)
Task {
    let (s, via, report) = await Loader.load()
    print("sandboxed home: \(NSHomeDirectory())")
    print("best: \(via)  latest: \(s?.latest.map { "\(Int($0.v)) mg/dL @ \($0.t)" } ?? "none")")
    for r in report { print("  \(r)") }
    // Can this (sandboxed, ad-hoc) process write into the app-group container itself?
    if let g = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedLocation.appGroup) {
        print("group container: \(g.path)")
        do {
            try FileManager.default.createDirectory(at: g, withIntermediateDirectories: true)
            try Data("probe".utf8).write(to: g.appendingPathComponent("probe-write-test"))
            print("  group write: ok")
        } catch { print("  group write: \((error as NSError).code) \(error.localizedDescription)") }
    }
    sema.signal()
}
sema.wait()
