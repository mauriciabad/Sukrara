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
//   (protocol v2 follower — the default way to follow; see FOLLOWER.md)
//   bleV2       NO to disable the CGM Share BLE transport (docs/07)
//   followLink / followLinkFile / followName   tests: join an invite at launch without the dialog
//   pairNearby  tests: answer a phone's "Pair nearby" offer with this name, without the dialog
//   keychainService  tests: use a throwaway Keychain item;  v2NoRelays YES: BLE only;  pairSnapshot out.png
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
if CommandLine.arguments.contains("-v2selftest") { exit(MainActor.assumeIsolated { V2SelfTest.run() } ? 0 : 1) }
if CommandLine.arguments.contains("-qrselftest") { exit(MainActor.assumeIsolated { FollowUI.qrSelfTest() } ? 0 : 1) }
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

    /// Debug/test aid: current merged history as CSV (unix s, mg/dL, rate).
    var csv: String { history.map { "\(Int($0.date.timeIntervalSince1970)),\(Int($0.mgdl)),\($0.rate.map { String($0) } ?? "")" }.joined(separator: "\n") }

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

// Protocol v2 follower (NostrV2.swift): pairs like the Android "CGM Share" follower. State + keys in the Keychain.
@MainActor final class Following: NSObject {
    private(set) var node: FollowerV2?
    private let window = PairingWindow()
    private var showWindow = false

    func launch() {
        if let st = FollowerV2State.load(), let n = FollowerV2(state: st) {
            attach(n)
            n.start()
            if case .waiting = n.phase { n.sendRequest() }     // in case the app quit before it reached a relay
        } else if let link = defaults.string(forKey: "followLink")      // tests: pair without the dialog
                    ?? defaults.string(forKey: "followLinkFile").flatMap({ try? String(contentsOfFile: $0, encoding: .utf8) }) {
            join(link, name: defaults.string(forKey: "followName") ?? FollowUI.computerName)
        }
        NSAppleEventManager.shared().setEventHandler(self, andSelector: #selector(openURL(_:with:)),
                                                     forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL))
    }

    @objc func openURL(_ event: NSAppleEventDescriptor, with reply: NSAppleEventDescriptor) {
        guard let s = event.paramDescriptor(forKeyword: keyDirectObject)?.stringValue else { return }
        log("opened \(s.prefix(16))…")
        begin(prefill: s)
    }

    func begin(prefill: String? = nil) {
        guard let (link, name) = FollowUI.ask(prefill: prefill) else { return }
        join(link, name: name)
    }

    func beginFromQR() {
        guard let link = FollowUI.linkFromQRImage() else { return FollowUI.error("No cgmshare: QR code found in that image.") }
        begin(prefill: link)
    }

    // Bluetooth v2 (docs/07): same events, same acceptance code; plus nearby pairing when the phone has
    // "Pair nearby" open (INFO carries an invite token).
    var ble: BLEV2Central?
    private var offeredTokens: Set<String> = []

    func nearby(_ info: BLEV2Central.Info, from phone: String) {
        if let n = node {
            if n.st.i != info.i {
                log("BLE v2: \(phone) belongs to another wearer — ignored")
                ble?.disconnect(retryAfter: 600)
            }
            return
        }
        guard let t = info.token, !offeredTokens.contains(t) else { ble?.disconnect(retryAfter: 60); return }
        offeredTokens.insert(t)
        let r = info.relays.joined(separator: ",").addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        let link = "cgmshare:join?i=\(info.i)&t=\(t)&x=\(info.expiry ?? Int(Date().timeIntervalSince1970) + 600)&r=\(r)"
        var name = defaults.string(forKey: "pairNearby") ?? ""                   // tests: no dialog
        if name.isEmpty {
            NSApp.activate(ignoringOtherApps: true)
            let a = NSAlert()
            a.messageText = "Follow \(phone) nearby?"
            a.informativeText = "\(phone) is offering to share glucose over Bluetooth. Its owner will see a request and "
                + "a 4-digit code to compare with this Mac."
            let f = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
            f.stringValue = FollowUI.computerName
            a.accessoryView = f
            a.addButton(withTitle: "Ask to follow")
            a.addButton(withTitle: "Not now")
            guard a.runModal() == .alertFirstButtonReturn else { return }
            name = f.stringValue.isEmpty ? FollowUI.computerName : f.stringValue
        }
        log("BLE v2: nearby pairing with \(phone)")
        join(link, name: name, nearby: true)
    }

    func join(_ link: String, name: String, nearby: Bool = false) {
        switch FollowerV2.join(link: link, name: name, nearby: nearby) {
        case .failure(let why):
            log("v2 join failed: \(why.text)")
            if defaults.string(forKey: "followLink") == nil, defaults.string(forKey: "followLinkFile") == nil { FollowUI.error(why.text) }
        case .success(let n):
            stop()
            attach(n)
            showWindow = true
            n.start()
            n.sendRequest()
        }
    }

    private func attach(_ n: FollowerV2) {
        node = n
        n.extraPublish = { [weak self, weak n] ev in     // only to the phone of the wearer we follow
            if let b = self?.ble, b.ownerI != nil, b.ownerI == n?.st.i { b.sendEvent(ev) }
        }
        n.onNewKey = { [weak self] in self?.ble?.hello() }
        n.onReading = { store.ingest($0, from: $1) }
        n.onHistory = { store.ingest(history: $0, from: $1) }
        n.onPhase = { [weak self, weak n] p in
            guard let self, let n else { return }
            switch p {
            case .waiting(let code): status.setFollow("Following: waiting for approval · code \(code)")
            case .active(let e): status.setFollow("Following (v2) · key epoch \(e) · \(n.relayHosts.count) relays")
            case .revoked: status.setFollow("⚠︎ Access removed by the wearer")
            }
            if self.showWindow { self.window.show(p, name: n.st.name) }
        }
    }

    func stop() {
        node?.stop()
        node = nil
        window.close()
        showWindow = false
    }

    func unfollow() {
        stop()
        FollowerV2State.clear()
        status.setFollow(nil)
        log("v2: stopped following (keys deleted from the Keychain)")
    }

    var statusLine: String {
        guard let n = node else { return "not following" }
        return "phase \(n.phase) accepted \(n.accepted) rejected \(n.rejected) relays \(n.connectedRelays)/\(n.relayHosts.count) last via \(n.lastVia)"
    }
}

