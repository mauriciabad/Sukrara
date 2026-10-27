// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation

#if DEBUG
    /// Debug builds only (snapshots, previews in tests): never a value the release app shows.
    public enum RealisticSynthetic {
        public static func readings(until now: TimeInterval, days: Int) -> [GraphReading] {
            var rng = JavaRandom(seed: 16)
            let start = now - Double(days) * 86400
            let tz = TimeZone.current
            struct Meal { var t: TimeInterval; var size: Double; var peak: Double }
            var meals: [Meal] = [], dips: [(TimeInterval, Double)] = []
            var day =
                start
                - (start + Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: start)))).truncatingRemainder(
                    dividingBy: 86400)
            while day < now + 86400 {
                let off = Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: day)))
                func at(_ h: Double) -> TimeInterval { day + TimeInterval(Int64(h * 3600)) - off }
                meals.append(
                    Meal(t: at(8.0 + rng.gauss() * 0.4), size: 45 + rng.next() * 50, peak: 45 + rng.next() * 20))
                meals.append(
                    Meal(t: at(13.8 + rng.gauss() * 0.5), size: 55 + rng.next() * 75, peak: 55 + rng.next() * 25))
                meals.append(
                    Meal(t: at(20.6 + rng.gauss() * 0.4), size: 50 + rng.next() * 115, peak: 60 + rng.next() * 25))
                if rng.next() < 0.6 { dips.append((at(17.0 + rng.gauss() * 0.6), 25 + rng.next() * 45)) }
                day += 86400
            }
            var walk = 0.0
            var out: [GraphReading] = []
            var t = (start / 60).rounded(.down) * 60
            while t <= now {
                let local = t + Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: t)))
                let hour = local.truncatingRemainder(dividingBy: 86400) / 3600
                var v = 104 + 18 * exp(-((hour - 6.5) * (hour - 6.5)) / 2)
                for m in meals {
                    let k = (t - m.t) / 60
                    if k > 0 && k < 900 {
                        v += m.size * (k / m.peak) * exp(1 - k / m.peak) - 8 * exp(-((k - 160) * (k - 160)) / 1800)
                    }
                }
                for (dt, depth) in dips {
                    let k = (t - dt) / 60; if k > -60 && k < 120 { v -= depth * exp(-(k * k) / 1600) }
                }
                walk = walk * 0.995 + rng.gauss() * 0.6
                v += walk + 3 * sin(t / 1300)
                out.append(GraphReading(t: t, v: max(40, v.rounded())))
                t += 60
            }
            for i in 5..<out.count { out[i].rate = (out[i].v - out[i - 5].v) / 5 }
            return out
        }

        /// java.util.Random (48-bit LCG, polar Gaussian), so the days match the Android gallery's `Random(16)` exactly.
        struct JavaRandom {
            private var seed: UInt64
            private var nextGaussian: Double? = nil
            init(seed: Int64) { self.seed = (UInt64(bitPattern: seed) ^ 0x5DEECE66D) & ((1 << 48) - 1) }
            private mutating func bits(_ n: Int) -> Int64 {
                seed = (seed &* 0x5DEECE66D &+ 0xB) & ((1 << 48) - 1)
                return Int64(Int32(truncatingIfNeeded: Int64(seed >> UInt64(48 - n))))
            }
            mutating func next() -> Double {
                Double((bits(26) << 27) + bits(27)) * 0x1.0p-53
            }
            mutating func gauss() -> Double {
                if let g = nextGaussian { nextGaussian = nil; return g }
                var v1 = 0.0, v2 = 0.0, s = 0.0
                repeat {
                    v1 = 2 * next() - 1; v2 = 2 * next() - 1; s = v1 * v1 + v2 * v2
                } while s >= 1 || s == 0
                let m = (-2 * Foundation.log(s) / s).squareRoot()
                nextGaussian = v2 * m
                return v1 * m
            }
        }
    }
#endif
