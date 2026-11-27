package ai.yasmin.assistant

import androidx.compose.ui.text.withStyle
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Colours of the Yasmin look: near-black background, deep-blue glass cards, cyan / blue / violet accents. */
object YasminUi {
    val Bg = Color(0xFF010713)          // deep midnight navy
    val Card = Color(0xFF071228)
    val CardHi = Color(0xFF0B1B3D)
    val Line = Color(0xFF16336E)        // blue hairline
    val Cyan = Color(0xFF38B6FF)        // neon blue (main accent)
    val Blue = Color(0xFF2F6BFF)        // electric blue
    val Violet = Color(0xFF8A6CFF)      // soft violet
    val Text = Color(0xFFEAF2FF)        // cool white
    val Muted = Color(0xFF8DA2C8)
    val Dim = Color(0xFF4A5B80)
    val Green = Color(0xFF63D6A0)
    val Amber = Color(0xFFF0BE6A)
    val Red = Color(0xFFEA6F66)
    val Gold = Cyan
    val GoldBrush = Brush.linearGradient(listOf(Color(0xFF7FD6FF), Color(0xFF2F8BFF), Color(0xFF2F4BFF)))
}

@Composable
fun YasminTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = YasminUi.Cyan, secondary = YasminUi.Violet, background = YasminUi.Bg, surface = YasminUi.Card,
            surfaceVariant = YasminUi.CardHi, outline = YasminUi.Line,
            onPrimary = Color.Black, onBackground = YasminUi.Text, onSurface = YasminUi.Text
        )
    ) { Surface(Modifier.fillMaxSize(), color = YasminUi.Bg, content = content) }
}

fun emoColor(e: String) = when (e.lowercase()) {
    "happy" -> Color(0xFFFFB300)
    "excited" -> Color(0xFFFF4D8D)
    "sad" -> Color(0xFF4A90E2)
    "love" -> Color(0xFFFF6FB5)
    "surprised" -> Color(0xFF2EE6C5)
    "angry" -> Color(0xFFFF5252)
    "jealous" -> Color(0xFF9C6BFF)
    "sulky" -> Color(0xFFFF8A65)
    "playful" -> Color(0xFFFFD54F)
    "worried" -> Color(0xFF7FB3D5)
    else -> YasminUi.Cyan
}

/** Switch the running session to another Live model (or remember it for the next start). Same behaviour as before. */
object ModelPick {
    fun apply(ctx: Context, id: String) {
        Prefs.put(ctx, Prefs.MODEL, id)
        if (AppState.running.value)
            ctx.startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_SWITCH).putExtra("model", id))
    }
}

fun statusLabel(running: Boolean, online: Boolean, switching: Boolean, mode: AssistantMode = AssistantMode.ACTIVE): String = when {
    running && mode == AssistantMode.SLEEP -> "Sleeping"
    switching -> "Switching model"
    online -> "Online"
    running -> "Connecting..."
    else -> "Offline"
}

// ------------------------------------------------------------------------------------------------ icons (drawn, no icon library needed)

enum class Ic { MENU, BACK, CHEVRON, BOLT, SHIELD, WAVE, KEY, CHAT, MIC, SCREEN, ROBOT, CHECK, HEART, SLIDERS, STAR, BRAIN, SWAP, CAMERA }

