// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import app.mauri.sukrara.core.ActiveSensor
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.core.SecureStore
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.upload.CloudUploader
import app.mauri.sukrara.upload.UploadLoop
import app.mauri.sukrara.upload.UploadStatus
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Settings → Share with apps → Nightscout upload (docs/25): the wearer's readings (every one, 30 days back), treatments,
 * sensor starts and the phone's battery to the user's own Nightscout site. Off by default; wearer only (started by
 * `WearerExtras`, never by the follower). Upload only: nothing is read back into Sukrara.
 *
 * The access token or API secret lives in a Keystore-encrypted file; the site and the switches in plain prefs. One cycle
 * after every reading (and every 5 minutes) plans what the site lacks from the phone's stores ([NightscoutQueue]) and
 * sends it ([NightscoutSync]). A refused credential stops uploading until it's changed; another uploader writing the
 * same site pauses the readings until the user confirms.
 */
object Nightscout : CloudUploader {
    private const val TAG = "Sukrara.nightscout"
    private const val INTERVAL_MS = 5 * 60_000L

    /** [credential] is never exposed: only whether one is set and which kind. */
    data class Settings(
        val on: Boolean = false, val url: String = "", val tokenKind: Boolean = true, val hasCredential: Boolean = false,
        val api: NsApi = NsApi.V1, val readings: Boolean = true, val treatments: Boolean = true, val sensor: Boolean = true,
        val battery: Boolean = true,
    ) {
        val site get() = NsSite.parse(url)
        val configured get() = url.isNotBlank() && site.problem == null && hasCredential
    }

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    /** kind: off | setup | sign_in | paused (error = the other device) | waiting | ok | error. */
    private val _status = MutableStateFlow(UploadStatus())
    override val status: StateFlow<UploadStatus> = _status
    override val title = "Nightscout"   // i18n-ok: brand name
    override val uploading get() = _settings.value.let { it.on && it.configured } && loaded && App.role(app) == "owner"

