// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home.banners

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** The Sukrara Showcase (the PoC app): while it is installed both apps can watch one sensor and ring twice. */
object Showcase {
    const val PACKAGE = "app.mauri.sukrara.poc"

    /** Visible through this module's `<queries>`; re-checked on every resume (the person may have just removed it). */
    fun installed(pm: PackageManager): Boolean =
        try {
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Its app info page, where the person can force-stop, turn off its alerts or uninstall it. */
    fun appInfo(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
