// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// sukrara-snap <surface> <output-dir>: renders one surface offline with synthetic data, for evidence and review.
// The one snapshot path (the app itself takes no snapshot flags). Debug builds only; no services, isolated data.
import Foundation

#if DEBUG
    import SukraraApp

    let args = Array(CommandLine.arguments.dropFirst())
    guard args.count == 2 else {
        FileHandle.standardError.write(Data("usage: sukrara-snap <surface> <output-dir>\n".utf8))
        exit(2)
    }
    let plan: LaunchPlan
    do {
        let scratch = FileManager.default.temporaryDirectory.appending(path: "sukrara-snap-\(UUID().uuidString)")
        plan = try LaunchPlan.snapshot(
            SnapshotRequest(surface: args[0], outputDirectory: URL(fileURLWithPath: args[1])),
            build: BuildInfo(isDebugBuild: true, bundlePath: Bundle.main.bundlePath, isBundle: false),
            scratch: scratch
        )
    } catch {
        FileHandle.standardError.write(Data("sukrara-snap: \(error.message)\n".utf8))
        exit(error.exitCode)
    }
    // Surfaces are registered by the chunks that build them.
    switch args[0] {
    case "card":
        do {
            let n = try CardSnapshots.render(to: URL(fileURLWithPath: args[1]))
            print("sukrara-snap: \(n) card pictures in \(args[1])")
        } catch {
            FileHandle.standardError.write(Data("sukrara-snap: \(error)\n".utf8))
            exit(1)
        }
    case "windows":
        do {
            let n = try WindowSnapshots.render(to: URL(fileURLWithPath: args[1]))
            print("sukrara-snap: \(n) window pictures in \(args[1])")
        } catch {
            FileHandle.standardError.write(Data("sukrara-snap: \(error)\n".utf8))
            exit(1)
        }
    default:
        FileHandle.standardError.write(Data("sukrara-snap: unknown surface '\(args[0])' (plan: \(plan.mode))\n".utf8))
        exit(2)
    }
#else
    FileHandle.standardError.write(Data("sukrara-snap is only available in debug builds\n".utf8))
    exit(2)
#endif
