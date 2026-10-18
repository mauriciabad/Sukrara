// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreImage
import NostrSDK

/// "Follow someone…": paste (or open via `sukrara:` URL / QR image) a Sukrara invite link, name this Mac, then a small
/// window shows the 4-digit match code the wearer's phone shows too, until the request is approved.
@MainActor enum FollowUI {
    static var computerName: String { Host.current().localizedName ?? "Mac" }

    static func ask(prefill: String?) -> (link: String, name: String)? {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Follow someone's glucose"
        alert.informativeText = "Paste the invite link they shared with you (https://sukrara.mauri.app/join#…). "
            + "Their phone will ask them to allow this Mac."
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 82))
        let link = NSTextField(frame: NSRect(x: 0, y: 52, width: 340, height: 24))
        link.placeholderString = "https://sukrara.mauri.app/join#…"
        link.stringValue = prefill ?? (NSPasteboard.general.string(forType: .string).flatMap {
            V2.isInvite($0) ? $0.trimmingCharacters(in: .whitespacesAndNewlines) : nil } ?? "")
        let label = NSTextField(labelWithString: "Name for this Mac")
        label.frame = NSRect(x: 0, y: 26, width: 340, height: 17)
        let name = NSTextField(frame: NSRect(x: 0, y: 0, width: 340, height: 24))
        name.stringValue = computerName
        [link, label, name].forEach(view.addSubview)
        alert.accessoryView = view
        alert.addButton(withTitle: "Ask to follow")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = link
        guard alert.runModal() == .alertFirstButtonReturn else { return nil }
        let n = name.stringValue.trimmingCharacters(in: .whitespaces)
        return (link.stringValue, n.isEmpty ? computerName : n)
    }

    /// Pick a screenshot/photo of the share QR and decode it (CoreImage, no extra dependency).
    static func linkFromQRImage() -> String? {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.message = "Choose a screenshot or photo of the share QR code"
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        return decodeQR(url)
    }

    static func decodeQR(_ url: URL) -> String? {
        guard let img = CIImage(contentsOf: url),
              let det = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        else { return nil }
        return det.features(in: img).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first { V2.isInvite($0) }
    }

    /// `Sukrara -qrselftest [-vectors <dir>]`: a v3 link survives QR encode → decode, old link forms are rejected, and
    /// the invite key + avatar "dots" match the Python reference (spikes/nostr/identity/vectors.json, avatar-v1.json).
    static func qrSelfTest() -> Bool {
        let dir = UserDefaults.standard.string(forKey: "vectors") ?? "../nostr/identity"
        func load(_ f: String) -> [String: Any]? {
            (try? Data(contentsOf: URL(fileURLWithPath: "\(dir)/\(f)"))).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        }
        guard let v = load("vectors.json"), let inv = v["invite"] as? [String: String], let link = inv["short_link"],
              let av = load("avatar-v1.json"), let dots = av["dots"] as? [[String: Any]] else {
            print("self-test FAILED: vectors not found in \(dir) (run from spikes/menubar or pass -vectors)"); return false
        }
        var ok = true
        func check(_ name: String, _ cond: Bool) { print("\(cond ? "OK" : "FAILED"): \(name)"); ok = ok && cond }
        // QR round trip
        if let f = CIFilter(name: "CIQRCodeGenerator") {
            f.setValue(Data(link.utf8), forKey: "inputMessage")
            if let img = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) {
                let url = URL(fileURLWithPath: NSTemporaryDirectory() + "sukrara-qr-selftest.png")
                try? NSBitmapImageRep(ciImage: img).representation(using: .png, properties: [:])?.write(to: url)
                check("QR round trip of the 63-char link", decodeQR(url) == link)
            }
        }
        let l = V2.parseLink(link)
        check("link → fingerprint + secret", l != nil && inv["i_pub"]!.hasPrefix(l!.fp) && V2.hex(l!.secret) == inv["secret"])
        check("invite key = Python", l.flatMap { V2.inviteKeys($0)?.publicKey().toHex() } == inv["invite_key_pub"])
        check("sukrara:join# form", V2.parseLink(V2.appScheme + link.dropFirst(V2.inviteBase.count)) != nil)
        let q = "i=npub1sx93muthatzjzm9mc9t9cq7fsczq2n76hjwk43lk6x6ec7f7xzyshsma7v&t=pEzb2FHGbVDJllnfcXW-fg&x=1792238661&r=wss%3A%2F%2Fnos.lol"
        check("old and foreign forms rejected", ["https://sukrara.mauri.app/join#" + q, "cgmshare:join?" + q,
            link.replacingOccurrences(of: "sukrara.mauri.app", with: "evil.example"), String(link.dropLast())].allSatisfy { V2.parseLink($0) == nil })
        var same = true
        for d in dots {
            let got = Avatar.V1.dots(d["seed"] as! Int), want = d["circles"] as! [[Double]]
            for (g, w) in zip(got, want) {
                same = same && abs(Double(g.0) - w[0]) < 1e-5 && abs(Double(g.1) - w[1]) < 1e-5 && abs(Double(g.2) - w[2]) < 1e-5
                    && abs(Double(g.3) - w[3]) <= 1
            }
        }
        check("avatar v1 dots = Python", same)
        return ok
    }

    static func error(_ text: String) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Can't follow"
        a.informativeText = text
        a.runModal()
    }
}

