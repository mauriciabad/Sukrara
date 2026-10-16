// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreBluetooth
import Foundation

/// Receives glucose from the GlucoBridge Android companion over BLE — no shared network needed
/// (Mac offline, or Mac on Wi-Fi while the phone is on mobile data).
/// Protocol: see spikes/glucobridge-android/.../Bridge.kt
///   READING (read, notify): u32 epoch s ‖ u16 mg/dL ‖ i16 rate×100 (0x8000 = unknown)
///   HISTORY (read):         u32 epoch s of newest ‖ u16 mg/dL per minute, newest first (0 = gap)
///   TREATMENTS (write, notify): one JSON message each way, see spikes/libreview/SPEC.md §2 (logic in TreatmentSync)
final class BLEBridgeSource: NSObject, BLEService {
    static let service = CBUUID(string: "6c1f0001-5b8a-4d8e-9a3c-676c75636f42")
    static let reading = CBUUID(string: "6c1f0002-5b8a-4d8e-9a3c-676c75636f42")
    static let history = CBUUID(string: "6c1f0003-5b8a-4d8e-9a3c-676c75636f42")
    static let treatments = CBUUID(string: "6c1f0004-5b8a-4d8e-9a3c-676c75636f42")

    var onReading: ((Reading) -> Void)?
    var onHistory: (([Reading]) -> Void)?
    var onState: ((String) -> Void)?
    /// Treatment sync over the same link; nil = glucose only.
    var treatmentSync: TreatmentSync?

    // BLEService: the connection belongs to BLELink (one link per phone, shared with the v2 path). The phone pushes
    // a reading every minute; BLELink drops a link that goes silent (CoreBluetooth sometimes keeps a dead link
    // `.connected`, observed 2026-10-15).
    let uuid = BLEBridgeSource.service
    let characteristics = [BLEBridgeSource.reading, BLEBridgeSource.history, BLEBridgeSource.treatments]
    let name = "legacy"
    let silenceLimit: TimeInterval = 150
    private var peripheral: CBPeripheral?
    /// Name of the phone while the link is up (the legacy service carries no identity).
    var connectedName: String? { peripheral.map { $0.name ?? "phone" } }

    init(link: BLELink) {
        super.init()
        link.register(self)
    }

    func attach(_ p: CBPeripheral, _ s: CBService) {
        peripheral = p
        onState?("BLE legacy: connected to \(p.name ?? "phone")")
        for ch in s.characteristics ?? [] {
            if ch.uuid == Self.history { p.readValue(for: ch) }          // long read (≤ 364 B) handled by CoreBluetooth
            if ch.uuid == Self.reading { p.setNotifyValue(true, for: ch); p.readValue(for: ch) }
            if ch.uuid == Self.treatments, treatmentSync != nil { p.setNotifyValue(true, for: ch) }
        }
    }

    func detach() {
        peripheral = nil
        MainActor.assumeIsolated { self.treatmentSync?.disconnected() }
    }

    func silent() {
        guard let p = peripheral, let ch = p.services?.first(where: { $0.uuid == Self.service })?
            .characteristics?.first(where: { $0.uuid == Self.reading }) else { return }
        p.readValue(for: ch)          // the reading notification stopped while the link lives: ask directly
    }

    /// Treatment sync starts once its notifications are on, so no reply to our `pull` can be missed.
    func notifying(_ ch: CBCharacteristic, error: Error?) {
        guard ch.uuid == Self.treatments, ch.isNotifying, error == nil, let p = peripheral else { return }
        let max = p.maximumWriteValueLength(for: .withResponse)
        MainActor.assumeIsolated {
            self.treatmentSync?.connected(maxWrite: max) { [weak p] data in
                guard let p, p.state == .connected else { return false }
                p.writeValue(data, for: ch, type: .withResponse)
                return true
            }
        }
    }

    func wrote(_ ch: CBCharacteristic, error: Error?) {
        guard ch.uuid == Self.treatments else { return }
        MainActor.assumeIsolated { self.treatmentSync?.writeCompleted(error: error) }
    }

    func received(_ ch: CBCharacteristic, _ p: CBPeripheral) {
        guard let value = ch.value else { return }
        if ch.uuid == Self.treatments { return MainActor.assumeIsolated { self.treatmentSync?.received(value) } }
        let d = [UInt8](value)
        func u32(_ o: Int) -> UInt32 { UInt32(d[o]) | UInt32(d[o + 1]) << 8 | UInt32(d[o + 2]) << 16 | UInt32(d[o + 3]) << 24 }
        func u16(_ o: Int) -> UInt16 { UInt16(d[o]) | UInt16(d[o + 1]) << 8 }
        if ch.uuid == Self.reading, d.count >= 8 {
            let raw = Int16(bitPattern: u16(6))
            onReading?(Reading(date: Date(timeIntervalSince1970: TimeInterval(u32(0))), mgdl: Double(u16(4)),
                               rate: raw == .min ? nil : Double(raw) / 100))
        } else if ch.uuid == Self.history, d.count >= 6 {
            let newest = TimeInterval(u32(0))
            let pts = stride(from: 4, to: d.count - 1, by: 2).enumerated().compactMap { i, o -> Reading? in
                let v = u16(o)
                return v == 0 ? nil : Reading(date: Date(timeIntervalSince1970: newest - Double(i) * 60), mgdl: Double(v))
            }
            onHistory?(pts.reversed())
        }
    }
}
