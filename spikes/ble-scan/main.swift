// Spike: scan BLE advertisements and print anything that looks like an Abbott/Libre device.
// Run: swiftc -O main.swift -o blescan && ./blescan [seconds] [--all]
import CoreBluetooth
import Foundation

let seconds = Double(CommandLine.arguments.dropFirst().first(where: { Double($0) != nil }) ?? "20")!
let showAll = CommandLine.arguments.contains("--all")

final class Scanner: NSObject, CBCentralManagerDelegate {
    var central: CBCentralManager!
    var seen = Set<UUID>()
    override init() { super.init(); central = CBCentralManager(delegate: self, queue: .main) }

    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        print("state:", c.state.rawValue, "(5 = poweredOn)")
        if c.state == .poweredOn {
            c.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
            print("scanning \(Int(seconds))s...")
        }
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData ad: [String: Any], rssi: NSNumber) {
        guard seen.insert(p.identifier).inserted else { return }
        let name = (ad[CBAdvertisementDataLocalNameKey] as? String) ?? p.name ?? "-"
        let services = (ad[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID])?.map(\.uuidString) ?? []
        let mfg = (ad[CBAdvertisementDataManufacturerDataKey] as? Data).map { $0.map { String(format: "%02x", $0) }.joined() } ?? ""
        // Abbott BT SIG company id 0x0236 (little-endian "3602"), member service 0xFDE3; Libre 3 uses 0898xxxx custom UUIDs
        let libreish = name.hasPrefix("ABBOTT") || services.contains { $0 == "FDE3" || $0.hasPrefix("0898") } || mfg.hasPrefix("3602")
        if libreish || showAll {
            print(libreish ? "*** LIBRE?" : "   ", name, p.identifier, "rssi", rssi, "svc", services, "mfg", mfg)
        }
    }
}

let s = Scanner()
DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { print("done, \(s.seen.count) devices seen"); exit(0) }
RunLoop.main.run()
