// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor

import android.app.Activity
import android.os.Bundle

/**
 * NFC entry point for a Libre sensor tap while Sukrara isn't in front (a no-UI trampoline; only the NFC service may
 * start it). Declared disabled until chunk 12/18 implements it.
 */
class SensorTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
