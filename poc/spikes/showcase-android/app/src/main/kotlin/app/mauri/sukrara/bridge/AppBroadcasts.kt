// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.SensorOutputs
import app.mauri.sukrara.features.Features
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Investigation 22: the wearer's readings as local broadcasts (xDrip+ `BgEstimate`, patched-LibreLink
 * `GLUCOSE_READING` + `SENSOR_ACTIVATE`, `glucodata.Minute`) to the apps the user allowed in Settings → Share with apps.
 * Since Android 8 a manifest receiver only gets explicit broadcasts, so like Juggluco we look up who declares a receiver
 * (the manifest's `<queries>`) and send one copy per allowed package with `setPackage`. Glucose is health data and any
 * app can declare a receiver: none is allowed until the user switches it on.
 */
object AppBroadcasts {
    private const val TAG = "Sukrara.broadcast"
    private const val PREFS = "app_broadcasts"

    /** One installed app that declares a receiver for at least one of the actions. */
    data class Receiver(val pkg: String, val label: String, val actions: Set<String>)

    private val _allowed = MutableStateFlow<Set<String>>(emptySet())
    val allowed: StateFlow<Set<String>> = _allowed
    @Volatile private var loaded = false
    @Volatile private var lastSentMs = 0L

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _allowed.value = prefs(ctx).getStringSet("allowed", emptySet())!!.toSet()
            loaded = true
        }
    }

    fun setAllowed(ctx: Context, pkg: String, on: Boolean) {
        init(ctx)
        val s = if (on) _allowed.value + pkg else _allowed.value - pkg
        prefs(ctx).edit().putStringSet("allowed", s).apply()
        _allowed.value = s
    }

    /** The installed apps that listen for any of Sukrara's broadcasts, by label. */
    fun receivers(ctx: Context): List<Receiver> {
        val pm = ctx.packageManager
        val acts = HashMap<String, MutableSet<String>>()
        for (a in BroadcastPayloads.ACTIONS) {
            pm.queryBroadcastReceivers(Intent(a), PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != ctx.packageName }
                .forEach { acts.getOrPut(it) { mutableSetOf() } += a }
        }
        return acts.map { (p, a) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, PackageManager.ApplicationInfoFlags.of(0))).toString() }.getOrDefault(p)
            Receiver(p, label, a)
        }.sortedBy { it.label.lowercase() }
    }

    /** A new live reading of the wearer: sent to the allowed apps (≤ 1 per 55 s). */
    fun onReading(ctx: Context, r: Reading) {
        init(ctx)
        val allowed = _allowed.value
        if (allowed.isEmpty()) return
        val timeMs = r.t * 1000
        if (!BroadcastPayloads.due(lastSentMs, timeMs)) return
        // the reading's own family (its dev tag): an untested one has no label and sends nothing
        val label = SensorOutputs.broadcastLabel(SensorOutputs.profileOf(r.dev)) ?: return
        val sensor = SensorLife.current.value
        val serial = sensor?.serial.orEmpty()
        val p = prefs(ctx)
        val newSensor = serial.isNotEmpty() && p.getString("activated", null) != serial
        val plan = BroadcastPayloads.plan(
            wearer = App.role(ctx) == "owner", direct = Features.value.source == "direct",
            declared = receivers(ctx).associate { it.pkg to it.actions }, allowed = allowed, newSensor = newSensor,
            sourceInfo = label,
        )
        if (plan.isEmpty()) return
        lastSentMs = timeMs
        val out = BroadcastPayloads.Out(r.sgv, r.rate, timeMs, serial, (sensor?.startEpoch ?: 0L) * 1000, label)
        for ((pkg, actions) in plan) for (a in actions) {
            val extras = when (a) {
                BroadcastPayloads.XDRIP -> BroadcastPayloads.xdrip(out)
                BroadcastPayloads.LIBRE -> BroadcastPayloads.libre(out)
                BroadcastPayloads.LIBRE_ACTIVATE -> BroadcastPayloads.libreActivate(out)
                else -> BroadcastPayloads.glucodata(out)
            }
            runCatching {
                ctx.sendBroadcast(Intent(a).putExtras(extras.toBundle()).setPackage(pkg)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND))
            }.onFailure { Log.w(TAG, "$a → $pkg: $it") }
        }
        if (newSensor && plan.values.any { BroadcastPayloads.LIBRE_ACTIVATE in it }) p.edit().putString("activated", serial).apply()
        Log.i(TAG, "${r.sgv} mg/dL @${r.t} → " + plan.entries.joinToString { (k, v) -> "$k ${v.size}" })
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any>.toBundle(): Bundle = Bundle().also { b ->
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
