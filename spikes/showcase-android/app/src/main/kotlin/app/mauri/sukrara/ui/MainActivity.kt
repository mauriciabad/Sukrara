// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.App
import app.mauri.sukrara.follower.FollowerService
import app.mauri.sukrara.owner.OwnerService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var role by mutableStateOf<String?>(null)
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        role = App.role(this)
        takeLink(intent)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN), 1)
        startServiceIfReady()
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                // every screen is a Page (own scroll + safe-area padding) so screens can animate as a whole (Motion.kt)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (role == "owner" && App.owner(this).hasIdentity()) App.owner(this).retryBle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
    }

    private fun takeLink(i: Intent?) {
        // the App Link, or sukrara:join#… from the web page's "Open in Sukrara" button: both become the https link
        val link = i?.dataString?.let { app.mauri.sukrara.core.Derive.parse(it) }?.let {
            app.mauri.sukrara.core.Derive.INVITE_BASE + i.dataString!!.substringAfter('#')
        } ?: return
        when (role) {
            null -> { App.setRole(this, "follower"); role = "follower"; deepLink = link }
            "follower" -> deepLink = link
            else -> Toast.makeText(this, "This phone is set up as the sensor owner; open the link on the follower's phone.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        when (role) {
            "owner" -> App.owner(this).let { if (it.hasIdentity()) it.setForeground(true) }
            "follower" -> App.follower(this).refreshUi()
        }
    }

    override fun onStop() {
        if (role == "owner") App.owner(this).setForeground(false)
        super.onStop()
    }

    private fun startServiceIfReady() {
        when (role) {
            "owner" -> if (App.owner(this).hasIdentity()) startForegroundService(Intent(this, OwnerService::class.java))
            "follower" -> if (App.follower(this).hasJoined()) startForegroundService(Intent(this, FollowerService::class.java))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun copy(text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText("Sukrara", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    @Composable
    private fun Root() {
        AnimatedContent(role, transitionSpec = { fadeThrough() }, label = "role") { r ->
            when (r) {
                null -> Page {
                    RoleChoice(
                        onOwner = { App.setRole(this@MainActivity, "owner"); role = "owner" },
                        onFollower = { App.setRole(this@MainActivity, "follower"); role = "follower" },
                    )
                }
                "owner" -> OwnerRoot()
                "follower" -> FollowerRoot()
            }
        }
    }

    // ================================================================================================ owner

    @Composable
    private fun OwnerRoot() {
        val engine = remember { App.owner(this) }
        val u by engine.ui.collectAsStateWithLifecycle()
        val relays by engine.relayStats.collectAsStateWithLifecycle()
        val log by engine.log.lines.collectAsStateWithLifecycle()
        var screen by remember { mutableStateOf("home") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf<String?>(null) }

        val actions = remember {
            object : OwnerActions {
                override fun create(profile: app.mauri.sukrara.core.Profile) {
                    lifecycleScope.launch {
                        engine.createIdentity(profile)
                        startForegroundService(Intent(this@MainActivity, OwnerService::class.java))
                        engine.setForeground(true)
                    }
                }

                private fun restored(err: String?) {
                    busy = null
                    error = err
                    if (err == null) {
                        startForegroundService(Intent(this@MainActivity, OwnerService::class.java))
                        engine.setForeground(true)
                        toast("Identity restored — your followers continue automatically")
                    }
                }

                override fun restoreFromPasswordManager() {
                    lifecycleScope.launch {
                        error = null
                        busy = "Asking your password manager…"
                        val secret = try {
                            val res = CredentialManager.create(this@MainActivity)
                                .getCredential(this@MainActivity, GetCredentialRequest(listOf(GetPasswordOption())))
                            (res.credential as? PasswordCredential)?.password
                        } catch (e: NoCredentialException) {
                            restored("No saved Sukrara identity in your password managers"); return@launch
                        } catch (e: GetCredentialCancellationException) {
                            restored("Cancelled"); return@launch
                        } catch (e: GetCredentialException) {
                            restored("${e.type}: ${e.message}"); return@launch
                        }
                        if (secret == null) { restored("Not a password credential"); return@launch }
                        busy = "Restoring…"
                        restored(engine.restore(secret, null))
                    }
                }

                override fun restorePasted(secret: String, password: String?) {
                    lifecycleScope.launch {
                        error = null
                        busy = "Restoring…"
                        restored(engine.restore(secret, password))
                    }
                }

                override fun saveBackup() {
                    lifecycleScope.launch {
                        val nsec = engine.identityNsec() ?: return@launch
                        try {
                            CredentialManager.create(this@MainActivity)
                                .createCredential(this@MainActivity, CreatePasswordRequest(engine.credentialId(), nsec))
                            engine.markBackupSaved()
                            toast("Saved to your password manager")
                        } catch (e: CreateCredentialCancellationException) {
                            toast("Not saved (cancelled)")
                        } catch (e: CreateCredentialException) {
                            engine.log.i("Credential Manager save failed: ${e.type}")
                            toast("Could not save: ${e.type}")
                        }
                    }
                }

                override fun share() { engine.newInvite(); screen = "share" }

                override fun shareLink(link: String) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Follow my glucose with Sukrara: $link")
                    startActivity(Intent.createChooser(send, "Send invite"))
                }

                override fun copy(text: String) { this@MainActivity.copy(text, sensitive = text.startsWith("ncryptsec")); toast("Copied") }
                override fun approve(f: String) { engine.approve(f) }
                override fun deny(f: String) { engine.deny(f) }
                override fun revoke(f: String) { engine.revoke(f) }
                override fun setSynthetic(on: Boolean) { engine.setSynthetic(on) }
                override fun checkNow() { lifecycleScope.launch { engine.checkInbox("manual") } }
                override suspend fun ncryptsec(password: String) = engine.identityNcryptsec(password)
                override fun nearbyPairing() { engine.nearbyPairing() }
                override fun setWriter(f: String, on: Boolean) { engine.setWriter(f, on) }
                override fun open(s: String) { screen = s }
                override fun saveProfile(profile: app.mauri.sukrara.core.Profile) { engine.setProfile(profile) }
            }
        }

        // the home screen keeps its scroll position while a child screen is open
        val homeScroll = rememberScrollState()
        AnimatedContent(u.hasIdentity, transitionSpec = { fadeThrough() }, label = "identity") { has ->
            if (!has) Page { OwnerOnboarding(busy ?: u.busy, error, actions) }
            else ScreenHost(screen, home = "home", onBack = { screen = "home" }) { s ->
                when (s) {
                    "share" -> Page { ShareScreen(u, actions) { screen = "home" } }
                    "profile" -> Page {
                        ProfileScreen("Your profile", u.profile ?: app.mauri.sukrara.core.Profile("", null), "Your name",
                            "Your followers get the change at once. New invites show it to the person you invite.",
                            actions::saveProfile) { screen = "home" }
                    }
                    "settings" -> Page { SettingsScreen(onSensor = { screen = "sensor" }) { screen = "home" } }
                    "sensor" -> Page { SensorScreen { screen = "home" } }
                    else -> Page(homeScroll) { OwnerHome(u, relays, log, actions) }
                }
            }
        }
        if (u.hasIdentity) ApproveDialog(u, actions)
    }

    // ================================================================================================ follower

    @Composable
    private fun FollowerRoot() {
        val engine = remember { App.follower(this) }
        val u by engine.ui.collectAsStateWithLifecycle()
        val relays by engine.relayStats.collectAsStateWithLifecycle()
        val log by engine.log.lines.collectAsStateWithLifecycle()
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var screen by remember { mutableStateOf("home") }

        val actions = remember {
            object : FollowerActions {
                override suspend fun resolve(link: String) = engine.resolveInvite(link)
                override fun editProfile() { screen = "profile" }
                override fun join(inv: app.mauri.sukrara.core.Invite, me: app.mauri.sukrara.core.Profile) {
                    lifecycleScope.launch {
                        busy = true
                        error = engine.join(inv, me)
                        busy = false
                        if (error == null) {
                            deepLink = null
                            startForegroundService(Intent(this@MainActivity, FollowerService::class.java))
                        }
                    }
                }

                override fun leave() {
                    lifecycleScope.launch {
                        stopService(Intent(this@MainActivity, FollowerService::class.java))
                        engine.leave()
                    }
                }

                override fun setAlarms(low: Int, high: Int, on: Boolean) { engine.setAlarms(low, high, on) }
                override fun setTestUnfiltered(on: Boolean) { engine.setTestUnfiltered(on) }
                override fun scanNearby(onFound: (app.mauri.sukrara.core.NearbyOwner) -> Unit) = engine.scanNearby(onFound)
                override fun joinNearby(address: String, me: app.mauri.sukrara.core.Profile) {
                    lifecycleScope.launch {
                        busy = true
                        error = engine.joinNearby(address, me)
                        busy = false
                        if (error == null) startForegroundService(Intent(this@MainActivity, FollowerService::class.java))
                    }
                }
                override fun addTreatment(k: String, a: Double, note: String, at: Long) { engine.addTreatment(k, a, note, at) }
            }
        }
        FollowerRefresh { engine.refreshUi() }
        val step = when (u.status) { "none", "requested" -> u.status; else -> if (screen == "profile") "profile" else "home" }
        AnimatedContent(step, transitionSpec = { fadeThrough() }, label = "follower") { s ->
            Page {
                when (s) {
                    "none" -> FollowerJoin(deepLink, busy, error, actions)
                    "requested" -> FollowerWaiting(u, actions)
                    "profile" -> ProfileScreen("How you appear", u.me ?: app.mauri.sukrara.core.Profile(u.name ?: "", null),
                        "Your name", "The person you follow sees the change as soon as it reaches them.", engine::setProfile) { screen = "home" }
                    else -> FollowerHome(u, relays, log, actions)
                }
            }
        }
    }
}
