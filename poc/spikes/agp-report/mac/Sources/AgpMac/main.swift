// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

//   swift run AgpMac selftest ../fixtures               the Swift engine vs every fixture's `expect`
//   swift run AgpMac paint <page.json> <out.pdf> [out.png]   the display list (from `gradlew run --args=render`) → PDF/PNG

// MARK: selftest

func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }

func check(_ path: String) -> [String] {
    let o = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [String: Any]
    let name = o["name"] as! String
    let fp = (o["first"] as! String).split(separator: "-").map { Int($0)! }
    let period = Period(zone: TimeZone(identifier: o["zone"] as! String)!, first: DateComponents(year: fp[0], month: fp[1], day: fp[2]),
                        days: o["days"] as! Int)
    let bj = o["bands"] as! [String: Int]
    let bands = Bands(veryLow: bj["veryLow"]!, low: bj["low"]!, tight: bj["tight"]!, high: bj["high"]!, veryHigh: bj["veryHigh"]!)
    let oj = o["options"] as! [String: Any]
    let opts = AgpOptions(gridS: Int64(num(oj["gridS"])!), binMinutes: Int(num(oj["binMinutes"])!), maxGapS: Int64(num(oj["maxGapS"])!))
    let readings = (o["readings"] as! [[NSNumber]]).map { Reading(t: $0[0].int64Value, sgv: $0[1].intValue) }
    let e = o["expect"] as! [String: Any]
    let r = Agp.report(readings, period, bands, opts)

    var bad: [String] = []
    func near(_ what: String, _ got: Double?, _ want: Any?) {
        let w = num(want)
        if got == nil && w == nil { return }
        guard let g = got, let w = w, abs(g - w) <= 1e-3 else { bad.append("\(name) \(what): \(String(describing: got)) ≠ \(String(describing: want))"); return }
    }
    let em = e["metrics"] as! [String: Any]
    for (k, i) in ["veryLow": 0, "low": 1, "tight": 2, "target": 3, "high": 4, "veryHigh": 5] { near(k, r.metrics.shares[i], em[k]) }
    near("active", r.metrics.active, em["active"]); near("mean", r.metrics.mean, em["mean"]); near("sd", r.metrics.sd, em["sd"])
    near("cv", r.metrics.cv, em["cv"]); near("gmi", r.metrics.gmi, em["gmi"]); near("readings", Double(r.metrics.readings), em["readings"])
    let es = e["sufficiency"] as! [String: Any]
    near("hours", r.sufficiency.hours, es["hours"]); near("daysWithData", Double(r.sufficiency.daysWithData), es["daysWithData"])
    near("best7Active", r.sufficiency.best7Active, es["best7Active"])
    for (k, v) in ["report": r.sufficiency.report, "profile": r.sufficiency.profile, "consensus": r.sufficiency.consensus] where (es[k] as! Bool) != v {
        bad.append("\(name) \(k): \(v)")
    }
    for (key, bins) in [("rawProfile", r.raw), ("profile", r.profile)] {
        let want = e[key] as! [[Any]]
        for (i, b) in bins.enumerated() {
            near("\(key)[\(i)].n", Double(b.n), want[i][0])
            for k in 0..<5 { near("\(key)[\(i)][\(k)]", b.p[k], want[i][k + 1] is NSNull ? nil : want[i][k + 1]) }
        }
    }
    let ed = e["days"] as! [[String: Any]]
    for (i, d) in r.days.enumerated() {
        if d.date != ed[i]["date"] as! String { bad.append("\(name) day \(i): \(d.date)") }
        near("day \(d.date) hours", Double(d.to - d.from) / 3600, ed[i]["hours"]); near("day \(d.date) active", d.active, ed[i]["active"])
        near("day \(d.date) inRange", d.inRange, ed[i]["inRange"] is NSNull ? nil : ed[i]["inRange"])
        near("day \(d.date) segments", Double(d.segments), ed[i]["segments"]); near("day \(d.date) points", Double(d.points), ed[i]["points"])
        near("day \(d.date) noon", d.noon, ed[i]["noon"])
    }
    print("\(name): \(bad.isEmpty ? "ok" : "\(bad.count) differences") · mean \(String(format: "%.2f", r.metrics.mean)) · p50 at 08:30 \(String(format: "%.2f", r.profile[8].p[2] ?? .nan))")
    return bad
}

// MARK: paint

func color(_ hex: String) -> CGColor {
    let v = UInt32(hex.dropFirst(), radix: 16)!
    return CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255,
                   alpha: CGFloat(v >> 24) / 255)
}

