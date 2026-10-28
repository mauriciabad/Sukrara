// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraCore
import SwiftUI

/// The kinds in form order, with the symbol the form and the Treatments window show.
enum TreatmentKindLook {
    static func symbol(_ k: Treatment.Kind) -> String {
        switch k {
        case .rapid: "syringe"
        case .long: "syringe.fill"
        case .carbs: "fork.knife"
        case .note: "text.bubble"
        }
    }

    static func name(_ k: Treatment.Kind) -> String {
        switch k {
        case .rapid: L10n.t("Rapid")
        case .long: L10n.t("Long")
        case .carbs: L10n.t("Carbs")
        case .note: L10n.t("Note")
        }
    }

    static func unit(_ k: Treatment.Kind) -> String { k == .carbs ? "g" : "u" }
}

/// Add Treatment: Rapid, Long and Carbs side by side, a Note with its byte counter, and *When?* (a chip that opens a
/// clock time / minutes-ago picker); the line under them says which alerts the entries will quiet. Return saves,
/// Escape cancels. The focused field is the one the latest reading (or the card's pill) suggests.
public struct AddTreatmentView: View {
    @State private var fields = TreatmentForm.Fields()
    @State private var when = TreatmentForm.When.now
    @State private var pickTime = false
    @State private var saved = false
    @FocusState private var focus: Treatment.Kind?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let initialFocus: Treatment.Kind
    /// The quiet line for entries logged at `at` (epoch s): rapid insulin, carbs; nil = nothing to say.
    let quiet: (_ rapid: Bool, _ carbs: Bool, _ at: Int64) -> String?
    let save: ([Treatment]) -> Void
    let cancel: () -> Void
    var now: () -> Date = Date.init

    public init(
        focus: Treatment.Kind, quiet: @escaping (Bool, Bool, Int64) -> String? = { _, _, _ in nil },
        save: @escaping ([Treatment]) -> Void, cancel: @escaping () -> Void
    ) {
        initialFocus = focus
        self.quiet = quiet
        self.save = save
        self.cancel = cancel
    }

    private var quietLine: String? {
        func has(_ k: Treatment.Kind) -> Bool {
            fields.amounts.contains { a in
                if a.kind == k, case .value = a.amount { return true }
                return false
            }
        }
        let nowS = Int64(now().timeIntervalSince1970)
        return quiet(has(.rapid), has(.carbs), when.resolve(now: nowS))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text(L10n.t("Add Treatment")).font(.title2.weight(.semibold))
                Spacer()
                Button {
                    pickTime = true
                } label: {
                    Label(Self.whenLabel(when, now: now()), systemImage: "clock")
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .help(L10n.t("When it happened"))
                .accessibilityLabel(L10n.t("When: \(Self.whenLabel(when, now: now()))"))
                .popover(isPresented: $pickTime, arrowEdge: .bottom) {
                    WhenPicker(when: when) { picked in
                        if let picked { when = picked }
                        pickTime = false
                    }
                }
            }
            HStack(alignment: .top, spacing: 8) {
                amount(.rapid, $fields.rapid)
                amount(.long, $fields.long)
                amount(.carbs, $fields.carbs)
            }
            VStack(alignment: .leading, spacing: 4) {
                kindLabel(.note)
                TextField(
                    L10n.t("Note"),
                    text: Binding(
                        get: { fields.note }, set: { fields.note = TreatmentForm.filterNote($0, old: fields.note) }),
                    prompt: Text(L10n.t("Pizza, exercise, sick…"))
                )
                .textFieldStyle(.roundedBorder)
                .focused($focus, equals: .note)
                .onSubmit(commit)
                .accessibilityLabel(L10n.t("Note"))
                let bytes = fields.note.utf8.count
                if bytes > Treatment.noteBytes - 20 {
                    Text(verbatim: "\(bytes) / \(Treatment.noteBytes)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .accessibilityLabel(L10n.t("\(Treatment.noteBytes - bytes) characters left"))
                }
            }
            if let q = quietLine {
                Label(q, systemImage: "bell.slash").foregroundStyle(.secondary)
            }
            if fields.hasInvalidAmount {
                Text(L10n.t("Insulin up to 100u, carbs up to 500g."))
                    .font(.callout).foregroundStyle(.red)
            }
            HStack {
                Spacer()
                Button(L10n.t("Cancel"), action: cancel).keyboardShortcut(.cancelAction)
                Button(L10n.t("Save")) { commit() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!fields.canSave)
            }
            .controlSize(.large)
        }
        .padding(20)
        .frame(width: 440)
        .fixedSize(horizontal: false, vertical: true)
        .scaleEffect(saved && !reduceMotion ? 0.97 : 1)
        .animation(reduceMotion ? nil : .spring(duration: 0.25), value: saved)
        .animation(reduceMotion ? nil : .default, value: quietLine)
        .onAppear { focus = initialFocus }
    }

