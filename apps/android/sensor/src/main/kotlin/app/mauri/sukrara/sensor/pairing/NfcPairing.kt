// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import android.nfc.Tag
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.sensor.libre3.Libre3Nfc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What one NFC tap did (chunk 18's new-sensor flow turns it into words and next steps). */
sealed interface PairOutcome {
    /** Paired; [activated] = a new sensor started now (warms up for an hour), else taken over. Readings follow. */
    data class Paired(val serial: String, val activated: Boolean) : PairOutcome

    /** No LibreView receiver ID is known: NOTHING was sent to the sensor. Sign in to LibreView, then scan again. */
    data class NeedsLibreViewSignIn(val serial: String, val neverStarted: Boolean) : PairOutcome

    /**
     * The sensor is bound to a receiver ID V1 doesn't have (another app or account started it). Nothing changed on the
     * sensor. If [showcaseInstalled]: "Stop the sensor in Sukrara Showcase first (Settings › Sensor › Stop) or uninstall
     * it", else sign in with the LibreView account that started it — or keep the other app until the next sensor.
     */
    data class OwnedByAnotherApp(val serial: String, val showcaseInstalled: Boolean) : PairOutcome

    data class Ended(val serial: String) : PairOutcome

    /** A Libre 1 / 2 / 2 Plus: not supported, nothing was sent (use Juggluco as the source). */
    data object Libre2Unsupported : PairOutcome

    data class Unsupported(val why: String) : PairOutcome

    /** The tap didn't complete (moved away, no answer); scanning again is safe. [why] is for diagnostics. */
    data class Failed(val why: String) : PairOutcome
}

/**
 * Starts or takes over a Libre 3 with one NFC tap (receiver-ID policy: [ReceiverIds]) and hands a new pairing to the
 * source. Every successful tap REPLACES the saved pairing with no authorization (the sensor gave a new PIN), so the
 * next Bluetooth connection runs a fresh pairing.
 */
class NfcPairing internal constructor(
    private val pairings: PairingStore,
    private val ids: StateStore<ReceiverIdState>,
    private val showcaseInstalled: () -> Boolean,
    private val onPaired: () -> Unit,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) {
    suspend fun pair(tag: Tag): PairOutcome = withContext(io) {
        val (account, own) = receiverIds()
        handle(Libre3Nfc.scan(tag, account, own, log))
    }

    /** The account's receiver ID and V1's own one (made once, on the first tap). */
    internal suspend fun receiverIds(): Pair<Long?, Long> {
        ids.update { if (it.own == null) it.copy(own = ReceiverIds.newOwn()) else it }
        val s = ids.data.first()
        return s.account to checkNotNull(s.own)
    }

    internal suspend fun handle(result: Libre3Nfc.Result): PairOutcome = when (result) {
        is Libre3Nfc.Result.Paired -> {
            pairings.save(result.pairing.copy(kAuth = null, ended = false))
            onPaired()
            PairOutcome.Paired(result.pairing.serial, result.activated)
        }
        is Libre3Nfc.Result.NeedsAccount -> PairOutcome.NeedsLibreViewSignIn(result.serial, result.neverStarted)
        is Libre3Nfc.Result.OwnedByAnotherReceiver -> PairOutcome.OwnedByAnotherApp(result.serial, showcaseInstalled())
        is Libre3Nfc.Result.Ended -> PairOutcome.Ended(result.serial)
        Libre3Nfc.Result.Libre2 -> PairOutcome.Libre2Unsupported
        is Libre3Nfc.Result.Unsupported -> PairOutcome.Unsupported(result.why)
        is Libre3Nfc.Result.Failed -> PairOutcome.Failed(result.why)
    }
}
