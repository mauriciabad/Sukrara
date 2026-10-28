// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.CALLER_IS_SYNCADAPTER
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Log
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.monitor.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.TimeZone

/**
 * *Sensor end in calendar*: one event per sensor at the moment it stops working, written by the wearer's phone
 * (followers never write). Written through [android.provider.CalendarContract], so events appear, move and go away
 * without a tap.
 *
 * Where:
 * - default: **Sukrara's own calendar** (`ACCOUNT_TYPE_LOCAL`, the provider's documented way for an app to create a
 *   calendar, inserted as a sync adapter). Calendar apps list it; it never syncs, so it stays on this phone. Google
 *   Calendar hides a non-Google account's calendars until the person turns the account on in its settings (and no
 *   app can do it for them), so the settings row says where.
 * - or **a calendar the person picks** (e.g. their Google one): written as a normal app, so the account's sync adapter
 *   uploads the events (and the deletions, as DELETED tombstones). Their ids are kept in [CalendarRecord].
 *
 * Events: [Plan.DURATION_MS] from the sensor's end (calendar apps draw a block with its title; a point event is a
 * title-less sliver), free, no reminder of its own (Sukrara notifies). One accepted organizer attendee row for the
 * calendar's owner so Google Calendar shows no RSVP. A future event follows the end; a past one keeps its time. Past
 * sensors' events stay (a history); a replaced sensor's future event goes. Off: the own calendar is deleted with its
 * events, or Sukrara's events in the picked calendar.
 */
