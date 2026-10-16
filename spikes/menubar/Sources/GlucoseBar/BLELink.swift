// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreBluetooth
import Foundation

/// One Bluetooth path on top of `BLELink`: a GATT service on the phone and the protocol spoken over it
/// (`BLEV2Central` = protocol v2 `7c6a0001-…`).
protocol BLEService: AnyObject {
    var uuid: CBUUID { get }
    var characteristics: [CBUUID] { get }
    var name: String { get }                       // for logs: "v2"
    /// No notification for this long while attached → `silent()` (the link itself is dropped if *nothing* arrives).
    var silenceLimit: TimeInterval { get }
    /// Characteristics discovered on a connected phone: subscribe, start the protocol.
    func attach(_ p: CBPeripheral, _ s: CBService)
    /// The link is gone (or this path was released): forget the peripheral, keep queued state.
    func detach()
    func received(_ c: CBCharacteristic, _ p: CBPeripheral)
    func wrote(_ c: CBCharacteristic, error: Error?)
    func notifying(_ c: CBCharacteristic, error: Error?)
    /// Attached but silent for `silenceLimit` while the link still carries other data.
    func silent()
}

/// The Mac's CoreBluetooth central for its Bluetooth path(s), today only protocol v2:
///   - scan for the services of every path that has no link yet; connects are serialised (one in flight, 25 s timeout,
///     an identifier that timed out is skipped for 60 s);
///   - after connecting, discover the registered services and attach each waiting path that the phone serves;
///   - a path that still waits first looks at the links we already hold (and at peripherals the system has already
///     connected, `retrieveConnectedPeripherals`) before scanning, so it never opens a second link to the same phone;
///   - a path can be released for a while (v2: another wearer's phone, or no pairing offered); the link is closed
///     when no path is attached;
///   - watchdog: a link with no notification at all for 150 s is dropped (CoreBluetooth sometimes keeps a dead link
///     "connected"); a single silent path on a live link is only asked to restart itself.
final class BLELink: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private final class Link {
        let p: CBPeripheral
        var attached: [ObjectIdentifier: BLEService] = [:]
        var lastData = Date()
        var lastRx: [ObjectIdentifier: Date] = [:]
        var lastRediscover = Date.distantPast
        var connected = false
        init(_ p: CBPeripheral) { self.p = p }
    }

    var onState: ((String) -> Void)?
    private var central: CBCentralManager!
    private var services: [BLEService] = []
    private var links: [UUID: Link] = [:]
    private var connecting: UUID?
    private var avoid: [UUID: Date] = [:]
    private var released: [ObjectIdentifier: Date] = [:]
    private var watchdog: Timer?
    private var scanning = false

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        watchdog = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.checkSilence() }
    }

    func register(_ s: BLEService) {
        services.append(s)
        scan()
    }

    /// The phone this path is attached to, if any.
    func peripheral(of s: BLEService) -> CBPeripheral? {
        links.values.first { $0.attached[ObjectIdentifier(s)] != nil }?.p
    }

    /// Detach `s` for `seconds` (its notifications are switched off by its own `detach`). The link stays up while
    /// another path uses it.
    func release(_ s: BLEService, for seconds: TimeInterval) {
        let k = ObjectIdentifier(s)
        released[k] = Date().addingTimeInterval(seconds)
        if let l = links.values.first(where: { $0.attached[k] != nil }) {
            l.attached[k] = nil
            s.detach()
            if l.attached.isEmpty { central.cancelPeripheralConnection(l.p) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds + 0.1) { [weak self] in self?.scan() }
    }

    // MARK: finding phones

    private func waiting() -> [BLEService] {
        let now = Date()
        return services.filter { s in
            let k = ObjectIdentifier(s)
            return (released[k] ?? .distantPast) <= now && !links.values.contains { $0.attached[k] != nil }
        }
    }

    private func scan() {
        guard central.state == .poweredOn else { return }
        let want = waiting()
        if want.isEmpty || connecting != nil { return stopScan() }
        let uuids = want.map(\.uuid)
        // 1. A phone we're already connected to may serve the waiting path too (the same phone, other service).
        for l in links.values where l.connected {
            if attachWaiting(on: l) { return scan() }
            if Date().timeIntervalSince(l.lastRediscover) > 60 {     // services added since (e.g. a feature toggled on)
                l.lastRediscover = Date()
                l.p.discoverServices(services.map(\.uuid))
            }
        }
        // 2. A phone the system already holds a link to (another app, a previous run): reuse that link.
        let now = Date()
        if let p = central.retrieveConnectedPeripherals(withServices: uuids)
            .first(where: { links[$0.identifier] == nil && (avoid[$0.identifier] ?? .distantPast) < now }) {
            onState?("BLE: \(p.name ?? "phone") is already connected to this Mac — using that link")
            return connect(p, rssi: nil)
        }
        // 3. Scan by service UUID (never by stored address: Android rotates its private address).
        if !scanning { onState?("BLE: scanning for \(want.map(\.name).joined(separator: " + "))") }
        scanning = true
        central.scanForPeripherals(withServices: uuids)
    }

    private func stopScan() {
        if scanning { central.stopScan() }
        scanning = false
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn { scan() } else { onState?("BLE: Bluetooth state \(c.state.rawValue)") }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard connecting == nil, links[p.identifier] == nil, (avoid[p.identifier] ?? .distantPast) < Date() else { return }
        connect(p, rssi: rssi)
    }

    private func connect(_ p: CBPeripheral, rssi: NSNumber?) {
        stopScan()
        let l = Link(p)
        links[p.identifier] = l
        connecting = p.identifier
        p.delegate = self
        onState?("BLE: connecting to \(p.name ?? "?")\(rssi.map { " (rssi \($0))" } ?? "")")
        central.connect(p)
        // connect() never times out on its own; observed stuck "connecting" with a second link to the same phone.
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self, weak l] in
            guard let self, let l, links[p.identifier] === l, !l.connected else { return }
            avoid[p.identifier] = Date().addingTimeInterval(60)
            central.cancelPeripheralConnection(p)
            drop(p, "connect timed out (skipping this address for 60 s)")
        }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        guard let l = links[p.identifier] else { return c.cancelPeripheralConnection(p) }
        l.connected = true
        l.lastData = Date()
        l.lastRediscover = Date()
        if connecting == p.identifier { connecting = nil }
        p.discoverServices(services.map(\.uuid))
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        drop(p, "connect failed: \(error?.localizedDescription ?? "?")")
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        drop(p, "disconnected from \(p.name ?? "phone") \(error?.localizedDescription ?? "")")
    }

    private func drop(_ p: CBPeripheral, _ why: String) {
        if connecting == p.identifier { connecting = nil }
        guard let l = links.removeValue(forKey: p.identifier) else { return }
        onState?("BLE: \(why)")
        for s in l.attached.values { s.detach() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.scan() }
    }

    // MARK: services

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let l = links[p.identifier] else { return }
        let found = (p.services ?? []).map(\.uuid)
        if !attachWaiting(on: l), l.attached.isEmpty {
            onState?("BLE: \(p.name ?? "phone") serves nothing we wait for (\(found.count) services) — leaving it for 60 s")
            avoid[p.identifier] = Date().addingTimeInterval(60)
            central.cancelPeripheralConnection(p)
        }
        scan()
    }

    /// Discover characteristics for every waiting path whose service this phone serves. True if any.
    @discardableResult
    private func attachWaiting(on l: Link) -> Bool {
        var any = false
        for s in waiting() {
            guard let svc = l.p.services?.first(where: { $0.uuid == s.uuid }) else { continue }
            l.attached[ObjectIdentifier(s)] = s          // reserved; attach() once the characteristics are known
            l.lastRx[ObjectIdentifier(s)] = Date()
            l.p.discoverCharacteristics(s.characteristics, for: svc)
            any = true
        }
        return any
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor svc: CBService, error: Error?) {
        guard let s = service(p, svc.uuid) else { return }
        onState?("BLE: \(s.name) path on \(p.name ?? "phone")")
        s.attach(p, svc)
    }

    /// The phone's GATT database changed (a path switched on/off there): detach what vanished, look again.
    func peripheral(_ p: CBPeripheral, didModifyServices invalidated: [CBService]) {
        guard let l = links[p.identifier] else { return }
        for svc in invalidated {
            guard let s = service(p, svc.uuid) else { continue }
            l.attached[ObjectIdentifier(s)] = nil
            s.detach()
            onState?("BLE: \(s.name) service changed on \(p.name ?? "phone")")
        }
        l.lastRediscover = Date()
        p.discoverServices(services.map(\.uuid))
    }

    private func service(_ p: CBPeripheral, _ uuid: CBUUID?) -> BLEService? {
        links[p.identifier]?.attached.values.first { $0.uuid == uuid }
    }

    // MARK: data, routed to the path that owns the characteristic's service

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard error == nil, let l = links[p.identifier], let s = service(p, c.service?.uuid) else { return }
        l.lastData = Date()
        l.lastRx[ObjectIdentifier(s)] = Date()
        s.received(c, p)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        service(p, c.service?.uuid)?.wrote(c, error: error)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        service(p, c.service?.uuid)?.notifying(c, error: error)
    }

    private func checkSilence() {
        let now = Date()
        for l in links.values where l.connected {
            let limit = l.attached.values.map(\.silenceLimit).min() ?? 150
            if now.timeIntervalSince(l.lastData) > limit {
                onState?("BLE: no data from \(l.p.name ?? "phone") for \(Int(now.timeIntervalSince(l.lastData))) s, reconnecting")
                central.cancelPeripheralConnection(l.p)
                drop(l.p, "dropped a silent link")
                continue
            }
            for (k, s) in l.attached where now.timeIntervalSince(l.lastRx[k] ?? now) > s.silenceLimit {
                l.lastRx[k] = now
                s.silent()
            }
        }
    }
}
