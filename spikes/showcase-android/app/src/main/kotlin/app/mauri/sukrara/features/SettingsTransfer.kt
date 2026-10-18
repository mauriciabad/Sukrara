// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphSettings
import org.json.JSONObject

/**
 * Settings → Backup → Settings: the app's options as one JSON file (export / import) and back to the defaults (reset).
 * Options only: the identity, followers, the sensor pairing and the LibreView sign-in are not in it and stay as they
 * are. Each part goes through its own validation (the same as loading it), so a hand-edited or older file can't
 * leave a broken value behind.
 */
object SettingsTransfer {
    const val KIND = "sukrara-settings"
    const val MIME = "application/json"

    fun fileName() = "sukrara-settings-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}.json"

    fun export(): JSONObject = JSONObject()
        .put("kind", KIND).put("v", 1).put("app", BuildConfig.VERSION_NAME)
        .put("features", Features.value.toJson())
        .put("alerts", Alerts.settings.value.toJson())
        .put("graph", GraphSettings.state.value.toJson())
        .put("sensorEnd", app.mauri.sukrara.sensorend.SensorEnd.config.value.toJson()
            .put("countdownHours", app.mauri.sukrara.core.SensorLife.countdown.value))

    /** Applies a file from [export]; parts it doesn't have keep their current values. Null = done, else why not. */
    fun import(ctx: Context, j: JSONObject): String? {
        if (j.optString("kind") != KIND) return ctx.getString(app.mauri.sukrara.R.string.transfer_not_settings_file)
        j.optJSONObject("features")?.let { o -> Features.update { FeatureState.fromJson(o, it) } }
        j.optJSONObject("alerts")?.let { o -> Alerts.update(ctx) { AlertSettings.fromJson(o) } }
        j.optJSONObject("graph")?.let { o -> GraphSettings.update { GraphConfig.fromJson(o) } }
        j.optJSONObject("sensorEnd")?.let { o ->
            app.mauri.sukrara.sensorend.SensorEnd.update(ctx) { app.mauri.sukrara.sensorend.SensorEndConfig.fromJson(o) }
            if (o.has("countdownHours")) app.mauri.sukrara.core.SensorLife.setCountdownHours(ctx, o.optInt("countdownHours"))
        }
        return null
    }

    /**
     * Every option back to its default. The glucose source and whether LibreView uploads stay: they're the setup
     * (switching the source would drop the sensor; stopping the upload would leave the doctor's record behind).
     */
    fun reset(ctx: Context) {
        Features.update { FeatureState(source = it.source, libreview = it.libreview, libreviewBannerDismissed = it.libreviewBannerDismissed) }
        Alerts.update(ctx) { AlertSettings() }
        GraphSettings.update { GraphConfig() }
        app.mauri.sukrara.sensorend.SensorEnd.update(ctx) { app.mauri.sukrara.sensorend.SensorEndConfig() }
        app.mauri.sukrara.core.SensorLife.setCountdownHours(ctx, app.mauri.sukrara.core.SensorLife.DEFAULT_COUNTDOWN_HOURS)
    }
}
