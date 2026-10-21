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
                            TreatmentRow(t: t, delivery: model.delivery(t), who: model.who())
                                .tag(t.id)
                                .help(model.details(t).joined(separator: "\n"))
                                .contextMenu {
                                    Button("Delete", role: .destructive) { model.delete(t.id) }.disabled(!model.deletable(t))
                                }
                        }
                    }
                }
            }
            .onDeleteCommand { model.delete(model.selection) }
            .frame(height: 420)
        }
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
