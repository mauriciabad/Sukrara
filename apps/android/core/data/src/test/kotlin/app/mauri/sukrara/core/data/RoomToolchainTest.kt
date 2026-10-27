// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import org.junit.Assert.assertNotNull
import org.junit.Test

@Entity
data class ProbeRow(
    @PrimaryKey val id: Long,
    val value: Int,
)

@Dao
interface ProbeDao {
    @Query("SELECT * FROM ProbeRow WHERE id = :id")
    suspend fun get(id: Long): ProbeRow?
}

@Database(entities = [ProbeRow::class], version = 1, exportSchema = false)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun probe(): ProbeDao
}

/** Room's KSP processor runs under AGP 9 built-in Kotlin: it generated the database implementation. */
class RoomToolchainTest {
    @Test
    fun `room generated the database implementation`() {
        assertNotNull(Class.forName("app.mauri.sukrara.core.data.ProbeDatabase_Impl"))
    }
}
