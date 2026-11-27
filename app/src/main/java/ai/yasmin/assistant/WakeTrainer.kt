package ai.yasmin.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** v2.29: records ONE "Hey Yasmin" sample for the offline wake check. Needs the microphone, so the assistant must not be running. */
object WakeTrainer {
    @Volatile var busy = false
        private set

    /** [done] runs on the main thread: (true, "...") when a sample was added, (false, reason) otherwise. */
    fun record(ctx: Context, done: (Boolean, String) -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun finish(ok: Boolean, msg: String) { busy = false; main.post { done(ok, msg) } }
        busy = true
        thread(name = "yasmin-wake-train", isDaemon = true) {
            val r = SleepMicListener.openRecord()
            if (r == null) { finish(false, "Microphone is busy or the permission is missing."); return@thread }
            val seg = UtteranceSegmenter()
            val frame = ByteArray(UtteranceSegmenter.FRAME_BYTES)
            var utt: ByteArray? = null
            try {
                r.startRecording()
                val end = System.currentTimeMillis() + 12_000
                var have = 0
                while (utt == null && System.currentTimeMillis() < end) {
                    val n = r.read(frame, have, frame.size - have)
                    if (n < 0) break
                    have += n
                    if (have < frame.size) continue
                    have = 0
                    utt = seg.feed(frame)
                }
            } catch (_: Exception) {
            } finally {
                try { r.stop() } catch (_: Exception) {}
                try { r.release() } catch (_: Exception) {}
                seg.reset(); java.util.Arrays.fill(frame, 0.toByte())
            }
            val u = utt
            if (u == null) { finish(false, "I did not hear a clear \"Hey Yasmin\". Try again a little louder."); return@thread }
            val ok = try { WakeTemplates.add(app, u) } catch (_: Exception) { false }
            java.util.Arrays.fill(u, 0.toByte())      // the recording is erased at once, only the feature numbers stay
            if (ok) finish(true, "Sample saved (${WakeTemplates.count(app)}).") else finish(false, "That sound was too short or unclear. Try again.")
        }
    }
}
