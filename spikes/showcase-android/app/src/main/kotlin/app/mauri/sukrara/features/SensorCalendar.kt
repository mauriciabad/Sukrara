// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL
import android.provider.CalendarContract.CALLER_IS_SYNCADAPTER
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.core.SensorLife
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/**
 * "Sensor end in calendar": one event per sensor at the moment it stops working, written by the wearer's app
 * (followers never write). Juggluco does this by opening the calendar app's "new event" screen
 * (`Intent.ACTION_INSERT` on `Events.CONTENT_URI`, third_party/Juggluco/Common/src/main/java/tk/glucodata/ScanNfcV.java
 * `insertcalendar`): the user saves it by hand, once per sensor. Sukrara writes the events itself through
 * [android.provider.CalendarContract], so they appear, move and go away without a tap.
 *
 * Where (research 2026-10-18, sources in docs/FEATURES.md "Sensor end in calendar"):
 * - default: **its own "Sukrara" calendar** with `ACCOUNT_TYPE_LOCAL`, the only way the provider documents for an app
 *   to create a calendar ("If a local calendar is required an app can do so by inserting as a sync adapter and using an
 *   ACCOUNT_TYPE of ACCOUNT_TYPE_LOCAL", CalendarContract.Calendars). Calendar apps on the phone show it (Google
 *   Calendar lists device calendars); it is never synced, so it stays on this phone.
 * - a calendar created under the user's Google account (`com.google`) through the sync-adapter URI is not uploaded:
 *   sync-adapter writes are not marked DIRTY and notify with syncToNetwork=false (CALLER_IS_SYNCADAPTER docs), the row
 *   has no server `_SYNC_ID`, and Google's adapter treats the server as the truth (bullet-to/device_calendar_plus #132:
 *   sync-adapter-mode writes to Google calendars are undone on the next sync). Not used.
 * - optional: **an existing calendar the user picks** (e.g. their Google one). Written as a normal app (plain URI), so
 *   the events are DIRTY and Google's adapter uploads them to every device; deletes leave the DELETED=1 tombstone the
 *   adapter uploads (the fix in device_calendar_plus #167).
 * - a real Google calendar via the Google Calendar API would need a Cloud project, OAuth consent and Play services
 *   sign-in: too heavy for one event per two weeks. Not used.
 *
 * Events: a point event (start = end) at the sensor's end, free, no reminder of its own (Sukrara notifies); it moves
 * earlier when the sensor fails early, and stays put once its time has passed ([keepTime]). Kept for
 * past sensors (a history of sensor changes); a sensor's event still in the future is removed once another sensor is
 * the current one (it was replaced early, so that end won't happen). Turning the setting off deletes the Sukrara
 * calendar with all its events, or Sukrara's events in the picked calendar.
 */
object SensorCalendar {
    private const val TAG = "SensorCalendar"
    const val ACCOUNT = "Sukrara"
    /** brand.json "top" (spikes/brand/assets/sukrara/brand.json). */
    const val COLOR = 0xFF1BB5C9.toInt()
    /** [Settings.target] for Sukrara's own calendar. */
    const val OWN = -1L
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    /** [target] is a calendar id, or [OWN]; [targetName] is what the settings row shows for it. */
    data class Settings(val on: Boolean = false, val target: Long = OWN, val targetName: String = "")

    /** A calendar the user may pick: writable, visible, not Sukrara's own; [synced] = its account syncs (not local). */
    data class Choice(val id: Long, val name: String, val account: String, val synced: Boolean)

    /** One event, independent of the provider (unit-tested). [key] identifies the sensor across restarts. */
    data class EventSpec(val key: String, val title: String, val description: String, val startMs: Long, val endMs: Long)

    fun key(serial: String) = "sensor:$serial"

    /** The event for a sensor: a point event at [endEpoch] (unix s). */
    fun event(serial: String, endEpoch: Long, title: String, description: String) =
        EventSpec(key(serial), title, description, endEpoch * 1000, endEpoch * 1000)

    /**
     * Which of the written events ([existing]: key → event start, ms) to delete when [currentKey] is the sensor now:
     * the other sensors' events still in the future (those sensors were replaced before their end).
     */
    fun stale(existing: Map<String, Long>, currentKey: String?, nowMs: Long): Set<String> =
        if (currentKey == null) emptySet() else existing.filter { (k, start) -> k != currentKey && start > nowMs }.keys

