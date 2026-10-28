// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraGraph

extension ChartScroll {
    /// The two-finger double tap: the place's own window ↔ 3 h, anchored at the pointer (`anchor` 0…1 of the plot).
    mutating func smartZoom(anchor: Double, default def: TimeInterval, first: TimeInterval, now: TimeInterval) {
        let close: TimeInterval = 3 * 3600
        let current = span(def)
        // at 3 h (or a place that shows 3 h or less) the tap goes back to the place's own window
        let target = abs(current - close) < Self.sameSpan || def <= close ? def : close
        guard abs(target - current) >= Self.sameSpan else { return }
        zoom(current / target, anchor: anchor, default: def, first: first, now: now)
    }
}

/// A critically damped spring (no bounce) at the stiffness Android's home card eases with (`EASE_STIFFNESS` 380):
/// the panel chart's y range, the Now pill's opacity and the way back to now glide instead of jumping. Pure: the
/// value at any time comes from the start, the start velocity and the target.
struct Glide: Equatable {
    var from: Double
    var velocity: Double = 0
    var target: Double
    var start: TimeInterval = 0

    static let omega = 380.0.squareRoot()
    /// Long enough for the spring to be within a ten-thousandth of the way (e^-ωt (1 + ωt)).
    static let settle: TimeInterval = 0.6

    init(_ value: Double) {
        from = value
        target = value
    }

    func value(at t: TimeInterval) -> Double {
        let dt = max(0, t - start)
        guard dt < Self.settle else { return target }
        let x0 = from - target
        return target + (x0 + (velocity + Self.omega * x0) * dt) * exp(-Self.omega * dt)
    }

    func velocity(at t: TimeInterval) -> Double {
        let dt = max(0, t - start)
        guard dt < Self.settle else { return 0 }
        let x0 = from - target
        let b = velocity + Self.omega * x0
        return (velocity - Self.omega * b * dt) * exp(-Self.omega * dt)
    }

    func settled(at t: TimeInterval) -> Bool { t - start >= Self.settle || (from == target && velocity == 0) }

    /// Glides from where it is at `t` (keeping its speed) to `new`; `instant` (reduced motion) jumps there.
    mutating func retarget(_ new: Double, at t: TimeInterval, instant: Bool) {
        if instant {
            self = Glide(new)
            return
        }
        guard new != target else { return }
        let x = value(at: t)
        let v = velocity(at: t)
        from = x
        velocity = v
        target = new
        start = t
    }
}

/// What the panel's chart draws while it glides: the y range and the Now pill eased towards the scroll's own, and
/// the way back to now (end and length together) after the Now pill or Back to Now.
struct ChartEase: Equatable {
    private var low = Glide(0)
    private var high = Glide(0)
    /// The y range has a value (the first target is taken as is, not glided to from nothing).
    private var hasY = false
    /// The first target (where the panel opens) is taken as is.
    private var started = false
    private var pill = Glide(0)
    /// 0 → 1 on the way back to now from `backEnd` / `backSpan`.
    private var back = Glide(1)
    private var backEnd: TimeInterval = 0
    private var backSpan: TimeInterval = 0

    /// New targets: the visible window's own y range and whether the pill shows.
    mutating func retarget(y: ClosedRange<Double>?, away: Bool, at t: TimeInterval, instant: Bool) {
        if let y {
            low.retarget(y.lowerBound, at: t, instant: instant || !hasY)
            high.retarget(y.upperBound, at: t, instant: instant || !hasY)
            hasY = true
        }
        pill.retarget(away ? 1 : 0, at: t, instant: instant || !started)
        started = true
    }

    /// The chart leaves `end` / `span` for now and the place's own window, gliding there (reduced motion: at once).
    mutating func goBack(from end: TimeInterval, span: TimeInterval, at t: TimeInterval, instant: Bool) {
        guard !instant else { return }
        backEnd = end
        backSpan = span
        back = Glide(0)
        back.retarget(1, at: t, instant: false)
    }

    /// Everything has arrived at `t` (the panel stops redrawing every frame).
    func settled(at t: TimeInterval) -> Bool {
        low.settled(at: t) && high.settled(at: t) && pill.settled(at: t) && back.settled(at: t)
    }

    /// The window the renderer draws at `t` for `scroll` (nil = the place's own window up to now, nothing gliding).
    func view(_ scroll: ChartScroll, default def: TimeInterval, now: TimeInterval, at t: TimeInterval) -> ChartView? {
        let p = CGFloat(min(max(pill.value(at: t), 0), 1))
        let lo = low.value(at: t)
        let y = hasY ? lo...max(high.value(at: t), lo + 1) : nil
        if scroll.away { return ChartView(end: scroll.end ?? now, y: y, now: p, span: scroll.span) }
        if !back.settled(at: t) {
            let k = min(max(back.value(at: t), 0), 1)
            let span = backSpan + (def - backSpan) * k
            return ChartView(
                end: min(backEnd + (now - backEnd) * k, now), y: y, now: p,
                span: abs(span - def) < ChartScroll.sameSpan ? nil : span)
        }
        guard !settled(at: t) || p > 0.001 else { return nil }
        return ChartView(end: now, y: y, now: p, span: nil)
    }
}
