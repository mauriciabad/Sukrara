// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.broadcasts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.integrations.CurrentSensor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

/** The installed apps as the broadcasts need them; [PlatformApps] on the phone, a fake in tests. Call off the main thread. */
interface AppDirectory {
    /** Installed packages (not this app) → the Sukrara actions each declares a receiver for. */
    fun receivers(): Map<String, Set<String>>

    fun label(pkg: String): String?

    /** The SHA-256 (hex) of the package's current signing certificate; null when not installed or not one signer. */
    fun signer(pkg: String): String?

    /** Whether [pkg] is installed and still signed with [sha256]. */
    fun signedBy(
        pkg: String,
        sha256: String,
    ): Boolean

    /** Sends [action] to [pkg] only; false when Android refused it. */
    fun send(
        pkg: String,
        action: String,
        extras: Map<String, Any>,
    ): Boolean
}

/** What the page's status line says: when the last reading went out, or why the newest one didn't. */
data class BcStatus(
    val lastSent: Instant?,
    val hold: Hold?,
)

/**
 * Sends the wearer's live Libre 3 readings to the apps the person allowed (schema `apps.broadcasts`, none by default):
 * glucose is health data and any app can declare a receiver. Since Android 8 a manifest receiver only gets explicit
 * broadcasts, so each allowed package gets its own copy (`setPackage`), and only while it is still signed by the
 * certificate it had when it was allowed: a reinstalled app with another signer gets nothing until it is allowed
 * again. The rules for what goes out are [BroadcastPlan]'s. Nothing here can crash the process (alerts run in it).
 */
class Broadcaster(
    private val settings: SettingsRepository,
    private val store: StateStore<BcState>,
    /** The Libre 3 driver's live readings (never history; only a real Libre 3 pairing emits). */
    private val live: Flow<LiveReading>,
    private val sensor: Flow<CurrentSensor?>,
    private val role: Flow<Role?>,
    private val apps: AppDirectory,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
) {
    val settingsFlow: Flow<BcSettings> = settings.flow(SETTING).map(::decode)

    private val hold = MutableStateFlow<Hold?>(null)

    val status: Flow<BcStatus> =
        combine(store.data, hold) { st, h ->
            BcStatus(st.lastSentMs.takeIf { it > 0 }?.let(Instant::ofEpochMilli), h)
        }

    fun start(scope: CoroutineScope) {
        scope.launch {
            live.collect { r ->
                try {
                    onLive(r)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // a PackageManager or store failure skips this reading; the next one tries again
                    log("broadcast failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    internal suspend fun onLive(r: LiveReading) {
        val allowed = settingsFlow.first().apps
        if (allowed.isEmpty()) return
        withContext(io) {
            val declared = apps.receivers()
            val trusted =
                allowed
                    .filter { it.pkg in declared && apps.signedBy(it.pkg, it.signer) }
                    .associate { it.pkg to declared.getValue(it.pkg) }
            val st = store.data.first()
            when (val d = BroadcastPlan.decide(role.first(), r, clock.now(), st, sensor.first(), trusted)) {
                is BcDecision.Wait -> {
                    hold.value = d.why
                }

                is BcDecision.Send -> {
                    val delivered =
                        d.actions
                            .mapValues { (pkg, actions) ->
                                actions.filter { a ->
                                    runCatching { apps.send(pkg, a, BroadcastPayloads.extras(a, d.out)) }.getOrDefault(false)
                                }
                            }.filterValues { it.isNotEmpty() }
                    if (delivered.isNotEmpty()) {
                        store.update { BroadcastPlan.after(it, d, r.lifeCount, delivered) }
                        hold.value = null
                        log("broadcast to ${delivered.size} app(s)")
                    }
                }
            }
        }
    }

    /** Allows [pkg], pinning its current signer; false when it isn't installed or has no single signer. */
    suspend fun allow(pkg: String): Boolean {
        val signer = withContext(io) { apps.signer(pkg) } ?: return false
        change { s -> s.copy(apps = s.apps.filterNot { it.pkg == pkg } + AllowedApp(pkg, signer)) }
        return true
    }

    suspend fun remove(pkg: String) = change { s -> s.copy(apps = s.apps.filterNot { it.pkg == pkg }) }

    private suspend fun change(transform: (BcSettings) -> BcSettings) = settings.update(SETTING) { encode(transform(decode(it))) }

    companion object {
        val SETTING = SchemaSettings.text("apps.broadcasts", default = "")
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): BcSettings =
            text.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(BcSettings.serializer(), it) }.getOrNull() }
                ?: BcSettings()

        fun encode(s: BcSettings): String = json.encodeToString(BcSettings.serializer(), s)
    }
}

/** [AppDirectory] over PackageManager and `sendBroadcast`. */
class PlatformApps(
    context: Context,
) : AppDirectory {
    private val context = context.applicationContext
    private val pm: PackageManager get() = context.packageManager

    override fun receivers(): Map<String, Set<String>> {
        val acts = HashMap<String, MutableSet<String>>()
        for (a in BroadcastPayloads.ACTIONS) {
            pm
                .queryBroadcastReceivers(Intent(a), PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != context.packageName }
                .forEach { acts.getOrPut(it) { mutableSetOf() } += a }
        }
        return acts
    }

    override fun label(pkg: String): String? =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString() }.getOrNull()

    override fun signer(pkg: String): String? {
        val info =
            runCatching {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            }.getOrNull()?.signingInfo ?: return null
        // an app signed by several certificates at once is refused: one pin can't stand for all of them
        if (info.hasMultipleSigners()) return null
        val cert = info.signingCertificateHistory?.lastOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).toHex()
    }

    override fun signedBy(
        pkg: String,
        sha256: String,
    ): Boolean = runCatching { pm.hasSigningCertificate(pkg, sha256.hexToBytes(), PackageManager.CERT_INPUT_SHA256) }.getOrDefault(false)

    override fun send(
        pkg: String,
        action: String,
        extras: Map<String, Any>,
    ): Boolean {
        context.sendBroadcast(
            Intent(action)
                .setPackage(pkg)
                .putExtras(extras.toBundle())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND),
        )
        return true
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any>.toBundle(): Bundle =
    Bundle().also { b ->
        for ((k, v) in this) {
            when (v) {
                is Double -> b.putDouble(k, v)
                is Long -> b.putLong(k, v)
                is Int -> b.putInt(k, v)
                is Float -> b.putFloat(k, v)
                is String -> b.putString(k, v)
                is Map<*, *> -> b.putBundle(k, (v as Map<String, Any>).toBundle())
                else -> error("unsupported extra $k: ${v::class}")
            }
        }
    }
