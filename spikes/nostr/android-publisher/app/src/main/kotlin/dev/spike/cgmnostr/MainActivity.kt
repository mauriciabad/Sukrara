// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.spike.cgmnostr.theme.CGMNostrTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, PublisherService::class.java))
        enableEdgeToEdge()
        setContent {
            CGMNostrTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatusScreen(
                        onStart = { startForegroundService(Intent(this, PublisherService::class.java)) },
                        onStop = { stopService(Intent(this, PublisherService::class.java)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(onStart: () -> Unit, onStop: () -> Unit) {
    // Only while RESUMED: with the screen off the activity can stay "visible"+STARTED, and a 1 Hz recomposition of
    // this screen cost ~3 % CPU on the main thread (measured) — more than the whole publisher.
    val s by Status.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumed) { while (resumed) { nowMs = System.currentTimeMillis(); delay(5000) } }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("CGM Nostr", style = MaterialTheme.typography.headlineSmall)

        Section("Juggluco")
        Text(s.juggluco + (s.jugglucoError?.let { " — $it" } ?: ""))
        s.last?.let { r ->
            Text("${r.sgv} mg/dL", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("rate ${r.rate?.let { "%+.2f".format(it) } ?: "?"} mg/dL/min · reading ${(nowMs / 1000 - r.t)} s old")
        }

        Section("Published")
        Text("${s.liveSent} live · ${s.chunksSent} chunks · ${s.readingsKnown} readings held")
        s.relays.values.sortedBy { it.url }.forEach { r ->
            val paused = if (r.pausedUntilMs > nowMs) " PAUSED ${(r.pausedUntilMs - nowMs) / 1000}s" else ""
            Text(
                "${if (r.connected) "●" else "○"} ${r.url.removePrefix("wss://")}  ok ${r.ok}  fail ${r.fail}  drop ${r.dropped}" +
                    "  conn ${r.connects}  q ${r.queued}${r.lastLiveOkMs?.let { "  live ${it}ms" } ?: ""}$paused",
                style = mono,
            )
            r.lastError?.let { Text("   $it", style = mono, color = MaterialTheme.colorScheme.error) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text("Start") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }

        Section("Follower link")
        s.followerLink?.let { link ->
            val qr = remember(link) { qrBitmap(link) }
            Image(qr.asImageBitmap(), contentDescription = "follower QR", modifier = Modifier.size(260.dp))
            SelectionContainer { Text(link, style = mono) }
        }
        Section("Source key")
        SelectionContainer {
            Column {
                Text(s.npub ?: "…", style = mono)
                Text("NIP-49 (password \"${PublisherService.NIP49_TEST_PASSWORD}\"):", style = MaterialTheme.typography.labelSmall)
                Text(s.ncryptsec ?: "computing…", style = mono)
            }
        }

        Section("Log")
        s.log.forEach { Text(it, style = mono) }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
}

private fun qrBitmap(text: String, px: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px)
    val pixels = IntArray(px * px) { i -> if (m[i % px, i / px]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}
