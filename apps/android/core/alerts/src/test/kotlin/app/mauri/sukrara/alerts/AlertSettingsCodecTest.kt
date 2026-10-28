// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.testing.Contracts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AlertSettingsCodecTest {
    /** The JSON block of contracts/alerts/SPEC.md §3: the defaults, written by hand in the spec. */
    private val specJson: String =
        Contracts
            .file("alerts/SPEC.md")
            .readText()
            .substringAfter("## 3. Settings")
            .substringAfter("```json")
            .substringBefore("```")

    @Test fun defaultsAreTheSpecsDefaults() {
        assertEquals(AlertSettings(), AlertSettingsCodec.decode(specJson))
        assertEquals(Json.parseToJsonElement(specJson), Json.parseToJsonElement(AlertSettingsCodec.encode(AlertSettings())))
    }

    @Test fun roundTrips() {
        val s =
            AlertSettings(
                on = false,
                rules = AlertSettings.DEFAULT_RULES + (AlertKind.FALLING_FAST to KindRule(true, 2.5, 0, alarm = true)),
                afterInsulin = QuietRule(false, 90),
                sounds = SoundSettings("arcade", mapOf(AlertKind.LOW to "soft"), shuffle = false),
                actions = NotificationActions(45, 60, 5, 15),
                source = AlertSource.OWN,
            )
        assertEquals(s, AlertSettingsCodec.decode(AlertSettingsCodec.encode(s)))
    }

    /** A newer peer's payload: unknown keys and kinds are ignored, every known field still read. */
    @Test fun decodesAPeersPayloadLeniently() {
        val wire = """{"v":1,"on":true,"future":{"x":1},"alerts":{"low":{"on":true,"mgdl":75,"repeatMin":10,"alarm":false,"tone":"x"},
            "brand_new_kind":{"on":true,"mgdl":1},"no_data":{"on":false,"min":30,"repeatMin":0}},
            "muteAfterCarbs":{"on":false,"min":20},"sounds":{"pack":"kart","kinds":{"high":"soft","nope":"x"}},"source":"own"}"""
        val s = AlertSettingsCodec.decode(wire)!!
        assertEquals(KindRule(true, 75.0, 10, false), s.rule(AlertKind.LOW))
        assertEquals(KindRule(false, 30.0, 0, false), s.rule(AlertKind.NO_DATA))
        assertEquals(AlertSettings.DEFAULT_RULES[AlertKind.HIGH], s.rule(AlertKind.HIGH))
        assertEquals(QuietRule(false, 20), s.afterCarbs)
        assertEquals(mapOf(AlertKind.HIGH to "soft"), s.sounds.perKind)
        assertEquals(AlertSource.OWN, s.source)
    }

    /** A bad value never switches an alert off: out of range is coerced, a mistyped value falls back to its default. */
    @Test fun badValuesAreCoercedNotDropped() {
        val s =
            AlertSettingsCodec.decode(
                """{"alerts":{"low":{"on":"yes","mgdl":500,"repeatMin":-3},"very_low":{"mgdl":"x"}},"notificationActions":{"muteMin":5000}}""",
            )!!
        assertEquals(KindRule(true, 120.0, 1, false), s.rule(AlertKind.LOW))
        assertEquals(55.0, s.rule(AlertKind.VERY_LOW).threshold, 0.0)
        assertEquals(720, s.actions.muteMin)
        assertEquals(0, AlertSettingsCodec.decode("""{"alerts":{"high":{"repeatMin":0}}}""")!!.rule(AlertKind.HIGH).repeatMin)
    }

    @Test fun notAnObjectIsNull() {
        assertNull(AlertSettingsCodec.decode("[1]"))
        assertNull(AlertSettingsCodec.decode("{"))
    }

    @Test fun limitsMatchTheSettingsSchema() {
        val numbers = Json.parseToJsonElement(Contracts.file("settings/settings.json").readText()).jsonObject["numbers"]!!.jsonObject
        for ((name, limit) in AlertLimits.BY_SCHEMA_NAME) {
            val n = numbers[name]?.jsonObject ?: error("$name missing in settings.json")
            assertEquals(name, n["min"]!!.jsonPrimitive.double, limit.min, 1e-9)
            assertEquals(name, n["max"]!!.jsonPrimitive.double, limit.max, 1e-9)
            assertEquals(name, n["step"]!!.jsonPrimitive.double, limit.step, 1e-9)
            assertEquals(name, n["default"]!!.jsonPrimitive.double, limit.default, 1e-9)
            assertEquals(name, n["presets"]!!.jsonArray.map { it.jsonPrimitive.double }, limit.presets)
            val specials =
                n["special"]
                    ?.jsonObject
                    ?.keys
                    ?.map { it.toDouble() }
                    ?.toSet()
                    .orEmpty()
            assertEquals(name, specials, limit.specials)
        }
    }

    @Test fun mutedByFollowsTheSpec() {
        val s = AlertSettings()
        assertNull(s.mutedBy(AlertKind.EXTREMELY_HIGH))
        assertNull(s.mutedBy(AlertKind.NO_DATA))
        assertEquals(TreatmentKind.CARBS, s.mutedBy(AlertKind.VERY_LOW))
        assertEquals(TreatmentKind.RAPID, s.mutedBy(AlertKind.RISING_FAST))
        assertNull(s.copy(afterCarbs = QuietRule(false, 15)).mutedBy(AlertKind.LOW))
        assertFalse(s.relaxChoices().any { it <= s.relaxFloor() })
    }
}
