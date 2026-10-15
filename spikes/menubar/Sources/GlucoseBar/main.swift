// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

// Config comes from UserDefaults, so it works both as CLI args (`-url http://...`) and as
// `defaults write dev.spike.GlucoseBar url http://...` for the bundled .app.
//   url     http://<phone>:17580, or "auto" (default) to find Juggluco on the local network
//   secret  Juggluco web server API secret, if set
//   unit    mgdl | mmol
//   lan     NO to disable the Juggluco web-server transport
//   ble     NO to disable the GlucoBridge BLE transport
let defaults = UserDefaults.standard
setvbuf(stdout, nil, _IOLBF, 0)

if let path = defaults.string(forKey: "preview") {
    Preview.render(to: path)
    exit(0)
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menubar only, no Dock icon

let status = StatusController()
status.unit = GlucoseUnit(rawValue: defaults.string(forKey: "unit") ?? "") ?? .mgdl

let configuredURL = defaults.string(forKey: "url").flatMap { $0 == "auto" ? nil : URL(string: $0) }
let secret = defaults.string(forKey: "secret")
let historySpan: TimeInterval = 3 * 3600

func log(_ s: String) { print("\(Date().formatted(date: .omitted, time: .standard))  \(s)") }

/// Merges readings from every transport (same reading may arrive over Wi-Fi and BLE) and drives the UI.
@MainActor final class Store {
    private var history: [Reading] = []
    private var lastSource = ""

    func ingest(history new: [Reading], from source: String) {
        new.forEach { merge($0) }
        publish(source)
    }

    func ingest(_ r: Reading, from source: String) {
        let isNew = !(history.last.map { r.date.timeIntervalSince($0.date) < 20 } ?? false)
        merge(r)
        publish(source)
        if isNew || source != lastSource {
            log("[\(source)] \(Int(r.mgdl)) mg/dL  rate \(r.rate.map { String(format: "%+.2f", $0) } ?? "nil")/min"
                + "  reading at \(r.date.formatted(date: .omitted, time: .standard))"
                + "  latency \(String(format: "%.1f", Date().timeIntervalSince(r.date)))s")
        }
        lastSource = source
    }

    private func merge(_ r: Reading) {
        if let i = history.firstIndex(where: { abs($0.date.timeIntervalSince(r.date)) < 20 }) {
            if r.rate != nil || history[i].rate == nil { history[i] = r }   // keep the copy that carries a rate
        } else {
            history.append(r)
            history.sort { $0.date < $1.date }
        }
        if let newest = history.last?.date { history.removeAll { newest.timeIntervalSince($0.date) > historySpan } }
    }

    private func publish(_ source: String) {
        guard let latest = history.last else { return }
        status.update(Snapshot(latest: latest, history: history, source: source))
    }
}

let store = MainActor.assumeIsolated { Store() }

/// Wi-Fi/LAN transport: find Juggluco (unless a URL is configured), fetch per-minute history, then follow
/// the live SSE stream; on any failure wait and start over (re-discovering, since the phone's IP may change).
@MainActor final class Feed {
    private var task: Task<Void, Never>?
    private var source: JugglucoSource?

    func restart(_ why: String) {
        log("lan feed restart: \(why)")
        task?.cancel()
        task = Task { await run() }
    }

    private func run() async {
        var failures = 0
        while !Task.isCancelled {
            do {
                if source == nil || (configuredURL == nil && failures >= 2) {
                    if let url = configuredURL {
                        source = JugglucoSource(base: url, secret: secret)
                    } else {
                        guard let url = await Discovery.find(secret: secret) else {
                            throw NSError(domain: "Discovery", code: 0,
                                          userInfo: [NSLocalizedDescriptionKey: "Juggluco not found on local network"])
                        }
                        log("discovery: found \(url)")
                        source = JugglucoSource(base: url, secret: secret)
                    }
                }
                let src = source!
                let label = "Wi-Fi · \(src.name)"
                store.ingest(history: try await src.history(), from: label)
                failures = 0
                for try await r in src.live() { store.ingest(r, from: label) }
                log("lan: live stream ended")
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                failures += 1
                log("lan error (\(failures)): \(error.localizedDescription)")
            }
            try? await Task.sleep(for: .seconds(failures > 3 ? 30 : 5))
        }
    }
}

let feed = MainActor.assumeIsolated { Feed() }
if defaults.object(forKey: "lan") == nil || defaults.bool(forKey: "lan") {
    MainActor.assumeIsolated { feed.restart("launch") }
}

let ble = BLEBridgeSource()
if defaults.object(forKey: "ble") == nil || defaults.bool(forKey: "ble") {
    ble.onState = { log($0) }
    ble.onHistory = { pts in MainActor.assumeIsolated { store.ingest(history: pts, from: "Bluetooth · GlucoBridge") } }
    ble.onReading = { r in MainActor.assumeIsolated { store.ingest(r, from: "Bluetooth · GlucoBridge") } }
}

status.onRefresh = { MainActor.assumeIsolated { feed.restart("manual refresh") } }

// After sleep the SSE socket is dead but URLSession would only notice after its 60 s idle timeout.
NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
    MainActor.assumeIsolated { feed.restart("system woke") }
}

app.run()
