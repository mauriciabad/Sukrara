// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle

/**
 * The NovoPen tap's entry point (manifest: TECH_DISCOVERED, IsoDep + NfcA, protected by DISPATCH_NFC_MESSAGE so only
 * the NFC service can start it, as Android 17 requires). No UI: it hands the tag to [PenReadActivity] (ours, not
 * protected, so Sukrara's own reader modes and debug reads can start it too) and finishes.
 */
class PenTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)?.let { tag ->
            startActivity(Intent(this, PenReadActivity::class.java).putExtra(NfcAdapter.EXTRA_TAG, tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
