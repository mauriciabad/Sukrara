// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.outputs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Sends one reading as every local broadcast. Since Android 8 a manifest receiver only gets explicit broadcasts, so
 * like Juggluco we look up who declares a receiver for the action (needs the `<queries>` entries in the manifest) and
 * send one copy per package with `setPackage`.
 */
class Emitter(ctx: Context) {
    private val app = ctx.applicationContext
    private val tag = "Outputs"

    data class Sent(val action: String, val packages: List<String>)

    fun receivers(action: String): List<String> =
        app.packageManager.queryBroadcastReceivers(Intent(action), PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it != app.packageName }
            .distinct()

    fun send(action: String, extras: Map<String, Any>): Sent {
        val targets = receivers(action)
        for (pkg in targets) {
            val intent = Intent(action)
                .putExtras(extras.toBundle())
                .setPackage(pkg)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            app.sendBroadcast(intent)
        }
        Log.i(tag, "$action → ${targets.ifEmpty { listOf("nobody") }.joinToString()} : $extras")
        return Sent(action, targets)
    }

    /** Everything Sukrara would emit for one new reading (the patched-Libre activate only on a new sensor). */
    fun all(r: Reading, newSensor: Boolean): List<Sent> = buildList {
        add(send(Payloads.XDRIP_ACTION, Payloads.xdrip(r)))
        if (newSensor) add(send(Payloads.LIBRE_ACTIVATE_ACTION, Payloads.libreActivate(r)))
        add(send(Payloads.LIBRE_ACTION, Payloads.libre(r)))
        add(send(Payloads.GLUCODATA_ACTION, Payloads.glucodata(r)))
    }
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any>.toBundle(): Bundle = Bundle().also { b ->
    for ((k, v) in this) when (v) {
        is Double -> b.putDouble(k, v)
        is Long -> b.putLong(k, v)
        is Int -> b.putInt(k, v)
        is Float -> b.putFloat(k, v)
        is String -> b.putString(k, v)
        is Map<*, *> -> b.putBundle(k, (v as Map<String, Any>).toBundle())
        else -> error("unsupported extra $k: ${v::class}")
    }
}
