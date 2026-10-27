// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Activity
import android.os.Bundle

/*
 * Insulin pen NFC entry points, declared disabled in this module's manifest. Chunk 19 implements them; the tap
 * activity is enabled at runtime only while Settings › Insulin pens isn't Off (it also matches bank cards).
 */

/** NFC trampoline for a pen tap (only the NFC service may start it); forwards to [PenReadActivity]. */
class PenTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/** Reads the pen and shows a card that closes by itself. */
class PenReadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
