// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.SensorLife
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Words
import app.mauri.sukrara.settings.WearerSettings
import app.mauri.sukrara.widgets.SukraraHooks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Glucose alerts for the role that runs (spikes/alerts/SPEC.md): [AlertEngine] + settings + notifications + timer.
 *
 * - **Wearer**: the rules run on the source's readings and the treatment log. Two engines see the same readings and
 *   treatments: [engine] drives this phone's notifications and takes this phone's actions (Mute, Relax, Mute all);
 *   [shared] never does, and its firings and clearings go to the followers as `alert` events ([attach] `publish`,
 *   relays + BLE). So a mute on this phone never quiets the Mac or a follower (user, 2026-10-21: "if someone wants
 *   to mute, it shouldn't mute the other"); treatment mutes are shared data and quiet both.
 * - **Follower**, Settings from *Wearer* (source "wearer", the default): the wearer's `alert` events are mirrored
 *   ([onRemote], [AlertMirror]); only no data runs here (it's about this phone's link). *Default* / *Custom* (source
 *   "own"): the rules run here on the followed readings and the treatments the follower received, with the schema's
 *   defaults or this phone's own values. Either way its actions act on this phone only.
 *
 * This device's actions (round f30, SPEC §2 "States and exits"): [mute] / [relax] one side (the notification's *Mute…*
 * and *Relax*, the sheet, Active now), [unmute] / [unrelax], and [muteAll] / [unmuteAll] (the home's *Mute alerts*).
 * Mute and Relax end at their time or once the side is back in range by the margin ([AlertEngine.Back] → a history
 * note, the "muted until" notification goes, the widgets redraw). Mute all is kept across a restart.
 *
 * Callers on any thread; everything is serialised on this object.
 */
object Alerts {
    private const val TAG = "Sukrara.Alerts"
    private const val PREFS = "alerts"
    private const val EPISODES = "episodes"
    /** This device's Mute all end (epoch s; [AlertEngine.NEVER] = until turned back on), for the engine and the mirror. */
    private const val ALL_UNTIL = "allUntil"
    private val SIDES = listOf("low", "high", Kind.FALLING_FAST, Kind.RISING_FAST, Kind.NO_DATA)

    private val _own = MutableStateFlow(AlertSettings())
    /** This device's stored alert settings (a follower's "custom"); [update] edits them. */
    val own: StateFlow<AlertSettings> = _own
    private val _settings = MutableStateFlow(AlertSettings())
    /**
     * The effective settings the rules and screens use: the wearer's role = [own]; a follower = [own] with the mirrored
     * fields (each kind's on / threshold / repeat but no data's, the three quiet rules) from its Settings from, and
     * `source` derived from it ([WearerSettings.effectiveAlerts]).
     */
    val settings: StateFlow<AlertSettings> = _settings
    /** group → state, for the settings screen's "now" line. */
    val state = MutableStateFlow<Map<String, AlertEngine.State>>(emptyMap())
    /**
     * Side ("low", "high", …) → until (epoch s): this device's *Mute* of the side, and *Mute all* on every side
     * ([AlertEngine.NEVER] = no end). The badge under the value shows it (AlertStatus.Paused).
     */
    val paused = MutableStateFlow<Map<String, Long>>(emptyMap())
    /**
     * This device's *Mute all*: its end (epoch s), [AlertEngine.NEVER] = until turned back on, 0 = off. The home's
     * Mute alerts control, Active now, the muted pill (AlertStatus.Muted) and the card's TalkBack actions read it.
     */
    val allMuted = MutableStateFlow(0L)
    /**
     * The alerts on this device the user can act on now (SPEC §2 "States and exits"): muted, quiet after a treatment,
     * relaxed. The home sheet, the Alerts page's Active now and the accessibility actions read it. Mute all is
     * [allMuted].
     */
    val active = MutableStateFlow<List<Active>>(emptyList())

    /**
     * One entry of [active]: [kind]'s side is quiet for [quiet] ([AlertEngine.QUIET_NOTIFICATION] / `_CARBS` /
     * `_INSULIN` / `_FALLING`, null = only relaxed) until [until] (epoch s, 0 = no fixed end) and/or relaxed to
     * [relaxMin]. [mirrored]: the wearer's alert mirrored here (only this phone's own Mute / Relax show; the wearer's
     * phone decides the treatment mutes). [canMute] / [canRelax]: a (new) Mute / Relax applies.
     */
    data class Active(val kind: String, val quiet: String?, val until: Long, val relaxMin: Int, val canMute: Boolean,
                      val canRelax: Boolean, val mirrored: Boolean)

    /** What happened lately, newest first (settings screen + DebugReceiver). */
    val history = MutableStateFlow<List<AlertNote>>(emptyList())

    private var app: Context? = null
    private var engine = AlertEngine()
    /** Wearer only: the rules without this phone's actions; what the followers get (see the class comment). */
    private var shared = AlertEngine()
    private var role: String? = null
    private var who: String? = null
    private var doses: () -> AlertEngine.Doses = { AlertEngine.Doses() }
    private var publish: ((JSONObject) -> Unit)? = null
    /** group → id of the alert whose notification is showing (a clear only removes its own firing). */
    private val posted = HashMap<String, String>()
    private val seenRemote = LinkedHashSet<String>()
    /** group → what its notification shows, to post it again (without sound) after *Relax* or a bad typed length. */
    private val shown = HashMap<String, Shown>()
    private data class Shown(val kind: String, val sgv: Int?, val rate: Double?, val th: Double, val at: Long, val treated: Int)
    /** Mirroring the wearer: which of its alerts show here, and this phone's own actions on them. */
    private var mirror = AlertMirror()
    /** Mirroring: id → what the wearer's firing shows, per group the newest (shown here or not), for Unmute. */
    private val remote = HashMap<String, Pair<String, Shown>>()
    /**
     * group → the kind whose "muted until" notification is up (AlertNotifier.postMuted): removed with the side's Mute
     * (Unmute, back by the margin; its own timeout at the mute's end) or a new alert of the group.
     */
    private val mutedShown = HashMap<String, String>()
    /** Set while a Mute runs: (side, until). Its alert's clear is the mute, not the episode ending. */
    private var muting: Pair<String, Long>? = null
    /** Set while Mute all starts: the clears it causes aren't the episodes ending. */
    private var allMuting = false
    /** Once per process: the saved episodes are read back on the first attach. */
    private var resumed = false

    @Synchronized fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("json", null)
        _own.value = json?.let { runCatching { AlertSettings.fromJson(JSONObject(it)) }.getOrNull() } ?: AlertSettings()
        _settings.value = WearerSettings.effectiveAlerts(_own.value)
        // Mute all survives a restart: the mirror's from here, the engine's from its snapshot (and this, see attach)
        mirror.allUntil = savedAll(ctx)
        allMuted.value = mirror.allUntil
        paused.value = pausedNow()
        AlertNotifier.channels(ctx)
    }

    private fun savedAll(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(ALL_UNTIL, 0).takeIf { it > Proto.nowS() } ?: 0

    private fun saveAll(ctx: Context, until: Long) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(ALL_UNTIL, until).apply()

    /** Called by the role's service. [who]: the followed person's label on a follower's notifications. */
    @Synchronized fun attach(ctx: Context, role: String, doses: () -> AlertEngine.Doses, publish: ((JSONObject) -> Unit)?, who: String? = null,
                             newest: Reading? = null) {
        init(ctx)
        engine.seed(newest)   // after a restart "no data" still counts from the last stored reading
        shared.seed(newest)
        if (!resumed) { resumed = true; resume(ctx, role) }
        this.role = role
        this.doses = doses
        this.publish = publish
        this.who = who
        // Mute all is the device's, whichever role saved the episodes last
        val all = savedAll(ctx)
        val now = Proto.nowS()
        if (all > 0 && engine.allMuted(now) != all) {
            allMuting = true
            try { apply(ctx, engine.muteAll(all, effective(), doses(), now)) } finally { allMuting = false }
        }
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
        // the followers' side goes on too (its alerts showing there need nothing again)
        j.optJSONObject("shared")?.let { sj -> runCatching { shared.restore(sj, _settings.value, Proto.nowS()) } }
        for (f in shown) {
            show(ctx, Shown(f.kind, f.reading?.sgv, f.reading?.rate, f.th, f.at, f.treated), silent = true)
            posted[Kind.group(f.kind)] = f.id
        }
        if (shown.isNotEmpty()) note(AlertNote(Proto.nowS(), AlertNote.RESUMED, shown.map { it.kind }))
        refresh(ctx)
    }

    @Synchronized fun detach() { role = null; publish = null; cancelTimer() }

    /**
     * Tests: the singleton outlives a test, so start from nothing (episodes, mutes, relaxes, Mute all, notifications
     * known, history), as after a fresh install; [keepSaved] keeps what's stored, as after a restart.
     */
    @Synchronized internal fun resetForTests(keepSaved: Boolean = false) {
        if (!keepSaved) app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        engine = AlertEngine(); shared = AlertEngine(); mirror = AlertMirror()
        posted.clear(); shown.clear(); remote.clear(); mutedShown.clear(); seenRemote.clear()
        role = null; publish = null; app = null; resumed = false
        state.value = emptyMap(); paused.value = emptyMap(); allMuted.value = 0; active.value = emptyList(); history.value = emptyList()
    }

    /** Edits [own] (this device's settings; the function gets [own]); [settings] follows. */
    @Synchronized fun update(ctx: Context, f: (AlertSettings) -> AlertSettings) {
        init(ctx)
        val s = f(_own.value)
        _own.value = s
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", s.toJson().toString()).apply()
        _settings.value = WearerSettings.effectiveAlerts(s)
        check(ctx)
    }

    /** [settings] again from [own] and the follower's Settings from (WearerSettings calls it on a change). */
    @Synchronized fun recompute() {
        _settings.value = WearerSettings.effectiveAlerts(_own.value)
        app?.let { check(it) }
    }

    /**
     * What logging rapid insulin and/or carbs at [at] silences on this phone, for the treatment dialog
     * ("Highs quiet for 1 h"), or null: alerts or the rule off, the window already over, or a follower that mirrors
     * the wearer (the wearer's phone decides).
     */
    fun quietNote(ctx: Context, rapid: Boolean, carbs: Boolean, at: Long, now: Long): String? {
        init(ctx)
        val s = _settings.value
        if (!s.on || (role == "follower" && s.source == "wearer")) return null
        fun left(on: Boolean, min: Int) = if (on) ((at + min * 60L - now) / 60).takeIf { it > 0 }?.let { Durations.text(it) } else null
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

    /** A follower that mirrors the wearer's alerts (its own Mute / Relax act on them through [mirror]). */
    private fun mirroring() = role == "follower" && _settings.value.source == "wearer"

    @Synchronized fun onReading(ctx: Context, r: Reading) {
        if (role == null) return
        val s = effective()
        val d = doses()
        val now = Proto.nowS()
        engine.sensor = SensorLife.current.value
        shared.sensor = engine.sensor
        val pub = if (publish != null) shared.reading(r, s, d, now) else emptyList()
        // mirroring: the wearer's alerts' Mute / Relax here end by the margin on the readings this phone gets
        val back = if (mirroring()) mirror.reading(r, _settings.value, now) else emptyList()
        back.forEach { back(ctx, it) }
        apply(ctx, engine.reading(r, s, d, now), pub, changed = back.isNotEmpty())
    }

    /** Re-evaluate now: timer, settings change, a treatment logged. */
    @Synchronized fun check(ctx: Context) {
        if (role == null) return
        val s = effective()
        val d = doses()
        val now = Proto.nowS()
        engine.sensor = SensorLife.current.value
        shared.sensor = engine.sensor
        val pub = if (publish != null) shared.evaluate(s, d, now) else emptyList()
        if (mirror.allUntil in 1..now || engine.allUntil in 1..now) allBack()   // Mute all's time is over
        var changed = false
        if (mirroring()) {
            mirror.reading(engine.last, _settings.value, now).forEach { back(ctx, it); changed = true }
            // Mute all's time is over: the wearer's alerts still up show at once
            mirror.expire(now).also { if (it.isNotEmpty()) changed = true }.forEach { showRemote(ctx, it) }
        } else mirror.expire(now)
        apply(ctx, engine.evaluate(s, d, now), pub, changed)
    }

    /** The followers' copy of the rules ([shared]) fired or cleared: to them as `alert` events (SPEC §4). */
    private fun share(actions: List<AlertEngine.Action>) {
        val p = publish ?: return
        for (a in actions) when (a) {
            is AlertEngine.Fire -> p(event(a.kind, "fire", a.id, a.at, a.reading, a.th, a.n, a.treated))
            is AlertEngine.Clear -> p(event(a.kind, "clear", a.id, a.at, shared.last, null, 0, 0))
            is AlertEngine.Back -> Unit   // the shared rules have no actions of this device
        }
    }

    /**
     * [actions]: this phone's notifications; [pub]: the followers' copy of the rules (wearer only, [share]);
     * [changed]: something else changed (a mirrored side back in range), redraw the widgets too.
     */
    private fun apply(ctx: Context, actions: List<AlertEngine.Action>, pub: List<AlertEngine.Action> = emptyList(), changed: Boolean = false) {
        share(pub)
        for (a in actions) when (a) {
            is AlertEngine.Fire -> {
                mutedShown.remove(Kind.group(a.kind))
                show(ctx, Shown(a.kind, a.reading?.sgv, a.reading?.rate, a.th, a.at, a.treated))
                posted[Kind.group(a.kind)] = a.id
                note(AlertNote(Proto.nowS(), AlertNote.FIRED, listOf(a.kind), a.treated, a.n, a.reading?.sgv))
            }
            is AlertEngine.Clear -> {
                val m = muting?.takeIf { it.first == Kind.side(a.kind) }
                if (posted[Kind.group(a.kind)] == a.id) {
                    posted.remove(Kind.group(a.kind))
                    if (m != null) showMuted(ctx, a.kind, m.second) else AlertNotifier.cancel(ctx, a.kind)
                }
                // muted: the history already says "Low muted for 45 min"; the episode goes on
                if (m == null && !allMuting) note(AlertNote(Proto.nowS(), AlertNote.CLEARED, listOf(a.kind)))
            }
            is AlertEngine.Back -> back(ctx, a.kind)
        }
        state.value = engine.states()
        refresh(ctx)
        // A mute ending between two readings fires from the timer: redraw the widgets now, or their last-treatment
        // ring still shows the minute left from the last reading while the alert sounds.
        if (actions.isNotEmpty() || pub.isNotEmpty() || changed) SukraraHooks.refresh(ctx, "alerts", force = true)
        role?.let { r -> ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(EPISODES, JSONObject().put("role", r).put("episodes", engine.snapshot())
                .put("shared", shared.snapshot()).toString()).apply() }
        schedule(ctx)
    }

    /** [side]'s Mute / Relax ended: back in range by the margin. The history says so, its "muted until" notification goes. */
    private fun back(ctx: Context, side: String) {
        note(AlertNote(Proto.nowS(), AlertNote.BACK, listOf(Kind.main(side))))
        val g = Kind.group(Kind.main(side))
        mutedShown[g]?.takeIf { Kind.side(it) == side }?.let { mutedShown.remove(g); AlertNotifier.cancel(ctx, it) }
    }

    /** Posts [x] with the notification actions that apply to it now, and remembers it for [repost]. */
    private fun show(ctx: Context, x: Shown, silent: Boolean = false) {
        val s = _settings.value
        shown[Kind.group(x.kind)] = x
        val relaxed = if (mirrors(x.kind)) mirror.relaxed(x.kind) else engine.relaxed(x.kind)
        val rep = s.cfg(x.kind).repeatMin
        val acts = AlertNotifier.Actions(mute = s.muteChoices(),
            relax = !relaxed && (mirrors(x.kind) || rep in 1 until s.relaxMin), relaxedMin = if (relaxed) s.relaxMin else 0)
        AlertNotifier.post(ctx, x.kind, x.sgv, x.rate, x.th, x.at, who, s.cfg(x.kind).alarm, x.treated, silent, acts)
    }

    /** [kind]'s alert notification becomes "muted until [until]" (AlertNotifier.postMuted). */
    private fun showMuted(ctx: Context, kind: String, until: Long) {
        val x = shown[Kind.group(kind)] ?: return AlertNotifier.cancel(ctx, kind)
        AlertNotifier.postMuted(ctx, x.kind, x.sgv, x.rate, x.th, x.at, who, _settings.value.cfg(x.kind).alarm, until, Proto.nowS(),
            _settings.value.muteChoices())
        mutedShown[Kind.group(kind)] = x.kind
    }

    /** The notification of [kind]'s group again, without sound (its actions updated; a typed reply's spinner gone). */
    @Synchronized fun repost(ctx: Context, kind: String) {
        init(ctx)
        val x = shown[Kind.group(kind)] ?: return
        if (posted.containsKey(Kind.group(kind)) || mirror.showing(kind)) show(ctx, x, silent = true)
        else sideMutes()[Kind.side(kind)]?.let { until -> showMuted(ctx, kind, until) }   // a bad Change… reply
    }

    /** The wearer's alerts are mirrored here, so an action on [kind] acts on this phone's copy only. */
    private fun mirrors(kind: String) = mirroring() && kind != Kind.NO_DATA

    /** The wearer's firing [id] (still up there) shows here at once: after Unmute, Stop relaxing, the end of Mute all. */
    private fun showRemote(ctx: Context, id: String) {
        remote.values.firstOrNull { it.first == id }?.let { show(ctx, it.second) }
    }

    /** [active] from the engine (own rules) and the mirror (the wearer's alerts shown here). */
    private fun activeNow(): List<Active> {
        val now = Proto.nowS()
        val own = if (role == null) emptyList() else engine.quiets(effective(), doses(), now).map {
            Active(it.kind, it.quiet, it.until, it.relaxMin, true, it.canRelax, mirrored = false)
        }
        val mirrored = if (!mirroring()) emptyList() else
            listOf(Kind.LOW, Kind.HIGH, Kind.FALLING_FAST, Kind.RISING_FAST).mapNotNull { side ->
                val sn = mirror.snooze(side) ?: return@mapNotNull null
                val k = remote[Kind.group(side)]?.second?.kind?.takeIf { Kind.side(it) == Kind.side(side) } ?: side
                val until = sn.until.takeIf { it > now } ?: 0
                if (until == 0L && sn.relaxMin == 0) null
                else Active(k, if (until > 0) AlertEngine.QUIET_NOTIFICATION else null, until, sn.relaxMin, true, sn.relaxMin == 0, mirrored = true)
            }
        return own.filter { o -> mirrored.none { Kind.group(it.kind) == Kind.group(o.kind) } } + mirrored
    }

    /** Side → the end of this device's Mute of it (not Mute all). */
    private fun sideMutes(): Map<String, Long> {
        val now = Proto.nowS()
        val m = engine.snoozes(now).filterValues { it.until > now }.mapValues { it.value.until }.toMutableMap()
        mirror.snoozes(now).filterValues { it.until > now }.forEach { (side, sn) -> m[side] = maxOf(m[side] ?: 0, sn.until) }
        return m
    }

    /** Mute all's end on this device (engine or mirror), 0 = off. */
    private fun allNow(now: Long = Proto.nowS()) = maxOf(engine.allMuted(now), mirror.allUntil.takeIf { it > now } ?: 0)

    private fun pausedNow(): Map<String, Long> {
        val m = sideMutes().toMutableMap()
        val all = allNow()
        if (all > 0) for (side in SIDES) m[side] = maxOf(m[side] ?: 0, all)
        return m
    }

    /**
     * The flows the UI reads ([paused], [allMuted], [active]) from the engine and the mirror. Mute all ending (its
     * time, Turn alerts back on) leaves a history note here, once. "Muted until" notifications of sides no longer
     * muted are forgotten (their own timeout removes them).
     */
    private fun refresh(ctx: Context) {
        val all = allNow()
        if (all == 0L) allBack()
        allMuted.value = all
        val sides = sideMutes()
        mutedShown.entries.removeAll { (_, k) -> Kind.side(k) !in sides }
        paused.value = pausedNow()
        active.value = activeNow()
    }

    /** Mute all ended (its time, Turn alerts back on): the history says so once, before the alerts it lets through. */
    private fun allBack() {
        if (allMuted.value == 0L) return
        allMuted.value = 0
        note(AlertNote(Proto.nowS(), AlertNote.ALL_BACK, emptyList()))
    }

    /**
     * *Mute* [kind]'s side for [min] minutes on this phone only (the notification's *Mute…* reply with
     * [notification], the sheet's and Active now's lengths otherwise): the wearer's [shared] rules (what the followers
     * get) never see it, and a follower that mirrors the wearer applies it to the wearer's alerts here ([AlertMirror]).
     * On a muted side it changes the end (*Change…*); the "muted until" notification follows.
     */
    @Synchronized fun mute(ctx: Context, kind: String, min: Int, notification: Boolean = false) {
        init(ctx)
        val now = Proto.nowS()
        val m = min.coerceIn(AlertSettings.MUTE_RANGE)
        val until = now + m * 60L
        val side = Kind.side(kind)
        val wasMuted = sideMutes().containsKey(side)
        note(AlertNote(now, AlertNote.MUTED, listOf(kind), n = m, notification = notification))
        if (mirrors(kind)) {
            if (mirror.mute(kind, until) || (wasMuted && Kind.group(kind) in mutedShown)) showMuted(ctx, kind, until)
            return changed(ctx)
        }
        if (role == null) return
        engine.sensor = SensorLife.current.value
        muting = side to until
        try { apply(ctx, engine.mute(kind, m, effective(), doses(), now)) } finally { muting = null }
        if (wasMuted && mutedShown[Kind.group(kind)]?.let { Kind.side(it) == side } == true) showMuted(ctx, kind, until)
        changed(ctx)
    }

    /** *Relax* [kind]'s side on this phone only: its reminders come every [AlertSettings.relaxMin] until back in range. */
    @Synchronized fun relax(ctx: Context, kind: String) {
        init(ctx)
        val now = Proto.nowS()
        val s = _settings.value
        note(AlertNote(now, AlertNote.RELAXED, listOf(kind), n = s.relaxMin))
        if (mirrors(kind)) { mirror.relax(kind, s.relaxMin); repost(ctx, kind); return changed(ctx) }
        if (role == null) return
        engine.sensor = SensorLife.current.value
        apply(ctx, engine.relax(kind, effective(), doses(), now))
        repost(ctx, kind)
        changed(ctx)
    }

    /**
     * *Unmute* (this device's Mute) / *Alert me anyway* (a treatment or falling-high mute) on [kind]'s side, this
     * phone only (SPEC §2 "States and exits"): still out of range → it alerts now; back in range → nothing. The "muted
     * until" notification goes; history, badge, widgets and ring update at once.
     */
    @Synchronized fun unmute(ctx: Context, kind: String) {
        init(ctx)
        val now = Proto.nowS()
        val g = Kind.group(kind)
        note(AlertNote(now, AlertNote.UNMUTED, listOf(kind)))
        mutedShown.remove(g)?.let { AlertNotifier.cancel(ctx, it) }
        if (mirrors(kind)) {
            AlertNotifier.cancel(ctx, kind)
            mirror.unmute(kind, now)?.let { id -> showRemote(ctx, id) }
            return changed(ctx)
        }
        if (role == null) return
        engine.sensor = SensorLife.current.value
        apply(ctx, engine.unmute(kind, effective(), doses(), now))
        changed(ctx)
    }

    /** *Stop relaxing* on [kind]'s side, this phone only: the kind's interval again (an overdue repeat now). */
    @Synchronized fun unrelax(ctx: Context, kind: String) {
        init(ctx)
        val now = Proto.nowS()
        note(AlertNote(now, AlertNote.UNRELAXED, listOf(kind), n = _settings.value.cfg(kind).repeatMin))
        if (mirrors(kind)) {
            val id = mirror.unrelax(kind, now)
            if (id != null) showRemote(ctx, id) else repost(ctx, kind)
            return changed(ctx)
        }
        if (role == null) return
        engine.sensor = SensorLife.current.value
        apply(ctx, engine.unrelax(kind, effective(), doses(), now))
        repost(ctx, kind)
        changed(ctx)
    }

    /**
     * *Mute all* on this device for [min] minutes, [NEVER_MIN] (0) = until turned back on (SettingsSpec MUTE_ALL's
     * special 0 = "never"): every kind, very low, extremely high and no data included, the wearer's mirrored alerts
     * too. The alerts showing go; a later call changes the end. Never shared with the followers ([shared]).
     */
    @Synchronized fun muteAll(ctx: Context, min: Int) {
        init(ctx)
        val now = Proto.nowS()
        val m = if (min <= NEVER_MIN) NEVER_MIN else min.coerceIn(AlertSettings.MUTE_ALL_RANGE)
        val until = if (m == NEVER_MIN) AlertEngine.NEVER else now + m * 60L
        note(AlertNote(now, AlertNote.ALL_MUTED, emptyList(), n = m))
        saveAll(ctx, until)
        for (g in mirror.muteAll(until)) { cancelGroup(ctx, g) }
        allMuting = true
        try { apply(ctx, engine.muteAll(until, effective(), doses(), now)) } finally { allMuting = false }
        changed(ctx)
    }

    /** *Turn alerts back on*: Mute all ends; whatever still matches (and isn't muted otherwise) alerts at once. */
    @Synchronized fun unmuteAll(ctx: Context) {
        init(ctx)
        val now = Proto.nowS()
        saveAll(ctx, 0)
        allBack()
        val ids = mirror.unmuteAll(now)
        if (mirroring()) ids.forEach { showRemote(ctx, it) }
        apply(ctx, engine.unmuteAll(effective(), doses(), now))
        changed(ctx)
    }

    /** The notification of [group] goes (Mute all), with what it stood for. */
    private fun cancelGroup(ctx: Context, group: String) {
        val k = shown[group]?.kind ?: remote[group]?.second?.kind ?: return
        AlertNotifier.cancel(ctx, k)
    }

    /** After a user's action: badge, Active now, widgets, ring and the lock screen at once. */
    private fun changed(ctx: Context) {
        refresh(ctx)
        SukraraHooks.refresh(ctx, "alerts", force = true)
        if (role != null) schedule(ctx)
    }

    /** [muteAll]'s length for "until turned back on". */
    const val NEVER_MIN = 0

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
        val at = body.optLong("at")
        when (st) {
            // this phone's own Mute / Relax / Mute all decide ([AlertMirror]); the wearer's never reach here
            "fire" -> {
              val treated = body.optInt("tr", 0)
              val x = Shown(k, body.optIntOrNull("sgv"), body.optDoubleOrNull("r"), body.optDouble("th", 0.0), at, treated)
              remote[Kind.group(k)] = id to x
              if (mirror.fire(k, id, body.optInt("n", 1), at)) {
                mutedShown.remove(Kind.group(k))
                show(ctx, x)
                note(AlertNote(Proto.nowS(), AlertNote.FIRED, listOf(k), treated, 1, body.optIntOrNull("sgv"), remote = true))
              }
            }
            "clear" -> if (mirror.clear(k, id)) {
                AlertNotifier.cancel(ctx, k)
                note(AlertNote(Proto.nowS(), AlertNote.CLEARED, listOf(k), remote = true))
            }
        }
        refresh(ctx)
        // The wearer's low at its carbs' mute end arrives between two readings: redraw the widgets (ring, badge) now.
        SukraraHooks.refresh(ctx, "alerts", force = true)
    }

    private fun note(n: AlertNote) {
        Log.i(TAG, n.english())
        history.value = (listOf(n) + history.value).take(20)
    }

    // ------------------------------------------------------------------------------------------------ timer

    private fun intent(ctx: Context) = PendingIntent.getBroadcast(ctx, 15, Intent(ctx, AlertCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * One exact, idle-allowed alarm at the engine's next useful check (no data, a repeat, a mute window ending, Mute
     * all ending), so a mute that ends between two readings (or with none coming) alerts on time. An inexact alarm
     * may be delivered up to 75 % of its delay late and later still in Doze (AlarmManager.set: "may be deferred");
     * with readings every 5 min that made the alert after a mute wait for the next reading. USE_EXACT_ALARM is
     * granted at install (API 33+); the inexact alarm stays only for a phone where the user revoked it.
     */
    private fun schedule(ctx: Context) {
        val now = Proto.nowS()
        val mirrorEnd = mirror.allUntil.takeIf { it in (now + 1) until AlertEngine.NEVER }
        val at = listOfNotNull(engine.nextCheckAt(effective(), now), mirrorEnd).minOrNull() ?: return cancelTimer()
        val inMs = ((at - now) * 1000).coerceAtLeast(1_000)
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
        apply(ctx, listOf(fire))
        if (share) publish?.invoke(event(kind, "fire", fire.id, fire.at, r, fire.th, 1, 0))
        history.value.firstOrNull()?.let { t -> history.value = listOf(t.copy(test = true)) + history.value.drop(1) }
    }

    fun toJson(): JSONObject = JSONObject().put("role", role ?: JSONObject.NULL).put("settings", _settings.value.toJson())
        .put("state", JSONObject().apply { state.value.forEach { (g, s) -> put(g, JSONObject().put("kind", s.kind)
            .put("muted", s.muted ?: JSONObject.NULL).put("firedAt", s.firedAt).put("n", s.n)) } })
        .put("allMuted", allMuted.value)
        .put("history", org.json.JSONArray(history.value.map { it.english() }))
}

private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null

/**
 * AlarmManager target of [Alerts]' timer, and `TIME_SET`: the timer is an elapsed-time alarm planned from the wall
 * clock, so after the clock is moved the check runs again at once (and plans the timer anew). Validation 21: with the
 * clock moved 27 min forward the high stayed up and no "no data" came, because the alarm still waited the real
 * minutes; a real gap alerts on time (scenario "A high with no more readings…").
 */
class AlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Alerts.check(context)
}

/**
 * One line of the alerts page's "lately" list: what happened ([FIRED], [CLEARED], [RESUMED] after a restart, this
 * device's actions, [BACK], Mute all) to which kinds, with the episode's [treated] count, the repeat or the minutes
 * [n] and the reading. [notification]: the action came from the alert notification. Worded at the UI edge
 * (AlertsScreen) in the app's language; [english] is the log's and DebugReceiver's wording (the engine's [Kind.message]).
 */
data class AlertNote(val at: Long, val what: String, val kinds: List<String>, val treated: Int = 0, val n: Int = 0, val sgv: Int? = null,
                     val remote: Boolean = false, val test: Boolean = false, val notification: Boolean = false) {
    fun english(): String {
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(at * 1000))
        val k = kinds.firstOrNull() ?: ""
        val w = Words()
        val text = when (what) {
            FIRED -> "${Kind.message(k, treated)}${if (n > 1) " ($n)" else ""}${if (remote) " (from the wearer)" else ""} · ${sgv ?: "–"} mg/dL"
            CLEARED -> "${Kind.label(k)} cleared${if (remote) " (by the wearer)" else ""}"
            MUTED -> "${Kind.label(k)} muted for ${Durations.text(n, w)}${if (notification) " (notification)" else ""}"
            RELAXED -> "${Kind.label(k)} relaxed: every ${Durations.text(n, w)}"
            UNMUTED -> "${Kind.label(k)} unmuted"
            UNRELAXED -> "${Kind.label(k)} back to every ${Durations.text(n, w)}"
            BACK -> "${Kind.label(k)} back in range: mute and relax ended"
            ALL_MUTED -> if (n == Alerts.NEVER_MIN) "All alerts muted until turned back on" else "All alerts muted for ${Durations.text(n, w)}"
            ALL_BACK -> "Alerts back on"
            else -> "resumed ${kinds.joinToString { Kind.label(it) }} (no sound)"
        }
        return "$t  $text${if (test) " (test)" else ""}"
    }

    companion object {
        const val FIRED = "fired"
        const val CLEARED = "cleared"
        const val RESUMED = "resumed"
        const val MUTED = "muted"
        const val RELAXED = "relaxed"
        const val UNMUTED = "unmuted"
        const val UNRELAXED = "unrelaxed"
        /** The side's Mute / Relax ended: back in range by the margin. */
        const val BACK = "back"
        /** Mute all started ([n] minutes, [Alerts.NEVER_MIN] = until turned back on). */
        const val ALL_MUTED = "all_muted"
        /** Mute all ended: its time, or Turn alerts back on. */
        const val ALL_BACK = "all_back"
    }
}
