// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.app.PendingIntent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The Quick Settings tile "Add treatment": Quick Settings open from the lock screen and the quick dialog shows over
 * it, so an entry needs no unlock (developer.android.com/develop/ui/views/quicksettings-tiles). An action tile, not a
 * toggle: inactive when this phone can add entries, unavailable when it can't (a follower without "Can add entries").
 */
class TreatmentTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = if (treatmentsModule.canAdd()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = if (tile.state == Tile.STATE_UNAVAILABLE) getString(R.string.tile_unavailable) else null
        tile.updateTile()
    }

    override fun onClick() {
        val intent = QuickTreatmentIntents.open(this, QuickSource.Tile)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }
}
