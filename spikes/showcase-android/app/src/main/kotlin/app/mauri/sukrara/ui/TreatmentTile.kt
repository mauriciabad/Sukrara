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
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendMark
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
        val now = System.currentTimeMillis() / 1000
        val look = look(r, now)
        tile.subtitle = subtitle(look, r?.rate) { state ->
            when (state) {
                GlucoseLook.State.ENDED -> getString(R.string.card_sensor_ended)
                GlucoseLook.State.WARMING_UP -> getString(R.string.card_warming_up)
                else -> getString(R.string.notif_no_reading_since, Freshness.timeText(r!!.t, now))
            }
        }
        tile.updateTile()
    }

    companion object {
        /** The tile's value decision for [r] (its one, also the tests'): the card's, with the sensor's state. */
        fun look(r: app.mauri.sukrara.core.Reading?, now: Long) = app.mauri.sukrara.graph.GlucoseCard.face(r, now,
            app.mauri.sukrara.graph.SensorBadge.look(app.mauri.sukrara.core.SensorLife.current.value, now, r?.t),
            app.mauri.sukrara.graph.CardSurface.QUICK_TILE)

        /**
         * The tile's plain-text line for a [look] ([GlucoseCard.face]): `123 ↗`, struck from 5 min (`1̶2̶3̶ ○`, a tile
         * can't draw a line), "No reading since 00:32" from 20 min and "Sensor ended" ([words]), nothing without a reading.
         */
        fun subtitle(look: GlucoseLook, rate: Double?, words: (GlucoseLook.State) -> String): String? = when (look.state) {
            GlucoseLook.State.VALUE -> "${look.plain} ${if (look.trend == TrendMark.ARROW) Trend.arrow(rate) else TrendMark.RING}"
            GlucoseLook.State.NO_READINGS, GlucoseLook.State.ENDED, GlucoseLook.State.WARMING_UP -> words(look.state)
            GlucoseLook.State.NONE -> null
        }
    }

    override fun onClick() {
        val i = Intent(this, QuickTreatmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
