// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

// How this Mac appears to the person it follows: a name and a generated picture (PROTOCOL-v2 "Profiles"), the Mac
// form of Android's `ProfileEditor` / `ProfileScreen` (ui/Profiles.kt). Never published: the picture travels as a few
// numbers inside the encrypted request and the later `profile` update (FollowerV2.setProfile).
//
// Self-contained: `IdentityRow` is the Settings row (picture, name, Edit…), `IdentitySheet` the editor with Save /
// Cancel; the Settings window only places the row.

/// The draft being edited. `avatar` nil = the grey initial (a fresh random picture is used once Customize starts).
@MainActor final class IdentityModel: ObservableObject {
    @Published var name: String
    @Published var avatar: [String: Any]? { didSet { avatarKey = avatar.map { V2.json($0) } ?? "" } }
    @Published var customizing = false
    @Published private(set) var avatarKey = ""          // redraws the picture when the numbers change
    /// The editable picture: a picture from a newer app version can't be edited here, customizing starts from a fresh one.
    private let fresh = Avatar.random()

    init(name: String, avatar: [String: Any]?) {
        self.name = name
        self.avatar = avatar
        avatarKey = avatar.map { V2.json($0) } ?? ""
    }

    var editable: [String: Any] { Avatar.known(avatar) ? avatar! : fresh }
    var shape: String { editable["s"] as? String ?? "cube" }
    var colors: Int { editable["c"] as? Int ?? 0 }
    var canSave: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    func shuffle() { avatar = Avatar.random() }
    func setShape(_ s: String) { avatar = Avatar.with(editable, s: s) }
    func setColors(_ c: Int) { avatar = Avatar.with(editable, c: c) }
    func turn() { avatar = Avatar.turned(editable) }
    /// Typing past the limit stops at 40 (Android's field takes the first 40).
    func clampName() {
        let t = V2.profileName(name, previous: "")
        if name.utf16.count > V2.maxName { name = t }
    }
}

/// Name + picture: Shuffle draws a new random picture; Customize shows the shape (a segmented control: one choice of
/// a small fixed set, HIG "Segmented controls") and the colours (circular swatches like System Settings › Appearance ›
/// Accent color), and "Turn the colours". Same options and words as Android.
struct IdentityEditor: View {
    @ObservedObject var model: IdentityModel

    static let shapes: [(String, String)] = [("cube", "Cube"), ("drop", "Drop"), ("dots", "Dots"), ("rings", "Rings"), ("letter", "Letter")]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 16) {
                Image(nsImage: Avatar.image(model.avatar, name: model.name, size: 88))
                    .id(model.avatarKey + Avatar.initial(model.name))
                    .accessibilityLabel("Your picture")
                VStack(alignment: .leading, spacing: 6) {
                    Button("Shuffle") { model.shuffle() }
                    Button(model.customizing ? "Done" : "Customize") { withAnimation { model.customizing.toggle() } }
                        .buttonStyle(.link)
                }
            }
            if model.customizing {
                VStack(alignment: .leading, spacing: 10) {
                    Picker("Shape", selection: Binding(get: { model.shape }, set: { model.setShape($0) })) {
                        ForEach(Self.shapes, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    HStack(spacing: 8) {
                        ForEach(0..<Avatar.V1.palettes.count, id: \.self) { i in
                            let selected = model.colors == i
                            Button { model.setColors(i) } label: {
                                Circle().fill(Color(cgColor: Avatar.rgb(Avatar.V1.palettes[i].0)))
                                    .frame(width: 22, height: 22)
                                    .overlay(Circle().strokeBorder(Color.primary, lineWidth: selected ? 2.5 : 0))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Colour \(i + 1)")
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                        }
                    }
                    Button("Turn the colours") { model.turn() }
                }
                .transition(.opacity)
            }
            TextField("Your name", text: $model.name)
                .textFieldStyle(.roundedBorder)
                .onChange(of: model.name) { model.clampName() }
            Text("Only the people you share with see your name and picture. They travel end-to-end encrypted and are never posted publicly.")
                .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// The editor as a sheet (HIG "Sheets": a focused task that returns to its parent): title, editor, the note on when
/// the wearer sees it, Cancel and Save (disabled while the name is blank, like Android).
struct IdentitySheet: View {
    @ObservedObject var model: IdentityModel
    let wearer: String
    let onSave: (String, [String: Any]?) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(wearer.isEmpty ? "How You Appear" : "How You Appear to \(wearer)").font(.headline)
            IdentityEditor(model: model)
            Text("The person you follow sees the change as soon as it reaches them.")
                .font(.callout).foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel, action: onCancel).keyboardShortcut(.cancelAction)
                Button("Save") { onSave(model.name, model.avatar) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!model.canSave)
            }
        }
        .padding(20)
        .frame(width: 400)
    }
}

/// The Settings row: this Mac's picture and name, and Edit… which opens the sheet.
struct IdentityRow: View {
    let name: String
    let avatar: [String: Any]?
    let wearer: String
    let onSave: (String, [String: Any]?) -> Void
    @State private var editing: IdentityModel?

