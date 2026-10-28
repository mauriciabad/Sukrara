// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The install's one database (`databases/sukrara.db`): glucose history, treatments and the sharing ledger. A phone is
 * either wearer or follower, so tables have no role column. Every schema change bumps [version], exports the schema to
 * `core/data/schemas/` and adds a migration with a `MigrationTestHelper` test (no destructive fallback).
 */
@Database(
    entities = [ReadingEntity::class, SeenWrapEntity::class, RejectEntity::class, TreatmentEntity::class, OutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SukraraDatabase : RoomDatabase() {
    abstract fun readings(): ReadingDao

    abstract fun seenWraps(): SeenWrapDao

    abstract fun rejects(): RejectDao

    abstract fun treatments(): TreatmentDao

    abstract fun outbox(): OutboxDao

    companion object {
        const val FILE = "sukrara.db"
    }
}