@Composable
fun MIcon(kind: Ic, tint: Color = YasminUi.Cyan, dp: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(dp)) {
        val s = this.size.minDimension
        val st = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun poly(close: Boolean, vararg pts: Pair<Float, Float>) {
            val path = Path()
            pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.first * s, pt.second * s) else path.lineTo(pt.first * s, pt.second * s) }
            if (close) path.close()
            drawPath(path, tint, style = st)
        }
        when (kind) {
            Ic.MENU -> {
                poly(false, 0.2f to 0.28f, 0.8f to 0.28f)
                poly(false, 0.2f to 0.5f, 0.8f to 0.5f)
                poly(false, 0.2f to 0.72f, 0.8f to 0.72f)
            }
            Ic.BACK -> poly(false, 0.62f to 0.2f, 0.36f to 0.5f, 0.62f to 0.8f)
            Ic.CHEVRON -> poly(false, 0.38f to 0.2f, 0.64f to 0.5f, 0.38f to 0.8f)
            Ic.CHECK -> poly(false, 0.2f to 0.52f, 0.42f to 0.72f, 0.8f to 0.3f)
            Ic.BOLT -> poly(true, 0.58f to 0.06f, 0.22f to 0.56f, 0.46f to 0.56f, 0.40f to 0.94f, 0.78f to 0.40f, 0.54f to 0.40f)
            Ic.SHIELD -> {
                val p = Path()
                p.moveTo(0.5f * s, 0.08f * s)
                p.lineTo(0.85f * s, 0.2f * s)
                p.lineTo(0.85f * s, 0.5f * s)
                p.cubicTo(0.85f * s, 0.75f * s, 0.68f * s, 0.88f * s, 0.5f * s, 0.94f * s)
                p.cubicTo(0.32f * s, 0.88f * s, 0.15f * s, 0.75f * s, 0.15f * s, 0.5f * s)
                p.lineTo(0.15f * s, 0.2f * s)
                p.close()
                drawPath(p, tint, style = st)
                poly(false, 0.34f to 0.5f, 0.46f to 0.62f, 0.68f to 0.38f)
            }
            Ic.WAVE -> {
                val hs = floatArrayOf(0.2f, 0.45f, 0.7f, 1f, 0.6f, 0.4f, 0.2f)
                for (i in hs.indices) {
                    val x = (0.14f + i * 0.12f) * s
                    val h = hs[i] * 0.42f * s
                    drawLine(tint, Offset(x, s * 0.5f - h), Offset(x, s * 0.5f + h), strokeWidth = s * 0.075f, cap = StrokeCap.Round)
                }
            }
            Ic.KEY -> {
                drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.31f, s * 0.31f), style = st)
                poly(false, 0.44f to 0.44f, 0.86f to 0.86f)
                poly(false, 0.66f to 0.66f, 0.77f to 0.55f)
                poly(false, 0.76f to 0.76f, 0.87f to 0.65f)
            }
            Ic.CHAT -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.1f, s * 0.12f), size = Size(s * 0.8f, s * 0.62f), cornerRadius = CornerRadius(s * 0.2f), style = st)
                poly(false, 0.3f to 0.72f, 0.3f to 0.9f, 0.5f to 0.74f)
                for (x in floatArrayOf(0.34f, 0.5f, 0.66f)) drawCircle(tint, radius = s * 0.045f, center = Offset(s * x, s * 0.43f))
            }
            Ic.MIC -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.36f, s * 0.08f), size = Size(s * 0.28f, s * 0.5f), cornerRadius = CornerRadius(s * 0.14f), style = st)
                drawArc(tint, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(s * 0.24f, s * 0.26f), size = Size(s * 0.52f, s * 0.46f), style = st)
                poly(false, 0.5f to 0.72f, 0.5f to 0.9f)
                poly(false, 0.36f to 0.9f, 0.64f to 0.9f)
            }
            Ic.SCREEN -> {
                poly(false, 0.12f to 0.36f, 0.12f to 0.12f, 0.36f to 0.12f)
                poly(false, 0.64f to 0.12f, 0.88f to 0.12f, 0.88f to 0.36f)
                poly(false, 0.12f to 0.64f, 0.12f to 0.88f, 0.36f to 0.88f)
                poly(false, 0.64f to 0.88f, 0.88f to 0.88f, 0.88f to 0.64f)
            }
            Ic.ROBOT -> {
                drawCircle(tint, radius = s * 0.34f, center = Offset(s * 0.5f, s * 0.5f))
                drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.1f, s * 0.5f))
                drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.9f, s * 0.5f))
                drawCircle(YasminUi.Bg, radius = s * 0.055f, center = Offset(s * 0.38f, s * 0.45f))
                drawCircle(YasminUi.Bg, radius = s * 0.055f, center = Offset(s * 0.62f, s * 0.45f))
                drawArc(YasminUi.Bg, startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(s * 0.36f, s * 0.46f), size = Size(s * 0.28f, s * 0.2f),
                    style = Stroke(width = s * 0.05f, cap = StrokeCap.Round))
            }
            Ic.HEART -> {
                val p = Path()
                p.moveTo(0.5f * s, 0.86f * s)
                p.cubicTo(0.04f * s, 0.54f * s, 0.14f * s, 0.12f * s, 0.5f * s, 0.34f * s)
                p.cubicTo(0.86f * s, 0.12f * s, 0.96f * s, 0.54f * s, 0.5f * s, 0.86f * s)
                drawPath(p, tint, style = st)
            }
            Ic.SLIDERS -> {
                poly(false, 0.14f to 0.25f, 0.86f to 0.25f)
                poly(false, 0.14f to 0.5f, 0.86f to 0.5f)
                poly(false, 0.14f to 0.75f, 0.86f to 0.75f)
                drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.68f, s * 0.25f))
                drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.34f, s * 0.5f))
                drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.6f, s * 0.75f))
            }
            Ic.BRAIN -> {
                drawCircle(tint, radius = s * 0.2f, center = Offset(s * 0.35f, s * 0.36f), style = st)
                drawCircle(tint, radius = s * 0.2f, center = Offset(s * 0.65f, s * 0.36f), style = st)
                drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.37f, s * 0.64f), style = st)
                drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.63f, s * 0.64f), style = st)
                poly(false, 0.5f to 0.2f, 0.5f to 0.84f)
            }
            Ic.SWAP -> {
                poly(false, 0.14f to 0.32f, 0.86f to 0.32f)
                poly(false, 0.68f to 0.16f, 0.86f to 0.32f, 0.68f to 0.48f)
                poly(false, 0.86f to 0.68f, 0.14f to 0.68f)
                poly(false, 0.32f to 0.52f, 0.14f to 0.68f, 0.32f to 0.84f)
            }
            Ic.CAMERA -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.1f, s * 0.28f), size = Size(s * 0.8f, s * 0.58f), cornerRadius = CornerRadius(s * 0.12f), style = st)
                poly(false, 0.32f to 0.28f, 0.38f to 0.16f, 0.62f to 0.16f, 0.68f to 0.28f)
                drawCircle(tint, radius = s * 0.16f, center = Offset(s * 0.5f, s * 0.57f), style = st)
            }
            Ic.STAR -> {
                val p = Path()
                p.moveTo(0.5f * s, 0.1f * s)
                p.cubicTo(0.52f * s, 0.38f * s, 0.62f * s, 0.48f * s, 0.9f * s, 0.5f * s)
                p.cubicTo(0.62f * s, 0.52f * s, 0.52f * s, 0.62f * s, 0.5f * s, 0.9f * s)
                p.cubicTo(0.48f * s, 0.62f * s, 0.38f * s, 0.52f * s, 0.1f * s, 0.5f * s)
                p.cubicTo(0.38f * s, 0.48f * s, 0.48f * s, 0.38f * s, 0.5f * s, 0.1f * s)
                p.close()
                drawPath(p, tint)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ building blocks

/** Round logo. If you add a picture named yasmin_logo (png / webp) to res/drawable it is used; otherwise a glowing badge is drawn. */
@Composable
fun YasminLogo(dp: Dp) {
    val ctx = LocalContext.current
    val res = remember { ctx.resources.getIdentifier("yasmin_logo", "drawable", ctx.packageName) }
    if (res != 0) {
        Image(painterResource(res), contentDescription = "Yasmin", contentScale = ContentScale.Crop,
            modifier = Modifier.size(dp).clip(CircleShape).border(1.5.dp, YasminUi.GoldBrush, CircleShape))
    } else {
        Canvas(Modifier.size(dp)) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3A2D14), Color(0xFF181208), Color(0xFF070604)), center = center, radius = r), radius = r)
            drawCircle(Brush.sweepGradient(listOf(YasminUi.Cyan, YasminUi.Violet, YasminUi.Blue, YasminUi.Cyan), center), radius = r - 1.5f, style = Stroke(width = r * 0.07f))
            val p = Path()
            p.moveTo(center.x - r * 0.45f, center.y + r * 0.35f)
            p.cubicTo(center.x - r * 0.5f, center.y - r * 0.6f, center.x + r * 0.35f, center.y - r * 0.7f, center.x + r * 0.42f, center.y + r * 0.05f)
            drawPath(p, Color(0xE6F3DDA6), style = Stroke(width = r * 0.11f, cap = StrokeCap.Round))
            drawCircle(Color(0xFFFFF3D4), radius = r * 0.09f, center = Offset(center.x + r * 0.08f, center.y - r * 0.02f))
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val base = modifier.clip(shape)
        .background(Brush.verticalGradient(listOf(YasminUi.CardHi, YasminUi.Card)))
        .border(1.dp, Brush.linearGradient(listOf(YasminUi.Cyan.copy(alpha = 0.38f), YasminUi.Line, YasminUi.Blue.copy(alpha = 0.22f))), shape)
    Box(if (onClick != null) base.clickable(onClick = onClick) else base) { content() }
}

