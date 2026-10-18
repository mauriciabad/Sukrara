// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Glucose alerts for the role that runs (spikes/alerts/SPEC.md): [AlertEngine] + settings + notifications + timer.
 *
 * - **Wearer**: the rules run on the source's readings and the treatment log; every firing and clearing also goes
 *   to the followers as an `alert` event ([attach] `publish`, relays + BLE).
 * - **Follower**, source "wearer" (default): the wearer's `alert` events are mirrored ([onRemote]); only no data
 *   runs here (it's about this phone's link). Source "own": the rules run here on the followed readings and the
 *   treatments the follower received.
 *
 * Callers on any thread; everything is serialised on this object.
 */
object Alerts {
    private const val TAG = "Sukrara.Alerts"
    private const val PREFS = "alerts"
    private const val EPISODES = "episodes"

    private val _settings = MutableStateFlow(AlertSettings())
    val settings: StateFlow<AlertSettings> = _settings
    /** group → state, for the settings screen's "now" line. */
    val state = MutableStateFlow<Map<String, AlertEngine.State>>(emptyMap())
    /** What happened lately, newest first (settings screen + DebugReceiver). */
    val history = MutableStateFlow<List<String>>(emptyList())

    private var app: Context? = null
    private val engine = AlertEngine()
    private var role: String? = null
    private var who: String? = null
    private var doses: () -> AlertEngine.Doses = { AlertEngine.Doses() }
    private var publish: ((JSONObject) -> Unit)? = null
    /** group → id of the alert whose notification is showing (a clear only removes its own firing). */
    private val posted = HashMap<String, String>()
    private val seenRemote = LinkedHashSet<String>()
    /** Once per process: the saved episodes are read back on the first attach. */
    private var resumed = false

