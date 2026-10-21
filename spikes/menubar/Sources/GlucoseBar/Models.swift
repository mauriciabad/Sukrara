// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

struct Reading: Equatable {
    var date: Date
    var mgdl: Double
    /// Rate reported by the source (mg/dL/min), if any. Libre 3 computes one on-sensor.
    var rate: Double? = nil
}

struct Snapshot {
    var latest: Reading
    var history: [Reading]          // ascending by date, includes latest
    var source: String

    /// The rate of change the surfaces show: the source's own (mg/dL/min), nothing else. No rate → the "no trend" ring
    /// (TrendMark), as on Android: the Mac used to derive one from a 15-min slope or the Nightscout direction and showed
    /// an arrow where Android showed the ring for the same reading (2026-10-21). LayoutSelfTest renders the menubar
    /// title through a Snapshot built from the scene's history, so a derivation here fails its `norate` scene.
    var rate: Double? { latest.rate.flatMap { $0.isFinite ? $0 : nil } }
}

enum GlucoseUnit: String {
    case mgdl, mmol
    func format(_ mgdl: Double) -> String {
        switch self {
        case .mgdl: String(Int(mgdl.rounded()))
        case .mmol: String(format: "%.1f", mgdl / 18.0182)
        }
    }
    func formatRate(_ r: Double) -> String {
        switch self {
        case .mgdl: String(format: "%+.1f mg/dL/min", r)
        case .mmol: String(format: "%+.2f mmol/L/min", r / 18.0182)
        }
    }
}
