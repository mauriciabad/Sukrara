// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/**
 * Sukrara Showcase (the PoC, `app.mauri.sukrara.poc`) on this phone (CHANGES P3): while it is installed it may still
 * ring alerts, hold the sensor and share as the same identity. The manifest's `<queries>` entry makes it visible.
 * [installed] is a cheap package query: call it on every resume (the person may uninstall it meanwhile).
 */
class ShowcaseDetector(private val context: Context) {
    fun installed(): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Opens the Showcase (to turn its alerts, sensor and sharing off), or null when it can't be launched. */
    fun openIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(PACKAGE)

    /** The system's "Uninstall Sukrara Showcase?" dialog (the person confirms there). */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE, "package:$PACKAGE".toUri())

    companion object {
        const val PACKAGE = "app.mauri.sukrara.poc"
    }
}
