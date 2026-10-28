// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor

import android.content.pm.PackageManager

/**
 * Abbott's apps that read a Libre sensor (Libre 3 and the Libre / LibreLink app, regional builds included). Each one
 * needs a `<queries>` entry in this module's manifest (OfficialAppsTest keeps them equal).
 */
object OfficialApps {
    val PACKAGES =
        listOf(
            "com.freestylelibre3.app",
            "com.freestylelibre3.app.de",
            "com.freestylelibre3.app.gb",
            "com.freestylelibre3.app.fr",
            "com.freestylelibre3.app.it",
            "com.freestylelibre3.app.es",
            "com.freestylelibre3.app.nl",
            "com.freestylelibre3.app.at",
            "com.freestylelibre3.app.ch",
            "com.freestylelibre3.app.au",
            "com.freestylelibre.app",
            "com.freestylelibre.app.de",
            "com.freestylelibre.app.gb",
            "com.freestylelibre.app.fr",
            "com.freestylelibre.app.it",
            "com.freestylelibre.app.es",
            "com.freestylelibre.app.nl",
            "com.freestylelibre.app.at",
            "com.freestylelibre.app.ch",
            "com.freestylelibre.app.au",
            "com.librelink.app",
        )

    fun anyInstalled(pm: PackageManager): Boolean = PACKAGES.any { pm.isInstalled(it) }

    private fun PackageManager.isInstalled(name: String): Boolean =
        try {
            getPackageInfo(name, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
