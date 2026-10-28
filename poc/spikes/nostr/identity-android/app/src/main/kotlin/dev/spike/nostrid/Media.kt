// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.Keys
import org.nostrdevkit.sdk.Kind

/**
 * Generated avatars. A picture is fully described by [Avatar] (a few small numbers), so the app can redraw and edit
 * it; what other people see is the rendered PNG uploaded to Blossom like any photo. Same spec as ident.py.
 */
data class Avatar(val palette: Int, val shape: String, val angle: Int, val seed: Int, val initial: String = "") {
    companion object {
        val PALETTES = listOf(0xFF1BB5C9 to 0xFF0B2E5E, 0xFFC6F432 to 0xFF2E7D32, 0xFFFF8A65 to 0xFFBF360C,
            0xFFBA68C8 to 0xFF4A148C, 0xFFFFD54F to 0xFFE65100, 0xFF4FC3F7 to 0xFF01579B, 0xFFF06292 to 0xFF880E4F,
            0xFF90A4AE to 0xFF263238).map { (a, b) -> a.toInt() to b.toInt() }
        val SHAPES = listOf("cube", "drop", "blob", "rings", "initial")

        fun random(r: Random = Random.Default, initial: String = "") = Avatar(
            r.nextInt(PALETTES.size), SHAPES[r.nextInt(SHAPES.size - 1)], r.nextInt(24) * 15, r.nextInt(1 shl 30),
            initial.take(1).uppercase())
    }

    fun render(size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val (c1, c2) = PALETTES[palette]
        val a = Math.toRadians(angle.toDouble())
        val dx = cos(a).toFloat() * s / 2
        val dy = sin(a).toFloat() * s / 2
        c.drawRect(0f, 0f, s, s, Paint().apply {
            shader = LinearGradient(s / 2 - dx, s / 2 - dy, s / 2 + dx, s / 2 + dy, c1, c2, Shader.TileMode.CLAMP)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        val cx = s / 2
        val rng = Random(seed)
        when (shape) {
            "cube" -> {
                val w = s * .22f
                fun face(alpha: Int, vararg pts: Float) {
                    val path = Path().apply { moveTo(pts[0], pts[1]); for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1]); close() }
                    c.drawPath(path, p.apply { this.alpha = alpha })
                }
                face(235, cx, cx - w * 1.1f, cx + w, cx - w * .55f, cx, cx, cx - w, cx - w * .55f)
                face(170, cx - w, cx - w * .55f, cx, cx, cx, cx + w * 1.15f, cx - w, cx + w * .6f)
                face(110, cx + w, cx - w * .55f, cx, cx, cx, cx + w * 1.15f, cx + w, cx + w * .6f)
            }
            "drop" -> {
                val r = s * .2f
                p.alpha = 220
                c.drawOval(cx - r, cx - r * .4f, cx + r, cx + r * 1.6f, p)
                c.drawPath(Path().apply { moveTo(cx - r * .93f, cx + r * .3f); lineTo(cx, cx - r * 1.5f); lineTo(cx + r * .93f, cx + r * .3f); close() }, p)
            }
            "blob" -> repeat(5) {
                val r = s * (0.08f + rng.nextFloat() * .12f)
                val x = (0.25f + rng.nextFloat() * .5f) * s
                val y = (0.25f + rng.nextFloat() * .5f) * s
                c.drawCircle(x, y, r, p.apply { alpha = 90 + rng.nextInt(110) })
            }
            "rings" -> for (i in 4 downTo 1) {
                c.drawCircle(cx, cx, s * .09f * i, p.apply { style = Paint.Style.STROKE; strokeWidth = s * .03f; alpha = 60 + 40 * i })
            }
            else -> {
                p.textSize = s * .5f
                p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                p.textAlign = Paint.Align.CENTER
                p.alpha = 240
                c.drawText(initial.ifBlank { "?" }, cx, cx - (p.descent() + p.ascent()) / 2, p)
            }
        }
        return bmp
    }
}

object Media {
    /**
     * A user photo → square 512 px JPEG. Re-encoding from pixels drops EXIF (GPS, camera, time): blossom.band even
     * rejects uploads with GPS metadata, and a profile photo must never reveal where it was taken.
     */
    suspend fun photoForUpload(ctx: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val m = minOf(info.size.width, info.size.height)
            if (m > 1024) d.setTargetSampleSize(m / 1024)
        }
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        Bitmap.createScaledBitmap(sq, 512, 512, true)
    }

    fun encode(bmp: Bitmap, jpeg: Boolean): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 88, out)
        return out.toByteArray() to (if (jpeg) "image/jpeg" else "image/png")
    }

    fun hasExif(bytes: ByteArray): Boolean =
        runCatching { androidx.exifinterface.media.ExifInterface(bytes.inputStream()).let { e ->
            e.getAttribute("GPSLatitude") != null || e.getAttribute("Make") != null || e.getAttribute("DateTimeOriginal") != null
        } }.getOrDefault(false)

    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/** Blossom (BUD-01/02 upload, BUD-11 auth, BUD-12 delete). Servers that took a free upload in identity/live_check.py. */
object Blossom {
    val SERVERS = listOf("https://blossom.primal.net", "https://nostr.download", "https://24242.io")
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    fun sha256(b: ByteArray) = Derive.hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun auth(k: Keys, verb: String, x: String, server: String): String {
        val ev = EventBuilder(Kind(24242u), "${verb.replaceFirstChar { it.uppercase() }} profile picture").tags(listOf(
            Nostr.tag("t", verb), Nostr.tag("x", x), Nostr.tag("expiration", (Nostr.now() + 300).toString()),
            Nostr.tag("server", server.substringAfter("//").trimEnd('/')))).finalize(k)
        // BUD-11 says base64url without padding; blossom.primal.net and nostr.download (route96) reject that and only
        // accept standard padded base64, which every server tried accepts (identity/live_check.py, 2026-10-16).
        return "Nostr " + java.util.Base64.getEncoder().encodeToString(ev.asJson().toByteArray())
    }

    data class Upload(val server: String, val url: String?, val error: String?)

    /** Upload to every server (first = `picture`, the rest = NIP-92 `fallback`s); signed by the profile key. */
    suspend fun uploadAll(k: Keys, bytes: ByteArray, mime: String): List<Upload> = withContext(Dispatchers.IO) {
        val x = sha256(bytes)
        SERVERS.map { s -> async {
            runCatching {
                http.newCall(Request.Builder().url("$s/upload").put(bytes.toRequestBody(mime.toMediaType()))
                    .header("Authorization", auth(k, "upload", x, s)).header("X-SHA-256", x).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (r.code in 200..299) {
                        val url = JSONObject(body).optString("url")
                        if (!url.endsWith(x) && !url.contains(x)) Upload(s, null, "url without hash: $url") else Upload(s, url, null)
                    } else Upload(s, null, "${r.code} ${r.header("X-Reason") ?: body.take(60)}")
                }
            }.getOrElse { Upload(s, null, it.javaClass.simpleName) }
        } }.awaitAll()
    }

    suspend fun delete(k: Keys, server: String, x: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$server/$x").delete().header("Authorization", auth(k, "delete", x, server)).build())
                .execute().use { it.code }
        }.getOrDefault(-1)
    }

    /** GET and check the bytes hash to the name (Blossom URLs are content addresses, so a picture can't be swapped). */
    suspend fun fetchVerified(urls: List<String>): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        for (u in urls) {
            val bytes = runCatching { http.newCall(Request.Builder().url(u).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
                ?: continue
            val x = sha256(bytes)
            if (u.contains(x)) return@withContext bytes to u
        }
        null
    }
}