let following = MainActor.assumeIsolated { Following() }
MainActor.assumeIsolated {
    if defaults.object(forKey: "bleV2") == nil || defaults.bool(forKey: "bleV2") {
        let b = BLEV2Central()
        b.onState = { log($0) }
        b.onEvent = { json, via in MainActor.assumeIsolated { following.node?.onEvent(json, via: via) } }
        b.onInfo = { info, phone in
            MainActor.assumeIsolated {
                log("BLE v2: INFO from \(phone): owner \(info.i.prefix(12))…, pairing \(info.token == nil ? "closed" : "OPEN")")
                following.nearby(info, from: phone)
            }
        }
        following.ble = b
    }
    status.onFollow = { following.begin() }
    status.onFollowQR = { following.beginFromQR() }
    status.onUnfollow = { following.unfollow() }
    following.launch()
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
        following.node?.publishTreatment(t.dict)       // v2 writer, only if the wearer allowed this Mac
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
            case "unfollow": following.unfollow()
            case "follow": following.join(o["link"] as? String ?? "", name: o["name"] as? String ?? FollowUI.computerName)
            case "follow-status": log("debug v2 \(following.statusLine)")
            case "v2-treat":       // writer test: publish only, never touches the local log / LibreView uploader
                let t = Treatment(k: o["k"] as? String ?? "note", a: o["a"] as? Double ?? 0,
                                  t: Int64(Date().timeIntervalSince1970), n: o["n"] as? String ?? "")
                log("debug v2-treat \(following.node?.publishTreatment(t.dict) == true ? "sent" : "NOT sent (not a writer)")")
            case "follower-pub": log("debug v2 follower pubkey \(following.node?.followerPub ?? "-")")
            case "readings":
                try? store.csv.write(toFile: o["path"] as? String ?? "/tmp/glucosebar-readings.csv", atomically: true, encoding: .utf8)
                log("debug readings written")
            case "dump":
                for t in treatments.rows { log("debug row \(t.dict)") }
                log("debug \(uploader.statusLine)")
            default: break
            }
        }
    }
}

status.onRefresh = { MainActor.assumeIsolated {
    if lanEnabled { feed.restart("manual refresh") }
    nostr?.restart("manual refresh")
    following.node?.restart("manual refresh")
} }

// After sleep the SSE/WebSocket sockets are dead but URLSession would only notice after its idle timeout.
NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
    MainActor.assumeIsolated {
        if lanEnabled { feed.restart("system woke") }
        nostr?.restart("system woke")
        following.node?.restart("system woke")
    }
}

app.run()
