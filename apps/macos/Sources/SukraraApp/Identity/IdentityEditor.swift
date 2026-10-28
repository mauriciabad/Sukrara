// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Observation
import SukraraShare
import SwiftUI

/// How this Mac appears to the person it follows: a name and a generated picture (PROTOCOL-v2 "Profiles"). Never
/// published: the picture travels as a few numbers inside the encrypted request and the later `profile` update.
/// The draft being edited; `avatar` nil = the grey initial (Customize starts from a fresh random picture).
@Observable
final class IdentityDraft: Identifiable {
    var name: String
    var avatar: Avatar?
    var customizing = false
    /// A picture from a newer app version can't be edited here: customizing starts from a fresh one.
    @ObservationIgnored private let fresh = Avatar.random()

    init(name: String, avatar: Avatar?) {
        self.name = name
        self.avatar = avatar
    }

    var editable: Avatar { avatar.flatMap { $0.isKnown ? $0 : nil } ?? fresh }
    var shape: String { editable.object["s"] as? String ?? "cube" }
    var colors: Int { editable.object["c"] as? Int ?? 0 }
    var canSave: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    func shuffle() { avatar = .random() }
    func setShape(_ s: String) { avatar = editable.with(shape: s) }
    func setColors(_ c: Int) { avatar = editable.with(colors: c) }
    func turn() { avatar = editable.turned() }
    /// Typing past the limit stops at 40 UTF-16 units (Android's field takes the first 40).
    func clampName() {
        if name.utf16.count > Profile.maxName { name = Profile.normalisedName(typed: name, previous: "") }
    }
}

/// Name + picture: Shuffle draws a new random picture; Customize shows the shape (a segmented control) and the colours
/// (circular swatches like System Settings › Appearance), and Turn the Colours. Same options as Android.
struct IdentityEditor: View {
    @Bindable var draft: IdentityDraft
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let shapes: [(String, String)] = [
        ("cube", L10n.t("Cube")), ("drop", L10n.t("Drop")), ("dots", L10n.t("Dots")), ("rings", L10n.t("Rings")),
        ("letter", L10n.t("Letter")),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 16) {
                AvatarView(avatar: draft.avatar, name: draft.name, size: 88)
                    .id((draft.avatar?.json ?? "") + AvatarDrawing.initial(draft.name))
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.85).combined(with: .opacity))
                VStack(alignment: .leading, spacing: 6) {
                    Button(L10n.t("Shuffle")) { change { draft.shuffle() } }
                    Button(draft.customizing ? L10n.t("Done") : L10n.t("Customize")) {
                        withAnimation(reduceMotion ? nil : .snappy) { draft.customizing.toggle() }
                    }
                    .buttonStyle(.link)
                }
            }
            if draft.customizing {
                VStack(alignment: .leading, spacing: 10) {
                    Picker(
                        L10n.t("Shape"),
                        selection: Binding(get: { draft.shape }, set: { s in change { draft.setShape(s) } })
                    ) {
                        ForEach(Self.shapes, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .accessibilityLabel(L10n.t("Shape"))
                    HStack(spacing: 8) {
                        ForEach(0..<AvatarDrawing.V1.palettes.count, id: \.self) { i in
                            let selected = draft.colors == i
                            Button {
                                change { draft.setColors(i) }
                            } label: {
                                Circle().fill(Color(cgColor: AvatarDrawing.color(AvatarDrawing.V1.palettes[i].0)))
                                    .frame(width: 22, height: 22)
                                    .overlay(Circle().strokeBorder(Color.primary, lineWidth: selected ? 2.5 : 0))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(L10n.t("Colour \(i + 1)"))
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                        }
                    }
                    Button(L10n.t("Turn the Colours")) { change { draft.turn() } }
                }
                .transition(.opacity)
            }
            TextField(L10n.t("Your name"), text: $draft.name)
                .textFieldStyle(.roundedBorder)
                .onChange(of: draft.name) { draft.clampName() }
            Text(
                L10n.t(
                    "Only the people you share with see your name and picture. They travel end-to-end encrypted and are never posted publicly."
                )
            )
            .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }

    /// A picture change pops in with a spring and a light haptic (reduced motion: no animation).
    private func change(_ edit: () -> Void) {
        withAnimation(reduceMotion ? nil : .spring(duration: 0.3, bounce: 0.3)) { edit() }
        NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
    }
}

/// The editor as a sheet: title, editor, when the wearer sees it, Cancel and Save (disabled while the name is blank).
struct IdentitySheet: View {
    @Bindable var draft: IdentityDraft
    let wearer: String?
    let onSave: (String, Avatar?) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(wearer.map { L10n.t("How You Appear to \($0)") } ?? L10n.t("How You Appear")).font(.headline)
            IdentityEditor(draft: draft)
            Text(L10n.t("The person you follow sees the change as soon as it reaches them."))
                .font(.callout).foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button(L10n.t("Cancel"), role: .cancel, action: onCancel).keyboardShortcut(.cancelAction)
                Button(L10n.t("Save")) { onSave(draft.name, draft.avatar) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!draft.canSave)
            }
        }
        .padding(20)
        .frame(width: 400)
    }
}

/// The Settings row: this Mac's picture and name, and Edit… which opens the sheet.
struct IdentityRow: View {
    let me: Profile
    let wearer: String?
    let onSave: (String, Avatar?) -> Void
    @State private var editing: IdentityDraft?

    var body: some View {
        LabeledContent {
            Button(L10n.t("Edit…")) { editing = IdentityDraft(name: me.name, avatar: me.avatar) }
                .accessibilityLabel(L10n.t("Edit how you appear"))
        } label: {
            HStack(spacing: 10) {
                AvatarView(avatar: me.avatar, name: me.name, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(me.name)
                    Text(wearer.map { L10n.t("How you appear to \($0)") } ?? L10n.t("How you appear to the wearer"))
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
        }
        .sheet(item: $editing) { draft in
            IdentitySheet(
                draft: draft, wearer: wearer,
                onSave: { name, avatar in
                    onSave(name, avatar)
                    editing = nil
                }, onCancel: { editing = nil })
        }
    }
}
