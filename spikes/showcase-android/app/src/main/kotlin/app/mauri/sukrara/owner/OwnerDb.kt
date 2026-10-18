// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.mauri.sukrara.core.Reading

/**
 * The OWNER's database (`owner.db`): the sensor's own readings + ids of gift wraps already processed.
 * Only the owner package touches it; the follower role has its own `follower.db`.
 */
class OwnerDb(context: Context) : SQLiteOpenHelper(context, NAME, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table readings(t integer primary key, sgv integer not null, rate real, dev text)")
        db.execSQL("create table seen_wraps(id text primary key, at integer not null)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // investigation 07: treatments written by authorized followers (LWW on `u`)
        if (oldVersion < 2) db.execSQL("create table treatments(id text primary key, json text not null, u integer not null, " +
            "author text not null, via text not null, received integer not null)")
    }

    /** Last-writer-wins on `u` (then author); returns true if inserted or replaced. The caller checked the author (P4). */
    fun upsertTreatment(tr: org.json.JSONObject, author: String, via: String): Boolean {
        val id = tr.getString("id")
        val u = tr.optLong("u")
        readableDatabase.rawQuery("select u, author from treatments where id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst() && (c.getLong(0) > u || (c.getLong(0) == u && c.getString(1) >= author))) return false
        }
        writableDatabase.insertWithOnConflict("treatments", null, ContentValues().apply {
            put("id", id); put("json", tr.toString()); put("u", u); put("author", author); put("via", via)
            put("received", System.currentTimeMillis() / 1000)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    /** The writer whose entry [id] is (the first writer accepted for it: P4 rejects any other), or null. */
    fun treatmentAuthor(id: String): String? =
        readableDatabase.rawQuery("select author from treatments where id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Newest first: (treatment JSON, author, via). */
    fun treatments(limit: Int = 20): List<Triple<org.json.JSONObject, String, String>> =
        readableDatabase.rawQuery("select json, author, via from treatments order by received desc limit $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(org.json.JSONObject(c.getString(0)), c.getString(1), c.getString(2))) }
        }

    /** Insert or replace; returns true if new or changed (keeps the copy that has a rate). */
    fun upsert(r: Reading): Boolean {
        val db = writableDatabase
        db.rawQuery("select sgv, rate from readings where t = ?", arrayOf(r.t.toString())).use { c ->
            if (c.moveToFirst()) {
                val sgv = c.getInt(0)
                val hasRate = !c.isNull(1)
                if (sgv == r.sgv && (r.rate == null || (hasRate && c.getDouble(1) == r.rate))) return false
            }
        }
        db.insertWithOnConflict("readings", null, ContentValues().apply {
            put("t", r.t); put("sgv", r.sgv); put("rate", r.rate); put("dev", r.dev)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    /** Batch [upsert] in one transaction (history: ~1500 rows). Returns the readings that were new or changed. */
    fun upsertAll(list: List<Reading>): List<Reading> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = list.filter { upsert(it) }
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }

    fun range(from: Long, to: Long): List<Reading> =
        readableDatabase.rawQuery("select t, sgv, rate, dev from readings where t >= ? and t < ? order by t",
            arrayOf(from.toString(), to.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Reading(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getDouble(2), c.getString(3) ?: "")) }
        }

    fun latest(): Reading? = readableDatabase.rawQuery("select t, sgv, rate, dev from readings order by t desc limit 1", null).use { c ->
        if (c.moveToFirst()) Reading(c.getLong(0), c.getInt(1), if (c.isNull(2)) null else c.getDouble(2), c.getString(3) ?: "") else null
    }

    fun prune(before: Long) {
        writableDatabase.delete("readings", "t < ?", arrayOf(before.toString()))
        writableDatabase.delete("seen_wraps", "at < ?", arrayOf((System.currentTimeMillis() / 1000 - 8 * 86400).toString()))
    }

    /** true the first time a wrap id is seen. */
    fun markSeen(id: String): Boolean =
        writableDatabase.insertWithOnConflict("seen_wraps", null, ContentValues().apply {
            put("id", id); put("at", System.currentTimeMillis() / 1000)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

    companion object { const val NAME = "owner.db" }
}
