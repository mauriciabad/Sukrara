// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Juggluco's sgv.json `sensor_status` (watchserver.cpp firstdata) → the sensor's start; end = start + 15 days. */
class JugglucoSensorTest {
    private val zone = ZoneId.of("Europe/Madrid")

    @Test fun parses() {
        val json = """[{"_id":"3MH00ABCDE#1234","device":"Juggluco","date":1760776800000,"sgv":120,"sensor_status":"09-10-26 08:05 (9d 3h)"}]"""
        val start = ZonedDateTime.of(2026, 10, 9, 8, 5, 0, 0, zone).toEpochSecond()
        val info = parseSensorStatus(json, zone)!!
        assertEquals("3MH00ABCDE", info.serial)
        assertEquals(start, info.startEpoch)
        assertEquals(start + 15 * 86_400L, info.endEpoch)
    }

    @Test fun absentOrBroken() {
        assertNull(parseSensorStatus("""[{"_id":"x#1","sgv":120}]""", zone))
        assertNull(parseSensorStatus("""[{"sensor_status":"yesterday-ish"}]""", zone))
        assertNull(parseSensorStatus("[]", zone))
    }
}
