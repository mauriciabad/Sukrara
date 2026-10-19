// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import java.io.File
import java.time.ZoneId

/**
 * Settings → Export data: what goes into [GlucoseCsv] for the active role, from what is already on this phone (no
 * fetching). The wearer: its own readings (owner.db, kept 31 days) and treatment log; a follower: the readings and
 * treatments it received (follower.db). Followers' sensor info has no serial, so their Serial Number column is empty.
 */
object Export {
    /** Period choices: "sensor" (the current sensor, when one is known) or a number of days. */
    val DAYS = listOf(7, 14, 30)
    const val DEFAULT = "14"

    class Data(val from: Long, val to: Long, val readings: List<GlucoseCsv.Row>, val treatments: List<Treatment>, val sensor: GlucoseCsv.Sensor?) {
        val readingRows get() = GlucoseCsv.thin(readings, from, to).size
        val treatmentRows get() = treatments.count { !it.del && it.t in from until to }
    }

    fun range(period: String, now: Long = Proto.nowS()): Pair<Long, Long> {
        val s = SensorLife.current.value
        val from = if (period == "sensor" && s != null) s.startEpoch else now - (period.toIntOrNull() ?: 14) * Proto.DAY
        return from to now + 1
    }

    /** Reads the role's store (disk: call off the main thread). */
    fun load(ctx: Context, period: String): Data {
        val (from, to) = range(period)
        val s = SensorLife.current.value
        // the wearer's own sensor family when it isn't a Libre 3 names the rows (followers don't know it)
        val model = app.mauri.sukrara.core.ActiveSensor.profile.value?.takeIf { it.id != app.mauri.sukrara.core.SensorProfiles.LIBRE_3.id }
            ?.let { app.mauri.sukrara.core.SensorOutputs.model(it) }
        val sensor = s?.let { GlucoseCsv.Sensor(it.serial, it.startEpoch, it.endEpoch, model.takeIf { _ -> App.role(ctx) == "owner" }) }
        return when (App.role(ctx)) {
            "owner" -> {
                Treat.init(ctx)
                Data(from, to, App.owner(ctx).history(from).map { GlucoseCsv.Row(it.t, it.sgv, it.rate) }, Treat.store.all(), sensor)
            }
            "follower" -> {
                val e = App.follower(ctx)
                Data(from, to, e.history(from).map { GlucoseCsv.Row(it.t, it.sgv, it.rate) }, e.treatmentsSince(from), sensor)
            }
            else -> Data(from, to, emptyList(), emptyList(), null)
        }
    }

    fun csv(d: Data): String =
        GlucoseCsv.write(d.readings, d.treatments, d.from, d.to, ZoneId.systemDefault(), Proto.nowS(), d.sensor)

    fun fileName(d: Data) = GlucoseCsv.fileName(d.from, d.to, ZoneId.systemDefault())

    /**
     * The share sheet's intent: the file goes to the app's cache (`cache/export/`, served by the FileProvider in the
     * manifest, replaced on every share) and the chooser gets a read grant for it only. Writes: call off the main thread.
     */
    fun shareIntent(ctx: Context, d: Data, chooserTitle: String): Intent {
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, fileName(d)).apply { writeText(csv(d)) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".export", file)
        val send = Intent(Intent.ACTION_SEND).setType(GlucoseCsv.MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)   // the chooser passes the grant on with it
        return Intent.createChooser(send, chooserTitle)
    }
}
