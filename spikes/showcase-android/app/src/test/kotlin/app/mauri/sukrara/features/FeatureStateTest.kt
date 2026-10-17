// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FeatureStateTest {
    @Test fun settingsFileRoundTrip() {
        val x = FeatureState(source = "juggluco", nostr = false, nostrIntervalMin = 15, garmin = true, nightscout = true,
            lockBg = LockBackground.CARD, connPriority = "high", libreviewBannerDismissed = true)
        assertEquals(x, FeatureState.fromJson(JSONObject(x.toJson().toString())))
    }

    @Test fun missingKeysKeepTheCurrentValues() {
        val cur = FeatureState(chip = false, garmin = true)
        val back = FeatureState.fromJson(JSONObject("{\"nostr\":false}"), cur)
        assertEquals(cur.copy(nostr = false), back)
    }

    @Test fun watchServerAndNightscoutAreOffByDefault() {
        assertFalse(FeatureState().garmin)
        assertFalse(FeatureState().nightscout)
    }
}
