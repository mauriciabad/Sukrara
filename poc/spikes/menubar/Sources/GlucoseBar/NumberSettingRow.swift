// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// THE number input of Sukrara for Mac (round f28; Android's NumberSettingDialog is the same pattern): every numeric
/// setting goes through this view and takes its format from the shared schema (`NumberSpec`, spikes/settings/settings.json),
/// so presets, ranges, steps and units can't drift from Android. tools/check rule `settings` fails on any other number
/// input in the Settings UI.
///
/// The row: a pop-up button with the presets and special values ("Off", "Once", "Same as default", …) and, when the
/// spec allows custom values, "Custom…". Choosing Custom (or a stored value that is no preset) shows a text field with a
/// stepper and the unit, and the range under it ("Enter 1–48 h"); input that isn't valid turns that note red and is
/// not applied. Return or leaving the field commits.
/// - HIG Pop-up buttons: "Use a pop-up button to present a flat list of mutually exclusive options or states" and
///   "If necessary, include a Custom option in a pop-up button's menu to provide additional items that are useful in
///   some situations. Offering a Custom option can help you avoid cluttering the interface with items or controls that
///   people need only occasionally. You can also display explanatory text below the list to help people understand
///   how the options work." (developer.apple.com/design/human-interface-guidelines/pop-up-buttons)
/// - HIG Steppers: "A stepper sits next to a field that displays its current value, because the stepper itself doesn't
///   display a value." / "Consider pairing a stepper with a text field when large value changes are likely." / macOS:
///   "For large value ranges, consider supporting Shift-click to change the value quickly" (Shift-click = 10 nudges).
/// - HIG Text fields: "Validate fields when it makes sense … when entering an email address, it's best to validate
///   when people switch to another field" (we validate on Return and on leaving the field) and "Use a number formatter
///   to help with numeric data" (`NumberSpec.parse`: digits, '.' or ',' decimals, range and step).
struct NumberSettingRow: View {
    let title: String
    let spec: NumberSpec
    @Binding var value: Double
    /// The presets offered when they differ from the spec's (Relax: only slower than every alert's repeat).
    var presets: [Double]? = nil
    /// The lowest custom value when it is above the spec's minimum (Relax: the slowest repeat + 1).
    var customMin: Double? = nil
    /// How a value reads in the menu, when `NumberFormat.label` doesn't fit ("Same as default (12 hours)").
    var label: ((Double) -> String)? = nil
    /// A second line under the title.
    var subtitle: String? = nil

    /// Evidence aid (`-settingsSnapshot`): text typed into a row's custom field, by title.
    @MainActor static var previewTexts: [String: String] = [:]

    @State private var customMode = false
    @State private var text = ""
    @State private var invalid = false
    @FocusState private var focused: Bool

    private enum Choice: Hashable { case value(Double), custom }

    private var choices: [Double] {
        let p = presets ?? spec.presets
        return spec.special.keys.filter { k in !p.contains { abs($0 - k) < 1e-9 } }.sorted() + p
    }
    private var lo: Double { Swift.max(spec.min, customMin ?? spec.min) }
    private func isChoice(_ v: Double) -> Bool { choices.contains { abs($0 - v) < 1e-9 } }
    private var showCustom: Bool { spec.custom && (customMode || !isChoice(value)) }
    private func name(_ v: Double) -> String { label?(v) ?? NumberFormat.label(v, spec) }

    private var selection: Binding<Choice> {
        Binding(get: { showCustom ? .custom : .value(value) }, set: { c in
            switch c {
            case .value(let v):
                customMode = false
                invalid = false
                value = v
            case .custom:
                customMode = true
                let start = spec.valid(value) && spec.inRange(value) && value >= lo ? value : Swift.max(lo, spec.inRange(spec.default) ? spec.default : lo)
                value = start
                text = spec.text(start)
                invalid = false
                focused = true
            }
        })
    }

    var body: some View {
        LabeledContent {
            VStack(alignment: .trailing, spacing: 3) {
                HStack(spacing: 6) {
                    Picker(title, selection: selection) {
                        ForEach(choices, id: \.self) { Text(name($0)).tag(Choice.value($0)) }
                        if spec.custom {
                            Divider()
                            Text(NumberFormat.custom).tag(Choice.custom)
                        }
                    }
                    .labelsHidden()
                    .fixedSize()
                    if showCustom {
                        TextField(title, text: $text)
                            .labelsHidden()
                            .textFieldStyle(.roundedBorder)
                            .multilineTextAlignment(.trailing)
                            .monospacedDigit()
                            .frame(width: spec.decimals > 0 ? 56 : 52)
                            .focused($focused)
                            .onSubmit(commit)
                            .accessibilityLabel("\(title), custom value")
                            .accessibilityHint(NumberFormat.rangeNote(spec, lo: lo))
                        Stepper(title, onIncrement: { nudge(1) }, onDecrement: { nudge(-1) })
                            .labelsHidden()
                            .accessibilityLabel("\(title), adjust by \(spec.text(spec.nudge)) \(NumberFormat.unit(spec))")
                        if !NumberFormat.unit(spec).isEmpty {
                            Text(NumberFormat.unit(spec)).foregroundStyle(.secondary)
                        }
                    }
                }
                if showCustom {
                    // HIG Pop-up buttons: "display explanatory text below the list"; red only for an error
                    Text(NumberFormat.rangeNote(spec, lo: lo))
                        .font(.caption)
                        .foregroundStyle(invalid ? Color.red : Color.secondary)
                        .accessibilityLabel(invalid ? "Not valid. \(NumberFormat.rangeNote(spec, lo: lo))" : NumberFormat.rangeNote(spec, lo: lo))
                }
            }
        } label: {
            Text(title)
            if let subtitle { Text(subtitle) }
        }
        .onAppear {
            text = spec.text(value)
            customMode = spec.custom && !isChoice(value)
            if let t = Self.previewTexts[title] { customMode = true; text = t; commit() }
        }
        .onChange(of: value) { _, v in if !focused { text = spec.text(v); invalid = false } }
        .onChange(of: focused) { _, f in if !f, showCustom { commit() } }
    }

