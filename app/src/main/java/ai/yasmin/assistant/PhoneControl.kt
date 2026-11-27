package ai.yasmin.assistant

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Phone controls that check their own result. Every function reads the state back from Android after acting
 * and returns ok=true only when the change is really there; otherwise ok=false with the reason and a fix.
 * Call these from a background thread (they wait briefly for Android to react).
 */
object PhoneControl {
    private fun err(msg: String, fix: String? = null): JSONObject =
        JSONObject().put("ok", false).put("error", msg).also { if (fix != null) it.put("fix", fix) }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ------------------------------------------------------------------------------------------ accessibility state

    /** True when Yasmin is switched on in Android's accessibility list (Android may still have it disconnected). */
    fun accEnabledInSettings(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val full = "${ctx.packageName}/${YasminAccessibilityService::class.java.name}"
        val short = "${ctx.packageName}/.${YasminAccessibilityService::class.java.simpleName}"
        return s.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    /** null when the accessibility service is connected and usable; otherwise the exact reason it is not. */
    fun accProblem(ctx: Context): String? = when {
        YasminAccessibilityService.inst != null -> null
        accEnabledInSettings(ctx) ->
            "The Yasmin accessibility service is switched on but Android has not connected it (it was stopped). Turn Yasmin off and on again in Settings > Accessibility."
        else ->
            "Phone control is OFF. Enable Yasmin in Settings > Accessibility. On Android 13+ open App info > three-dot menu > Allow restricted settings first."
    }

    /** Waits until the foreground app's package satisfies [ok]. Returns (matched, last package seen). Needs the accessibility service. */
    private fun awaitForeground(ms: Long, ok: (String) -> Boolean): Pair<Boolean, String?> {
        val acc = YasminAccessibilityService.inst ?: return false to null
        val end = SystemClock.uptimeMillis() + ms
        var last: String? = null
        while (SystemClock.uptimeMillis() < end) {
            last = try { acc.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            if (last != null && ok(last)) return true to last
            sleep(150)
        }
        return false to last
    }

    // ------------------------------------------------------------------------------------------ launching apps / links

    private class AppHit(val pkg: String, val label: String, val component: ComponentName)

    private fun findApp(ctx: Context, name: String): AppHit? {
        val q = name.lowercase().trim()
        if (q.isEmpty()) return null
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        fun label(r: ResolveInfo) = r.loadLabel(pm).toString().lowercase()
        val hit = apps.firstOrNull { label(it) == q }
            ?: apps.firstOrNull { label(it).startsWith(q) }
            ?: apps.firstOrNull { label(it).contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q) }
            ?: return null
        val ai = hit.activityInfo
        return AppHit(ai.packageName, hit.loadLabel(pm).toString(), ComponentName(ai.packageName, ai.name))
    }

    /** Starts [intent], then checks that the expected app really came to the front. Falls back to root [rootCmd] when blocked. */
    private fun launch(ctx: Context, intent: Intent, what: String, rootCmd: String?, expect: (String) -> Boolean): JSONObject {
        var startError: String? = null
        try { ctx.startActivity(intent) } catch (e: Exception) { startError = e.message ?: e.javaClass.simpleName }
        var method = "startActivity"
        val acc = YasminAccessibilityService.inst
        var verified: Boolean? = null
        var fg: String? = null
        if (acc != null && startError == null) { val r = awaitForeground(2500, expect); verified = r.first; fg = r.second }

        val failed = startError != null || verified == false
        if (failed && rootCmd != null && Root.available()) {
            method = "root"
            val (code, out) = Root.exec(rootCmd, 8000)
            val rootOk = code == 0 && !out.contains("Error", ignoreCase = true)
            if (acc != null) { val r = awaitForeground(2500, expect); verified = r.first; fg = r.second }
            else verified = if (rootOk) null else false
            if (!rootOk && acc == null) startError = "root start failed: " + out.trim().take(160)
            else if (rootOk) startError = null
        }

        return when {
            verified == true -> JSONObject().put("ok", true).put("opened", what).put("verified", true).put("method", method)
            startError != null && acc == null && method != "root" ->
                err("Android refused to open $what: $startError",
                    "If Android blocks starting apps from the background, open Yasmin's screen first, allow 'Display over other apps', or grant root.")
            verified == false || startError != null ->
                err("Asked Android to open $what but it did not come to the front" + (fg?.let { " (still showing $it)" } ?: "") +
                        (startError?.let { ": $it" } ?: "") + ".",
                    "Android 10+ can block apps from opening other apps while they are in the background. Keep Yasmin's screen open, allow 'Display over other apps', or use a rooted phone.")
            else -> JSONObject().put("ok", true).put("opened", what).put("verified", false).put("method", method)
                .put("note", "Start was requested but cannot be confirmed because the accessibility service is off. Say it was requested, not that it opened.")
        }
    }

    fun openApp(ctx: Context, name: String): JSONObject {
        val app = findApp(ctx, name) ?: return err("No installed app matches \"$name\".", "Ask for the exact app name, or use run_root with: pm list packages -3")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val r = launch(ctx, intent, app.label, "am start -n " + app.component.flattenToShortString()) { it == app.pkg }
        return r.put("package", app.pkg)
    }

    fun openUrl(ctx: Context, raw: String): JSONObject {
        var url = raw.trim()
        if (url.isEmpty()) return err("The link is empty.")
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url = "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val handler = intent.resolveActivity(ctx.packageManager)?.packageName
            ?: return err("No app on this phone can open $url.")
        val safe = Regex("^[A-Za-z0-9:/?&=%#._~+,;@!*()\\-]+$").matches(url)
        val rootCmd = if (safe) "am start -a android.intent.action.VIEW -d '$url'" else null
        // "android" is the system app chooser, shown when several apps can open the link
        return launch(ctx, intent, url, rootCmd) { it == handler || it == "android" }
    }

    @Suppress("DEPRECATION")
    fun call(ctx: Context, number: String): JSONObject {
        val n = number.trim()
        if (!Regex("^[+0-9*#()\\-\\s]{3,}$").matches(n)) return err("\"$n\" does not look like a phone number.")
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", n, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: Exception) {
            return err("Android refused to start the call: ${e.message ?: e.javaClass.simpleName}",
                "Allow the Phone permission for Yasmin in Settings > Apps > Yasmin > Permissions.")
        }
        if (!canCall) return JSONObject().put("ok", true).put("placed", false)
            .put("note", "The Phone permission is missing, so only the dialer was opened with the number filled in. The call is NOT placed until the user presses the green call button.")
            .put("fix", "Allow the Phone permission for Yasmin (Permissions page).")
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("ok", true).put("placed", JSONObject.NULL).put("verified", false)
                .put("note", "Call was requested but cannot be confirmed (Phone state permission missing). Say it was requested, not that it connected.")
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val end = SystemClock.uptimeMillis() + 4000
        var state = TelephonyManager.CALL_STATE_IDLE
        while (SystemClock.uptimeMillis() < end) {
            state = try { tm.callState } catch (_: SecurityException) { return JSONObject().put("ok", true).put("verified", false).put("note", "Call requested, state unreadable.") }
            if (state != TelephonyManager.CALL_STATE_IDLE) break
            sleep(200)
        }
        return if (state != TelephonyManager.CALL_STATE_IDLE) JSONObject().put("ok", true).put("placed", true).put("verified", true)
        else err("Android did not start the call (the phone is still idle).", "Check the SIM and signal, and that no other app blocks calls.")
    }

