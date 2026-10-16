// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Network
import os
import WidgetKit

/// Headless host app (LSUIElement) for the widget extension. Once a minute it produces a glucose
/// snapshot (synthetic sine for the spike), publishes it on every transport the widget might be able
/// to read, and asks WidgetKit to reload. Options are UserDefaults (`-reloadInterval 60` etc.).
let hlog = Logger(subsystem: "dev.spike.GlucoseWidgets", category: "host")

func say(_ s: String) {
    let line = "\(ISO8601DateFormatter().string(from: Date())) \(s)"
    print(line); fflush(stdout)
    hlog.notice("\(s, privacy: .public)")
}

final class Host: NSObject, NSApplicationDelegate {
    var snapshot = Synthetic.snapshot(hours: 12)
    var listener: NWListener?
    var reloads = 0
    let reloadInterval = max(5, UserDefaults.standard.double(forKey: "reloadInterval").nonZero ?? 60)

    func applicationDidFinishLaunching(_ n: Notification) {
        NSAppleEventManager.shared().setEventHandler(self, andSelector: #selector(openURL(_:reply:)),
            forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL))
        startHTTP()
        tick()
        // A new sample every minute, aligned to the minute (like a Libre 3 stream).
        let first = ceil(Date().timeIntervalSince1970 / 60) * 60 + 1
        let t = Timer(fire: Date(timeIntervalSince1970: first), interval: 60, repeats: true) { [weak self] _ in self?.tick() }
        RunLoop.main.add(t, forMode: .common)
        if reloadInterval != 60 {
            let r = Timer(timeInterval: reloadInterval, repeats: true) { [weak self] _ in self?.reload("timer") }
            RunLoop.main.add(r, forMode: .common)
        }
        say("host up; http 127.0.0.1:\(SharedLocation.httpPort); reloadInterval \(reloadInterval)s")
    }

    func tick() {
        snapshot = Synthetic.snapshot(hours: 12)
        publish()
        if reloadInterval == 60 { reload("new-sample") }
    }

    func reload(_ why: String) {
        reloads += 1
        WidgetCenter.shared.reloadAllTimelines()
        say("reloadAllTimelines #\(reloads) (\(why)) latest=\(Int(snapshot.latest?.v ?? 0)) @\(Int(snapshot.latest?.t.timeIntervalSince1970 ?? 0))")
    }

    func publish() {
        guard let data = try? JSONEncoder.glucose.encode(snapshot) else { return }
        let home = NSHomeDirectory() // host is not sandboxed: the real home
        let targets: [(String, URL)] = [
            ("appgroup", SharedLocation.groupFile(home: home)),
            ("widget-container", SharedLocation.widgetContainerFile(home: home)),
            ("users-shared", SharedLocation.sharedFile),
        ]
        var res: [String] = []
        for (name, url) in targets {
            do {
                try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
                try data.write(to: url, options: .atomic)
                res.append("\(name)=ok")
            } catch {
                res.append("\(name)=\((error as NSError).code)")
            }
        }
        say("published \(data.count) B: \(res.joined(separator: " "))")
    }

    // MARK: HTTP on 127.0.0.1 (one GET, one response, close)

    func startHTTP() {
        do {
            let p = NWParameters.tcp
            p.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: SharedLocation.httpPort)!)
            let l = try NWListener(using: p)
            l.newConnectionHandler = { [weak self] c in self?.serve(c) }
            l.start(queue: .main)
            listener = l
        } catch {
            say("http listen failed: \(error)")
        }
    }

    func serve(_ c: NWConnection) {
        c.start(queue: .main)
        c.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] d, _, _, _ in
            guard let self else { return }
            let req = d.flatMap { String(data: $0, encoding: .utf8) }?.split(separator: "\r\n").first ?? "?"
            say("http \(req)")
            let body = (try? JSONEncoder.glucose.encode(self.snapshot)) ?? Data()
            let head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
            c.send(content: Data(head.utf8) + body, completion: .contentProcessed { _ in c.cancel() })
        }
    }

    // widgetURL → glucosewidgets://open?from=… lands here.
    @objc func openURL(_ e: NSAppleEventDescriptor, reply: NSAppleEventDescriptor) {
        let url = e.paramDescriptor(forKeyword: keyDirectObject)?.stringValue ?? "?"
        say("opened via URL \(url)")
        // A real app would show its window / menubar dropdown here; the spike only proves the hand-off.
    }
}

extension Double { var nonZero: Double? { self == 0 ? nil : self } }

let app = NSApplication.shared
let host = Host()
app.delegate = host
app.setActivationPolicy(.accessory)
app.run()
