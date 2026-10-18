// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.R
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.core.stale
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.widgets.WidgetUpdater

/**
 * Investigation 17, the rootless way to the lock screen: a Quick Settings tile "Add treatment" with the current value
 * as its subtitle. Quick Settings open from the lock screen, and the [QuickTreatmentActivity] it starts shows over
 * the lock screen, so an entry needs no unlock (developer.android.com/develop/ui/views/quicksettings-tiles).
 */
class TreatmentTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        Features.init(this)
        val r = WidgetUpdater.current(this).history.lastOrNull()
        tile.state = if (QuickEntry.canAdd(this)) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = r?.let { if (it.stale(System.currentTimeMillis() / 1000)) getString(R.string.tile_value_old, it.sgv) else "${it.sgv} ${Trend.arrow(it.rate)}" }
        tile.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, QuickTreatmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