    // ------------------------------------------------------------------------------------------ volume

    private val STREAMS = mapOf(
        "media" to AudioManager.STREAM_MUSIC, "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING, "ringtone" to AudioManager.STREAM_RING,
        "notification" to AudioManager.STREAM_NOTIFICATION, "alarm" to AudioManager.STREAM_ALARM,
        "system" to AudioManager.STREAM_SYSTEM,
        "call" to AudioManager.STREAM_VOICE_CALL, "voice" to AudioManager.STREAM_VOICE_CALL, "yasmin" to AudioManager.STREAM_VOICE_CALL
    )

    fun setVolume(ctx: Context, a: JSONObject): JSONObject {
        val name = a.optString("stream", "media").lowercase().trim().ifBlank { "media" }
        val s = STREAMS[name] ?: return err("Unknown volume \"$name\".", "Use: media, ring, notification, alarm, system, call or yasmin.")
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am.isVolumeFixed) return err("This phone has a fixed volume; Android does not allow changing it.")
        val max = am.getStreamMaxVolume(s)
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(s) else if (s == AudioManager.STREAM_VOICE_CALL) 1 else 0
        val before = am.getStreamVolume(s)
        val direction = a.optString("direction", "").lowercase().trim()
        val hasPercent = a.has("percent") && !a.isNull("percent")
        val dndFix = "Allow 'Do Not Disturb access' for Yasmin (Permissions page), or grant root. Ring, notification and system volume are protected while Do Not Disturb is on."