    @Volatile private var loaded = false
    private lateinit var app: Context
    private lateinit var secure: SecureStore
    private val loop = UploadLoop(TAG, INTERVAL_MS) { cycle() }
    private var client: Pair<NsConfig, NsClient>? = null

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            app = ctx.applicationContext
            secure = SecureStore(File(app.filesDir, "nightscout.bin"), "sukrara.nightscout")
            _settings.value = readSettings()
            _status.value = UploadStatus(if (!_settings.value.on) "off" else "waiting", prefs().getLong("lastOkMs", 0), prefs().getInt("lastCount", 0))
            loaded = true
        }
    }

    private fun prefs() = app.getSharedPreferences("nightscout", Context.MODE_PRIVATE)

    private fun readSettings(): Settings = prefs().let { p ->
        val cred = runCatching { secure.read() }.getOrNull()
        Settings(p.getBoolean("on", false), p.getString("url", "")!!, cred?.optString("kind", "token") != "secret", cred != null,
            if (p.getString("api", "v1") == "v3") NsApi.V3 else NsApi.V1,
            p.getBoolean("readings", true), p.getBoolean("treatments", true), p.getBoolean("sensor", true), p.getBoolean("battery", true))
    }

    private fun auth(): NsAuth? = runCatching { secure.read() }.getOrNull()?.let {
        val v = it.getString("value")
        if (it.optString("kind") == "secret") NsAuth.ApiSecret(v) else NsAuth.AccessToken(v)
    }

    // ============================================================================================ settings

    /**
     * Saves the site and, when [credential] isn't null, the token / secret. A different site starts from scratch (its
     * own 30-day backfill); the same site keeps what it has. Clears a refused-credential stop.
     */
    fun configure(ctx: Context, url: String, token: Boolean, credential: String?, api: NsApi) {
        init(ctx)
        val site = NsSite.parse(url)
        require(site.problem == null) { site.problem!! }
        if (credential != null) secure.write(JSONObject().put("kind", if (token) "token" else "secret").put("value", credential.trim()))
        val e = prefs().edit()
        if (prefs().getString("url", "") != site.root) e.remove("state")
        e.putString("url", site.root).putString("api", if (api == NsApi.V3) "v3" else "v1").remove("authFailed").apply()
        client = null
        refresh()
    }

    fun setOn(ctx: Context, on: Boolean) = edit(ctx) { putBoolean("on", on) }
    fun setReadings(ctx: Context, on: Boolean) = edit(ctx) { putBoolean("readings", on) }
    fun setTreatments(ctx: Context, on: Boolean) = edit(ctx) { putBoolean("treatments", on) }
    fun setSensor(ctx: Context, on: Boolean) = edit(ctx) { putBoolean("sensor", on) }
    fun setBattery(ctx: Context, on: Boolean) = edit(ctx) { putBoolean("battery", on) }

    /** "Upload anyway": the user confirmed that this phone should upload next to the other device. */
    fun acceptConflict(ctx: Context) {
        init(ctx)
        val st = NightscoutQueue.decode(prefs().getString("state", null))
        prefs().edit().putString("state", NightscoutQueue.encode(st.copy(conflictAccepted = true))).apply()
        kick()
    }

    /** Forgets the site, the credential and what was sent (what's on the site stays there). */
    fun remove(ctx: Context) {
        init(ctx)
        secure.wipe()
        prefs().edit().clear().apply()
        client = null
        _settings.value = Settings()
        _status.value = UploadStatus()
    }

    private fun edit(ctx: Context, f: android.content.SharedPreferences.Editor.() -> Unit) {
        init(ctx)
        prefs().edit().apply(f).apply()
        refresh()
    }

    private fun refresh() { _settings.value = readSettings(); kick() }

    /**
     * "Test connection" (blocking; call off the main thread): the site's status with this credential. Returns the
     * Nightscout version, or throws with the reason.
     */
    fun test(url: String, token: Boolean, credential: String, api: NsApi): String {
        val cfg = NsConfig(NsSite.parse(url), if (token) NsAuth.AccessToken(credential.trim()) else NsAuth.ApiSecret(credential.trim()), api)
        val (r, o) = NsClient(cfg, UrlConnectionHttp()).call("GET", if (api == NsApi.V3) "/api/v3/status" else "/api/v1/status.json")
        if (o !is Outcome.Ok) throw NsFailure(o)
        return runCatching { JSONObject(r!!.body).let { it.optJSONObject("result")?.optString("version") ?: it.optString("version") } }
            .getOrNull().orEmpty()
    }

    override fun kick() = loop.kick()

    // ============================================================================================ upload loop

    /** Started by the wearer's service only; one loop per process. */
    @Synchronized
    fun start(ctx: Context, scope: CoroutineScope) {
        init(ctx)
        if (!loop.start(scope)) return
        scope.launch { Treat.init(ctx); Treat.store.items.collect { kick() } }     // an entry goes up at once
        scope.launch { SensorLife.current.collect { kick() } }                    // a new sensor too
    }

    @Synchronized
    fun stop() = loop.stop()

    private fun cycle() {
        val s = _settings.value
        if (!s.on) { _status.value = _status.value.copy(kind = "off", error = null); return }
        val auth = auth()
        if (!s.configured || auth == null) { _status.value = _status.value.copy(kind = "setup", error = null); return }
        if (App.role(app) != "owner") { _status.value = _status.value.copy(kind = "off", error = null); return }   // wearer only
        if (prefs().getBoolean("authFailed", false)) { _status.value = _status.value.copy(kind = "sign_in", error = null); return }
        val nowMs = System.currentTimeMillis()
        val nowS = nowMs / 1000
        var state = NightscoutQueue.decode(prefs().getString("state", null))
        if (state.from == 0L) state = state.copy(from = nowS - NightscoutQueue.WINDOW_S)   // first cycle: backfill 30 days
        if (nowMs < state.nextAttemptMs) return
        val save = { st: NightscoutQueue.State -> state = st; prefs().edit().putString("state", NightscoutQueue.encode(st)).apply() }
        val cfg = NsConfig(s.site, auth, if (auth is NsAuth.AccessToken) s.api else NsApi.V1)
        val c = client?.takeIf { it.first == cfg }?.second ?: NsClient(cfg, UrlConnectionHttp()).also { client = cfg to it }
        val zone = TimeZone.getDefault()
        try {
            save(NightscoutSync.reconcile(c, cfg, state, nowMs))
            Treat.init(app)
            val readings = App.owner(app).history(maxOf(state.from, nowS - NightscoutQueue.WINDOW_S) - 15 * 60)
            val plan = NightscoutQueue.plan(readings, Treat.store.all(), SensorLife.current.value, state,
                NightscoutQueue.Options(s.readings, s.treatments, s.sensor, s.battery), nowS)
            val r = NightscoutSync.send(c, cfg, plan, state, readings, zone, nowS, save, ActiveSensor.profile.value)
            if (s.battery) battery()?.let { (p, charging) -> NightscoutSync.battery(c, cfg, state, p, charging, nowMs, zone)?.let(save) }
            save(NightscoutQueue.ok(state))
            if (r.sent > 0) {
                prefs().edit().putLong("lastOkMs", nowMs).putInt("lastCount", r.sent).apply()
                Log.i(TAG, "sent ${r.sent}, dropped ${r.dropped}")
            }
            val last = prefs().getLong("lastOkMs", 0)
            _status.value = when {
                state.conflict != null && !state.conflictAccepted -> UploadStatus("paused", last, 0, state.conflict)
                else -> UploadStatus(if (last > 0) "ok" else "waiting", last, prefs().getInt("lastCount", 0))
            }
        } catch (e: NsFailure) {
            val o = e.outcome
            if (o is Outcome.AuthFailed) {
                prefs().edit().putBoolean("authFailed", true).apply()
                _status.value = _status.value.copy(kind = "sign_in", error = null)
                Log.w(TAG, "credential refused: ${o.reason}")
                return
            }
            save(NightscoutQueue.failed(state, nowMs, (o as? Outcome.Retry)?.afterMs))
            _status.value = _status.value.copy(kind = "error", error = e.message)
            throw e
        } catch (e: Exception) {
            save(NightscoutQueue.failed(state, nowMs))
            _status.value = _status.value.copy(kind = "error", error = e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun battery(): Pair<Int, Boolean>? {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (level * 100 / scale) to plugged
    }
}
