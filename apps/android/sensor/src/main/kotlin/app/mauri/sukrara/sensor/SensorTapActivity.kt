// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import app.mauri.sukrara.core.model.NavTargets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NFC entry point for a Libre sensor tap while Sukrara isn't in front (a no-UI trampoline; only the NFC service may
 * start it): opens the app's new-sensor flow, whose reader mode reads the sensor still held at the phone. Enabled
 * only while *Open Sukrara when you scan a sensor* is on ([SensorTapLaunch]).
 */
class SensorTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the app's own main activity, explicitly (no intent filter: nothing else can open it this way)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(
                launch
                    .setAction(NavTargets.ACTION_SENSOR_SETUP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }
}

/**
 * *Open Sukrara when you scan a sensor*: the switch is [SensorTapActivity]'s enabled state itself, so Android's NFC
 * dispatch and the setting can't disagree. Off by default (the manifest declares it disabled). [available] = the
 * phone has NFC.
 */
class SensorTapLaunch(context: Context) {
    private val app = context.applicationContext
    private val component = ComponentName(app, SensorTapActivity::class.java)

    val available: Boolean = runCatching { NfcAdapter.getDefaultAdapter(app) != null }.getOrDefault(false)

    private val state = MutableStateFlow(read())
    val on: StateFlow<Boolean> = state.asStateFlow()

    /** Android 16+ lets the person block an app's tag intents: the switch is on but no tap reaches Sukrara. */
    fun blocked(): Boolean = runCatching { NfcAdapter.getDefaultAdapter(app)?.isTagIntentAllowed == false }.getOrDefault(false)

    fun set(on: Boolean) {
        if (state.value == on) return
        runCatching {
            app.packageManager.setComponentEnabledSetting(
                component,
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        state.value = read()
    }

    private fun read(): Boolean =
        runCatching {
            app.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }.getOrDefault(false)
}
