// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// "Treatments…": the treatment history in its own window, apart from the Glucose Report (the user, 2026-10-21: "it has
/// to be its own menu item"; Android keeps its treatments list apart from the report too). The last 7 days, newest
/// first, grouped by day, each row with its delivery state; Delete (context menu or ⌫) when this Mac may log, and
/// while following someone only on entries made here. HIG "Windows": a normal window, position remembered
/// (`SukraraTreatments`), closes with ⌘W.
@MainActor final class TreatmentsWindow: NSObject {
    let model = TreatmentsModel()
    private var window: NSWindow?

    var isVisible: Bool { window?.isVisible == true }
    var windowNumber: Int { window?.windowNumber ?? 0 }

    func show() {
        let w = window ?? make()
        window = w
        refresh()
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    /// New or deleted treatments: redraw while open.
    func refresh() { model.reload() }

    private func make() -> NSWindow {
        let host = NSHostingController(rootView: TreatmentsView(model: model))
        host.sizingOptions = [.preferredContentSize]
        let w = NSWindow(contentViewController: host)
        w.styleMask = [.titled, .closable, .miniaturizable]
        w.isReleasedWhenClosed = false
        w.title = "Treatments"
        if !w.setFrameUsingName("SukraraTreatments") { w.center() }
        w.setFrameAutosaveName("SukraraTreatments")
        return w
    }
}

@MainActor final class TreatmentsModel: ObservableObject {
    @Published private(set) var rows: [Treatment] = []
    @Published private(set) var canDelete = false
    @Published var selection: String?
    /// Pen entries whose doses are shown (the row's disclosure triangle).
    @Published var expanded: Set<String> = []

    var treatments: () -> [Treatment] = { [] }
    var delivery: (Treatment) -> (Delivery, Date)? = { _ in nil }
    /// The wearer's name for the delivery wording ("On Ana's phone since 14:07").
    var who: () -> String = { "the wearer" }
    var details: (Treatment) -> [String] = { _ in [] }
    var canLog: () -> Bool = { false }
    /// A writer deletes only the entries it made (P4): the rest stay read-only here.
    var mayChange: (Treatment) -> Bool = { _ in true }
    var onDelete: ((String) -> Void)?

    func reload() {
        let since = Int64(Date().timeIntervalSince1970) - 7 * 86400
        rows = treatments().filter { !$0.del && $0.t >= since }.sorted { $0.t > $1.t }
        canDelete = canLog()
    }

    func deletable(_ t: Treatment) -> Bool { canDelete && mayChange(t) }

    func delete(_ id: String?) {
        guard canDelete, let id, let t = rows.first(where: { $0.id == id }), mayChange(t) else { return }
        onDelete?(id)
        reload()
    }
}

struct TreatmentsView: View {
    @ObservedObject var model: TreatmentsModel

    var body: some View {
        list.frame(width: 420)
    }

    @ViewBuilder private var list: some View {
        if model.rows.isEmpty {
            Text("No treatments in the last 7 days")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 200)
        } else {
            List(selection: $model.selection) {
                ForEach(days, id: \.0) { day, rows in
                    Section(day) {
                        ForEach(rows, id: \.id) { t in
                            if let doses = t.pd, doses.count > 1 {
                                // A pen entry: one row per injection with its total; the doses the pen recorded
                                // (priming included) under its disclosure triangle (HIG "Disclosure controls"); one dose = the total, no triangle.
                                DisclosureGroup(isExpanded: expandedBinding(t.id)) {
                                    ForEach(Array(doses.enumerated()), id: \.offset) { _, d in PenDoseRow(d: d) }
                                } label: { row(t) }
                            } else {
                                row(t)
                            }
                        }
                    }
                }
            }
            .onDeleteCommand { model.delete(model.selection) }
            .frame(height: 420)
        }
    }

    private func row(_ t: Treatment) -> some View {
        TreatmentRow(t: t, delivery: model.delivery(t), who: model.who())
            .tag(t.id)
            .help(model.details(t).joined(separator: "\n"))
            .contextMenu {
                Button("Delete", role: .destructive) { model.delete(t.id) }.disabled(!model.deletable(t))
            }
    }

    private func expandedBinding(_ id: String) -> Binding<Bool> {
        Binding(get: { model.expanded.contains(id) },
                set: { if $0 { model.expanded.insert(id) } else { model.expanded.remove(id) } })
    }

