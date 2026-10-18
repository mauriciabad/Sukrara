// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter

/**
 * "Open Sukrara when you scan a sensor": the manifest's `.ui.NfcLaunch` alias (NfcV TECH_DISCOVERED → MainActivity).
 * The switch is the component's enabled state itself, so Android's NFC dispatch and the setting can't disagree.
 */
object NfcLaunch {
    private fun component(ctx: Context) = ComponentName(ctx, "app.mauri.sukrara.ui.NfcLaunch")

    fun isOn(ctx: Context): Boolean = when (ctx.packageManager.getComponentEnabledSetting(component(ctx))) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        else -> true // default = the manifest's enabled="true"
    }

    /** Android 16 lets the user block an app's tag intents (Settings → Apps → Sukrara); then the alias never fires. */
    fun allowed(nfc: NfcAdapter?): Boolean = nfc == null || runCatching { nfc.isTagIntentAllowed }.getOrDefault(true)

    fun set(ctx: Context, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(component(ctx),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP)
}