        if (direction == "mute" || direction == "unmute") {
            val mute = direction == "mute"
            try {
                am.adjustStreamVolume(s, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            } catch (e: SecurityException) { return err("Android refused to $direction the $name volume: ${e.message}", dndFix) }
            sleep(200)
            val muted = am.isStreamMute(s)
            return if (muted == mute) JSONObject().put("ok", true).put("stream", name).put("muted", muted).put("volume", am.getStreamVolume(s))
            else err("Android did not $direction the $name volume (muted is still $muted).", "Try set_volume with percent 0, or check Do Not Disturb access.")
        }

        val step = maxOf(1, (max * 0.1).roundToInt())
        val target = when {
            hasPercent -> (a.getDouble("percent").coerceIn(0.0, 100.0) / 100.0 * max).roundToInt()
            direction == "up" -> before + step
            direction == "down" -> before - step
            direction == "max" -> max
            direction == "min" -> min
            else -> return err("Say how to change it: percent 0-100, or direction up, down, max, min, mute or unmute.")
        }.coerceIn(min, max)

        var method = "AudioManager"
        if (target != before) {
            try {
                am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
            } catch (e: SecurityException) {
                if (Root.available()) { Root.exec("cmd media_session volume --stream $s --set $target", 6000); method = "root" }
                else return err("Android refused to change the $name volume: ${e.message}", dndFix)
            }
            sleep(200)
        }
        val after = am.getStreamVolume(s)
        val res = JSONObject().put("stream", name).put("before", before).put("after", after).put("max", max)
            .put("percent_after", if (max > 0) (after * 100.0 / max).roundToInt() else 0).put("method", method)
        return if (after == target) res.put("ok", true)
        else res.put("ok", false).put("error", "Android did not apply it: the $name volume is $after of $max, not $target.")
            .put("fix", "Possible causes: Do Not Disturb protection, a headphone safe-volume limit, or a Bluetooth device controlling volume. Check Do Not Disturb access on the Permissions page.")
    }

    // ------------------------------------------------------------------------------------------ brightness

