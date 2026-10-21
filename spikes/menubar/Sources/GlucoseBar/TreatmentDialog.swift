// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

// Add Treatment: rapid insulin, long-acting insulin, carbs and a note in one go, like Android's treatment dialog
// (ui/TreatmentDialog.kt): the same fields in the same order, units, limits, defaults, time picker and quiet line.
//
// A small auxiliary window, not a sheet or a popover. HIG "Windows": "An auxiliary window presents a specific task or
// area in an app. Dedicated to one experience, an auxiliary window doesn't allow navigation to other app areas, and
// it typically includes a button people use to close it after completing the task." A sheet needs a parent window
// (the menubar panel closes as soon as it loses focus, and HIG "Sheets" points macOS to a window for anything more
// than a quick question), and a popover "disappears after people interact with it" (HIG "Popovers"), while HIG
// "Panels" says to avoid "controls that require typing text". Buttons follow HIG "Sheets"/"Alerts": Cancel and the
// default Save on the trailing edge; Return saves, Escape cancels (HIG "Keyboards"); Tab walks the fields in order.

/// The form without the UI: what the amount fields accept, the entries Save creates and the time picker's day. The same
/// rules as Android's `TreatmentForm`; both run spikes/libreview/treatment-form.json.
enum TreatmentForm {
    static let maxUnits = 100.0
    static let maxGrams = 500.0
    static let noteBytes = 100

    /// The kinds in dialog order, with the symbol the Treatments list shows.
    static let kinds: [(k: String, label: String, unit: String, symbol: String)] = [
        ("rapid", "Rapid", "u", "syringe"), ("long", "Long", "u", "syringe.fill"),
        ("carbs", "Carbs", "g", "fork.knife"), ("note", "Note", "", "text.bubble"),
    ]
    static func symbol(_ k: String) -> String { kinds.first { $0.k == k }?.symbol ?? "circle" }

    enum Amount: Equatable { case empty, invalid, value(Double) }

    /// What an amount field keeps of a typed text: digits, `.` and `,`, at most 5 characters.
    static func filterAmount(_ s: String) -> String { String(s.filter { $0.isASCII && ($0.isNumber || $0 == "." || $0 == ",") }.prefix(5)) }

    /// What the note keeps: one line (a line break becomes a space), refused past `noteBytes` UTF-8 bytes.
    static func filterNote(_ new: String, old: String) -> String {
        let s = new.replacingOccurrences(of: "\n", with: " ")
        return s.utf8.count <= noteBytes ? s : old
    }

    /// "4", "4.5" (insulin, 0.1u steps) or whole grams; `.invalid` past the limits, at 0 or 0 after rounding.
    static func parse(_ s: String, grams: Bool) -> Amount {
        let t = s.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        if t.isEmpty { return .empty }
        guard let v = Double(t), v.isFinite else { return .invalid }
        if v <= 0 || v > (grams ? maxGrams : maxUnits) { return .invalid }
        let a = grams ? v.rounded() : (v * 10).rounded() / 10
        return a <= 0 ? .invalid : .value(a)
    }

    struct Fields: Equatable {
        var rapid = "", long = "", carbs = "", note = ""
        var amounts: [(k: String, Amount)] {
            [("rapid", TreatmentForm.parse(rapid, grams: false)), ("long", TreatmentForm.parse(long, grams: false)),
             ("carbs", TreatmentForm.parse(carbs, grams: true))]
        }
        var invalid: Bool { amounts.contains { $0.1 == .invalid } }
        func amount(_ k: String) -> Amount { amounts.first { $0.k == k }?.1 ?? .empty }
        /// The entries Save creates at `t` (epoch s), in kind order; nil = an invalid amount.
        func entries(at t: Int64) -> [Treatment]? {
            if invalid { return nil }
            var out: [Treatment] = amounts.compactMap { k, a in
                if case .value(let v) = a { return Treatment(k: k, a: v, t: t) } else { return nil }
            }
            let n = note.trimmingCharacters(in: .whitespacesAndNewlines)
            if !n.isEmpty { out.append(Treatment(k: "note", a: 0, t: t, n: Treatment.truncate(n, bytes: TreatmentForm.noteBytes))) }
            return out
        }
        var canSave: Bool { entries(at: 0).map { !$0.isEmpty } ?? false }
    }

