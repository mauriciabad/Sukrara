// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.powerlab

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Just shows the status and the log tail; the lab is driven over adb (see [ControlReceiver]). */
class MainActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val log = File(getExternalFilesDir(null), "log.txt").takeIf { it.exists() }?.readLines()?.takeLast(40).orEmpty()
        setContentView(TextView(this).apply {
            setPadding(32, 120, 32, 32)
            textSize = 12f
            text = Lab.status(this@MainActivity) + "\n\n" + log.joinToString("\n")
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lab.loadPlan(this) // writes the default plan on first launch
    }
}