    fun setBrightness(ctx: Context, a: JSONObject): JSONObject {
        val wantAuto = a.optBoolean("auto", false)
        val hasPercent = a.has("percent") && !a.isNull("percent")
        if (!wantAuto && !hasPercent) return err("Give percent 0-100, or auto true for automatic brightness.")
        val mode = if (wantAuto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        // Settings.System.SCREEN_BRIGHTNESS is 0-255; 0 can black out some screens, so the lowest level used is 1
        val value = if (!wantAuto) (a.getDouble("percent").coerceIn(0.0, 100.0) / 100.0 * 255).roundToInt().coerceIn(1, 255) else -1
        val cr = ctx.contentResolver
        var method = "Settings.System"
        var problem: String? = null
        if (Settings.System.canWrite(ctx)) {
            try {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
                if (!wantAuto) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
            } catch (e: Exception) { problem = e.message ?: e.javaClass.simpleName }
        } else problem = "Yasmin is not allowed to modify system settings."
        if (problem != null) {
            if (!Root.available())
                return err(problem, "Allow 'Modify system settings' for Yasmin (Permissions page, or Settings > Apps > Yasmin > Modify system settings), or grant root.")
            method = "root"
            Root.exec("settings put system screen_brightness_mode $mode" + if (!wantAuto) "; settings put system screen_brightness $value" else "", 6000)
            sleep(200)
        }
        val gotMode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
        val gotValue = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val ok = gotMode == mode && (wantAuto || gotValue == value)
        val res = JSONObject().put("ok", ok).put("method", method).put("auto", gotMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            .put("level", gotValue).put("percent", (gotValue * 100.0 / 255).roundToInt())
        return if (ok) res.put("note", "The system brightness setting was changed and read back. Some phones fine-tune the real light output.")
        else res.put("error", "Android did not keep the change (mode $gotMode, level $gotValue).")
    }

    // ------------------------------------------------------------------------------------------ lock screen

    fun lockScreen(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        fun done() = !pm.isInteractive || km.isKeyguardLocked
        if (done()) return JSONObject().put("ok", true).put("screen_off", !pm.isInteractive).put("locked", km.isKeyguardLocked).put("note", "It was already locked or off.")
        var method = ""
        val acc = YasminAccessibilityService.inst
        if (acc != null && Build.VERSION.SDK_INT >= 28 && acc.key("lock_screen")) method = "accessibility"
        else if (Root.available()) { Root.exec("input keyevent 223", 5000); method = "root" }   // 223 = KEYCODE_SLEEP
        else {
            val why = when {
                Build.VERSION.SDK_INT < 28 -> "Android 8 and 8.1 have no accessibility lock action, so locking needs root."
                else -> accProblem(ctx) ?: "Android did not accept the lock request."
            }
            return err("Could not lock the screen. $why", "Enable the Yasmin accessibility service, or grant root.")
        }
        val end = SystemClock.uptimeMillis() + 3000
        while (SystemClock.uptimeMillis() < end && !done()) sleep(150)
        val off = !pm.isInteractive; val locked = km.isKeyguardLocked
        return if (off || locked) JSONObject().put("ok", true).put("method", method).put("screen_off", off).put("locked", locked)
            .put("note", "The phone is locked now: nothing on screen can be controlled until the user unlocks it." +
                    if (off && !locked) " The screen is off; Android may wait a few seconds (its 'lock after' setting) before it asks for the PIN." else "")
        else err("The lock request ($method) was sent but the screen is still on and unlocked.", "Some phones block this; try again, or use the power button.")
    }

    // ------------------------------------------------------------------------------------------ status of every requirement

    fun status(ctx: Context, rootKnown: Boolean? = null): JSONObject {
        val sdk = Build.VERSION.SDK_INT
        val accOn = accEnabledInSettings(ctx)
        val accConn = YasminAccessibilityService.inst != null
        val root = rootKnown ?: Root.available()   // reuses a result that is already known; no forced `su` call
        val canWrite = Settings.System.canWrite(ctx)
        val dnd = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        val overlay = Settings.canDrawOverlays(ctx)
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val listener = (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains("${ctx.packageName}/")
        val battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

        val problems = JSONArray()
        accProblem(ctx)?.let { problems.put(it) }
        if (!canWrite && !root) problems.put("Brightness: Yasmin may not 'Modify system settings' yet (Permissions page).")
        if (!dnd) problems.put("Ring/notification volume can fail while Do Not Disturb is on: allow 'Do Not Disturb access' (Permissions page).")
        if (!overlay) problems.put("'Display over other apps' is off; Android may block opening apps from the background.")
        if (!battery) problems.put("Battery optimisation is on for Yasmin; Android may stop it in the background.")
        if (!has(Manifest.permission.CALL_PHONE)) problems.put("Calls: Phone permission missing, only the dialer opens.")
        if (!has(Manifest.permission.READ_PHONE_STATE)) problems.put("Calls cannot be confirmed without the Phone state permission.")
        if (!has(Manifest.permission.READ_CONTACTS)) problems.put("find_contact: Contacts permission missing.")
        if (!has(Manifest.permission.SEND_SMS)) problems.put("send_sms: SMS permission missing.")
        if (!listener) problems.put("Reading WhatsApp/Messenger notifications: Notification access is off.")
        if (!root) problems.put("Root is not available: no run_root, no setup_self; features that need root cannot work.")

        val controls = JSONObject()
            .put("screen_control_tap_swipe_type_keys", accConn)
            .put("open_app_open_url", true)
            .put("open_app_confirmation", accConn)
            .put("volume", true)
            .put("ring_notification_volume_in_dnd", dnd || root)
            .put("brightness", canWrite || root)
            .put("lock_screen", (accConn && sdk >= 28) || root)
            .put("call_placed_directly", has(Manifest.permission.CALL_PHONE))
            .put("root_commands", root)

        return JSONObject().put("android_sdk", sdk)
            .put("accessibility_enabled_in_settings", accOn).put("accessibility_connected", accConn)
            .put("root", root).put("modify_system_settings", canWrite).put("do_not_disturb_access", dnd)
            .put("display_over_apps", overlay).put("notification_access", listener).put("battery_unrestricted", battery)
            .put("controls_available", controls).put("problems", problems)
            .put("cannot_be_supported_without_user", "Accessibility, Notification access, Modify system settings, Do Not Disturb access and Display over other apps can only be turned on by the user in Android settings (or by root via setup_self).")
    }
}