    @Synchronized fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
        _settings.value = json?.let { runCatching { AlertSettings.fromJson(JSONObject(it)) }.getOrNull() } ?: AlertSettings()
        AlertNotifier.channels(ctx)
    }

    /** Called by the role's service. [who]: the followed person's label on a follower's notifications. */
    @Synchronized fun attach(ctx: Context, role: String, doses: () -> AlertEngine.Doses, publish: ((JSONObject) -> Unit)?, who: String? = null,
                             newest: Reading? = null) {
        init(ctx)
        engine.seed(newest)   // after a restart "no data" still counts from the last stored reading
        if (!resumed) { resumed = true; resume(ctx, role) }
        this.role = role
        this.doses = doses
        this.publish = publish
        this.who = who
        check(ctx)
    }

    /**
     * After a restart (an app update kills the process and removes its notifications): the episodes go on where they
     * were and the alerts that were showing come back without sound; only a repeat that's due rings (docs/15).
     */
    private fun resume(ctx: Context, role: String) {
        val j = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EPISODES, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optString("role") == role } ?: return
        val shown = runCatching { engine.restore(j.getJSONObject("episodes"), _settings.value, Proto.nowS()) }.getOrElse { return }
        for (f in shown) {
            AlertNotifier.post(ctx, f.kind, f.reading?.sgv, f.reading?.rate, f.th, f.at, who, _settings.value.cfg(f.kind).alarm, f.treated,
                silent = true)
            posted[Kind.group(f.kind)] = f.id
        }
        if (shown.isNotEmpty()) note("resumed ${shown.joinToString { Kind.label(it.kind) }} (no sound)")
    }

    @Synchronized fun detach() { role = null; publish = null; cancelTimer() }

    @Synchronized fun update(ctx: Context, f: (AlertSettings) -> AlertSettings) {
        init(ctx)
        val s = f(_settings.value)
        _settings.value = s
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", s.toJson().toString()).apply()
        check(ctx)
    }

    /**
     * What logging rapid insulin and/or carbs at [at] silences on this phone, for the treatment dialog
     * ("Highs quiet for 60 min"), or null: alerts or the rule off, the window already over, or a follower that mirrors
     * the wearer (the wearer's phone decides).
     */
    fun quietNote(ctx: Context, rapid: Boolean, carbs: Boolean, at: Long, now: Long): String? {
        init(ctx)
        val s = _settings.value
        if (!s.on || (role == "follower" && s.source == "wearer")) return null
        fun left(on: Boolean, min: Int) = if (on) ((at + min * 60L - now) / 60).toInt().takeIf { it > 0 } else null
        val highs = left(rapid && s.muteInsulin, s.muteInsulinMin)
        val lows = left(carbs && s.muteCarbs, s.muteCarbsMin)
        return when {
            highs != null && lows != null -> ctx.getString(R.string.treatment_quiet_both, highs, lows)
            highs != null -> ctx.getString(R.string.treatment_quiet_highs, highs)
            lows != null -> ctx.getString(R.string.treatment_quiet_lows, lows)
            else -> null
        }
    }

    /** Rules that run on this phone: all of them, or only no data while a follower mirrors the wearer. */
    private fun effective(): AlertSettings {
        val s = _settings.value
        if (role != "follower" || s.source != "wearer") return s
        return s.copy(kinds = s.kinds.mapValues { (k, c) -> if (k == Kind.NO_DATA) c else c.copy(on = false) })
    }

    @Synchronized fun onReading(ctx: Context, r: Reading) {
        if (role == null) return
        apply(ctx, engine.reading(r, effective(), doses(), Proto.nowS()))
    }

    /** Re-evaluate now: timer, settings change, a treatment logged. */
    @Synchronized fun check(ctx: Context) {
        if (role == null) return
        apply(ctx, engine.evaluate(effective(), doses(), Proto.nowS()))
    }

    private fun apply(ctx: Context, actions: List<AlertEngine.Action>) {
        for (a in actions) when (a) {
            is AlertEngine.Fire -> {
                AlertNotifier.post(ctx, a.kind, a.reading?.sgv, a.reading?.rate, a.th, a.at, who, _settings.value.cfg(a.kind).alarm, a.treated)
                posted[Kind.group(a.kind)] = a.id
                note("${Kind.message(a.kind, a.treated)}${if (a.n > 1) " (${a.n})" else ""} · ${a.reading?.sgv ?: "–"} mg/dL")
                publish?.invoke(event(a.kind, "fire", a.id, a.at, a.reading, a.th, a.n, a.treated))
            }
            is AlertEngine.Clear -> {
                if (posted[Kind.group(a.kind)] == a.id) { AlertNotifier.cancel(ctx, a.kind); posted.remove(Kind.group(a.kind)) }
                note("${Kind.label(a.kind)} cleared")
                publish?.invoke(event(a.kind, "clear", a.id, a.at, engine.last, null, 0, 0))
            }
        }
        state.value = engine.states()
        role?.let { r -> ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(EPISODES, JSONObject().put("role", r).put("episodes", engine.snapshot()).toString()).apply() }
        schedule(ctx)
    }

    /** spikes/alerts/SPEC.md §4 body. */
    private fun event(k: String, st: String, id: String, at: Long, r: Reading?, th: Double?, n: Int, treated: Int) = JSONObject()
        .put("v", 2).put("type", "alert").put("id", id).put("k", k).put("state", st).put("at", at)
        .put("t", r?.t ?: JSONObject.NULL).put("sgv", r?.sgv ?: JSONObject.NULL).put("r", r?.rate ?: JSONObject.NULL)
        .put("th", th ?: JSONObject.NULL).put("n", n).put("tr", treated)

    /** A verified, decrypted `alert` event from the followed wearer (any transport; deduped here by its id+state). */
    @Synchronized fun onRemote(ctx: Context, body: JSONObject) {
        val s = _settings.value
        if (role != "follower" || !s.on || s.source != "wearer") return
        val k = body.optString("k").takeIf { it in Kind.ALL } ?: return
        val id = body.optString("id")
        val st = body.optString("state")
        if (!seenRemote.add("$id/$st/${body.optInt("n")}")) return
        while (seenRemote.size > 200) seenRemote.remove(seenRemote.first())
        if (Proto.nowS() - body.optLong("at") > 10 * 60) return
        // no data on the wearer's side and on this phone share one notification
        val group = Kind.group(k)
        when (st) {
            "fire" -> {
                val treated = body.optInt("tr", 0)
                AlertNotifier.post(ctx, k, body.optIntOrNull("sgv"), body.optDoubleOrNull("r"), body.optDouble("th", 0.0),
                    body.optLong("at"), who, s.cfg(k).alarm, treated)
                posted[group] = id
                note("${Kind.message(k, treated)} (from the wearer) · ${body.optIntOrNull("sgv") ?: "–"} mg/dL")
            }
            "clear" -> if (posted[group] == id) {
                AlertNotifier.cancel(ctx, k); posted.remove(group)
                note("${Kind.label(k)} cleared (by the wearer)")
            }
        }
    }

    private fun note(s: String) {
        Log.i(TAG, s)
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        history.value = (listOf("$t  $s") + history.value).take(20)
    }

    // ------------------------------------------------------------------------------------------------ timer

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(ctx, 15, Intent(ctx, AlertCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * One exact, idle-allowed alarm at the engine's next useful check (no data, a repeat, a mute window ending), so a
     * mute that ends between two readings (or with none coming) alerts on time. An inexact alarm may be delivered up
     * to 75 % of its delay late and later still in Doze (AlarmManager.set: "may be deferred"); with readings every 5 min
     * that made the alert after a mute wait for the next reading. USE_EXACT_ALARM is granted at install (API 33+);
     * the inexact alarm stays only for a phone where the user revoked it.
     */
    private fun schedule(ctx: Context) {
        val at = engine.nextCheckAt(effective(), Proto.nowS()) ?: return cancelTimer()
        val inMs = ((at - Proto.nowS()) * 1000).coerceAtLeast(1_000)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = SystemClock.elapsedRealtime() + inMs
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, intent(ctx))
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, intent(ctx))
    }

    private fun cancelTimer() { app?.let { it.getSystemService(AlarmManager::class.java).cancel(intent(it)) } }

    /**
     * Fire [kind] once as if the rules had: notification, sound, vibration, and with [share] (DebugReceiver) the
     * followers too. The settings screen's Test button doesn't share.
     */
    @Synchronized fun test(ctx: Context, kind: String, share: Boolean) {
        init(ctx)
        val sgv = when (kind) { Kind.VERY_LOW -> 50; Kind.LOW -> 62; Kind.VERY_HIGH -> 262; Kind.EXTREMELY_HIGH -> 312; else -> 204 }
        val r = Reading(Proto.nowS(), sgv, when (kind) { Kind.FALLING_FAST -> -2.6; Kind.RISING_FAST -> 2.4; else -> -0.4 })
        val fire = AlertEngine.Fire(kind, AlertEngine.newId(), 1, r, _settings.value.cfg(kind).value, Proto.nowS())
        val pub = publish
        if (!share) publish = null
        try { apply(ctx, listOf(fire)) } finally { publish = pub }
        note("(test)")
    }

    fun toJson(): JSONObject = JSONObject().put("role", role ?: JSONObject.NULL).put("settings", _settings.value.toJson())
        .put("state", JSONObject().apply { state.value.forEach { (g, s) -> put(g, JSONObject().put("kind", s.kind)
            .put("muted", s.muted ?: JSONObject.NULL).put("firedAt", s.firedAt).put("n", s.n)) } })
        .put("history", org.json.JSONArray(history.value))
}

private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

/** AlarmManager target of [Alerts]' timer. */
class AlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Alerts.check(context)
}
