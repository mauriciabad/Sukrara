// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

// Config comes from UserDefaults, so it works both as CLI args (`-url http://...`) and as
// `defaults write app.mauri.sukrara.poc url http://...` for the bundled Sukrara.app (Sukrara for Mac, formerly
// GlucoseBar: Migration.swift copies the old `dev.spike.GlucoseBar` state once).
//   url     http://<phone>:17580, or "auto" (default) to find Juggluco on the local network
//   secret  Juggluco web server API secret, if set
//   unit    mgdl | mmol
//   lan     NO to disable the Juggluco web-server transport
//   nostr   cgmnostr:1?s=…&k=…&r=… follower link → CGM-over-Nostr transport (or `nostr-file <path>` holding it)
//   (protocol v2 follower — the default way to follow; see FOLLOWER.md)
//   bleV2       NO to disable the CGM Share BLE transport (docs/07)
//   followLink / followLinkFile / followName   tests: join an invite at launch without the dialog
//   pairNearby  tests: answer a phone's "Pair nearby" offer with this name, without the dialog
//   keychainService  tests: use a throwaway Keychain item;  v2NoRelays YES: BLE only;  pairSnapshot out.png
let defaults = UserDefaults.standard
setvbuf(stdout, nil, _IOLBF, 0)
/// stdout, and for the bundled app (launched by `open`, no terminal) also ~/Library/Logs/Sukrara.log.
let logFile: FileHandle? = Bundle.main.bundleIdentifier == nil ? nil : {
    let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Sukrara.log")
    if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
    let h = try? FileHandle(forWritingTo: url)
    _ = try? h?.seekToEnd()
    return h
}()
func log(_ s: String) {
    let line = "\(Date().formatted(date: .omitted, time: .standard))  \(s)"
    print(line)
    logFile?.write(Data((line + "\n").utf8))
}
Migration.run()

if let path = defaults.string(forKey: "preview") {
    Preview.render(to: path)
    exit(0)
}
if let path = defaults.string(forKey: "menuPreview") {
    MainActor.assumeIsolated { MenuPreview.render(to: path) }
    exit(0)
}
if CommandLine.arguments.contains("-v2selftest") { exit(MainActor.assumeIsolated { V2SelfTest.run() } ? 0 : 1) }
if CommandLine.arguments.contains("-v2relaytest") { exit(MainActor.assumeIsolated { V2RelayTest.run() } ? 0 : 1) }
if CommandLine.arguments.contains("-qrselftest") { exit(MainActor.assumeIsolated { FollowUI.qrSelfTest() } ? 0 : 1) }
if CommandLine.arguments.contains("-treatmentsselftest") { exit(SelfTest.merge() ? 0 : 1) }
// Spike experiments: `Sukrara -debugsend '{"cmd":"add","k":"rapid","a":0.5,"n":"…"}'` drives the running
// instance (cmd log = as the menu | remove {id} = as the menu | paths | edit {id,a,n} |
// delete {id} | dump), like the phone's DebugReceiver.
let debugNote = Notification.Name("app.mauri.sukrara.poc.debug")
if let i = CommandLine.arguments.firstIndex(of: "-debugsend"), i + 1 < CommandLine.arguments.count {
    let msg = CommandLine.arguments[i + 1]     // not via UserDefaults: it would parse "{…}" as a plist
    DistributedNotificationCenter.default().postNotificationName(debugNote, object: msg, userInfo: nil, deliverImmediately: true)
    exit(0)
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)   // menubar only, no Dock icon

let status = StatusController()
status.unit = GlucoseUnit(rawValue: defaults.string(forKey: "unit") ?? "") ?? .mgdl
let alarms = MainActor.assumeIsolated { Alarms() }
MainActor.assumeIsolated { status.addToggle(alarms.menuItem) }

let configuredURL = defaults.string(forKey: "url").flatMap { $0 == "auto" ? nil : URL(string: $0) }
let secret = defaults.string(forKey: "secret")
let historySpan: TimeInterval = 3 * 3600


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
        let snap = Snapshot(latest: latest, history: history, source: source)
        status.update(snap)
        widgetFeed.update(snap, unit: status.unit, thresholds: status.thresholds, treatments: treatmentStore.rows)
        alarms.onLatest(latest)
    }
}

let store = MainActor.assumeIsolated { Store() }
let widgetFeed = MainActor.assumeIsolated { WidgetFeed() }     // desktop widgets (WidgetFeed.swift)

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

// Bluetooth: protocol v2 over one CoreBluetooth central (BLELink.swift). Created only when enabled:
// CBCentralManager starts scanning as soon as Bluetooth reports powered on.
let bleV2On = defaults.object(forKey: "bleV2") == nil || defaults.bool(forKey: "bleV2")
let bleLink = bleV2On ? BLELink() : nil
bleLink?.onState = { log($0) }

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