@Composable
fun IconButtonBox(icon: Ic, round: Boolean = false, tint: Color = YasminUi.Cyan, onClick: () -> Unit) {
    val shape = if (round) CircleShape else RoundedCornerShape(16.dp)
    Box(
        Modifier.size(44.dp).clip(shape).background(Brush.verticalGradient(listOf(YasminUi.CardHi, YasminUi.Card)))
            .border(1.dp, tint.copy(alpha = 0.35f), shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { MIcon(icon, tint, 21.dp) }
}

/** Small tinted pill button. */
@Composable
fun MChip(text: String, color: Color = YasminUi.Cyan, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.55f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 11.sp, letterSpacing = 1.2.sp, color = if (enabled) color else color.copy(alpha = 0.4f), maxLines = 1) }
}

/** Header used on every screen: left button, centre status, bolt button (opens the model menu). */
@Composable
fun YasminTopBar(left: Ic, onLeft: () -> Unit, online: Boolean, label: String, running: Boolean, onBolt: () -> Unit, onVoice: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButtonBox(left, onClick = onLeft)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(YasminUi.Card).border(1.dp, YasminUi.Line, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) YasminUi.Green else if (running) YasminUi.Amber else YasminUi.Dim))
            Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), color = if (online) YasminUi.Cyan else YasminUi.Muted, fontSize = 11.sp, letterSpacing = 1.6.sp)
        }
        Spacer(Modifier.weight(1f))
        if (onVoice != null) {   // voice switch: Gemini voice / ElevenLabs voice
            IconButtonBox(Ic.WAVE, round = true, onClick = onVoice)
            Spacer(Modifier.width(8.dp))
        }
        IconButtonBox(Ic.BOLT, round = true, onClick = onBolt)
    }
}

