// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.content.Context
import android.content.pm.PackageManager

/**
 * Installed apps known to read glucose from the local xDrip+ server (127.0.0.1:17580) rather than from broadcasts, so
 * [AppBroadcasts.receivers] never lists them. Garmin watch apps can't open sockets: Garmin Connect (or the Connect IQ
 * app) makes the HTTP request for them (docs/08-garmin.md: every logged request carries Garmin Connect's
 * `x-app-ver`). Settings → Share with apps shows them under "Apps on this phone" with the server's switch.
 * Each package is also in the manifest's `<queries>`.
 */
object ServerReaders {
    val PACKAGES = listOf(
        "com.garmin.android.apps.connectmobile",   // Garmin Connect
        "com.garmin.connectiq",                    // Connect IQ store app (older watches / setups)
    )

    data class App(val pkg: String, val label: String)

    /** The ones installed, by label. */
    fun installed(ctx: Context): List<App> {
        val pm = ctx.packageManager
        return PACKAGES.mapNotNull { p ->
            runCatching { pm.getApplicationInfo(p, PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull()
                ?.let { App(p, pm.getApplicationLabel(it).toString()) }
        }.sortedBy { it.label.lowercase() }
    }
}
