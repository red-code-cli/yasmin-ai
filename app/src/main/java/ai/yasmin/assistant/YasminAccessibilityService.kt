package ai.yasmin.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The "hands" of Yasmin: reads the screen tree and performs taps, swipes, typing and system actions. */
class YasminAccessibilityService : AccessibilityService() {
    companion object { @Volatile var inst: YasminAccessibilityService? = null }

    /** Time of the last real UI change in an app window; used to wait until a screen has settled. */
    @Volatile var lastEventAt = 0L
        private set

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { dropCover(); if (inst === this) inst = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (inst === this) inst = null; super.onDestroy() }   // never keep a dead service as "connected"
    override fun onInterrupt() {}

    /** Yasmin's own automation sets this so the lock gate does not fight it (the lock is for people, never for Yasmin). */
    @Volatile var automationUntil = 0L

    private val ui = Handler(Looper.getMainLooper())

    /**
     * Every action Yasmin performs keeps the gate open for a few more seconds, and when Yasmin has been quiet for that long the lock
     * comes back by itself. Yasmin therefore works on any app while the lock is closed and the screen is on, and a person never gets
     * more than a few seconds of free access out of it.
     */
    private fun busy(ms: Long = 8_000L) {
        automationUntil = maxOf(automationUntil, SystemClock.uptimeMillis() + ms)
        ui.removeCallbacks(recheck)
        ui.postDelayed(recheck, ms + 400L)
    }

    private val recheck: Runnable = Runnable {
        try {
            if (SystemClock.uptimeMillis() < automationUntil) { ui.postDelayed(recheck, 500L); return@Runnable }
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (LockStateManager.isLocked(this) && pm != null && pm.isInteractive) { showCover(); YasminLockManager.guardFromService(this) }
        } catch (_: Throwable) {}
    }

    // ---- cover: an opaque view drawn at once, so a tapped notification's app is never visible or touchable while the lock screen starts ----
    private var cover: View? = null
    @Volatile private var lastGateAt = 0L
    private val dropCoverRunnable: Runnable = Runnable { dropCover() }

