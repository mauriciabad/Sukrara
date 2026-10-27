// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The wearer's current sensor as the live events say (PROTOCOL-v2 "Sensor life"), kept in UserDefaults so the badge
/// survives a restart: `sensorStart`, `sensorEnd`, `sensorWarmupEnd` (unix s). Android: core/SensorLife.
enum SensorStore {
    static var current: SensorInfo? {
        let d = UserDefaults.standard
        guard d.object(forKey: "sensorEnd") != nil else { return nil }
        return SensorInfo(start: d.double(forKey: "sensorStart"), end: d.double(forKey: "sensorEnd"),
                          warmupEnd: d.double(forKey: "sensorWarmupEnd"))
    }

    /// Stores `s` (nil = forget); true when it changed.
    @discardableResult
    static func set(_ s: SensorInfo?) -> Bool {
        guard s != current else { return false }
        let d = UserDefaults.standard
        if let s {
            d.set(s.start, forKey: "sensorStart"); d.set(s.end, forKey: "sensorEnd"); d.set(s.warmupEnd, forKey: "sensorWarmupEnd")
        } else {
            for k in ["sensorStart", "sensorEnd", "sensorWarmupEnd"] { d.removeObject(forKey: k) }
        }
        return true
    }
}
