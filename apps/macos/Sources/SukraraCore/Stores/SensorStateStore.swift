// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The wearer's sensor as their phone last reported it: `sensor.json` (absent = the wearer knows no sensor). A file,
/// not defaults: it is the followed person's data and "Stop following" wipes it with the readings.
public typealias SensorStateStore = JSONFileStore<WearerSensor>

/// The newest settings mirrored from the wearer: `wearer-settings.json`.
public typealias WearerSettingsStore = JSONFileStore<MirroredSettings>

extension SukraraPaths {
    public var readingsDirectory: URL { appSupport }
    public var sensorFile: URL { appSupport.appending(path: "sensor.json", directoryHint: .notDirectory) }
    public var wearerSettingsFile: URL {
        appSupport.appending(path: "wearer-settings.json", directoryHint: .notDirectory)
    }
}
