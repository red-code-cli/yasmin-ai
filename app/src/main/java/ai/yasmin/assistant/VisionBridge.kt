package ai.yasmin.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v2.41: lets the voice assistant start and stop LIVE camera vision by itself.
 *
 * "Yasmin, camera kholo": the model calls `look_camera`. The tool opens Camera Vision, the page binds the camera, switches Live Vision ON and
 * answers the tool as soon as the FIRST live frame has really reached the voice session. From then on a frame goes into the same session about
 * once per second (see [LiveVision]) until `stop_camera`, the END CAMERA button or leaving the page. The tool never waits for a written
 * description any more: the Live model looks at the frames itself.
 *
 * Android rules (not bypassed): an activity may not be started from the background on Android 10+. We try the launch; if the app is still not
 * in front after a moment, a notification "Tap to open Camera Vision" is shown instead and the tool waits for that tap. A locked phone is
 * reported at once. The camera is only ever used by the visible Camera Vision page (no hidden camera service).
 */
object VisionBridge {
    class ScanRequest(val id: Long, val front: Boolean, val question: String, val fromOutside: Boolean)

    /** The page being shown reacts to a new request at once (MainActivity opens the page, VisionPage starts Live Vision). */
    val request = MutableStateFlow<ScanRequest?>(null)

    /** Changes every time the assistant asks to stop the camera (`stop_camera`); VisionPage ends the session when it changes. */
    val stopSignal = MutableStateFlow(0L)

    /** Set by the voice service while a session runs: sends ONE jpeg frame into the live session. Returns false when it was not sent. */
    @Volatile var sendFrame: ((ByteArray) -> Boolean)? = null

    /** Set by the voice service: a short information text for the model (never spoken by the user). */
    @Volatile var sendNotice: ((String) -> Unit)? = null

    /** True while MainActivity is in the foreground (set from onResume / onPause). */
    @Volatile var uiResumed = false

    /** True when the current camera session was started while the app was in the background: ending it sends the app back (moveTaskToBack). */
    @Volatile var openedFromOutside = false

    private const val READY_WAIT_S = 22L          // launch / notification tap, permission, camera start, first frame
    private const val FRONT_WAIT_MS = 2500L       // how long a background launch gets before the "tap to open" notification appears
    private const val CH = "yasmin_vision"
    private const val NOTE_ID = 7741

    private val lock = Any()
    private var waitId = 0L
    private var readyLatch: CountDownLatch? = null
    private var readyBack: Boolean? = null
    private var failed: String? = null

    /** Page side: the first live frame of this request reached the voice session. */
    fun deliverReady(id: Long, back: Boolean) {
        synchronized(lock) {
            if (id != waitId) return
            readyBack = back
            readyLatch?.countDown()
        }
    }

    /** Page side: the request failed ([text] is the reason). */
    fun deliver(id: Long, text: String, ok: Boolean) {
        synchronized(lock) {
            if (id != waitId) return
            if (!ok) failed = text
            readyLatch?.countDown()
        }
    }

    /** Blocking (runs on the tool thread), at most about [READY_WAIT_S] s. Returns the tool result JSON. */
    fun scanBlocking(ctx: Context, a: JSONObject): JSONObject {
        if (Prefs.get(ctx, Prefs.KEY, "").isBlank()) return err("No Gemini API key is saved. Ask him to paste it in the menu under API Key.")
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return err("The phone is locked, so the camera cannot open. Ask him to unlock the phone first and then ask again.")
        if (LockStateManager.isLocked(ctx)) return err("YASMIN's own lock is closed, so the camera cannot open. Ask him to unlock Yasmin first and then ask again.")

        val id = System.nanoTime()
        val fromOutside = !uiResumed
        val req = ScanRequest(id, a.optString("camera").lowercase().startsWith("front"), a.optString("question").trim(), fromOutside)
        val latch = CountDownLatch(1)
        synchronized(lock) { waitId = id; readyLatch = latch; readyBack = null; failed = null }
        AppState.add("Camera: live look requested")
        if (fromOutside) openedFromOutside = true
        request.value = req
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Throwable) {}
        if (fromOutside) {
            // Android may silently refuse a launch from the background. Give it a moment; if the app is still not in front, ask for one tap.
            var waited = 0L
            while (!uiResumed && waited < FRONT_WAIT_MS) { try { Thread.sleep(100) } catch (_: InterruptedException) { break }; waited += 100 }
            if (!uiResumed) { showTapNotice(ctx); AppState.add("Camera: app is in the background, notification shown (tap to open)") }
        }
        val got = try { latch.await(READY_WAIT_S, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        val back: Boolean?; val why: String?
        synchronized(lock) { back = readyBack; why = failed }
        cancelTapNotice(ctx)
        finish(id)
        if (back == null) {
            if (fromOutside) openedFromOutside = false
            LiveVision.stopStreaming()
            AppState.add("Camera: no live picture" + (why?.let { " ($it)" } ?: ""))
            return err(why ?: (if (got) "The camera did not start." else
                "The camera screen did not open in time. Android only lets the app open the camera when it is in front: ask him to tap the Yasmin notification or open the app, then ask again. Also check that the camera permission is allowed."))
        }
        AppState.add("Camera: live vision ON (" + (if (back) "back" else "front") + ")")
        return JSONObject().put("ok", true).put("camera", if (back) "back" else "front").put("live_vision", "ON, a new picture reaches you about once a second")
            .put("note", "The camera is ON and its live picture is already reaching you. Do not announce that you are looking and do not narrate. " +
                "Answer his actual question directly from the newest picture, short and natural, in his language" +
                (if (req.question.isNotBlank()) ": he asked \"" + req.question + "\"" else ", or wait for his question") + ". " +
                (if (back) "Back camera: the left of the picture is his LEFT. " else "Front (selfie) camera, not mirrored: the left of the picture is his RIGHT. ") +
                "If you cannot tell the direction, ask one short question. Never invent what is not visible. Keep the camera on until he asks to stop, then call stop_camera.")
    }

    /** Tool `stop_camera`. */
    fun requestStop(): JSONObject {
        stopSignal.value = System.nanoTime()
        AppState.add("Camera: stop requested by voice")
        return JSONObject().put("ok", true).put("camera", "off")
            .put("note", "The camera and the picture stream are being switched off. You cannot see anything any more: do not describe the old picture. Confirm in one short sentence.")
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m)

    private fun finish(id: Long) {
        if (request.value?.id == id) request.value = null
        synchronized(lock) { if (waitId == id) { waitId = 0L; readyLatch = null } }
    }

    private fun showTapNotice(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH, "Yasmin camera", NotificationManager.IMPORTANCE_HIGH))
            val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            val pi = PendingIntent.getActivity(ctx, NOTE_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Yasmin wants to use the camera").setContentText("Tap to open Camera Vision")
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(NOTE_ID, n)
        } catch (_: Throwable) {}
    }

    private fun cancelTapNotice(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) } catch (_: Throwable) {}
    }
}
