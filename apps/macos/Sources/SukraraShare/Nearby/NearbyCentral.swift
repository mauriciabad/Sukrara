// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
@preconcurrency import CoreBluetooth
import Foundation
import SukraraCore
import os

/// What the Bluetooth path reports.
public enum NearbyEvent: Equatable, Sendable {
    case event(SignedEvent)
    case info(BleInfo, BleInfo.Owner)
    case state(NearbyState)
}

public enum NearbyState: Equatable, Sendable {
    /// Bluetooth is off (the person can turn it on; following continues over the internet).
    case off
    /// The app may not use Bluetooth (System Settings › Privacy & Security › Bluetooth).
    case unauthorized
    case searching
    case connected(phone: String)
}

/// The Bluetooth path to the wearer's phone (`NearbyCentral`; tests use a fake). Single consumer of `events`.
public protocol NearbyTransport: Sendable {
    var events: AsyncStream<NearbyEvent> { get }
    func start() async
    /// Terminal: disconnects and ends `events`.
    func stop() async
    /// The follower state changed (joined, first key): HELLO proofs and INFO checks use it.
    func update(identity: NearbyLink.Identity) async
    /// Writes an event to the wearer's phone; false when there is no verified phone or the write failed.
    func send(_ event: SignedEvent) async -> Bool
    /// Leaves the current phone for `seconds` (another wearer's phone, or nothing to pair with).
    func leave(for seconds: Double) async
}