/// Paints the display list into [ctx], already flipped to y-down points (the same ops the Java2D painter draws).
func paint(_ page: [String: Any], _ ctx: CGContext) {
    for op in page["ops"] as! [[String: Any]] {
        func d(_ k: String) -> CGFloat { CGFloat(num(op[k]) ?? 0) }
        switch op["k"] as! String {
        case "rect":
            ctx.setFillColor(color(op["fill"] as! String))
            let r = CGRect(x: d("x"), y: d("y"), width: d("w"), height: d("h"))
            if d("r") > 0 { ctx.addPath(CGPath(roundedRect: r, cornerWidth: d("r"), cornerHeight: d("r"), transform: nil)); ctx.fillPath() } else { ctx.fill(r) }
        case "line":
            ctx.setStrokeColor(color(op["color"] as! String)); ctx.setLineWidth(d("width"))
            ctx.setLineDash(phase: 0, lengths: d("dash") > 0 ? [d("dash"), d("dash")] : [])
            ctx.setLineCap(.butt)
            ctx.strokeLineSegments(between: [CGPoint(x: d("x1"), y: d("y1")), CGPoint(x: d("x2"), y: d("y2"))])
            ctx.setLineDash(phase: 0, lengths: [])
        case "poly":
            let p = (op["pts"] as! [NSNumber]).map { CGFloat($0.doubleValue) }
            let path = CGMutablePath()
            path.move(to: CGPoint(x: p[0], y: p[1]))
            for i in stride(from: 2, to: p.count, by: 2) { path.addLine(to: CGPoint(x: p[i], y: p[i + 1])) }
            if op["closed"] as! Bool { path.closeSubpath() }
            if let f = op["fill"] as? String { ctx.setFillColor(color(f)); ctx.addPath(path); ctx.fillPath() }
            if let s = op["stroke"] as? String {
                ctx.setStrokeColor(color(s)); ctx.setLineWidth(d("width")); ctx.setLineCap(.round); ctx.setLineJoin(.round)
                ctx.addPath(path); ctx.strokePath()
            }
        case "text":
            let font = CTFontCreateWithName((op["bold"] as! Bool ? "Helvetica-Bold" : "Helvetica") as CFString, d("size"), nil)
            let s = NSAttributedString(string: op["text"] as! String, attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font,
                NSAttributedString.Key(kCTForegroundColorAttributeName as String): color(op["color"] as! String)])
            let line = CTLineCreateWithAttributedString(s)
            let w = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
            let align = (op["align"] as! NSNumber).intValue
            let x = align == 0 ? d("x") - w / 2 : align == 1 ? d("x") - w : d("x")
            ctx.saveGState()
            ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
            ctx.textPosition = CGPoint(x: x, y: d("y"))
            CTLineDraw(line, ctx)
            ctx.restoreGState()
        case "clip":
            ctx.saveGState(); ctx.clip(to: CGRect(x: d("x"), y: d("y"), width: d("w"), height: d("h")))
        case "unclip":
            ctx.restoreGState()
        default: break
        }
    }
}

func paintFiles(_ json: String, _ pdf: String, _ png: String?) {
    let page = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: json))) as! [String: Any]
    let w = CGFloat(num(page["w"])!), h = CGFloat(num(page["h"])!)
    var box = CGRect(x: 0, y: 0, width: w, height: h)
    let meta = [kCGPDFContextTitle: "Glucose report", kCGPDFContextCreator: "Sukrara (investigation 26 PoC)"] as CFDictionary
    let ctx = CGContext(URL(fileURLWithPath: pdf) as CFURL, mediaBox: &box, meta)!
    ctx.beginPDFPage(nil)
    ctx.translateBy(x: 0, y: h); ctx.scaleBy(x: 1, y: -1)
    paint(page, ctx)
    ctx.endPDFPage(); ctx.closePDF()
    if let png {
        let scale: CGFloat = 2
        let bm = CGContext(data: nil, width: Int(w * scale), height: Int(h * scale), bitsPerComponent: 8, bytesPerRow: 0,
                           space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        bm.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)); bm.fill(CGRect(x: 0, y: 0, width: w * scale, height: h * scale))
        bm.translateBy(x: 0, y: h * scale); bm.scaleBy(x: scale, y: -scale)
        paint(page, bm)
        let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: png) as CFURL, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, bm.makeImage()!, nil)
        CGImageDestinationFinalize(dest)
    }
    print("\(json) → \(pdf)\(png.map { " + \($0)" } ?? "")")
}

let args = CommandLine.arguments
switch args.count > 1 ? args[1] : "" {
case "selftest":
    let dir = args.count > 2 ? args[2] : "../fixtures"
    let files = try! FileManager.default.contentsOfDirectory(atPath: dir).filter { $0.hasSuffix(".json") }.sorted()
    let bad = files.flatMap { check("\(dir)/\($0)") }
    bad.prefix(20).forEach { print("  " + $0) }
    exit(bad.isEmpty && !files.isEmpty ? 0 : 1)
case "paint":
    paintFiles(args[2], args[3], args.count > 4 ? args[4] : nil)
default:
    print("usage: AgpMac selftest <fixtures dir> | paint <page.json> <out.pdf> [out.png]"); exit(2)
}
