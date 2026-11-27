package ai.yasmin.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Anything that can own the microphone while Yasmin sleeps and report a possible wake phrase. */
interface WakeSource {
    fun start()
    fun stop()
}

/**
 * v2.27: keeps the microphone ON the whole time Yasmin sleeps (Android's SpeechRecognizer kept closing / restarting and
 * missed the wake phrase). The assistant itself stays off: nothing is streamed to the live session.
 *
 * Security layer: every short sound is held only in memory and checked once, with the strict phrase list in
 * [WakeCommandParser] (the whole utterance must be exactly "Hey/Okay Yasmin/Yasmin"). If it is the right phrase -> wake up.
 * If it is anything else -> the audio is overwritten with zeros and forgotten at once. Nothing is stored on disk or logged.
 * The check is a single short transcription request to Gemini with the saved API key (only sounds of 0.3 - 3 s are ever sent).
 */
class SleepMicListener(
    private val ctx: Context,
    private val onResult: (List<String>, FloatArray?) -> Unit,
    private val onProblem: (String, Boolean) -> Unit
) : WakeSource {
    /** Short live text for the status line (mic level, last check result). Set by the service; called on the main thread. */
    var onInfo: ((String) -> Unit)? = null
    private var sounds = 0
    private var lastResult = "waiting"
    @Volatile private var lvl = 0
    private fun info() { val f = onInfo ?: return; main.post { if (run.get()) f("Sleeping: say \"Hey Yasmin\" | mic $lvl | sounds $sounds | $lastResult") } }
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()   // a slow check must not block the next "Hey Yasmin"
    private val run = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    @Volatile private var rec: AudioRecord? = null
    private var worker: Thread? = null
    private var failures = 0
    private var prevHeard = ""
    private var prevAt = 0L
    @Volatile private var lastHttp = 0
    private val recent = ArrayDeque<Long>()      // times of the last checks: at most 20 per minute

    companion object {
        /** Offline wake needs trained samples (Preferences > Wake word training); otherwise the online check needs an API key. */
        fun available(ctx: Context): Boolean = WakeTemplates.ready(ctx) || Prefs.get(ctx, Prefs.KEY, "").trim().isNotEmpty()

        /** Opens the microphone for wake use (also used by the training screen). null = permission missing / mic busy. */
        @SuppressLint("MissingPermission")
        fun openRecord(): AudioRecord? {
            val minIn = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            fun make(src: Int): AudioRecord? = try {
                val r = AudioRecord(src, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200) * 4)
                if (r.state == AudioRecord.STATE_INITIALIZED) r else { try { r.release() } catch (_: Exception) {}; null }
            } catch (e: Exception) { null }
            val r = make(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: make(MediaRecorder.AudioSource.MIC) ?: return null
            try { if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(r.audioSessionId)?.setEnabled(true) } catch (_: Exception) {}
            try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(r.audioSessionId)?.setEnabled(true) } catch (_: Exception) {}
            return r
        }
        private const val PROMPT =
            "This is a short voice clip (about 1 to 3 seconds) from a phone. Write down the words you hear, nothing else. " +
            "Bengali words in Bengali script, English words in English. If it is silence, noise, music or you hear no clear words, write NONE. " +
            "Context: the assistant in this app is called Yasmin (also spelled Yasmin). If you hear that name or something that sounds very close to it " +
            "(Yesmin, Yasmeen, Yasmine, Jasmin, Jasmine, Yashmin, ইয়াসমিন, ইয়াসমীন, যাসমিন, यास्मिन), write it as Yasmin. If you hear a greeting like hey / hay / hei / hi / hello / okay / ok / হে / হেই / হাই / ওকে, write it as Hey or Okay. " +
            "The phrase is almost always just \"Hey Yasmin\" or \"Okay Yasmin\": if the sound is those two words, write exactly that. A single word like Hey alone: write just Hey. " +
            "Never add words that were not said."
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!run.compareAndSet(false, true)) return
        // Open the secure connection to Google now, so the first "Hey Yasmin" does not pay for the handshake (~0.5 s).
        thread(name = "yasmin-wake-warm", isDaemon = true) {
            try { http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/").head().build()).execute().close() } catch (_: Exception) {}
        }
        worker = thread(name = "yasmin-sleep-mic", isDaemon = true) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            // v2.31: right after the assistant's own microphone was released the system may still report it as busy for a moment.
            // That used to count as a fatal problem and woke Yasmin up again at once ("it never really sleeps"). Try again for ~4 s.
            var r: AudioRecord? = null
            for (i in 0 until 10) {
                if (!run.get()) return@thread
                r = openRecord()
                if (r != null) {
                    try { r.startRecording(); if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) break } catch (_: Exception) {}
                    try { r.release() } catch (_: Exception) {}
                    r = null
                }
                try { Thread.sleep(400) } catch (_: InterruptedException) { return@thread }
            }
            if (r == null) { run.set(false); main.post { onProblem("Microphone permission is missing or the microphone is busy.", true) }; return@thread }
            rec = r
            if (!run.get()) { try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {}; rec = null; return@thread }   // stopped while opening
            val seg = UtteranceSegmenter(22)   // v2.40: 0.44 s of quiet ends "Hey Yasmin" (was 0.3 s): a short breath between "Hey" and "Yasmin" no longer cuts the phrase in two
            val frame = ByteArray(UtteranceSegmenter.FRAME_BYTES)
            var have = 0
            var frames = 0
            while (run.get()) {
                val n = try { r.read(frame, have, frame.size - have) } catch (_: Exception) { -1 }
                if (n < 0) break
                have += n
                if (have < frame.size) continue
                have = 0
                val utt = seg.feed(frame)
                lvl = seg.lastLevel.toInt()
                if (++frames % 100 == 0) info()      // every 2 s: shows the mic is really open
                if (utt == null) continue
                sounds++; lastResult = "checking"; info()
                verify(utt)
            }
            seg.reset()
            java.util.Arrays.fill(frame, 0.toByte())
        }
    }

    override fun stop() {
        run.set(false)
        val r = rec; rec = null
        if (r != null) {
            try { r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        try { worker?.join(500) } catch (_: Exception) {}
        worker = null
    }

    /** Called on the mic thread with one finished utterance; hands it to the checker and wipes it afterwards, whatever the answer. */
    private fun verify(pcm: ByteArray) {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && now - recent.first() > 60_000) recent.removeFirst()
        if (checking.get() || recent.size >= 20) { java.util.Arrays.fill(pcm, 0.toByte()); lastResult = "skipped (busy / limit)"; info(); return }   // busy / too many: forget it
        if (WakeTemplates.ready(ctx)) {
            // OFFLINE: compared with the owner's trained samples, no internet. The recording is wiped right after, whatever the answer.
            val res = try { WakeTemplates.judge(ctx, pcm) } catch (_: Exception) { null }
            java.util.Arrays.fill(pcm, 0.toByte())
            val ok = res != null && res.ok
            lastResult = if (res == null || res.score == Double.MAX_VALUE) "not the phrase (too short / different)"
                else (if (ok) "WAKE" else "not the phrase") + " (score %.2f, limit %.2f)".format(res.score, res.limit)
            info()
            if (ok && run.get()) main.post { if (run.get()) onResult(listOf("Hey Yasmin"), null) }
            return
        }
        recent.addLast(now)
        checking.set(true)
        thread(name = "yasmin-wake-check", isDaemon = true) {
            var heard: String? = null
            try { heard = transcribe(pcm) } catch (_: Exception) { }
            finally {
                java.util.Arrays.fill(pcm, 0.toByte())      // security layer: the recording is erased in every case
                checking.set(false)
            }
            val text = heard
            if (text == null) {
                lastResult = "check failed" + (if (lastHttp != 0) " (HTTP $lastHttp)" else " (internet?)"); info()
                if (++failures == 3) main.post { onProblem("Wake check failed 3 times: " + lastResult, false) }
                return@thread
            }
            failures = 0
            // Only the strict phrase list decides. The text is shown once on the status line only (screen, never stored).
            // v2.40: if \"Hey\" and \"Yasmin\" came as two separate sounds, the two texts together still count (within 3.5 s)
            val clean = if (text.trim().equals("NONE", true)) "" else text
            val combo = if (prevHeard.isNotBlank() && System.currentTimeMillis() - prevAt < 3500L) prevHeard + " " + clean else clean
            val ok = WakeCommandParser.isWakeLoose(clean) || WakeCommandParser.isWakeLoose(combo)
            prevHeard = clean.take(40); prevAt = System.currentTimeMillis()
            lastResult = if (ok) "WAKE" else "not the phrase: " + text.replace(Regex("\\s+"), " ").take(30)
            info()
            if (ok && run.get()) main.post { if (run.get()) onResult(listOf("Hey Yasmin"), null) }   // the service compares with the strict list, so pass the canonical phrase
        }
    }

    /** Raises a quiet recording to a clear level (peak about 70 %), in place. */
    private fun normalize(pcm: ByteArray) {
        var peak = 1; var i = 0
        while (i + 1 < pcm.size) { val v = Math.abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()); if (v > peak) peak = v; i += 2 }
        val g = (23000.0 / peak).coerceIn(1.0, 30.0)
        if (g < 1.2) return
        i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            val o = (v * g).toInt().coerceIn(-32768, 32767)
            pcm[i] = o.toByte(); pcm[i + 1] = (o shr 8).toByte(); i += 2
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val n = pcm.size; val out = ByteArray(44 + n)
        fun i32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); i32(4, 36 + n); "WAVEfmt ".toByteArray().copyInto(out, 8)
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, 16000); i32(28, 32000); i16(32, 2); i16(34, 16)
        "data".toByteArray().copyInto(out, 36); i32(40, n); pcm.copyInto(out, 44)
        return out
    }

    /** One short request. Returns the heard text ("" when nothing clear) or null when the check itself failed. */
    private fun transcribe(pcm: ByteArray): String? {
        val key = Prefs.get(ctx, Prefs.KEY, "").trim()
        if (key.isEmpty()) return null
        val model = Prefs.get(ctx, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL).trim().ifBlank { Prefs.DEFAULT_CODE_MODEL }
        normalize(pcm)
        val w = wav(pcm)
        val b64 = Base64.encodeToString(w, Base64.NO_WRAP)
        java.util.Arrays.fill(w, 0.toByte())
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", PROMPT))
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64))))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 48))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            lastHttp = if (resp.isSuccessful) 0 else resp.code
            if (!resp.isSuccessful) return null
            val j = JSONObject(resp.body?.string() ?: return null)
            val t = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return ""
            return t.trim()
        }
    }
}
