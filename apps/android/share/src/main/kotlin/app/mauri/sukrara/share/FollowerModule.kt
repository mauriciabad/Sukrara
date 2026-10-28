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
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.ble.AndroidNearbyCentral
import app.mauri.sukrara.share.ble.NearbyTransportFactory
import app.mauri.sukrara.share.crypto.SdkNostrCrypto
import app.mauri.sukrara.share.follower.FollowerEngine
import app.mauri.sukrara.share.follower.FollowerNotice
import app.mauri.sukrara.share.follower.FollowerStore
import app.mauri.sukrara.share.follower.RelayTransportFactory
import app.mauri.sukrara.share.transport.OkHttpWebSockets
import app.mauri.sukrara.share.transport.RelayPool

/**
 * The follower role's sharing graph (composed by `AppGraph`). Nothing is built until first use; the engine connects
 * only while the monitoring service hosts it as the follower's reading source. [notices] = the notification for what
 * the wearer said (invite used, declined, access removed).
 */
class FollowerModule(
    private val context: Context,
    private val data: DataModule,
    private val hub: GlucoseHub,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val notices: (FollowerNotice, Profile?) -> Unit,
) {
    private val crypto by lazy { SdkNostrCrypto() }

    val engine: FollowerEngine by lazy {
        val relays =
            RelayTransportFactory { urls, auth ->
                RelayPool(urls, auth, crypto, OkHttpWebSockets(data.http), dispatchers.io, { clock.now().epochSecond })
            }
        FollowerEngine(
            crypto = crypto,
            store = FollowerStore(data.secureStore, crypto),
            transports = relays,
            readingsRepo = data.readings,
            treatments = data.treatments,
            clock = clock,
            dispatcher = dispatchers.default,
            setLink = hub::setLink,
            onNotice = notices,
            nearbyTransports = NearbyTransportFactory { identity -> AndroidNearbyCentral(context, crypto, identity) },
        ).also { watchNetwork(it) }
    }

    /** The network came back: reconnect relays now instead of waiting out the backoff. */
    private fun watchNetwork(engine: FollowerEngine) {
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = engine.reconnectNow()
            },
        )
    }
}
