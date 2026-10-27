// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.wearer

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.libreview.LibreViewPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Runtime state of the wearer's local features (for the UI and the DebugReceiver's resultData). */
data class WearerStatus(
    val last: Reading? = null,
    val source: String = "not started",
    val sourceError: String? = null,
    // Until a role's LocalServers applies the switches (e.g. the DebugReceiver woke the process before the service
    // started): not "off", which reads as "switched off" or "left to Juggluco" gone wrong.
    val garmin: String = "not started",
    val nightscout: String = "not started",
    val watchPolledAt: Long = 0,
    val libreview: String = "off",
    val notificationPosts: Int = 0,
    val notificationSkipped: Int = 0,
    val screenOn: Boolean = true,
    val sensorSerial: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("last", last?.let { JSONObject().put("t", it.t).put("sgv", it.sgv).put("rate", it.rate ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("sourceState", source).put("sourceError", sourceError ?: JSONObject.NULL)
        .put("garminState", garmin).put("nightscoutState", nightscout).put("watchPolledAt", watchPolledAt / 1000)
        .put("libreviewState", libreview)
        .put("notificationPosts", notificationPosts).put("notificationSkipped", notificationSkipped).put("screenOn", screenOn)
}

/**
 * The single glucose fan-out of the wearer role: the owner engine's source feeds it, and the local features
 * (status bar, Garmin server, LibreView) listen. Process-wide, set by [WearerExtras].
 */
object Live {
    interface Listener {
        fun onReading(r: Reading)
        fun onHistory(list: List<Reading>)
        fun onSensor(serial: String, startEpoch: Long) {}
        fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {}
        fun onFollowerTreatment(tr: JSONObject) {}
    }

    @Volatile var listener: Listener? = null
    val status = MutableStateFlow(WearerStatus())

    /**
     * The newest stored reading at the service's start (a restart, an app update): the notification and the status bar
     * show it ("No reading since …", struck through) instead of "waiting for glucose…" until the source sends one.
     */
    fun seed(r: Reading?) {
        if (r != null) status.update { if ((it.last?.t ?: 0) < r.t) it.copy(last = r) else it }
    }

    fun reading(r: Reading) {
        status.update { it.copy(last = r) }
        listener?.onReading(r)
    }

    fun history(list: List<Reading>) {
        list.maxByOrNull { it.t }?.let { n -> status.update { if ((it.last?.t ?: 0) < n.t) it.copy(last = n) else it } }
        listener?.onHistory(list)
    }

    fun sourceStatus(state: String, error: String?) = status.update { it.copy(source = state, sourceError = error) }
    fun sensor(serial: String, startEpoch: Long) { status.update { it.copy(sensorSerial = serial) }; listener?.onSensor(serial, startEpoch) }
    fun sensorGlucose(c: LibreViewPayload.Glucose?, h: List<LibreViewPayload.Glucose>) = listener?.onSensorGlucose(c, h)
    fun followerTreatment(tr: JSONObject) = listener?.onFollowerTreatment(tr)
}
