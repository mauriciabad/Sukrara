// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.hook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.features.HookPosition
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.status.Range
import app.mauri.sukrara.status.RootStatusBar
import app.mauri.sukrara.status.Trend
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Approach 3b: an LSPosed module running inside SystemUI. It adds its own TextView to the status bar —
 * next to the clock, or around the system icons — so it can use colour, the clock's font, and a trend
 * arrow at any angle. The app feeds it with a permission-protected broadcast ([RootStatusBar]).
 *
 * Safety: SystemUI is crash-looped into Android's rescue escalation if it dies 5× in a minute, so every hook
 * body catches Throwable, and `adb shell settings put global sukrara_hook_disabled 1` turns the module
 * into a no-op on the next SystemUI start without touching LSPosed.
 */
class SystemUiHook : XposedModule() {
    // Modern libxposed API (101+, entry in META-INF/xposed/java_init.list). SystemUI's classes are only
    // reachable once its class loader is ready.
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SYSTEMUI || !param.isFirstPackage) return
        val cl = param.classLoader
        safe("hook") {
            // Collapsed status bar (the one over apps and the home screen). Still a classic View hierarchy on
            // Android 17 — Compose only wraps it and adds the chips and the right-side icons.
            after(cl.loadClass("com.android.systemui.statusbar.phone.PhoneStatusBarView").getDeclaredMethod("onFinishInflate")) {
                attach(it.thisObject as ViewGroup, Bar.MAIN)
            }
            // Lock screen status bar (no clock there; we sit left of the system icons).
            after(cl.loadClass("com.android.systemui.statusbar.phone.KeyguardStatusBarView").getDeclaredMethod("onFinishInflate")) {
                attach(it.thisObject as ViewGroup, Bar.KEYGUARD)
            }
        }
        safe("hook icon order") { hookIconOrder(cl) }
        safe("hook icon tint") { hookIconTint(cl) }
        safe("hook notification icons") { hookNotificationIcons(cl) }
    }

    /**
     * Drop Sukrara's own notification icon from the status bar while the value is shown there (`hideIcon`): the
     * notification itself stays in the shade, on the lock screen and on AOD. SystemUI maps the set of status-bar
     * notification icons (ActiveNotificationIconModel, from NotificationIconsInteractor.filteredNotifSet) to the
     * container's view data in a `map` collector that belongs to the status bar only
     * (NotificationIconContainerStatusBarViewModel; the shelf and AOD have their own), so the set is filtered there.
     * Takes effect on SystemUI's next rebuild of that set, i.e. our next notification post.
     */
    private fun hookNotificationIcons(cl: ClassLoader) {
        val vm = "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel"
        val collector = cl.find("$vm\$special\$\$inlined\$map\$1\$2") ?: return log("no $vm map collector")
        val emit = collector.declaredMethods.firstOrNull { it.name == "emit" && it.parameterCount == 2 }
            ?: return log("no $vm map collector emit()")
        val model = cl.find("com.android.systemui.statusbar.notification.icon.domain.interactor.ActiveNotificationIconModel")
            ?: return log("no ActiveNotificationIconModel")
        val key = runCatching { model.getDeclaredField("notifKey") }.getOrNull()?.apply { isAccessible = true }
            ?: return log("no ActiveNotificationIconModel.notifKey")
        // Notification keys are "user|package|id|tag|uid".
        fun ours(o: Any?) = model.isInstance(o) && (key.get(o) as? String)?.split('|')?.getOrNull(1) == RootStatusBar.PKG
        hook(emit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            // The same collector class also maps the chips' keys (not a set of icon models) → untouched.
            val set = chain.getArg(0) as? Set<*>
            val st = state
            val filtered = try {
                if (set != null && st.enabled && st.hideIcon && st.reading != null && set.any(::ours))
                    LinkedHashSet(set.filterNot(::ours)) else null
            } catch (t: Throwable) { log("icon filter failed: $t"); null }
            if (filtered == null) chain.proceed() else chain.proceed(arrayOf(filtered, chain.getArg(1)))
        }
        log("notification icon hook: ok")
    }

    /**
     * Colour for the system-icon placements. SystemUI's Compose `StatusBarIcon` tints every icon with one colour
     * filter and draws it with `Image(painter, contentDescription, …, colorFilter, …)`. Our icons are recognisable
     * by their content description ("Glucose …", status/RootStatusBar), so for those the filter is swapped for the
     * range colour (in range: SystemUI's own tint, like the other icons).
     */
    private fun hookIconTint(cl: ClassLoader) {
        val image = cl.find("androidx.compose.foundation.ImageKt")?.declaredMethods?.firstOrNull {
            it.name == "Image" && it.parameterCount == 10 && it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[6].name == "androidx.compose.ui.graphics.ColorFilter"
        } ?: return log("no Compose Image(…, ColorFilter, …)")
        val filterClass = cl.find("androidx.compose.ui.graphics.BlendModeColorFilter")
            ?: return log("no Compose BlendModeColorFilter")
        // StatusBarIcon creates a fresh filter object per draw, so recolour that object instead of building one
        // (R8 left no usable constructor): its native filter lives in the base class ColorFilter.
        val native = filterClass.superclass?.declaredFields?.firstOrNull { it.type == android.graphics.ColorFilter::class.java }
            ?.apply { isAccessible = true } ?: return log("no ColorFilter.nativeColorFilter")
        val colorField = runCatching { filterClass.getDeclaredField("color").apply { isAccessible = true } }.getOrNull()
        hook(image).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val desc = chain.getArg(1) as? String
            val filter = chain.getArg(6) as Any?
            val color = if (desc != null && desc.startsWith("Glucose ") && filterClass.isInstance(filter)) iconColor() else null
            if (color != null) runCatching {
                native.set(filter, android.graphics.BlendModeColorFilter(color, android.graphics.BlendMode.SRC_IN))
                // Compose's own copy of the colour (packed sRGB: ARGB in the high 32 bits), for equals()/toString().
                colorField?.setLong(filter, (color.toLong() and 0xFFFFFFFFL) shl 32)
            }
            chain.proceed()
        }
        log("icon tint hook: ok")
    }

    /** Range colour for our system icons, or null to keep SystemUI's tint (off, in range, stale, other placements). */
    private fun iconColor(): Int? {
        val st = state
        val r = st.reading ?: return null
        if (!st.enabled || !st.color || !st.position.startsWith(HookPosition.SLOT_PREFIX)) return null
        if (System.currentTimeMillis() / 1000 - r.t > GlucoseNotification.STALE_AFTER_S) return null
        return Range.color(r.sgv)
    }

    /**
     * Precise position among the right-side icons. Those are Compose, so instead of adding a view we reorder the
     * list SystemUI renders: the icons set by the slot daemon (approach 3a) are moved right after the chosen slot.
     * The list is built in an R8-named lambda of SystemStatusIconsViewModelImpl, so find it by shape, not name.
     */
    private fun hookIconOrder(cl: ClassLoader) {
        val vm = "com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconsViewModelImpl"
        val vmClass = cl.find(vm) ?: return log("no $vm")
        var hooked = 0
        for (n in 0..15) {
            val c = cl.find("$vm\$\$ExternalSyntheticLambda$n") ?: continue
            if (c.interfaces.none { it.name == "kotlin.jvm.functions.Function0" }) continue
            if (c.declaredFields.none { it.type == vmClass }) continue
            val invoke = c.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 0 } ?: continue
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val result = chain.proceed()
                try { reorder(result) ?: result } catch (t: Throwable) { log("reorder failed: $t"); result }
            }
            hooked++
        }
        log("icon order hooks: $hooked")
    }

    private fun ClassLoader.find(name: String): Class<*>? = runCatching { Class.forName(name, false, this) }.getOrNull()

    /** The reordered list, or null to keep SystemUI's. */
    private fun reorder(result: Any?): List<*>? {
        val after = state.position.takeIf { state.enabled && it.startsWith(HookPosition.SLOT_PREFIX) }
            ?.removePrefix(HookPosition.SLOT_PREFIX) ?: return null
        val list = result as? List<*> ?: return null
        val ours = list.filter { slotName(it) in OUR_SLOTS }
        if (ours.isEmpty()) return null
        val rest = list.filter { slotName(it) !in OUR_SLOTS }.toMutableList()
        val i = rest.indexOfFirst { slotName(it) == after }
        if (i < 0) return null // target icon not shown right now → leave ours first (leftmost)
        rest.addAll(i + 1, ours)
        return rest
    }

    private fun slotName(o: Any?): String? = o?.let {
        runCatching { it.javaClass.getMethod("getSlotName").invoke(it) as String }.getOrNull()
            ?: runCatching { it.javaClass.getField("slotName").get(it) as String }.getOrNull()
    }

    private enum class Bar { MAIN, KEYGUARD }

    /** One glucose view per status bar instance; SystemUI may inflate several (e.g. per display). */
    private class Slot(val bar: Bar, root: ViewGroup, val view: GlucoseView) {
        val root = WeakReference(root)
    }

    private fun attach(root: ViewGroup, bar: Bar) = safe("attach $bar") {
        val ctx = root.context
        if (Settings.Global.getInt(ctx.contentResolver, KILL_SWITCH, 0) == 1) {
            log("disabled by $KILL_SWITCH"); return@safe
        }
        ensureReceiver(ctx)
        val view = GlucoseView(ctx)
        val slot = Slot(bar, root, view)
        slots.add(slot)
        // Copy the clock's look; on the keyguard (no clock) the carrier text has the same style.
        (root.findById("clock") ?: root.findById("keyguard_carrier_text"))?.let { ref ->
            view.reference = WeakReference(ref)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, ref.textSize)
            view.typeface = ref.typeface
        }
        place(slot)
        view.render()
        log("attached to $bar")
    }

    @SuppressLint("DiscouragedApi")
    private fun View.findById(name: String): TextView? =
        findViewById(resources.getIdentifier(name, "id", SYSTEMUI)) as? TextView

    @SuppressLint("DiscouragedApi")
    private fun ViewGroup.findGroup(name: String): ViewGroup? =
        findViewById(resources.getIdentifier(name, "id", SYSTEMUI)) as? ViewGroup

    /** (Re)insert the view where [state].position says. Called on attach and when the position changes. */
    private fun place(slot: Slot) = safe("place") {
        val root = slot.root.get() ?: return@safe
        val view = slot.view
        (view.parent as? ViewGroup)?.removeView(view)
        val lp = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val pad = root.resources.displayMetrics.density * 4
        if (slot.bar == Bar.KEYGUARD) {
            // status_icon_area: [system_icons_container (Compose icons), …] → we go first.
            val area = root.findGroup("status_icon_area") ?: return@safe
            view.setPadding(0, 0, pad.toInt(), 0)
            area.addView(view, 0, lp)
            return@safe
        }
        val clock = root.findById("clock")
        val end = root.findGroup("status_bar_end_side_content")
        val pos = state.position
        when {
            pos == HookPosition.BEFORE_CLOCK && clock != null -> {
                view.setPadding(0, 0, pad.toInt(), 0)
                (clock.parent as ViewGroup).addView(view, (clock.parent as ViewGroup).indexOfChild(clock), lp)
            }
            pos.startsWith(HookPosition.ICONS_PREFIX) && end != null -> {
                // icons:0 → left of every right-side icon; icons:-1 → after the battery (far right).
                // The Compose icon row is a single child of `end`, so "between Wi-Fi and battery" is out of
                // reach from here (see the report).
                val n = pos.removePrefix(HookPosition.ICONS_PREFIX).toIntOrNull() ?: 0
                view.setPadding(if (n < 0) pad.toInt() else 0, 0, if (n < 0) 0 else pad.toInt(), 0)
                // At onFinishInflate the Compose icons aren't added yet; post so indexes are final.
                end.post {
                    safe("place end") {
                        (view.parent as? ViewGroup)?.removeView(view)
                        val compose = (0 until end.childCount).map(end::getChildAt).lastOrNull { it.visibility == View.VISIBLE }
                        val i = if (n < 0 || compose == null) end.childCount else end.indexOfChild(compose)
                        end.addView(view, i, lp)
                    }
                }
            }
            clock != null -> {
                view.setPadding(pad.toInt(), 0, 0, 0)
                (clock.parent as ViewGroup).addView(view, (clock.parent as ViewGroup).indexOfChild(clock) + 1, lp)
            }
        }
    }

    // --- data from the app ---------------------------------------------------------------------------

    private data class State(val reading: Reading? = null, val enabled: Boolean = false,
                             val position: String = HookPosition.AFTER_CLOCK, val color: Boolean = true,
                             val hideIcon: Boolean = false)

    /** Written on the main thread (receiver), read by the Compose/flow hooks on SystemUI's background threads. */
    @Volatile private var state = State()
    private val slots = mutableListOf<Slot>()
    private var receiverRegistered = false
    private val main by lazy { Handler(Looper.getMainLooper()) } // the module is created before the main looper

    private fun ensureReceiver(ctx: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val app = ctx.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = safe("receive") { onUpdate(i) }
        }, IntentFilter(RootStatusBar.ACTION), RootStatusBar.PERMISSION, main, Context.RECEIVER_EXPORTED)
        // Ask the app for the current value instead of waiting for the next reading.
        app.sendBroadcast(Intent(RootStatusBar.ACTION_HELLO).setPackage(RootStatusBar.PKG))
        // Re-render once a minute so "stale" appears even when no data arrives.
        main.postDelayed(object : Runnable {
            override fun run() { safe("tick") { renderAll() }; main.postDelayed(this, 30_000) }
        }, 30_000)
    }

    private fun onUpdate(i: Intent) {
        val old = state
        val reading = if (i.hasExtra("mgdl")) Reading(i.getLongExtra("epochSec", 0), i.getIntExtra("mgdl", 0),
            if (i.hasExtra("rate")) i.getDoubleExtra("rate", 0.0) else null) else old.reading
        state = State(reading, i.getBooleanExtra("enabled", true), i.getStringExtra("position") ?: old.position,
            i.getBooleanExtra("color", old.color), i.getBooleanExtra("hideIcon", old.hideIcon))
        slots.removeAll { it.root.get() == null }
        if (state.position != old.position) slots.forEach { place(it) }
        renderAll()
    }

    private fun renderAll() = slots.forEach { it.view.render() }

    // --- the view ---------------------------------------------------------------------------------

    @SuppressLint("ViewConstructor", "AppCompatCustomView")
    private inner class GlucoseView(ctx: Context) : TextView(ctx) {
        var reference: WeakReference<TextView>? = null
        private val arrow = ArrowDrawable()
        private var lastRefColor = 0
        private var followAlpha = 1f
        private var staleAlpha = 1f

        init {
            gravity = android.view.Gravity.CENTER_VERTICAL
            isSingleLine = true
            includeFontPadding = false
            compoundDrawablePadding = (ctx.resources.displayMetrics.density * 1).toInt()
            // Follow the clock: light/dark tint, and fading out (lock screen, heads-up notifications) — SystemUI
            // animates the clock's own alpha/visibility, not its siblings'. Cheap per-frame check.
            viewTreeObserver.addOnPreDrawListener {
                safe("predraw") {
                    val ref = reference?.get() ?: return@safe
                    if (ref.currentTextColor != lastRefColor) { lastRefColor = ref.currentTextColor; render() }
                    val a = if (ref.isShown) ref.alpha else 0f
                    if (a != followAlpha) { followAlpha = a; alpha = a * staleAlpha }
                }
                true
            }
        }

        fun render() = safe("render") {
            val r = state.reading
            // "slot:…" positions are drawn by SystemUI itself (approach 3a icons, reordered above).
            if (!state.enabled || r == null || state.position.startsWith(HookPosition.SLOT_PREFIX)) {
                visibility = GONE; return@safe
            }
            visibility = VISIBLE
            text = when {
                r.sgv < 40 -> "LO"
                r.sgv > 400 -> "HI"
                else -> r.sgv.toString()
            }
            val stale = System.currentTimeMillis() / 1000 - r.t > GlucoseNotification.STALE_AFTER_S
            val base = reference?.get()?.currentTextColor ?: currentTextColor
            val color = if (stale || !state.color) base else Range.color(r.sgv) ?: base
            setTextColor(color)
            staleAlpha = if (stale) 0.5f else 1f
            alpha = followAlpha * staleAlpha
            paintFlags = if (stale) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            arrow.rate = r.rate
            arrow.color = color
            val s = (textSize * 0.95f).toInt()
            arrow.setBounds(0, 0, s, s)
            setCompoundDrawables(null, null, arrow, null)
            contentDescription = "Glucose ${r.sgv} ${Trend.arrow(r.rate)}"
        }
    }

    /** The Mac's continuous-angle arrow (GlucoseNotification.drawArrow), as a Drawable. */
    private class ArrowDrawable : Drawable() {
        var rate: Double? = null
        var color = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(cv: Canvas) {
            val b = bounds
            paint.color = color
            val rate = rate
            cv.save()
            cv.translate(b.exactCenterX(), b.exactCenterY())
            if (rate == null) {
                cv.drawCircle(0f, 0f, b.width() * 0.11f, paint)
            } else {
                cv.rotate(Trend.angleDeg(rate))
                GlucoseNotification.drawArrow(cv, b.width().toFloat(), paint, Trend.isDouble(rate))
            }
            cv.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // --- safety -------------------------------------------------------------------------------------

    /** Run [block] after [m]; PROTECTIVE means a throw from our side can't break the original call. */
    private fun after(m: Method, block: (XposedInterface.Chain) -> Unit) {
        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            safe("after ${m.name}") { block(chain) }
            result
        }
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("$what failed: $t")
        }
    }

    private fun log(s: String) = runCatching { log(Log.INFO, "Sukrara", s) }.let { }

    companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val KILL_SWITCH = "sukrara_hook_disabled"
        val OUR_SLOTS = setOf(RootStatusBar.SLOT_NUMBER, RootStatusBar.SLOT_TREND)
    }
}
