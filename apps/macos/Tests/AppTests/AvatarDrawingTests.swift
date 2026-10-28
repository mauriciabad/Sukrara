// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Foundation
import SukraraShare
import SukraraTesting
import Testing

@testable import SukraraApp

/// The v1 picture as PROTOCOL-v2 "Profiles" pins it (Android's AvatarDrawingTest draws the same numbers).
struct AvatarDrawingTests {
    @Test func dotsMatchTheContractVectors() throws {
        let data = try Data(contentsOf: Contracts.url("protocol/identity/avatar-v1.json"))
        let vectors = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try #require(vectors["dots"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        for c in cases {
            let seed = (c["seed"] as! NSNumber).int64Value
            let marks = AvatarDrawing.of(.v1(c: 0, s: "dots", a: 0, n: seed), name: "A").marks
            let expected = c["circles"] as! [[NSNumber]]
            #expect(marks.count == expected.count)
            for (e, m) in zip(expected, marks) {
                guard case .circle(let p, let r, let alpha) = m else {
                    Issue.record("not a circle")
                    continue
                }
                #expect(abs(p.x - e[0].doubleValue) < 1e-5)
                #expect(abs(p.y - e[1].doubleValue) < 1e-5)
                #expect(abs(r - e[2].doubleValue) < 1e-5)
                #expect(alpha == e[3].intValue)
            }
        }
    }

    @Test func gradientRunsAlongTheAngleWithThePalette() {
        guard
            case .gradient(let a, let b, let from, let to) = AvatarDrawing.of(
                .v1(c: 2, s: "cube", a: 0, n: 1), name: "A"
            ).background
        else {
            Issue.record("no gradient")
            return
        }
        #expect(a == 0xFFFF_8A65 && b == 0xFFBF_360C)
        #expect(abs(from.x) < 1e-9 && abs(from.y - 0.5) < 1e-9 && abs(to.x - 1) < 1e-9)
        guard
            case .gradient(_, _, let f2, let t2) = AvatarDrawing.of(.v1(c: 0, s: "cube", a: 90, n: 1), name: "A")
                .background
        else {
            Issue.record("no gradient")
            return
        }
        #expect(abs(f2.y) < 1e-9 && abs(t2.y - 1) < 1e-9)
    }

    @Test func shapes() {
        let alphas = AvatarDrawing.of(.v1(c: 0, s: "cube", a: 0, n: 0), name: "A").marks.map { m -> Int in
            if case .polygon(_, let a) = m { return a }
            return -1
        }
        #expect(alphas == [235, 170, 110])
        let drop = AvatarDrawing.of(.v1(c: 0, s: "drop", a: 0, n: 0), name: "A").marks
        if case .circle(let p, let r, _) = drop[0] { #expect(abs(p.y - 0.62) < 1e-9 && abs(r - 0.2) < 1e-9) }
        if case .polygon(let pts, _) = drop[1] { #expect(abs(pts[1].y - 0.2) < 1e-9) }
        let rings = AvatarDrawing.of(.v1(c: 0, s: "rings", a: 0, n: 0), name: "A").marks.compactMap {
            m -> (Double, Int)? in
            if case .ring(_, let r, let w, let a) = m, w == 0.03 { return ((r * 100).rounded() / 100, a) }
            return nil
        }
        #expect(rings.map(\.0) == [0.36, 0.27, 0.18, 0.09])
        #expect(rings.map(\.1) == [220, 180, 140, 100])
        #expect(
            AvatarDrawing.of(.v1(c: 0, s: "letter", a: 0, n: 0), name: " ñandú").marks == [.letter("Ñ", alpha: 240)])
    }

    @Test func unknownVersionOrNoneDrawsTheGreyInitial() {
        let newer = Avatar(wire: ["v": 2, "x": 1])
        for a in [newer, nil] {
            let d = AvatarDrawing.of(a, name: "maria")
            #expect(d.background == .solid(0xFF78_909C))
            #expect(d.marks == [.letter("M", alpha: 240)])
        }
        #expect(AvatarDrawing.of(nil, name: "  ").marks == [.letter("?", alpha: 240)])
    }

    @Test @MainActor func drawsAPicture() {
        let image = AvatarDrawing.of(.v1(c: 5, s: "dots", a: 45, n: 7), name: "A").image(size: 40)
        let rep = image.cgImage(forProposedRect: nil, context: nil, hints: nil)
        #expect(rep?.width ?? 0 >= 40)
    }

    @Test @MainActor func editorOpsKeepThePictureEditable() {
        let draft = IdentityDraft(name: "Ana", avatar: Avatar(wire: ["v": 9]))
        #expect(draft.editable.isKnown)  // a newer picture: customizing starts from a fresh one
        draft.setShape("rings")
        #expect(draft.shape == "rings" && draft.avatar?.isKnown == true)
        draft.setColors(3)
        #expect(draft.colors == 3 && draft.shape == "rings")
        draft.name = String(repeating: "a", count: 50)
        draft.clampName()
        #expect(draft.name.count == 40)
        draft.name = "  "
        #expect(!draft.canSave)
    }
}