    private fun showCover() {
        try {
            if (cover == null) {
                val v = View(this).apply { setBackgroundColor(0xFF05080F.toInt()); isClickable = true }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE
                )
                getSystemService(WindowManager::class.java).addView(v, lp)
                cover = v
            }
            ui.removeCallbacks(dropCoverRunnable)
            ui.postDelayed(dropCoverRunnable, 1_800L)     // safety: the cover can never stay and block the lock screen's own touches
        } catch (_: Throwable) {}
    }

    /** Removes the cover (lock screen is in front, or the phone was unlocked). */
    fun dropCover() {
        ui.removeCallbacks(dropCoverRunnable)
        val v = cover ?: return
        cover = null
        try { getSystemService(WindowManager::class.java).removeViewImmediate(v) } catch (_: Throwable) {}
    }

    /** A notification shade / quick-settings panel (Wi-Fi etc.) is a big System UI window; the thin status bar and the volume bubble are not. */
    private fun shadeOpen(): Boolean = try {
        val h = resources.displayMetrics.heightPixels
        val r = Rect()
        windows.any { w ->
            r.setEmpty(); w.getBoundsInScreen(r)
            r.height() > h * 0.3f && (w.root?.packageName?.toString() == "com.android.systemui")
        }
    } catch (_: Throwable) { false }

    private fun closeShade() {
        try {
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) else performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (_: Throwable) {}
    }

    /**
     * Lock gate: while the Yasmin lock is closed and the screen is on, any other app that comes to the front
     * (for example from a tapped notification) is covered at once and the lock screen opens. Notifications still show;
     * only entering needs the secret. Yasmin's own automation and a screen that is off are never blocked.
     */
    private fun gateIfLocked(event: AccessibilityEvent, pkg: String) {
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val cls = event.className?.toString() ?: ""
        if (pkg == packageName) { if (cls.contains("LockScreenActivity")) dropCover(); return }
        if (!LockStateManager.isLocked(this)) { dropCover(); return }
        // The lock screen is in front: the fingerprint prompt, the keyboard and the lock's own windows must never be "gated"
        // (that closed the prompt and relaunched the lock screen again and again).
        if (LockScreenActivity.visible) { dropCover(); return }
        if (SystemClock.uptimeMillis() < automationUntil) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm == null || !pm.isInteractive) return           // screen off: automation keeps working
        // Rate limit: at most one gate action per 1.5 s. The HOME press below produces a launcher event of its own; without this
        // it triggered another cover + HOME + relaunch, a loop that could freeze the phone until a reboot.
        val nowG = SystemClock.uptimeMillis()
        if (nowG - lastGateAt < 1_500L) return
        if (pkg == "com.android.systemui") {
            if (!shadeOpen()) return
            lastGateAt = nowG
            closeShade()
            YasminLockManager.guardFromService(this)
            return
        }
        // the keyboard (needed to type a password) and toasts are not "another app"
        if (cls.startsWith("android.inputmethodservice") || cls.startsWith("android.widget.Toast")) return
        val imeWindow = try { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.packageName?.toString() == pkg } } catch (_: Throwable) { false }
        if (imeWindow) return
        // Without "display over other apps" the lock screen cannot open from here: never cover the screen then (an opaque cover
        // with nothing behind it looked like a frozen phone). The lock still guards Yasmin's own screens.
        if (!YasminLockManager.canShowFromBackground(this)) return
        lastGateAt = nowG
        showCover()
        try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        YasminLockManager.guardFromService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!License.isUnlocked(applicationContext)) return   // no license: no phone control, no screen gate
        // a "windows changed" event often carries no package: that is how a pulled-down panel shows up
        val p = event.packageName?.toString() ?: (if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) "com.android.systemui" else return)
        try { gateIfLocked(event, p) } catch (_: Throwable) {}
        if (p == "com.android.systemui" || p == packageName) return   // clock ticks etc. must not look like "screen still changing"
        lastEventAt = SystemClock.uptimeMillis()
    }

    // ---- waiting helpers (call from a background thread, never the main thread) ----

    /** The active window root, retried for up to [waitMs]: right after an app switch it is briefly null. */
    fun root(waitMs: Long = 1500): AccessibilityNodeInfo? {
        val end = SystemClock.uptimeMillis() + waitMs
        while (true) {
            rootInActiveWindow?.let { return it }
            if (SystemClock.uptimeMillis() >= end) return null
            try { Thread.sleep(120) } catch (_: InterruptedException) { return null }
        }
    }

    /** Blocks until the UI has been quiet for [quietMs] (or [timeoutMs] passed), so we never read a half-loaded screen. */
    fun awaitStable(quietMs: Long = 350, timeoutMs: Long = 3500) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            try { Thread.sleep(80) } catch (_: InterruptedException) { return }
        }
    }

    // ---- gestures ----
    private fun gesture(p: Path, dur: Long): Boolean {
        busy()
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1))).build()
        val posted = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!posted) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok
    }

    fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 50)
    fun longPress(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 700)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down" -> swipe(w / 2, h * 0.72f, w / 2, h * 0.28f, 350)   // see content further down
            "up" -> swipe(w / 2, h * 0.28f, w / 2, h * 0.72f, 350)
            "left" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2, 350)
            "right" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2, 350)
            else -> false
        }
    }

    // ---- nodes ----
    fun labelOf(n: AccessibilityNodeInfo): String = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    /** Clicks the node itself or its nearest enabled clickable ancestor; falls back to a real tap on its centre. */
    fun clickNode(n: AccessibilityNodeInfo): Boolean {
        busy()
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && !(c.isClickable && c.isEnabled) && depth < 6) { c = c.parent; depth++ }
        if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r)
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /** Taps the best match: exact label first, then prefix, preferring clickable, short labels, never a text field. */
    fun clickText(text: String): Boolean {
        val root = root() ?: return false
        val q = text.trim()
        if (q.isEmpty()) return false
        val hits = root.findAccessibilityNodeInfosByText(q).filter { it.isVisibleToUser }
        if (hits.isEmpty()) return false
        fun score(n: AccessibilityNodeInfo): Int {
            val l = labelOf(n)
            var s = 0
            if (l.equals(q, true)) s += 100 else if (l.startsWith(q, true)) s += 40
            if (n.isClickable) s += 20
            if (n.isEditable) s -= 200
            s -= (l.length - q.length).coerceIn(0, 30)
            return s
        }
        val best = hits.maxByOrNull { score(it) } ?: return false
        return clickNode(best)
    }

    fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) { n.getChild(i)?.let { c -> findEditable(c)?.let { return it } } }
        return null
    }

    /** Replaces the whole content of a text field. Focuses it first, because many apps ignore SET_TEXT on an unfocused field. */
    fun setNodeText(n: AccessibilityNodeInfo, text: String): Boolean {
        if (!n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun typeText(text: String, replace: Boolean): Boolean {
        busy()
        val root = root() ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(root) ?: return false
        val cur = if (replace || n.isShowingHintText) "" else (n.text?.toString() ?: "")
        return setNodeText(n, cur + text)
    }

    fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val n = root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun key(name: String): Boolean {
        val n = name.lowercase()
        if (n != "lock_screen") busy()
        return when (n) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            "enter" -> pressEnter()
            else -> false
        }
    }

    fun openApp(name: String): String? {
        val pm = packageManager
        busy(12_000L)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
        val q = name.lowercase().trim()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
            ?: return null
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launch)
        return hit.loadLabel(pm).toString()
    }

    // ---- text for copy / paste between apps (password fields are never read) ----
    fun visibleText(max: Int): String {
        val root = root() ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo) {
            if (sb.length >= max) return
            if (n.isVisibleToUser && !n.isPassword) {
                val t = n.text?.toString()
                if (!t.isNullOrBlank()) sb.append(t).append('\n')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return sb.toString().take(max)
    }

    fun nodeTextFor(query: String): String? {
        if (query.isBlank()) return null
        val root = root() ?: return null
        val hit = root.findAccessibilityNodeInfosByText(query).firstOrNull { it.isVisibleToUser && !it.isPassword } ?: return null
        return (hit.text ?: hit.contentDescription)?.toString()
    }

    fun focusedText(): String? =
        root()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { !it.isPassword }?.text?.toString()

    /** Pastes the clipboard into the focused (or first) text field; falls back to typing the given text. */
    fun pasteIntoFocus(fallback: String?): Boolean {
        busy()
        val root = root() ?: return false
        val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        if (n.isPassword) return false
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        return fallback != null && typeText(fallback, false)
    }

    /** Compact text description of what is on screen, with tap coordinates (pixels). */
    fun dumpScreen(max: Int): String {
        val root = root(800) ?: return "No active window (screen may be locked or transitioning)"
        val dm = resources.displayMetrics
        val sb = StringBuilder("screen=${dm.widthPixels}x${dm.heightPixels} app=${root.packageName}\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (count >= max) return
            val label = (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.replace("\n", " ")?.take(100)
            if (n.isVisibleToUser && (label != null || n.isClickable || n.isEditable)) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    sb.append("[${r.centerX()},${r.centerY()}] ")
                    sb.append((n.className?.toString() ?: "").substringAfterLast('.'))
                    if (label != null) sb.append(" \"$label\"")
                    if (n.isClickable) sb.append(" click")
                    if (n.isEditable) sb.append(" edit")
                    if (n.isScrollable) sb.append(" scroll")
                    if (n.isChecked) sb.append(" checked")
                    sb.append('\n'); count++
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return sb.toString()
    }
}