    /// The picked hour:minute today, or yesterday when that is later than `now` (entries are logged after the fact,
    /// never ahead); nil = within the current minute, i.e. now. Epoch s.
    static func pick(hour: Int, minute: Int, now: Date, calendar: Calendar = .current) -> Int64? {
        var p = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: now)!
        if p > now { p = calendar.date(byAdding: .day, value: -1, to: p)! }
        return now.timeIntervalSince(p) < 60 ? nil : Int64(p.timeIntervalSince1970)
    }

    /// The time chip: "Now", "Today 14:05", "Yesterday 23:10".
    static func timeLabel(_ at: Int64?, now: Date = Date()) -> String {
        guard let at else { return "Now" }
        let d = Date(timeIntervalSince1970: TimeInterval(at))
        return (Calendar.current.isDate(d, inSameDayAs: now) ? "Today " : "Yesterday ") + Clock.time(d)
    }

    /// "Highs quiet for 60 min · lows quiet for 15 min" for what the form would log at `at` (Android
    /// `Alerts.quietNote`): rapid insulin quiets highs, carbs quiet lows, for the minutes in Settings › Alerts that are
    /// still left at `now`. nil when alerts are off, while this Mac mirrors the wearer's alerts (the wearer's phone
    /// decides, like an Android follower), or when nothing would be quiet.
    static func quietNote(rapid: Bool, carbs: Bool, at: Int64, now: Int64, settings s: AlertSettings, mirroring: Bool) -> String? {
        if !s.on || mirroring { return nil }
        func left(_ on: Bool, _ m: AlertMute) -> Int? {
            guard on, m.on else { return nil }
            let l = Int((at + Int64(m.min) * 60 - now) / 60)
            return l > 0 ? l : nil
        }
        switch (left(rapid, s.muteAfterInsulin), left(carbs, s.muteAfterCarbs)) {
        case let (h?, l?): return "Highs quiet for \(h) min · lows quiet for \(l) min"
        case let (h?, nil): return "Highs quiet for \(h) min"
        case let (nil, l?): return "Lows quiet for \(l) min"
        default: return nil
        }
    }

    static var limitsText: String { "Insulin: up to \(Int(maxUnits))u · carbs: up to \(Int(maxGrams))g" }

    // MARK: self-test

    /// spikes/libreview/treatment-form.json (Android's TreatmentFormTest runs the same file) and the quiet line.
    static func selfTest() -> Bool {
        var ok = true
        func fail(_ s: String) { print("FAILED: treatment form \(s)"); ok = false }
        var dir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(atPath: dir.appendingPathComponent("spikes/libreview/treatment-form.json").path) {
            if dir.path == "/" { print("FAILED: spikes/libreview/treatment-form.json not found above the working directory"); return false }
            dir.deleteLastPathComponent()
        }
        guard let data = try? Data(contentsOf: dir.appendingPathComponent("spikes/libreview/treatment-form.json")),
              let fx = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { print("FAILED: treatment-form.json unreadable"); return false }
        let lim = fx["limits"] as? [String: Any] ?? [:]
        if (lim["units"] as? Double) != maxUnits || (lim["grams"] as? Double) != maxGrams || (lim["noteBytes"] as? Int) != noteBytes { fail("limits") }
        for f in fx["forms"] as? [[String: Any]] ?? [] {
            let name = f["name"] as? String ?? "?"
            let fields = Fields(rapid: f["rapid"] as? String ?? "", long: f["long"] as? String ?? "",
                                carbs: f["carbs"] as? String ?? "", note: f["note"] as? String ?? "")
            let got = fields.entries(at: 1000)
            if f["entries"] is NSNull {
                if got != nil || fields.canSave { fail("\"\(name)\": want invalid, got \(String(describing: got?.map(\.k)))") }
                continue
            }
            let want = (f["entries"] as? [[Any]] ?? []).map { "\($0[0]) \(($0[1] as? NSNumber)?.doubleValue ?? -1) \($0[2])" }
            let have = got?.map { "\($0.k) \($0.a) \($0.n)" }
            if have != want || got?.contains(where: { $0.t != 1000 || $0.o != "mac" || $0.del }) == true || fields.canSave != !want.isEmpty {
                fail("\"\(name)\": \(String(describing: have)), want \(want)")
            }
        }
        for c in fx["filters"] as? [[String: String]] ?? [] where filterAmount(c["in"]!) != c["out"]! {
            fail("filter \"\(c["in"]!)\" → \"\(filterAmount(c["in"]!))\", want \"\(c["out"]!)\"")
        }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        for c in fx["times"] as? [[String: Any]] ?? [] {
            let n = (c["now"] as! String).split(separator: ":").map { Int($0)! }
            let p = (c["pick"] as! String).split(separator: ":").map { Int($0)! }
            let now = cal.date(from: DateComponents(year: 2026, month: 6, day: 15, hour: n[0], minute: n[1], second: n[2]))!
            let got = pick(hour: p[0], minute: p[1], now: now, calendar: cal).map { Int64(now.timeIntervalSince1970) - $0 }
            let want = (c["ago_s"] as? NSNumber)?.int64Value
            if got != want { fail("time \(c["now"]!) pick \(c["pick"]!) → \(String(describing: got)) s ago, want \(String(describing: want))") }
        }
        // the quiet line (Android Alerts.quietNote)
        var s = AlertSettings()
        let now: Int64 = 100_000
        func quiet(_ r: Bool, _ c: Bool, ago: Int64 = 0, mirroring: Bool = false) -> String? {
            quietNote(rapid: r, carbs: c, at: now - ago * 60, now: now, settings: s, mirroring: mirroring)
        }
        if quiet(true, true) != "Highs quiet for 60 min · lows quiet for 15 min" { fail("quiet both: \(String(describing: quiet(true, true)))") }
        if quiet(true, false) != "Highs quiet for 60 min" { fail("quiet highs") }
        if quiet(false, true, ago: 10) != "Lows quiet for 5 min" { fail("quiet lows 10 min ago: \(String(describing: quiet(false, true, ago: 10)))") }
        if quiet(false, true, ago: 15) != nil || quiet(false, false) != nil { fail("quiet over / nothing") }
        if quiet(true, true, mirroring: true) != nil { fail("quiet while mirroring") }
        s.muteAfterInsulin.on = false
        if quiet(true, true) != "Lows quiet for 15 min" { fail("quiet with the insulin mute off") }
        s.on = false
        if quiet(true, true) != nil { fail("quiet with alerts off") }
        print(ok ? "treatment form selftest OK" : "treatment form selftest FAILED")
        return ok
    }
}