private val modelTints = listOf(Color(0xFF38B6FF), Color(0xFF8A6CFF), Color(0xFF63D6A0))

/** "Select Model" dropdown. The models are the verified Live models from LiveModels.ALL. Place it inside a full-screen Box. */
@Composable
fun BoxScope.ModelMenu(visible: Boolean, selected: String, locked: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit, top: Dp = 66.dp) {
    if (visible) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = top, end = 16.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
    ) {
        GlassCard(Modifier.width(250.dp)) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Select Model", color = YasminUi.Cyan, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LiveModels.ALL.forEachIndexed { i, m ->
                    val on = selected == m.id
                    val tint = modelTints[i % modelTints.size]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(enabled = !locked) { onPick(m.id); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)).border(1.dp, tint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                            MIcon(Ic.STAR, tint, 18.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.label, color = if (on) YasminUi.Cyan else YasminUi.Text, fontSize = 16.sp)
                            Text(m.note, color = YasminUi.Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        if (on) MIcon(Ic.CHECK, YasminUi.Cyan, 18.dp)
                    }
                }
                if (locked) Text("Switching, please wait...", color = YasminUi.Amber, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

/** Voice engine switch. Needs ElevenLabs key + voice before ElevenLabs can be chosen (checked in VoicePick). */
object VoicePick {
    /** Returns false and changes nothing when ElevenLabs is picked but not set up yet. */
    fun apply(ctx: Context, engine: String): Boolean {
        if (engine == VoiceEngine.ELEVEN && !VoiceEngine.elevenReady(ctx)) return false
        Prefs.put(ctx, Prefs.ENGINE, engine)
        AppState.engine.value = engine
        if (AppState.running.value)
            ctx.startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_VOICE).putExtra("engine", engine))
        return true
    }
}