    /// Rows grouped by day: "Today", "Yesterday", "Thursday 16 October".
    private var days: [(String, [Treatment])] {
        let cal = Calendar.current
        var out: [(String, [Treatment])] = []
        for t in model.rows {
            let d = Date(timeIntervalSince1970: TimeInterval(t.t))
            let name = cal.isDateInToday(d) ? "Today" : cal.isDateInYesterday(d) ? "Yesterday" : d.formatted(.dateTime.weekday(.wide).day().month(.wide))
            if out.last?.0 == name { out[out.count - 1].1.append(t) } else { out.append((name, [t])) }
        }
        return out
    }
}

private struct TreatmentRow: View {
    let t: Treatment
    let delivery: (Delivery, Date)?
    let who: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: TreatmentForm.symbol(t.k))
                .foregroundStyle(Color(cgColor: TreatmentMarks.color(t.k, dark: scheme == .dark)))
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                if let pen = PenCopy.summary(t) { Text(pen).font(.callout).foregroundStyle(.secondary) }
                if !t.n.isEmpty, t.k != "note" { Text(t.n).font(.callout).foregroundStyle(.secondary) }
            }
            Spacer()
            // Delivery like Messages' "Delivered": a symbol, its sentence as tooltip and for VoiceOver.
            if let (d, at) = delivery {
                let text = d.text(who: who, at: at)
                Image(systemName: d.symbol)
                    .foregroundStyle(d == .delivered ? AnyShapeStyle(.green) : d == .late ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                    .help(text)
                    .accessibilityLabel(text)
            }
            // Time of day, then the time since it like Android's lists (`12m`, `3h`), refreshed while shown.
            TimelineView(.periodic(from: .now, by: 30)) { ctx in
                HStack(spacing: 6) {
                    Text(Clock.time(Date(timeIntervalSince1970: TimeInterval(t.t))))
                        .foregroundStyle(.secondary)
                    Text(TreatmentMarks.duration(ctx.date.timeIntervalSince1970 - TimeInterval(t.t)))
                        .frame(minWidth: 34, alignment: .trailing)
                }
                .monospacedDigit()
            }
        }
        .padding(.vertical, 2)
    }

    private var title: String {
        switch t.k {
        case "rapid": "\(TreatmentMarks.amount(k: t.k, a: t.a) ?? "") rapid insulin"
        case "long": "\(TreatmentMarks.amount(k: t.k, a: t.a) ?? "") long insulin"
        case "carbs": "\(TreatmentMarks.amount(k: t.k, a: t.a) ?? "") carbs"
        default: t.n.isEmpty ? "Note" : t.n
        }
    }
}

/// One dose under a pen entry: its time, its units, "primed" for an air shot (not counted in the entry's total).
/// No role editor here: correcting the pen's split is the wearer's (SPEC §1), and a Mac writer may change only the
/// entries it made (writer scope), never the phone's pen entries.
private struct PenDoseRow: View {
    let d: Treatment.PenDose

    var body: some View {
        HStack(spacing: 10) {
            Text(PenCopy.dose(d))
                .foregroundStyle(d.p ? .secondary : .primary)
            Spacer()
            Text(Clock.time(Date(timeIntervalSince1970: TimeInterval(d.t))))
                .foregroundStyle(.secondary)
                .monospacedDigit()
        }
        .padding(.leading, 28)
        .accessibilityElement(children: .combine)
    }
}

/// The words for an entry read from an insulin pen (spikes/novopen-android/DESIGN.md §5): the list row's second line,
/// its doses and its tooltip. The total (`a`) is the row's title like any insulin entry.
enum PenCopy {
    /// "From the pen · 1.5u primed"; nil for entries typed by hand.
    static func summary(_ t: Treatment) -> String? {
        guard t.fromPen else { return nil }
        guard let pr = t.pr, pr > 0, let amount = TreatmentMarks.amount(k: "rapid", a: pr) else { return "From the pen" }
        return "From the pen · \(amount) primed"
    }

    /// "8u", "0.5u primed".
    static func dose(_ d: Treatment.PenDose) -> String {
        let amount = TreatmentMarks.amount(k: "rapid", a: d.a) ?? ""
        return d.p ? "\(amount) primed" : amount
    }