// MARK: - The window

@MainActor final class TreatmentDialogModel: ObservableObject {
    @Published var f = TreatmentForm.Fields()
    /// nil = now (resolved when saving); else epoch s.
    @Published var at: Int64?
    /// The latest reading is low: the carbs field takes the focus (eating is what's needed).
    var low = false
    var settings: () -> AlertSettings = { AlertSettings() }
    var mirroring: () -> Bool = { false }
    var onSave: (([Treatment]) -> Void)?
    var onClose: (() -> Void)?
    private var saved = false

    func reset(low: Bool) { f = .init(); at = nil; self.low = low; saved = false }

    var quiet: String? {
        let now = Int64(Date().timeIntervalSince1970)
        func ok(_ k: String) -> Bool { if case .value = f.amount(k) { true } else { false } }
        return TreatmentForm.quietNote(rapid: ok("rapid"), carbs: ok("carbs"), at: at ?? now, now: now,
                                       settings: settings(), mirroring: mirroring())
    }

    func save() {
        guard !saved, f.canSave, let e = f.entries(at: at ?? Int64(Date().timeIntervalSince1970)) else { return NSSound.beep() }
        saved = true
        onSave?(e)
        onClose?()
    }
}

struct TreatmentDialogView: View {
    @ObservedObject var model: TreatmentDialogModel
    enum Field: Hashable { case rapid, long, carbs, note }
    @FocusState private var focus: Field?
    @State private var pickTime = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text("Add Treatment").font(.title2.weight(.semibold))
                Spacer()
                Button { pickTime = true } label: {
                    Label(TreatmentForm.timeLabel(model.at), systemImage: "clock")
                }
                .buttonStyle(.bordered).buttonBorderShape(.capsule)
                .help("When it happened")
                .popover(isPresented: $pickTime, arrowEdge: .bottom) {
                    TimePicker(at: model.at) { model.at = $0; pickTime = false } cancel: { pickTime = false }
                }
            }
            HStack(alignment: .top, spacing: 8) {
                amount("rapid", $model.f.rapid, .rapid)
                amount("long", $model.f.long, .long)
                amount("carbs", $model.f.carbs, .carbs)
            }
            VStack(alignment: .leading, spacing: 4) {
                label("note")
                TextField("Note", text: Binding(get: { model.f.note }, set: { model.f.note = TreatmentForm.filterNote($0, old: model.f.note) }),
                          prompt: Text("Pizza, exercise, sick…"))
                    .textFieldStyle(.roundedBorder)
                    .controlSize(.large)
                    .focused($focus, equals: .note)
                    .onSubmit { model.save() }
                    .accessibilityLabel("Note")
                let bytes = model.f.note.utf8.count
                if bytes > TreatmentForm.noteBytes - 20 {
                    Text("\(bytes) / \(TreatmentForm.noteBytes)").font(.caption).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            if let q = model.quiet {
                Text(q).foregroundStyle(.secondary)
            }
            if model.f.invalid {
                Text(TreatmentForm.limitsText).foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button("Cancel") { model.onClose?() }.keyboardShortcut(.cancelAction)
                Button("Save") { model.save() }.keyboardShortcut(.defaultAction).disabled(!model.f.canSave)
            }
            .controlSize(.large)
        }
        .padding([.horizontal, .bottom], 20)
        .padding(.top, 6)
        .frame(width: 440)
        .fixedSize(horizontal: false, vertical: true)
        .animation(.default, value: model.quiet)
        .animation(.default, value: model.f.invalid)
        .onAppear { DispatchQueue.main.async { focus = model.low ? .carbs : .rapid } }
    }

    private func label(_ k: String) -> some View {
        HStack(spacing: 6) {
            KindMark(kind: k, color: Color(cgColor: TreatmentMarks.color(k, dark: scheme == .dark)))
            Text(TreatmentForm.kinds.first { $0.k == k }?.label ?? k)
        }
        .font(.callout).foregroundStyle(.secondary)
    }

    /// Amount field: kind marker + name above, the unit inside on the trailing edge (`u`, `g`), red outline when invalid.
    private func amount(_ k: String, _ text: Binding<String>, _ field: Field) -> some View {
        let kind = TreatmentForm.kinds.first { $0.k == k }!
        let bad = model.f.amount(k) == .invalid
        return VStack(alignment: .leading, spacing: 4) {
            label(k)
            HStack(spacing: 2) {
                TextField(kind.label, text: Binding(get: { text.wrappedValue }, set: { text.wrappedValue = TreatmentForm.filterAmount($0) }),
                          prompt: Text(""))
                    .textFieldStyle(.plain)
                    .font(.title2)
                    .focused($focus, equals: field)
                    .onSubmit { model.save() }
                    .accessibilityLabel("\(kind.label), \(kind.unit == "g" ? "grams" : "units")")
                Text(kind.unit).font(.title2).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Color(nsColor: .textBackgroundColor)))
            .overlay {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .strokeBorder(bad ? Color.red : focus == field ? Color.accentColor : Color(nsColor: .separatorColor),
                                  lineWidth: bad || focus == field ? 2 : 1)
            }
            .contentShape(Rectangle())
            .onTapGesture { focus = field }
        }
        .frame(maxWidth: .infinity)
    }
}

