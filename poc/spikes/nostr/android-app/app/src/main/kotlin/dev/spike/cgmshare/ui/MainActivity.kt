// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
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
import dev.spike.cgmshare.App
import dev.spike.cgmshare.follower.FollowerService
import dev.spike.cgmshare.owner.OwnerService
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
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Root()
                    }
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
        val link = i?.dataString?.takeIf { it.startsWith("cgmshare:join?") } ?: return
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
        val clip = ClipData.newPlainText("CGM Share", text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    @Composable
    private fun Root() {
        when (role) {
            null -> RoleChoice(
                onOwner = { App.setRole(this, "owner"); role = "owner" },
                onFollower = { App.setRole(this, "follower"); role = "follower" },
            )
            "owner" -> OwnerRoot()
            "follower" -> FollowerRoot()
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
                override fun create() {
                    lifecycleScope.launch {
                        engine.createIdentity()
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
                            restored("No saved CGM Share identity in your password managers"); return@launch
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
                        .putExtra(Intent.EXTRA_TEXT, "Follow my glucose with CGM Share: $link")
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
            }
        }

        if (!u.hasIdentity) {
            OwnerOnboarding(busy ?: u.busy, error, actions)
            return
        }
        BackHandler(enabled = screen != "home") { screen = "home" }
        when (screen) {
            "share" -> ShareScreen(u, actions) { screen = "home" }
            else -> OwnerHome(u, relays, log, actions)
        }
        ApproveDialog(u, actions)
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

        val actions = remember {
            object : FollowerActions {
                override fun join(link: String, name: String) {
                    lifecycleScope.launch {
                        busy = true
                        error = engine.join(link, name)
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
                override fun scanNearby(onFound: (dev.spike.cgmshare.core.NearbyOwner) -> Unit) = engine.scanNearby(onFound)
                override fun joinNearby(address: String, name: String) {
                    lifecycleScope.launch {
                        busy = true
                        error = engine.joinNearby(address, name)
                        busy = false
                        if (error == null) startForegroundService(Intent(this@MainActivity, FollowerService::class.java))
                    }
                }
                override fun addTreatment(k: String, a: Double, note: String) { engine.addTreatment(k, a, note) }
            }
        }
        FollowerRefresh { engine.refreshUi() }
        when (u.status) {
            "none" -> FollowerJoin(deepLink, busy, error, actions)
            "requested" -> FollowerWaiting(u, actions)
            else -> FollowerHome(u, relays, log, actions)
        }
    }
}