    /// The typed text, applied when it's a valid value (in range, on step); otherwise the note turns red.
    private func commit() {
        guard let v = spec.parse(text), v >= lo - 1e-9 else { invalid = true; return }
        invalid = false
        value = v
        text = spec.text(v)
    }

    /// One stepper click: ± `nudge` (Shift-click: 10 ×), kept in range and on step.
    private func nudge(_ dir: Double) {
        let base = spec.parse(text) ?? value
        let n = NSEvent.modifierFlags.contains(.shift) ? 10.0 : 1.0
        var v = Swift.min(Swift.max(base + dir * n * spec.nudge, lo), spec.max)
        v = Swift.min(lo + ((v - lo) / spec.step).rounded() * spec.step, spec.max)
        customMode = true
        invalid = false
        value = v
        text = spec.text(v)
    }
}

/// Adding to a list of numbers (lines across the graph): a field validated by the spec, an Add button, and the range
/// and the maximum count under it.
struct NumberAddRow: View {
    let title: String
    let spec: NumberSpec
    let count: Int
    let onAdd: (Double) -> Void
    @State private var text = ""
    @State private var invalid = false

    private var full: Bool { spec.maxItems > 0 && count >= spec.maxItems }

    var body: some View {
        LabeledContent {
            VStack(alignment: .trailing, spacing: 3) {
                HStack(spacing: 6) {
                    TextField(title, text: $text, prompt: Text(NumberFormat.unit(spec)))
                        .labelsHidden()
                        .textFieldStyle(.roundedBorder)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                        .onSubmit(add)
                    Button("Add", action: add).disabled(text.isEmpty)
                }
                Text(full ? "Up to \(spec.maxItems); remove one first." : NumberFormat.rangeNote(spec, lo: spec.min) + (spec.maxItems > 0 ? ", up to \(spec.maxItems)" : ""))
                    .font(.caption)
                    .foregroundStyle(invalid ? Color.red : Color.secondary)
            }
        } label: { Text(title) }
        .disabled(full)
    }

    private func add() {
        guard let v = spec.parse(text) else { invalid = true; return }
        invalid = false
        text = ""
        onAdd(v)
    }
}

/// How numbers read in Settings: in the menu ("12 hours", "30 min", "7 days", "180 mg/dL", "Off") and the range
/// note ("Enter 1–48 h").
enum NumberFormat {
    static func word(_ special: String) -> String {
        switch special {
        case "off": "Off"
        case "once": "Only once"
        case "none": "None"
        case "same_as_default": "Same as default"
        case "every_reading": "Every reading"
        case "until_turned_off": MuteCopy.choiceForever
        default: special
        }
    }

    /// The choice that opens a field for a typed value ends in an ellipsis because it needs more input before it
    /// completes (HIG Menus on ellipses; Android's Custom… radio too; -settingsselftest (j)).
    static let custom = "Custom…"

    /// The unit next to a custom field.
    static func unit(_ s: NumberSpec) -> String { s.unit == "d" ? "days" : s.unit }

    static func label(_ v: Double, _ s: NumberSpec) -> String {
        if let w = s.specialOf(v) { return word(w) }
        let n = s.text(v)
        switch s.unit {
        case "h": return n == "1" ? "1 hour" : "\(n) hours"
        case "d":
            if v < 86400 { let h = Int((v / 3600).rounded()); return h == 1 ? "1 hour" : "\(h) hours" }
            return n == "1" ? "1 day" : "\(n) days"
        case "": return n
        case "min": return Durations.text(Int(v.rounded()))   // round f30: "1 h", "2 h", not "60 min"
        default: return "\(n) \(s.unit)"
        }
    }

    /// "Enter 1–48 h", "Enter 0.5–5.0 mg/dL/min".
    static func rangeNote(_ s: NumberSpec, lo: Double) -> String {
        let u = unit(s)
        return "Enter \(s.text(lo))–\(s.text(s.max))" + (u.isEmpty ? "" : " \(u)")
    }
}