/// The same marker the charts draw for this kind (TreatmentMarks): rapid ▼ filled, long ▽ outlined, carbs ●, note ◆.
struct KindMark: View {
    let kind: String
    let color: Color
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            switch kind {
            case "rapid", "long":
                var p = Path()
                p.move(to: CGPoint(x: 0, y: w * 0.1)); p.addLine(to: CGPoint(x: w, y: w * 0.1)); p.addLine(to: CGPoint(x: w / 2, y: w * 0.95)); p.closeSubpath()
                if kind == "rapid" { ctx.fill(p, with: .color(color)) } else { ctx.stroke(p, with: .color(color), lineWidth: w * 0.16) }
            case "carbs":
                ctx.fill(Path(ellipseIn: CGRect(x: 0, y: 0, width: w, height: w)), with: .color(color))
            default:
                var p = Path()
                p.move(to: CGPoint(x: w / 2, y: 0)); p.addLine(to: CGPoint(x: w, y: w / 2)); p.addLine(to: CGPoint(x: w / 2, y: w)); p.addLine(to: CGPoint(x: 0, y: w / 2)); p.closeSubpath()
                ctx.fill(p, with: .color(color))
            }
        }
        .frame(width: 10, height: 10)
        .accessibilityHidden(true)
    }
}

/// "When?": hour and minute (a time later than now is yesterday), Now, Cancel, OK — Android's time dialog.
private struct TimePicker: View {
    let at: Int64?
    let pick: (Int64?) -> Void
    let cancel: () -> Void
    @State private var date = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("When?").font(.headline)
            DatePicker("Time", selection: $date, displayedComponents: .hourAndMinute)
                .datePickerStyle(.stepperField)
                .labelsHidden()
            HStack {
                Button("Now") { pick(nil) }
                Spacer()
                Button("Cancel", action: cancel).keyboardShortcut(.cancelAction)
                Button("OK") {
                    let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                    pick(TreatmentForm.pick(hour: c.hour ?? 0, minute: c.minute ?? 0, now: Date()))
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(14)
        .frame(width: 260)
        .onAppear { date = at.map { Date(timeIntervalSince1970: TimeInterval($0)) } ?? Date() }
    }
}

/// The Add Treatment window: one at a time, centred on the screen with the menubar item, brought to the front (the app
/// is menubar-only, so it activates itself); closes on Save, Cancel, Escape or its close button.
@MainActor final class TreatmentDialog: NSObject, NSWindowDelegate {
    let model = TreatmentDialogModel()
    private var window: NSWindow?
    var isVisible: Bool { window?.isVisible == true }
    var windowNumber: Int { window?.windowNumber ?? 0 }
    var frame: NSRect { window?.frame ?? .zero }

