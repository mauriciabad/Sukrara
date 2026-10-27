// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle

/**
 * The Libre sensor tap's entry point (manifest: TECH_DISCOVERED, NfcV, protected by DISPATCH_NFC_MESSAGE as Android 17
 * requires; it replaces the `.ui.NfcLaunch` activity-alias of MainActivity, which can't be protected: MainActivity is
 * the launcher). No UI: it hands the tag to [MainActivity] (its New sensor screen reads it) and finishes. Its enabled
 * state is the *Open Sukrara when you scan a sensor* switch ([NfcLaunch]).
 */
class SensorTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)?.let { tag ->
            startActivity(Intent(this, MainActivity::class.java).setAction(NfcAdapter.ACTION_TECH_DISCOVERED)
                .putExtra(NfcAdapter.EXTRA_TAG, tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