    /// The tooltip's first line: "Read from pen FHB2N9KE on Ana's phone".
    static func origin(_ t: Treatment, who: String) -> String? {
        guard let pen = t.pen else { return nil }
        return pen.isEmpty ? "Read from the pen on \(who)'s phone" : "Read from pen \(pen) on \(who)'s phone"
    }
}

extension TreatmentsModel {
    /// `-treatmentsselftest`: the list shows the last 7 days without deleted rows, newest first; Delete only when this
    /// Mac may log and the entry is its own to change.
    static func selfTest() -> Bool {
        var ok = true
        func check(_ what: String, _ c: Bool) { if !c { print("FAILED: treatments window \(what)"); ok = false } }
        let now = Int64(Date().timeIntervalSince1970)
        let a = Treatment(k: "rapid", a: 4.5, t: now - 60), b = Treatment(k: "carbs", a: 30, t: now - 3600)
        let old = Treatment(k: "long", a: 12, t: now - 8 * 86400), gone = Treatment(k: "note", a: 0, t: now - 120, n: "x", del: true)
        let m = TreatmentsModel()
        var deleted: [String] = []
        m.treatments = { [b, old, gone, a] }
        m.mayChange = { $0.id != b.id }
        m.onDelete = { deleted.append($0) }
        m.reload()
        check("rows = last 7 days, live, newest first", m.rows.map(\.id) == [a.id, b.id])
        m.delete(a.id)
        check("read-only while this Mac may not log", deleted.isEmpty)
        m.canLog = { true }
        m.reload()
        m.delete(b.id)
        check("someone else's entry stays", deleted.isEmpty)
        m.delete(a.id)
        check("its own entry is deleted", deleted == [a.id])
        print(ok ? "treatments window selftest OK" : "treatments window selftest FAILED")
        return ok
    }
}

/// Evidence aid: `-treatmentsSnapshot <dir>` renders the Treatments window with synthetic entries (a pen entry closed
/// and open, a pen entry without priming, hand-typed ones) to PNGs, light and dark, in invisible windows: no app data,
/// nothing on screen, offline (LaunchGuard).
@MainActor enum TreatmentsSnapshot {
    static func run(dir: String) {
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        let now = Int64(Date().timeIntervalSince1970) / 60 * 60
        var pen = Treatment(id: "pen-lunch", k: "rapid", a: 8.5, t: now - 47 * 60, o: "phone")
        pen.pr = 1.5; pen.pen = "FHB2N9KE"
        pen.pd = [.init(t: pen.t - 150, a: 1, p: true), .init(t: pen.t - 90, a: 0.5, p: true), .init(t: pen.t, a: 8),
                  .init(t: pen.t + 70, a: 0.5)]
        var plain = Treatment(id: "pen-breakfast", k: "rapid", a: 5, t: now - 5 * 3600, o: "phone")
        plain.pen = "FHB2N9KE"; plain.pr = 0; plain.pd = [.init(t: plain.t, a: 5)]
        let rows = [pen, Treatment(k: "carbs", a: 60, t: now - 50 * 60, o: "phone"), plain,
                    Treatment(k: "rapid", a: 2, t: now - 3 * 3600, o: "phone"), Treatment(k: "long", a: 14, t: now - 8 * 3600, o: "phone")]
        var windows: [(NSWindow, NSView, String)] = []
        for (name, open, list) in [("treatments-pen-row", false, rows), ("treatments-pen-detail", true, rows)] {
            for dark in [false, true] {
                let m = TreatmentsModel()
                m.treatments = { list }
                m.who = { "Ana" }
                m.reload()
                if open { m.expanded = [pen.id] }
                let host = NSHostingView(rootView: TreatmentsView(model: m).background(Color(nsColor: .windowBackgroundColor)))
                host.frame = NSRect(origin: .zero, size: host.fittingSize)
                let w = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
                w.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
                w.contentView = host
                w.alphaValue = 0
                w.orderFrontRegardless()
                windows.append((w, host, "\(dir)/\(name)-\(dark ? "dark" : "light").png"))
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            for (w, host, out) in windows {
                guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { continue }
                host.cacheDisplay(in: host.bounds, to: rep)
                try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: out))
                print(FileManager.default.fileExists(atPath: out) ? "wrote \(out)" : "FAILED \(out)")
                w.orderOut(nil)
            }
            exit(0)
        }
        app.run()
    }
}
