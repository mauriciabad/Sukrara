// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensorend

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.core.NotifGroups
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.ui.MainActivity
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.ZoneId

/**
 * Sensor end reminders and the live countdown (wearer only; the rules are [SensorEndPlan]).
 *
 * Every trigger (the alarm, a new sensor, a settings change, boot, app update, time or time-zone change, and an unlock
 * that brings a reminder forward: [unlocked]) runs
 * [sync], which recomputes what should be showing *now* and sets one alarm for the next moment that matters. Nothing
 * is queued ahead, so a changed sensor, zone or setting can't leave a stale alarm behind.
 *
 * Alarms (developer.android.com/develop/background-work/services/alarms): reminders, the window start and the end
 * use `setExactAndAllowWhileIdle` (RTC_WAKEUP). Sukrara already holds `USE_EXACT_ALARM` for its glucose alerts (an
 * alarm app, granted at install, no user-facing permission), so exact alarms cost nothing extra here; a few per
 * sensor are negligible for the battery. Should exact alarms ever be unavailable, a 10-minute window is used. The
 * countdown's progress refresh every 15 min is a plain non-waking `set` (RTC): it only matters when the screen is on.
 *
 * The countdown is a Live Update (developer.android.com/develop/ui/views/notifications/live-update; promoted only
 * while the glucose chip is off, see [countdown]): an ongoing
 * ProgressStyle notification with `setRequestPromotedOngoing`, no custom views, not colorized, on a DEFAULT channel;
 * the status-bar chip shows the time left through `setWhen(end)` + `setUsesChronometer` + `setChronometerCountDown`
 * ("A timer in the chip can be shown when using Chronometer for when time"). The bar is the time left in the window
 * (Live Update design guide: "make sure the fill matches the remaining time"). The guide accepts scheduled activities
 * "only when the activity is imminent" and says "Don't repost Live Updates that the user dismissed. Use
 * setDeleteIntent to detect dismissed updates": a swiped-away countdown stays away for this sensor. No action buttons
 * (no Hide; the way out is a swipe or the settings, see [live]). At the end it becomes a normal "Sensor ended" notification (no alarm sound, channel default).
 *
 * The warm-up of a new sensor (no readings for ~60 min) uses the same Live Update, with the bar filling up to the
 * warm-up's end. A countdown never goes below zero: at the warm-up's end (an exact alarm) it turns into "Almost ready"
 * (full bar, no timer) until the first reading, and the end countdown hands over to "Sensor ended" at the
 * end. Not `setTimeoutAfter` on the countdowns: a timeout sends the delete intent, which reads as "dismissed". The
 * first reading cancels the warm-up notification outright (whatever it shows) and posts a separate, short "Sensor
 * ready" (setTimeoutAfter 10 min); every re-plan without a warm-up cancels it again, and the first reading's time is
 * remembered ([PlanMemory.firstReading]). On the phone (2026-10-18) "ready" had replaced the countdown at 21:07, an
 * app update at 21:08 restarted the process with no reading in memory, the restart re-posted the countdown, and
 * since "ready" counted as shown nothing ever removed it again: stuck, its timer below zero.
 */
object SensorEnd {
    private const val TAG = "Sukrara.SensorEnd"
    private const val PREFS = "sensor_end"
    const val CH_REMIND = "sensor_end"
    const val CH_COUNTDOWN = "sensor_countdown"
    const val ID_REMIND = 30
    const val ID_COUNTDOWN = 31
    const val ID_ENDED = 32
    /** The warm-up countdown, then "Almost ready" until the first reading. */
    const val ID_WARMUP = 33
    /** "Sensor ready" after the warm-up. */
    const val ID_READY = 34
    const val ACTION_ALARM = "app.mauri.sukrara.SENSOR_END_ALARM"
    const val ACTION_DISMISSED = "app.mauri.sukrara.SENSOR_COUNTDOWN_DISMISSED"
    const val ACTION_WARMUP_DISMISSED = "app.mauri.sukrara.SENSOR_WARMUP_DISMISSED"

