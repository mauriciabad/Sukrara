import CoreBluetooth
import Foundation

/// Receives glucose from the GlucoBridge Android companion over BLE — no shared network needed
/// (Mac offline, or Mac on Wi-Fi while the phone is on mobile data).
/// Protocol: see spikes/glucobridge-android/.../Bridge.kt
///   READING (read, notify): u32 epoch s ‖ u16 mg/dL ‖ i16 rate×100 (0x8000 = unknown)
///   HISTORY (read):         u32 epoch s of newest ‖ u16 mg/dL per minute, newest first (0 = gap)
final class BLEBridgeSource: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    static let service = CBUUID(string: "6c1f0001-5b8a-4d8e-9a3c-676c75636f42")
    static let reading = CBUUID(string: "6c1f0002-5b8a-4d8e-9a3c-676c75636f42")
    static let history = CBUUID(string: "6c1f0003-5b8a-4d8e-9a3c-676c75636f42")

    var onReading: ((Reading) -> Void)?
    var onHistory: (([Reading]) -> Void)?
    var onState: ((String) -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    private func scan() {
        guard central.state == .poweredOn else { return }
        onState?("BLE: scanning for GlucoBridge")
        central.scanForPeripherals(withServices: [Self.service])
    }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        if c.state == .poweredOn { scan() } else { onState?("BLE: Bluetooth state \(c.state.rawValue)") }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        guard peripheral == nil else { return }
        c.stopScan()
        peripheral = p
        p.delegate = self
        onState?("BLE: connecting (rssi \(rssi))")
        c.connect(p)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        onState?("BLE: connected")
        p.discoverServices([Self.service])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        retry("connect failed: \(error?.localizedDescription ?? "?")")
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        retry("disconnected \(error?.localizedDescription ?? "")")
    }

    private func retry(_ why: String) {
        onState?("BLE: \(why)")
        peripheral = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.scan() }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == Self.service }) else { return drop(p, "service missing") }
        p.discoverCharacteristics([Self.reading, Self.history], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        for ch in s.characteristics ?? [] {
            if ch.uuid == Self.history { p.readValue(for: ch) }          // long read (≤ 364 B) handled by CoreBluetooth
            if ch.uuid == Self.reading { p.setNotifyValue(true, for: ch); p.readValue(for: ch) }
        }
    }

    private func drop(_ p: CBPeripheral, _ why: String) { central.cancelPeripheralConnection(p); onState?("BLE: \(why)") }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        guard error == nil, let d = ch.value.map({ [UInt8]($0) }) else { return }
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
