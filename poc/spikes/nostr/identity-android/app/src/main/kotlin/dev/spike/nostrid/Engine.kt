// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.EncryptedSecretKey
import org.nostrdevkit.sdk.Keys

/**
 * Everything the PoC does, behind one state document (Keystore-encrypted at rest). UI and DebugReceiver call the
 * same functions, so adb-driven tests exercise exactly what the screens do.
 *
 * Keys: root = identity I (PROTOCOL-v2), never has a kind 0 · P = subkey(root, "profile") carries the public kind 0 ·
 * main key (Nostr login) is never stored and never signs anything public.
 */
class Engine private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var inst: Engine? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Engine(ctx.applicationContext).also { inst = it } }
        const val TAG = "NostrId"
    }

    private val store = SecureStore(File(ctx.filesDir, "account.bin"), "nostrid.account")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(store.read() ?: JSONObject())
    val state: StateFlow<JSONObject> = _state
    val busy = MutableStateFlow<String?>(null)
    val pictures = MutableStateFlow<Map<String, Bitmap>>(emptyMap())   // pubkey → verified picture

    private fun s() = JSONObject(_state.value.toString())
    private fun save(j: JSONObject) {
        store.write(j)
        _state.value = j
        // debug builds: a redacted copy for adb-driven tests (no secrets)
        if (BuildConfig.DEBUG) File(ctx.filesDir, "state.json").writeText(redacted(j).toString(2))
    }
    private fun redacted(j: JSONObject) = JSONObject(j.toString()).apply {
        remove("root"); optJSONArray("invites")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).remove("secret") }
        put("pubs", JSONObject().put("identity", optString("identityPub")).put("profile", profilePubOf(j)))
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val j = s()
        val a = j.optJSONArray("log") ?: JSONArray()
        a.put("${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} $msg")
        while (a.length() > 60) a.remove(0)
        save(j.put("log", a))
    }

    val hasAccount get() = _state.value.has("root")
    private fun root(): Keys = Nostr.keys(Derive.unhex(_state.value.getString("root")))
    private fun profileKeys(): Keys = Nostr.keys(Derive.subkey(Derive.unhex(_state.value.getString("root")), "profile"))
    private fun profilePubOf(j: JSONObject): String? = when {
        !j.has("root") -> null
        j.optString("profileMode") == "nostr" -> j.optString("mainPub")
        else -> Nostr.pub(Nostr.keys(Derive.subkey(Derive.unhex(j.getString("root")), "profile")))
    }
    fun profilePub() = profilePubOf(_state.value)

    private suspend fun <T> work(label: String, f: suspend () -> T): T = mutex.withLock {
        busy.value = label
        try { f() } catch (e: Throwable) { log("❌ $label: ${e.message}"); throw e } finally { busy.value = null }
    }

    private fun newAccount(root: ByteArray, origin: String, mainPub: String?, profileMode: String, extra: JSONObject.() -> Unit = {}) {
        val j = JSONObject().put("root", Derive.hex(root)).put("origin", origin).put("profileMode", profileMode)
            .put("identityPub", Nostr.pub(Nostr.keys(root))).put("created", Nostr.now())
            .put("log", _state.value.optJSONArray("log") ?: JSONArray())
        if (mainPub != null) j.put("mainPub", mainPub)
        j.extra()
        save(j)
    }

    // ----------------------------------------------------------------------------------------------- accounts

    /** Default path: a fresh random account (today's Sukrara behaviour) + a public kind 0 on the profile key. */
    suspend fun createGenerated(name: String, avatar: Avatar?, photo: Bitmap?) = work("Creating your profile") {
        newAccount(Nostr.random(32).let { Derive.subkey(it, "root") }, "generated", null, "own")
        log("account: new random root (backup = this key, in the password manager)")
        publishProfileLocked(name, avatar, photo)
    }

    /**
     * Nostr login with a key the app can read (nsec, NIP-49 ncryptsec, or NIP-06 words): the Sukrara root is
     * HKDF-derived, the main key is dropped right away. Same main key → same account on any phone.
     */
    suspend fun loginWithSecret(input: String, password: String?, profileMode: String, name: String, avatar: Avatar?) =
        work("Deriving your Sukrara account") {
            val main = parseMainKey(input.trim(), password)
            val mainPub = Nostr.pub(main)
            val root = Derive.derivedAccount(Nostr.sec(main), Derive.APP_ID, 0)
            newAccount(root, "derived", mainPub, profileMode)
            log("account: derived from ${Nostr.shortNpub(mainPub)} (main key discarded, nothing published by it)")
            if (profileMode == "own") publishProfileLocked(name, avatar, null)
            else { log("profile: your Nostr profile, shown only to people you approve (no new kind 0)"); fetchPicture(mainPub) }
        }

    private fun parseMainKey(input: String, password: String?): Keys = when {
        input.startsWith("ncryptsec") -> Keys(EncryptedSecretKey.fromBech32(input).decrypt(password ?: error("password needed")))
        input.trim().split(Regex("\\s+")).size in setOf(12, 15, 18, 21, 24) -> Keys.fromMnemonic(input, null, 0u, null, null)
        else -> Keys.parse(input)                                        // nsec or hex
    }

    /** Nostr login through a NIP-55 signer: find (or create) the sealed root addressed to the main key. */
    suspend fun loginWithSigner(profileMode: String, name: String, avatar: Avatar?) = work("Connecting to your signer") {
        val s = Signer.connect(ctx)
        log("signer: ${s.pkg}, main ${Nostr.shortNpub(s.userPub)}")
        val wraps = Relays.queryAll(JSONObject().put("kinds", JSONArray().put(1059)).put("#p", JSONArray().put(s.userPub)))
        log("signer: ${wraps.size} gift wrap(s) addressed to the main key on the relays")
        val found = SealedRoot.find(s, wraps.map { it.first }) { log(it) }
        val root = if (found != null) { log("account: sealed root found → same Sukrara account restored"); found }
        else {
            val r = Nostr.random(32).let { Derive.subkey(it, "root") }
            val wrap = SealedRoot.create(s, r)
            val res = Relays.publishAll(wrap)
            log("account: new root sealed to the main key, wrap ${wrap.id().toHex().take(8)} on ${res.count { it.value.first }}/${res.size} relays")
            r
        }
        newAccount(root, "sealed", s.userPub, profileMode) { put("signerPkg", s.pkg); put("signerCalls", JSONArray(s.calls)) }
        if (profileMode == "own") publishProfileLocked(name, avatar, null)
        else { log("profile: your Nostr profile, shown only to people you approve (no new kind 0)"); fetchPicture(s.userPub) }
    }

    suspend fun publishProfile(name: String, avatar: Avatar?, photo: Bitmap?) = work("Publishing your profile") {
        publishProfileLocked(name, avatar, photo)
    }

    private suspend fun publishProfileLocked(name: String, avatar: Avatar?, photo: Bitmap?) {
        val p = profileKeys()
        val bmp = photo ?: (avatar ?: Avatar.random()).render(512)
        val (bytes, mime) = Media.encode(bmp, jpeg = photo != null)
        pictures.value = pictures.value + (Nostr.pub(p) to bmp)
        save(s().put("name", name).put("photo", photo != null)
            .put("avatar", avatar?.let { JSONObject().put("palette", it.palette).put("shape", it.shape).put("angle", it.angle).put("seed", it.seed).put("initial", it.initial) } ?: JSONObject.NULL))
        // Image servers can be slow (one took > 30 s while testing): the name goes out at once, the picture follows.
        var pic: Nostr.Pic? = null
        for (attempt in 1..3) {
            val ups = Blossom.uploadAll(p, bytes, mime)
            ups.forEach { log("blossom ${it.server.substringAfter("//")}: ${it.url?.let { "ok" } ?: it.error}") }
            val ok = ups.filter { it.url != null }
            pic = ok.firstOrNull()?.let { Nostr.Pic(it.url!!, mime, Blossom.sha256(bytes), "512x512", ok.drop(1).map { u -> u.url!! }) }
            if (pic != null || attempt == 3) break
            if (attempt == 1) publishKind0(p, name, null, bytes)                     // name now, picture later
            delay(20_000L * attempt)
        }
        publishKind0(p, name, pic, bytes)
    }

    private suspend fun publishKind0(p: Keys, name: String, pic: Nostr.Pic?, bytes: ByteArray) {
        val ev = Nostr.metadata(p, name, pic)
        val res = Relays.publishAll(ev)
        log("kind 0 ${if (pic == null) "(no picture yet) " else ""}by profile key ${Nostr.shortNpub(Nostr.pub(p))}: ${res.count { it.value.first }}/${res.size} relays")
        save(s().put("picture", pic?.let { JSONObject().put("url", it.url).put("mime", it.mime).put("x", it.sha256).put("fallbacks", JSONArray(it.fallbacks)).put("bytes", bytes.size).put("exif", Media.hasExif(bytes)) } ?: JSONObject.NULL)
            .put("kind0", JSONObject().put("id", ev.id().toHex()).put("relays", JSONObject(res.mapValues { it.value.first }))))
    }

    /** Downloads a profile's picture and checks the bytes against the hash in the Blossom URL. */
    suspend fun fetchPicture(pub: String): Nostr.Profile? {
        val prof = Relays.latestProfile(pub) ?: return null
        val urls = listOfNotNull(prof.picture) + prof.fallbacks
        Blossom.fetchVerified(urls)?.let { (b, _) -> Media.decode(b)?.let { pictures.value = pictures.value + (pub to it) } }
        return prof
    }

    // ----------------------------------------------------------------------------------------------- sharing

    suspend fun createInvite(): String = work("Creating an invite") {
        val secret = Nostr.random(16)
        val expiry = Nostr.now() + Nostr.DAY
        val wrap = Nostr.inviteWrap(root(), secret, expiry, profilePub(), JSONObject().put("name", s().optString("name")))
        val res = Relays.publishAll(wrap)
        val link = Derive.shortLink(Nostr.pub(root()), secret)
        log("invite: wrap on ${res.count { it.value.first }}/${res.size} relays · ${link.length}-char link")
        val j = s()
        val a = j.optJSONArray("invites") ?: JSONArray()
        a.put(JSONObject().put("secret", Derive.hex(secret)).put("token", Derive.b64u(secret)).put("expiry", expiry).put("link", link))
        save(j.put("invites", a).put("lastInvite", link))
        link
    }

    /** Follower: resolve the link via the relays, show who's inviting, then ask. */
    suspend fun join(link: String, myName: String) = work("Opening the invite") {
        val p = Derive.parse(link) ?: error("not a Sukrara invite link")
        val k = Nostr.keys(Derive.inviteKey(p.secret))
        val found = Relays.queryAll(JSONObject().put("kinds", JSONArray().put(1059)).put("#p", JSONArray().put(Nostr.pub(k))), auth = k)
        log("join: ${found.size} wrap(s) for this link via ${found.map { it.second.substringAfter("//") }.distinct()}")
        val inv = Nostr.openInvite(p, found.map { it.first }).getOrThrow()
        val prof = inv.profile?.let { fetchPicture(it) }
        val wearerName = prof?.name ?: inv.name ?: "The wearer"
        val me = root()
        val body = JSONObject().put("v", 2).put("type", "request").put("token", inv.token).put("name", myName)
            .put("profile", profilePub())
        val res = Relays.publishAll(Nostr.gift(me, inv.iPub, Nostr.RUMOR_REQUEST, body, Nostr.now() + 7 * Nostr.DAY), inv.relays.ifEmpty { Nostr.RELAYS })
        val code = Nostr.matchCode(Nostr.pub(me), inv.token)
        log("join: asked $wearerName (code $code) on ${res.count { it.value.first }}/${res.size} relays")
        val j = s()
        val a = j.optJSONArray("following") ?: JSONArray()
        a.put(JSONObject().put("i", inv.iPub).put("profile", inv.profile ?: JSONObject.NULL).put("name", wearerName)
            .put("code", code).put("status", "waiting").put("since", Nostr.now()))
        save(j.put("following", a))
    }

    /** Both roles: read gift wraps addressed to our identity (requests for the wearer, approvals for the follower). */
    suspend fun poll() = work("Checking for news") {
        val me = root()
        val wraps = Relays.queryAll(JSONObject().put("kinds", JSONArray().put(1059)).put("#p", JSONArray().put(Nostr.pub(me)))
            .put("since", Nostr.now() - 4 * Nostr.DAY), auth = me)
        val j = s()
        val tokens = (j.optJSONArray("invites") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.getLong("expiry") > Nostr.now() }.associateBy { it.getString("token") }
        val reqs = j.optJSONArray("requests") ?: JSONArray()
        val known = (0 until reqs.length()).map { reqs.getJSONObject(it).getString("f") }.toMutableSet()
        val following = j.optJSONArray("following") ?: JSONArray()
        for ((w, via) in wraps) {
            val u = Nostr.unwrap(me, w) ?: continue
            when (u.body.optString("type")) {
                "request" -> if (u.kind == Nostr.RUMOR_REQUEST && u.sender !in known && tokens.containsKey(u.body.optString("token"))) {
                    known += u.sender
                    val token = u.body.getString("token")
                    reqs.put(JSONObject().put("f", u.sender).put("profile", u.body.optString("profile")).put("name", u.body.optString("name"))
                        .put("token", token).put("code", Nostr.matchCode(u.sender, token)).put("status", "pending").put("via", via))
                    log("request from ${u.body.optString("name")} (code ${Nostr.matchCode(u.sender, token)}) via ${via.substringAfter("//")}")
                }
                "approved" -> for (i in 0 until following.length()) {
                    val f = following.getJSONObject(i)
                    if (f.getString("i") == u.sender && f.getString("status") != "approved") {
                        f.put("status", "approved").put("profile", u.body.optString("profile"))
                        u.body.optString("name").takeIf { it.isNotBlank() }?.let { f.put("name", it) }
                        log("approved by ${f.getString("name")} via ${via.substringAfter("//")} (sealed by its identity)")
                    }
                }
            }
        }
        save(j.put("requests", reqs).put("following", following).put("lastPoll", Nostr.now()))
        // profiles: the requester's kind 0 (name + picture) — fetched by the key it told us, privately
        for (i in 0 until reqs.length()) reqs.getJSONObject(i).optString("profile").takeIf { it.length == 64 }?.let { p ->
            if (p !in pictures.value) fetchPicture(p)?.let { prof -> updateRequestName(p, prof.name) }
        }
        for (i in 0 until following.length()) following.getJSONObject(i).optString("profile").takeIf { it.length == 64 }?.let { p ->
            if (p !in pictures.value) fetchPicture(p)
        }
    }

    private fun updateRequestName(profile: String, name: String) {
        val j = s()
        val reqs = j.optJSONArray("requests") ?: return
        for (i in 0 until reqs.length()) reqs.getJSONObject(i).takeIf { it.optString("profile") == profile }?.put("profileName", name)
        save(j.put("requests", reqs))
    }

    /** Wearer taps Allow: tell the follower (in Sukrara this rides in the PROTOCOL-v2 key delivery). */
    suspend fun decide(f: String, allow: Boolean) = work(if (allow) "Allowing" else "Denying") {
        val j = s()
        val reqs = j.getJSONArray("requests")
        for (i in 0 until reqs.length()) {
            val r = reqs.getJSONObject(i)
            if (r.getString("f") != f) continue
            r.put("status", if (allow) "approved" else "denied")
            if (allow) {
                val body = JSONObject().put("v", 3).put("type", "approved").put("profile", profilePub()).put("name", j.optString("name"))
                val res = Relays.publishAll(Nostr.gift(root(), f, Nostr.RUMOR_TO_FOLLOWER, body, Nostr.now() + 7 * Nostr.DAY))
                log("allowed ${r.optString("profileName", r.optString("name"))}: delivery on ${res.count { it.value.first }}/${res.size} relays")
            }
        }
        save(j.put("requests", reqs))
    }

    /** Pictures of everyone on screen (in-memory cache; refilled after a restart). */
    suspend fun fetchAllPictures() {
        val j = _state.value
        val pubs = listOfNotNull(profilePub()) + listOf("requests", "following").flatMap { k ->
            (j.optJSONArray(k) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("profile") } }
        }
        pubs.filter { it.length == 64 && it !in pictures.value }.distinct().forEach { runCatching { fetchPicture(it) } }
    }

    /**
     * "Delete account everywhere": BUD-12 DELETE of the picture on every server, then a NIP-62 request to vanish for
     * the profile key and the identity (relays SHOULD also drop gift wraps addressed to it). Best effort — the live
     * check found most relays accept the request but keep serving the events — so the UI never promises erasure.
     */
    suspend fun deleteEverywhere() = work("Deleting your account") {
        val j = _state.value
        val p = profileKeys()
        j.optJSONObject("picture")?.let { pic ->
            val x = pic.getString("x")
            val urls = listOf(pic.getString("url")) + Nostr.strings(pic.optJSONArray("fallbacks"))
            urls.forEach { u -> val server = u.substringBefore("/" + x).let { s -> Regex("^(https://[^/]+)").find(s)!!.value }
                log("blossom delete ${server.substringAfter("//")}: ${Blossom.delete(p, server, x)}") }
        }
        // Overwrite first: kind 0 is replaceable, and in the live check an empty kind 0 removed the old name from
        // 6/7 relays, while NIP-09 left it on 3/7 and NIP-62 was honoured by one relay only.
        if (j.optString("profileMode") != "nostr") {
            val res = Relays.publishAll(Nostr.metadata(p, "", null))
            log("kind 0 overwritten with an empty profile on ${res.count { it.value.first }}/${res.size}")
        }
        val keys = listOf("profile" to p, "identity" to root()).filter { !(it.first == "profile" && j.optString("profileMode") == "nostr") }
        for ((label, k) in keys) {
            val ev = org.nostrdevkit.sdk.EventBuilder(org.nostrdevkit.sdk.Kind(62u), "")
                .tags(Nostr.RELAYS.map { Nostr.tag("relay", it) }).finalize(k)
            val res = Relays.publishAll(ev, auth = k)
            log("NIP-62 vanish ($label): accepted by ${res.count { it.value.first }}/${res.size}")
        }
    }

    fun reset() {
        store.wipe()
        File(ctx.filesDir, "state.json").delete()
        _state.value = JSONObject()
        pictures.value = emptyMap()
    }

    fun launch(label: String, f: suspend () -> Unit) = scope.launch { runCatching { f() }.onFailure { Log.w(TAG, label, it) } }
}
