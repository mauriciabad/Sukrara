import AppKit

/// `GlucoseBar -preview out.png` renders what the menubar item looks like for a range of rates,
/// on light and dark backgrounds, so the arrow can be eyeballed without a live source.
enum Preview {
    static func render(to path: String) {
        let rates: [Double] = [-4, -3, -2.5, -2, -1.5, -1, -0.5, -0.2, 0, 0.2, 0.5, 1, 1.5, 2, 2.5, 3, 4]
        let values: [Double] = [58, 72, 88, 104, 110, 120, 126, 131, 133, 135, 140, 150, 168, 190, 214, 240, 270]
        let cell = NSSize(width: 64, height: 24), scale: CGFloat = 3
        let size = NSSize(width: cell.width * CGFloat(rates.count), height: cell.height * 2 + 14)
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = size
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        let th = Thresholds()
        for (row, dark) in [false, true].enumerated() {
            let fg: NSColor = dark ? .white : .black
            let y = size.height - CGFloat(row + 1) * cell.height
            (dark ? NSColor(white: 0.12, alpha: 1) : NSColor(white: 0.93, alpha: 1)).setFill()
            NSRect(x: 0, y: y, width: size.width, height: cell.height).fill()
            for (i, rate) in rates.enumerated() {
                let x = CGFloat(i) * cell.width + 6
                let color = th.color(for: values[i]) ?? fg
                let s = NSAttributedString(string: GlucoseUnit.mgdl.format(values[i]), attributes: [
                    .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
                    .foregroundColor: color,
                ])
                s.draw(at: NSPoint(x: x, y: y + 4))
                TrendArrow.image(rate: rate, size: 15, color: color)
                    .draw(in: NSRect(x: x + s.size().width + 2, y: y + 4.5, width: 15, height: 15))
            }
        }
        for (i, rate) in rates.enumerated() {
            NSAttributedString(string: String(format: "%+.1f", rate), attributes: [.font: NSFont.systemFont(ofSize: 9)])
                .draw(at: NSPoint(x: CGFloat(i) * cell.width + 14, y: 1))
        }
        NSGraphicsContext.restoreGraphicsState()
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
        print("wrote", path)
    }
}
