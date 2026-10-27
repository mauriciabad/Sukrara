// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

// The one gate between a launch and the user's real devices and data (CLAUDE.md: test and snapshot runs never
// touch the user's devices). Pure: everything it decides on is passed in, so `LaunchModeTests` covers every case.
// The PoC needed this after a stale debug binary followed the wearer with the real Keychain item (2026-10-19) and a
// snapshot run joined the phone over Bluetooth (2026-10-21).

/// What this process is for.
public enum LaunchMode: Equatable, Sendable {
    /// The menu bar app.
    case normal
    /// An offline render of one surface (`sukrara-snap`, debug builds only).
    case snapshot(SnapshotRequest)
}

public struct SnapshotRequest: Equatable, Sendable {
    public var surface: String
    public var outputDirectory: URL

    public init(surface: String, outputDirectory: URL) {
        self.surface = surface
        self.outputDirectory = outputDirectory
    }
}

/// Something that reaches outside the process: the user's sensor or phone, relays, notifications, the widget, the
/// network (update check) and the system's login items.
public enum Service: CaseIterable, Sendable {
    // notifications first: the alert notifier needs the center when `alerts` starts
    case notifications, ble, relays, alerts, widgetFeed, updateCheck, loginItem
}

/// The services a launch MAY start: an upper limit. The app ANDs it with the user's settings
/// (`limited(by:)`) and starts services only through `ServicesPlan.start(using:)`.
public struct ServicesPlan: Equatable, Sendable {
    public var ble, relays, alerts, widgetFeed, notifications, updateCheck, loginItem: Bool

    public init(
        ble: Bool, relays: Bool, alerts: Bool, widgetFeed: Bool, notifications: Bool, updateCheck: Bool = false,
        loginItem: Bool = false
    ) {
        self.ble = ble
        self.relays = relays
        self.alerts = alerts
        self.widgetFeed = widgetFeed
        self.notifications = notifications
        self.updateCheck = updateCheck
        self.loginItem = loginItem
    }

    public static let all = ServicesPlan(
        ble: true, relays: true, alerts: true, widgetFeed: true, notifications: true, updateCheck: true,
        loginItem: true)
    public static let none = ServicesPlan(
        ble: false, relays: false, alerts: false, widgetFeed: false, notifications: false)

    public func allows(_ service: Service) -> Bool {
        switch service {
        case .ble: ble
        case .relays: relays
        case .alerts: alerts
        case .widgetFeed: widgetFeed
        case .notifications: notifications
        case .updateCheck: updateCheck
        case .loginItem: loginItem
        }
    }

    /// The services this plan allows, in a fixed order.
    public var enabled: [Service] { Service.allCases.filter(allows) }

    /// Only what both this plan and `wanted` (e.g. the user's settings) allow.
    public func limited(by wanted: ServicesPlan) -> ServicesPlan {
        ServicesPlan(
            ble: ble && wanted.ble,
            relays: relays && wanted.relays,
            alerts: alerts && wanted.alerts,
            widgetFeed: widgetFeed && wanted.widgetFeed,
            notifications: notifications && wanted.notifications,
            updateCheck: updateCheck && wanted.updateCheck,
            loginItem: loginItem && wanted.loginItem
        )
    }

    /// Starts every allowed service and nothing else. The only place services are started from.
    public func start(using starter: some ServiceStarting) {
        for service in enabled { starter.start(service) }
    }
}

/// Starts one service. The app's implementation is the only code that creates a Bluetooth central, a relay pool,
/// the alert engine, the widget feed or notification requests.
public protocol ServiceStarting {
    func start(_ service: Service)
}

/// Where a launch keeps its data. Dev and snapshot runs never share the installed app's files, Keychain items or
/// defaults.
public struct DataScope: Equatable, Sendable {
    public var paths: SukraraPaths
    public var keychainService: String
    /// The defaults suite; nil = the standard domain (`SukraraIDs.defaultsDomain`).
    public var defaultsSuite: String?

    public var isIsolated: Bool { defaultsSuite != nil }

    /// The installed app's data.
    public static func user(home: URL) -> DataScope {
        DataScope(paths: SukraraPaths(home: home), keychainService: SukraraIDs.keychainService, defaultsSuite: nil)
    }

    /// Separate data under `root` (used as the home directory), Keychain service and defaults suite `….dev`.
    public static func isolated(root: URL) -> DataScope {
        let dev = "\(SukraraIDs.appBundleID).dev"
        return DataScope(paths: SukraraPaths(home: root), keychainService: dev, defaultsSuite: dev)
    }
}

