// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.app.Activity
import android.os.Bundle
import android.service.quicksettings.TileService

/** What the treatment screens need from the app (`AppGraph` implements it; chunk 19 fills it). */
interface TreatmentsDeps

/*
 * Skeletons declared disabled in this module's manifest: chunk 19 implements them and sets android:enabled="true",
 * so no dead tile or empty dialog appears before then.
 */

/** Quick Settings tile "Add treatment" (works from the lock screen). */
class TreatmentTileService : TileService()

/** The quick treatment dialog over any app and the lock screen (widget / notification tap). */
class QuickTreatmentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
