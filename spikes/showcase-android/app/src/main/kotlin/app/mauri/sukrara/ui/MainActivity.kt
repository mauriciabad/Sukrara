// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.Manifest
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
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
    companion object {
        /** Opens the Sensor screen (scan a new sensor): the "Replace sensor" pill, its notification action, widget taps. */
        fun sensorIntent(c: android.content.Context): android.content.Intent =
            android.content.Intent(c, MainActivity::class.java).putExtra("screen", "sensor")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun sensorPendingIntent(c: android.content.Context): android.app.PendingIntent = android.app.PendingIntent.getActivity(c, 7,
            sensorIntent(c), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private var role by mutableStateOf<String?>(null)
    private var deepLink by mutableStateOf<String?>(null)
    /** A screen a notification asked for (alert notifications open "alerts"). */
    private var openScreen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        role = App.role(this)
        takeLink(intent)
        openScreen = intent?.getStringExtra("screen")
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
        intent.getStringExtra("screen")?.let { openScreen = it }
    }

    private fun takeLink(i: Intent?) {
        // the App Link, or sukrara:join#… from the web page's "Open in Sukrara" button: both become the https link
        val link = i?.dataString?.let { app.mauri.sukrara.core.Derive.parse(it) }?.let {
            app.mauri.sukrara.core.Derive.INVITE_BASE + i.dataString!!.substringAfter('#')
        } ?: return
        when (role) {
            null -> { App.setRole(this, "follower"); role = "follower"; deepLink = link }
            "follower" -> deepLink = link
            else -> Toast.makeText(this, R.string.toast_link_on_wearer_phone, Toast.LENGTH_LONG).show()
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
        val nav = rememberNav("home")
        LaunchedEffect(openScreen) { openScreen?.let { nav.reset(it); openScreen = null } }
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
                        toast(getString(R.string.toast_identity_restored))
                    }
                }

                override fun restoreFromPasswordManager() {
                    lifecycleScope.launch {
                        error = null
                        busy = getString(R.string.onboarding_busy_asking_password_manager)
                        val secret = try {
                            val res = CredentialManager.create(this@MainActivity)
                                .getCredential(this@MainActivity, GetCredentialRequest(listOf(GetPasswordOption())))
                            (res.credential as? PasswordCredential)?.password
                        } catch (e: NoCredentialException) {
                            restored(getString(R.string.onboarding_error_no_saved_identity)); return@launch
                        } catch (e: GetCredentialCancellationException) {
                            restored(getString(R.string.onboarding_error_cancelled)); return@launch
                        } catch (e: GetCredentialException) {
                            restored("${e.type}: ${e.message}"); return@launch
                        }
                        if (secret == null) { restored(getString(R.string.onboarding_error_not_password)); return@launch }
                        busy = getString(R.string.onboarding_busy_restoring)
                        restored(engine.restore(secret, null))
                    }
                }

                override fun restorePasted(secret: String, password: String?) {
                    lifecycleScope.launch {
                        error = null
                        busy = getString(R.string.onboarding_busy_restoring)
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
                            toast(getString(R.string.toast_backup_saved))
                        } catch (e: CreateCredentialCancellationException) {
                            toast(getString(R.string.toast_backup_cancelled))
                        } catch (e: CreateCredentialException) {
                            engine.log.i("Credential Manager save failed: ${e.type}")
                            toast(getString(R.string.toast_backup_failed, e.type))
                        }
                    }
                }

                override fun share() { engine.newInvite(); nav.open("share") }

                override fun shareLink(link: String) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_invite_message, link))
                    startActivity(Intent.createChooser(send, getString(R.string.share_invite_chooser)))
                }

                override fun copy(text: String) { this@MainActivity.copy(text, sensitive = text.startsWith("ncryptsec")); toast(getString(R.string.toast_copied)) }
                override fun approve(f: String) { engine.approve(f) }
                override fun deny(f: String) { engine.deny(f) }
                override fun revoke(f: String) { engine.revoke(f) }
                override fun setSynthetic(on: Boolean) { engine.setSynthetic(on) }
                override fun checkNow() { lifecycleScope.launch { engine.checkInbox("manual") } }
                override suspend fun ncryptsec(password: String) = engine.identityNcryptsec(password)
                override fun nearbyPairing() { engine.nearbyPairing() }
                override fun setWriter(f: String, on: Boolean) { engine.setWriter(f, on) }
                override fun open(s: String) { nav.open(s) }
                override fun saveProfile(profile: app.mauri.sukrara.core.Profile) { engine.setProfile(profile) }
                override fun setInviteLabel(label: String) { engine.setInviteLabel(label) }
            }
        }

        // the home screen keeps its scroll position while a child screen is open
        val homeScroll = rememberScrollState()
        val index = remember { SettingsIndex("settings") }
        // every screen by route: drawn by the screen host, and composed in index mode by settings search
        @Composable
        fun page(s: String) {
            val back = nav::back
            when (s) {
                "share" -> SettingsRoute { ShareScreen(u, actions, back) }
                "profile" -> SettingsRoute {
                    ProfileScreen(stringResource(R.string.profile_title_wearer), u.profile ?: app.mauri.sukrara.core.Profile("", null), stringResource(R.string.profile_name_label),
                        stringResource(R.string.profile_note_wearer),
                        actions::saveProfile, back)
                }
                "settings" -> SettingsRoute { WearerSettings(u, back) }
                "alerts" -> SettingsRoute { AlertsScreen(follower = false, back) }
                "source" -> SettingsRoute { SourceSettings(back) }
                "sensor" -> SettingsRoute { SensorScreen(back) }
                "sensorend" -> SettingsRoute { SensorEndSettings(back) }
                "sharing" -> SettingsRoute { SharingSettings(u, actions, back) }
                "graph" -> SettingsRoute { GraphScreen(back) }
                "statusbar" -> SettingsRoute { StatusBarSettings(back) }
                "lockscreen" -> SettingsRoute { LockscreenSettings(back) }
                "apps" -> SettingsRoute { AppsSettings(wearer = true, back) }
                "libreview" -> SettingsRoute { LibreViewSettings(back) }
                "backup" -> SettingsRoute { BackupSettings(u, actions, back) }
                "advanced" -> SettingsRoute { WearerAdvanced(u, relays, actions, back) }
                "log" -> SettingsRoute { LogPage(log, back) }
                "about" -> SettingsRoute { AboutSettings(back) }
                else -> if (s.startsWith(ALERT_ROUTE)) SettingsRoute { AlertKindScreen(s.removePrefix(ALERT_ROUTE), follower = false, back) }
                    else Page(homeScroll) { OwnerHome(u, actions) }
            }
        }
        AnimatedContent(u.hasIdentity, transitionSpec = { fadeThrough() }, label = "identity") { has ->
            if (!has) Page { OwnerOnboarding(busy ?: u.busy, error, actions) }
            else CompositionLocalProvider(LocalNav provides nav, LocalSettingsSearch provides SettingsSearch(index) { page(it) }) {
                ScreenHost(nav) { page(it) }
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
        val nav = rememberNav("home")
        LaunchedEffect(openScreen) { openScreen?.let { nav.reset(it); openScreen = null } }

        val actions = remember {
            object : FollowerActions {
                override suspend fun resolve(link: String) = engine.resolveInvite(link)
                override fun editProfile() { nav.open("profile") }
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

                override fun open(screen: String) { nav.open(screen) }
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
        val step = when (u.status) { "none", "requested", "used", "declined" -> u.status; else -> "home" }
        val homeScroll = rememberScrollState()
        val index = remember { SettingsIndex("settings") }
        @Composable
        fun followerPage(sc: String) {
            val back = nav::back
            when (sc) {
                "settings" -> SettingsRoute { FollowerSettings(u, back) }
                "alerts" -> SettingsRoute { AlertsScreen(follower = true, back) }
                "graph" -> SettingsRoute { GraphScreen(back) }
                "following" -> SettingsRoute { FollowingSettings(u, actions, back) }
                "apps" -> SettingsRoute { AppsSettings(wearer = false, back) }
                "advanced" -> SettingsRoute { FollowerAdvanced(u, relays, actions, back) }
                "log" -> SettingsRoute { LogPage(log, back) }
                "about" -> SettingsRoute { AboutSettings(back) }
                "profile" -> SettingsRoute {
                    ProfileScreen(stringResource(R.string.profile_title_follower), u.me ?: app.mauri.sukrara.core.Profile(u.name ?: "", null),
                        stringResource(R.string.profile_name_label), stringResource(R.string.profile_note_follower), engine::setProfile, back)
                }
                else -> if (sc.startsWith(ALERT_ROUTE)) SettingsRoute { AlertKindScreen(sc.removePrefix(ALERT_ROUTE), follower = true, back) }
                    else Page(homeScroll) { FollowerHome(u, actions) }
            }
        }
        AnimatedContent(step, transitionSpec = { fadeThrough() }, label = "follower") { s ->
            when (s) {
                "none" -> Page { FollowerJoin(deepLink, busy, error, actions) }
                "requested" -> Page { FollowerWaiting(u, actions) }
                "used", "declined" -> Page { FollowerEnded(u, actions) }
                else -> CompositionLocalProvider(LocalNav provides nav, LocalSettingsSearch provides SettingsSearch(index) { followerPage(it) }) {
                    ScreenHost(nav) { followerPage(it) }
                }
            }
        }
    }
}
