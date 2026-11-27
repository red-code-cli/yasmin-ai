package ai.yasmin.assistant

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.41: LIVE VISION state. Camera Vision is no longer "one picture per question": while the user has Live Vision ON, the camera page sends a
 * small JPEG frame to the running Gemini Live session at a fixed interval (default 1 per second, the rate the Live API expects), so the model
 * answers follow-up questions from what it sees NOW. Nothing is sent while [streaming] is false.
 *
 * Protocol (Gemini Live API, WebSocket): each frame is one image sent as `realtimeInput.video` with mimeType image/jpeg (see GeminiLive.sendFrame).
 * The Live API takes discrete frames at about 1 FPS; 768 px on the long side is the recommended size.
 *
 * Camera "generation": every camera (re)bind gets a new number. A frame is only accepted when its number is still the current one, so a frame
 * from the previous lens can never be sent after a switch, and "ready" means a real frame of the CURRENT binding arrived (no fixed delay).
 */
object LiveVision {
    enum class Cam { OFF, STARTING, ACTIVE, ERROR }

    const val MIN_INTERVAL_MS = 1000L
    const val MAX_INTERVAL_MS = 10_000L
    const val DEFAULT_INTERVAL_MS = 1000L
    const val FRAME_MAX_SIDE = 768
    const val FRAME_QUALITY = 70
    /** A frame is skipped (not queued) while the socket still holds this many unsent bytes: a slow network must not build a backlog. */
    const val MAX_QUEUED_BYTES = 600_000L
    const val KEY_INTERVAL = "vision_interval_ms"

    val cam = MutableStateFlow(Cam.OFF)
    val camMessage = MutableStateFlow("")
    /** Lens of the frames that are really arriving (true = back camera). */
    val facingBack = MutableStateFlow(true)
    /** Frames that the live session accepted since Live Vision was switched on. */
    val framesSent = MutableStateFlow(0)
    /** True only while frames may be sent to the live session. */
    val streaming = MutableStateFlow(false)

    @Volatile var intervalMs: Long = DEFAULT_INTERVAL_MS
    /** uptimeMillis of the last send attempt (successful or not): throttles the converting work too. */
    @Volatile var lastAttemptAt = 0L
    @Volatile private var announceFirst = false
    @Volatile private var stillCb: ((Bitmap) -> Unit)? = null

    private val gen = AtomicInteger(0)

    // ------------------------------------------------------------------ pure helpers (unit tested)

    fun clampInterval(ms: Long): Long = ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /** True when a new frame may be sent: the first one at once, then one per [interval]. */
    fun due(now: Long, last: Long, interval: Long): Boolean = last == 0L || now - last >= interval

    fun loadInterval(c: Context): Long = clampInterval(Prefs.getInt(c, KEY_INTERVAL, DEFAULT_INTERVAL_MS.toInt()).toLong())
    fun saveInterval(c: Context, ms: Long) { Prefs.putInt(c, KEY_INTERVAL, clampInterval(ms).toInt()); intervalMs = clampInterval(ms) }

    // ------------------------------------------------------------------ camera binding generation

    /** A camera is being (re)bound: everything from the earlier binding is now stale. */
    @Synchronized fun newGeneration(): Int {
        cam.value = Cam.STARTING; camMessage.value = ""
        return gen.incrementAndGet()
    }

    @Synchronized fun invalidate() { gen.incrementAndGet() }

    fun isCurrent(g: Int): Boolean = gen.get() == g

    /** Called for every frame the camera produces: the first frame of the CURRENT binding is what makes the camera "ready". */
    @Synchronized fun markActive(g: Int, back: Boolean) {
        if (gen.get() != g) return
        if (cam.value != Cam.ACTIVE || facingBack.value != back) {
            facingBack.value = back; cam.value = Cam.ACTIVE; camMessage.value = ""
        }
    }

    @Synchronized fun fail(g: Int, msg: String) {
        if (gen.get() != g) return
        cam.value = Cam.ERROR; camMessage.value = msg
    }

    /** Watchdog: the camera was bound but never produced a frame. */
    @Synchronized fun failStuck(msg: String) {
        if (cam.value == Cam.STARTING) { cam.value = Cam.ERROR; camMessage.value = msg }
    }

    // ------------------------------------------------------------------ streaming on / off

    @Synchronized fun startStreaming(announce: Boolean) {
        framesSent.value = 0; lastAttemptAt = 0L; announceFirst = announce
        streaming.value = true
    }

    @Synchronized fun stopStreaming() {
        streaming.value = false; stillCb = null; announceFirst = false
    }

    /** Page left / session ended: no more frames, camera state reset. Tells the model once if it had been seeing. */
    fun release() {
        val was = streaming.value
        stopStreaming()
        invalidate()
        cam.value = Cam.OFF; camMessage.value = ""
        if (was) notice(NOTICE_OFF)
    }

    /** One frame to the live session. Returns true only if the session accepted it (connected, not asleep, socket not backed up). */
    fun deliverFrame(jpeg: ByteArray): Boolean {
        val send = VisionBridge.sendFrame ?: return false
        val ok = try { send(jpeg) } catch (_: Throwable) { false }
        if (ok) {
            val first = framesSent.value == 0
            framesSent.value = framesSent.value + 1
            if (first && announceFirst) { announceFirst = false; notice(cameraNote(facingBack.value)) }
        }
        return ok
    }

    // ------------------------------------------------------------------ one-shot still (the "WHAT DO YOU SEE?" button)

    @Synchronized fun requestStill(cb: (Bitmap) -> Unit) { stillCb = cb }
    /** Returns true if a request was still waiting (it is cancelled). */
    @Synchronized fun cancelStill(): Boolean { val had = stillCb != null; stillCb = null; return had }
    @Synchronized fun takeStill(): ((Bitmap) -> Unit)? { val c = stillCb; stillCb = null; return c }

    // ------------------------------------------------------------------ notes to the model (information only, no answer wanted)

    fun notice(text: String) { try { VisionBridge.sendNotice?.invoke(text) } catch (_: Throwable) {} }

    fun cameraNote(back: Boolean): String =
        "[SYSTEM NOTICE, not spoken by the user] Live camera is ON: " +
            (if (back) "BACK camera, the left of the picture is his LEFT." else "FRONT (selfie) camera, the picture is not mirrored, so the left of the picture is his RIGHT.") +
            " A new picture reaches you about once a second. This is information only: do not answer it and do not speak."

    const val NOTICE_OFF = "[SYSTEM NOTICE, not spoken by the user] The camera is now OFF. You cannot see anything any more: do not describe the old picture. This is information only: do not answer it and do not speak."
    const val NOTICE_PAUSED = "[SYSTEM NOTICE, not spoken by the user] The camera is paused because the app went to the background. No new pictures arrive until he comes back. This is information only: do not answer it and do not speak."
    const val NOTICE_RESUMED = "[SYSTEM NOTICE, not spoken by the user] The camera is back on and new pictures arrive again. This is information only: do not answer it and do not speak."
}