/// CoreBluetooth central for the share service (B§2). The actor runs on the serial queue CoreBluetooth calls back on
/// (custom executor), so delegate callbacks enter it synchronously with `assumeIsolated` and CB objects never cross
/// threads. Scans by service UUID (Android rotates its address), one connect at a time with a 25 s timeout (that
/// phone is skipped for 60 s), HELLO again after 180 s without a notification, link dropped after 300 s, rescan 3 s
/// after a disconnect, Bluetooth off → `.off` and rescan when it comes back. The protocol itself is `NearbyLink`.
public actor NearbyCentral: NearbyTransport {
    public nonisolated let events: AsyncStream<NearbyEvent>
    private let continuation: AsyncStream<NearbyEvent>.Continuation
    private let queue: DispatchSerialQueue
    public nonisolated var unownedExecutor: UnownedSerialExecutor { queue.asUnownedSerialExecutor() }

    private static let log = Logger(subsystem: SukraraIDs.appBundleID, category: "nearby")
    private static let service = CBUUID(string: BleUUIDs.service)
    private static let rx = CBUUID(string: BleUUIDs.rx)
    private static let tx = CBUUID(string: BleUUIDs.tx)
    static let connectTimeout: Double = 25
    static let skipAfterTimeout: Double = 60
    static let silenceHello: TimeInterval = 180
    static let silenceDrop: TimeInterval = 300

    private var link: NearbyLink
    private var manager: CBCentralManager?
    private var proxy: Proxy?
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private var skip: [UUID: Date] = [:]
    private var lastData = Date()
    private var lastHello = Date()
    private var nextToken = 0
    private var sends: [Int: CheckedContinuation<Bool, Never>] = [:]
    private var watchdog: Task<Void, Never>?
    private var stopped = false

    public init(identity: NearbyLink.Identity, crypto: any NostrCrypto) {
        queue = DispatchSerialQueue(label: "app.mauri.sukrara.nearby")
        link = NearbyLink(identity: identity, crypto: crypto)
        (events, continuation) = AsyncStream.makeStream(bufferingPolicy: .unbounded)
    }

    public func start() {
        guard manager == nil, !stopped else { return }
        let proxy = Proxy(owner: self)
        self.proxy = proxy
        manager = CBCentralManager(delegate: proxy, queue: queue)
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                await self?.checkSilence()
            }
        }
    }

    public func stop() {
        stopped = true
        watchdog?.cancel()
        if let peripheral { manager?.cancelPeripheralConnection(peripheral) }
        manager?.stopScan()
        apply(link.disconnected())
        manager = nil
        proxy = nil
        continuation.finish()
    }

    public func update(identity: NearbyLink.Identity) { link.identity = identity }

    public func send(_ event: SignedEvent) async -> Bool {
        nextToken += 1
        let token = nextToken
        return await withCheckedContinuation { continuation in
            sends[token] = continuation
            apply(link.send(event, token: token))
        }
    }

    public func leave(for seconds: Double) {
        guard let peripheral else { return }
        skip[peripheral.identifier] = Date().addingTimeInterval(seconds)
        manager?.cancelPeripheralConnection(peripheral)
    }

    // MARK: - CoreBluetooth (entered on `queue` through `Proxy`)

    fileprivate func stateChanged(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: scan()
        case .unauthorized: continuation.yield(.state(.unauthorized))
        default:
            drop("bluetooth \(central.state.rawValue)")
            continuation.yield(.state(.off))
        }
    }

    fileprivate func discovered(_ found: CBPeripheral) {
        guard peripheral == nil, (skip[found.identifier] ?? .distantPast) < Date() else { return }
        manager?.stopScan()
        peripheral = found
        found.delegate = proxy
        manager?.connect(found)
        let id = found.identifier
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.connectTimeout))
            await self?.connectTimedOut(id)
        }
    }

    fileprivate func connected(_ connected: CBPeripheral) {
        guard connected === peripheral else { return }
        lastData = Date()
        connected.discoverServices([Self.service])
    }

    fileprivate func disconnected(_ gone: CBPeripheral) {
        guard gone === peripheral else { return }
        drop("disconnected")
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            await self?.scan()
        }
    }

    fileprivate func discoveredServices(_ p: CBPeripheral) {
        guard let service = p.services?.first(where: { $0.uuid == Self.service }) else {
            return leave(for: Self.skipAfterTimeout)
        }
        p.discoverCharacteristics([Self.rx, Self.tx], for: service)
    }

    fileprivate func discoveredCharacteristics(_ p: CBPeripheral, _ service: CBService) {
        rxCharacteristic = service.characteristics?.first { $0.uuid == Self.rx }
        guard rxCharacteristic != nil, let tx = service.characteristics?.first(where: { $0.uuid == Self.tx }) else {
            return leave(for: Self.skipAfterTimeout)
        }
        p.setNotifyValue(true, for: tx)
    }

    fileprivate func notifying(_ p: CBPeripheral, _ c: CBCharacteristic) {
        guard c.uuid == Self.tx, c.isNotifying else { return }
        continuation.yield(.state(.connected(phone: p.name ?? "phone")))
        lastHello = Date()
        apply(link.connected(perFrame: p.maximumWriteValueLength(for: .withResponse) - BleFraming.header, nonce: Self.nonce()))
    }

    fileprivate func received(_ c: CBCharacteristic) {
        guard c.uuid == Self.tx, let value = c.value else { return }
        lastData = Date()
        apply(link.received(value, now: Date()))
    }

    fileprivate func wrote(error: (any Error)?) {
        apply(link.wrote(ok: error == nil))
    }

    // MARK: - helpers

    private func scan() {
        guard let manager, manager.state == .poweredOn, peripheral == nil, !stopped else { return }
        if let known = manager.retrieveConnectedPeripherals(withServices: [Self.service])
            .first(where: { (skip[$0.identifier] ?? .distantPast) < Date() })
        {
            return discovered(known)
        }
        continuation.yield(.state(.searching))
        manager.scanForPeripherals(withServices: [Self.service])
    }

    private func connectTimedOut(_ id: UUID) {
        guard let peripheral, peripheral.identifier == id, !link.isConnected else { return }
        Self.log.info("nearby connect timed out; skipping that phone for 60 s")
        leave(for: Self.skipAfterTimeout)
        disconnected(peripheral)
    }

    private func checkSilence() {
        guard link.isConnected else { return }
        let silent = Date().timeIntervalSince(lastData)
        if silent > Self.silenceDrop, let peripheral {
            manager?.cancelPeripheralConnection(peripheral)
        } else if silent > Self.silenceHello, Date().timeIntervalSince(lastHello) > Self.silenceHello {
            lastHello = Date()
            apply(link.hello(nonce: Self.nonce()))
        }
    }

    private func drop(_ why: String) {
        Self.log.info("nearby link dropped: \(why, privacy: .public)")
        peripheral = nil
        rxCharacteristic = nil
        apply(link.disconnected())
    }

    private func apply(_ effects: [NearbyLink.Effect]) {
        for effect in effects {
            switch effect {
            case .write(let frame):
                guard let peripheral, let rxCharacteristic else { continue }
                peripheral.writeValue(frame, for: rxCharacteristic, type: .withResponse)
            case .event(let event): continuation.yield(.event(event))
            case .info(let info, let owner): continuation.yield(.info(info, owner))
            case .sent(let token, let ok): sends.removeValue(forKey: token)?.resume(returning: ok)
            }
        }
    }

    private static func nonce() -> String { Hex.encode((0..<16).map { _ in UInt8.random(in: 0...255) }) }

    /// The CoreBluetooth delegate. Callbacks arrive on the actor's own queue, so `assumeIsolated` holds.
    private final class Proxy: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
        private weak var owner: NearbyCentral?

        init(owner: NearbyCentral) { self.owner = owner }

        func centralManagerDidUpdateState(_ central: CBCentralManager) {
            owner?.assumeIsolated { $0.stateChanged(central) }
        }

        func centralManager(
            _ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any],
            rssi RSSI: NSNumber
        ) {
            owner?.assumeIsolated { $0.discovered(peripheral) }
        }

        func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
            owner?.assumeIsolated { $0.connected(peripheral) }
        }

        func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: (any Error)?)
        {
            owner?.assumeIsolated { $0.disconnected(peripheral) }
        }

        func centralManager(
            _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: (any Error)?
        ) {
            owner?.assumeIsolated { $0.disconnected(peripheral) }
        }

        func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: (any Error)?) {
            owner?.assumeIsolated { $0.discoveredServices(peripheral) }
        }

        func peripheral(
            _ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: (any Error)?
        ) {
            owner?.assumeIsolated { $0.discoveredCharacteristics(peripheral, service) }
        }

        func peripheral(
            _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
            error: (any Error)?
        ) {
            owner?.assumeIsolated { $0.notifying(peripheral, characteristic) }
        }

        func peripheral(
            _ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: (any Error)?
        ) {
            owner?.assumeIsolated { $0.received(characteristic) }
        }

        func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: (any Error)?)
        {
            owner?.assumeIsolated { $0.wrote(error: error) }
        }

        func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
            owner?.assumeIsolated { $0.leave(for: 0) }
        }
    }
}
