// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.follower

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A reading as the FOLLOWER stored it: with the epoch it was encrypted under and the relay it came from. */
data class FollowedReading(val t: Long, val sgv: Int, val rate: Double?, val epoch: Int, val via: String, val received: Long)

/**
 * The FOLLOWER's own database (`follower.db`). Only the follower package touches it; nothing in here can come
 * from the owner role's `owner.db` — every row was decrypted from a relay event and records `via` = relay host.
 */
class FollowerDb(context: Context) : SQLiteOpenHelper(context, NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table readings(t integer primary key, sgv integer not null, rate real, epoch integer not null, " +
            "via text not null, received integer not null)")
        db.execSQL("create table rejects(at integer not null, why text not null, author text, via text)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
    }

    companion object { const val NAME = "follower.db" }
}
