// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import android.content.Context
import app.mauri.sukrara.libreview.LibreViewUploader
import java.io.File

/**
 * Process-wide treatment log (investigation 04's TreatmentStore: insulin / carbs / notes, last-writer-wins merge)
 * and the LibreView uploader that drains it. The uploader is OFF until the user sets it up (Features.libreview).
 */
object Treat {
    lateinit var store: TreatmentStore
        private set
    lateinit var uploader: LibreViewUploader
        private set

    @Synchronized fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        store = TreatmentStore(File(app.filesDir, "treatments.json"))
        uploader = LibreViewUploader(app, store)
    }
}
