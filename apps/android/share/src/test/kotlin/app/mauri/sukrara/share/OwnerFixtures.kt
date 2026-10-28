// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.RumorCodec
import app.mauri.sukrara.share.crypto.SdkNostrCrypto
import app.mauri.sukrara.share.owner.OwnerState
import app.mauri.sukrara.share.owner.usecase.OwnerContext
import app.mauri.sukrara.share.owner.usecase.RandomBytes
import kotlin.random.Random

/** Shared test setup: real crypto (nostr-sdk JVM build), seeded randomness, synthetic keys only. */
object OwnerFixtures {
    val crypto = SdkNostrCrypto()
    const val NOW = 1_792_000_000L

    fun context(seed: Int = 1): OwnerContext {
        val random = Random(seed)
        return OwnerContext(crypto, RandomBytes { n -> random.nextBytes(n) })
    }

    /** A follower's request (30500 `request`) gift-wrapped to the wearer's identity. */
    fun request(follower: KeyPair, s: OwnerState, token: String, name: String, now: Long = NOW): SignedEvent =
        EventFactory(crypto).gift(
            follower,
            s.identity(crypto).public,
            Rumor.Request(token, Profile(name, null)),
            now,
            now + Wire.TTL_REQUEST,
        )

    fun toOwner(follower: KeyPair, s: OwnerState, rumor: Rumor, now: Long = NOW): SignedEvent =
        EventFactory(crypto).gift(follower, s.identity(crypto).public, rumor, now, now + Wire.TTL_REQUEST)

    /** What [follower] reads from a 30501 wrap the wearer published; null if it isn't for them. */
    fun open(follower: KeyPair, wrap: SignedEvent): Rumor? =
        crypto.unwrap(follower.secret, wrap)?.takeIf { it.kind == Wire.KIND_TO_FOLLOWER }?.let { RumorCodec.decode(it.kind, it.content) }
}
