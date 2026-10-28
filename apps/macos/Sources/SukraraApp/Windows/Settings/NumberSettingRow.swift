// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SwiftUI

/// THE number input of the Mac (CLAUDE.md "Settings never diverge"): a pop-up of the format's presets and special
/// values plus "Custom…" where custom values are allowed; Custom shows a field + stepper + unit with the range under
/// it. Typed text that isn't in range on the step turns the note red and isn't applied. `readOnly` = a mirrored row.
struct NumberSettingRow: View {
    let label: String
    let format: NumberSpec
    @Binding var value: Double
    var readOnly = false
    @State private var custom = false
    @State private var text = ""

    private var isCustom: Bool { custom || !(format.isPreset(value) || format.specialOf(value) != nil) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Picker(label, selection: choice) {
                ForEach(format.presets + format.special.keys.filter { !format.presets.contains($0) }.sorted(), id: \.self) {
                    Text(Self.word(format, $0)).tag(Optional($0))
                }
                if format.custom { Text(L10n.t("Custom…")).tag(Double?.none) }
            }
            .disabled(readOnly)
            .accessibilityValue(Self.word(format, value))
            if isCustom && format.custom && !readOnly {
                HStack {
                    TextField(L10n.t("Value"), text: $text)  // settings-ok: the one number input
                        .frame(width: 70)
                        .onSubmit(commit)
                        .accessibilityLabel(label)
                    Stepper("", onIncrement: { nudge(1) }, onDecrement: { nudge(-1) })  // settings-ok: the one number input
                        .labelsHidden()
                        .accessibilityLabel(L10n.t("Adjust \(label)"))
                    Text(format.unit).foregroundStyle(.secondary)
                }
                Text(L10n.t("Enter \(format.text(format.min))–\(format.text(format.max)) \(format.unit)"))
                    .font(.caption)
                    .foregroundStyle(format.parse(text) == nil ? .red : .secondary)
            }
        }
        .frame(minHeight: 24)
        .onAppear { text = format.text(value) }
        .onChange(of: value) { text = format.text(value) }
    }

    private var choice: Binding<Double?> {
        Binding(
            get: { isCustom ? nil : value },
            set: { new in
                if let new {
                    custom = false
                    value = new
                } else {
                    custom = true
                }
            })
    }

    private func commit() {
        if let v = format.parse(text) { value = v }
    }

    private func nudge(_ sign: Double) {
        value = format.coerce(value + sign * format.nudge)
    }

    /// "70 mg/dL", "Off", "Only once", "1 h".
    static func word(_ f: NumberSpec, _ v: Double) -> String {
        switch f.specialOf(v) {
        case "off": L10n.t("Off")
        case "once": L10n.t("Only once")
        case "none": L10n.t("None")
        case "until_turned_off": L10n.t("Until you turn it off")
        case "same_as_default": L10n.t("Same as default")
        default: f.unit.isEmpty ? f.text(v) : "\(f.text(v)) \(f.unit)"
        }
    }
}
