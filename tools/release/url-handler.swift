// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Makes the given app the default handler of sukrara: links and prints the handler macOS now uses.
//   swift tools/release/url-handler.swift ~/Applications/Sukrara.app
// lsregister only registers an app; with the showcase also claiming sukrara:, only this makes V1 the default.
import AppKit

let app = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "")
let probe = URL(string: "sukrara://join")!
let done = DispatchSemaphore(value: 0)
var failure: Error?
NSWorkspace.shared.setDefaultApplication(at: app, toOpenURLsWithScheme: "sukrara") { error in
    failure = error
    done.signal()
}
_ = done.wait(timeout: .now() + 30)
let handler = NSWorkspace.shared.urlForApplication(toOpen: probe)?.standardizedFileURL.path ?? "none"
print("sukrara: links open with \(handler)")
if let failure { print("could not set the default handler: \(failure.localizedDescription)") }
exit(handler == app.standardizedFileURL.path ? 0 : 1)
