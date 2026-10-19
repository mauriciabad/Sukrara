// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.core.SensorProfiles
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The untested-sensor banner (wearer only, unverified only) and its GitHub issue link (no personal data). */
class SensorFeedbackTest {
    private val device = SensorFeedback.Device("0.11.27", "16 (API 36)", "Google Pixel 10 Pro")

    @Test fun bannerOnlyForTheWearersUnverifiedSensor() {
        assertNull(SensorFeedback.bannerFor("owner", SensorProfiles.LIBRE_3))       // verified: never
        assertSame(SensorProfiles.DEXCOM_G7, SensorFeedback.bannerFor("owner", SensorProfiles.DEXCOM_G7))
        assertNull(SensorFeedback.bannerFor("follower", SensorProfiles.DEXCOM_G7))  // it's the wearer's sensor
        assertNull(SensorFeedback.bannerFor(null, SensorProfiles.DEXCOM_G7))
        assertNull(SensorFeedback.bannerFor("owner", null))                         // no direct sensor (Juggluco)
        for (p in SensorProfiles.all) assertEquals(p.id, !p.verified, SensorFeedback.bannerFor("owner", p) != null)
        // flipping the profile's flag is what removes it
        assertNull(SensorFeedback.bannerFor("owner", SensorProfiles.DEXCOM_G7.copy(verified = true)))
    }

    @Test fun issueUrlIsPrefilledWithNothingPersonal() {
        val url = SensorFeedback.issueUrl(SensorProfiles.AIDEX_X, device)
        val uri = URI(url)
        assertEquals("https://github.com/mauriciabad/cgm-reading-mac-spike/issues/new", url.substringBefore('?'))
        val q = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        // exactly these fields: the template, the title, the label and the four facts about the setup
        assertEquals(listOf("template", "title", "labels", "sensor", "app-version", "android", "phone"), q.keys.toList())
        assertEquals("sensor-feedback.yml", q["template"])
        assertEquals("Sensor feedback: Aidex X / LinX / GlucoRx", q["title"])
        assertEquals("sensor-feedback", q["labels"])
        assertEquals("Aidex X / LinX / GlucoRx", q["sensor"])
        assertEquals("0.11.27", q["app-version"])
        assertEquals("16 (API 36)", q["android"])
        assertEquals("Google Pixel 10 Pro", q["phone"])
        assertFalse("spaces are %20, not +", url.contains('+'))
    }

    /** The builder can't leak a serial or a glucose value: it never gets one, whatever the saved sensor holds. */
    @Test fun noSerialOrValuesInTheUrl() {
        for (p in SensorProfiles.all.filter { !it.verified }) {
            val url = SensorFeedback.issueUrl(p, device)
            for (secret in listOf("8GABCD", "22222A2WC8", "mg/dL", "mmol")) assertFalse("$secret in $url", url.contains(secret))
            val values = URI(url).rawQuery.split('&').map { URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            assertEquals(setOf(p.name, "Sensor feedback: ${p.name}"), values.filter { it.contains(p.name) }.toSet())
        }
    }
}
