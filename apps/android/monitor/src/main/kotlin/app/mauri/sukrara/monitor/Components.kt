// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/*
 * The alert runtime's Android components, declared in this module's manifest. They are skeletons until chunk 11
 * (alerts) fills them; they stay enabled so a reboot or an update always reaches them (ManifestTest keeps them
 * enabled). The monitor's own (MonitoringService, BootReceiver, HealthCheckReceiver) are chunk 10's files.
 */

/** The exact alarm that checks alerts even when the service is dead; also re-checks on a clock change. Chunk 11. */
class AlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = Unit
}

/** Mute / remind-less actions on alert notifications (PendingIntents only). Chunk 11. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) = Unit
}

/**
 * Serves the bundled alert sounds to System UI (notification channel sound URIs point here). Exported without a
 * permission because System UI must open it; read-only, files only. Chunk 11.
 */
class AlertSoundProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor = throw FileNotFoundException(uri.toString())

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")
}
