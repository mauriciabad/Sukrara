// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.avatar

import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.designsystem.avatar.AvatarDrawing.Background
import app.mauri.sukrara.designsystem.avatar.AvatarDrawing.Mark
import app.mauri.sukrara.protocol.event.Avatar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v1 picture as PROTOCOL-v2 §Profiles pins it (the Mac draws the same numbers). */
class AvatarDrawingTest {
    @Test
    fun dotsMatchTheContractVectors() {
        val vectors = Json.parseToJsonElement(Contracts.file("protocol/identity/avatar-v1.json").readText()) as JsonObject
        for (v in vectors.getValue("dots").jsonArray) {
            val o = v as JsonObject
            val seed = o.getValue("seed").jsonPrimitive.long
            val marks = AvatarDrawing.of(Avatar.v1(0, "dots", 0, seed), "A").marks
            val expected = o.getValue("circles") as JsonArray
            assertEquals(expected.size, marks.size)
            expected.zip(marks).forEach { (e, m) ->
                val (x, y, r, a) = e.jsonArray
                m as Mark.Circle
                assertEquals(x.jsonPrimitive.double, m.center.x, 1e-5)
                assertEquals(y.jsonPrimitive.double, m.center.y, 1e-5)
                assertEquals(r.jsonPrimitive.double, m.r, 1e-5)
                assertEquals(a.jsonPrimitive.int, m.alpha)
            }
        }
    }

    @Test
    fun gradientRunsAlongTheAngleWithThePalette() {
        val bg = AvatarDrawing.of(Avatar.v1(2, "cube", 0, 1), "A").background as Background.Gradient
        assertEquals(0xFFFF8A65, bg.a)
        assertEquals(0xFFBF360C, bg.b)
        assertEquals(0.0, bg.from.x, 1e-9)
        assertEquals(0.5, bg.from.y, 1e-9)
        assertEquals(1.0, bg.to.x, 1e-9)
        val down = AvatarDrawing.of(Avatar.v1(0, "cube", 90, 1), "A").background as Background.Gradient
        assertEquals(0.0, down.from.y, 1e-9)
        assertEquals(1.0, down.to.y, 1e-9)
    }

    @Test
    fun shapes() {
        val cube = AvatarDrawing.of(Avatar.v1(0, "cube", 0, 0), "A").marks
        assertEquals(listOf(235, 170, 110), cube.map { it.alpha })
        val drop = AvatarDrawing.of(Avatar.v1(0, "drop", 0, 0), "A").marks
        val circle = drop.first() as Mark.Circle
        assertEquals(0.62, circle.center.y, 1e-9)
        assertEquals(0.2, circle.r, 1e-9)
        assertEquals(0.2, (drop[1] as Mark.Polygon).points[1].y, 1e-9)
        val rings = AvatarDrawing.of(Avatar.v1(0, "rings", 0, 0), "A").marks.map { it as Mark.Ring }
        assertEquals(listOf(0.36, 0.27, 0.18, 0.09), rings.map { Math.round(it.r * 100) / 100.0 })
        assertEquals(listOf(220, 180, 140, 100), rings.map { it.alpha })
        assertTrue(rings.all { it.width == 0.03 })
        val letter = AvatarDrawing.of(Avatar.v1(0, "letter", 0, 0), " ñandú").marks.single() as Mark.Letter
        assertEquals("Ñ", letter.text)
        assertEquals(240, letter.alpha)
    }

    @Test
    fun unknownVersionOrNoneDrawsTheGreyInitial() {
        val newer = Avatar.of(Json.parseToJsonElement("""{"v":2,"x":1}"""))
        for (a in listOf(newer, null)) {
            val d = AvatarDrawing.of(a, "maria")
            assertEquals(Background.Solid(0xFF78909C), d.background)
            assertEquals("M", (d.marks.single() as Mark.Letter).text)
        }
        assertEquals("?", (AvatarDrawing.of(null, "  ").marks.single() as Mark.Letter).text)
    }
}
