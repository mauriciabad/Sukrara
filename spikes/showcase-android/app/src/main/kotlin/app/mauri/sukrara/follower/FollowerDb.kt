// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.follower

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentJson

/** A reading as the FOLLOWER stored it: with the epoch it was encrypted under and the relay it came from. */
data class FollowedReading(val t: Long, val sgv: Int, val rate: Double?, val epoch: Int, val via: String, val received: Long)

/**
 * A treatment as the follower holds it (SPEC §5): the §1 winner for its id, who signed that version, and the newest
 * `u` the wearer's device key has published for it ([ownerU], 0 = never) — the echo that marks a writer's entry accepted.
 */
data class FollowedTreatment(val tr: Treatment, val author: String, val via: String, val ownerU: Long)

/** An entry this follower sent as a writer: [sentAt] = first relay OK / BLE write response (0 = still sending). */
data class OutboxEntry(val id: String, val u: Long, val ev: String, val createdAt: Long, val sentAt: Long)

/**
 * The FOLLOWER's own database (`follower.db`). Only the follower package touches it; nothing in here can come
 * from the owner role's `owner.db` — every row was decrypted from a relay event and records `via` = relay host.
 */
class FollowerDb(context: Context) : SQLiteOpenHelper(context, NAME, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table readings(t integer primary key, sgv integer not null, rate real, epoch integer not null, " +
            "via text not null, received integer not null)")
        db.execSQL("create table rejects(at integer not null, why text not null, author text, via text)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // treatments from the wearer (SPEC §5), and the entries this follower sent as a writer
        if (oldVersion < 2) {
            db.execSQL("create table treatments(id text primary key, json text not null, u integer not null, o text not null, " +
                "t integer not null, author text not null, via text not null, received integer not null, owner_u integer not null)")
            db.execSQL("create table outbox(id text primary key, u integer not null, ev text not null, created integer not null, " +
                "sent integer not null)")
        }
    }

    /**
     * SPEC §1 merge (newest `(u, o)` wins) of a treatment signed by [author]; [fromOwner] = signed by a roster device
     * key, which also raises `owner_u`. Returns true if anything changed.
     */
    fun mergeTreatment(inc: Treatment, author: String, via: String, fromOwner: Boolean): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var cur: Treatment? = null
            var curAuthor = author; var curVia = via; var ownerU = 0L
            db.rawQuery("select json, author, via, owner_u from treatments where id = ?", arrayOf(inc.id)).use { c ->
                if (c.moveToFirst()) {
                    cur = runCatching { TreatmentJson.decodeFromString<Treatment>(c.getString(0)) }.getOrNull()
                    curAuthor = c.getString(1); curVia = c.getString(2); ownerU = c.getLong(3)
                }
            }
            val win = Treatment.merge(cur, inc).copy(lv = 0)
            val incWon = cur == null || win != cur
            val newOwnerU = if (fromOwner) maxOf(ownerU, inc.u) else ownerU
            if (!incWon && newOwnerU == ownerU) return false
            db.insertWithOnConflict("treatments", null, ContentValues().apply {
                put("id", win.id); put("json", TreatmentJson.encodeToString(Treatment.serializer(), win)); put("u", win.u)
                put("o", win.o); put("t", win.t)
                put("author", if (incWon) author else curAuthor); put("via", if (incWon) via else curVia)
                put("received", System.currentTimeMillis() / 1000); put("owner_u", newOwnerU)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    /** Newest `t` first, tombstones included. */
    fun treatments(fromT: Long = 0): List<FollowedTreatment> =
        readableDatabase.rawQuery("select json, author, via, owner_u from treatments where t >= ? order by t desc",
            arrayOf(fromT.toString())).use { c ->
            buildList {
                while (c.moveToNext()) runCatching { TreatmentJson.decodeFromString<Treatment>(c.getString(0)) }.getOrNull()
                    ?.let { add(FollowedTreatment(it, c.getString(1), c.getString(2), c.getLong(3))) }
            }
        }

    fun addOutbox(id: String, u: Long, ev: String) {
        writableDatabase.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("id", id); put("u", u); put("ev", ev); put("created", System.currentTimeMillis() / 1000); put("sent", 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** First confirmation (relay OK / BLE write response) for the event [ev]; true if it changed a row. */
    fun markSent(ev: String): Boolean = writableDatabase.update("outbox", ContentValues().apply {
        put("sent", System.currentTimeMillis() / 1000)
    }, "ev = ? and sent = 0", arrayOf(ev)) > 0

    fun outbox(): List<OutboxEntry> =
        readableDatabase.rawQuery("select id, u, ev, created, sent from outbox", null).use { c ->
            buildList { while (c.moveToNext()) add(OutboxEntry(c.getString(0), c.getLong(1), c.getString(2), c.getLong(3), c.getLong(4))) }
        }

    /** insert-or-ignore (dedupe by t: the first relay to deliver a reading wins `via`); returns rows inserted. */
    fun insert(rows: List<Triple<Long, Int, Double?>>, epoch: Int, via: String): Int {
        val db = writableDatabase
        val now = System.currentTimeMillis() / 1000
        var n = 0
        db.beginTransaction()
        try {
            rows.forEach { (t, sgv, rate) ->
                val id = db.insertWithOnConflict("readings", null, ContentValues().apply {
                    put("t", t); put("sgv", sgv); put("rate", rate); put("epoch", epoch); put("via", via); put("received", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (id != -1L) n++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return n
    }

    fun since(from: Long): List<FollowedReading> =
        readableDatabase.rawQuery("select t, sgv, rate, epoch, via, received from readings where t >= ? order by t",
            arrayOf(from.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(FollowedReading(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getDouble(2),
                    c.getInt(3), c.getString(4), c.getLong(5)))
            }
        }

    fun latest(): FollowedReading? =
        readableDatabase.rawQuery("select t, sgv, rate, epoch, via, received from readings order by t desc limit 1", null).use { c ->
            if (c.moveToFirst()) FollowedReading(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getDouble(2),
                c.getInt(3), c.getString(4), c.getLong(5)) else null
        }

    fun count(): Int = readableDatabase.rawQuery("select count(*) from readings", null).use { it.moveToFirst(); it.getInt(0) }

    fun reject(why: String, author: String?, via: String?) {
        writableDatabase.insert("rejects", null, ContentValues().apply {
            put("at", System.currentTimeMillis() / 1000); put("why", why); put("author", author); put("via", via)
        })
    }

    fun rejectCount(): Int = readableDatabase.rawQuery("select count(*) from rejects", null).use { it.moveToFirst(); it.getInt(0) }

    fun wipe() {
        writableDatabase.execSQL("delete from readings")
        writableDatabase.execSQL("delete from rejects")
        writableDatabase.execSQL("delete from treatments")
        writableDatabase.execSQL("delete from outbox")
    }

    companion object { const val NAME = "follower.db" }
}
