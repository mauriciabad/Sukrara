// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI

// The alert sound choice, the Mac form of Android's SoundDialog (ui/AlertsScreen.kt): the four packs, the system
// sound and none (plus "Pack default (…)" for one alert), each with ▶ to hear it before choosing. A pop-up menu can't
// hold a play button per item, so the button opens a popover with the list (HIG "Popovers": options for the control
// that opened it). Self-contained: the Settings window only places `SoundPickerButton`.
//
// Files: spikes/alerts/sounds (4 packs × 8 kinds × 3 variants, IMA4 CAF < 30 s, in Sukrara.app/Contents/Resources,
// where UNNotificationSound(named:) looks); Alerts.sound picks the variant.

enum SoundChoice {
    /// Every value in the order Android lists them ("" = the pack default, per alert only).
    static let all = AlertSettings.packs + ["system", "none"]

    /// Android `soundLabel`: "Quest (adventure)", "System default", "None".
    static func label(_ p: String) -> String {
        switch p {
        case "arcade": "Arcade (8-bit)"
        case "kart": "Kart (racing)"
        case "quest": "Quest (adventure)"
        case "soft": "Soft (calm)"
        case "system": "System default"
        default: "None"
        }
    }

    /// Android `soundName`: the pack's name alone.
    static func name(_ p: String) -> String {
        AlertSettings.packs.contains(p) ? p.capitalized : label(p)
    }
}

/// The list: a checkmark on the chosen sound, ▶ on every one that plays something.
struct SoundPickerList: View {
    let kind: AlertKind
    @Binding var selection: String
    /// Non-nil adds "Pack default (<pack>)" first (value "").
    let packDefault: String?
    let onPlay: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach((packDefault == nil ? [] : [""]) + SoundChoice.all, id: \.self) { o in
                let plays = o.isEmpty ? packDefault! : o
                HStack(spacing: 8) {
                    Button { selection = o } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "checkmark").opacity(selection == o ? 1 : 0).fontWeight(.semibold)
                            Text(o.isEmpty ? "Pack default (\(SoundChoice.name(packDefault!)))" : SoundChoice.label(o))
                            Spacer(minLength: 0)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selection == o ? [.isSelected] : [])
                    if plays != "none" {
                        Button { onPlay(plays) } label: { Image(systemName: "play.fill") }
                            .buttonStyle(.borderless)
                            .help("Play")
                            .accessibilityLabel("Play \(SoundChoice.name(plays))")
                    }
                }
                .padding(.vertical, 5)
            }
        }
        .padding(12)
    }
}

/// A Settings row: the title, then a button with the current sound that opens the list.
struct SoundPickerButton: View {
    let title: String
    let kind: AlertKind
    @Binding var selection: String
    let packDefault: String?
    let onPlay: (String) -> Void
    @State private var open = false

    var body: some View {
        LabeledContent(title) {
            Button {
                open.toggle()
            } label: {
                HStack(spacing: 4) {
                    Text(selection.isEmpty ? "Pack (\(SoundChoice.name(packDefault ?? "quest")))" : SoundChoice.name(selection))
                    Image(systemName: "chevron.up.chevron.down").font(.caption2)
                }
            }
            .popover(isPresented: $open, arrowEdge: .trailing) {
                SoundPickerList(kind: kind, selection: $selection, packDefault: packDefault, onPlay: onPlay).frame(width: 260)
            }
        }
    }
}