    override init() {
        super.init()
        model.onClose = { [weak self] in self?.window?.close() }
    }

    func show(low: Bool, on screen: NSScreen? = nil) {
        if let w = window, w.isVisible { NSApp.activate(ignoringOtherApps: true); w.makeKeyAndOrderFront(nil); return }
        model.reset(low: low)
        let host = NSHostingController(rootView: TreatmentDialogView(model: model))
        host.sizingOptions = [.preferredContentSize]
        let w = NSWindow(contentViewController: host)
        w.styleMask = [.titled, .closable]
        w.title = "Add Treatment"
        w.titleVisibility = .hidden
        w.titlebarAppearsTransparent = true
        w.isReleasedWhenClosed = false
        w.delegate = self
        w.layoutIfNeeded()
        if let s = (screen ?? NSScreen.main)?.visibleFrame {
            w.setFrameOrigin(NSPoint(x: s.midX - w.frame.width / 2, y: s.maxY - w.frame.height - s.height * 0.18))
        } else { w.center() }
        window = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) { window = nil }

    /// Debug aid (`treatment-keys`): key presses into this window as the keyboard sends them — key equivalents first
    /// (Return = Save, Escape = Cancel), then the focused field — without taking the focus from the app in front.
    func type(_ keys: String) {
        for ch in keys {
            guard let w = window else { return }
            let s = String(ch)
            let code: UInt16 = switch ch { case "\t": 48; case "\r": 36; case "\u{1b}": 53; default: 0 }
            for type in [NSEvent.EventType.keyDown, .keyUp] {
                guard let e = NSEvent.keyEvent(with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                                               windowNumber: w.windowNumber, context: nil, characters: s,
                                               charactersIgnoringModifiers: s, isARepeat: false, keyCode: code) else { continue }
                if type == .keyDown, w.performKeyEquivalent(with: e) { continue }
                w.sendEvent(e)
            }
        }
    }
}