// Treatments: entries made here + the wearer's log over v2, read by the menu, the chart and the widgets.
// `treatmentsDir` (tests): keep the log somewhere else than ~/Library/Application Support/Sukrara.
let treatmentStore = MainActor.assumeIsolated {
    defaults.string(forKey: "treatmentsDir").map { TreatmentStore(directory: URL(fileURLWithPath: $0)) } ?? TreatmentStore()
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
            follow(link, name: defaults.string(forKey: "followName") ?? FollowUI.computerName)
        }
        NSAppleEventManager.shared().setEventHandler(self, andSelector: #selector(openURL(_:with:)),
                                                     forEventClass: AEEventClass(kInternetEventClass), andEventID: AEEventID(kAEGetURL))
    }

    /// The follow state appeared after launch (Migration.keychain): start following it.
    func resume() {
        guard node == nil, let st = FollowerV2State.load(), let n = FollowerV2(state: st) else { return }
        log("v2: resuming the migrated follow state")
        attach(n)
        n.start()
        ble?.hello()
    }

    @objc func openURL(_ event: NSAppleEventDescriptor, with reply: NSAppleEventDescriptor) {
        guard let s = event.paramDescriptor(forKeyword: keyDirectObject)?.stringValue else { return }
        log("opened \(s.prefix(16))…")
        // sukrara:join#… (the web page's "Open in Sukrara" button) is the https link
        begin(prefill: V2.isInvite(s) ? V2.inviteBase + (s.split(separator: "#", maxSplits: 1).last.map(String.init) ?? "") : s)
    }

    func begin(prefill: String? = nil) {
        guard let (link, name) = FollowUI.ask(prefill: prefill) else { return }
        follow(link, name: name)
    }

    func beginFromQR() {
        guard let link = FollowUI.linkFromQRImage() else { return FollowUI.error("No Sukrara invite QR code found in that image.") }
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
        let inv = V2.Invite(i: info.i, t: t, x: info.expiry ?? Int(Date().timeIntervalSince1970) + 600, r: info.relays,
                            profile: info.profile)
        let who = (info.profile?["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? phone
        var name = defaults.string(forKey: "pairNearby") ?? ""                   // tests: no dialog
        if name.isEmpty {
            NSApp.activate(ignoringOtherApps: true)
            let a = NSAlert()
            a.messageText = "Follow \(who) nearby?"
            a.icon = Avatar.image(info.profile?["avatar"] as? [String: Any], name: who, size: 64)
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
        join(inv, name: name)
    }

    /// A pasted / opened / test link: look the invite up on the relays first (who is inviting), then ask.
    func follow(_ link: String, name: String) {
        Task { @MainActor in
            switch await V2.resolveInvite(link) {
            case .failure(let why):
                log("v2 join failed: \(why.text)")
                if defaults.string(forKey: "followLink") == nil, defaults.string(forKey: "followLinkFile") == nil { FollowUI.error(why.text) }
            case .success(let inv): join(inv, name: name)
            }
        }
    }

    func join(_ inv: V2.Invite, name: String) {
        switch FollowerV2.join(inv, name: name) {
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
        n.extraPublish = { [weak self, weak n] ev, done in     // only to the phone of the wearer we follow
            if let b = self?.ble, b.ownerI != nil, b.ownerI == n?.st.i { b.sendEvent(ev, done: done) }
        }
        n.onTreatment = { t, fromWearer, via in
            // Accepted = the wearer merged it: its device key republished this id with a version ≥ the one we sent.
            if treatmentStore.receiveV2(t, fromWearer: fromWearer, via: via) {
                log("v2: treatment \(t.id.prefix(8)) accepted by the wearer (via \(via))")
            }
        }
        n.onTreatmentSent = { id, u, via in treatmentStore.setV2(id, u: u, .sent, via: via) }
        n.onNewKey = { [weak self] in self?.ble?.hello() }
        n.onReading = { store.ingest($0, from: $1) }
        n.onHistory = { store.ingest(history: $0, from: $1) }
        n.onPhase = { [weak self, weak n] p in
            guard let self, let n else { return }
            let w = n.wearer, who = w.name.isEmpty ? "someone" : w.name
            let pic = Avatar.image(w.avatar, name: who, size: 18)
            switch p {
            case .waiting(let code): status.setFollow("Following \(who): waiting for approval · code \(code)", image: pic)
            case .active(let e): status.setFollow("Following \(who) · key epoch \(e) · \(n.relayHosts.count) relays", image: pic)
            case .revoked: status.setFollow("⚠︎ \(who) stopped sharing with this Mac", image: pic)
            }
            status.treatmentsChanged()                   // may add entries? (roster writers)
            if self.showWindow { self.window.show(p, name: n.st.name, wearer: n.wearer) }
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

    /// Send an entry made on this Mac over v2 — only when the roster lists this Mac as a writer.
    func sendTreatment(_ t: Treatment) {
        if node?.publishTreatment(t) == true { treatmentStore.setV2(t.id, u: t.u, .sending) }
        else { log("v2: treatment \(t.id.prefix(8)) NOT sent (this Mac is not a writer)") }
    }

    var statusLine: String {
        guard let n = node else { return "not following" }
        return "phase \(n.phase) accepted \(n.accepted) rejected \(n.rejected) relays \(n.connectedRelays)/\(n.relayHosts.count) last via \(n.lastVia)"
    }
}

let following = MainActor.assumeIsolated { Following() }
MainActor.assumeIsolated {
    if bleV2On, let bleLink {
        let b = BLEV2Central(link: bleLink)
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
    Migration.keychain { following.resume() }      // GlucoseBar → Sukrara: follow state from the old Keychain item
}

// Treatments: local log + the wearer's over v2. The phone uploads the merged log to LibreView, Mac entries included.
MainActor.assumeIsolated {
    let treatments = treatmentStore
    treatments.onChange.append { status.treatmentsChanged() }
    treatments.onChange.append { widgetFeed.treatmentsChanged(treatments.rows) }
    status.treatments = { treatments.rows }
    status.treatmentPaths = { TreatmentPaths.current(node: following.node) }
    status.treatmentStatus = { TreatmentPaths.status(of: $0, store: treatments) }
    status.treatmentDetails = { TreatmentPaths.details(of: $0, store: treatments) }
    // One path per entry, the wearer's choice (TreatmentPaths): v2 as a roster writer, else none (the menu item is
    // disabled); local only when following no one.
    @MainActor func paths() -> TreatmentPaths { TreatmentPaths.current(node: following.node) }
    status.onLogTreatment = { t in
        let p = paths()
        if p.canLog { log("treatment logged: \(StatusController.describe(t)) → \(p.route.rawValue)") }
        switch p.route {
        case .v2: treatments.upsertLocal(t); following.sendTreatment(t)
        case .local: treatments.upsertLocal(t)
        case .none: log("treatment NOT logged: \(TreatmentPaths.notAllowed)")
        }
    }
    status.onDeleteTreatment = { id in
        let p = paths()
        switch p.route {
        case .v2: if let t = treatments.delete(id) { following.sendTreatment(t) }
        case .local: treatments.delete(id)
        case .none: log("treatment NOT deleted: \(TreatmentPaths.notAllowed)")
        }
    }
    status.treatmentsChanged()

    DistributedNotificationCenter.default().addObserver(forName: debugNote, object: nil, queue: .main) { n in
        MainActor.assumeIsolated {
            guard let s = n.object as? String,
                  let o = (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any] else { return }
            let id = o["id"] as? String ?? ""
            switch o["cmd"] as? String {
            case "log":        // exactly what Save in "Log treatment…" does (refused like the menu when not allowed)
                let t = Treatment(k: o["k"] as? String ?? "note", a: o["a"] as? Double ?? 0,
                                  t: Int64(Date().timeIntervalSince1970), n: o["n"] as? String ?? "")
                log("debug log \(t.id)")
                status.onLogTreatment?(t)
            case "remove":     // exactly what Delete in the menu does
                status.onDeleteTreatment?(id)
            case "paths":
                let p = paths()
                log("debug paths route=\(p.route.rawValue) canLog=\(p.canLog) | " + p.lines.joined(separator: " | "))
            case "edit":
                guard var t = treatments.row(id) else { return }
                if let a = o["a"] as? Double { t.a = a }
                if let n = o["n"] as? String { t.n = n }
                t.u = max(Treatment.nowMs(), t.u + 1)
                treatments.upsertLocal(t)
            case "delete": treatments.delete(id)
            case "unfollow": following.unfollow()
            case "follow": following.follow(o["link"] as? String ?? "", name: o["name"] as? String ?? FollowUI.computerName)
            case "follow-status": log("debug v2 \(following.statusLine)")
            case "v2-treat":       // writer test: publish only, never touches the local log
                let t = Treatment(k: o["k"] as? String ?? "note", a: o["a"] as? Double ?? 0,
                                  t: Int64(Date().timeIntervalSince1970), n: o["n"] as? String ?? "")
                log("debug v2-treat \(following.node?.publishTreatment(t) == true ? "sent" : "NOT sent (not a writer)")")
            case "follower-pub": log("debug v2 follower pubkey \(following.node?.followerPub ?? "-")")
            case "readings":
                try? store.csv.write(toFile: o["path"] as? String ?? "/tmp/sukrara-readings.csv", atomically: true, encoding: .utf8)
                log("debug readings written")
            case "dump":
                for t in treatments.rows {
                    log("debug row \(t.dict) status=\(TreatmentPaths.status(of: t, store: treatments))")
                }
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
