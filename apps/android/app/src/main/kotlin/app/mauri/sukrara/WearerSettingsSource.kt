// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSettingsCodec
import app.mauri.sukrara.core.data.settings.MirroredGraph
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.share.owner.usecase.MirroredSettingsSource
import app.mauri.sukrara.share.owner.usecase.WearerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The wearer's own settings for its followers' *Settings from: Wearer* (PROTOCOL-v2 §Settings): every mirrored graph
 * row ([FollowerSettingsBridge.MIRRORED_GRAPH], this phone's own values under their wire keys) and the whole alert
 * settings ([alerts], SPEC §3). The owner engine keeps only the schema's `"mirror": true` rows and publishes when they
 * change; a change elsewhere (sounds, no data, per-place hours) sends nothing.
 */
class WearerSettingsSource(
    private val own: SettingsRepository,
    private val alerts: Flow<AlertSettings>,
) : MirroredSettingsSource {
    override fun settings(): Flow<WearerSettings> =
        combine(MirroredGraph.graphObject(own, FollowerSettingsBridge.MIRRORED_GRAPH), alerts) { graph, a ->
            WearerSettings(graph, AlertSettingsCodec.toJson(a))
        }.distinctUntilChanged()
}