    /**
     * Whether an existing event keeps its time ([eventStartMs]) instead of moving to the sensor's end now: once the
     * event's moment has passed it stays (SensorLife pulls the end to "now" when it detects the ended sensor, which
     * would otherwise move a past event); an event still in the future follows the end (a sensor that fails early).
     */
    fun keepTime(eventStartMs: Long, nowMs: Long) = eventStartMs <= nowMs

    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Loads the setting and follows [SensorLife.current] (App.onCreate): every sensor change rewrites the event. */
    @Synchronized fun init(ctx: Context) {
        if (prefs != null) return
        val app = ctx.applicationContext
        val p = app.getSharedPreferences("sensor_calendar", Context.MODE_PRIVATE)
        prefs = p
        _settings.value = Settings(p.getBoolean("on", false), p.getLong("target", OWN), p.getString("targetName", "") ?: "")
        SensorLife.init(app)
        scope.launch { SensorLife.current.collect { runCatching { write(app) }.onFailure { Log.w(TAG, "write failed", it) } } }
    }

    private fun save(s: Settings) {
        _settings.value = s
        prefs?.edit()?.putBoolean("on", s.on)?.putLong("target", s.target)?.putString("targetName", s.targetName)?.apply()
    }

    fun granted(ctx: Context) = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Turns it on with the permissions granted; writes the current sensor's event. */
    fun enable(ctx: Context) {
        init(ctx)
        save(_settings.value.copy(on = true))
        resync(ctx)
    }

    /** Turns it off and removes what Sukrara wrote. */
    fun disable(ctx: Context) {
        init(ctx)
        val was = _settings.value
        save(was.copy(on = false))
        val app = ctx.applicationContext
        scope.launch { runCatching { removeAll(app, was.target) }.onFailure { Log.w(TAG, "remove failed", it) } }
    }

    /** Moves Sukrara's events to [target] ([OWN] or a picked calendar), removing them from the old place. */
    fun choose(ctx: Context, target: Long, name: String) {
        init(ctx)
        val was = _settings.value
        if (was.target == target) return
        save(was.copy(target = target, targetName = if (target == OWN) "" else name))
        val app = ctx.applicationContext
        scope.launch {
            runCatching { removeAll(app, was.target) }.onFailure { Log.w(TAG, "remove failed", it) }
            runCatching { write(app) }.onFailure { Log.w(TAG, "write failed", it) }
        }
    }

    fun resync(ctx: Context) {
        init(ctx)
        val app = ctx.applicationContext
        scope.launch { runCatching { write(app) }.onFailure { Log.w(TAG, "write failed", it) } }
    }

