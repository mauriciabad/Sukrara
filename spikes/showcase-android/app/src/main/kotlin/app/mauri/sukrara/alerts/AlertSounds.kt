// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Words
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings

/**
 * Sound packs (spikes/alerts/sounds, `res/raw/<pack>_<kind>_<n>.ogg`) and how they reach the native channels.
 *
 * Each alert channel's sound is `content://<package>.alertsound/<kind>`, served by [AlertSoundProvider]. A channel's
 * sound can't be changed by the app once the channel exists (developer.android.com/develop/ui/views/notifications/
 * channels: "Only the user can change the channel behaviors from the system settings"), but SystemUI's
 * NotificationPlayer makes a new MediaPlayer for every alert, and `MediaPlayer.setDataSource(context, uri)` opens the
 * URI through the ContentResolver each time (AOSP, docs/15). So the provider decides at play time: the pack chosen
 * in the app, a random variant, the system default sound, or silence. A user who picks another sound in the
 * channel's system settings overrides all of this, as with any app.
 */
object AlertSounds {
    val PACKS = listOf("arcade", "kart", "quest", "soft")
    const val SYSTEM = "system"
    const val NONE = "none"
    const val VARIANTS = 3

    fun label(p: String) = when (p) {
        "arcade" -> "Arcade (8-bit)"; "kart" -> "Kart (racing)"; "quest" -> "Quest (adventure)"; "soft" -> "Soft (calm)"
        SYSTEM -> "System default"; else -> "None"
    }

    fun uri(ctx: Context, kind: String): Uri = Uri.parse("content://${ctx.packageName}.alertsound/$kind")

    private fun raw(ctx: Context, name: String): Int = ctx.resources.getIdentifier(name, "raw", ctx.packageName)

    // A notification opens the URI more than once (the haptic-channel probe, then playback): keep one pick per kind
    // for a few seconds so both see the same file, and don't repeat the previous variant.
    private class Pick(val name: String, val atMs: Long)
    private val picks = HashMap<String, Pick>()
    private val lastVariant = HashMap<String, Int>()

    /** The raw resource name to play for [kind] now, or [SYSTEM] / "silence". */
    @Synchronized fun pick(kind: String, s: AlertSettings, nowMs: Long = System.currentTimeMillis()): String {
        picks[kind]?.let { if (nowMs - it.atMs < 5_000) return it.name }
        val pack = s.soundOf(kind)
        val name = when (pack) {
            SYSTEM -> SYSTEM
            !in PACKS -> "silence"
            else -> {
                val n = if (!s.shuffle) 1 else (1..VARIANTS).filter { it != lastVariant[kind] }.random()
                lastVariant[kind] = n
                "${pack}_${kind}_$n"
            }
        }
        picks[kind] = Pick(name, nowMs)
        return name
    }

    fun open(ctx: Context, name: String): AssetFileDescriptor? =
        if (name == SYSTEM) ctx.contentResolver.openAssetFileDescriptor(Settings.System.DEFAULT_NOTIFICATION_URI, "r")
        else raw(ctx, name).takeIf { it != 0 }?.let { ctx.resources.openRawResourceFd(it) }

    // ------------------------------------------------------------------------------------------------ preview

    private var player: MediaPlayer? = null

    /** Settings' ▶ button: one variant of [pack] for [kind] (or the system sound), on the stream it would ring on. */
    fun preview(ctx: Context, kind: String, pack: String, alarm: Boolean, variant: Int? = null) {
        stopPreview()
        val name = when (pack) {
            SYSTEM -> SYSTEM
            in PACKS -> "${pack}_${kind}_${variant ?: (1..VARIANTS).random()}"
            else -> return
        }
        val fd = runCatching { open(ctx, name) }.getOrNull() ?: return
        player = MediaPlayer().apply {
            setAudioAttributes(AlertNotifier.audioAttributes(alarm))
            fd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun stopPreview() { player?.let { runCatching { it.stop(); it.release() } }; player = null }
}

/** Serves `content://<package>.alertsound/<kind>`: exported, read-only, nothing secret (see [AlertSounds]). */
class AlertSoundProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        val kind = uri.lastPathSegment?.takeIf { it in Kind.ALL } ?: return null
        Alerts.init(ctx)
        val name = AlertSounds.pick(kind, Alerts.settings.value)
        android.util.Log.i("Sukrara.Alerts", "sound $kind → $name for ${callingPackage ?: "uid " + android.os.Binder.getCallingUid()}")
        return AlertSounds.open(ctx, name) ?: AlertSounds.open(ctx, "silence")
    }

    override fun getType(uri: Uri) = "audio/ogg"

    // Backup/restore canonicalises channel sounds; the default (null) would reset them to the default sound.
    override fun canonicalize(url: Uri) = url
    override fun uncanonicalize(url: Uri) = url

    /** The name the channel's settings page shows for the sound. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val kind = uri.lastPathSegment ?: ""
        val title = "Sukrara · ${Words.current.kind(kind)}"
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, "title")
        return MatrixCursor(cols).apply {
            addRow(cols.map { c -> when (c) { OpenableColumns.DISPLAY_NAME, "title" -> title; "_id" -> 0; else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
