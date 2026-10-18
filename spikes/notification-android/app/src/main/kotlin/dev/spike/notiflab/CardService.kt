// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.notiflab

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo

/** Holds the one colorized variant (baseline or `*_c`): the system only honours setColorized for a foreground service. */
class CardService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val v = intent?.getStringExtra("v") ?: "baseline"
        val n = Lab.build(this, v, intent?.getIntExtra("sgv", 138) ?: 138, intent?.getFloatExtra("rate", 0.7f) ?: 0.7f)
        startForeground(Lab.id(v), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Lab.report(this, v, n)
        return START_NOT_STICKY
    }
}
