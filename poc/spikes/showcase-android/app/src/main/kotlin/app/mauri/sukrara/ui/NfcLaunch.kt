// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter

/**
 * "Open Sukrara when you scan a sensor": the manifest's `.ui.SensorTapActivity` (NfcV TECH_DISCOVERED, protected by
 * DISPATCH_NFC_MESSAGE → MainActivity).
 * The switch is the component's enabled state itself, so Android's NFC dispatch and the setting can't disagree.
 */
object NfcLaunch {
    private fun component(ctx: Context) = ComponentName(ctx, "app.mauri.sukrara.ui.SensorTapActivity")

    fun isOn(ctx: Context): Boolean = when (ctx.packageManager.getComponentEnabledSetting(component(ctx))) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        else -> true // default = the manifest's enabled="true"
    }

    /**
     * Android 16+ lets the user block an app's tag intents (Settings → Apps → Sukrara), and Android 17 mutes an app's tag
     * intents until the user allows them ("tryTech: mute", TagAppPreference false): then no tap reaches Sukrara, pen or
     * sensor. The one check for both (the home banner, the pen and sensor settings rows).
     */
    fun allowed(nfc: NfcAdapter?): Boolean = !fakeBlocked && (nfc == null || runCatching { nfc.isTagIntentAllowed }.getOrDefault(true))

    /** Debug builds only (DebugFakes `tagIntents --ez blocked true`): the emulator shows the blocked state. */
    @Volatile var fakeBlocked = false

    /** What a block stops, for the home banner (one banner names what's blocked); null = nothing to say. */
    enum class Blocked { PEN, SENSOR, BOTH }
    fun blocked(allowed: Boolean, pensOn: Boolean, sensorOn: Boolean): Blocked? = when {
        allowed -> null
        pensOn && sensorOn -> Blocked.BOTH
        pensOn -> Blocked.PEN
        sensorOn -> Blocked.SENSOR
        else -> null
    }

    fun set(ctx: Context, on: Boolean) = ctx.packageManager.setComponentEnabledSetting(component(ctx),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP)
}
