// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The icon loading is a Kotlin port of AOSP's android.util.IconDrawableFactory and android.util.LauncherIcons
// (frameworks/base, Apache-2.0, © 2017 The Android Open Source Project), both hidden from apps.
package app.mauri.sukrara.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Process
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import app.mauri.sukrara.R
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Another app or service that a settings row stands for, drawn as an app icon. [packages]: the installed app whose
 * real icon is used when present (first one found); [bundled]: Sukrara's copy of the service's own icon, used when
 * the app isn't installed (or the service has no app). Every bundled icon is an `<adaptive-icon>` (or, for an app
 * whose launcher icon is legacy, that legacy bitmap), so it goes through the same loader as an installed app.
 * Where the brand doesn't allow its logo (Tidepool) there is no entry: the row keeps a Material Symbol.
 * Sources and licences: docs/10-showcase.md "Service and app icons".
 */
enum class Service(val packages: List<String>, @param:DrawableRes val bundled: Int) {
    JUGGLUCO(listOf("tk.glucodata"), R.drawable.ic_app_juggluco),
    XDRIP(listOf("com.eveningoutpost.dexdrip"), R.drawable.ic_app_xdrip),
    // Health Connect is a system module since Android 14: Google builds ship the first package, AOSP the second
    HEALTH_CONNECT(listOf("com.google.android.healthconnect.controller", "com.android.healthconnect.controller"), R.drawable.ic_app_health_connect),
    NIGHTSCOUT(emptyList(), R.drawable.ic_app_nightscout),
    LIBREVIEW(emptyList(), R.drawable.ic_app_libreview),
}

/**
 * App icons exactly as AOSP Settings draws them in its app lists: SettingsLib's AppRepository loads
 * `IconDrawableFactory.newInstance(context).getBadgedIcon(app)` off the main thread and SPA's AppIcon draws it at
 * [SettingsDimension.appIconItemSize] (32 dp), unclipped. The factory takes the unbadged icon, puts the launcher
 * shadow under an adaptive icon (the device's mask shape comes from the AdaptiveIconDrawable itself) and adds the
 * work-profile badge. Every app or service icon in Sukrara goes through here, so they can't drift apart in size.
 */
object AppIcons {
    // LauncherIcons' constants (percent of the icon size)
    private const val BLUR_FACTOR = 0.5f / 48
    private const val KEY_SHADOW_DELTA_FACTOR = 1f / 48
    private const val KEY_SHADOW_ALPHA = 61
    private const val AMBIENT_SHADOW_ALPHA = 30

    /** The installed app's icon, or null when it isn't installed (or not visible: see `<queries>`). */
    fun forPackage(ctx: Context, pkg: String): Drawable? {
        val app = runCatching { ctx.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull() ?: return null
        return badged(ctx, app, app)
    }

    /** One of Sukrara's own icon resources, loaded as if it were an app's icon (like Settings loads activity icons). */
    fun forResource(ctx: Context, @DrawableRes res: Int): Drawable {
        val item = PackageItemInfo().apply { packageName = ctx.packageName; icon = res }
        return badged(ctx, item, ctx.applicationInfo)
    }

    /** The service's installed app if there is one, else its bundled icon. */
    fun forService(ctx: Context, s: Service): Drawable = s.packages.firstNotNullOfOrNull { forPackage(ctx, it) } ?: forResource(ctx, s.bundled)

    /** IconDrawableFactory.getBadgedIcon with embedShadow = true (what newInstance(context) does). */
    private fun badged(ctx: Context, item: PackageItemInfo, app: ApplicationInfo): Drawable {
        val pm = ctx.packageManager
        val icon = shadowed(ctx, item.loadUnbadgedIcon(pm))
        // the user's badge (work profile, clone…); returns the icon itself when this user has none
        return pm.getUserBadgedIcon(icon, Process.myUserHandle())
    }

    /** LauncherIcons.wrapIconDrawableWithShadow: legacy icons are drawn as they are, adaptive ones get the shadow. */
    fun shadowed(ctx: Context, d: Drawable): Drawable {
        if (d !is AdaptiveIconDrawable) return d
        val size = maxOf(ctx.resources.getDimensionPixelSize(android.R.dimen.app_icon_size), d.intrinsicHeight)
        return ShadowDrawable(shadowBitmap(d, size), d)
    }

    private fun shadowBitmap(d: AdaptiveIconDrawable, size: Int): Bitmap {
        d.setBounds(0, 0, size, size)
        val blur = BLUR_FACTOR * size
        val keyShadowDistance = KEY_SHADOW_DELTA_FACTOR * size
        val bitmapSize = (size + 2 * blur + keyShadowDistance).toInt()
        val shadow = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(shadow)
        canvas.translate(blur + keyShadowDistance / 2, blur)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
        paint.setShadowLayer(blur, 0f, 0f, AMBIENT_SHADOW_ALPHA shl 24)   // ambient shadow
        canvas.drawPath(d.iconMask, paint)
        canvas.translate(0f, keyShadowDistance)
        paint.setShadowLayer(blur, 0f, 0f, KEY_SHADOW_ALPHA shl 24)       // key shadow
        canvas.drawPath(d.iconMask, paint)
        canvas.setBitmap(null)
        return shadow
    }

    /** LauncherIcons.ShadowDrawable: the shadow bitmap over the bounds, the icon scaled into it. */
    private class ShadowDrawable(private val shadow: Bitmap, icon: Drawable) : DrawableWrapper(icon) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawBitmap(shadow, null, b, paint)
            canvas.save()
            val factor = 1 / (1 + 2 * BLUR_FACTOR + KEY_SHADOW_DELTA_FACTOR)
            canvas.translate(b.width() * factor * (BLUR_FACTOR + KEY_SHADOW_DELTA_FACTOR / 2), b.height() * factor * BLUR_FACTOR)
            canvas.scale(factor, factor)
            super.draw(canvas)
            canvas.restore()
        }
    }
}

/** SPA's AppIcon: the drawable loaded off the main thread, at the app-list size (32 dp in the 40 dp icon slot). */
@Composable
private fun AppIconImage(key: Any, load: (Context) -> Drawable?) {
    val ctx = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val d by produceState(if (inPreview) load(ctx) else null, key) { value = withContext(Dispatchers.IO) { load(ctx) } }
    Image(rememberDrawablePainter(d), contentDescription = null, modifier = Modifier.size(SettingsDimension.appIconItemSize))
}

/** The icon of a service or app Sukrara works with (its installed app's icon when there is one). */
@Composable
fun ServiceIcon(s: Service) = AppIconImage(s) { AppIcons.forService(it, s) }

/** An installed app's icon; the system's default app icon if it disappeared meanwhile. */
@Composable
fun PackageIcon(pkg: String) = AppIconImage(pkg) { AppIcons.forPackage(it, pkg) ?: AppIcons.shadowed(it, it.packageManager.defaultActivityIcon) }