    private val _config = MutableStateFlow(SensorEndConfig())
    val config: StateFlow<SensorEndConfig> = _config
    private var app: Context? = null
    /** Where [init]'s re-plan collectors run: Dispatchers.Default, or what a test hands [resetForTest]. */
    private var scope = replanScope(Dispatchers.Default)

    private fun replanScope(d: CoroutineDispatcher) = CoroutineScope(SupervisorJob() + d +
        CoroutineExceptionHandler { _, e -> Log.w(TAG, "re-plan failed: $e") })

    /**
     * Tests only. Robolectric keeps this object for the whole test JVM but gives every test a new Application: without
     * a reset, [init] would stay bound to the first test's context and its collectors would keep re-planning, at the
     * wall-clock time, from a background thread, cancelling the notifications a later test just posted (Robolectric's
     * notification list is static). Drops that context and its collectors; the next [init] starts them on
     * [dispatcher], which the test controls (a held one: only the test's own [sync] calls re-plan).
     */
    @androidx.annotation.VisibleForTesting
    @Synchronized fun resetForTest(dispatcher: CoroutineDispatcher) {
        scope.cancel()
        scope = replanScope(dispatcher)
        app = null
        debugNoReadings = false
    }

    @Synchronized fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        SensorLife.init(c)
        _config.value = prefs(c).getString("config", null)?.let { runCatching { SensorEndConfig.fromJson(JSONObject(it)) }.getOrNull() }
            ?: SensorEndConfig()
        channels(c)
        // a new sensor (or a changed end) and the countdown hours (shared with the graph ring) re-plan
        scope.launch { combine(SensorLife.current, SensorLife.countdown) { s, h -> s to h }.drop(1).collect { sync(c) } }
        // during the warm-up countdown, the first reading ends it ("Sensor ready")
        scope.launch {
            Live.status.map { it.last?.t }.distinctUntilChanged().drop(1).collect {
                // while a warm-up notification may be up (whatever "ready" says); a failing sync must not end this
                // collector (it would never see a reading again)
                val m = memory(c)
                val info = SensorLife.current.value
                if (m.warmupShown && m.firstReading == 0L && !m.warmupDismissed && info != null && keyOf(info) == m.key)
                    runCatching { sync(c) }.onFailure { e -> Log.w(TAG, "first-reading sync failed: $e") }
            }
        }
        sync(c)
    }

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, ctx.getString(R.string.channel_sensor_end), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = ctx.getString(R.string.channel_sensor_end_description) })
        // DEFAULT, not LOW: shown on the lock screen even where silent notifications are hidden; posts are silent
        nm.createNotificationChannel(NotificationChannel(CH_COUNTDOWN, ctx.getString(R.string.channel_sensor_countdown), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = ctx.getString(R.string.channel_sensor_countdown_description); setSound(null, null); enableVibration(false) })
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A settings change: saved, and slots that are already past don't fire because of it. */
    fun update(ctx: Context, f: (SensorEndConfig) -> SensorEndConfig) {
        init(ctx)
        val v = f(_config.value).normalized()
        _config.value = v
        prefs(ctx).edit().putString("config", v.toJson().toString()).apply()
        val now = System.currentTimeMillis() / 1000
        memory(ctx).let { m -> if (m.key.isNotEmpty()) save(ctx, m.copy(lastReminder = maxOf(m.lastReminder, now), countdownDismissed = false)) }
        sync(ctx)
    }

    fun setCountdownHours(ctx: Context, hours: Int) {
        SensorLife.setCountdownHours(ctx, hours)   // the collector re-plans
    }

    private fun memory(ctx: Context): PlanMemory = prefs(ctx).getString("memory", null)?.let {
        runCatching { JSONObject(it).let { o -> PlanMemory(o.getString("key"), o.getLong("last"), o.getBoolean("dismissed"), o.getBoolean("ended"),
            o.getBoolean("warmup"), o.getBoolean("warmupDismissed"), o.getBoolean("ready"), o.optLong("first", 0), o.optLong("unlock", 0)) } }.getOrNull()
    } ?: PlanMemory()

    private fun PlanMemory.toJson() = JSONObject().put("key", key).put("last", lastReminder).put("dismissed", countdownDismissed)
        .put("ended", endedShown).put("warmup", warmupShown).put("warmupDismissed", warmupDismissed).put("ready", readyShown)
        .put("first", firstReading).put("unlock", lastUnlock)

    private fun save(ctx: Context, m: PlanMemory) = prefs(ctx).edit().putString("memory", m.toJson().toString()).apply()

    /** One sensor = one serial + start; its end may move (an early end) without starting over. */
    private fun keyOf(info: SensorInfo) = "${info.serial}@${info.startEpoch}"

    @Synchronized fun sync(ctx: Context, now: Long = System.currentTimeMillis() / 1000) {
        init(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val info = SensorLife.current.value
        if (App.role(ctx) != "owner" || info == null) {   // only the wearer gets these
            NotifGroups.cancel(ctx, ID_REMIND, ID_COUNTDOWN, ID_WARMUP, ID_READY)
            if (App.role(ctx) != "owner") NotifGroups.cancel(ctx, ID_ENDED)
            alarms(ctx).cancel(alarmIntent(ctx))
            return
        }
        var mem = memory(ctx)
        if (mem.key != keyOf(info)) {   // a new sensor: start fresh, past slots don't fire
            mem = PlanMemory(keyOf(info), now)
            NotifGroups.cancel(ctx, ID_ENDED, ID_REMIND, ID_COUNTDOWN, ID_WARMUP, ID_READY)
        }
        val hours = SensorLife.countdownHours(ctx)
        val zone = ZoneId.systemDefault()
        val first = Live.status.value.last?.t?.takeIf { it >= info.startEpoch && !debugNoReadings }
        val p = SensorEndPlan.plan(info.startEpoch, info.endEpoch, _config.value, hours, now, zone, mem, info.warmupEndEpoch, first,
            SensorLife.WARMUP_GRACE_S)
        if (p.warmup) {
            // countdown → "Almost ready": a fresh post, so a dropped update can't leave the timer running below zero
            if (p.warmupWaiting && showsTimer(nm, ID_WARMUP)) nm.cancel(ID_WARMUP)
            post(ctx, ID_WARMUP) { if (p.warmupWaiting) waiting(ctx) else warmup(ctx, info, now) }
            mem = mem.copy(warmupShown = true)
        } else nm.cancel(ID_WARMUP)   // the first reading (or the give-up) ends it, whatever it shows
        if (p.ready) { post(ctx, ID_READY) { ready(ctx) }; mem = mem.copy(readyShown = true) }
        // remembered: a restart (Live starts empty) must not bring the warm-up back
        if (first != null && mem.firstReading == 0L) mem = mem.copy(firstReading = first)
        p.remind?.let {
            post(ctx, ID_REMIND) { reminder(ctx, info, now, zone, p.remindText) }
            mem = mem.copy(lastReminder = it)
        }
        if (p.countdown) post(ctx, ID_COUNTDOWN) { countdown(ctx, info, hours, now) } else nm.cancel(ID_COUNTDOWN)
        if (p.ended) {
            NotifGroups.cancel(ctx, ID_REMIND)
            post(ctx, ID_ENDED) { ended(ctx) }
            mem = mem.copy(endedShown = true)
        }
        save(ctx, mem)
        schedule(ctx, p.next, p.nextExact)
        Log.i(TAG, "sync end=${info.endEpoch} remind=${p.remind} countdown=${p.countdown} ended=${p.ended} warmup=${p.warmup} waiting=${p.warmupWaiting} ready=${p.ready} next=${p.next} exact=${p.nextExact}")
    }

    /**
     * The phone was unlocked (`ACTION_USER_PRESENT`, registered at runtime by the wearer service: it can't be declared
     * in the manifest). Recorded and re-planned only when it brings a reminder forward
     * ([SensorEndPlan.unlockWanted]: the first unlock of the morning, or of the evening before a night-time end).
     */
    fun unlocked(ctx: Context, now: Long = System.currentTimeMillis() / 1000) {
        init(ctx)
        val info = SensorLife.current.value ?: return
        if (App.role(ctx) != "owner") return
        val mem = memory(ctx)
        if (mem.key != keyOf(info)) return   // a sensor the planner hasn't seen yet: sync starts it fresh
        if (!SensorEndPlan.unlockWanted(info.startEpoch, info.endEpoch, _config.value, now, ZoneId.systemDefault(), mem)) return
        save(ctx, mem.copy(lastUnlock = now))
        sync(ctx, now)
    }

    /** The countdown was swiped away or hidden: not shown again for this sensor. */
    fun dismissed(ctx: Context) {
        init(ctx)
        save(ctx, memory(ctx).copy(countdownDismissed = true))
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_COUNTDOWN)
    }

    /** The warm-up countdown was swiped away or hidden: no warm-up countdown and no "Sensor ready" for this sensor. */
    fun warmupDismissed(ctx: Context) {
        init(ctx)
        save(ctx, memory(ctx).copy(warmupDismissed = true))
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_WARMUP)
    }

    // ------------------------------------------------------------------------------------------------ alarms

    private fun alarms(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, SensorEndReceiver::class.java).setAction(ACTION_ALARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(ctx: Context, at: Long?, exact: Boolean) {
        val am = alarms(ctx)
        val pi = alarmIntent(ctx)
        if (at == null) { am.cancel(pi); return }
        val ms = at * 1000
        when {
            // the progress tick: non-waking, at most TICK_WINDOW late (the plan keeps it clear of the next event)
            !exact -> am.setWindow(AlarmManager.RTC, ms, SensorEndPlan.TICK_WINDOW * 1000, pi)
            am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else -> am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60_000L, pi)
        }
    }

    // ------------------------------------------------------------------------------------------- notifications

    /**
     * Builds and posts; a failure (building included) is logged, never thrown into sync / App.onCreate. Reminders,
     * *Sensor ready* and *Sensor ended* go in Sukrara's info group; the countdowns (one at a time, a Live Update)
     * stay ungrouped (core/NotifGroups).
     */
    private fun post(ctx: Context, id: Int, build: () -> Notification) {
        runCatching {
            val n = build()
            if (n.group == null) NotifGroups.postUngrouped(ctx, id, n) else NotifGroups.post(ctx, id, n, NotifGroups.Group.INFO)
        }.onFailure { Log.w(TAG, "post $id: $it") }
    }

    /**
     * Promoted notifications are Android 16 QPR2 (API 36.1) APIs: on 36.0 `canPostPromotedNotifications` and
     * `setRequestPromotedOngoing` don't exist (NoSuchMethodError), so both are tried, like GlucoseNotification.
     */
    /** Whether notification [id] is showing with a timer (a chronometer). */
    private fun showsTimer(nm: NotificationManager, id: Int) = runCatching {
        nm.activeNotifications.any { it.id == id && it.notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) }
    }.getOrDefault(false)

    private fun canPromote(ctx: Context) =
        runCatching { ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(false)

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun time(ctx: Context, t: Long) = Clock.time(t)

    /** "Sensor ends today at 14:30" / "tomorrow at 14:30" / "on Wednesday at 14:30". */
    fun endsText(ctx: Context, end: Long, now: Long, zone: ZoneId): String {
        val at = time(ctx, end)
        return when (val d = SensorEndPlan.dayOffset(end, now, zone)) {
            0L -> ctx.getString(R.string.sensor_end_notif_today, at)
            1L -> ctx.getString(R.string.sensor_end_notif_tomorrow, at)
            else -> if (d < 7) ctx.getString(R.string.sensor_end_notif_on_day,
                java.time.Instant.ofEpochSecond(end).atZone(zone).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, ctx.resources.configuration.locales[0]), at)
                else ctx.getString(R.string.sensor_end_notif_on_day, java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(end * 1000)), at)
        }
    }

    /**
     * A reminder: a plain notification on the reminders channel (DEFAULT importance, "Information that should be seen
     * at the user's earliest convenience, but not interrupt what they're doing … task reminders"), never an alarm and
     * never a Live Update ("Inappropriate uses: … upcoming calendar events"; developer.android.com/develop/ui/views/
     * notifications/live-update). Words per the notification design guide (developer.android.com/design/ui/mobile/
     * guides/home-screen/notifications): the most important information in the title (when it ends), the text short
     * and not repeating it (what to do: [ReminderText]).
     */
    private fun reminder(ctx: Context, info: SensorInfo, now: Long, zone: ZoneId, kind: ReminderText): Notification =
        Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_sukrara)
            .setContentTitle(if (kind == ReminderText.TONIGHT) ctx.getString(R.string.sensor_end_notif_tonight, time(ctx, info.endEpoch))
                else endsText(ctx, info.endEpoch, now, zone))
            .setContentText(ctx.getString(when (kind) {
                ReminderText.ENDS -> R.string.sensor_end_notif_reminder_text
                ReminderText.SPARE -> R.string.sensor_end_notif_spare_text
                ReminderText.TONIGHT -> R.string.sensor_end_notif_tonight_text
            }))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .setGroup(NotifGroups.Group.INFO.key)
            .build()

    /**
     * A Live Update (promotable): ProgressStyle with one segment and no tracker icon (a plain bar), the timer as small icon, a chronometer
     * counting down to [until] (the chip's timer), silent updates, and **no action buttons** (the user wants no Hide
     * button on any notification, 0.11.20). The way out without a button:
     *  - **Swipe it away.** Since Android 14 an ongoing notification is user-dismissible ("Android 14 has changed the
     *    behavior to allow users to dismiss such notifications", except "When the phone is locked" and on *Clear all*;
     *    developer.android.com/about/versions/14/behavior-changes-all), and a promoted one can be "temporarily
     *    dismiss[ed] or demote[d] … to a standard notification" (developer.android.com/develop/ui/views/notifications/
     *    live-update). The delete intent ("Use setDeleteIntent to detect dismissed updates") marks it dismissed for
     *    this sensor, so it isn't reposted ("Don't repost Live Updates that the user dismissed").
     *  - **Turn it off for good**: Settings → Sensor end → *End countdown* / *Warm-up countdown*; or the system's own
     *    controls: the *Sensor countdowns* channel, and Live Updates per app (the guide's
     *    `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`; `canPostPromotedNotifications` follows it).
     * The guide asks for an "Unpin" action only when the user started following an event; these countdowns follow the
     * user's own sensor, so none is required.
     */
    private fun live(ctx: Context, title: String, text: String, until: Long?, perMille: Int, dismissAction: String, req: Int,
                     promote: Boolean): Notification {
        val dismissed = PendingIntent.getBroadcast(ctx, req, Intent(ctx, SensorEndReceiver::class.java).setAction(dismissAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = Notification.ProgressStyle()
            .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
            .setProgress(perMille)
        return Notification.Builder(ctx, CH_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_sym_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .apply {
                // until == null: no timer at all (a countdown is never left running past its end)
                if (until != null) setWhen(until * 1000).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                else setShowWhen(false)
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open(ctx))
            .setDeleteIntent(dismissed)
            .apply { if (promote && canPromote(ctx)) runCatching { setRequestPromotedOngoing(true) } }
            .build()
    }

    /**
     * The end countdown: the bar is the time left (it empties). The status bar shows one Live Update chip (seen on API
     * 36.1: the newer countdown took it from the glucose chip for good), so it asks for promotion only when the
     * glucose chip is off: the glucose value stays in the status bar, the countdown in the shade and lock screen.
     */
    private fun countdown(ctx: Context, info: SensorInfo, hours: Int, now: Long): Notification =
        live(ctx, ctx.getString(R.string.sensor_countdown_title, time(ctx, info.endEpoch)), ctx.getString(R.string.sensor_countdown_text),
            info.endEpoch, SensorEndPlan.leftPerMille(info.endEpoch, hours, now), ACTION_DISMISSED, 1,
            promote = !Features.value.chip)

    /** The warm-up: the bar is the time done (it fills up to the first reading). */
    private fun warmup(ctx: Context, info: SensorInfo, now: Long): Notification =
        live(ctx, ctx.getString(R.string.sensor_warmup_title), ctx.getString(R.string.sensor_warmup_text, time(ctx, info.warmupEndEpoch)),
            info.warmupEndEpoch, (SensorLife.warmupFraction(info, now) * 1000).toInt(), ACTION_WARMUP_DISMISSED, 2, promote = true)

    /** Past the warm-up's end without a reading yet: no timer, the bar full (the warm-up is done), until the first reading. */
    private fun waiting(ctx: Context): Notification =
        live(ctx, ctx.getString(R.string.card_almost_ready), ctx.getString(R.string.card_waiting_reading), null, 1000,
            ACTION_WARMUP_DISMISSED, 2, promote = true)

    /** Short-lived: gone by itself after 10 minutes. */
    private fun ready(ctx: Context): Notification =
        Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_sukrara)
            .setContentTitle(ctx.getString(R.string.sensor_ready_title))
            .setContentText(ctx.getString(R.string.sensor_ready_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open(ctx))
            .setTimeoutAfter(10 * 60_000L)
            .setAutoCancel(true)
            .setGroup(NotifGroups.Group.INFO.key)
            .build()

    private fun ended(ctx: Context): Notification =
        Notification.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_sukrara)
            // the card's wording (round sensor-life): "Sensor ended" + the "Replace sensor" action (opens the Sensor screen)
            .setContentTitle(ctx.getString(R.string.card_sensor_ended))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(MainActivity.sensorPendingIntent(ctx))
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.badge_replace_sensor),
                MainActivity.sensorPendingIntent(ctx)).build())
            .setAutoCancel(true)
            .setGroup(NotifGroups.Group.INFO.key)
            .build()

    /** Debug (DebugReceiver `cmd sensorEnd`): a made-up sensor that ends in [minutes]; the next real one replaces it. */
    fun fake(ctx: Context, minutes: Int, startedDaysAgo: Int = 14) {
        val now = System.currentTimeMillis() / 1000
        SensorLife.update(ctx, SensorInfo("TEST-$now", now - startedDaysAgo * 86_400L, now + minutes * 60L))
        sync(ctx)
    }

    /** Debug: readings that arrive are not the made-up sensor's first one (until [fakeFirstReading]). */
    @Volatile private var debugNoReadings = false

    /**
     * Debug (DebugReceiver `cmd sensorStart`): a made-up sensor that started [minutesAgo] ago (warm-up countdown);
     * readings from the current source don't end its warm-up until `--ez firstReading true` ([fakeFirstReading]).
     */
    fun fakeStart(ctx: Context, minutesAgo: Int) {
        val now = System.currentTimeMillis() / 1000
        debugNoReadings = true
        SensorLife.update(ctx, SensorLife.fromStart("TEST-$now", now - minutesAgo * 60L, 0))
        sync(ctx)
    }

    fun fakeFirstReading(ctx: Context) { debugNoReadings = false; sync(ctx) }

    fun state(ctx: Context): JSONObject {
        init(ctx)
        val m = memory(ctx)
        return JSONObject().put("config", _config.value.toJson()).put("countdownHours", SensorLife.countdownHours(ctx))
            .put("sensor", SensorLife.current.value?.let { JSONObject().put("serial", it.serial).put("start", it.startEpoch).put("end", it.endEpoch) })
            .put("memory", m.toJson())
            .put("canPostPromoted", canPromote(ctx))
    }
}

/** The alarm, a countdown swiped away, boot, app update, time and time-zone changes. */
class SensorEndReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching {
            when (i.action) {
                SensorEnd.ACTION_DISMISSED -> SensorEnd.dismissed(c)
                SensorEnd.ACTION_WARMUP_DISMISSED -> SensorEnd.warmupDismissed(c)
                else -> SensorEnd.sync(c)
            }
        }.onFailure { Log.w("Sukrara.SensorEnd", "${i.action}: $it") }
    }
}
