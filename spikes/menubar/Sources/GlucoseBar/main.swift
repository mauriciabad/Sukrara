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
//   nostr   cgmnostr:1?s=…&k=…&r=… follower link → CGM-over-Nostr transport (or `nostr-file <path>` holding it)
//   lvUpload    YES to upload treatments to LibreView (spikes/libreview/SPEC.md); default NO
//   lvEmail     LibreView login; password in the keychain:
//               security add-generic-password -s dev.spike.libreview -a <email> -w
//   lvTakeover  YES to log in with SetDevice:true (kicks LibreView's other active app); default NO
let defaults = UserDefaults.standard
setvbuf(stdout, nil, _IOLBF, 0)

if let path = defaults.string(forKey: "preview") {
    Preview.render(to: path)
    exit(0)
}
if let path = defaults.string(forKey: "lvfixture") { exit(SelfTest.fixture(path) ? 0 : 1) }
if CommandLine.arguments.contains("-treatmentsselftest") { exit(SelfTest.merge() ? 0 : 1) }
// Spike experiments: `GlucoseBar -debugsend '{"cmd":"add","k":"rapid","a":0.5,"n":"…"}'` drives the running
// instance (cmd add | edit {id,a,n} | delete {id} | upload | dump), like the phone's DebugReceiver.
let debugNote = Notification.Name("dev.spike.GlucoseBar.debug")
if let i = CommandLine.arguments.firstIndex(of: "-debugsend"), i + 1 < CommandLine.arguments.count {
    let msg = CommandLine.arguments[i + 1]     // not via UserDefaults: it would parse "{…}" as a plist
    DistributedNotificationCenter.default().postNotificationName(debugNote, object: msg, userInfo: nil, deliverImmediately: true)
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
let lanEnabled = defaults.object(forKey: "lan") == nil || defaults.bool(forKey: "lan")
if lanEnabled {
    MainActor.assumeIsolated { feed.restart("launch") }
}

// Created only when enabled: CBCentralManager starts scanning as soon as Bluetooth reports powered on.
let ble = defaults.object(forKey: "ble") == nil || defaults.bool(forKey: "ble") ? BLEBridgeSource() : nil
if let ble {
    ble.onState = { log($0) }
    ble.onHistory = { pts in MainActor.assumeIsolated { store.ingest(history: pts, from: "Bluetooth · GlucoBridge") } }
    ble.onReading = { r in MainActor.assumeIsolated { store.ingest(r, from: "Bluetooth · GlucoBridge") } }
}

let nostrLink = defaults.string(forKey: "nostr")
    ?? defaults.string(forKey: "nostr-file").flatMap { try? String(contentsOfFile: $0, encoding: .utf8) }
let nostr = MainActor.assumeIsolated { nostrLink.flatMap(NostrSource.init(link:)) }
if let nostr {
    MainActor.assumeIsolated {
        nostr.onReading = { store.ingest($0, from: $1) }
        nostr.onHistory = { store.ingest(history: $0, from: $1) }
        nostr.restart("launch")
    }
}

// Treatments: local log, synced with the phone over BLE, uploaded to LibreView (spikes/libreview/SPEC.md).
MainActor.assumeIsolated {
    let treatments = TreatmentStore()
    let sync = TreatmentSync(store: treatments)
    let uploader = LibreViewUploader(store: treatments)
    sync.log = log
    uploader.log = log
    uploader.onStatus = { status.treatmentsChanged() }
    treatments.onChange.append { _ in status.treatmentsChanged() }
    ble?.treatmentSync = sync          // nil when BLE is off (e.g. Nostr-only)
    status.treatments = { treatments.rows }
    status.libreViewStatus = { uploader.statusLine }
    status.onLogTreatment = { t in
        log("treatment logged: \(StatusController.describe(t))")
        treatments.upsertLocal(t)
    }
    status.onDeleteTreatment = { treatments.delete($0) }
    status.onUploadNow = { uploader.retry() }
    status.treatmentsChanged()
    uploader.kick()

    DistributedNotificationCenter.default().addObserver(forName: debugNote, object: nil, queue: .main) { n in
        MainActor.assumeIsolated {
            guard let s = n.object as? String,
                  let o = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] else { return }
            let id = o["id"] as? String ?? ""
            switch o["cmd"] as? String {
            case "add":
                let t = Treatment(k: o["k"] as? String ?? "note", a: o["a"] as? Double ?? 0,
                                  t: Int64(Date().timeIntervalSince1970), n: o["n"] as? String ?? "")
                log("debug add \(t.id)")
                treatments.upsertLocal(t)
            case "edit":
                guard var t = treatments.row(id) else { return }
                if let a = o["a"] as? Double { t.a = a }
                if let n = o["n"] as? String { t.n = n }
                t.u = max(Treatment.nowMs(), t.u + 1)
                treatments.upsertLocal(t)
            case "delete": treatments.delete(id)
            case "upload": uploader.retry()
            case "dump":
                for t in treatments.rows { log("debug row \(t.dict)") }
                log("debug \(uploader.statusLine)")
            default: break
            }
        }
    }
}

status.onRefresh = { MainActor.assumeIsolated { if lanEnabled { feed.restart("manual refresh") }; nostr?.restart("manual refresh") } }

// After sleep the SSE/WebSocket sockets are dead but URLSession would only notice after its idle timeout.
NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
    MainActor.assumeIsolated { if lanEnabled { feed.restart("system woke") }; nostr?.restart("system woke") }
}

app.run()
