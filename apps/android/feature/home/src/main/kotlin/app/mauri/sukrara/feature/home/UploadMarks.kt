// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.Treatment
import java.time.Instant

/**
 * Where a listed entry stands with the clinic upload (LibreView): its current version is there, an older version is
 * (the correction is on its way), it is still to go up, or it waits because the upload stopped (sign in again).
 */
enum class UploadMark { Uploaded, Updating, Pending, Stopped }

/** The clinic upload's view of the entries, by its own rules (the app adapts the uploader's ledger). */
interface UploadMarks {
    /** Null = the upload has nothing of [t] and never will (no mark: it would say "not uploaded" forever). */
    fun mark(
        t: Treatment,
        now: Instant,
    ): UploadMark?

    /** Deleting [t] takes it out of the clinic upload too (it has it, and the upload runs). */
    fun removesOnDelete(t: Treatment): Boolean
}
