// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import java.time.Instant

/**
 * One insulin / carbs / note entry as the app works with it. The wire and storage encodings (field names, `v`,
 * last-writer-wins merge on [modifiedAt]) are defined by the treatments contract and live with their codecs
 * (`:core:protocol`, `:core:data`), not here.
 */
data class Treatment(
    val id: String,
    val kind: TreatmentKind,
    /** Units of insulin or grams of carbs; 0 for a note. */
    val amount: Double,
    /** When it happened. */
    val at: Instant,
    val note: String = "",
    /** Last edit (the merge version). */
    val modifiedAt: Instant,
    val deleted: Boolean = false,
    /** Which device wrote it. */
    val origin: TreatmentOrigin,
)

enum class TreatmentKind { RapidInsulin, LongInsulin, Carbs, Note }

/** Who created the entry: the wearer's phone, the Mac (legacy link) or a follower allowed to add entries (writer). */
enum class TreatmentOrigin { Phone, Mac, Follower }
