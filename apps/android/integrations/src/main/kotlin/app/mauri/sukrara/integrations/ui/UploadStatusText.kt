// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.integrations.R
import app.mauri.sukrara.integrations.upload.UploadStatus
import java.time.Instant

/** "5 min ago" in the phone's language. */
fun ago(
    at: Instant,
    now: Instant,
): String = DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()

/** One upload's state in words, the same on every upload page and in Share with apps. */
@Composable
fun uploadStatusText(
    status: UploadStatus,
    now: Instant,
): String =
    when (status) {
        UploadStatus.Off -> stringResource(R.string.in_status_off)
        UploadStatus.Setup -> stringResource(R.string.in_status_setup)
        UploadStatus.AuthFailed -> stringResource(R.string.in_status_auth)
        is UploadStatus.Paused -> stringResource(R.string.in_status_paused, status.other)
        UploadStatus.Waiting -> stringResource(R.string.in_status_waiting)
        is UploadStatus.Ok -> pluralStringResource(R.plurals.in_status_ok, status.count, ago(status.at, now), status.count)
        is UploadStatus.Failing -> stringResource(R.string.in_status_failing, status.why)
    }
