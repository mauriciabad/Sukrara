// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Copy into AndroidAPS plugins/source/src/test/kotlin/app/aaps/plugins/source/ and run (JDK 21):
//   ./gradlew :plugins:source:testFullDebugUnitTest --tests "*SukraraPayloadScratchTest*"
package app.aaps.plugins.source

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

// Investigation 22 scratch test (not committed upstream): the exact strings the outputs spike puts into the
// xDrip+ BgEstimate broadcast, run through AAPS's own parsing (SourceSensor/TrendArrow.fromString + detectSource).
class SukraraPayloadScratchTest : TestBaseWithProfile() {

    private fun gv(src: String, trend: String) = GV(
        timestamp = 10000L, value = 120.0, raw = 0.0, noise = null,
        trendArrow = TrendArrow.fromString(trend), sourceSensor = SourceSensor.fromString(src)
    )

    @Test fun libre3LabelGetsAdvancedFiltering() {
        val p = XdripSourcePlugin(rh, aapsLogger)
        val v = gv("Libre3", "Flat")
        assertThat(v.sourceSensor).isEqualTo(SourceSensor.LIBRE_3)
        p.detectSource(v)
        assertThat(p.advancedFilteringSupported()).isTrue()
        println("SUKRARA-I22 Libre3 -> ${v.sourceSensor} advancedFiltering=${p.advancedFilteringSupported()}")
    }

    @Test fun unknownLabelDoesNot() {
        val p = XdripSourcePlugin(rh, aapsLogger)
        val v = gv("Sukrara", "Flat")
        assertThat(v.sourceSensor).isEqualTo(SourceSensor.UNKNOWN)
        p.detectSource(v)
        assertThat(p.advancedFilteringSupported()).isFalse()
        println("SUKRARA-I22 Sukrara -> ${v.sourceSensor} advancedFiltering=${p.advancedFilteringSupported()}")
    }

    @Test fun everyTrendNameParses() {
        for (t in listOf("DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown")) {
            assertThat(TrendArrow.fromString(t)).isNotEqualTo(TrendArrow.NONE)
        }
        assertThat(TrendArrow.fromString("")).isEqualTo(TrendArrow.NONE)
        println("SUKRARA-I22 trends ok")
    }
}