    private func kindLabel(_ k: Treatment.Kind) -> some View {
        Label(TreatmentKindLook.name(k), systemImage: TreatmentKindLook.symbol(k))
            .font(.callout)
            .foregroundStyle(.secondary)
            .accessibilityHidden(true)
    }

    private func amount(_ k: Treatment.Kind, _ text: Binding<String>) -> some View {
        let invalid = TreatmentForm.parse(text.wrappedValue, grams: k == .carbs) == .invalid
        return VStack(alignment: .leading, spacing: 4) {
            kindLabel(k)
            HStack(spacing: 4) {
                TextField(
                    TreatmentKindLook.name(k),
                    text: Binding(
                        get: { text.wrappedValue }, set: { text.wrappedValue = TreatmentForm.filterAmount($0) }),
                    prompt: Text(verbatim: "0")
                )
                .textFieldStyle(.roundedBorder)
                .focused($focus, equals: k)
                .onSubmit(commit)
                .accessibilityLabel(TreatmentKindLook.name(k))
                .overlay(RoundedRectangle(cornerRadius: 5).stroke(invalid ? Color.red : .clear))
                Text(TreatmentKindLook.unit(k)).foregroundStyle(.secondary).accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func commit() {
        // one Return can reach both the field's submit and the default button: save once
        guard fields.canSave, !saved else { return }
        let d = now()
        let t = when.resolve(now: Int64(d.timeIntervalSince1970))
        let entries = fields.treatments(at: t, u: TreatmentsModel.millis(d))
        guard !entries.isEmpty else { return }
        NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
        saved = true
        save(entries)
    }

    /// The chip: "Now", "15 min ago", "Today 14:05", "Yesterday 23:10".
    static func whenLabel(_ when: TreatmentForm.When, now: Date) -> String {
        switch when {
        case .now: return L10n.t("Now")
        case .ago(let m): return L10n.t("\(Durations.text(m)) ago")
        case .at(let t):
            let d = Date(timeIntervalSince1970: TimeInterval(t))
            return Calendar.current.isDate(d, inSameDayAs: now)
                ? L10n.t("Today \(Clock.time(d))") : L10n.t("Yesterday \(Clock.time(d))")
        }
    }
}

/// *When?*: a clock time (later than now = yesterday) or minutes ago with 5 / 15 / 30 / 60 presets; Now, Cancel, OK.
struct WhenPicker: View {
    let when: TreatmentForm.When
    /// The choice, or nil for Cancel.
    let done: (TreatmentForm.When?) -> Void
    @State private var date = Date()
    @State private var relative = false
    @State private var minutes = ""
    @FocusState private var minutesFocus: Bool

    var body: some View {
        let typed = TreatmentForm.When.fromPicker(minutesAgo: minutes)
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.t("When?")).font(.headline)
            Picker(L10n.t("When?"), selection: $relative) {
                Text(L10n.t("Time")).tag(false)
                Text(L10n.t("Minutes ago")).tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if relative {
                HStack(spacing: 6) {
                    TextField(
                        L10n.t("Minutes ago"),
                        text: Binding(get: { minutes }, set: { minutes = TreatmentForm.filterMinutes($0) })
                    )
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 70)
                    .focused($minutesFocus)
                    Text(verbatim: "min").foregroundStyle(.secondary).accessibilityHidden(true)
                }
                HStack(spacing: 6) {
                    ForEach(TreatmentForm.agoPresets, id: \.self) { m in
                        let on = typed == .ago(m)
                        Button(Durations.text(m)) { minutes = "\(m)" }
                            .buttonStyle(on ? AnyPrimitiveButtonStyle(.borderedProminent) : .init(.bordered))
                    }
                }
                .controlSize(.small)
            } else {
                DatePicker(L10n.t("Time"), selection: $date, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.stepperField)
                    .labelsHidden()
            }
            HStack {
                Button(L10n.t("Now")) { done(.now) }
                Spacer()
                Button(L10n.t("Cancel")) { done(nil) }.keyboardShortcut(.cancelAction)
                Button(L10n.t("OK")) {
                    if relative {
                        done(typed)
                    } else {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
                        done(.fromPicker(hour: c.hour ?? 0, minute: c.minute ?? 0, now: Date()))
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(relative && typed == nil)
            }
        }
        .padding(14)
        .frame(width: 280)
        .onAppear {
            switch when {
            case .now: break
            case .at(let t): date = Date(timeIntervalSince1970: TimeInterval(t))
            case .ago(let m):
                relative = true
                minutes = "\(m)"
            }
        }
        .onChange(of: relative) { _, r in if r { minutesFocus = true } }
    }
}

/// A button style chosen at run time (the preset that matches the typed minutes is prominent).
struct AnyPrimitiveButtonStyle: PrimitiveButtonStyle {
    private let make: (Configuration) -> AnyView
    init<S: PrimitiveButtonStyle>(_ style: S) { make = { AnyView(style.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}

/// The Treatments window: the last 7 days grouped by day, newest first, each entry with its kind's symbol, time,
/// time since, delivery state (Retry), who logged it (tooltip), and delete with a 10 s undo toast / ⌘Z.
public struct TreatmentsView: View {
    @Bindable var model: TreatmentsModel
    var add: () -> Void
    var now: () -> Date = Date.init

    public init(model: TreatmentsModel, add: @escaping () -> Void) {
        self.model = model
        self.add = add
    }

    public var body: some View {
        VStack(spacing: 0) {
            if TreatmentsModel.byDay(model.rows, now: now()).isEmpty {
                ContentUnavailableView(
                    L10n.t("No treatments in the last 7 days"), systemImage: "syringe",
                    description: Text(L10n.t("Entries logged on the phone or here show up in this list.")))
            } else {
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    let days = TreatmentsModel.byDay(model.rows, now: context.date)
                    List {
                        ForEach(days, id: \.day) { group in
                            Section(Self.dayTitle(group.day, now: context.date)) {
                                ForEach(group.rows) { row in rowView(row, now: context.date) }
                            }
                        }
                    }
                }
                .id(model.clockStyle)
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
        .frame(minWidth: 460, minHeight: 380)
    }

    private func rowView(_ row: TreatmentsModel.Row, now: Date) -> some View {
        let t = row.treatment
        let at = Date(timeIntervalSince1970: TimeInterval(t.t))
        return HStack(spacing: 10) {
            Image(systemName: TreatmentKindLook.symbol(t.k))
                .frame(width: 20)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.title(t))
                if t.k != .note, !t.n.isEmpty { Text(t.n).font(.callout).foregroundStyle(.secondary) }
                Text(Clock.time(at) + " · " + Self.since(at, now: now))
                    .font(.callout)
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
                .accessibilityLabel(L10n.t("Delete \(Self.title(t))"))
            }
        }
        .frame(minHeight: 30)
        .help(Self.loggedBy(t))
        .accessibilityElement(children: .combine)
        .accessibilityHint(Self.loggedBy(t))
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

    /// "Logged on the phone", "Logged on this Mac", "Logged by a follower", "Logged by the pen".
    static func loggedBy(_ t: Treatment) -> String {
        if t.fromPen { return L10n.t("Logged by the pen") }
        switch t.o {
        case .phone: return L10n.t("Logged on the wearer's phone")
        case .mac: return L10n.t("Logged on a Mac")
        case .follower: return L10n.t("Logged by a follower")
        }
    }

    /// "now", "12m ago", "3h 5m ago".
    static func since(_ at: Date, now: Date) -> String {
        let min = Int(max(0, now.timeIntervalSince(at)) / 60)
        return min < 1 ? L10n.t("just now") : L10n.t("\(Durations.compact(min)) ago")
    }

    /// "Today", "Yesterday", "Monday 26 Oct".
    static func dayTitle(_ day: Date, now: Date) -> String {
        let cal = Calendar.current
        if cal.isDate(day, inSameDayAs: now) { return L10n.t("Today") }
        if let y = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(day, inSameDayAs: y) {
            return L10n.t("Yesterday")
        }
        return day.formatted(.dateTime.weekday(.wide).day().month(.abbreviated))
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
