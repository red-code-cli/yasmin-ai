package ai.yasmin.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

/** Mic capture (16 kHz PCM) + speaker playback (24 kHz PCM), tuned for low latency and echo cancellation. */
class AudioIO(private val ctx: Context, private val onMic: (ByteArray) -> Unit) {
    @Volatile private var run = false
    private var rec: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var fastTrack = false   // track was built on the low-latency path
    private var am: AudioManager? = null
    // Each chunk is tagged with the "epoch" it belongs to, so speech that was interrupted can never leak out after a flush.
    private val q = LinkedBlockingQueue<Pair<Int, ByteArray>>()
    private val epoch = AtomicInteger(0)
    @Volatile private var written = 0L   // 16-bit samples handed to the speaker so far (lets us know if sound is still coming out)

    @Volatile private var held = 0   // bytes waiting in the jitter buffer

    companion object {
        private const val PRE_ROLL = 7200   // 150 ms of 24 kHz 16-bit mono
        @Volatile private var current: AudioIO? = null
        /** Called by the pitch slider: re-applies the saved pitch to the running speaker. */
        fun pitchChanged() { current?.let { it.applyPitch(it.track) } }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        am = ctx.getSystemService(AudioManager::class.java)
        am?.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        val minIn = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 3200) * 2
        )
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.setEnabled(true)
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.setEnabled(true)
        // v2.14: automatic gain keeps a quiet or far voice at a level the speech recogniser understands
        if (AutomaticGainControl.isAvailable()) try { AutomaticGainControl.create(r.audioSessionId)?.setEnabled(true) } catch (_: Exception) {}
        rec = r

        val t = buildTrack()
        track = t
        current = this
        applyPitch(t)
        run = true
        r.startRecording()
        t.play()

        thread(name = "yasmin-mic", isDaemon = true) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            val buf = ByteArray(640) // 20 ms chunks = lowest latency
            while (run) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    onMic(buf.copyOf(n))
                    AppState.level.value = rms(buf, n)
                } else if (n < 0) break
            }
        }
        thread(name = "yasmin-speaker", isDaemon = true) {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            var carry = -1 // leftover odd byte: keeps 16-bit samples aligned across network chunks
            var seen = epoch.get()
            // Jitter buffer: Gemini audio arrives in bursts over the mobile network. If the speaker buffer runs dry in the middle of a
            // word (underrun) many phones play crackle / radio-style static. So every answer first collects PRE_ROLL of audio and only
            // then starts; a chunk that stops arriving flushes what was collected after 60 ms.
            val pre = ArrayList<ByteArray>()
            var preBytes = 0
            var primed = false
            var lastUnderrun = 0
            var noiseMuted = 0

            fun emit(b: ByteArray, e: Int) {
                val lv = rms(b, b.size)
                AppState.level.value = lv
                AppState.outLevel.value = lv
                AppState.outAt = android.os.SystemClock.uptimeMillis()
                var off = 0
                while (off < b.size && run && e == epoch.get()) {
                    val w = track?.write(b, off, b.size - off) ?: break
                    if (w < 0) { recoverTrack(w); break }
                    off += w
                    written += w / 2
                }
                if (Build.VERSION.SDK_INT >= 24) try {
                    val u = track?.underrunCount ?: 0
                    if (u > lastUnderrun) { AppState.add("Audio: speaker ran dry (underrun #$u), network too slow for a moment"); lastUnderrun = u }
                } catch (_: Exception) {}
            }
            fun release(e: Int) {
                val items = ArrayList(pre); pre.clear(); preBytes = 0; held = 0; primed = true
                for (c in items) emit(c, e)
            }

            while (run) {
                val item = q.poll(if (!primed && preBytes > 0) 60L else 200L, TimeUnit.MILLISECONDS)
                if (item == null) { if (!primed && preBytes > 0) release(epoch.get()); continue }
                val (e, raw) = item
                val cur = epoch.get()
                // After an interruption the queue is emptied, so a carried odd byte of the OLD speech must be dropped too,
                // otherwise the next answer starts one byte off and every sample is garbled (sounds like another voice).
                if (cur != seen) { seen = cur; carry = -1; pre.clear(); preBytes = 0; held = 0; primed = false }
                if (e != cur) { carry = -1; continue } // belonged to interrupted speech
                if (raw.isEmpty()) {   // end-of-turn marker: start the next answer sample-aligned
                    if (!primed && preBytes > 0) release(e)
                    carry = -1; primed = false
                    emit(ByteArray(960), e)   // 20 ms of silence so the end of the buffer is never a loud sample that can repeat
                    continue
                }
                var b = raw
                if (carry >= 0) {
                    val m = ByteArray(b.size + 1)
                    m[0] = carry.toByte()
                    System.arraycopy(b, 0, m, 1, b.size)
                    b = m; carry = -1
                }
                if (b.size % 2 == 1) { carry = b[b.size - 1].toInt() and 0xFF; b = b.copyOf(b.size - 1) }
                if (b.isEmpty()) continue
                if (isNoiseBurst(b)) {   // a glitch of the model (seen with Gemini 3.1): loud broadband hiss instead of a voice
                    b = ByteArray(b.size)
                    if (++noiseMuted % 5 == 1) AppState.add("Audio: a loud noise burst from the voice model was muted (#$noiseMuted)")
                }
                if (primed) emit(b, e)
                else {
                    pre.add(b); preBytes += b.size; held = preBytes
                    if (preBytes >= PRE_ROLL) release(e)
                }
            }
        }
    }

    /** Output device died or stopped (route change, Bluetooth switch): rebuild instead of going silent or garbled. */
    private fun recoverTrack(err: Int) {
        try {
            if (err == AudioTrack.ERROR_DEAD_OBJECT) {
                try { track?.release() } catch (_: Exception) {}
                val nt = buildTrack(); track = nt; applyPitch(nt); nt.play(); written = 0L
            } else track?.play()
        } catch (_: Exception) {}
    }

    private fun buildTrack(): AudioTrack {
        val minOut = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        fastTrack = false
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(24000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minOut * 4, 38400))   // ~800 ms capacity: absorbs network jitter (an underrun can sound like buzzing); adds no delay
            .setTransferMode(AudioTrack.MODE_STREAM)
            // v2.17: the low-latency "fast" path cannot change pitch, so it is used only while the pitch is normal
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)   // the low-latency fast path underruns easily and crackles on many phones; speech from the network does not need it
            .build()
    }

    /**
     * Pitch slider. First choice: change the pitch alone (speed stays the same). If this phone refuses that,
     * the playback rate is changed instead, which moves pitch and speed together (small slider steps still sound natural).
     */
    private fun applyPitch(t: AudioTrack?) {
        val tr = t ?: return
        val pct = VoicePitch.saved(ctx)
        val p = pct / 100f
        // Normal pitch on the low-latency path: leave the track alone. Forcing time-stretch parameters onto the fast mixer
        // makes some phones produce crackle / static.
        if (fastTrack && pct == VoicePitch.DEFAULT) return
        try {
            tr.playbackParams = PlaybackParams().setPitch(p).setSpeed(1f)
        } catch (_: Exception) {
            try { tr.playbackRate = (24000 * p).toInt() } catch (_: Exception) {}
        }
    }

    fun play(pcm: ByteArray) { q.offer(epoch.get() to pcm) }

    /** Marks the end of one answer so the next one always starts on a clean 16-bit sample boundary. */
    fun endTurn() { q.offer(epoch.get() to ByteArray(0)) }

    /** Called when the user interrupts (barge-in): drop queued speech instantly. */
    fun flush() {
        q.clear()
        epoch.incrementAndGet()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
        written = head()
    }

    private fun head(): Long = try { (track?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL } catch (_: Exception) { 0L }

    /** True while queued or buffered speech is still coming out of the speaker (the voice check must not score Yasmin's own voice). */
    fun isPlaying(): Boolean = !q.isEmpty() || held > 0 || written - head() > 240

    fun stop() {
        run = false
        if (current === this) current = null
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        rec = null; track = null
        val a = am ?: return
        a.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() else a.isSpeakerphoneOn = false
    }

    private fun route() {
        val a = am ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = a.availableCommunicationDevices
            val headset = intArrayOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET
            )
            val pick = devs.firstOrNull { it.type in headset }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (pick != null) a.setCommunicationDevice(pick)
        } else {
            @Suppress("DEPRECATION")
            a.isSpeakerphoneOn = true
        }
    }

    /**
     * Speech is smooth (neighbouring samples are alike); a hiss is not. Compare the energy of the sample-to-sample difference with the
     * energy of the signal: white noise gives about 2.0, speech stays well below 1 (even "s" sounds are quiet). Only a chunk of at least
     * 10 ms that is both LOUD and noise-like counts, so normal speech is never touched.
     */
    private fun isNoiseBurst(b: ByteArray): Boolean {
        val n = b.size / 2
        if (n < 240) return false
        var e = 0.0; var d = 0.0; var prev = 0.0
        for (k in 0 until n) {
            val v = (((b[2 * k + 1].toInt() shl 8) or (b[2 * k].toInt() and 0xFF)).toShort().toInt()) / 32768.0
            e += v * v
            if (k > 0) { val x = v - prev; d += x * x }
            prev = v
        }
        val rms = sqrt(e / n)
        return rms > 0.12 && d / (e + 1e-9) > 1.4
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var s = 0.0
        var i = 0
        val cnt = n / 2
        if (cnt == 0) return 0f
        while (i + 1 < n) {
            val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            s += v * v
            i += 2
        }
        return min(1f, (sqrt(s / cnt) * 4).toFloat())
    }
}
