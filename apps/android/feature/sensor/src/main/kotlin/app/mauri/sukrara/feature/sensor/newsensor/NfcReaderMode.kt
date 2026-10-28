// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * NFC reader mode for the Libre 3 tap while [enabled] and the activity is resumed (Android allows it only then):
 * on at ON_RESUME, off at ON_PAUSE, when [enabled] turns false and when this leaves the composition. Only NFC-V
 * (ISO 15693, the Libre 3) and no NDEF check, so other tags and the system's own tag dispatch stay out of the way.
 */
@Composable
fun NfcReaderMode(enabled: Boolean, onTag: (Tag) -> Unit) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnTag by rememberUpdatedState(onTag)
    DisposableEffect(activity, lifecycle, enabled) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (!enabled || adapter == null) return@DisposableEffect onDispose {}
        val callback = NfcAdapter.ReaderCallback { tag -> currentOnTag(tag) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> adapter.on(activity, callback)
                Lifecycle.Event.ON_PAUSE -> adapter.off(activity)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // replays ON_RESUME when already resumed
        onDispose {
            lifecycle.removeObserver(observer)
            adapter.off(activity)
        }
    }
}

private const val FLAGS = NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

/** The Libre 3 exchange is several commands long: a slow presence check keeps it from being cut off mid-way. */
private const val PRESENCE_CHECK_DELAY_MS = 1_000

// NFC switched off, or the activity going away between the event and the call, throw: the next resume retries.
private fun NfcAdapter.on(activity: Activity, callback: NfcAdapter.ReaderCallback) {
    val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS) }
    runCatching { enableReaderMode(activity, callback, FLAGS, extras) }
}

private fun NfcAdapter.off(activity: Activity) {
    runCatching { disableReaderMode(activity) }
}
