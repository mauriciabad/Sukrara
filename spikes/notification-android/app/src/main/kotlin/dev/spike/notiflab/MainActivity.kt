// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.notiflab

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.widget.TextView

/**
 * Posts the variants named in `--es v a,b,c` (default: all non-colorized ones) with `--ei sgv` / `--ef rate`;
 * `--ez clear true` removes everything first. Grant notifications once: `pm grant dev.spike.notiflab android.permission.POST_NOTIFICATIONS`.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lab.channel(this, intent.getBooleanExtra("visible", false))
        val sgv = intent.getIntExtra("sgv", 138)
        val rate = intent.getFloatExtra("rate", 0.7f)
        Lab.hours = intent.getIntExtra("hours", 3)
        if (intent.getBooleanExtra("clear", false)) {
            getSystemService(NotificationManager::class.java).cancelAll()
            stopService(android.content.Intent(this, CardService::class.java))
        }
        val vs = intent.getStringExtra("v")?.split(",") ?: Lab.VARIANTS.filter { !it.endsWith("_c") && it != "baseline" }
        for (v in vs.reversed()) Lab.post(this, v, sgv, rate)
        setContentView(TextView(this).apply {
            setPadding(48, 160, 48, 48); textSize = 16f
            text = "NotifLab (investigation 19)\nposted: ${vs.joinToString()}\nsgv $sgv, rate $rate\n\n" +
                "am start -n dev.spike.notiflab/.MainActivity --es v ${Lab.VARIANTS.joinToString(",")} --ei sgv 138 --ef rate 0.7"
        })
    }
}
