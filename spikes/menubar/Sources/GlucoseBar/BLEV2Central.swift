// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreBluetooth
import Foundation

/// Protocol v2 over Bluetooth (docs/07-bluetooth-followers.md): the CGM Share owner phone is a GATT server; this Mac
/// is a central. No BLE pairing/bonding — every event goes through the same `FollowerV2.onEvent(_:via:)` acceptance
/// rules as relay events (seal signer = I, roster devices, known gk, valid signature).
///   frame = [msgId u16][idx u16][count u16][payload]  (big endian); message = JSON array
///   follower → owner: ["HELLO",{"v":2}], ["EVENT",<event>]   owner → follower: ["INFO",{…}], ["EVENT",<event>]
final class BLEV2Central: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007")
    static let rx = CBUUID(string: "7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007")
    static let tx = CBUUID(string: "7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007")

    struct Info { let i: String; let relays: [String]; let token: String?; let expiry: Int? }

    var onEvent: ((String, String) -> Void)?        // (event json, via "ble:<name>")
    var onInfo: ((Info, String) -> Void)?           // (info, peripheral name)
    var onState: ((String) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var parts: [UInt16: [Data?]] = [:]
    private var outbox: [Data] = []
    private var writing = false
    private var msgId: UInt16 = 0
    private var lastData = Date()
    private var watchdog: Timer?
    private(set) var connectedName: String?
    /// Identity key (hex) of the connected phone's wearer, from INFO.
    private(set) var ownerI: String?
    private var name: String { peripheral?.name ?? "phone" }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        // CoreBluetooth sometimes keeps a dead link "connected"; the owner pushes a live event every minute.
        watchdog = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            guard let self, !paused, let p = peripheral, Date().timeIntervalSince(lastData) > 180 else { return }
            onState?("BLE v2: silent \(Int(Date().timeIntervalSince(lastData))) s, reconnecting")
            central.cancelPeripheralConnection(p)
            reset()
            scan()
        }
    }

    /// Drop the link and look again later (e.g. a phone that isn't offering pairing while we follow nobody,
    /// or another wearer's phone) — keeps both radios idle instead of streaming events we would discard.
    func disconnect(retryAfter s: TimeInterval) {
        guard let p = peripheral else { return }
        onState?("BLE v2: leaving \(p.name ?? "phone") for \(Int(s)) s")
        let old = p
        reset()
        peripheral = old            // keep the slot taken so didDisconnect's retry doesn't rescan immediately
        paused = true
        central.cancelPeripheralConnection(old)
        DispatchQueue.main.asyncAfter(deadline: .now() + s) { [weak self] in
            guard let self else { return }
            paused = false
            reset()
            scan()
        }
    }
    private var paused = false

    private func reset() {
        peripheral = nil; rxChar = nil; parts = [:]; outbox = []; writing = false; connectedName = nil; ownerI = nil
        lastData = Date()
    }

    private func scan() {
        guard central.state == .poweredOn else { return }
        onState?("BLE v2: scanning for a CGM Share phone")
        central.scanForPeripherals(withServices: [Self.service])
    }

    // MARK: sending

    /// Queue a message; frames are written one at a time (next after the write response).
    func send(_ msg: [Any]) {
        guard let p = peripheral, rxChar != nil,
              let payload = try? JSONSerialization.data(withJSONObject: msg, options: [.withoutEscapingSlashes]) else { return }
        msgId &+= 1
        let per = max(20, p.maximumWriteValueLength(for: .withResponse) - 6)
        let count = max(1, (payload.count + per - 1) / per)
        for i in 0..<count {
            var f = Data()
            for v in [msgId, UInt16(i), UInt16(count)] { f.append(UInt8(v >> 8)); f.append(UInt8(v & 0xff)) }
            f.append(payload.subdata(in: payload.startIndex + i * per..<min(payload.endIndex, payload.startIndex + (i + 1) * per)))
            outbox.append(f)
        }
        pump()
    }

    func sendEvent(_ json: String) {
        guard let o = try? JSONSerialization.jsonObject(with: Data(json.utf8)) else { return }
        send(["EVENT", o])
    }

    func hello() { send(["HELLO", ["v": 2]]) }

    private func pump() {
        guard !writing, !outbox.isEmpty, let p = peripheral, let c = rxChar else { return }
        writing = true
        p.writeValue(outbox.removeFirst(), for: c, type: .withResponse)
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        if let error { onState?("BLE v2: write failed: \(error.localizedDescription)") }
        writing = false
        pump()
    }

    // MARK: receiving

    private func frame(_ d: Data) {
        guard d.count >= 6 else { return }
        let b = [UInt8](d)
        let mid = UInt16(b[0]) << 8 | UInt16(b[1]), idx = Int(UInt16(b[2]) << 8 | UInt16(b[3])),
            count = Int(UInt16(b[4]) << 8 | UInt16(b[5]))
        guard count > 0, idx < count else { return }
        if idx == 0 || parts[mid]?.count != count { parts[mid] = Array(repeating: nil, count: count) }
        parts[mid]![idx] = Data(b[6...])
        guard let all = parts[mid], all.allSatisfy({ $0 != nil }) else { return }
        parts[mid] = nil
        let payload = all.compactMap { $0 }.reduce(Data(), +)
        guard let msg = try? JSONSerialization.jsonObject(with: payload) as? [Any], let type = msg.first as? String,
              msg.count > 1 else { return }
        switch type {
        case "INFO":
            guard let o = msg[1] as? [String: Any], let i = o["i"] as? String else { return }
            let pair = o["pair"] as? [String: Any]
            ownerI = i
            onInfo?(Info(i: i, relays: o["relays"] as? [String] ?? [], token: pair?["t"] as? String,
                         expiry: (pair?["x"] as? NSNumber)?.intValue), name)
        case "EVENT":
            guard let ev = msg[1] as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: ev) else { return }
            onEvent?(String(decoding: data, as: UTF8.self), "ble:\(name)")
        default: break
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Self.tx, let d = c.value else { return }
        lastData = Date()
        frame(d)
    }

    // MARK: connection

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn { scan() } else { onState?("BLE v2: Bluetooth state \(c.state.rawValue)") }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard peripheral == nil else { return }
        c.stopScan()
        peripheral = p
        p.delegate = self
        onState?("BLE v2: connecting to \(p.name ?? "?") (rssi \(rssi))")
        c.connect(p)
        // connect() never times out on its own; observed stuck "connecting" with a second central process.
        DispatchQueue.main.asyncAfter(deadline: .now() + 25) { [weak self] in
            guard let self, !paused, peripheral === p, connectedName == nil else { return }
            c.cancelPeripheralConnection(p)
            retry("connect timed out")
        }
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        lastData = Date()
        p.discoverServices([Self.service])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        retry("connect failed: \(error?.localizedDescription ?? "?")")
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        retry("disconnected \(error?.localizedDescription ?? "")")
    }

    private func retry(_ why: String) {
        if paused { return }
        onState?("BLE v2: \(why)")
        reset()
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in self?.scan() }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == Self.service }) else { return retry("service not found") }
        p.discoverCharacteristics([Self.rx, Self.tx], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        rxChar = s.characteristics?.first { $0.uuid == Self.rx }
        guard let tx = s.characteristics?.first(where: { $0.uuid == Self.tx }), rxChar != nil else {
            return retry("characteristics not found")
        }
        p.setNotifyValue(true, for: tx)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == Self.tx, c.isNotifying else { return }
        connectedName = p.name ?? "phone"
        onState?("BLE v2: connected to \(connectedName!), MTU write \(p.maximumWriteValueLength(for: .withResponse)) B")
        hello()
    }
}