/** "Select Voice" dropdown: Gemini voice or ElevenLabs voice, switchable at any time. Place it inside a full-screen Box. */
@Composable
fun BoxScope.VoiceEngineMenu(visible: Boolean, selected: String, elevenName: String, onPick: (String) -> Unit, onDismiss: () -> Unit, top: Dp = 66.dp) {
    if (visible) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = top, end = 16.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
    ) {
        GlassCard(Modifier.width(250.dp)) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Select Voice", color = YasminUi.Cyan, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                listOf(
                    Triple(VoiceEngine.GEMINI, "Gemini voice", "Fastest, built into Gemini"),
                    Triple(VoiceEngine.ELEVEN, "ElevenLabs voice", if (elevenName.isNotBlank()) elevenName else "Female voice, needs ElevenLabs key")
                ).forEachIndexed { i, item ->
                    val on = selected == item.first
                    val tint = modelTints[i % modelTints.size]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) YasminUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onPick(item.first); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)).border(1.dp, tint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                            MIcon(Ic.WAVE, tint, 18.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.second, color = if (on) YasminUi.Cyan else YasminUi.Text, fontSize = 16.sp)
                            Text(item.third, color = YasminUi.Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        if (on) MIcon(Ic.CHECK, YasminUi.Cyan, 18.dp)
                    }
                }
            }
        }
    }
}

/** Five animated bars under "is listening..." : they move with the real microphone / speaker level. */
@Composable
fun VoiceBars(level: Float, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "bars-phase"
    )
    val lv = min(1f, level * 3.2f)
    val colors = listOf(YasminUi.Blue, YasminUi.Cyan, YasminUi.Violet, YasminUi.Cyan, YasminUi.Blue)
    val shape = floatArrayOf(0.35f, 0.7f, 1f, 0.7f, 0.35f)
    Canvas(modifier.size(width = 54.dp, height = 40.dp)) {
        val w = size.width / 5f
        for (i in 0 until 5) {
            val wave = 0.5f + 0.5f * sin(phase + i * 0.95f)
            val h = size.height * shape[i] * (0.22f + 0.78f * wave) * (0.4f + 0.6f * lv)
            val x = w * (i + 0.5f)
            drawLine(colors[i], Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** "Listening • Processing • Responding" with the active step lit. active: 0, 1 or 2 (-1 = none). */
@Composable
fun StepsRow(active: Int) {
    val names = listOf("Listening", "Processing", "Responding")
    Row(verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            if (i > 0) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(3.dp).clip(CircleShape).background(YasminUi.Blue.copy(alpha = 0.7f)))
                Spacer(Modifier.width(12.dp))
            }
            Text(n.uppercase(), fontSize = 11.sp, letterSpacing = 1.8.sp, color = if (i == active) YasminUi.Cyan else YasminUi.Dim)
        }
    }
}

/** Soft flowing lines used as the footer decoration of the home screen. */
@Composable
fun WaveFooter(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val brush = Brush.horizontalGradient(listOf(Color.Transparent, YasminUi.Blue.copy(alpha = 0.55f), YasminUi.Cyan.copy(alpha = 0.35f), Color.Transparent))
        for (k in 0..2) {
            val path = Path()
            val base = size.height * (0.35f + 0.15f * k)
            for (i in 0..40) {
                val x = size.width * i / 40f
                val y = base + sin(i / 40f * 2f * PI.toFloat() + k * 0.9f) * size.height * 0.16f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, brush, style = Stroke(width = 1.2f.dp.toPx() * (1f - k * 0.25f)))
        }
    }
}

/** The Yasmin wordmark from the design: glowing voice-wave bars + "Yasmin" + handwritten tagline. */
@Composable
fun YasminWordmark(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 44.dp, height = 46.dp)) {
                val hs = floatArrayOf(0.22f, 0.5f, 0.78f, 1f, 0.78f, 0.5f, 0.22f)
                val w = size.width / hs.size
                for (i in hs.indices) {
                    val h = size.height * hs[i]
                    val x = w * (i + 0.5f)
                    val br = Brush.verticalGradient(listOf(YasminUi.Cyan, YasminUi.Blue), startY = size.height / 2f - h / 2f, endY = size.height / 2f + h / 2f)
                    drawLine(YasminUi.Cyan.copy(alpha = 0.25f), Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(br, Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = 3.4.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = YasminUi.Text)) { append("Yasmin") }
                    
                },
                fontSize = 34.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = 1.sp,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x6638B6FF), Offset.Zero, 22f))
            )
        }
        Text("Your Voice, My World", fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive, color = YasminUi.Text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
    }
}

/** Glass pill used for the top-right buttons of the home screen (icon + text + optional trailing icon). */
@Composable
fun GlassPill(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xCC0B1B3D), Color(0xCC050E22))))
            .border(1.dp, Brush.linearGradient(listOf(YasminUi.Cyan.copy(alpha = 0.85f), YasminUi.Blue.copy(alpha = 0.45f))), shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}