/// How this build was made and where it runs from.
public struct BuildInfo: Equatable, Sendable {
    public var isDebugBuild: Bool
    public var bundlePath: String
    /// Whether the executable runs from an app bundle (false for a bare `swift build` executable).
    public var isBundle: Bool

    public init(isDebugBuild: Bool, bundlePath: String, isBundle: Bool) {
        self.isDebugBuild = isDebugBuild
        self.bundlePath = bundlePath
        self.isBundle = isBundle
    }

    /// A dev build: a debug build, a bare executable, or a bundle inside a git checkout (`build/Sukrara.app`).
    /// Copies outside a repo (`~/Applications`, a release unzipped in Downloads) are the user's app. The search
    /// stops below `home`, so a dotfiles repo in `~/.git` doesn't turn the installed app into a dev build.
    public func isDevBuild(home: URL, exists: (String) -> Bool) -> Bool {
        if isDebugBuild || !isBundle { return true }
        let stop = home.standardizedFileURL.path
        var dir = URL(fileURLWithPath: bundlePath).standardizedFileURL.deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty && dir.path != stop {
            if exists(dir.appending(path: ".git").path) { return true }
            dir = dir.deletingLastPathComponent()
        }
        return false
    }
}

public enum LaunchError: Error, Equatable {
    /// An argument the app doesn't understand: never "start the app anyway".
    case unknownArgument(String)
    /// A dev build asked to start without `SUKRARA_DEV=1`.
    case devBuildNeedsOptIn(bundlePath: String)
    /// A debug-only mode in a release build.
    case debugOnly
    /// `SUKRARA_DEV_HOME` points at the user's real home: a dev run would use the installed app's files.
    case devHomeIsUserHome

    public var exitCode: Int32 {
        switch self {
        case .unknownArgument, .debugOnly, .devHomeIsUserHome: 2
        case .devBuildNeedsOptIn: 3
        }
    }

    public var message: String {
        switch self {
        case .unknownArgument(let arg):
            "Unexpected argument '\(arg)'. Sukrara takes no arguments; snapshots are rendered by sukrara-snap."
        case .devBuildNeedsOptIn(let path):
            "This is a dev build (\(path)), not the installed Sukrara. Set SUKRARA_DEV=1 to start it offline "
                + "with separate data (no sensor, relays or alerts)."
        case .debugOnly:
            "Snapshots are only available in debug builds."
        case .devHomeIsUserHome:
            "SUKRARA_DEV_HOME is your real home folder: a dev run must use separate data. Choose another folder."
        }
    }
}

/// What a launch does: its mode, the services it may start and where its data lives.
public struct LaunchPlan: Equatable, Sendable {
    public var mode: LaunchMode
    public var services: ServicesPlan
    public var data: DataScope

    /// True when this run must not monitor (dev build or snapshot): the UI says so instead of looking like
    /// "no data".
    public var isOffline: Bool { services == .none }

    /// The plan for the menu bar app.
    /// - Parameters:
    ///   - args: the arguments without the executable path.
    ///   - home: the user's home directory.
    ///   - exists: file check used to find a git checkout around the bundle.
    public static func parse(
        _ args: [String],
        build: BuildInfo,
        env: [String: String],
        home: URL,
        exists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
    ) throws(LaunchError) -> LaunchPlan {
        // Old Finder / LaunchServices launches may add a process serial number; nothing else is accepted.
        if let unknown = args.first(where: { !$0.hasPrefix("-psn_") }) {
            throw .unknownArgument(unknown)
        }
        guard build.isDevBuild(home: home, exists: exists) else {
            return LaunchPlan(mode: .normal, services: .all, data: .user(home: home))
        }
        guard env["SUKRARA_DEV"] == "1" else {
            throw .devBuildNeedsOptIn(bundlePath: build.bundlePath)
        }
        let devHome =
            env["SUKRARA_DEV_HOME"].map { URL(fileURLWithPath: $0, isDirectory: true) }
            ?? home.appending(
                path: "Library/Application Support/\(SukraraIDs.appBundleID).dev", directoryHint: .isDirectory)
        guard devHome.standardizedFileURL.path != home.standardizedFileURL.path else { throw .devHomeIsUserHome }
        return LaunchPlan(mode: .normal, services: .none, data: .isolated(root: devHome))
    }

    /// The plan for an offline render: no services, data under `scratch`. Debug builds only.
    public static func snapshot(
        _ request: SnapshotRequest,
        build: BuildInfo,
        scratch: URL
    ) throws(LaunchError) -> LaunchPlan {
        guard build.isDebugBuild else { throw .debugOnly }
        return LaunchPlan(mode: .snapshot(request), services: .none, data: .isolated(root: scratch))
    }
}
