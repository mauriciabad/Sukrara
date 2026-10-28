// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.mauri.sukrara.core.data.DataModule
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.share.crypto.SdkNostrCrypto
import app.mauri.sukrara.share.owner.OwnerEngine
import app.mauri.sukrara.share.owner.OwnerIdentityStore
import app.mauri.sukrara.share.owner.SeenWraps
import app.mauri.sukrara.share.owner.requests.RequestNotifications
import app.mauri.sukrara.share.owner.requests.RequestNotifier
import app.mauri.sukrara.share.owner.usecase.MirroredSettingsSource
import app.mauri.sukrara.share.owner.usecase.OwnerContext
import app.mauri.sukrara.share.owner.usecase.RandomBytes
import app.mauri.sukrara.share.transport.OkHttpWebSockets
import app.mauri.sukrara.share.transport.RelayPool
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The wearer's sharing graph (composed by `AppGraph`, owner role only). Nothing is built until first use.
 * [requestNotifications] = the access-request notification (the app's notification table posts it); [settings] = the
 * wearer's settings for the followers' mirror; [scope] = the process scope (the notification's Allow / Deny).
 */
class ShareOwnerModule(
    private val context: Context,
    private val data: DataModule,
    private val hub: GlucoseHub,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    val scope: CoroutineScope,
    val requestNotifications: RequestNotifications,
    private val settings: MirroredSettingsSource = MirroredSettingsSource.None,
) {
    val crypto by lazy { SdkNostrCrypto() }

    private val ownerContext by lazy {
        val random = SecureRandom()
        OwnerContext(crypto, RandomBytes { n -> ByteArray(n).also(random::nextBytes) })
    }

    val identityStore by lazy { OwnerIdentityStore(data.secureStore) }

    /** Relays read gift wraps addressed to the identity only after NIP-42 AUTH as it: the pool gets I when known. */
    private val transport by lazy {
        RelayPool(Wire.DEFAULT_RELAYS, authKey = { ownerEngine.identityKey() }, crypto, OkHttpWebSockets(data.http), dispatchers.io, { clock.now().epochSecond })
    }

    private val requests by lazy { RequestNotifier(requestNotifications) }

    val ownerEngine: OwnerEngine by lazy {
        val seen = SeenWraps { id, at -> data.database.seenWraps().markSeen(id, at) }
        OwnerEngine(
            ownerContext, identityStore, transport, hub, seen, data.treatments, clock, dispatchers.default,
            settings = settings,
            onRequest = { requests.onRequest(it) },
        ).also { engine ->
            watchNetwork()
            // the notification goes as soon as its request is answered anywhere (app, notification) or paused
            scope.launch { engine.state.collect { requests.onState(it) } }
        }
    }

    /**
     * Allow / Deny from the notification; false when it didn't happen (the request isn't waiting any more, its invite
     * expired, another phone also tried it, sharing is paused). An engine started here because the process was asleep
     * is stopped again: the saved key delivery goes out on the next start (the sharing service owns relays).
     */
    suspend fun answer(follower: String, allow: Boolean): Boolean {
        val engine = ownerEngine
        val wasRunning = engine.running
        engine.start(scope)
        try {
            val request = engine.pendingRequest(follower) ?: return false
            if (!allow) return true.also { engine.deny(follower) }
            return request.others == 0 && engine.approve(follower)
        } finally {
            if (!wasRunning) engine.stop()
        }
    }

    /** The network came back: reconnect relays now instead of waiting out the backoff. */
    private fun watchNetwork() {
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = transport.reconnectNow()
            },
        )
    }
}
