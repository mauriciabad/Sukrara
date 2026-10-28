// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    /** The card's window: everything from [from], oldest first, live. */
    @Query("SELECT * FROM readings WHERE t >= :from ORDER BY t")
    fun window(from: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings ORDER BY t DESC LIMIT 1")
    fun latest(): Flow<ReadingEntity?>

    /** Backfill / export range [from, to). */
    @Query("SELECT * FROM readings WHERE t >= :from AND t < :to ORDER BY t")
    suspend fun range(
        from: Long,
        to: Long,
    ): List<ReadingEntity>

    @Upsert
    suspend fun upsert(readings: List<ReadingEntity>)

    /** Retention. */
    @Query("DELETE FROM readings WHERE t < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int
}

@Dao
interface SeenWrapDao {
    /** Records [id]; returns false when it was already seen (the caller skips the event). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(wrap: SeenWrapEntity): Long

    suspend fun markSeen(
        id: String,
        at: Long,
    ): Boolean = insert(SeenWrapEntity(id, at)) != -1L

    @Query("DELETE FROM seen_wraps WHERE at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int
}

@Dao
interface RejectDao {
    @Insert
    suspend fun insert(reject: RejectEntity)

    @Query("SELECT * FROM rejects ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RejectEntity>>

    @Query("DELETE FROM rejects WHERE at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int
}

@Dao
interface TreatmentDao {
    @Query("SELECT * FROM treatments WHERE deleted = 0 AND at >= :from AND at < :to ORDER BY at")
    fun between(
        from: Long,
        to: Long,
    ): Flow<List<TreatmentEntity>>

    @Query("SELECT * FROM treatments WHERE id = :id")
    suspend fun get(id: String): TreatmentEntity?

    @Query("SELECT * FROM treatments WHERE modified_at > :since ORDER BY modified_at")
    suspend fun changedSince(since: Long): List<TreatmentEntity>

    @Upsert
    suspend fun upsert(treatment: TreatmentEntity)

    /** Last writer wins: stores [treatment] only if nothing with the same or a later `modifiedAt` is stored. */
    @Transaction
    suspend fun upsertIfNewer(treatment: TreatmentEntity): Boolean {
        val stored = get(treatment.id)
        if (stored != null && stored.modifiedAt >= treatment.modifiedAt) return false
        upsert(treatment)
        return true
    }
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE sent_at IS NULL ORDER BY created_at")
    fun pending(): Flow<List<OutboxEntity>>

    @Upsert
    suspend fun put(entry: OutboxEntity)

    @Query("UPDATE outbox SET sent_at = :at WHERE id = :id AND version = :version")
    suspend fun markSent(
        id: String,
        version: Long,
        at: Long,
    ): Int

    @Query("DELETE FROM outbox WHERE sent_at IS NOT NULL AND sent_at < :cutoff")
    suspend fun deleteSentBefore(cutoff: Long): Int
}