class SensorCalendar(
    context: Context,
    private val record: StateStore<CalendarRecord>,
) {
    private val app = context.applicationContext
    private val cr: ContentResolver = app.contentResolver

    /** Writes and removals one at a time (the settings page and the sensor both trigger them). */
    private val lock = Mutex()

    fun granted(): Boolean = PERMISSIONS.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** A calendar the person may pick: writable, visible, not Sukrara's own; [synced] = its account syncs. */
    data class Choice(
        val id: Long,
        val name: String,
        val account: String,
        val synced: Boolean,
        val owner: String,
    )

    /** Writable calendars for the picker (empty without the permission). */
    fun choices(): List<Choice> {
        if (!granted()) return emptyList()
        val out = mutableListOf<Choice>()
        runCatching {
            cr
                .query(
                    Calendars.CONTENT_URI,
                    arrayOf(
                        Calendars._ID,
                        Calendars.CALENDAR_DISPLAY_NAME,
                        Calendars.ACCOUNT_NAME,
                        Calendars.ACCOUNT_TYPE,
                        Calendars.OWNER_ACCOUNT,
                    ),
                    "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
                    arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                    Calendars.ACCOUNT_NAME,
                )?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(3) == ACCOUNT_TYPE_LOCAL && c.getString(2) == ACCOUNT) continue
                        out +=
                            Choice(
                                c.getLong(0),
                                c.getString(1).orEmpty(),
                                c.getString(2).orEmpty(),
                                c.getString(3) != ACCOUNT_TYPE_LOCAL,
                                c.getString(4).orEmpty(),
                            )
                    }
                }
        }.onFailure { Log.w(TAG, "calendars unreadable", it) }
        return out
    }

    /**
     * Brings the calendar in line: [on] with [target] ([OWN] or a calendar id) and the wearer's [sensor] (null = none
     * now). Off (or no permission) writes nothing. Returns false when the picked calendar is gone (the caller goes
     * back to the own one).
     */
    suspend fun sync(
        on: Boolean,
        target: String,
        sensor: Sensor?,
        nowMs: Long,
    ): Boolean =
        lock.withLock {
            if (!on || !granted()) return@withLock true
            runCatching { write(target, sensor, nowMs) }.getOrElse {
                Log.w(TAG, "write failed", it)
                true
            }
        }

    /** Removes everything Sukrara wrote to [target]: its own calendar (with its events) or its events in a picked one. */
    suspend fun removeAll(target: String) =
        lock.withLock {
            if (!granted()) return@withLock
            runCatching {
                if (target == OWN) {
                    cr.delete(
                        asSyncAdapter(Calendars.CONTENT_URI),
                        "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
                        arrayOf(ACCOUNT, ACCOUNT_TYPE_LOCAL),
                    )
                } else {
                    // plain URI: marked DELETED + DIRTY, so the account's sync adapter deletes them on the server too
                    for (id in record.data
                        .first()
                        .picked.values) {
                        cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
                    }
                    record.update { it.copy(picked = emptyMap()) }
                }
            }.onFailure { Log.w(TAG, "remove failed", it) }
        }

    /** The sensor whose end goes in the calendar. */
    data class Sensor(
        val serial: String,
        val startEpoch: Long,
        val endEpoch: Long,
    )

    private suspend fun write(
        target: String,
        sensor: Sensor?,
        now: Long,
    ): Boolean {
        val own = target == OWN
        val cal = if (own) ownCalendar(create = true) ?: return true else target.toLongOrNull() ?: return false
        val owner = if (own) ACCOUNT else choices().firstOrNull { it.id == cal }?.owner ?: return false
        val have = written(cal, own)
        val keep = HashMap(have.mapValues { it.value.first })
        val current = sensor?.takeIf { it.serial.isNotEmpty() }
        val eventsUri = if (own) asSyncAdapter(Events.CONTENT_URI) else Events.CONTENT_URI
        for (k in Plan.stale(have.mapValues { it.value.second }, current?.let { Plan.key(it.serial) }, now)) {
            cr.delete(ContentUris.withAppendedId(eventsUri, have.getValue(k).first), null, null)
            keep.remove(k)
        }
        if (current != null) {
            val e =
                Plan.event(
                    current.serial,
                    current.endEpoch,
                    app.getString(R.string.calendar_event_title),
                    app.getString(R.string.calendar_event_description, current.serial, Clock.dateTime(current.startEpoch)),
                )
            val v =
                ContentValues().apply {
                    put(Events.CALENDAR_ID, cal)
                    put(Events.TITLE, e.title)
                    put(Events.DESCRIPTION, e.description)
                    put(Events.DTSTART, e.startMs)
                    put(Events.DTEND, e.endMs)
                    put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
                    if (owner.isNotEmpty()) put(Events.ORGANIZER, owner)
                    put(Events.HAS_ATTENDEE_DATA, 1)
                    if (own) put(Events._SYNC_ID, e.key)
                }
            val existing = have[e.key]
            if (existing != null) {
                if (Plan.keepTime(existing.second, now)) {
                    v.remove(Events.DTSTART)
                    v.remove(Events.DTEND)
                }
                cr.update(ContentUris.withAppendedId(eventsUri, existing.first), v, null, null)
            } else {
                cr.insert(eventsUri, v)?.let { keep[e.key] = ContentUris.parseId(it) }
            }
        }
        for (id in keep.values) ensureOwnerAttendee(id, owner, own)
        if (!own) record.update { it.copy(picked = keep) }
        return true
    }

    private fun asSyncAdapter(uri: Uri): Uri =
        uri
            .buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE_LOCAL)
            .build()

    /** Sukrara's local calendar, created when [create] (found by account, so never twice). */
    private fun ownCalendar(create: Boolean): Long? {
        cr
            .query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID),
                "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(ACCOUNT, ACCOUNT_TYPE_LOCAL),
                null,
            )?.use { if (it.moveToFirst()) return it.getLong(0) }
        if (!create) return null
        val v =
            ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, ACCOUNT)
                put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, "sukrara")
                put(Calendars.CALENDAR_DISPLAY_NAME, ACCOUNT)
                put(Calendars.CALENDAR_COLOR, COLOR)
                put(Calendars.OWNER_ACCOUNT, ACCOUNT)
                // read-only for the person: Sukrara keeps these events up to date and would overwrite edits
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
                put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1)
                put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            }
        return cr.insert(asSyncAdapter(Calendars.CONTENT_URI), v)?.let(ContentUris::parseId)
    }

    /** key → (event id, start ms) of Sukrara's events in [cal]. */
    private suspend fun written(
        cal: Long,
        own: Boolean,
    ): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        if (own) {
            cr
                .query(
                    Events.CONTENT_URI,
                    arrayOf(Events._ID, Events._SYNC_ID, Events.DTSTART),
                    "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0",
                    arrayOf(cal.toString()),
                    null,
                )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { out[it] = c.getLong(0) to c.getLong(2) } }
        } else {
            for ((k, id) in record.data.first().picked) {
                cr
                    .query(
                        ContentUris.withAppendedId(Events.CONTENT_URI, id),
                        arrayOf(Events.CALENDAR_ID, Events.DELETED, Events.DTSTART),
                        null,
                        null,
                        null,
                    )?.use { c -> if (c.moveToFirst() && c.getLong(0) == cal && c.getInt(1) == 0) out[k] = id to c.getLong(2) }
            }
        }
        return out
    }

    /**
     * Exactly one attendee row for [owner] on [eventId] (accepted, organizer, required): the provider then marks the
     * event accepted, which stops Google Calendar from asking for an RSVP. Inserted only when none exists.
     */
    private fun ensureOwnerAttendee(
        eventId: Long,
        owner: String,
        own: Boolean,
    ) {
        if (owner.isEmpty()) return
        val exists =
            cr
                .query(
                    Attendees.CONTENT_URI,
                    arrayOf(Attendees._ID),
                    "${Attendees.EVENT_ID} = ? AND ${Attendees.ATTENDEE_EMAIL} = ?",
                    arrayOf(eventId.toString(), owner),
                    null,
                )?.use { it.count > 0 } ?: return
        if (exists) return
        val v =
            ContentValues().apply {
                put(Attendees.EVENT_ID, eventId)
                put(Attendees.ATTENDEE_EMAIL, owner)
                put(Attendees.ATTENDEE_NAME, owner)
                put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED)
                put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ORGANIZER)
                put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
            }
        cr.insert(if (own) asSyncAdapter(Attendees.CONTENT_URI) else Attendees.CONTENT_URI, v)
    }

    /** The decisions, independent of the provider (unit-tested). */
    object Plan {
        /** How long the event is: long enough that calendar apps draw it as a block with its title. */
        const val DURATION_MS = 30 * 60_000L

        data class EventSpec(
            val key: String,
            val title: String,
            val description: String,
            val startMs: Long,
            val endMs: Long,
        )

        fun key(serial: String) = "sensor:$serial"

        /** The event for a sensor: [DURATION_MS] from [endEpoch] (unix s), the moment the sensor stops. */
        fun event(
            serial: String,
            endEpoch: Long,
            title: String,
            description: String,
        ) = EventSpec(key(serial), title, description, endEpoch * 1000, endEpoch * 1000 + DURATION_MS)

        /** The written events ([existing]: key → start ms) to delete: other sensors' events still ahead (replaced early). */
        fun stale(
            existing: Map<String, Long>,
            currentKey: String?,
            nowMs: Long,
        ): Set<String> = if (currentKey == null) emptySet() else existing.filter { (k, start) -> k != currentKey && start > nowMs }.keys

        /** An event whose moment has passed keeps its time; one still ahead follows the end (a sensor that fails early). */
        fun keepTime(
            eventStartMs: Long,
            nowMs: Long,
        ) = eventStartMs <= nowMs
    }

    companion object {
        private const val TAG = "Sukrara.calendar"
        const val ACCOUNT = "Sukrara"

        /** The brand's top colour. */
        const val COLOR = 0xFF1BB5C9.toInt()

        /** `sensorCalendar.calendarId` for Sukrara's own calendar. */
        const val OWN = "own"

        /** Google Calendar's package (declared in the manifest's `<queries>`). */
        const val GOOGLE_CALENDAR = "com.google.android.calendar"

        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        val On = SchemaSettings.bool("sensorCalendar.on", default = false)
        val Target = SchemaSettings.text("sensorCalendar.calendarId", OWN)
    }
}

/** What the calendar keeps besides its settings: the picked calendar's name and Sukrara's event ids in it. */
@Serializable
data class CalendarRecord(
    val targetName: String = "",
    val picked: Map<String, Long> = emptyMap(),
)
