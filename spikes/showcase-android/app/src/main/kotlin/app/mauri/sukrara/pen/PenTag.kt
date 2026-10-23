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
 * One NFC tag → a pen read (blocking: call off the main thread). The pen is an NFC Forum Type 4 tag, but Android's own
 * NDEF check times out on it (NFA_NDEF_DETECT_EVT 0xB2, seen on the user's Pixel with a real NovoPen Echo Plus,
 * 2026-10-23), so the tag arrives as IsoDep + NfcA without Ndef: every IsoDep tag (bank and transit cards, passports,
 * phones) reaches us and only the pen's application selection tells a pen apart ([Outcome.NOT_PEN], said nothing about).
 * A pen that broke off mid-transfer throws: nothing of a partial read is used (PRODUCT §3.13).
 */
object PenTag {
    enum class Outcome { PEN, NOT_PEN, FAILED }

    /**
     * What a read attempt was: a pen read ([PEN]), a tag that never answered the pen's selection ([NOT_PEN]: finish
     * silently, no card, no vibration), or a pen that answered and then failed ([FAILED]: the failure card).
     */
    fun outcome(selected: Boolean, error: Throwable?): Outcome = when {
        error == null && selected -> Outcome.PEN
        !selected || error is app.mauri.sukrara.pen.opennov.NotAPenException -> Outcome.NOT_PEN
        else -> Outcome.FAILED
    }

    /** IsoDep, and not some other NDEF tag type (an NDEF sticker that isn't Type 4). */
    fun isPen(tag: Tag): Boolean = IsoDep.get(tag) != null &&
        (Ndef.get(tag)?.type?.let { it == Ndef.NFC_FORUM_TYPE_4 } ?: true)

    /** [onSelected]: the tag answered the pen's selection (it is a pen). */
    fun read(tag: Tag, onSelected: () -> Unit = {}, progress: (Float) -> Unit = {}, stopBefore: (String) -> Long? = { null }): PenRead? {
        if (!isPen(tag)) return null
        val iso = IsoDep.get(tag)
        iso.connect()
        try {
            iso.timeout = 3000
            return OpenNovReader.read({ iso.transceive(it) }, log = { Log.d("SukraraPen", it) }, stopBeforeFor = stopBefore,
                progress = progress, onSelected = onSelected)
        } finally {
            runCatching { iso.close() }
        }
    }
}
