// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.feature.home.UploadMark
import app.mauri.sukrara.feature.home.UploadMarks
import app.mauri.sukrara.integrations.libreview.LvEntryMarks
import app.mauri.sukrara.integrations.libreview.LvMark
import java.time.Instant

/** Home's cloud marks from the LibreView upload's ledger (its own rules); anything not there waits while it's stopped. */
class LibreViewMarks(
    private val marks: LvEntryMarks,
) : UploadMarks {
    override fun mark(
        t: Treatment,
        now: Instant,
    ): UploadMark? =
        when (marks.mark(t, now)) {
            null -> null
            LvMark.Uploaded -> UploadMark.Uploaded
            LvMark.Updating -> if (marks.stopped) UploadMark.Stopped else UploadMark.Updating
            LvMark.Pending -> if (marks.stopped) UploadMark.Stopped else UploadMark.Pending
        }

    override fun removesOnDelete(t: Treatment) = marks.removesOnDelete(t)
}
