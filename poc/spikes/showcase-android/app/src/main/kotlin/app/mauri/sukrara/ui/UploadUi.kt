// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import app.mauri.sukrara.upload.UploadStatus

/**
 * The status line every upload page shows the same way (Tidepool, Nightscout; `upload/Uploads.kt`): "Up to date · last
 * upload <date time>", "Waiting for the first upload" or "Will retry · <reason>". Each page words its own
 * account / choice states first.
 */
@Composable
internal fun uploadStatusText(st: UploadStatus): String = when (st.kind) {
    "error" -> stringResource(R.string.upload_status_error, st.error.orEmpty())
    else -> if (st.lastOkMs > 0) stringResource(R.string.upload_status_ok, app.mauri.sukrara.graph.Clock.dateTime(st.lastOkMs / 1000))
        else stringResource(R.string.upload_status_waiting)
}