    var body: some View {
        LabeledContent {
            Button("Edit…") { editing = IdentityModel(name: name, avatar: avatar) }
        } label: {
            HStack(spacing: 10) {
                Image(nsImage: Avatar.image(avatar, name: name, size: 32))
                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                    Text(wearer.isEmpty ? "How you appear to the wearer" : "How you appear to \(wearer)")
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
        }
        .sheet(item: $editing) { m in
            IdentitySheet(model: m, wearer: wearer, onSave: { n, a in onSave(n, a); editing = nil }, onCancel: { editing = nil })
        }
    }
}

extension IdentityModel: Identifiable { nonisolated var id: ObjectIdentifier { ObjectIdentifier(self) } }

// MARK: - self-test and snapshots

enum IdentitySelfTest {
    /// `Sukrara -identityselftest [-vectors <dir>]`: profile editing against spikes/nostr/identity/profile-v1.json,
    /// the cases Android's ProfileFixtureTest runs (names, v1 normalisation, editor buttons, wire avatars, update body).
    static func run() -> Bool {
        let dir = UserDefaults.standard.string(forKey: "vectors") ?? "../nostr/identity"
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: "\(dir)/profile-v1.json")),
              let f = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            print("self-test FAILED: profile-v1.json not found in \(dir) (run from spikes/menubar or pass -vectors)"); return false
        }
        var ok = true
        func check(_ name: String, _ cond: Bool, _ detail: String = "") {
            print("\(cond ? "OK" : "FAILED"): \(name)\(cond || detail.isEmpty ? "" : " — \(detail)")"); ok = ok && cond
        }
        func same(_ a: Any?, _ b: Any?) -> Bool { V2.json(a ?? NSNull()) == V2.json(b ?? NSNull()) }
        for c in f["names"] as? [[String: Any]] ?? [] {
            let got = V2.profileName(c["typed"] as! String, previous: c["previous"] as! String)
            check("name \(V2.json(c["typed"]!))", got == c["name"] as? String, got)
        }
        for c in f["v1"] as? [[String: Any]] ?? [] {
            let got = Avatar.v1(c: c["c"] as! Int, s: c["s"] as! String, a: c["a"] as! Int, n: (c["n"] as! NSNumber).int64Value)
            check("v1 \(V2.json(c["avatar"]!))", same(got, c["avatar"]), V2.json(got))
        }
        for c in f["ops"] as? [[String: Any]] ?? [] {
            let from = c["from"] as! [String: Any]
            let got: [String: Any] = switch c["op"] as! String {
            case "turn": Avatar.turned(from)
            case "shape": Avatar.with(from, s: c["value"] as? String)
            default: Avatar.with(from, c: c["value"] as? Int)
            }
            check("editor \(c["op"]!)", same(got, c["avatar"]), V2.json(got))
        }
        for c in f["wire"] as? [[String: Any]] ?? [] {
            let got = Avatar.of(c["in"])
            check("wire \(V2.json(c["in"]!).prefix(40))", same(got, c["avatar"] is NSNull ? nil : c["avatar"]), got.map { V2.json($0) } ?? "nil")
        }
        for c in f["update"] as? [[String: Any]] ?? [] {
            let got = V2.profileUpdate(name: c["name"] as! String, avatar: c["avatar"] as? [String: Any])
            check("update body for \(c["name"]!)", same(got, c["body"]), V2.json(got))
        }
        return ok
    }
}

/// `-identitySnapshot <dir>`: renders the identity editor (closed and customizing), the Settings row, the sound
/// picker and the panel's dimmed Add Treatment with its reason to PNGs in invisible windows: synthetic data, no app
/// state, no Keychain, no network.
@MainActor enum IdentitySnapshot {
    static func run(dir: String) {
        let app = NSApplication.shared
        app.setActivationPolicy(.prohibited)
        let avatar: [String: Any] = ["v": 1, "c": 5, "s": "drop", "a": 45, "n": 7]
        let closed = IdentityModel(name: "Ana's MacBook", avatar: avatar)
        let open = IdentityModel(name: "Ana's MacBook", avatar: avatar)
        open.customizing = true
        func panel(_ reason: String) -> PanelModel {
            let m = PanelModel()
            m.following = true
            m.canLog = false
            m.logBlocked = reason
            return m
        }
        let shots: [(String, AnyView)] = [
            ("identity-sheet", AnyView(IdentitySheet(model: closed, wearer: "Marta", onSave: { _, _ in }, onCancel: {}))),
            ("identity-customize", AnyView(IdentitySheet(model: open, wearer: "Marta", onSave: { _, _ in }, onCancel: {}))),
            ("identity-row", AnyView(Form { Section { IdentityRow(name: "Ana's MacBook", avatar: avatar, wearer: "Marta", onSave: { _, _ in }) } }
                .formStyle(.grouped).frame(width: 480))),
            ("panel-add-treatment-not-allowed", AnyView(PanelView(model: panel(TreatmentPaths.Blocked.notWriter.text(who: "Marta"))))),
            ("panel-add-treatment-waiting", AnyView(PanelView(model: panel(TreatmentPaths.Blocked.waiting.text(who: "Marta"))))),
            ("sound-picker", AnyView(SoundPickerList(kind: .low, selection: .constant("kart"), packDefault: "quest", onPlay: { _ in })
                .frame(width: 320))),
        ]
        var windows: [(NSWindow, NSView, String)] = []
        for (name, view) in shots {
            for dark in [false, true] {
                let host = NSHostingView(rootView: view.background(Color(nsColor: .windowBackgroundColor)))
                host.frame = NSRect(origin: .zero, size: host.fittingSize)
                let w = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
                w.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
                w.contentView = host
                w.alphaValue = 0
                w.orderFrontRegardless()
                windows.append((w, host, "\(dir)/\(name)-\(dark ? "dark" : "light").png"))
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            for (w, host, out) in windows {
                host.frame = NSRect(origin: .zero, size: host.fittingSize)
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
