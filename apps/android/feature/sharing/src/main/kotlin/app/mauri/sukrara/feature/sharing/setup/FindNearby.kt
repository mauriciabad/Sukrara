// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.avatar.AvatarImage
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.feature.sharing.BLOCKED
import app.mauri.sukrara.feature.sharing.NearbyFixRow
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.nearbySummary
import app.mauri.sukrara.feature.sharing.share.shownName
import app.mauri.sukrara.share.ble.AndroidNearbyCentral
import app.mauri.sukrara.share.follower.NearbyOffer
import app.mauri.sukrara.share.follower.NearbySearch

/**
 * *Find phones nearby* on the join step: joining without a link when the wearer is next to you and has *Pair nearby*
 * open. Off: one button. Looking: what limits the search (Bluetooth off / permission, each with its fix), the phones
 * offering pairing (the wearer's picture and name; a tap asks to follow with [name]) and *Stop looking*.
 */
@Composable
internal fun ColumnScope.FindNearby(
    search: NearbySearch?,
    name: String,
    enabled: Boolean,
    actions: SetupActions,
) {
    val haptics = rememberHaptics()
    if (search == null) {
        Text(stringResource(R.string.sh_find_nearby_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { actions.findNearby(true) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.sh_find_nearby)) }
        return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.sh_find_nearby),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (search.status in BLOCKED) {
                Text(
                    nearbySummary(search.status, on = true, wearer = false),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                NearbyFixRow(search.status, AndroidNearbyCentral.PERMISSIONS, actions.refreshNearby)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        if (search.offers.isEmpty()) {
                            stringResource(R.string.sh_find_nearby_looking)
                        } else {
                            stringResource(R.string.sh_find_nearby_found)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                for (offer in search.offers) {
                    OfferRow(offer, enabled) {
                        haptics.confirm()
                        actions.joinNearby(offer, name)
                    }
                }
            }
            TextButton(
                onClick = { actions.findNearby(false) },
                modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sh_find_nearby_stop)) }
        }
    }
}

@Composable
private fun OfferRow(
    offer: NearbyOffer,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val name =
        shownName(
            offer.pairing.profile
                ?.name
                .orEmpty(),
        ).ifBlank { stringResource(R.string.sh_join_someone) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AvatarImage(offer.pairing.profile?.avatar, name, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sh_find_nearby_ask), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
