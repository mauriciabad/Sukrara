package dev.spike.glucobridge

import android.content.Context
import dev.spike.glucobridge.libreview.LibreViewUploader
import dev.spike.glucobridge.treatments.TreatmentStore
import java.io.File

/** Process-wide state shared by the service and the UI (investigation 04). */
object Spike {
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
