package ai.yasmin.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.Executors

/*
 * v2.36: "Camera Vision" and "Chat" pages.
 * v2.41: Camera Vision is now LIVE: CameraX Preview + ImageAnalysis (no ImageCapture), frames go to the running Gemini Live session about once
 * per second while Live Vision is ON (see LiveVision.kt). The Chat page below is unchanged.
 */

private val uiHandler = Handler(Looper.getMainLooper())

private fun cleanForSpeech(s: String): String =
    s.replace(Regex("[*#`_>]+"), " ").replace(Regex("(?m)^\\s*-\\s+"), "").replace(Regex("\\s+"), " ").trim()

@Composable
private fun VCard(content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

/** One camera frame (runs on the analysis thread). Frames of an old binding are dropped; work is done only when a frame is due. */
private fun processFrame(image: ImageProxy, gen: Int, back: Boolean) {
    try {
        if (!LiveVision.isCurrent(gen)) return
        val now = SystemClock.uptimeMillis()
        LiveVision.markActive(gen, back)     // the first frame of THIS binding is what makes the camera "ready"
        val wantLive = LiveVision.streaming.value && LiveVision.due(now, LiveVision.lastAttemptAt, LiveVision.intervalMs)
        val still = LiveVision.takeStill()
        if (!wantLive && still == null) return
        val raw = image.toBitmap()
        val bmp = VisionAi.rotate(raw, image.imageInfo.rotationDegrees)
        if (bmp !== raw) raw.recycle()
        if (still != null) still(bmp)
        if (wantLive) {
            LiveVision.lastAttemptAt = now
            val jpg = VisionAi.toJpeg(bmp, LiveVision.FRAME_MAX_SIDE, LiveVision.FRAME_QUALITY)
            // re-check right before sending: a lens switch or "end" may have happened while the picture was being encoded
            if (LiveVision.isCurrent(gen) && LiveVision.streaming.value) LiveVision.deliverFrame(jpg)
        }
    } catch (_: Throwable) {
    } finally { image.close() }
}

// =================================================================================================== Camera Vision

@Composable
fun VisionPage(onStartAssistant: () -> Unit = {}, onStopAssistant: () -> Unit = {}, onClose: () -> Unit = {}) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPerm = ok
        if (!ok) {
            VisionBridge.request.value?.let { VisionBridge.deliver(it.id, "The camera permission is not allowed. Ask him to allow the camera for Yasmin in the phone settings.", false) }
            LiveVision.stopStreaming()
        }
    }
    val scanReq by VisionBridge.request.collectAsState()
    val stopSig by VisionBridge.stopSignal.collectAsState()
    val stopAtEntry = remember { VisionBridge.stopSignal.value }

    val running by AppState.running.collectAsState()
    val online by AppState.online.collectAsState()
    val muted by AppState.muted.collectAsState()
    val modeNow by AppState.mode.collectAsState()
    val assistantStatus by AppState.status.collectAsState()
    val cam by LiveVision.cam.collectAsState()
    val camMsg by LiveVision.camMessage.collectAsState()
    val camBack by LiveVision.facingBack.collectAsState()
    val frames by LiveVision.framesSent.collectAsState()
    val streaming by LiveVision.streaming.collectAsState()

    var lensBack by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var startedByVision by remember { mutableStateOf(false) }
    var intervalMs by remember { mutableStateOf(LiveVision.loadInterval(ctx)) }
    var auto by remember { mutableStateOf(false) }
    var speakOn by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Bitmap?>(null) }

    // COMPATIBLE (TextureView) draws correctly inside the scrolling column with rounded corners; the default SurfaceView mode can glitch there
    val previewView = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(intervalMs) { LiveVision.intervalMs = intervalMs }
    LaunchedEffect(streaming) { previewView.keepScreenOn = streaming }   // the screen must stay on while the camera is looking

    // ---- text to speech (only for the one-shot buttons; while the live voice is connected Gemini speaks, so this stays silent: no double speech, no echo)
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(ctx) { st -> ttsReady = (st == TextToSpeech.SUCCESS) } }
    DisposableEffect(Unit) { onDispose { try { tts.stop(); tts.shutdown() } catch (_: Throwable) {} } }
    fun speak(text: String) {
        if (!ttsReady) return
        val loc = when (BrainLanguage.detect(text).reply) {
            ReplyLanguage.BENGALI -> Locale("bn", "IN")
            ReplyLanguage.HINDI -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
        try {
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.language = loc
            tts.speak(cleanForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "vision")
        } catch (_: Throwable) {}
    }

    // ---- leaving the page in ANY way (back button, menu, end): no more frames, camera released, executor stopped
    DisposableEffect(Unit) {
        onDispose {
            LiveVision.release()
            try { analysis.clearAnalyzer() } catch (_: Throwable) {}
            try { val f = ProcessCameraProvider.getInstance(ctx); if (f.isDone) f.get().unbindAll() } catch (_: Throwable) {}
            try { analysisExecutor.shutdown() } catch (_: Throwable) {}
        }
    }

    // ---- app goes to the background: Android closes the camera by itself, so frames stop; the model is told once
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP) {
                paused = true
                if (LiveVision.streaming.value) LiveVision.notice(LiveVision.NOTICE_PAUSED)
            } else if (ev == Lifecycle.Event.ON_START && paused) {
                paused = false
                if (LiveVision.streaming.value) LiveVision.notice(LiveVision.NOTICE_RESUMED)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // ---- camera binding. Every (re)bind gets a new generation; the camera is "ready" only when a real frame of THIS binding arrives
    DisposableEffect(hasPerm, lensBack, retry) {
        if (!hasPerm) {
            LiveVision.release()
            return@DisposableEffect onDispose { }
        }
        val back = lensBack
        val gen = LiveVision.newGeneration()
        val future = ProcessCameraProvider.getInstance(ctx)
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val provider = future.get()
                val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (!provider.hasCamera(selector)) {
                    LiveVision.fail(gen, if (back) "This phone has no back camera." else "This phone has no front camera.")
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                analysis.clearAnalyzer()
                analysis.setAnalyzer(analysisExecutor) { image -> processFrame(image, gen, back) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Throwable) {
                LiveVision.fail(gen, "Camera error: " + (e.message ?: "could not start the camera"))
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            disposed = true
            LiveVision.invalidate()          // frames of this binding are stale from now on
            try { analysis.clearAnalyzer() } catch (_: Throwable) {}
            try { if (future.isDone) future.get().unbindAll() } catch (_: Throwable) {}
        }
    }

    // ---- watchdog: bound but no frame for 7 s (camera used by another app, hardware problem)
    LaunchedEffect(cam, lensBack, retry, hasPerm) {
        if (hasPerm && cam == LiveVision.Cam.STARTING) {
            delay(7000)
            LiveVision.failStuck("The camera is not delivering pictures. Another app may be using it. Close that app and press RETRY.")
        }
    }

    fun endLive(closePage: Boolean) {
        val was = LiveVision.streaming.value
        LiveVision.stopStreaming()
        if (was) LiveVision.notice(LiveVision.NOTICE_OFF)
        VisionBridge.request.value?.let { VisionBridge.deliver(it.id, "The camera session was ended.", false) }
        if (startedByVision && AppState.running.value) { onStopAssistant(); startedByVision = false }
        if (closePage) onClose()
    }

    fun startLive() {
        if (!hasPerm) { askPerm.launch(Manifest.permission.CAMERA); return }
        if (Prefs.get(ctx, Prefs.KEY, "").isBlank()) { Toast.makeText(ctx, "Paste your API key first (menu > API Key)", Toast.LENGTH_LONG).show(); return }
        if (cam == LiveVision.Cam.ERROR) retry++
        if (!AppState.running.value) { startedByVision = true; onStartAssistant() }
        LiveVision.startStreaming(announce = true)
    }

    fun switchLens() {
        lensBack = !lensBack
        if (LiveVision.streaming.value) LiveVision.notice(LiveVision.cameraNote(lensBack))
    }

    fun analyze(bmp: Bitmap, prompt: String) {
        busy = true
        Thread {
            try {
                val jpg = VisionAi.toJpeg(bmp)
                val ans = VisionAi.ask(ctx, listOf(VisionAi.Turn(true, prompt, jpg)))
                uiHandler.post { result = ans; if (speakOn && !AppState.online.value) speak(ans) }
            } catch (e: Exception) {
                uiHandler.post { result = "Error: " + (e.message ?: "something went wrong") }
            } finally { uiHandler.post { busy = false } }
        }.start()
    }

    /** One picture for the written answer on this page (the live stream is not needed for it). */
    fun lookNow() {
        if (busy) return
        if (!hasPerm) { askPerm.launch(Manifest.permission.CAMERA); return }
        if (cam != LiveVision.Cam.ACTIVE) { result = "The camera is not ready yet."; return }
        busy = true
        LiveVision.requestStill { bmp -> uiHandler.post { busy = false; picked = null; analyze(bmp, VisionAi.PROMPT_LIVE) } }
        uiHandler.postDelayed({ if (LiveVision.cancelStill()) { busy = false; result = "No camera picture arrived. Try again." } }, 4000)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            Thread {
                val bmp = VisionAi.loadUri(ctx, uri)
                uiHandler.post {
                    if (bmp == null) { busy = false; Toast.makeText(ctx, "Could not open that picture", Toast.LENGTH_SHORT).show() }
                    else { busy = false; picked = bmp; analyze(bmp, VisionAi.PROMPT_REPORT) }
                }
            }.start()
        }
    }

    // ---- the voice assistant asked for the camera ("Yasmin, camera kholo"): Live Vision starts, the tool is answered after the FIRST live frame
    LaunchedEffect(scanReq?.id, hasPerm) {
        val r = scanReq ?: return@LaunchedEffect
        if (!hasPerm) { askPerm.launch(Manifest.permission.CAMERA); return@LaunchedEffect }
        val wantBack = !r.front
        lensBack = wantBack
        LiveVision.startStreaming(announce = false)    // the tool result already tells the model which camera it is
        var waited = 0
        while (waited < 15000) {
            if (LiveVision.cam.value == LiveVision.Cam.ERROR) {
                LiveVision.stopStreaming()
                VisionBridge.deliver(r.id, LiveVision.camMessage.value.ifBlank { "The camera could not start." }, false)
                return@LaunchedEffect
            }
            if (LiveVision.cam.value == LiveVision.Cam.ACTIVE && LiveVision.facingBack.value == wantBack && LiveVision.framesSent.value > 0) {
                VisionBridge.deliverReady(r.id, wantBack)
                return@LaunchedEffect
            }
            delay(100); waited += 100
        }
        LiveVision.stopStreaming()
        VisionBridge.deliver(r.id, "No picture reached the live voice session in time. The voice connection may be offline or asleep.", false)
    }

    // ---- "camera band karo" by voice
    LaunchedEffect(stopSig) { if (stopSig != stopAtEntry) endLive(true) }

    // ---- automatic one-shot mode (kept from v2.36; switched off while the live stream is on)
    LaunchedEffect(auto, hasPerm) {
        while (auto && hasPerm) {
            if (!busy && !LiveVision.streaming.value) lookNow()
            delay(8000)
        }
    }

    // ---- truthful status lines
    val camLine = when {
        !hasPerm -> "Camera: permission needed"
        paused -> "Camera: paused (app in the background)"
        cam == LiveVision.Cam.STARTING -> "Camera: starting..."
        cam == LiveVision.Cam.ACTIVE -> "Camera: active (" + (if (camBack) "back" else "front") + ")"
        cam == LiveVision.Cam.ERROR -> "Camera: " + camMsg
        else -> "Camera: off"
    }
    val voiceLine = when {
        !running -> "Voice: assistant is off"
        modeNow == AssistantMode.SLEEP -> "Voice: sleeping (nothing is sent)"
        !online -> "Voice: connecting..."
        muted -> "Voice: connected, microphone muted"
        else -> "Voice: connected - " + assistantStatus
    }
    val sendLine = when {
        !streaming -> "Live vision: OFF (no pictures are sent)"
        frames == 0 -> "Live vision: ON, waiting for the first picture"
        else -> "Live vision: ON - " + frames + " pictures sent, 1 every " + (intervalMs / 1000L) + " s"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Camera Vision", fontSize = 28.sp, fontFamily = FontFamily.Serif, color = YasminUi.Text, modifier = Modifier.padding(top = 4.dp))

        Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(22.dp)).background(YasminUi.Card), contentAlignment = Alignment.Center) {
            if (hasPerm) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(20.dp)) {
                    Text("Camera permission is needed to see.", color = YasminUi.Muted, fontSize = 14.sp)
                    MChip("ALLOW CAMERA") { askPerm.launch(Manifest.permission.CAMERA) }
                }
            }
        }

        VCard {
            if (streaming) Text("CAMERA ON" + (if (running && online && !muted && modeNow != AssistantMode.SLEEP) "  -  MICROPHONE ON" else ""), fontSize = 13.sp, color = YasminUi.Red)
            Text(camLine, fontSize = 13.sp, color = YasminUi.Text)
            Text(voiceLine, fontSize = 13.sp, color = YasminUi.Text)
            Text(sendLine, fontSize = 13.sp, color = if (streaming) YasminUi.Green else YasminUi.Muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MChip(if (streaming) "LIVE VISION: ON" else if (cam == LiveVision.Cam.ERROR) "RETRY" else "START LIVE VISION", if (streaming) YasminUi.Green else YasminUi.Cyan, enabled = !streaming) { startLive() }
            MChip("END CAMERA", YasminUi.Red) { endLive(true) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MChip(if (lensBack) "FRONT CAMERA" else "BACK CAMERA") { switchLens() }
            MChip("PICTURES: 1 / " + (intervalMs / 1000L) + " s") {
                intervalMs = when (intervalMs) { 1000L -> 2000L; 2000L -> 3000L; 3000L -> 5000L; else -> 1000L }
                LiveVision.saveInterval(ctx, intervalMs)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MChip(if (busy) "LOOKING..." else "WHAT DO YOU SEE?", enabled = !busy) { lookNow() }
            MChip("PICK IMAGE", enabled = !busy) { pickImage.launch("image/*") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MChip(if (auto) "AUTO: ON" else "AUTO: OFF", if (auto) YasminUi.Green else YasminUi.Cyan, enabled = !streaming) { auto = !auto }
            MChip(if (speakOn) "VOICE: ON" else "VOICE: OFF", if (speakOn) YasminUi.Green else YasminUi.Muted) { speakOn = !speakOn; if (!speakOn) try { tts.stop() } catch (_: Throwable) {} }
            MChip("STOP VOICE", YasminUi.Amber) { try { tts.stop() } catch (_: Throwable) {} }
        }

        picked?.let { b ->
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)))
        }

        VCard {
            Text("What Yasmin sees", fontSize = 15.sp, color = YasminUi.Cyan)
            Text(
                if (result.isBlank()) (if (streaming) "Live: just talk. Yasmin answers from the live picture." else if (busy) "Looking..." else "Tap START LIVE VISION and ask Yasmin what she sees, or tap \"What do you see?\" for a written answer, or pick an image for a full report.") else result,
                fontSize = 14.sp, color = if (result.isBlank()) YasminUi.Muted else YasminUi.Text, lineHeight = 21.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

// =================================================================================================== Chat

private class ChatMsg(val fromUser: Boolean, val text: String, val jpeg: ByteArray? = null, val thumb: Bitmap? = null)

@Composable
fun ChatTextPage() {
    val ctx = LocalContext.current
    val msgs = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf<Bitmap?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Thread {
            val b = VisionAi.loadUri(ctx, uri)
            uiHandler.post { if (b != null) attached = b else Toast.makeText(ctx, "Could not open that picture", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    fun send() {
        val text = input.trim()
        val img = attached
        if ((text.isEmpty() && img == null) || busy) return
        if (Prefs.get(ctx, Prefs.KEY, "").isBlank()) { Toast.makeText(ctx, "Paste your API key first (menu > API Key)", Toast.LENGTH_LONG).show(); return }
        input = ""; attached = null; busy = true
        // the history before this message (photos of older messages are not sent again, only noted)
        val history = msgs.takeLast(20).map { VisionAi.Turn(it.fromUser, if (it.thumb != null) "(photo sent) " + it.text else it.text) }
        msgs.add(ChatMsg(true, text, null, img))
        Thread {
            try {
                val jpg = img?.let { VisionAi.toJpeg(it) }
                val ask = text.ifBlank { VisionAi.PROMPT_REPORT }
                val ans = VisionAi.ask(ctx, history + VisionAi.Turn(true, ask, jpg))
                uiHandler.post { msgs.add(ChatMsg(false, ans)) }
            } catch (e: Exception) {
                uiHandler.post { msgs.add(ChatMsg(false, "Error: " + (e.message ?: "something went wrong"))) }
            } finally { uiHandler.post { busy = false } }
        }.start()
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Chat", fontSize = 28.sp, fontFamily = FontFamily.Serif, color = YasminUi.Text)
            MChip("CLEAR", YasminUi.Amber, enabled = msgs.isNotEmpty() && !busy) { msgs.clear() }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty()) item { Text("Write anything to Yasmin. You can also attach a picture and ask about it.", color = YasminUi.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (m.fromUser) YasminUi.Blue.copy(alpha = 0.35f) else YasminUi.Card).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        m.thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = 240.dp).height(140.dp).clip(RoundedCornerShape(12.dp))) }
                        if (m.text.isNotBlank()) Text(m.text, fontSize = 14.sp, color = YasminUi.Text, lineHeight = 20.sp)
                    }
                }
            }
            if (busy) item { Text("Yasmin is typing...", color = YasminUi.Muted, fontSize = 13.sp) }
        }
        attached?.let { b ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.height(56.dp).widthIn(max = 56.dp).clip(RoundedCornerShape(10.dp)))
                MChip("REMOVE PICTURE", YasminUi.Red) { attached = null }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, placeholder = { Text("Message") },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = YasminUi.Cyan, unfocusedBorderColor = YasminUi.Line,
                    focusedTextColor = YasminUi.Text, unfocusedTextColor = YasminUi.Text,
                    cursorColor = YasminUi.Cyan, focusedPlaceholderColor = YasminUi.Muted, unfocusedPlaceholderColor = YasminUi.Muted
                )
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MChip("PHOTO", enabled = !busy) { pick.launch("image/*") }
                MChip("SEND", enabled = !busy) { send() }
            }
        }
    }
}
