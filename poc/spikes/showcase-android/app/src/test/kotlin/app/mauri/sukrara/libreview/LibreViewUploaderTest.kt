// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.libreview

import android.content.Context
import app.mauri.sukrara.core.SensorOutputs
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.treatments.TreatmentStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** LibreView stays the Libre 3's (investigation 23): another family's sensor never reaches it, nor stale Libre data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibreViewUploaderTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun onlyTheLibre3sGlucoseGoesUp() {
        ctx.getSharedPreferences("libreview", Context.MODE_PRIVATE).edit().putBoolean("glucose", true).putLong("glucoseFrom", 1).commit()
        val up = LibreViewUploader(ctx, TreatmentStore(File(ctx.filesDir, "t.json")))
        val now = System.currentTimeMillis() / 1000
        up.onSensor("3MH0001", now - 3_600, libreView = SensorOutputs.libreView(SensorProfiles.LIBRE_3))
        up.onCurrent(LibreViewPayload.Glucose(60, 120, now))
        assertEquals("3MH0001", up.pendingGlucose()?.serial)

        // a Dexcom G6 replaces it: nothing for LibreView, and the Libre 3's buffered value isn't sent under its id
        up.onSensor("8GABCD", now, libreView = SensorOutputs.libreView(SensorProfiles.DEXCOM_G6))
        assertNull(up.pendingGlucose())
        up.onCurrent(LibreViewPayload.Glucose(61, 121, now))
        up.onHistory(listOf(LibreViewPayload.Glucose(55, 118, now - 300)))
        assertNull(up.pendingGlucose())
        for (p in SensorProfiles.all.filter { it.id != "libre3" }) {
            up.onSensor("X", now, libreView = SensorOutputs.libreView(p))
            assertNull(p.id, up.pendingGlucose())
        }
    }
}
