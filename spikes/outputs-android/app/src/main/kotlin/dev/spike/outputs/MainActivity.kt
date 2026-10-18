// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.outputs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Buttons for each output plus a 1/min loop, all with [Synthetic] readings.
 * adb: `am start -n dev.spike.outputs/.MainActivity --ez send true` (one reading to every receiver; add
 * `--es only xdrip|libre|glucodata` for a single broadcast),
 * `--ez loop true` (every minute while open), `--ez hc true` (last 3 h to Health Connect).
 */
class MainActivity : Activity() {
    private lateinit var emitter: Emitter
    private lateinit var health: HealthWriter
    private lateinit var log: TextView
    private val main = Handler(Looper.getMainLooper())
    private var looping = false
    private var activated = false
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private val tick = object : Runnable {
        override fun run() {
            sendNow()
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        emitter = Emitter(this)
        health = HealthWriter(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 120, 32, 32) }
        fun button(label: String, onClick: () -> Unit) =
            col.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } })
        col.addView(TextView(this).apply { text = "Outputs spike (synthetic readings only)"; textSize = 20f })
        button("Send one reading to every receiver") { sendNow() }
        button("Every minute: start / stop") { toggleLoop() }
        button("Health Connect: allow writing glucose") { requestPermissions(arrayOf(health.permission), 1) }
        button("Health Connect: write the last 3 h") { writeHealth() }
        button("Who listens?") { listReceivers() }
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        col.addView(log)
        setContentView(ScrollView(this).apply {
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        listReceivers()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        if (i.getBooleanExtra("send", false)) sendNow(i.getStringExtra("only"))
        if (i.getBooleanExtra("loop", false) && !looping) toggleLoop()
        if (i.getBooleanExtra("hc", false)) writeHealth()
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i("Outputs", line)
        log.text = "${fmt.format(Date())}  $line\n${log.text}".take(6000)
    }

    private fun sendNow(only: String? = null) {
        val r = Synthetic.at(System.currentTimeMillis())
        val sent = when (only) {
            "xdrip" -> listOf(emitter.send(Payloads.XDRIP_ACTION, Payloads.xdrip(r)))
            "libre" -> listOf(emitter.send(Payloads.LIBRE_ACTIVATE_ACTION, Payloads.libreActivate(r)),
                emitter.send(Payloads.LIBRE_ACTION, Payloads.libre(r)))
            "glucodata" -> listOf(emitter.send(Payloads.GLUCODATA_ACTION, Payloads.glucodata(r)))
            else -> emitter.all(r, newSensor = !activated).also { activated = true }
        }
        say("${r.mgdl} mg/dL ${Payloads.trendName(r.rate)} (${"%.2f".format(r.rate)}/min) → " +
            sent.joinToString("; ") { "${it.action.substringAfterLast('.')}: ${it.packages.size}" })
    }

    private fun toggleLoop() {
        looping = !looping
        if (looping) main.post(tick) else main.removeCallbacks(tick)
        say(if (looping) "loop on (every minute)" else "loop off")
    }

    private fun writeHealth() {
        if (!health.granted()) { say("Health Connect: permission not granted yet"); return }
        val now = System.currentTimeMillis()
        val readings = (0 until 180 step 5).map { Synthetic.at(now - it * 60_000L) }
        health.write(readings) { say(it) }
    }

    private fun listReceivers() {
        for (a in listOf(Payloads.XDRIP_ACTION, Payloads.LIBRE_ACTION, Payloads.GLUCODATA_ACTION))
            say("$a ← ${emitter.receivers(a).ifEmpty { listOf("nobody") }.joinToString()}")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        say("Health Connect permission: ${if (health.granted()) "granted" else "denied"}")
    }
}
