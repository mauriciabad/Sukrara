// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import app.mauri.sukrara.core.SensorOutputs
import android.content.Context
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.core.SecureStore
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.mauri.sukrara.upload.CloudUploader
import app.mauri.sukrara.upload.UploadLoop
import app.mauri.sukrara.upload.UploadStatus as Status
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Settings → Share with apps → Tidepool (docs/27): uploads the wearer's readings (one per 5 minutes) and treatments to
 * the user's Tidepool account, off until the user signs in and switches it on. A follower uploads the person it follows
 * only after its own separate choice ([uploadFollowed], default off).
 *
 * The session token (and the account's user id) live in a Keystore-encrypted file; the password is used once to sign
 * in and never kept. Every [INTERVAL_MS] (or when a treatment / setting changes) one cycle works out what Tidepool
 * doesn't have yet from the role's own stores ([TidepoolQueue]) and sends it; failures back off up to an hour.
 */
object Tidepool : CloudUploader {
    private const val TAG = "Sukrara.tidepool"
    private const val INTERVAL_MS = 5 * 60_000L

    /** [on] is the user's switch; it uploads only while [signedIn] too (a session Tidepool ended keeps it on). */
    data class Settings(val on: Boolean = false, val email: String = "", val signedIn: Boolean = false,
                        val glucose: Boolean = true, val treatments: Boolean = true, val uploadFollowed: Boolean = false)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    /** What the settings page shows. kind: off | sign_in | not_allowed | waiting | ok | error. */
    private val _status = MutableStateFlow(Status())
    override val status: StateFlow<Status> = _status
    override val title = "Tidepool"   // i18n-ok: brand name
    override val uploading get() = _settings.value.let { it.on && it.signedIn && loaded && TidepoolQueue.allowed(App.role(app), it.uploadFollowed) }

    @Volatile private var loaded = false
    private lateinit var app: Context
    private lateinit var secure: SecureStore
    private val client = TidepoolClient(BuildConfig.VERSION_NAME)
    private val loop = UploadLoop(TAG, INTERVAL_MS) { cycle() }

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            app = ctx.applicationContext
            secure = SecureStore(File(app.filesDir, "tidepool.bin"), "sukrara.tidepool")
            val s = runCatching { secure.read() }.getOrNull()
            s?.let { client.restore(TidepoolClient.Session(it.getString("token"), it.getString("userId"))) }
            client.onToken = { saveSession(it) }
            _settings.value = readSettings(prefs().getString("email", "")!!, s != null)
            _status.value = Status(if (!_settings.value.on) "off" else "waiting", prefs().getLong("lastOkMs", 0), prefs().getInt("lastCount", 0))
            loaded = true
        }
    }

    private fun prefs() = app.getSharedPreferences("tidepool", Context.MODE_PRIVATE)

    private fun readSettings(email: String, signedIn: Boolean) = prefs().let { p ->
        Settings(p.getBoolean("on", false), email, signedIn, p.getBoolean("glucose", true), p.getBoolean("treatments", true),
            p.getBoolean("uploadFollowed", false))
    }

    private fun refreshSettings() { _settings.value = readSettings(_settings.value.email, client.session != null) }

    // ============================================================================================ account

    /** Signs in (blocking: call off the main thread); throws with Tidepool's reason when the login is refused. */
    fun signIn(ctx: Context, email: String, password: String) {
        init(ctx)
        val s = client.login(email, password)
        saveSession(s)
        // another account starts from scratch; the same one again keeps what it has (Tidepool notes aren't deduplicated)
        val account = sha256(s.userId)
        if (prefs().getString("account", null) != account) prefs().edit().apply {
            for (k in prefs().all.keys) if (k.startsWith("state.") || k.startsWith("dataSet.")) remove(k)
        }.apply()
        prefs().edit().putString("account", account).putString("email", email.trim()).apply()
        _settings.value = readSettings(email.trim(), true)
        kick()
    }

    private fun saveSession(s: TidepoolClient.Session) {
        secure.write(JSONObject().put("token", s.token).put("userId", s.userId))
    }

    private fun sha256(x: String) = java.security.MessageDigest.getInstance("SHA-256").digest(x.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Signs out: ends the session and forgets it and the switches here. What was uploaded stays on Tidepool. */
    fun signOut(ctx: Context) {
        init(ctx)
        Thread { runCatching { client.logout() } }.start()
        secure.wipe()
        // what this account already has stays known (state, upload record), so signing in again sends no note twice
        prefs().edit().apply { for (k in listOf("on", "email", "glucose", "treatments", "uploadFollowed", "lastOkMs", "lastCount")) remove(k) }.apply()
        client.restore(null)
        _settings.value = Settings()
        _status.value = Status()
    }

    fun setOn(ctx: Context, on: Boolean) = configure(ctx) { putBoolean("on", on) }
    fun setGlucose(ctx: Context, on: Boolean) = configure(ctx) { putBoolean("glucose", on) }
    fun setTreatments(ctx: Context, on: Boolean) = configure(ctx) { putBoolean("treatments", on) }
    /** The follower's separate decision: upload the person it follows to the signed-in Tidepool account. */
    fun setUploadFollowed(ctx: Context, on: Boolean) = configure(ctx) { putBoolean("uploadFollowed", on) }

    private fun configure(ctx: Context, f: android.content.SharedPreferences.Editor.() -> Unit) {
        init(ctx)
        prefs().edit().apply(f).apply()
        refreshSettings()
        kick()
    }

    override fun kick() = loop.kick()

    // ============================================================================================ upload loop

    /** Started by the role's service (wearer: WearerExtras, follower: FollowerExtras); one loop per process. */
    @Synchronized
    fun start(ctx: Context, scope: CoroutineScope) {
        init(ctx)
        if (!loop.start(scope)) return
        // a new or changed treatment goes up within the cycle, not five minutes later (wearer's log)
        if (App.role(ctx) == "owner") scope.launch { Treat.init(ctx); Treat.store.items.collect { kick() } }
    }

    @Synchronized
    fun stop() = loop.stop()

    private fun cycle() {
        val s = _settings.value
        val role = App.role(app)
        if (!s.on) { _status.value = _status.value.copy(kind = "off", error = null); return }
        if (client.session == null) { _status.value = _status.value.copy(kind = "sign_in", error = null); return }
        if (!TidepoolQueue.allowed(role, s.uploadFollowed)) { _status.value = _status.value.copy(kind = "not_allowed", error = null); return }
        val key = "state.$role"
        val nowMs = System.currentTimeMillis()
        val nowS = nowMs / 1000
        var state = TidepoolQueue.decode(prefs().getString(key, null))
        if (state.from == 0L) state = state.copy(from = nowS - TidepoolQueue.WINDOW_S)   // first cycle: backfill what's stored
        if (nowMs < state.nextAttemptMs) return
        val save = { st: TidepoolQueue.State -> state = st; prefs().edit().putString(key, TidepoolQueue.encode(st)).apply() }

        val (readings, treatments) = load(role!!, maxOf(state.from, nowS - TidepoolQueue.WINDOW_S))
        val plan = TidepoolQueue.plan(readings, treatments, state, s.glucose, s.treatments, nowS)
        if (plan.isEmpty) {
            save(state)
            _status.value = _status.value.copy(kind = "ok", error = null)
            return
        }
        try {
            val zone = ZoneId.systemDefault()
            val device = deviceId(role)
            val version = BuildConfig.VERSION_NAME
            val dataSet = dataSet(role, device, zone, nowS)
            TidepoolSync.send(client, dataSet, plan, state, zone, device, version, nowS, save)
            prefs().edit().putLong("lastOkMs", nowMs).putInt("lastCount", plan.count).apply()
            _status.value = Status("ok", nowMs, plan.count)
            Log.i(TAG, "uploaded ${plan.cbg.size} readings, ${plan.upserts.size} entries, ${plan.deletes.size + plan.noteDeletes.size} deletions, ${plan.noteCreates.size + plan.noteEdits.size} notes")
        } catch (e: TidepoolClient.SignedOut) {
            Log.w(TAG, "signed out: ${e.message}")
            secure.wipe()
            client.restore(null)
            refreshSettings()
            _status.value = _status.value.copy(kind = "sign_in", error = null)
        } catch (e: Exception) {
            // the upload record was deleted on Tidepool (by the user): make a new one next time
            if (e is TidepoolClient.Failure && e.code == 404) prefs().edit().remove("dataSet.$role").apply()
            save(TidepoolQueue.failed(state, nowMs))
            _status.value = _status.value.copy(kind = "error", error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** The wearer's reading's sensor model, from its `dev` tag. */
    internal fun sensorOf(dev: String): String? = SensorOutputs.profileOf(dev)?.let { SensorOutputs.model(it) }

    /** The role's readings and treatments from [from] (the wearer's 31-day store, or what the follower received). */
    private fun load(role: String, from: Long): Pair<List<TidepoolData.Glucose>, List<Treatment>> = when (role) {
        "owner" -> {
            Treat.init(app)
            App.owner(app).history(from).map { TidepoolData.Glucose(it.t, it.sgv, it.rate, sensorOf(it.dev)) } to Treat.store.all()
        }
        "follower" -> {
            val e = App.follower(app)
            e.history(from).map { TidepoolData.Glucose(it.t, it.sgv, it.rate) } to e.treatmentsSince(from)
        }
        else -> emptyList<TidepoolData.Glucose>() to emptyList()
    }

    /** One device id per install and role, so a wearer's and a follower's uploads stay apart on the account. */
    private fun deviceId(role: String): String = prefs().getString("deviceId.$role", null)
        ?: ("Sukrara-" + (if (role == "owner") "wearer" else "following") + "-" + java.util.UUID.randomUUID().toString().take(8))
            .also { prefs().edit().putString("deviceId.$role", it).apply() }

    /** This install's upload record on the account: remembered, found again by device id, or created. */
    private fun dataSet(role: String, device: String, zone: ZoneId, nowS: Long): String {
        prefs().getString("dataSet.$role", null)?.let { return it }
        val found = client.dataSets().firstOrNull {
            val o = it as? kotlinx.serialization.json.JsonObject
            o?.get("deviceId")?.toString()?.trim('"') == device
        }?.let { o -> (o as kotlinx.serialization.json.JsonObject).let { (it["uploadId"] ?: it["id"]).toString().trim('"') } }
        val id = found ?: client.createDataSet(TidepoolData.dataSet(device, if (role == "owner") "Sukrara" else "Sukrara (following)",
            BuildConfig.VERSION_NAME, zone, nowS))
        prefs().edit().putString("dataSet.$role", id).apply()
        return id
    }
}
