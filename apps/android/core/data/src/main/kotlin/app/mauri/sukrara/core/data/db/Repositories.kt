// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data.db

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

class RoomReadingsRepository(private val dao: ReadingDao) : ReadingsRepository {
    override fun window(from: Instant): Flow<List<Reading>> = dao.window(from.toEpochMilli()).map { rows -> rows.map { it.toReading() } }

    override fun latest(): Flow<Reading?> = dao.latest().map { it?.toReading() }

    override suspend fun range(
        from: Instant,
        to: Instant,
    ): List<Reading> = dao.range(from.toEpochMilli(), to.toEpochMilli()).map { it.toReading() }

    override suspend fun upsert(readings: List<Reading>) {
        if (readings.isNotEmpty()) dao.upsert(readings.map { it.toEntity() })
    }

    override suspend fun deleteBefore(cutoff: Instant): Int = dao.deleteBefore(cutoff.toEpochMilli())
}

class RoomTreatmentsRepository(private val dao: TreatmentDao) : TreatmentsRepository {
    override fun between(
        from: Instant,
        to: Instant,
    ): Flow<List<Treatment>> = dao.between(from.toEpochMilli(), to.toEpochMilli()).map { rows -> rows.map { it.toRecord().treatment } }

    override suspend fun get(id: String): TreatmentRecord? = dao.get(id)?.toRecord()

    override suspend fun upsertIfNewer(record: TreatmentRecord): Boolean = dao.upsertIfNewer(record.toEntity())

    override suspend fun changedSince(since: Instant): List<TreatmentRecord> = dao.changedSince(since.toEpochMilli()).map { it.toRecord() }
}

internal fun ReadingEntity.toReading() = Reading(Instant.ofEpochMilli(t), mgdl, rate, SourceId(source))

internal fun Reading.toEntity() = ReadingEntity(t.toEpochMilli(), source.id, mgdl, rateMgdlPerMin)

internal fun TreatmentRecord.toEntity() =
    TreatmentEntity(
        id = treatment.id,
        kind = treatment.kind.name,
        amount = treatment.amount,
        at = treatment.at.toEpochMilli(),
        note = treatment.note,
        modifiedAt = treatment.modifiedAt.toEpochMilli(),
        deleted = treatment.deleted,
        origin = treatment.origin.name,
        creator = creator,
        author = author,
        via = via,
        receivedAt = receivedAt?.toEpochMilli(),
        delivery = delivery.name,
        penJson = penJson,
    )

internal fun TreatmentEntity.toRecord() =
    TreatmentRecord(
        treatment =
            Treatment(
                id = id,
                kind = TreatmentKind.valueOf(kind),
                amount = amount,
                at = Instant.ofEpochMilli(at),
                note = note,
                modifiedAt = Instant.ofEpochMilli(modifiedAt),
                deleted = deleted,
                origin = TreatmentOrigin.valueOf(origin),
            ),
        creator = creator,
        author = author,
        via = via,
        receivedAt = receivedAt?.let(Instant::ofEpochMilli),
        delivery = TreatmentDelivery.valueOf(delivery),
        penJson = penJson,
    )
