// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreImage

/// "Follow someone…": paste (or open via `cgmshare:` URL / QR image) an invite link, name this Mac, then a small
/// window shows the 4-digit match code the wearer's phone shows too, until the request is approved.
@MainActor enum FollowUI {
    static var computerName: String { Host.current().localizedName ?? "Mac" }

    static func ask(prefill: String?) -> (link: String, name: String)? {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Follow someone's glucose"
        alert.informativeText = "Paste the invite link they shared with you (it starts with cgmshare:). "
            + "Their phone will ask them to allow this Mac."
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 82))
        let link = NSTextField(frame: NSRect(x: 0, y: 52, width: 340, height: 24))
        link.placeholderString = "cgmshare:join?i=…"
        link.stringValue = prefill ?? (NSPasteboard.general.string(forType: .string).flatMap {
            $0.hasPrefix("cgmshare:") ? $0 : nil } ?? "")
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
        return det.features(in: img).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first { $0.hasPrefix("cgmshare:") }
    }

    /// `GlucoseBar -qrselftest`: encode an invite as a QR PNG with CoreImage, decode it back with `decodeQR`.
    static func qrSelfTest() -> Bool {
        let link = "cgmshare:join?i=npub1test&t=AAAAAAAAAAAAAAAAAAAAAA&x=1792197843&r=wss%3A%2F%2Fnos.lol%2Cwss%3A%2F%2Fnostr.mom"
        guard let f = CIFilter(name: "CIQRCodeGenerator") else { return false }
        f.setValue(Data(link.utf8), forKey: "inputMessage")
        guard let img = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return false }
        let rep = NSBitmapImageRep(ciImage: img)
        let url = URL(fileURLWithPath: NSTemporaryDirectory() + "glucosebar-qr-selftest.png")
        try? rep.representation(using: .png, properties: [:])?.write(to: url)
        let back = decodeQR(url)
        print(back == link ? "qr self-test OK" : "qr self-test FAILED: \(back ?? "nil")")
        return back == link
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
