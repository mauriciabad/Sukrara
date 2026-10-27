// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraCore

/// How far an entry this Mac wrote has got (PROTOCOL-v2 "Treatments").
public enum TreatmentDeliveryState: Sendable, Equatable {
    case sending, sent, accepted
    /// No relay took it and the phone isn't near; Retry sends it again.
    case failed
}

/// What the treatment windows need from the follower runtime (chunk 15's `FollowerService.send` behind an adapter).
public protocol TreatmentActions: AnyObject {
    /// Signs and sends a new version of `treatment` (new entry, edit or tombstone). False = not allowed.
    func send(_ treatment: Treatment) async -> Bool
}

/// The Treatments window's state: entries newest first, delivery per entry, and delete with undo (⌘Z or the
/// toast's Undo for 10 s).
@Observable
public final class TreatmentsModel {
    public static let undoSeconds: TimeInterval = 10

    public struct Row: Identifiable, Equatable, Sendable {
        public var treatment: Treatment
        /// nil for entries this Mac didn't write.
        public var delivery: TreatmentDeliveryState?
        public var id: String { treatment.id }
    }

    public private(set) var rows: [Row]
    /// The entry the toast offers to bring back, and when the offer ends.
    public private(set) var undo: (row: Row, until: Date)?
    /// The wearer allowed this Mac to add treatments.
    public var canWrite: Bool
    @ObservationIgnored private weak var actions: (any TreatmentActions)?
    @ObservationIgnored private let now: () -> Date

    public init(rows: [Row], canWrite: Bool, actions: (any TreatmentActions)?, now: @escaping () -> Date = Date.init) {
        self.rows = rows.sorted { $0.treatment.t > $1.treatment.t }
        self.canWrite = canWrite
        self.actions = actions
        self.now = now
    }

    /// Only entries this Mac (or the wearer allowed it to) wrote can be changed: never the phone's or a pen's.
    public func canChange(_ row: Row) -> Bool { canWrite && row.treatment.o == .mac }

    public var undoAvailable: Bool { undo.map { $0.until > now() } ?? false }

    public func delete(_ row: Row) async {
        guard canChange(row), let i = rows.firstIndex(of: row) else { return }
        rows.remove(at: i)
        undo = (row, now().addingTimeInterval(Self.undoSeconds))
        var t = row.treatment
        t.del = true
        t.u = Self.millis(now())
        _ = await actions?.send(t)
    }

    /// Brings the last deleted entry back (a newer version wins over the tombstone).
    public func undoDelete() async {
        guard undoAvailable, let (row, _) = undo else { return }
        undo = nil
        var t = row.treatment
        t.del = false
        t.u = Self.millis(now())
        insert(Row(treatment: t, delivery: .sending))
        await send(t)
    }

    public func add(_ treatments: [Treatment]) async {
        for t in treatments {
            insert(Row(treatment: t, delivery: .sending))
            await send(t)
        }
    }

    public func retry(_ row: Row) async {
        guard row.delivery == .failed else { return }
        set(row.id, .sending)
        await send(row.treatment)
    }

    /// The runtime reported a delivery change (relay OK, the phone's echo, or a timeout).
    public func set(_ id: String, _ state: TreatmentDeliveryState) {
        guard let i = rows.firstIndex(where: { $0.id == id }) else { return }
        rows[i].delivery = state
    }

    public func dismissUndo() { undo = nil }

    private func send(_ t: Treatment) async {
        let ok = await actions?.send(t) ?? false
        if !ok { set(t.id, .failed) }
    }

    private func insert(_ row: Row) {
        rows.removeAll { $0.id == row.id }
        let i = rows.firstIndex { $0.treatment.t < row.treatment.t } ?? rows.endIndex
        rows.insert(row, at: i)
    }

    static func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }
}