/// Small floating window with the match code while waiting for approval.
@MainActor final class PairingWindow {
    private let window: NSWindow
    private let title = NSTextField(labelWithString: "")
    private let codeLabel = NSTextField(labelWithString: "")
    private let detail = NSTextField(wrappingLabelWithString: "")
    private let picture = NSImageView()

    init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 210), styleMask: [.titled, .closable],
                          backing: .buffered, defer: false)
        window.title = "Follow someone"
        window.isReleasedWhenClosed = false
        window.level = .floating
        let v = FilledView(frame: window.contentLayoutRect)
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        picture.frame = NSRect(x: 20, y: 160, width: 40, height: 40)
        title.frame = NSRect(x: 68, y: 168, width: 272, height: 22)
        codeLabel.font = .monospacedDigitSystemFont(ofSize: 52, weight: .bold)
        codeLabel.alignment = .center
        codeLabel.frame = NSRect(x: 20, y: 92, width: 320, height: 66)
        detail.frame = NSRect(x: 20, y: 14, width: 320, height: 70)
        detail.textColor = .secondaryLabelColor
        [picture, title, codeLabel, detail].forEach(v.addSubview)
        window.contentView = v
    }

    func show(_ phase: FollowerV2.Phase, name: String, wearer: (name: String, avatar: [String: Any]?)) {
        let who = wearer.name.isEmpty ? "the wearer" : wearer.name
        window.title = "Follow \(wearer.name.isEmpty ? "someone" : wearer.name)"
        picture.image = Avatar.image(wearer.avatar, name: wearer.name, size: 40)
        switch phase {
        case .waiting(let code?):
            title.stringValue = "Waiting for \(who)…"
            codeLabel.stringValue = code
            codeLabel.textColor = .labelColor
            detail.stringValue = "This Mac was offered over Bluetooth, so \(who) types this code on their phone to allow it. "
                + "You can close this window; Settings › Following shows the status."
        case .waiting(nil):
            title.stringValue = "Request sent"
            codeLabel.stringValue = "…"
            codeLabel.textColor = .secondaryLabelColor
            detail.stringValue = "\(who) sees a request from \"\(name)\" and taps Allow. You can close this window; "
                + "the menubar shows the readings as soon as it happens."
        case .active:
            title.stringValue = "Approved ✓"
            codeLabel.stringValue = "✓"
            codeLabel.textColor = .systemGreen
            detail.stringValue = "Receiving glucose. The menu bar shows the latest reading."
        case .revoked:
            title.stringValue = "Access removed"
            codeLabel.stringValue = "—"
            codeLabel.textColor = .secondaryLabelColor
            detail.stringValue = "\(who) stopped sharing with this Mac."
        case .ended(let used):
            title.stringValue = used ? "This invite was already used" : "\(who) didn't allow this Mac"
            codeLabel.stringValue = "—"
            codeLabel.textColor = .secondaryLabelColor
            detail.stringValue = used
                ? "Someone opened this link before you. Ask \(who) for a new one, and tell them, in case it wasn't someone they invited."
                : "Nothing was shared with this Mac. If it was a mistake, ask \(who) for a new invite."
        }
        if !window.isVisible {
            window.center()
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
        }
        // Evidence aid: `-pairSnapshot out.png` renders the window content (no screen-recording permission needed).
        if let path = UserDefaults.standard.string(forKey: "pairSnapshot") {
            let tag = switch phase { case .waiting: "waiting"; case .active: "approved"; case .revoked: "revoked"; case .ended: "ended" }
            DispatchQueue.main.async { [window] in
                guard let v = window.contentView, let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { return }
                v.cacheDisplay(in: v.bounds, to: rep)
                try? rep.representation(using: .png, properties: [:])?
                    .write(to: URL(fileURLWithPath: path.replacingOccurrences(of: ".png", with: "-\(tag).png")))
            }
        }
    }

    func close() { window.orderOut(nil) }
}

/// Opaque window background so `-pairSnapshot` PNGs are readable in light and dark mode.
private final class FilledView: NSView {
    override func draw(_ dirtyRect: NSRect) {
        NSColor.windowBackgroundColor.setFill()
        dirtyRect.fill()
    }
}