    /** Writable calendars for the picker (needs the permissions). */
    fun choices(ctx: Context): List<Choice> {
        if (!granted(ctx)) return emptyList()
        val out = mutableListOf<Choice>()
        ctx.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), Calendars.ACCOUNT_NAME)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == ACCOUNT_TYPE_LOCAL && c.getString(2) == ACCOUNT) continue
                out += Choice(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) != ACCOUNT_TYPE_LOCAL)
            }
        }
        return out
    }

    /** For adb (DebugReceiver `cmd calendar`): the calendars and Sukrara's events, as the provider has them. */
    fun dump(ctx: Context): JSONObject {
        val s = _settings.value
        val out = JSONObject().put("on", s.on).put("target", s.target).put("granted", granted(ctx))
        if (!granted(ctx)) return out
        val cr = ctx.contentResolver
        out.put("ownCalendar", ownCalendar(cr, create = false) ?: JSONObject.NULL)
        val cal = if (s.target == OWN) ownCalendar(cr, create = false) else s.target
        val events = org.json.JSONArray()
        if (cal != null) cr.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE, Events.DESCRIPTION, Events.DTSTART, Events._SYNC_ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(cal.toString()), null)?.use { c ->
            while (c.moveToNext()) events.put(JSONObject().put("id", c.getLong(0)).put("title", c.getString(1))
                .put("description", c.getString(2)).put("start", c.getLong(3)).put("syncId", c.getString(4) ?: JSONObject.NULL))
        }
        return out.put("events", events)
    }

    // ------------------------------------------------------------------------------------------------ provider

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT).appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE_LOCAL).build()

    /** Sukrara's local calendar, created when [create] (found by account, so never twice). */
    private fun ownCalendar(cr: ContentResolver, create: Boolean): Long? {
        cr.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(ACCOUNT, ACCOUNT_TYPE_LOCAL), null)?.use { if (it.moveToFirst()) return it.getLong(0) }
        if (!create) return null
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT); put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "sukrara"); put(Calendars.CALENDAR_DISPLAY_NAME, ACCOUNT)
            put(Calendars.CALENDAR_COLOR, COLOR); put(Calendars.OWNER_ACCOUNT, ACCOUNT)
            // read-only for the user: Sukrara keeps these events up to date and would overwrite edits
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return cr.insert(asSyncAdapter(Calendars.CONTENT_URI), v)?.let(ContentUris::parseId)
    }

    private fun picked(ids: JSONObject): Map<String, Long> = ids.keys().asSequence().associateWith { ids.getLong(it) }
    private fun pickedIds() = runCatching { JSONObject(prefs?.getString("picked", null) ?: "{}") }.getOrDefault(JSONObject())
    private fun savePicked(m: Map<String, Long>) { prefs?.edit()?.putString("picked", JSONObject(m).toString())?.apply() }

    /** key → (event id, start ms) of Sukrara's events in [cal]. */
    private fun written(cr: ContentResolver, cal: Long, own: Boolean): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        if (own) {
            cr.query(Events.CONTENT_URI, arrayOf(Events._ID, Events._SYNC_ID, Events.DTSTART),
                "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(cal.toString()), null)?.use { c ->
                while (c.moveToNext()) c.getString(1)?.let { out[it] = c.getLong(0) to c.getLong(2) }
            }
        } else for ((k, id) in picked(pickedIds())) {
            cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.CALENDAR_ID, Events.DELETED, Events.DTSTART),
                null, null, null)?.use { c -> if (c.moveToFirst() && c.getLong(0) == cal && c.getInt(1) == 0) out[k] = id to c.getLong(2) }
        }
        return out
    }

    private fun write(ctx: Context) {
        val s = _settings.value
        if (!s.on || App.role(ctx) != "owner") return   // only the wearer's app writes; followers have no serial
        if (!granted(ctx)) { save(s.copy(on = false)); return }   // permission revoked in system settings
        val cr = ctx.contentResolver
        val own = s.target == OWN
        val cal = if (own) ownCalendar(cr, create = true) ?: return else s.target
        if (!own && choices(ctx).none { it.id == cal }) {   // the picked calendar (or its account) is gone
            save(s.copy(target = OWN, targetName = "")); write(ctx); return
        }
        val have = written(cr, cal, own)
        val cur = SensorLife.current.value?.takeIf { it.serial.isNotEmpty() }
        val keep = HashMap(have.mapValues { it.value.first })
        val now = System.currentTimeMillis()
        for (k in stale(have.mapValues { it.value.second }, cur?.let { key(it.serial) }, now)) {
            val id = have.getValue(k).first
            cr.delete(ContentUris.withAppendedId(if (own) asSyncAdapter(Events.CONTENT_URI) else Events.CONTENT_URI, id), null, null)
            keep.remove(k)
        }
        if (cur != null) {
            val (serial, start, end) = cur
            val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start * 1000))
            val e = event(serial, end, ctx.getString(R.string.calendar_event_title), ctx.getString(R.string.calendar_event_description, serial, started))
            val v = ContentValues().apply {
                put(Events.CALENDAR_ID, cal); put(Events.TITLE, e.title); put(Events.DESCRIPTION, e.description)
                put(Events.DTSTART, e.startMs); put(Events.DTEND, e.endMs); put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
                if (own) put(Events._SYNC_ID, e.key)
            }
            val uri = if (own) asSyncAdapter(Events.CONTENT_URI) else Events.CONTENT_URI
            val id = have[e.key]?.first
            if (id != null) { if (keepTime(have.getValue(e.key).second, now)) { v.remove(Events.DTSTART); v.remove(Events.DTEND) }
                cr.update(ContentUris.withAppendedId(uri, id), v, null, null) }
            else cr.insert(uri, v)?.let { keep[e.key] = ContentUris.parseId(it) }
        }
        if (!own) savePicked(keep)
    }

    /** Removes everything Sukrara wrote to [target]: its own calendar (with its events) or its events in a picked one. */
    private fun removeAll(ctx: Context, target: Long) {
        if (!granted(ctx)) return
        val cr = ctx.contentResolver
        if (target == OWN) {
            cr.delete(asSyncAdapter(Calendars.CONTENT_URI), "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(ACCOUNT, ACCOUNT_TYPE_LOCAL))
        } else {
            // plain URI: the provider marks them DELETED + DIRTY, so the account's sync adapter deletes them on the server too
            for (id in picked(pickedIds()).values) cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
            savePicked(emptyMap())
        }
    }
}
