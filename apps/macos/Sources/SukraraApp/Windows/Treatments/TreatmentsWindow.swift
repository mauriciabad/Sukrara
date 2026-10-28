// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraCore
import SwiftUI

/// Add treatment: Rapid, Long, Carbs, Note at once, when (now or minutes ago); Save sends every filled field.
public struct AddTreatmentView: View {
    @State private var fields = TreatmentForm.Fields()
    @State private var minutes = ""
    @State private var saved = false
    @FocusState private var focus: Treatment.Kind?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let focusCarbs: Bool
    let save: ([Treatment]) -> Void
    let cancel: () -> Void
    var now: () -> Date = Date.init

    public init(focusCarbs: Bool, save: @escaping ([Treatment]) -> Void, cancel: @escaping () -> Void) {
        self.focusCarbs = focusCarbs
        self.save = save
        self.cancel = cancel
    }

    private var ago: TreatmentForm.MinutesAgo { minutes.isEmpty ? .valid(nil) : TreatmentForm.minutesAgo(minutes) }

    public var body: some View {
        Form {
            amount(L10n.t("Rapid"), "u", $fields.rapid, .rapid)
            amount(L10n.t("Long"), "u", $fields.long, .long)
            amount(L10n.t("Carbs"), "g", $fields.carbs, .carbs)
            TextField(
                L10n.t("Note"),
                text: Binding(
                    get: { fields.note }, set: { fields.note = TreatmentForm.filterNote($0, old: fields.note) })
            )
            .focused($focus, equals: .note)
            TextField(
                L10n.t("Minutes ago (empty = now)"),
                text: Binding(get: { minutes }, set: { minutes = TreatmentForm.filterMinutes($0) }))
            if fields.hasInvalidAmount || ago == .invalid {
                Text(L10n.t("Insulin up to 100u, carbs up to 500g, at most 23 h 59 min ago."))
                    .font(.caption).foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button(L10n.t("Cancel"), action: cancel).keyboardShortcut(.cancelAction)
                Button(L10n.t("Save")) { commit() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!fields.canSave || ago == .invalid)
            }
        }
        .formStyle(.grouped)
        .scaleEffect(saved && !reduceMotion ? 0.97 : 1)
        .animation(reduceMotion ? nil : .spring(duration: 0.25), value: saved)
        .frame(width: 380)
        .onAppear { focus = focusCarbs ? .carbs : .rapid }
    }

    private func amount(_ label: String, _ unit: String, _ text: Binding<String>, _ kind: Treatment.Kind) -> some View {
        HStack {
            TextField(
                label,
                text: Binding(get: { text.wrappedValue }, set: { text.wrappedValue = TreatmentForm.filterAmount($0) })
            )
            .focused($focus, equals: kind)
            .accessibilityLabel(label)
            Text(unit).foregroundStyle(.secondary).accessibilityHidden(true)
        }
    }

    private func commit() {
        guard case .valid(let agoS) = ago else { return }
        let d = now()
        let t = Int64(d.timeIntervalSince1970) - Int64(agoS ?? 0)
        let entries = fields.treatments(at: t, u: TreatmentsModel.millis(d))
        guard !entries.isEmpty else { return }
        NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
        saved = true
        save(entries)
    }
}

/// The Treatments window: entries newest first with their delivery state, Retry, delete with a 10 s undo toast / ⌘Z.
public struct TreatmentsView: View {
    @Bindable var model: TreatmentsModel
    var add: () -> Void

    public init(model: TreatmentsModel, add: @escaping () -> Void) {
        self.model = model
        self.add = add
    }

    public var body: some View {
        VStack(spacing: 0) {
            if model.rows.isEmpty {
                ContentUnavailableView(L10n.t("No treatments yet"), systemImage: "syringe")
            } else {
                List(model.rows) { row in rowView(row) }
            }
            if !model.canWrite {
                Label(
                    model.isFollowing
                        ? L10n.t("The wearer hasn't allowed this Mac to add treatments")
                        : L10n.t("Follow someone to see and add their treatments"),
                    systemImage: model.isFollowing ? "lock" : "person.badge.plus"
                )
                .font(.callout)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
            }
            if model.undoAvailable, let u = model.undo {
                HStack {
                    Text(L10n.t("Deleted \(Self.title(u.row.treatment))"))
                    Spacer()
                    Button(L10n.t("Undo")) { Task { await model.undoDelete() } }
                        .keyboardShortcut("z", modifiers: .command)
                }
                .padding(10)
                .background(.thinMaterial)
                .task(id: u.row.id) {
                    try? await Task.sleep(for: .seconds(TreatmentsModel.undoSeconds))
                    model.dismissUndo()
                }
            }
        }
        .toolbar {
            Button(action: add) { Label(L10n.t("Add treatment"), systemImage: "plus") }
                .disabled(!model.canWrite)
                .help(
                    model.canWrite
                        ? L10n.t("Add treatment") : L10n.t("The wearer hasn't allowed this Mac to add treatments"))
        }
        .frame(minWidth: 420, minHeight: 360)
    }

    private func rowView(_ row: TreatmentsModel.Row) -> some View {
        HStack {
            VStack(alignment: .leading) {
                Text(Self.title(row.treatment))
                Text(Clock.time(Date(timeIntervalSince1970: TimeInterval(row.treatment.t))))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            delivery(row)
            if model.canChange(row) {
                Button {
                    Task { await model.delete(row) }
                } label: {
                    Image(systemName: "trash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L10n.t("Delete \(Self.title(row.treatment))"))
            }
        }
        .frame(minHeight: 28)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func delivery(_ row: TreatmentsModel.Row) -> some View {
        switch row.delivery {
        case .sending: Label(L10n.t("Sending"), systemImage: "arrow.up.circle").foregroundStyle(.secondary)
        case .sent: Label(L10n.t("Sent"), systemImage: "checkmark.circle").foregroundStyle(.secondary)
        case .accepted: Label(L10n.t("On the phone"), systemImage: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed:
            HStack {
                Label(L10n.t("Not sent"), systemImage: "exclamationmark.circle").foregroundStyle(.red)
                Button(L10n.t("Retry")) { Task { await model.retry(row) } }
            }
        case nil: EmptyView()
        }
    }

    /// "4.5u rapid insulin", "30g carbs", the note's text.
    static func title(_ t: Treatment) -> String {
        let a = t.a.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(t.a)) : String(format: "%.1f", t.a)
        switch t.k {
        case .rapid: return L10n.t("\(a)u rapid insulin")
        case .long: return L10n.t("\(a)u long-acting insulin")
        case .carbs: return L10n.t("\(a)g carbs")
        case .note: return t.n
        }
    }
}
