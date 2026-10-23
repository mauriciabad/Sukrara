// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.util.Log
import app.mauri.sukrara.pen.opennov.OpenNovReader
import app.mauri.sukrara.pen.opennov.PenRead

/**
 * One NFC tag → a pen read (blocking: call off the main thread). The pen is an NFC Forum Type 4 tag (IsoDep + NfcA +
 * Ndef, docs/29); any other tag is not a pen and returns null without a word (a sticker, a phone). Throws on a pen
 * that broke off mid-transfer: nothing of a partial read is used (PRODUCT §3.13).
 */
object PenTag {
    fun isPen(tag: Tag): Boolean = IsoDep.get(tag) != null &&
        (Ndef.get(tag)?.type?.let { it == Ndef.NFC_FORUM_TYPE_4 } ?: true)

    /** [onExchange]: one APDU went through (the read card's "data flowing" ticks). */
    fun read(tag: Tag, onExchange: () -> Unit = {}, progress: (Float) -> Unit = {}, stopBefore: (String) -> Long? = { null }): PenRead? {
        if (!isPen(tag)) return null
        val iso = IsoDep.get(tag)
        iso.connect()
        try {
            iso.timeout = 3000
            return OpenNovReader.read({ iso.transceive(it).also { onExchange() } }, log = { Log.d("SukraraPen", it) }, stopBeforeFor = stopBefore, progress = progress)
        } finally {
            runCatching { iso.close() }
        }
    }
}
