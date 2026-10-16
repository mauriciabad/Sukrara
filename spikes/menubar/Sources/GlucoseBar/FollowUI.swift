// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreImage
import NostrSDK

/// "Follow someone…": paste (or open via `cgmshare:` URL / QR image) a Sukrara invite link (https or cgmshare:), name this Mac, then a small
/// window shows the 4-digit match code the wearer's phone shows too, until the request is approved.
@MainActor enum FollowUI {
    static var computerName: String { Host.current().localizedName ?? "Mac" }

    static func ask(prefill: String?) -> (link: String, name: String)? {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Follow someone's glucose"
        alert.informativeText = "Paste the invite link they shared with you (https://sukrara.mauri.app/join#… or cgmshare:join?…). "
            + "Their phone will ask them to allow this Mac."
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 82))
        let link = NSTextField(frame: NSRect(x: 0, y: 52, width: 340, height: 24))
        link.placeholderString = "https://sukrara.mauri.app/join#i=…"
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

    /// `Sukrara -qrselftest`: encode both invite forms as QR PNGs with CoreImage, decode them back with `decodeQR`,
    /// and check both parse to the same invite.
    static func qrSelfTest() -> Bool {
        let npub = (try? Keys.generate().publicKey().toBech32()) ?? "npub1invalid"
        let query = "i=\(npub)&t=AAAAAAAAAAAAAAAAAAAAAA&x=1792197843&r=wss%3A%2F%2Fnos.lol%2Cwss%3A%2F%2Fnostr.mom"
        var ok = true
        var parsed: [V2.Invite] = []
        for link in ["cgmshare:join?" + query, "https://sukrara.mauri.app/join#" + query] {
            guard let f = CIFilter(name: "CIQRCodeGenerator") else { return false }
            f.setValue(Data(link.utf8), forKey: "inputMessage")
            guard let img = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return false }
            let rep = NSBitmapImageRep(ciImage: img)
            let url = URL(fileURLWithPath: NSTemporaryDirectory() + "sukrara-qr-selftest.png")
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            let back = decodeQR(url)
            let inv = back.flatMap { V2.parseInvite($0) }
            print(back == link && inv != nil ? "qr self-test OK (\(link.prefix(28))…)" : "qr self-test FAILED: \(back ?? "nil")")
            ok = ok && back == link && inv != nil
            if let inv { parsed.append(inv) }
        }
        let same = parsed.count == 2 && parsed[0].i == parsed[1].i && parsed[0].t == parsed[1].t
            && parsed[0].x == parsed[1].x && parsed[0].r == parsed[1].r && parsed[0].r.count == 2
        print(same ? "invite forms parse identically OK" : "invite forms parse identically FAILED")
        // a pasted link with whitespace, and a non-invite https link
        let pasted = V2.parseInvite("  https://sukrara.mauri.app/join/#" + query + "\n") != nil
        let foreign = V2.parseInvite("https://example.com/join#" + query) == nil
        print(pasted && foreign ? "paste / foreign-link checks OK" : "paste / foreign-link checks FAILED")
        return ok && same && pasted && foreign
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

    init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 210), styleMask: [.titled, .closable],
                          backing: .buffered, defer: false)
        window.title = "Follow someone"
        window.isReleasedWhenClosed = false
        window.level = .floating
        let v = FilledView(frame: window.contentLayoutRect)
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.frame = NSRect(x: 20, y: 168, width: 320, height: 22)
        codeLabel.font = .monospacedDigitSystemFont(ofSize: 52, weight: .bold)
        codeLabel.alignment = .center
        codeLabel.frame = NSRect(x: 20, y: 92, width: 320, height: 66)
        detail.frame = NSRect(x: 20, y: 14, width: 320, height: 70)
        detail.textColor = .secondaryLabelColor
        [title, codeLabel, detail].forEach(v.addSubview)
        window.contentView = v
    }

    func show(_ phase: FollowerV2.Phase, name: String) {
        switch phase {
        case .waiting(let code):
            title.stringValue = "Waiting for approval…"
            codeLabel.stringValue = code
            codeLabel.textColor = .labelColor
            detail.stringValue = "Their phone shows a request from \"\(name)\". Check that it shows the same code "
                + "\(code), then they tap Allow. You can close this window; the menu shows the status."
        case .active(let epoch):
            title.stringValue = "Approved ✓"
            codeLabel.stringValue = "✓"
            codeLabel.textColor = .systemGreen
            detail.stringValue = "Receiving glucose (key epoch \(epoch)). The menubar shows the latest reading."
        case .revoked:
            title.stringValue = "Access removed"
            codeLabel.stringValue = "—"
            codeLabel.textColor = .secondaryLabelColor
            detail.stringValue = "The wearer stopped sharing with this Mac."
        }
        if !window.isVisible {
            window.center()
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
        }
        // Evidence aid: `-pairSnapshot out.png` renders the window content (no screen-recording permission needed).
        if let path = UserDefaults.standard.string(forKey: "pairSnapshot") {
            let tag = switch phase { case .waiting: "waiting"; case .active: "approved"; case .revoked: "revoked" }
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
