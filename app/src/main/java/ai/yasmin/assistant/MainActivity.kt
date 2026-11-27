package ai.yasmin.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal enum class Page(val title: String) {
    HOME("Yasmin"), PERMS("Permissions"), VOICE("Voice Pack"), KEY("API Key"), CHAT("Conversation"), PREFS("Preferences"), EXTRA("Additional Settings"),
    AUTOMATION("Automation"), LOCK("Security / Custom Lock"), EDGE("Edge Glow"), LICENSE("License"), VISION("Camera Vision"), CHATTXT("Chat");

    companion object {
        /** Pages that need the license code. Everything about talking (API key, voices, models, conversation, preferences) stays free. */
        val LOCKED: Set<Page> = setOf(PERMS, EXTRA, AUTOMATION, LOCK, EDGE)
    }
}

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    /** true while the app is in the foreground; the Automation screen only polls the real state while this is true. */
    private val resumed = mutableStateOf(false)

    /** Voice Changer: the sample files the user picked (kept here so they survive a recomposition). */
    private val pickedSamples = mutableStateOf<List<Uri>>(emptyList())
    private val samplePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { l -> if (l.isNotEmpty()) pickedSamples.value = l }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick.intValue++ }
    /** v2.33: asks for the microphone, then starts the assistant (there is no separate AI page any more, everything happens on the home screen). */
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick.intValue++
        if (micOk()) startAssistant() else Toast.makeText(this, "Microphone permission is needed", Toast.LENGTH_LONG).show()
    }
    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            val i = Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_PROJ)
                .putExtra("code", r.resultCode).putExtra("data", d)
            ContextCompat.startForegroundService(this, i)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ChatMemory.loadRecentLog(this)   // the saved chat comes back on every start
        MessageProcessor.start(this)   // opens the message store so the counters in Additional Settings are real
        YasminBackgroundService.restoreIfEnabled(this)   // brings the background service back if the user had it on and Android stopped it
        setContent {
            YasminTheme {
                Main()
            }
        }
    }



    override fun onResume() { super.onResume(); YasminLockManager.guard(this); resumed.value = true; VisionBridge.uiResumed = true; MessageAccess.refresh(this); tick.intValue++ }

    override fun onPause() { resumed.value = false; VisionBridge.uiResumed = false; super.onPause() }

    /** Same start as the switch in Additional Settings: asks for the notification permission on Android 13+, then starts the background service. */
    private fun startBackgroundService() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        YasminBackgroundService.start(this)
    }

    private fun micOk() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun accOk(): Boolean {
        return PhoneControl.accEnabledInSettings(this)
    }
    private fun notifOk(): Boolean = MessageAccess.enabled(this)
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun batteryOk() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestRuntime() {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)   // every phone tool asks for what it needs, in one go
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_CONNECT); p.add(Manifest.permission.BLUETOOTH_SCAN) }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
            p.add(Manifest.permission.READ_MEDIA_IMAGES); p.add(Manifest.permission.READ_MEDIA_VIDEO); p.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else p.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Terminal.installed(this)) p.add("com.termux.permission.RUN_COMMAND")   // lets Yasmin run commands in Termux
        permLauncher.launch(p.toTypedArray())
    }
    /** Starts the assistant service on the home screen: microphone permission first, then the service. The key was already checked by the caller. */
    private fun startAssistant() {
        if (AppState.running.value) return
        if (!micOk()) {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            micLauncher.launch(p.toTypedArray())
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_START))
    }
    private fun appInfo() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ================================================================================================ root

    @Composable
    private fun Main() {
        val ctx: Context = this@MainActivity
        val t = tick.intValue
        val running by AppState.running.collectAsState()
        val online by AppState.online.collectAsState()
        val switching by AppState.switching.collectAsState()
        val activeModel by AppState.model.collectAsState()

        var page by remember { mutableStateOf(Page.HOME) }
        // v2.37: the voice assistant asked the camera to look ("Yasmin dekho"): open Camera Vision, it scans by itself
        val scanReq by VisionBridge.request.collectAsState()
        LaunchedEffect(scanReq?.id) { if (scanReq != null) page = Page.VISION }
        val licensed = true   // this build has no license lock
        var drawer by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var voiceMenu by remember { mutableStateOf(false) }
        var engine by remember { mutableStateOf(VoiceEngine.saved(ctx)) }
        val liveEngine by AppState.engine.collectAsState()
        LaunchedEffect(liveEngine) { engine = VoiceEngine.saved(ctx) }   // the service may fall back to Gemini if ElevenLabs fails
        var model by remember { mutableStateOf(Prefs.liveModel(ctx)) }

        // the service may change the model by itself (for example it goes back to the old one if the new one is rejected)
        LaunchedEffect(activeModel) { if (activeModel.isNotEmpty() && activeModel != model) model = activeModel }

        BackHandler(enabled = drawer || menu || voiceMenu || page != Page.HOME) {
            when {
                menu -> menu = false
                voiceMenu -> voiceMenu = false
                drawer -> drawer = false
                else -> page = Page.HOME
            }
        }

        Box(Modifier.fillMaxSize().background(YasminUi.Bg)) {
            Crossfade(targetState = page, label = "page", modifier = Modifier.fillMaxSize()) { p ->
                if (p == Page.HOME) {
                    Home(
                        t, engine,
                        onMenu = { drawer = true },
                        onVoice = { voiceMenu = !voiceMenu; menu = false },
                        onModel = { menu = !menu; voiceMenu = false }
                    ) { page = it }
                } else {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        YasminTopBar(
                            left = Ic.BACK,
                            onLeft = { page = Page.HOME },
                            online = online, label = statusLabel(running, online, switching, AppState.mode.collectAsState().value), running = running,
                            onBolt = { menu = !menu; voiceMenu = false },
                            onVoice = { voiceMenu = !voiceMenu; menu = false }
                        )
                        Box(Modifier.weight(1f)) {
                            when (if (!licensed && p in Page.LOCKED) Page.LICENSE else p) {
                                Page.HOME -> {}
                                Page.LICENSE -> {}
                                Page.VISION -> VisionPage(
                                    onStartAssistant = { startAssistant() },
                                    onStopAssistant = { startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)) },
                                    onClose = {
                                        page = Page.HOME
                                        // the camera was opened by voice while another app was in front: go back to that app
                                        if (VisionBridge.openedFromOutside) { VisionBridge.openedFromOutside = false; moveTaskToBack(true) }
                                    }
                                )
                                Page.CHATTXT -> ChatTextPage()
                                Page.PERMS -> PermsPage(t)
                                Page.VOICE -> VoicePage()
                                Page.KEY -> KeyPage()
                                Page.CHAT -> ChatPage(t)
                                Page.PREFS -> PrefsPage()
                                Page.EXTRA -> ExtraPage { page = it }
                                Page.AUTOMATION -> AutomationPage(active = resumed.value, onAppInfo = { appInfo() }, onStartService = { startBackgroundService() })
                                Page.LOCK -> LockSettingsPage()
                                Page.EDGE -> EdgeGlowPage(t)
                            }
                        }
                    }
                }
            }

            // side drawer
            AnimatedVisibility(visible = drawer, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { drawer = false })
            }
            AnimatedVisibility(
                visible = drawer,
                modifier = Modifier.statusBarsPadding().padding(start = 12.dp, top = 8.dp),
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut()
            ) {
                Drawer(page, licensed, onShare = { drawer = false; if (licensed) shareScreen() else page = Page.LICENSE },
                    onVoice = { drawer = false; voiceMenu = true; menu = false },
                    onModel = { drawer = false; menu = true; voiceMenu = false }) { p -> page = if (!licensed && p in Page.LOCKED) Page.LICENSE else p; drawer = false }
            }

            ModelMenu(
                visible = menu, selected = model, locked = switching,
                onPick = { id -> model = id; ModelPick.apply(ctx, id) },
                onDismiss = { menu = false },
                top = if (page == Page.HOME) 112.dp else 66.dp
            )
            VoiceEngineMenu(
                visible = voiceMenu, selected = engine, elevenName = VoiceEngine.elevenVoiceName(ctx),
                onPick = { e ->
                    if (VoicePick.apply(ctx, e)) engine = e
                    else Toast.makeText(ctx, "Add your ElevenLabs API key on the API Key page first", Toast.LENGTH_LONG).show()
                },
                onDismiss = { voiceMenu = false },
                top = if (page == Page.HOME) 112.dp else 66.dp
            )
        }
    }


    // ================================================================================================ drawer

    @Composable
    private fun Drawer(current: Page, licensed: Boolean, onShare: () -> Unit, onVoice: () -> Unit, onModel: () -> Unit, go: (Page) -> Unit) {
        GlassCard(Modifier.width(290.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    YasminLogo(56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(brandTitle(), fontSize = 24.sp, fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
                        Text("YOUR AI COMPANION", fontSize = 10.sp, letterSpacing = 2.sp, color = YasminUi.Muted)
                    }
                }
                DrawerItem(Ic.SCREEN, "Camera Vision", current == Page.VISION) { go(Page.VISION) }
                DrawerItem(Ic.CHAT, "Chat", current == Page.CHATTXT) { go(Page.CHATTXT) }
                DrawerItem(Ic.STAR, "Voice Engine", false) { onVoice() }
                DrawerItem(Ic.BRAIN, "AI Model", false) { onModel() }
                DrawerItem(Ic.SHIELD, (if (licensed) "Permissions" else "\uD83D\uDD12 Permissions"), current == Page.PERMS) { go(Page.PERMS) }
                DrawerItem(Ic.WAVE, "Voice Pack", current == Page.VOICE) { go(Page.VOICE) }
                DrawerItem(Ic.KEY, "API Key", current == Page.KEY) { go(Page.KEY) }
                DrawerItem(Ic.CHAT, "Conversation", current == Page.CHAT) { go(Page.CHAT) }
                DrawerItem(Ic.SLIDERS, "Preferences", current == Page.PREFS) { go(Page.PREFS) }
                DrawerItem(Ic.BOLT, (if (licensed) "Automation" else "\uD83D\uDD12 Automation"), current == Page.AUTOMATION) { go(Page.AUTOMATION) }
                DrawerItem(Ic.SCREEN, (if (licensed) "Screen Share" else "\uD83D\uDD12 Screen Share"), false) { onShare() }
                DrawerItem(Ic.BOLT, (if (licensed) "Edge Glow" else "\uD83D\uDD12 Edge Glow"), current == Page.EDGE) { go(Page.EDGE) }
                DrawerItem(Ic.STAR, (if (licensed) "Additional Settings" else "\uD83D\uDD12 Additional Settings"), current == Page.EXTRA) { go(Page.EXTRA) }
            }
        }
    }

    @Composable
    private fun DrawerItem(icon: Ic, label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (selected) YasminUi.Cyan.copy(alpha = 0.12f) else Color.Transparent)
                .border(1.dp, if (selected) YasminUi.Cyan.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MIcon(icon, if (selected) YasminUi.Cyan else YasminUi.Muted, 24.dp)
            Spacer(Modifier.width(16.dp))
            Text(label, Modifier.weight(1f), fontSize = 16.sp, letterSpacing = 0.4.sp)
            MIcon(Ic.CHEVRON, YasminUi.Muted, 18.dp)
        }
    }

    private fun brandTitle() = buildAnnotatedString {
        withStyle(SpanStyle(color = YasminUi.Text)) { append("Yasmin") }
    }

    // ================================================================================================ home

    /** Screen share, same behaviour as the old home button (now in the side menu). */
    private fun shareScreen() {
        val ctx: Context = this
        when {
            !AppState.running.value -> Toast.makeText(ctx, "Start the Voice Assistant first", Toast.LENGTH_SHORT).show()
            Build.VERSION.SDK_INT >= 30 && accOk() -> Toast.makeText(ctx, "Not needed on Android 11+, Phone control already sees the screen", Toast.LENGTH_LONG).show()
            else -> projLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }

    @Composable
    private fun Home(t: Int, engine: String, onMenu: () -> Unit, onVoice: () -> Unit, onModel: () -> Unit, go: (Page) -> Unit) {
        val ctx: Context = this@MainActivity
        val running by AppState.running.collectAsState()
        val status by AppState.status.collectAsState()
        val emotion by AppState.emotion.collectAsState()
        val level by AppState.level.collectAsState()
        val muted by AppState.muted.collectAsState()
        val key = remember(t) { Prefs.get(ctx, Prefs.KEY, "") }
        LaunchedEffect(Unit) { CharacterStore.ensureLoaded(ctx) }
        val charSel by CharacterStore.selection.collectAsState()
        val (characterSpec, outfitSpec) = remember(charSel) { CharacterCatalog.resolve(charSel.characterId, charSel.outfitId) }

        val ring = if (running) emoColor(emotion) else YasminUi.Cyan
        val glow by animateFloatAsState(if (running) 0.55f + level * 1.2f else 0.4f, label = "glow")

        val onMic: () -> Unit = {
            if (!running && key.isBlank()) { Toast.makeText(ctx, "Paste your API key first", Toast.LENGTH_LONG).show(); go(Page.KEY) }
            else if (!running) startAssistant()             // v2.33: starts right here, no second page
            else AppState.muted.value = !muted               // already running: the mic button mutes / unmutes
        }

        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF020F2A), YasminUi.Bg, Color(0xFF01091C))))) {
            // soft blue aura behind the character
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height * 0.52f)
                drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = 0.22f * glow), Color.Transparent), center = c, radius = size.width * 0.85f), radius = size.width * 0.85f, center = c)
                // faint vertical grid lines like the design's background texture
                var x = 0f
                while (x < size.width) { drawLine(Color(0x0A5C8CFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f); x += 6.dp.toPx() }
            }

            // v2.42: multi-character + outfit Live2D renderer (Yasmin stays the default with zero regression).
            // v2.32: while the assistant speaks, her mouth opens and closes with her voice (lip-sync) and the head nods.
            AnimeCharacter(
                character = characterSpec,
                outfit = outfitSpec,
                modifier = Modifier.fillMaxSize(),
                speech = { if (running) AppState.speechLevel() else 0f },
                emotion = if (running) emotion else characterSpec.defaultMood
            )

            // curved footer line behind the mic
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp)) {
                val path = Path()
                path.moveTo(0f, size.height * 0.62f)
                path.quadraticBezierTo(size.width / 2f, size.height * 0.02f, size.width, size.height * 0.62f)
                drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent, YasminUi.Blue.copy(alpha = 0.7f), YasminUi.Cyan.copy(alpha = 0.7f), YasminUi.Blue.copy(alpha = 0.7f), Color.Transparent)), style = Stroke(width = 1.2f.dp.toPx()))
            }

            // header: menu / wordmark / voice + model
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Box(
                    Modifier.align(Alignment.TopStart).size(50.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xCC0B1B3D), Color(0xCC050E22))))
                        .border(1.2.dp, Brush.linearGradient(listOf(YasminUi.Cyan, YasminUi.Blue)), CircleShape)
                        .clickable(onClick = onMenu),
                    contentAlignment = Alignment.Center
                ) { MIcon(Ic.MENU, YasminUi.Cyan, 26.dp) }

                YasminWordmark(Modifier.align(Alignment.TopCenter).padding(top = 2.dp))
            }

            // bottom: (running controls) + Tap to speak
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (running) {
                    Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MChip(if (muted) "UNMUTE" else "MUTE", if (muted) YasminUi.Amber else YasminUi.Cyan) { AppState.muted.value = !muted }
                        if (Prefs.getBool(ctx, Prefs.WAKE_SLEEP, Prefs.WAKE_SLEEP_DEFAULT) && !status.startsWith("Sleeping"))
                            MChip("SLEEP", YasminUi.Amber) { startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_SLEEP)) }
                        MChip("STOP", YasminUi.Red) { startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)) }
                    }
                }
                // v2.37: camera vision on the left of the mic, text chat on the right
                Box(Modifier.width(230.dp).height(76.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(44.dp).clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Color(0xCC0B1B3D), Color(0xCC050E22))))
                            .border(1.dp, Brush.linearGradient(listOf(YasminUi.Cyan, YasminUi.Blue)), CircleShape)
                            .clickable { go(Page.VISION) },
                        contentAlignment = Alignment.Center
                    ) { MIcon(Ic.CAMERA, YasminUi.Cyan, 22.dp) }
                    Box(
                        Modifier.size(76.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(ring.copy(alpha = 0.28f), Color(0xFF040C1E))))
                            .border(2.dp, Brush.sweepGradient(listOf(ring, YasminUi.Blue, YasminUi.Violet, ring)), CircleShape)
                            .clickable(onClick = onMic),
                        contentAlignment = Alignment.Center
                    ) { MIcon(Ic.MIC, YasminUi.Text, 32.dp) }
                    Box(
                        Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(44.dp).clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Color(0xCC0B1B3D), Color(0xCC050E22))))
                            .border(1.dp, Brush.linearGradient(listOf(YasminUi.Cyan, YasminUi.Blue)), CircleShape)
                            .clickable { go(Page.CHATTXT) },
                        contentAlignment = Alignment.Center
                    ) { MIcon(Ic.CHAT, YasminUi.Cyan, 22.dp) }
                }
                Text(if (running) status.ifBlank { "Listening" } else "Tap to speak", fontSize = 12.sp, color = YasminUi.Muted, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }

    @Composable
    private fun NavRow(icon: Ic, title: String, subtitle: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
        GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(YasminUi.Cyan.copy(alpha = 0.08f)).border(1.dp, YasminUi.Cyan.copy(alpha = 0.35f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    MIcon(icon, YasminUi.Cyan, 24.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, letterSpacing = 0.3.sp)
                    Text(subtitle, fontSize = 12.sp, color = YasminUi.Muted)
                }
                Spacer(Modifier.width(8.dp))
                if (trailing != null) trailing() else MIcon(Ic.CHEVRON, YasminUi.Muted, 20.dp)
            }
        }
    }

    // ================================================================================================ sub pages

    @Composable
    private fun PageFrame(title: String, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(title, fontSize = 28.sp, fontFamily = FontFamily.Serif, letterSpacing = 0.5.sp, color = YasminUi.Text, modifier = Modifier.padding(top = 4.dp))
            content()
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun Panel(content: @Composable () -> Unit) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }

    @Composable
    private fun PermsPage(t: Int) {
        val ctx: Context = this@MainActivity
        val mic = remember(t) { micOk() }
        val acc = remember(t) { accOk() }
        val ovl = remember(t) { overlayOk() }
        val notif = remember(t) { notifOk() }
        val bat = remember(t) { batteryOk() }
        val wrt = remember(t) { Settings.System.canWrite(ctx) }
        val dnd = remember(t) { ctx.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted }
        PageFrame("Permissions") {
            Panel {
                PermRow("Microphone, calls, contacts, SMS, notifications", mic) { requestRuntime() }
                PermRow("Phone control (Accessibility)", acc) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                if (!acc) Note("Android 13+: if the switch is greyed out, tap 'App info', open the \u22EE menu and choose 'Allow restricted settings', then enable Yasmin in Accessibility.")
                if (!acc) Row { MChip("App info") { appInfo() } }
                PermRow("Read WhatsApp/Messenger messages (Notification access)", notif) { MessageAccess.openSettings(ctx) }
                if (!notif) Note("Android 13+: if it is greyed out, open 'App info' \u2192 \u22EE \u2192 'Allow restricted settings' first, then allow Yasmin here.")
                PermRow("Display over other apps", ovl) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                PermRow("Change system settings (brightness)", wrt) { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
                PermRow("Do Not Disturb access (ring / notification volume)", dnd) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                PermRow("Run in background (no battery limit)", bat) { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            }
        }
    }

    @Composable
    private fun VoicePage() {
        val ctx: Context = this@MainActivity
        val running by AppState.running.collectAsState()
        val liveEngine by AppState.engine.collectAsState()
        var voice by remember { mutableStateOf(Voice.saved(ctx)) }
        var engine by remember { mutableStateOf(VoiceEngine.saved(ctx)) }
        LaunchedEffect(liveEngine) { engine = VoiceEngine.saved(ctx) }
        var elVoice by remember { mutableStateOf(Prefs.get(ctx, Prefs.ELEVEN_VOICE, "")) }
        var elName by remember { mutableStateOf(Prefs.get(ctx, Prefs.ELEVEN_VOICE_NAME, "")) }
        var elModel by remember { mutableStateOf(Prefs.get(ctx, Prefs.ELEVEN_MODEL, VoiceEngine.DEFAULT_ELEVEN_MODEL)) }
        var voices by remember { mutableStateOf<List<ElevenVoices.V>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf("") }
        var pitch by remember { mutableStateOf(VoicePitch.saved(ctx).toFloat()) }
        PageFrame("Voice Pack") {
            Panel {
                Text("Voice engine", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("You can also switch any time with the wave button at the top of the screen.", fontSize = 13.sp, color = YasminUi.Muted)
                listOf(VoiceEngine.GEMINI to "Gemini voice", VoiceEngine.ELEVEN to "ElevenLabs voice").forEach { item ->
                    val on = engine == item.first
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable {
                                if (VoicePick.apply(ctx, item.first)) engine = item.first
                                else Toast.makeText(ctx, "Add your ElevenLabs API key on the API Key page first", Toast.LENGTH_LONG).show()
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MIcon(Ic.WAVE, if (on) YasminUi.Cyan else YasminUi.Muted, 24.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(item.second, Modifier.weight(1f), fontSize = 16.sp, color = if (on) YasminUi.Cyan else YasminUi.Text)
                        if (on) MIcon(Ic.CHECK, YasminUi.Cyan, 20.dp)
                    }
                }
            }
            Panel {
                var custom by remember { mutableStateOf(CustomVoices.list(ctx)) }
                var activeCustom by remember { mutableStateOf(CustomVoices.active(ctx)?.id) }
                var cvName by remember { mutableStateOf("My voice") }
                var consent by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var cvMsg by remember { mutableStateOf("") }
                val picked by pickedSamples
                val hasKey = Prefs.get(ctx, Prefs.ELEVEN_KEY, "").isNotBlank()
                Text("Voice Changer (custom voice)", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("Upload a voice sample and Yasmin will speak with that voice. You can keep several, remove any, or go back to the default voices.", fontSize = 13.sp, color = YasminUi.Muted)
                if (!hasKey) Note("Add your ElevenLabs API key on the API Key page first (the key needs 'Voices: Write', and the plan must include voice cloning).")
                MField(cvName, { cvName = it }, "Name for this voice")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MChip(if (picked.isEmpty()) "Choose voice sample" else "Change sample") { samplePicker.launch("audio/*") }
                    if (picked.isNotEmpty()) Text(picked.size.toString() + " file(s) chosen", fontSize = 12.sp, color = YasminUi.Muted)
                }
                Text("Best: 1-2 minutes of ONE person speaking clearly, no music or noise (mp3, wav, m4a, ogg; max 10 MB per file).", fontSize = 12.sp, color = YasminUi.Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = consent, onCheckedChange = { consent = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = YasminUi.Cyan, checkedTrackColor = YasminUi.Cyan.copy(alpha = 0.35f)))
                    Spacer(Modifier.width(10.dp))
                    Text("I have the permission of the person whose voice this is.", Modifier.weight(1f), fontSize = 12.sp, color = YasminUi.Text)
                }
                MChip(if (busy) "Creating voice..." else "Create custom voice", enabled = hasKey && consent && picked.isNotEmpty() && !busy) {
                    busy = true; cvMsg = ""
                    CustomVoices.create(ctx, cvName.trim(), picked) { item, msg ->
                        busy = false
                        if (item == null) cvMsg = msg
                        else {
                            custom = CustomVoices.list(ctx); pickedSamples.value = emptyList(); consent = false
                            if (CustomVoices.use(ctx, item)) {
                                activeCustom = item.id; engine = VoiceEngine.ELEVEN
                                elVoice = item.id; elName = item.name
                                cvMsg = "Done. Yasmin now speaks with \"" + item.name + "\"."
                            } else cvMsg = "Voice created."
                        }
                    }
                }
                if (cvMsg.isNotEmpty()) Note(cvMsg)
                custom.forEach { v ->
                    val on = activeCustom == v.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable {
                                if (CustomVoices.use(ctx, v)) { activeCustom = v.id; engine = VoiceEngine.ELEVEN; elVoice = v.id; elName = v.name }
                                else Toast.makeText(ctx, "Add your ElevenLabs API key on the API Key page first", Toast.LENGTH_LONG).show()
                            }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MIcon(Ic.WAVE, if (on) YasminUi.Cyan else YasminUi.Muted, 24.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(v.name, Modifier.weight(1f), fontSize = 16.sp, color = if (on) YasminUi.Cyan else YasminUi.Text)
                        if (on) { MIcon(Ic.CHECK, YasminUi.Cyan, 20.dp); Spacer(Modifier.width(8.dp)) }
                        MChip("Remove", color = YasminUi.Amber) {
                            CustomVoices.remove(ctx, v) { note ->
                                custom = CustomVoices.list(ctx); activeCustom = CustomVoices.active(ctx)?.id
                                engine = VoiceEngine.saved(ctx); elVoice = Prefs.get(ctx, Prefs.ELEVEN_VOICE, ""); elName = Prefs.get(ctx, Prefs.ELEVEN_VOICE_NAME, "")
                                cvMsg = if (note.isEmpty()) "Removed \"" + v.name + "\"." else note
                            }
                        }
                    }
                }
                if (custom.isNotEmpty() || activeCustom != null) MChip("Back to default voices") {
                    CustomVoices.useDefault(ctx)
                    activeCustom = null; engine = VoiceEngine.saved(ctx); elVoice = Prefs.get(ctx, Prefs.ELEVEN_VOICE, ""); elName = Prefs.get(ctx, Prefs.ELEVEN_VOICE_NAME, "")
                    cvMsg = "Default voices are active again."
                }
            }
            Panel {
                Text("ElevenLabs voice (female)", fontSize = 15.sp, color = YasminUi.Cyan)
                if (Prefs.get(ctx, Prefs.ELEVEN_KEY, "").isBlank()) Note("Add your ElevenLabs API key on the API Key page first.")
                else if (elVoice.isBlank()) Note("No voice picked yet: the default female voice (Sarah) is used. Load voices below to pick another.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip(if (loading) "Loading..." else "Load female voices", enabled = !loading) {
                        val k = Prefs.get(ctx, Prefs.ELEVEN_KEY, "").trim()
                        if (k.isEmpty()) err = "Add your ElevenLabs API key on the API Key page first."
                        else {
                            loading = true; err = ""
                            ElevenVoices.fetchFemale(k) { list, msg ->
                                loading = false
                                if (list == null) err = msg
                                else { voices = list; if (list.isEmpty()) err = "No female voices found in this ElevenLabs account." }
                            }
                        }
                    }
                }
                if (err.isNotEmpty()) Note(err)
                val shown = if (voices.isEmpty() && elVoice.isNotEmpty()) listOf(ElevenVoices.V(elVoice, elName.ifBlank { elVoice }, "")) else voices
                shown.forEach { v ->
                    val on = elVoice == v.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable {
                                elVoice = v.id; elName = v.name
                                Prefs.put(ctx, Prefs.ELEVEN_VOICE, v.id); Prefs.put(ctx, Prefs.ELEVEN_VOICE_NAME, v.name)
                            }.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MIcon(Ic.WAVE, if (on) YasminUi.Cyan else YasminUi.Muted, 24.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, fontSize = 16.sp, color = if (on) YasminUi.Cyan else YasminUi.Text)
                            if (v.note.isNotBlank()) Text(v.note, fontSize = 11.sp, color = YasminUi.Muted, maxLines = 1)
                        }
                        if (on) MIcon(Ic.CHECK, YasminUi.Cyan, 20.dp)
                    }
                }
                MField(elVoice, { elVoice = it.trim(); elName = ""; Prefs.put(ctx, Prefs.ELEVEN_VOICE, it.trim()); Prefs.put(ctx, Prefs.ELEVEN_VOICE_NAME, "") }, "Voice ID (optional, paste from ElevenLabs)")
                MField(elModel, { elModel = it; Prefs.put(ctx, Prefs.ELEVEN_MODEL, it.trim()) }, "ElevenLabs model (eleven_v3 speaks Bengali)")
            }
            Panel {
                Text("Voice pitch: " + (if (pitch.roundToInt() == VoicePitch.DEFAULT) "normal" else (if (pitch < VoicePitch.DEFAULT) "lower " else "higher ") + pitch.roundToInt() + "%"), fontSize = 15.sp, color = YasminUi.Cyan)
                Slider(
                    value = pitch, onValueChange = { pitch = it }, valueRange = VoicePitch.MIN.toFloat()..VoicePitch.MAX.toFloat(),
                    onValueChangeFinished = { VoicePitch.save(ctx, pitch.roundToInt()) },
                    colors = SliderDefaults.colors(thumbColor = YasminUi.Cyan, activeTrackColor = YasminUi.Cyan, inactiveTrackColor = YasminUi.Line)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip("Lower") { pitch = (pitch - 5f).coerceAtLeast(VoicePitch.MIN.toFloat()); VoicePitch.save(ctx, pitch.roundToInt()) }
                    MChip("Reset") { pitch = VoicePitch.DEFAULT.toFloat(); VoicePitch.save(ctx, VoicePitch.DEFAULT) }
                    MChip("Higher") { pitch = (pitch + 5f).coerceAtMost(VoicePitch.MAX.toFloat()); VoicePitch.save(ctx, pitch.roundToInt()) }
                }
                Text("Makes the voice deeper or thinner. Works for both the Gemini and the ElevenLabs voice, and changes immediately while she is talking.", fontSize = 12.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Gemini voice", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("Choose the voice Yasmin speaks with.", fontSize = 13.sp, color = YasminUi.Muted)
                Voice.ALL.forEach { v ->
                    val on = voice == v
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable {
                                voice = v; Prefs.put(ctx, Prefs.VOICE, v)
                                // the running session keeps its voice, so it can never change in the middle of a conversation
                                if (running) Toast.makeText(ctx, "Voice $v is used from the next START", Toast.LENGTH_LONG).show()
                            }.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MIcon(Ic.WAVE, if (on) YasminUi.Cyan else YasminUi.Muted, 24.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(v, Modifier.weight(1f), fontSize = 16.sp, color = if (on) YasminUi.Cyan else YasminUi.Text)
                        if (on) MIcon(Ic.CHECK, YasminUi.Cyan, 20.dp)
                    }
                }
            }
        }
    }

    @Composable
    private fun KeyPage() {
        val ctx: Context = this@MainActivity
        var key by remember { mutableStateOf(Prefs.get(ctx, Prefs.KEY, "")) }
        var showKey by remember { mutableStateOf(false) }
        var model by remember { mutableStateOf(Prefs.liveModel(ctx)) }
        var codeModel by remember { mutableStateOf(Prefs.get(ctx, Prefs.CODE_MODEL, Prefs.DEFAULT_CODE_MODEL)) }
        var elKey by remember { mutableStateOf(Prefs.get(ctx, Prefs.ELEVEN_KEY, "")) }
        var showElKey by remember { mutableStateOf(false) }
        PageFrame("API Key") {
            Panel {
                MField(key, { key = it; Prefs.put(ctx, Prefs.KEY, it.trim()) }, "Gemini API key", password = !showKey)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip(if (showKey) "Hide" else "Show") { showKey = !showKey }
                    MChip("Get free key") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
                }
            }
            Panel {
                Text("ElevenLabs (second voice)", fontSize = 15.sp, color = YasminUi.Cyan)
                MField(elKey, { elKey = it; Prefs.put(ctx, Prefs.ELEVEN_KEY, it.trim()) }, "ElevenLabs API key", password = !showElKey)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip(if (showElKey) "Hide" else "Show") { showElKey = !showElKey }
                    MChip("Get key") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://elevenlabs.io/app/settings/api-keys"))) }
                }
            }
            Panel {
                Text("Models", fontSize = 15.sp, color = YasminUi.Cyan)
                Note("Pick the ready models with the \u26A1 button at the top. Use the fields below only for a custom name.")
                MField(model, { model = it; Prefs.put(ctx, Prefs.MODEL, it.trim()) }, "Live model name (custom)")
                MField(codeModel, { codeModel = it; Prefs.put(ctx, Prefs.CODE_MODEL, it.trim()) }, "Code model (writes the apps Yasmin builds)")
            }
        }
    }

    @Composable
    private fun ChatPage(t: Int) {
        val ctx: Context = this@MainActivity
        val log by AppState.log.collectAsState()
        val used = remember(log, t) { ChatMemory.usedBytes(ctx) }
        PageFrame("Conversation") {
            if (log.isEmpty()) Panel { Text("Nothing yet. Start talking to Yasmin and the chat shows up here.", color = YasminUi.Muted, fontSize = 14.sp) }
            log.takeLast(40).forEach { line ->
                val mine = line.startsWith("You: ")
                val hers = line.startsWith("Yasmin: ")
                if (mine || hers) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Box(
                            Modifier.fillMaxWidth(0.84f).clip(RoundedCornerShape(18.dp))
                                .background(if (mine) YasminUi.Blue.copy(alpha = 0.25f) else YasminUi.CardHi)
                                .border(1.dp, if (mine) YasminUi.Blue.copy(alpha = 0.5f) else YasminUi.Line, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) { Text(line.substringAfter(": "), fontSize = 14.sp, textAlign = if (mine) TextAlign.End else TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
                    }
                } else {
                    Text(line, fontSize = 12.sp, color = YasminUi.Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
            Text(
                "Saved memory: " + (if (used < 1_048_576L) "${used / 1024} KB" else "%.1f MB".format(used / 1_048_576.0)) +
                    " of ${ChatMemory.MAX_BYTES / 1_048_576L} MB (saved automatically, oldest is deleted first)",
                fontSize = 11.sp, color = YasminUi.Muted
            )
        }
    }

    @Composable
    private fun PrefsPage() {
        val ctx: Context = this@MainActivity
        var name by remember { mutableStateOf(Prefs.get(ctx, Prefs.NAME, "")) }
        var lang by remember { mutableStateOf(Prefs.get(ctx, Prefs.LANG, "Bengali, Hindi and English - mirror whatever the user speaks")) }
        var country by remember { mutableStateOf(Prefs.get(ctx, Prefs.COUNTRY, "")) }
        var announce by remember { mutableStateOf(Prefs.getBool(ctx, Prefs.ANNOUNCE, true)) }
        var watch by remember { mutableStateOf(Prefs.getBool(ctx, Prefs.WATCH, false)) }
        var persona by remember { mutableStateOf(PersonaMode.saved(ctx)) }
        var wakeSleep by remember { mutableStateOf(Prefs.getBool(ctx, Prefs.WAKE_SLEEP, Prefs.WAKE_SLEEP_DEFAULT)) }
        LaunchedEffect(Unit) { CharacterStore.ensureLoaded(ctx) }
        val charSel by CharacterStore.selection.collectAsState()
        val (activeChar, activeOutfit) = remember(charSel) { CharacterCatalog.resolve(charSel.characterId, charSel.outfitId) }
        PageFrame("Preferences") {
            Panel {
                Text("Character", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("Choose your companion and outfit. Updates the home screen right away without interrupting an active voice call.", fontSize = 13.sp, color = YasminUi.Muted)
                val previewAccent = Color(activeChar.accentColor)
                Box(
                    Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF03112B), previewAccent.copy(alpha = 0.14f), Color(0xFF040D20))))
                        .border(1.dp, previewAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                ) {
                    AnimeCharacter(
                        character = activeChar,
                        outfit = activeOutfit,
                        modifier = Modifier.fillMaxSize(),
                        emotion = activeChar.defaultMood,
                        fade = YasminUi.Card,
                        sampleSize = 2,
                        showParticles = true
                    )
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xB8040C1E))
                            .border(1.dp, previewAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(activeChar.name + " • " + activeOutfit.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = YasminUi.Text)
                        Text(activeChar.styleTag, fontSize = 11.sp, color = YasminUi.Muted)
                    }
                }
                Text("Characters", fontSize = 13.sp, color = YasminUi.Text)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CharacterCatalog.ALL.forEach { spec ->
                        val selected = spec.id == activeChar.id
                        val accent = Color(spec.accentColor)
                        val thumb = remember(spec.bodyArtRes) { CharacterBitmapCache.acquire(ctx, spec.bodyArtRes, sampleSize = 4) }
                        DisposableEffect(spec.bodyArtRes) {
                            onDispose { CharacterBitmapCache.release(spec.bodyArtRes, sampleSize = 4) }
                        }
                        Column(
                            Modifier.width(96.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (selected) accent.copy(alpha = 0.16f) else YasminUi.CardHi)
                                .border(if (selected) 1.5.dp else 1.dp, if (selected) accent else YasminUi.Line, RoundedCornerShape(14.dp))
                                .clickable { CharacterStore.selectCharacter(ctx, spec.id) }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF040B1A))
                            ) {
                                if (thumb != null && !thumb.isRecycled) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = spec.name,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (selected) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                                            .clip(CircleShape).background(accent),
                                        contentAlignment = Alignment.Center
                                    ) { MIcon(Ic.CHECK, Color.Black, 13.dp) }
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(spec.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) accent else YasminUi.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(spec.defaultMood.replaceFirstChar { it.uppercase() }, fontSize = 10.sp, color = YasminUi.Muted, maxLines = 1)
                        }
                    }
                }
                Text("Outfit for " + activeChar.name, fontSize = 13.sp, color = YasminUi.Text)
                activeChar.outfits.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { outfit ->
                            val selected = outfit.id == activeOutfit.id
                            val swatch = Color(outfit.previewColor)
                            Row(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) YasminUi.Cyan.copy(alpha = 0.12f) else YasminUi.CardHi)
                                    .border(if (selected) 1.5.dp else 1.dp, if (selected) YasminUi.Cyan else YasminUi.Line, RoundedCornerShape(14.dp))
                                    .clickable { CharacterStore.selectOutfit(ctx, outfit.id) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(16.dp).clip(CircleShape).background(swatch).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(outfit.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) YasminUi.Cyan else YasminUi.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(outfit.note, fontSize = 10.sp, color = YasminUi.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Panel {
                Text("Memory", fontSize = 15.sp, color = YasminUi.Cyan)
                Note("Every chat is saved automatically on this phone (up to ${ChatMemory.MAX_BYTES / 1_048_576L} MB, the oldest is deleted first). Whenever Yasmin starts or restarts, it reads the saved chats and remembers everything. No login needed.")
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, YasminUi.Line, RoundedCornerShape(14.dp))
                        .clickable { ChatMemory.clear(ctx); Toast.makeText(ctx, "Memory cleared", Toast.LENGTH_SHORT).show() }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Clear memory", fontSize = 14.sp, color = YasminUi.Red) }
            }
            Panel {
                Text("Wake word training (offline)", fontSize = 15.sp, color = YasminUi.Cyan)
                var trained by remember { mutableStateOf(WakeTemplates.count(ctx)) }
                var rec by remember { mutableStateOf(false) }
                var msg by remember { mutableStateOf("") }
                var sens by remember { mutableStateOf(WakeTemplates.sensitivityName(ctx)) }
                Text(
                    if (trained >= 3) "Trained with $trained samples. While sleeping, Yasmin checks \"Hey Yasmin\" on this phone, no internet needed."
                    else "Not trained yet ($trained / ${WakeTemplates.NEED}). Until then the wake check needs internet.",
                    fontSize = 13.sp, color = YasminUi.Text
                )
                Note("Stop Yasmin first, sit in a quiet place, tap Record and say \"Hey Yasmin\" (or \"Hey Yasmin\") the way you normally do. Do ${WakeTemplates.NEED} samples. Only numbers are saved, never the recording. It answers best to your own voice.")
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(YasminUi.GoldBrush)
                        .clickable(enabled = !rec) {
                            if (AppState.running.value) { msg = "Stop Yasmin first: it is using the microphone." }
                            else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { msg = "Microphone permission is needed." }
                            else { rec = true; msg = "Say \"Hey Yasmin\" now..."; WakeTrainer.record(ctx) { ok, m -> rec = false; msg = m; trained = WakeTemplates.count(ctx) } }
                        }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(if (rec) "Listening..." else "Record sample", fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.SemiBold) }
                if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = YasminUi.Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, YasminUi.Line, RoundedCornerShape(14.dp))
                            .clickable { WakeTemplates.cycleSensitivity(ctx); sens = WakeTemplates.sensitivityName(ctx) }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Match: $sens", fontSize = 13.sp, color = YasminUi.Text) }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).border(1.dp, YasminUi.Line, RoundedCornerShape(14.dp))
                            .clickable { WakeTemplates.clear(ctx); trained = 0; msg = "Training removed." }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Reset", fontSize = 13.sp, color = YasminUi.Red) }
                }
                Note("Match: Strict = fewer false wakes, Relaxed = wakes more easily. While sleeping, the status line shows the score and the limit so you can pick the right level.")
            }
            Panel {
                Text("Personality mode", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("You can also say \"switch to boss mode\" or \"care mode\" or \"friend mode\". A running conversation reconnects and keeps its memory.", fontSize = 13.sp, color = YasminUi.Muted)
                PersonaMode.ALL.forEach { m ->
                    val on = persona == m
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { persona = m; PersonaMode.apply(ctx, m) }.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MIcon(if (m == PersonaMode.CARE) Ic.HEART else if (m == PersonaMode.BOSS) Ic.STAR else Ic.CHAT, if (on) YasminUi.Cyan else YasminUi.Muted, 24.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(PersonaMode.label(m), fontSize = 16.sp, color = if (on) YasminUi.Cyan else YasminUi.Text)
                            Text(PersonaMode.note(m), fontSize = 12.sp, color = YasminUi.Muted)
                        }
                        if (on) MIcon(Ic.CHECK, YasminUi.Cyan, 20.dp)
                    }
                }
            }
            Panel {
                Text("Reply Delay and Reply Limit", fontSize = 15.sp, color = YasminUi.Cyan)
                ReplySettingsSelectors()
            }
            Panel {
                MField(name, { name = it; Prefs.put(ctx, Prefs.NAME, it) }, "Your name")
                MField(lang, { lang = it; Prefs.put(ctx, Prefs.LANG, it) }, "Languages", singleLine = false)
                MField(country, { country = it.filter { c -> c.isDigit() }.take(4); Prefs.put(ctx, Prefs.COUNTRY, country) }, "Country code for WhatsApp numbers (e.g. 880 or 91)")
            }
            Panel {
                SwitchRow("Tell me when a new WhatsApp/Messenger message arrives (sends that message text to Gemini)", announce) { announce = it; Prefs.putBool(ctx, Prefs.ANNOUNCE, it) }
                SwitchRow("Always watch my screen (uses more data)", watch) { watch = it; Prefs.putBool(ctx, Prefs.WATCH, it) }
            }
            Panel {
                SwitchRow("Wake word and sleep mode: say \"Sleep Yasmin\" / \"Sleep Yasmin\" to sleep, \"Hey Yasmin\" / \"Hey Yasmin\" to wake. While asleep Yasmin answers nothing else. Off = Yasmin listens as before. Automatic WhatsApp/Messenger replies work either way.", wakeSleep) {
                    wakeSleep = it; Prefs.putBool(ctx, Prefs.WAKE_SLEEP, it)
                    if (AppState.running.value) ctx.startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_WAKEPREF))
                }
            }
        }
    }

    @Composable
    private fun ExtraPage(go: (Page) -> Unit) {
        val ctx: Context = this@MainActivity
        val running by AppState.running.collectAsState()
        val n by VoiceId.samples.collectAsState()
        val busy by VoiceId.enrolling.collectAsState()
        val msg by VoiceId.enrollMsg.collectAsState()
        val who by VoiceId.who.collectAsState()
        val last by VoiceId.lastPct.collectAsState()
        val tMsg by VoiceId.testMsg.collectAsState()
        val tPct by VoiceId.testPct.collectAsState()
        val tNo by VoiceId.testNo.collectAsState()
        var on by remember { mutableStateOf(VoiceId.enabled(ctx)) }
        var thr by remember { mutableFloatStateOf(VoiceId.threshold(ctx).toFloat()) }
        val bgOn by BackgroundState.running.collectAsState()
        val bgSince by BackgroundState.startedAt.collectAsState()
        val bgMods by BackgroundState.modules.collectAsState()
        val bgErr by BackgroundState.lastError.collectAsState()
        val bgShown by BackgroundState.notificationVisible.collectAsState()
        var bgWanted by remember { mutableStateOf(YasminBackgroundService.isEnabled(ctx)) }
        val msgAccess by MessageAccess.state.collectAsState()
        val msgStats by IncomingMessages.stats.collectAsState()
        val stored by MessageProcessor.stats.collectAsState()
        val brain by BrainState.snapshot.collectAsState()
        var sendDelayMs by remember { mutableStateOf(TaskManager.sendDelayMs(ctx)) }
        val waStats by WhatsAppAgentState.stats.collectAsState()
        var waDrafts by remember { mutableStateOf(Prefs.getBool(ctx, Prefs.WA_DRAFTS, true)) }
        val msgrStats by MessengerAgentState.stats.collectAsState()
        val autoStats by AutoReplyState.stats.collectAsState()
        // the switches themselves live on the Automation screen (step 13); here they are only read
        val autoOn = AutoReplySettings.masterOn(ctx) && !AutoReplySettings.paused(ctx)
        var msgrDrafts by remember { mutableStateOf(Prefs.getBool(ctx, Prefs.MSGR_DRAFTS, true)) }
        var confirmDelete by remember { mutableStateOf(false) }
        val lifeCounts by ConversationLifecycle.counts.collectAsState()
        val privacyStats by PrivacyState.stats.collectAsState()
        var holdName by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { ConversationLifecycle.refreshCounts(ctx) }
        LaunchedEffect(Unit) { VoiceId.refresh(ctx) }
        PageFrame("Additional Settings") {
            Panel {
                Text("Reply Delay and Reply Limit", fontSize = 15.sp, color = YasminUi.Cyan)
                ReplySettingsSelectors()
            }
            Panel {
                Text("Security / Custom Lock", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("Yasmin's own lock: swipe, pattern, PIN or password, with fingerprint / face when the device supports it.", fontSize = 13.sp, color = YasminUi.Muted)
                MChip("Open Custom Lock") { go(Page.LOCK) }
            }
            Panel {
                Text("Background service", fontSize = 15.sp, color = YasminUi.Cyan)
                SwitchRow("Keep Yasmin's background service running (a notification is shown while it is on)", bgWanted) { want ->
                    if (want) {
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        bgWanted = YasminBackgroundService.start(ctx)
                    } else {
                        YasminBackgroundService.stop(ctx); bgWanted = false
                    }
                }
                Text(
                    when {
                        bgOn -> "Running since " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(bgSince)) +
                            (if (bgMods.isEmpty()) ". No background tasks are active yet." else ". Active modules: " + bgMods.joinToString())
                        bgWanted -> "Turned on, but not running right now."
                        else -> "Off."
                    },
                    fontSize = 13.sp, color = if (bgOn) YasminUi.Green else YasminUi.Muted
                )
                if (bgOn && !bgShown) Note("Notifications are blocked for Yasmin, so you will not see the service notification. The service still runs.")
                bgErr?.let { Note(it) }
                Text("This only keeps the app process alive and prepares a place for future background tasks. It does not listen, record or send anything.", fontSize = 11.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Message detection (WhatsApp and Messenger)", fontSize = 15.sp, color = YasminUi.Cyan)
                Text(
                    when (msgAccess) {
                        MessageAccess.State.OFF -> "Notification access is OFF. Yasmin cannot see WhatsApp or Messenger notifications."
                        MessageAccess.State.WAITING -> "Notification access is allowed, but Android has not connected Yasmin's listener yet. " +
                            (if (bgOn) "Yasmin is asking Android to reconnect." else "Turn on the background service above so Yasmin can ask Android to reconnect.")
                        MessageAccess.State.CONNECTED -> "Listening for new WhatsApp and Messenger notifications. Notifications from other apps are ignored."
                    },
                    fontSize = 13.sp, color = if (msgAccess == MessageAccess.State.CONNECTED) YasminUi.Green else YasminUi.Amber
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip(if (msgAccess == MessageAccess.State.OFF) "Turn on notification access" else "Notification access settings") { MessageAccess.openSettings(ctx) }
                    if (msgAccess == MessageAccess.State.OFF) MChip("App info") { appInfo() }
                }
                if (msgAccess == MessageAccess.State.OFF)
                    Note("Android 13+: if the switch is greyed out, tap 'App info', open the \u22EE menu, choose 'Allow restricted settings', then come back and turn on notification access.")
                if (msgAccess == MessageAccess.State.CONNECTED) {
                    Text("Detected since Yasmin started: WhatsApp ${msgStats.whatsapp}, Messenger ${msgStats.messenger}", fontSize = 13.sp)
                    if (msgStats.lastTime > 0) Text(
                        "Last: ${msgStats.lastApp}, ${msgStats.lastChat}, " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(msgStats.lastTime)),
                        fontSize = 12.sp, color = YasminUi.Muted
                    )
                }
                Text("Saved on this phone: WhatsApp ${stored.whatsappChats} chats, Messenger ${stored.messengerChats} chats, ${stored.messages} messages. " +
                    "Waiting to be passed to the Main Brain (it runs inside the background service): ${stored.pending}.", fontSize = 13.sp)
                if (stored.messages > 0) MChip(if (confirmDelete) "Tap again to delete everything" else "Delete message history", YasminUi.Red) {
                    if (confirmDelete) { MessageProcessor.clearAll(ctx); confirmDelete = false } else { confirmDelete = true }
                }
                Text("Yasmin replies automatically only if you turn on 'Automatic replies' below. Messages are saved only in this app's private storage (not backed up, not uploaded by the storage itself; the WhatsApp agent's AI drafting below is the only thing that sends message text anywhere, and it can be switched off), " +
                    "the newest ${MessageStore.MAX_PER_CONVERSATION} per chat are kept, messages that were already handled are deleted after ${MessageStore.RETENTION_DAYS} days, and they stay saved if you turn notification access off until you delete them here.", fontSize = 11.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Main Brain (task coordinator)", fontSize = 15.sp, color = YasminUi.Cyan)
                Text(
                    if (brain.running) "Running inside the background service." else "Not running. It starts and stops with the background service.",
                    fontSize = 13.sp, color = if (brain.running) YasminUi.Green else YasminUi.Muted
                )
                Text(
                    if (brain.agents.isEmpty()) "Agents: none registered right now (they are registered while the Main Brain runs)."
                    else "Agents: " + brain.agents.joinToString { it.displayName },
                    fontSize = 13.sp
                )
                if (brain.running && brain.agents.none { MessageSource.MESSENGER in it.sources })
                    Text("The Messenger agent is not registered right now: Messenger tasks wait.", fontSize = 12.sp, color = YasminUi.Muted)
                Text(
                    "Queue (WhatsApp and Messenger together, one task at a time): waiting ${brain.tasks.pending} (${brain.tasks.waitingForAgent} of them have no agent yet), running ${brain.tasks.running}, " +
                        "done ${brain.tasks.completed}, failed ${brain.tasks.failed}, cancelled ${brain.tasks.cancelled}.",
                    fontSize = 13.sp
                )
                if (brain.tasks.uncertainSends > 0)
                    Text("${brain.tasks.uncertainSends} send(s) may or may not have gone out. They are never repeated automatically; please check the chat.", fontSize = 12.sp, color = YasminUi.Red)
                Text(
                    "Owner check (${ReplySettings.delayMs() / 1000} s wait for Mehdi to answer by hand): waiting ${brain.owner.waiting}, no sign of a manual reply ${brain.owner.eligible}, " +
                        "owner replied ${brain.owner.ownerReplied}, held as uncertain ${brain.owner.uncertain} (conversations right now).",
                    fontSize = 13.sp
                )
                Text("A manual reply is only seen when Android shows it in the chat's notification. If the chat was opened or the notification vanished, Yasmin cannot tell whether you replied, so it holds back instead of guessing.",
                    fontSize = 11.sp, color = YasminUi.Muted)
                Text("Pause after each confirmed send: ${sendDelayMs / 1000} s (both apps together). It never delays drafting.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in listOf(2L, 3L, 4L, 5L)) MChip("$s s", if (sendDelayMs == s * 1000L) YasminUi.Green else YasminUi.Muted) {
                        TaskManager.setSendDelayMs(ctx, s * 1000L); sendDelayMs = TaskManager.sendDelayMs(ctx)
                    }
                }
                Text("The Main Brain sorts messages into tasks per app and conversation and the Task Manager runs them in order, one at a time, for both apps. Only the Automatic replies section below can make it send. " +
                    "Tasks for an app without an agent wait and are dropped after 6 hours. Task records contain no message text.", fontSize = 11.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Automatic replies (WhatsApp and Messenger)", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("The master switch, the WhatsApp and Messenger switches, Pause / Resume / Stop all and the conversation controls are on the Automation screen (side menu > Automation).", fontSize = 13.sp)
                MChip("Open Automation screen", YasminUi.Cyan) { go(Page.AUTOMATION) }
                Text(
                    if (!autoOn) "Off or paused. Nothing is sent." else "On. Confirmed replies ${autoStats.confirmed}, closing messages ${autoStats.closing}, " +
                        "not sent ${autoStats.notSent}, uncertain ${autoStats.uncertain} (since the background service started)." +
                        (autoStats.lastCode?.let { " Last reason: $it." } ?: ""),
                    fontSize = 13.sp, color = if (autoOn) YasminUi.Green else YasminUi.Muted
                )
                if (autoOn && !(waDrafts || msgrDrafts)) Note("Both 'Write reply drafts with Gemini' switches are off, so there is nothing to send.")
                if (autoOn && msgAccess != MessageAccess.State.CONNECTED) Note("Notification access is not connected: replies are sent through the chat notification's Reply button, so nothing can be sent.")
                if (autoStats.uncertain > 0 || brain.tasks.uncertainSends > 0)
                    Text("Some sends could not be verified: they may have reached the person. They are never repeated and they count against the limit. Please check the chat.", fontSize = 12.sp, color = YasminUi.Red)
                Text("Rules: only a private chat where the other person wrote first, and only as an answer to their newest message; never in groups; at most ${AutoReplyLimits.maxText()} automatic replies per person per app, " +
                    "then one closing message; that person is answered again 1 hour later (Unlimited: always); if you answer yourself, or Yasmin cannot tell whether you did, it does not send. " +
                    "Replies go out through the chat notification's own Reply button only, and only when the app shows its own new message afterwards is a send counted as confirmed. " +
                    "A chat without a Reply button (for example some Messenger Lite notifications, or a chat you already opened) is never answered.",
                    fontSize = 11.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Privacy and conversations", fontSize = 15.sp, color = YasminUi.Cyan)
                Text(
                    "Each person on each app is a separate conversation with its own state and its own reply count. Held back right now: " +
                        "paused ${lifeCounts.paused} (you answered by hand), completed ${lifeCounts.completed} (reply limit reached), blocked ${lifeCounts.blocked}.",
                    fontSize = 13.sp
                )
                Text(
                    "Replies stopped by the privacy check: ${privacyStats.repliesBlocked}, privacy refusals sent: ${privacyStats.refusals} (since the background service started)." +
                        (privacyStats.lastCode?.let { " Last reason: $it." } ?: ""),
                    fontSize = 13.sp, color = if (privacyStats.repliesBlocked > 0) YasminUi.Amber else YasminUi.Muted
                )
                if (lifeCounts.paused > 0) MChip("Resume all paused conversations", YasminUi.Amber) { ConversationLifecycle.resumePausedAsync(ctx) }
                MField(holdName, { holdName = it.take(80) }, "Chat name, exactly as the app shows it")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip("Block on WhatsApp", YasminUi.Red, enabled = holdName.isNotBlank()) { ConversationLifecycle.setBlockedAsync(ctx, MessageSource.WHATSAPP, holdName, true) }
                    MChip("Block on Messenger", YasminUi.Red, enabled = holdName.isNotBlank()) { ConversationLifecycle.setBlockedAsync(ctx, MessageSource.MESSENGER, holdName, true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip("Unblock on WhatsApp", YasminUi.Green, enabled = holdName.isNotBlank()) { ConversationLifecycle.setBlockedAsync(ctx, MessageSource.WHATSAPP, holdName, false) }
                    MChip("Unblock on Messenger", YasminUi.Green, enabled = holdName.isNotBlank()) { ConversationLifecycle.setBlockedAsync(ctx, MessageSource.MESSENGER, holdName, false) }
                }
                Text(
                    "Blocking also deletes the saved messages of that chat. Unblocking lifts any hold on that person (paused, completed or blocked); someone who already reached the reply limit (${AutoReplyLimits.maxText()}) still gets no more. " +
                        "If you answer a person by hand while Yasmin is handling their message, that conversation is paused until you resume it here. " +
                        "Questions about who else wrote to you get the fixed refusal. Every AI reply is checked against the names and messages of your other chats, your API key and Yasmin's own instructions before it can be sent; " +
                        "a reply that fails is dropped and nothing is sent. The check compares exact words and cannot catch a leak that was reworded; the AI only ever sees the one chat it answers.",
                    fontSize = 11.sp, color = YasminUi.Muted
                )
            }
            Panel {
                Text("WhatsApp Master Agent", fontSize = 15.sp, color = YasminUi.Cyan)
                SwitchRow("Write reply drafts with Gemini. This sends the text of the new WhatsApp message and a few earlier messages of that same chat to Google's Gemini API (names are replaced by labels). Off = nothing is sent.", waDrafts) {
                    waDrafts = it; Prefs.putBool(ctx, Prefs.WA_DRAFTS, it)
                }
                Text(
                    if (brain.agents.any { it.id == AgentIds.WHATSAPP_MASTER }) "Registered. Drafts prepared ${waStats.draftsPrepared}, messages without a draft ${waStats.noDraft}, failed ${waStats.failedRuns} (since the background service started)."
                    else "Not registered (it runs inside the Main Brain, which runs inside the background service).",
                    fontSize = 13.sp, color = if (brain.agents.any { it.id == AgentIds.WHATSAPP_MASTER }) YasminUi.Green else YasminUi.Muted
                )
                Text("Drafts are kept in memory only and are sent only through the Automatic replies switch above. Not implemented: " +
                    WhatsAppCapabilities.ALL.filter { it.support == ActionSupport.NOT_IMPLEMENTED }.joinToString("; ") { it.name.replaceFirstChar { c -> c.lowercase() } } + ".",
                    fontSize = 11.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Messenger Master Agent", fontSize = 15.sp, color = YasminUi.Cyan)
                SwitchRow("Write reply drafts with Gemini. This sends the text of the new Messenger message and a few earlier messages of that same chat to Google's Gemini API (names are replaced by labels). Off = nothing is sent.", msgrDrafts) {
                    msgrDrafts = it; Prefs.putBool(ctx, Prefs.MSGR_DRAFTS, it)
                }
                Text(
                    if (brain.agents.any { it.id == AgentIds.MESSENGER_MASTER }) "Registered. Drafts prepared ${msgrStats.draftsPrepared}, messages without a draft ${msgrStats.noDraft}, failed ${msgrStats.failedRuns} (since the background service started)."
                    else "Not registered (it runs inside the Main Brain, which runs inside the background service).",
                    fontSize = 13.sp, color = if (brain.agents.any { it.id == AgentIds.MESSENGER_MASTER }) YasminUi.Green else YasminUi.Muted
                )
                Text("Drafts are kept in memory only and are sent only through the Automatic replies switch above. Not implemented: " +
                    MessengerCapabilities.ALL.filter { it.support == ActionSupport.NOT_IMPLEMENTED }.joinToString("; ") { it.name.replaceFirstChar { c -> c.lowercase() } } + ".",
                    fontSize = 11.sp, color = YasminUi.Muted)
            }
            Panel {
                Text("Owner & Guest voice recognition", fontSize = 15.sp, color = YasminUi.Cyan)
                SwitchRow("Recognize my voice as the Owner. Other voices are treated politely as Guests.", on) {
                    on = it; VoiceId.setEnabled(ctx, it)
                    if (running) Toast.makeText(ctx, "Applies from the next START", Toast.LENGTH_SHORT).show()
                }
                Text(
                    if (n == 0) "No voice profile yet. Record at least ${VoiceId.MIN_SAMPLES} samples of your voice."
                    else if (n < VoiceId.MIN_SAMPLES) "$n of ${VoiceId.MIN_SAMPLES} samples saved. Record ${VoiceId.MIN_SAMPLES - n} more."
                    else "Voice profile ready: $n samples, saved only on this phone.",
                    fontSize = 13.sp, color = if (n >= VoiceId.MIN_SAMPLES) YasminUi.Green else YasminUi.Muted
                )
                Text("Record ${VoiceId.MIN_SAMPLES} to ${VoiceId.MAX_SAMPLES} samples of ${VoiceId.SAMPLE_SECONDS} seconds each, in a quiet place, ideally at different times. Each recording has a different Bengali text.", fontSize = 12.sp, color = YasminUi.Muted)
                if (n < VoiceId.MAX_SAMPLES) {
                    Text("রেকর্ডিং ${n + 1}", fontSize = 13.sp, color = YasminUi.Cyan)
                    Text(VoiceId.HOW_TO_BN, fontSize = 12.sp, color = YasminUi.Muted)
                    Text(VoiceId.enrollTextFor(n), fontSize = 16.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MChip(if (busy) "Recording..." else "Record sample", enabled = !busy && !running && n < VoiceId.MAX_SAMPLES) { VoiceId.recordSample(ctx) }
                    if (n > 0) MChip("Reset profile", YasminUi.Red, enabled = !busy) { VoiceId.reset(ctx) }
                }
                if (running) Note("Stop Yasmin to record new samples.")
                if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = YasminUi.Cyan)
            }
            Panel {
                Text("Minimum voice match for Owner: ${thr.roundToInt()}%", fontSize = 15.sp)
                Slider(
                    value = thr, onValueChange = { thr = it }, valueRange = 0f..100f,
                    onValueChangeFinished = { VoiceId.setThreshold(ctx, thr.roundToInt()) },
                    colors = SliderDefaults.colors(
                        thumbColor = YasminUi.Cyan, activeTrackColor = YasminUi.Cyan, inactiveTrackColor = YasminUi.Line
                    )
                )
                Text("Below this score the speaker is treated as an unknown Guest. Higher = stricter.", fontSize = 12.sp, color = YasminUi.Muted)
                Text(
                    if (last >= 0) "Last voice heard: $last% match, " + (if (who == VoiceId.Who.OWNER) "Owner" else "Guest")
                    else "Last voice heard: nothing yet. Start Yasmin and talk to see the match score here.",
                    fontSize = 13.sp, color = YasminUi.Cyan
                )
                Text("The match score shows how closely a voice resembles your saved profile. It is a similarity score calculated on this phone, not a guaranteed probability. Very short phrases (under 1.5 s of speech) are not judged.", fontSize = 11.sp, color = YasminUi.Muted)
            }
            if (n >= VoiceId.MIN_SAMPLES) Panel {
                Text("Test my voice", fontSize = 15.sp, color = YasminUi.Cyan)
                Text("Check that you are accepted, then let another person try: they should be rejected. Raise the slider if others pass, lower it if you are rejected.", fontSize = 12.sp, color = YasminUi.Muted)
                Text(VoiceId.HOW_TO_TEST_BN, fontSize = 12.sp, color = YasminUi.Muted)
                Text(VoiceId.testTextFor(tNo), fontSize = 16.sp)
                MChip(if (busy) "Please wait..." else "Test my voice", enabled = !busy && !running) { VoiceId.testVoice(ctx) }
                if (tPct >= 0) Text("Score: $tPct%  (limit ${thr.roundToInt()}%)", fontSize = 13.sp, color = if (tPct >= thr.roundToInt()) YasminUi.Green else YasminUi.Amber)
                if (tMsg.isNotBlank()) Text(tMsg, fontSize = 13.sp, color = YasminUi.Cyan)
            }
        }
    }

    // ================================================================================================ edge glow (v2.38)

    @Composable
    private fun EdgeGlowPage(t: Int) {
        val ctx: Context = this@MainActivity
        val c0 = remember { EdgeGlow.load(ctx) }
        var on by remember { mutableStateOf(c0.on) }
        var mode by remember { mutableStateOf(c0.mode) }
        var bright by remember { mutableFloatStateOf(c0.bright.toFloat()) }
        var width by remember { mutableFloatStateOf(c0.width.toFloat()) }
        var speed by remember { mutableFloatStateOf(c0.speed.toFloat()) }
        var color by remember { mutableStateOf(c0.color) }
        var hue by remember { mutableFloatStateOf(c0.hue.toFloat()) }
        val ovl = remember(t) { overlayOk() }
        val askOverlay = { try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Throwable) {} }
        val dim = if (on) 1f else 0.4f
        val colorChoices = listOf("rainbow", "cyan", "blue", "purple", "pink", "red", "orange", "green", "white", "custom")

        PageFrame("Edge Glow") {
            Panel {
                SwitchRow("Edge Glow: a light around the screen edge while Yasmin is on. It keeps glowing over other apps and the home screen until you switch this off.", on) {
                    on = it; Prefs.putBool(ctx, EdgeGlow.K_ON, it); EdgeGlow.reload(ctx)
                    if (it && !overlayOk()) askOverlay()
                }
                if (!ovl) PermRow("Display over other apps (needed for the glow)", false) { askOverlay() }
                Note("The glow shows only while Yasmin is running and awake. Switch it off here and it disappears at once.")
            }
            Column(Modifier.graphicsLayer { alpha = dim }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Panel {
                    Text("When should it glow?", fontSize = 15.sp, color = YasminUi.Cyan)
                    listOf(
                        Triple(EdgeGlow.MODE_ALWAYS, "Always on", "Glows all the time while Yasmin is on."),
                        Triple(EdgeGlow.MODE_SPEAK, "Only while she speaks", "The light comes up slowly with her voice, follows how loud she is, and fades away when she stops.")
                    ).forEach { (m, title, note) ->
                        val sel = mode == m
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (sel) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable(enabled = on) { mode = m; Prefs.put(ctx, EdgeGlow.K_MODE, m); EdgeGlow.reload(ctx) }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontSize = 16.sp, color = if (sel) YasminUi.Cyan else YasminUi.Text)
                                Text(note, fontSize = 12.sp, color = YasminUi.Muted)
                            }
                            if (sel) MIcon(Ic.CHECK, YasminUi.Cyan, 20.dp)
                        }
                    }
                }
                Panel {
                    Text("Colour", fontSize = 15.sp, color = YasminUi.Cyan)
                    colorChoices.chunked(5).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { name ->
                                val sel = color == name
                                val fill: Brush = when (name) {
                                    "rainbow" -> Brush.sweepGradient(EdgeGlow.RAINBOW.map { Color(it) })
                                    "custom" -> Brush.sweepGradient((0..6).map { Color(EdgeGlow.colorOf("custom", it * 60)) })
                                    else -> SolidColor(Color(EdgeGlow.colorOf(name, 0)))
                                }
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape).background(fill)
                                        .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else YasminUi.Line, CircleShape)
                                        .clickable(enabled = on) { color = name; Prefs.put(ctx, EdgeGlow.K_COLOR, name); EdgeGlow.reload(ctx) }
                                )
                            }
                        }
                    }
                    Text(if (color == "rainbow") "Rainbow (moves around the screen)" else if (color == "custom") "Custom colour (use the slider below)" else color.replaceFirstChar { it.uppercase() }, fontSize = 12.sp, color = YasminUi.Muted)
                    if (color == "custom") {
                        Slider(
                            value = hue, onValueChange = { hue = it; Prefs.putInt(ctx, EdgeGlow.K_HUE, it.roundToInt()); EdgeGlow.reload(ctx) }, valueRange = 0f..359f, enabled = on,
                            colors = SliderDefaults.colors(thumbColor = Color(EdgeGlow.colorOf("custom", hue.roundToInt())), activeTrackColor = Color(EdgeGlow.colorOf("custom", hue.roundToInt())), inactiveTrackColor = YasminUi.Line)
                        )
                    }
                }
                Panel {
                    Text("Light: ${bright.roundToInt()}%", fontSize = 15.sp, color = YasminUi.Cyan)
                    Slider(
                        value = bright, onValueChange = { bright = it; Prefs.putInt(ctx, EdgeGlow.K_BRIGHT, it.roundToInt()); EdgeGlow.reload(ctx) }, valueRange = 10f..100f, enabled = on,
                        colors = SliderDefaults.colors(thumbColor = YasminUi.Cyan, activeTrackColor = YasminUi.Cyan, inactiveTrackColor = YasminUi.Line)
                    )
                    Text("Thickness: ${width.roundToInt()} dp", fontSize = 15.sp, color = YasminUi.Cyan)
                    Slider(
                        value = width, onValueChange = { width = it; Prefs.putInt(ctx, EdgeGlow.K_WIDTH, it.roundToInt()); EdgeGlow.reload(ctx) }, valueRange = 2f..24f, enabled = on,
                        colors = SliderDefaults.colors(thumbColor = YasminUi.Cyan, activeTrackColor = YasminUi.Cyan, inactiveTrackColor = YasminUi.Line)
                    )
                    if (color == "rainbow") {
                        Text("Rainbow speed: " + (if (speed.roundToInt() == 0) "still" else "${speed.roundToInt()}%"), fontSize = 15.sp, color = YasminUi.Cyan)
                        Slider(
                            value = speed, onValueChange = { speed = it; Prefs.putInt(ctx, EdgeGlow.K_SPEED, it.roundToInt()); EdgeGlow.reload(ctx) }, valueRange = 0f..100f, enabled = on,
                            colors = SliderDefaults.colors(thumbColor = YasminUi.Cyan, activeTrackColor = YasminUi.Cyan, inactiveTrackColor = YasminUi.Line)
                        )
                    }
                }
                Panel {
                    Text("Test", fontSize = 15.sp, color = YasminUi.Cyan)
                    Text("Shows the glow for 5 seconds with your current settings, even when Yasmin is not running.", fontSize = 12.sp, color = YasminUi.Muted)
                    MChip("Test glow", enabled = on) { if (overlayOk()) EdgeGlow.preview(ctx) else askOverlay() }
                }
            }
        }
    }

    // ================================================================================================ small pieces

    @Composable
    private fun Note(text: String) = Text(text, fontSize = 12.sp, color = YasminUi.Amber)

    @Composable
    private fun PermRow(label: String, ok: Boolean, onClick: () -> Unit) {
        val tone by animateColorAsState(if (ok) YasminUi.Green else YasminUi.Amber, label = "perm")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(tone.copy(alpha = 0.15f)).border(1.dp, tone.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                if (ok) MIcon(Ic.CHECK, tone, 16.dp) else Text("!", color = tone, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            if (!ok) { Spacer(Modifier.width(8.dp)); MChip("Allow", onClick = onClick) }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1A1305), checkedTrackColor = YasminUi.Cyan,
                    uncheckedThumbColor = YasminUi.Muted, uncheckedTrackColor = YasminUi.Card, uncheckedBorderColor = YasminUi.Line
                )
            )
        }
    }

    @Composable
    private fun MField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false, singleLine: Boolean = true) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = YasminUi.Cyan, unfocusedBorderColor = YasminUi.Line,
                focusedTextColor = YasminUi.Text, unfocusedTextColor = YasminUi.Text,
                cursorColor = YasminUi.Cyan, focusedLabelColor = YasminUi.Cyan, unfocusedLabelColor = YasminUi.Muted
            )
        )
    }
}
