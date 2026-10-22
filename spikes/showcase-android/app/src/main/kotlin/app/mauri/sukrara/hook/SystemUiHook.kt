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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.features.HookPosition
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.ValueGlyphs
import app.mauri.sukrara.core.Range
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.status.RootStatusBar
import app.mauri.sukrara.core.Trend
import java.lang.ref.WeakReference
import java.lang.reflect.Executable

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
        safe("hook icon fit") { hookIconFit(cl) }
        safe("hook icon tint") { hookIconTint(cl) }
        safe("hook notification icons") { hookNotificationIcons(cl) }
        safe("hook lock screen widget") { hookLockscreenWidget(cl) }
    }

    /**
     * Investigation 17: the lock screen widget ([LockscreenWidgetHost]). Its view goes into the notification shade
     * window's root (SceneWindowRootView, where the Compose lock screen is), which also passes it every touch.
     */
    private fun hookLockscreenWidget(cl: ClassLoader) {
        val rootClass = cl.loadClass("com.android.systemui.scene.ui.view.SceneWindowRootView")
        after(cl.loadClass("com.android.systemui.scene.domain.interactor.LegacySceneInteractorImpl").declaredConstructors.single()) {
            lockWidget.onScenes(it.thisObject!!)
        }
        lockWidget.resolveAuth(cl)
        for (c in cl.loadClass("com.android.systemui.biometrics.AuthController").declaredConstructors) {
            after(c) { lockWidget.onAuth(it.thisObject!!) }
        }
        after(cl.loadClass("com.android.systemui.scene.ui.view.WindowRootView").getDeclaredMethod("onAttachedToWindow")) {
            val root = it.thisObject as ViewGroup
            if (!rootClass.isInstance(root)) return@after
            if (Settings.Global.getInt(root.context.contentResolver, KILL_SWITCH, 0) == 1) return@after
            ensureReceiver(root.context)
            lockWidget.onRootAttached(root)
        }
        hook(rootClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java))
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                (chain.getArg(0) as? MotionEvent)?.let(lockWidget::onTouch)
                chain.proceed()
            }
        // The lock screen gives the notification shelf extra room beside the lock icon (spaceForShelf, below the
        // notifications' space, which ends at the icon's top). With the widget there, the shelf has to fit above it.
        val calc = cl.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator")
        hook(calc.declaredMethods.single { it.name == "computeMaxKeyguardNotifications" })
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val space = chain.getArg(1) as Float
                val shelf = chain.getArg(2) as Float
                if (!lockWidget.enabled || shelf <= 0f) chain.proceed()
                else chain.proceed(arrayOf(chain.getArg(0), (space - shelf).coerceAtLeast(0f), shelf, chain.getArg(3)))
            }
        log("lock screen widget hooks: ok")
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
        val now = System.currentTimeMillis() / 1000
        val look = chipFace(r, now, st.sensor())
        // struck (its drawable is already half strength), no readings, ended: SystemUI's own tint
        if (look.state != GlucoseLook.State.VALUE || look.strike) return null
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
        iconOrderHooked = hooked > 0
        log("icon order hooks: $hooked")
    }

    /** The icon order hook is in place: the hello tells the app, so the slot daemon leaves the order to us. */
    @Volatile private var iconOrderHooked = false

    private fun ClassLoader.find(name: String): Class<*>? = runCatching { Class.forName(name, false, this) }.getOrNull()

    /**
     * The reordered list, or null to keep SystemUI's ([IconFit.arrange]). Always number then trend: after a System UI
     * restart system_server hands our icons back in hash order (trend first), so this holds them in place from the first
     * frame, also for the plain `slot:` placement and before the app's state arrives (the slot daemon's re-add is then
     * skipped, RootStatusBar.moduleHello). With "after X": SystemUI's list holds only the icons shown right now, so a
     * hidden target (Bluetooth off, no VPN) is placed by the platform's slot order: ours stay where they'd be next to it.
     */
    private fun reorder(result: Any?): List<*>? {
        val list = result as? List<*> ?: return null
        val after = state.position.takeIf { state.enabled && it.startsWith(HookPosition.SLOT_PREFIX) }?.removePrefix(HookPosition.SLOT_PREFIX)
        return IconFit.arrange(list.map(::slotName), OUR_SLOTS, after, slotOrder)?.map { list[it] }
    }

    /** The platform's status-bar slot order (`config_statusBarIcons`, what SystemUI sorts its own icons by). */
    @get:SuppressLint("DiscouragedApi")
    private val slotOrder: List<String> by lazy {
        runCatching {
            val res = android.content.res.Resources.getSystem()
            res.getStringArray(res.getIdentifier("config_statusBarIcons", "array", "android")).toList()
        }.getOrElse { log("no config_statusBarIcons: $it"); emptyList() }
    }

    /**
     * Glucose wins the space (the user, 2026-10-22: with vibrate / hotspot icons on, only the arrow was left). SystemUI's
     * `EllipsizingRow` measure policy fits the icons from the right and folds the first one that doesn't fit, and all
     * left of it, into an overflow dot; ours were the leftmost, so the number went first. This hands SystemUI's own
     * measurement the row with our icons at the priority end ([IconFit]), then puts the shown icons back in their
     * on-screen order and keeps number + trend together (both, or both in the dot). The placement only chooses where
     * ours sit. R8 keeps the classes' names in the Pixel build but they move between releases, so they're found by shape.
     */
    private fun hookIconFit(cl: ClassLoader) {
        val policyIface = cl.find("androidx.compose.ui.layout.MeasurePolicy") ?: return log("no MeasurePolicy")
        val policies = listOf("com.android.systemui.common.ui.compose", "com.android.systemui.statusbar.icons.ui.compose")
            .flatMap { pkg -> (1..3).flatMap { a -> (1..3).map { b -> "$pkg.EllipsizingRowKt\$EllipsizingRow\$$a\$$b" } } }
            .mapNotNull { cl.find(it) }.filter { policyIface.isAssignableFrom(it) }
        var hooked = 0
        var pulls = 0
        for (c in policies) {
            val measure = c.declaredMethods.firstOrNull {
                it.name.startsWith("measure") && it.parameterCount == 3 && it.parameterTypes[1] == List::class.java &&
                    it.parameterTypes[2] == Long::class.javaPrimitiveType
            } ?: continue
            val fit = RowFit(c, cl)
            // SystemUI's placement block for this policy (a synthetic lambda next to it), for the number → trend gap.
            (0..5).mapNotNull { cl.find("${c.name}\$\$ExternalSyntheticLambda$it") }
                .firstOrNull { b -> b.interfaces.any { it.name == "kotlin.jvm.functions.Function1" } && b.declaredFields.any { it.type == List::class.java } }
                ?.let { b -> safe("hook icon pull") { fit.hookPull(b) } }
            if (fit.pullHooked) pulls++
            hook(measure).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val list = chain.getArg(1) as? List<*>
                // Decided before SystemUI measures: the children may be measured only once per pass.
                val plan = try { if (fit.broken || list == null || !shownAsIcons()) null else fit.plan(chain.thisObject!!, list) }
                    catch (t: Throwable) { fit.fail("plan", t); null }
                if (plan == null) return@intercept chain.proceed()
                val result = chain.proceed(arrayOf(chain.getArg(0), plan.permuted, chain.getArg(2)))
                try { fit.apply(chain.thisObject!!, chain.getArg(0)!!, chain.getArg(2) as Long, result, plan) }
                catch (t: Throwable) { fit.fail("apply", t) }
                result
            }
            hooked++
        }
        log("icon fit hooks: $hooked, gap hooks: $pulls")
    }

    /** Our icons are system icons right now (a `slot:` placement with the module on). */
    private fun shownAsIcons() = state.enabled && state.position.startsWith(HookPosition.SLOT_PREFIX)

    /** The reflection behind [hookIconFit] for one measure-policy class; [broken] after a failure (SystemUI's behaviour). */
    private inner class RowFit(private val policy: Class<*>, private val cl: ClassLoader) {
        @Volatile var broken = false
        private val fromStart = policy.declaredFields.firstOrNull { it.type.isEnum && it.type.simpleName == "FillDirection" }
            ?.apply { isAccessible = true }
        private val spacingDp = policy.declaredFields.firstOrNull { it.type == Float::class.javaPrimitiveType }?.apply { isAccessible = true }
        private val nodeClass = cl.find("androidx.compose.ui.node.LayoutNode")
        private val placeableWidth = cl.find("androidx.compose.ui.layout.Placeable")?.getMethod("getWidth")
        private val density = cl.find("androidx.compose.ui.unit.Density")?.getMethod("getDensity")
        private val minWidth = cl.find("androidx.compose.ui.unit.Constraints")?.declaredMethods
            ?.firstOrNull { it.name.startsWith("getMinWidth-impl") && it.parameterCount == 1 }

        /** Our icons' places in the row SystemUI measures ([permuted]); [number] / [trend] = the pair's, when both are there. */
        inner class Plan(val permuted: List<Any?>, val perm: IntArray, val oursPermuted: Set<Int>, val number: Int?, val trend: Int?)

        /** The number's placeable and how many px SystemUI's placement block moves everything after it left ([hookPull]). */
        inner class Pull(val number: Any, val px: Int)

        /** Per placement block (a new one per measurement; Compose may run one several times): the pull to apply. */
        val pulls: MutableMap<Any, Pull> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())
        @Volatile var pullHooked = false
        private val placeableWidthField = cl.find("androidx.compose.ui.layout.Placeable")?.declaredFields
            ?.firstOrNull { it.name == "width" && it.type == Int::class.javaPrimitiveType }?.apply { isAccessible = true }
        private val layoutDirection = cl.find("androidx.compose.ui.layout.IntrinsicMeasureScope")?.methods
            ?.firstOrNull { it.name == "getLayoutDirection" && it.parameterCount == 0 }
        private val placeableHeight = cl.find("androidx.compose.ui.layout.Placeable")?.getMethod("getHeight")

        fun fail(what: String, t: Throwable) { broken = true; log("icon fit $what failed, SystemUI's own fit from now on: $t") }

        fun plan(thiz: Any, list: List<*>): Plan? {
            checkNotNull(semantics) { "no LayoutNode.getSemanticsConfiguration" }
            val kinds = list.map { kindOf(it) }
            val ours = list.indices.filter { kinds[it] != null }.toSet()
            if (ours.isEmpty()) return null
            val fromEnd = (fromStart?.get(thiz) as? Enum<*>)?.name != "FromStart"
            val perm = IconFit.permutation(list.size, ours, fromEnd) ?: IntArray(list.size) { it }
            val at = { k: Kind -> perm.indices.singleOrNull { kinds[perm[it]] == k } }
            val trend = at(Kind.TREND)
            return Plan(perm.map { list[it] }, perm, perm.indices.filter { perm[it] in ours }.toSet(),
                if (trend != null) at(Kind.OURS) else null, trend)
        }

        /** After SystemUI's measurement: rewrite its decision (read by its placement block, which runs later). */
        fun apply(thiz: Any, scope: Any, constraints: Long, result: Any?, plan: Plan) {
            val blockField = result?.javaClass?.declaredFields?.firstOrNull { it.type.name == "kotlin.jvm.functions.Function1" &&
                it.apply { isAccessible = true }.get(result)?.javaClass?.name?.startsWith(policy.name.substringBefore("\$")) == true }
                ?: error("no placement block in $result")
            val block = blockField.get(result)!!
            val decision = block.javaClass.declaredFields.map { it.apply { isAccessible = true }.get(block) }
                .firstOrNull { o -> o != null && o.javaClass.name.substringBeforeLast('.') == policy.name.substringBeforeLast('.') &&
                    o.javaClass.declaredFields.any { it.type == List::class.java } && o.javaClass.declaredFields.any { it.type == Integer::class.java } }
                ?: error("no MeasureResult in ${block.javaClass.name}")
            val fields = decision.javaClass.declaredFields.onEach { it.isAccessible = true }
            val visibleField = fields.first { it.type == List::class.java }
            val overflowField = fields.first { it.type == Integer::class.java }
            @Suppress("UNCHECKED_CAST") val visible = visibleField.get(decision) as MutableList<Int>
            val before = IconFit.Fit(visible.toList(), overflowField.get(decision) as Int?)
            val after = IconFit.repair(before, plan.perm, plan.oursPermuted)
            if (after != before) {
                visible.clear(); visible.addAll(after.visible)
                overflowField.set(decision, after.overflow)
            }
            val widths = plan.permuted.map { (placeableWidth?.invoke(it) as? Int) ?: 0 }
            val spacing = Math.round((spacingDp?.getFloat(thiz) ?: 6f) * (density?.invoke(scope) as Float))
            // Number + trend next to each other: the trend at the card's gap from the number (the block moves it).
            val n = plan.number; val t = plan.trend
            val adjacent = n != null && t != null && after.visible.indexOf(n).let { it >= 0 && after.visible.getOrNull(it + 1) == t }
            val ltr = (layoutDirection?.invoke(scope) as? Enum<*>)?.name == "Ltr"
            val pull = if (adjacent && ltr && pullHooked && placeableWidthField != null)
                IconFit.pull(spacing, (placeableHeight?.invoke(plan.permuted[n!!]) as? Int) ?: 0) else 0
            if (after.visible.size == before.visible.size && pull == 0) return // at most the order changed
            // Our pair folded into the dot, or pulled together: the row is narrower than SystemUI measured it.
            val old = fields.filter { it.type == Int::class.javaPrimitiveType && it.name.contains("idth", true) }
            val oldTotal = old.firstOrNull()?.getInt(decision) ?: return
            val total = oldTotal - IconFit.width(widths, before, spacing, 0) + IconFit.place(widths, after, spacing, 0, n, pull).second
            old.forEach { it.setInt(decision, total) }
            val w = maxOf(total, (minWidth?.invoke(null, constraints) as? Int) ?: 0)
            result.javaClass.declaredFields.filter { it.type == Int::class.javaPrimitiveType && it.name.removePrefix("\$") == "width" }
                .forEach { it.isAccessible = true; it.setInt(result, w) }
            if (pull > 0) pulls[block] = Pull(plan.permuted[n!!]!!, pull)
        }

        /**
         * SystemUI's placement block moves on by each placed icon's width: while it runs, our number reports [Pull.px]
         * less, so the trend and everything after it sit that much closer (each icon still placed once, by SystemUI).
         * Restored right after; only for blocks [apply] registered.
         */
        fun hookPull(blockClass: Class<*>) {
            val invoke = blockClass.declaredMethods.firstOrNull { it.name == "invoke" && it.parameterCount == 1 } ?: return
            val field = placeableWidthField ?: return
            hook(invoke).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val p = if (broken) null else pulls[chain.thisObject]
                if (p == null) return@intercept chain.proceed()
                val w = field.getInt(p.number)
                field.setInt(p.number, w - p.px)
                try { chain.proceed() } finally { field.setInt(p.number, w) }
            }
            pullHooked = true
        }

        /** Which of ours a row child (a measurable: its LayoutNode's measure delegate) is, by its TalkBack text; null = not ours. */
        private fun kindOf(measurable: Any?): Kind? = nodeOf(measurable)?.let { ourDescription(it, 4) }
            ?.let { if (RootStatusBar.isTrendDescription(it)) Kind.TREND else Kind.OURS }

        private val nodeOfDelegate = HashMap<Class<*>, (Any) -> Any?>()

        /** The LayoutNode behind a measurable (MeasurePassDelegate → LayoutNodeLayoutDelegate → LayoutNode), found by type once per class. */
        private fun nodeOf(o: Any?): Any? {
            if (o == null || nodeClass == null) return null
            return nodeOfDelegate.getOrPut(o.javaClass) {
                val direct = o.javaClass.declaredFields.firstOrNull { it.type == nodeClass }?.apply { isAccessible = true }
                val viaDelegate = o.javaClass.declaredFields.firstOrNull { it.type.name.endsWith("LayoutNodeLayoutDelegate") }?.apply { isAccessible = true }
                val inner = viaDelegate?.type?.declaredFields?.firstOrNull { it.type == nodeClass }?.apply { isAccessible = true }
                when {
                    direct != null -> { x -> direct.get(x) }
                    viaDelegate != null && inner != null -> { x -> viaDelegate.get(x)?.let(inner::get) }
                    else -> { _ -> null }
                }
            }(o)
        }

        private val semantics by lazy { nodeClass?.methods?.firstOrNull { it.name == "getSemanticsConfiguration" && it.parameterCount == 0 } }
        private val children by lazy { nodeClass?.methods?.firstOrNull { it.name.startsWith("getChildren\$") && it.parameterCount == 0 } }

        private fun ourDescription(node: Any, depth: Int): String? {
            val sem = semantics?.invoke(node)
            (sem as? Iterable<*>)?.forEach { e ->
                when (val v = (e as? Map.Entry<*, *>)?.value) {
                    is String -> if (RootStatusBar.isOurDescription(v)) return v
                    is List<*> -> v.firstOrNull { it is String && RootStatusBar.isOurDescription(it) }?.let { return it as String }
                }
            }
            if (depth == 0) return null
            val kids = children?.invoke(node) as? List<*> ?: return null
            return kids.firstNotNullOfOrNull { it?.let { k -> ourDescription(k, depth - 1) } }
        }
    }

    /** Our icon in SystemUI's row: the trend beside the number, or the number / the single no-readings / ended icon. */
    private enum class Kind { OURS, TREND }

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
                             val hideIcon: Boolean = false, val sensorEnd: Long = 0, val sensorStart: Long = 0,
                             val sensorWarmup: Long = 0) {
        /** The sensor as the app sent it (null = none known). */
        fun sensor() = if (sensorEnd > 0) app.mauri.sukrara.core.SensorInfo("", sensorStart, sensorEnd, sensorWarmup) else null
    }

    /** Written on the main thread (receiver), read by the Compose/flow hooks on SystemUI's background threads. */
    @Volatile private var state = State()
    private val lockWidget = LockscreenWidgetHost(::log)
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
        app.sendBroadcast(Intent(RootStatusBar.ACTION_HELLO).setPackage(RootStatusBar.PKG).putExtra("iconOrder", iconOrderHooked))
        // Re-render once a minute so "stale" appears even when no data arrives.
        main.postDelayed(object : Runnable {
            override fun run() { safe("tick") { renderAll() }; main.postDelayed(this, 30_000) }
        }, 30_000)
    }

    private fun onUpdate(i: Intent) {
        val old = state
        if (i.hasExtra("low")) Range.bands = app.mauri.sukrara.graph.Bands(low = i.getIntExtra("low", 70), high = i.getIntExtra("high", 180)).sane()
        val reading = if (i.hasExtra("mgdl")) Reading(i.getLongExtra("epochSec", 0), i.getIntExtra("mgdl", 0),
            if (i.hasExtra("rate")) i.getDoubleExtra("rate", 0.0) else null) else old.reading
        state = State(reading, i.getBooleanExtra("enabled", true), i.getStringExtra("position") ?: old.position,
            i.getBooleanExtra("color", old.color), i.getBooleanExtra("hideIcon", old.hideIcon), i.getLongExtra("sensorEnd", 0),
            i.getLongExtra("sensorStart", 0), i.getLongExtra("sensorWarmup", 0))
        if (i.hasExtra("lockWidget")) lockWidget.enabled = i.getBooleanExtra("lockWidget", false)
        i.getStringExtra("lockBg")?.let { lockWidget.background = it }
        i.getStringExtra("lockLook")?.let { lockWidget.look = it }
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
        private val flag = PathDrawable(app.mauri.sukrara.core.SensorLife.FLAG_PATH)
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
            val base = reference?.get()?.currentTextColor ?: currentTextColor
            val now = System.currentTimeMillis() / 1000
            // what shows is the card's decision for this reading (GlucoseCard.face): the sensor's end or warm-up first,
            // then the freshness steps
            val v = HookText.of(chipFace(r, now, state.sensor()), state.color, Range.color(r.sgv), base)
            text = v.text
            paintFlags = if (v.strike) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            setTextColor(v.color)
            staleAlpha = v.alpha
            alpha = followAlpha * staleAlpha
            val drawable = when (v.mark) {
                HookText.Mark.ARROW, HookText.Mark.RING -> arrow.also { it.rate = if (v.mark == HookText.Mark.ARROW) r.rate else null; it.color = v.color }
                // round sensor-life: an ended sensor shows the checkered flag instead of a stale value; from 20 min
                // without a reading the "no readings" icon (sensors_off) instead of the old value
                HookText.Mark.FLAG -> flag.also { it.color = v.color }
                HookText.Mark.NO_READINGS -> noReadings.also { it.color = v.color }
                HookText.Mark.NONE -> null
            }
            val s = (textSize * if (v.mark == HookText.Mark.ARROW || v.mark == HookText.Mark.RING) 0.95f else 1.05f).toInt()
            drawable?.setBounds(0, 0, s, s)
            setCompoundDrawables(null, null, drawable, null)
            contentDescription = v.desc
        }
    }

    /** The "no readings" icon (Material Symbols `sensors_off`, [GlucoseLook.NO_READINGS_PATH]), as a Drawable. */
    private val noReadings by lazy { PathDrawable(GlucoseLook.NO_READINGS_PATH) }

    /** A Material Symbols path (960 × 960, y from −960) as a Drawable: the "sensor ended" flag, the "no readings" icon. */
    private class PathDrawable(private val data: String) : Drawable() {
        var color = 0
        private val path by lazy { androidx.core.graphics.PathParser.createPathFromPathData(data) }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(cv: Canvas) {
            cv.save()
            cv.translate(bounds.left.toFloat(), bounds.top.toFloat())
            cv.scale(bounds.width() / 960f, bounds.height() / 960f)
            cv.translate(0f, 960f)
            paint.color = color
            cv.drawPath(path, paint)
            cv.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The card's arrow ([ValueGlyphs.arrow], the Mac's continuous angle), as a Drawable. */
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
                // no trend: the hollow ring ([TrendMark])
                val p = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = TrendMark.ringStroke(b.width().toFloat()) }
                cv.drawCircle(0f, 0f, TrendMark.ringRadius(b.width().toFloat()), p)
            } else {
                cv.rotate(Trend.angleDeg(rate))
                ValueGlyphs.arrow(cv, b.width().toFloat(), paint, Trend.isDouble(rate))
            }
            cv.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // --- safety -------------------------------------------------------------------------------------

    /** Run [block] after [m]; PROTECTIVE means a throw from our side can't break the original call. */
    private fun after(m: Executable, block: (XposedInterface.Chain) -> Unit) {
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
        /** Our slots in the order they show: the number, then the trend. */
        val OUR_SLOTS = listOf(RootStatusBar.SLOT_NUMBER, RootStatusBar.SLOT_TREND)
    }
}

/**
 * What the root status bar shows for [r] (its chip's text and mark, and whether the icons get the range colour): the
 * card's own decision, [GlucoseCard.face], with the sensor the app sent. The hook's one value decision, also the tests'.
 */
internal fun chipFace(r: Reading, nowS: Long, sensor: app.mauri.sukrara.core.SensorInfo?) =
    GlucoseCard.face(r, nowS, app.mauri.sukrara.graph.SensorBadge.look(sensor, nowS, r.t), CardSurface.STATUS_HOOK)
