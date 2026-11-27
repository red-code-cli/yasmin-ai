package ai.yasmin.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * v2.42: Multi-character + Outfit Live2D-style 2D anime renderer.
 *
 * Keeps Yasmin's exact v2.31-2.41 look & mesh physics while adding:
 *   - 5 original anime characters via CharacterCatalog (Yasmin, Aanya, Zoya, Meera, Kaira)
 *   - 4 outfits per character (layered transparent RGBA overlays drawn on the exact same deformed mesh,
 *     or full-art variants when provided)
 *   - Per-character hair mask, background mask, face anchors, skin tones, and ear/ahoge/hair deformers
 *   - Lazy LRU bitmap cache with safe ref-counted recycling + zero per-frame object allocation for smooth 24+ fps
 */

private val TINTS = arrayOf(
    Color(0xFFFF9EC8),
    Color(0xFFB69CFF),
    Color(0xFF7FD8FF),
    Color(0xFF8CF0C8),
    Color(0xFFFFC98A),
    Color(0xFFFF8FA3)
)
private const val TINT_HOLD = 8f

private const val IMG_W = 768f
private const val IMG_H = 1376f
private const val COLS = 32
private const val ROWS = 57
private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

/**
 * Lazy LRU cache for character/outfit bitmaps & precomputed hair-weight grids.
 * Bitmaps that are currently bound to an active Compose view are ref-counted and only recycled once
 * evicted AND released, preventing Canvas "recycled bitmap" crashes and memory leaks on character switches.
 */
object CharacterBitmapCache {
    private data class Key(val resId: Int, val sampleSize: Int)

    private val lock = Any()
    private val inUse = HashMap<Key, Int>()
    private val pendingRecycle = HashSet<Bitmap>()
    private val hairWeightCache = HashMap<String, FloatArray>()

    private val lru = object : LruCache<Key, Bitmap>(28 * 1024) { // ~28 MB cap in KB
        override fun sizeOf(key: Key, value: Bitmap): Int = max(1, value.allocationByteCount / 1024)

        override fun entryRemoved(evicted: Boolean, key: Key, oldValue: Bitmap, newValue: Bitmap?) {
            if (!evicted || oldValue === newValue) return
            synchronized(lock) {
                if ((inUse[key] ?: 0) <= 0) {
                    if (!oldValue.isRecycled) oldValue.recycle()
                } else {
                    pendingRecycle.add(oldValue)
                }
            }
        }
    }

    fun acquire(ctx: Context, resId: Int, sampleSize: Int = 1): Bitmap? {
        if (resId == 0) return null
        val key = Key(resId, sampleSize)
        synchronized(lock) {
            val existing = lru.get(key)
            if (existing != null && !existing.isRecycled) {
                inUse[key] = (inUse[key] ?: 0) + 1
                pendingRecycle.remove(existing)
                return existing
            }
        }
        val decoded = try {
            BitmapFactory.decodeResource(
                ctx.resources,
                resId,
                BitmapFactory.Options().apply {
                    inScaled = false
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } catch (_: Throwable) {
            null
        } ?: return null

        synchronized(lock) {
            val race = lru.get(key)
            if (race != null && !race.isRecycled) {
                if (!decoded.isRecycled) decoded.recycle()
                inUse[key] = (inUse[key] ?: 0) + 1
                return race
            }
            inUse[key] = (inUse[key] ?: 0) + 1
            lru.put(key, decoded)
            return decoded
        }
    }

    fun release(resId: Int, sampleSize: Int = 1) {
        if (resId == 0) return
        val key = Key(resId, sampleSize)
        synchronized(lock) {
            val left = (inUse[key] ?: 0) - 1
            if (left <= 0) {
                inUse.remove(key)
                val bmp = lru.get(key)
                if (bmp != null && pendingRecycle.remove(bmp) && !bmp.isRecycled) {
                    bmp.recycle()
                }
            } else {
                inUse[key] = left
            }
        }
    }

    fun hairWeights(ctx: Context, character: CharacterSpec, bodyBmp: Bitmap): FloatArray {
        synchronized(lock) {
            hairWeightCache[character.id]?.let { return it }
        }
        val w = try {
            if (character.hairMaskRes != 0) {
                val mask = acquire(ctx, character.hairMaskRes, 1)
                if (mask != null) {
                    try {
                        computeHairWeightsFromMask(mask)
                    } finally {
                        release(character.hairMaskRes, 1)
                    }
                } else computePinkHairWeights(bodyBmp)
            } else {
                computePinkHairWeights(bodyBmp)
            }
        } catch (_: Throwable) {
            FloatArray((COLS + 1) * (ROWS + 1)) { 1f }
        }
        synchronized(lock) {
            hairWeightCache[character.id] = w
        }
        return w
    }
}

private fun sm(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun bell(v: Float, c: Float, w: Float): Float {
    val d = (v - c) / w
    return exp(-d * d)
}

private fun ell(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float {
    val a = (x - cx) / rx
    val b = (y - cy) / ry
    return 1f - sm(0.8f, 1.3f, sqrt(a * a + b * b))
}

private fun tintAt(time: Float): Color {
    val u = time / TINT_HOLD
    val i = u.toInt()
    val a = TINTS[i % TINTS.size]
    val b = TINTS[(i + 1) % TINTS.size]
    return colorLerp(a, b, sm(0.35f, 1f, u - i))
}

private fun smoothGrid(g: FloatArray): FloatArray {
    val o = FloatArray(g.size)
    for (r in 0..ROWS) for (c in 0..COLS) {
        var a = 0f
        var k = 0
        for (rr in (r - 1)..(r + 1)) for (cc in (c - 1)..(c + 1)) {
            if (rr in 0..ROWS && cc in 0..COLS) {
                a += g[rr * (COLS + 1) + cc]
                k++
            }
        }
        o[r * (COLS + 1) + c] = sm(0.15f, 0.7f, a / k)
    }
    return o
}

private fun computeHairWeightsFromMask(maskBmp: Bitmap): FloatArray {
    val w = maskBmp.width
    val h = maskBmp.height
    val px = IntArray(w * h)
    maskBmp.getPixels(px, 0, w, 0, 0, w, h)
    val g = FloatArray((COLS + 1) * (ROWS + 1))
    for (r in 0..ROWS) {
        for (c in 0..COLS) {
            val cx = (w * c / COLS).coerceIn(0, w - 1)
            val cy = (h * r / ROWS).coerceIn(0, h - 1)
            var sum = 0f
            var tot = 0
            var dy = -12
            while (dy <= 12) {
                var dx = -12
                while (dx <= 12) {
                    val xx = (cx + dx).coerceIn(0, w - 1)
                    val yy = (cy + dy).coerceIn(0, h - 1)
                    val p = px[yy * w + xx]
                    val a = (p ushr 24) and 255
                    sum += a / 255f
                    tot++
                    dx += 6
                }
                dy += 6
            }
            g[r * (COLS + 1) + c] = sum / tot
        }
    }
    return smoothGrid(g)
}

private fun computePinkHairWeights(bmp: Bitmap): FloatArray {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val g = FloatArray((COLS + 1) * (ROWS + 1))
    for (r in 0..ROWS) {
        for (c in 0..COLS) {
            val cx = (w * c / COLS).coerceIn(0, w - 1)
            val cy = (h * r / ROWS).coerceIn(0, h - 1)
            var n = 0
            var tot = 0
            var dy = -18
            while (dy <= 18) {
                var dx = -18
                while (dx <= 18) {
                    val xx = (cx + dx).coerceIn(0, w - 1)
                    val yy = (cy + dy).coerceIn(0, h - 1)
                    val p = px[yy * w + xx]
                    val rr = (p shr 16) and 255
                    val gg = (p shr 8) and 255
                    val bb = p and 255
                    tot++
                    if (rr > 190 && bb > gg + 6 && bb < rr - 10 && gg < 208) n++
                    dx += 6
                }
                dy += 6
            }
            g[r * (COLS + 1) + c] = n.toFloat() / tot
        }
    }
    return smoothGrid(g)
}

private class Pose {
    var t = 0f
    var sway = 0f
    var breath = 0f
    var earL = 0f
    var earR = 0f
    var ahoge = 0f
    var talk = 0f
    var talkSmooth = 0f
    var mouth = 0f
    var dx = 0f
    var dy = 0f

    fun update(time: Float, talkLevel: Float) {
        t = time
        sway = sin(time * TAU / 5.2f)
        breath = sin(time * TAU / 3.6f)
        earL = twitch(time, 4.9f, 0f)
        earR = twitch(time, 6.1f, 2.0f)
        ahoge = sin(time * TAU / 2.4f)
        // Only AppState.speechLevel() drives lip-sync; small noise floor ensures mouth never moves when silent / user speaks
        val cleanLevel = if (talkLevel > 0.015f) talkLevel.coerceIn(0f, 1f) else 0f
        talkSmooth += (cleanLevel - talkSmooth) * 0.18f
        talk = talkSmooth
        // Quick attack, slower release for crisp syllable lip-sync
        val target = (cleanLevel * 2.2f).coerceIn(0f, 1f)
        mouth += (target - mouth) * (if (target > mouth) 0.65f else 0.28f)
        if (mouth < 0.01f && target == 0f) mouth = 0f
    }

    private fun twitch(time: Float, period: Float, offset: Float): Float {
        val p = (time + offset) % period
        return if (p < 0.45f) sin(p / 0.45f * TAU * 1.5f) * (1f - p / 0.45f) else 0f
    }

    private fun rot(x: Float, y: Float, px: Float, py: Float, ang: Float, w: Float) {
        if (w <= 0f || ang == 0f) return
        val rx = x - px
        val ry = y - py
        val c = cos(ang)
        val s = sin(ang)
        dx += ((rx * c - ry * s) - rx) * w
        dy += ((rx * s + ry * c) - ry) * w
    }

    fun warp(x: Float, y: Float, spec: CharacterSpec, hairW: Float = 1f) {
        val a = spec.anchors
        val hz = spec.hairZone
        dx = 0f
        dy = 0f

        // 1. body sway around hips
        val wb = sm(a.hipY, a.headBaseY, y)
        dx += sway * 0.008f * (a.hipY - y) * wb
        dy += sway * 0.008f * (x - a.centerX) * wb

        // 2. breathing
        dy -= breath * 3.5f * sm(a.hipY - 180f, a.headBaseY - 120f, y)
        dx += breath * 0.010f * (x - a.centerX) * bell(y, a.chestY, 170f)

        // 3. talking head nod
        dy += talk * 5f * sm(a.headBaseY, a.eyeL.y - 50f, y)

        // 4. hair swing (masked by hairW so clothes & body never wobble with hair)
        val hw = sm(hz.topY, hz.midY, y) * (1f - 0.5f * sm(hz.fadeStartY, hz.fadeEndY, y)) * hairW
        val left = sm(hz.leftInnerX, hz.leftOuterX, x)
        val right = sm(hz.rightInnerX, hz.rightOuterX, x) * (1f - sm(hz.rightCutoffStartX, hz.rightCutoffEndX, x))
        val ph = if (x < a.centerX) 0f else 1.7f
        val swing = sin(t * TAU / 3.1f - y * 0.0045f + ph) + 0.35f * sin(t * TAU / 1.7f - y * 0.006f + ph * 2f)
        dx += swing * 5.5f * hw * (left + right)
        dy += swing * 1.2f * hw * (left + right)

        // 5. optional animal ears (e.g. Yasmin)
        spec.earL?.let { e ->
            rot(x, y, e.pivotX, e.pivotY, earL * e.amp, ell(x, y, e.cx, e.cy, e.rx, e.ry))
        }
        spec.earR?.let { e ->
            rot(x, y, e.pivotX, e.pivotY, earR * e.amp, ell(x, y, e.cx, e.cy, e.rx, e.ry))
        }

        // 6. optional ahoge
        spec.ahoge?.let { ah ->
            rot(x, y, ah.pivotX, ah.pivotY, ahoge * ah.amp, ell(x, y, ah.cx, ah.cy, ah.rx, ah.ry))
        }

        // Keep frame borders pinned
        val edge = sm(0f, 40f, x) * sm(IMG_W, IMG_W - 40f, x) * sm(0f, 40f, y) * sm(IMG_H, IMG_H - 40f, y)
        dx *= edge
        dy *= edge
    }
}

private fun blink(t: Float): Float {
    fun one(p: Float): Float = if (p < 0.17f) sin(p / 0.17f * PI_F) else 0f
    return max(one(t % 3.7f), one((t + 1.3f) % 5.9f))
}

private val LID_SCALES = floatArrayOf(1.28f, 0.18f, 1.14f, 0.42f, 1.0f, 1.0f)
private val MOUTH_PATCH = floatArrayOf(1.3f, 0.30f, 1.15f, 0.65f, 1.0f, 1.0f)

private class RenderScratch {
    val path = Path()
    val oval = Path()
    val mouth = Path()
    val heart = Path()
    val sweat = Path()
}

private fun DrawScope.drawEyelid(
    sx: Float,
    sy: Float,
    w: Float,
    h: Float,
    tilt: Float,
    c: Float,
    skinTop: Color,
    skinBot: Color,
    lashCol: Color,
    scratch: RenderScratch
) {
    if (c <= 0.03f) return
    withTransform({ translate(sx, sy); rotate(tilt, Offset.Zero) }) {
        var li = 0
        while (li < LID_SCALES.size) {
            val sc = LID_SCALES[li]
            val al = LID_SCALES[li + 1]
            li += 2
            val ow = w * sc
            val oh = h * sc
            scratch.oval.reset()
            scratch.oval.addOval(Rect(-ow, -oh, ow, oh))
            val top = -oh - 1f
            val bottom = top + (2f * oh + 2f) * c
            clipPath(scratch.oval) {
                drawRect(
                    Brush.verticalGradient(listOf(skinTop.copy(alpha = al), skinBot.copy(alpha = al)), startY = -oh, endY = oh),
                    topLeft = Offset(-ow - 2f, top),
                    size = Size(2f * ow + 4f, bottom - top)
                )
            }
        }
        val bottom = -h - 1f + (2f * h + 2f) * c
        val a = sm(0.25f, 0.6f, c)
        if (a > 0f) {
            val ly = if (bottom < h * 0.35f) bottom else h * 0.35f
            scratch.path.reset()
            scratch.path.moveTo(-w * 0.90f, ly - h * 0.20f)
            scratch.path.quadraticBezierTo(0f, ly + h * 0.50f, w * 0.90f, ly - h * 0.20f)
            drawPath(scratch.path, lashCol.copy(alpha = a), style = Stroke(width = max(2.4f, h * 0.17f), cap = StrokeCap.Round))
        }
    }
}

private val MOUTH_IN = Color(0xFF7B2B38)
private val MOUTH_EDGE = Color(0xFF4E1D26)
private val TONGUE = Color(0xFFEE8C98)

private fun DrawScope.drawMouth(
    sx: Float,
    sy: Float,
    s: Float,
    tiltDeg: Float,
    open: Float,
    t: Float,
    f: FloatArray,
    skinMouth: Color,
    mouthLineCol: Color,
    scratch: RenderScratch
) {
    val eo = max(open, f[F_OPEN])
    val shape = f[F_SHAPE]
    val fadeAll = max(sm(0.03f, 0.14f, eo), shape)
    if (fadeAll <= 0.03f) return
    withTransform({ translate(sx, sy); rotate(tiltDeg, Offset.Zero); scale(s, s, Offset.Zero) }) {
        var i = 0
        while (i < MOUTH_PATCH.size) {
            val sc = MOUTH_PATCH[i]
            val al = MOUTH_PATCH[i + 1] * fadeAll
            i += 2
            drawOval(skinMouth.copy(alpha = al), Offset(-17f * sc, -6.5f * sc), Size(34f * sc, 13f * sc))
        }

        val closed = (1f - sm(0.03f, 0.2f, eo)) * shape
        if (closed > 0.03f) {
            val w = f[F_MW]
            val c = f[F_CURVE]
            val wavy = f[F_WAVY]
            val cat = f[F_CAT]
            val teeth = f[F_TEETH]
            val stroke = Stroke(width = 1.7f, cap = StrokeCap.Round)
            if (teeth > 0.05f) {
                val a = teeth * closed
                drawRoundRect(Color.White.copy(alpha = 0.95f * a), Offset(-w * 0.8f, -2.4f), Size(w * 1.6f, 4.8f), CornerRadius(1.6f, 1.6f))
                drawRoundRect(MOUTH_EDGE.copy(alpha = a), Offset(-w * 0.8f, -2.4f), Size(w * 1.6f, 4.8f), CornerRadius(1.6f, 1.6f), style = Stroke(width = 1.2f))
                drawLine(MOUTH_EDGE.copy(alpha = 0.8f * a), Offset(-w * 0.8f, 0f), Offset(w * 0.8f, 0f), strokeWidth = 0.9f)
                var tx = -w * 0.4f
                while (tx < w * 0.8f) {
                    drawLine(MOUTH_EDGE.copy(alpha = 0.7f * a), Offset(tx, -2.4f), Offset(tx, 2.4f), strokeWidth = 0.8f)
                    tx += w * 0.4f
                }
            } else {
                scratch.path.reset()
                scratch.path.moveTo(-w, 0f)
                scratch.path.cubicTo(-w * 0.5f, c * 2f - 3f * wavy, w * 0.5f, c * 2f + 3f * wavy, w, 0f)
                drawPath(scratch.path, mouthLineCol.copy(alpha = closed * (1f - cat)), style = stroke)
            }
            if (cat > 0.05f) {
                scratch.path.reset()
                scratch.path.moveTo(-w * 0.9f, 0f)
                scratch.path.quadraticBezierTo(-w * 0.45f, c * 1.6f, 0f, 0f)
                scratch.path.quadraticBezierTo(w * 0.45f, c * 1.6f, w * 0.9f, 0f)
                drawPath(scratch.path, mouthLineCol.copy(alpha = closed * cat), style = stroke)
            }
        }

        val fadeIn = sm(0.03f, 0.14f, eo)
        if (fadeIn <= 0.02f) return@withTransform
        val wobAmt = if (open >= f[F_OPEN]) 0.25f else 0.04f
        val wob = 1f + wobAmt * (0.6f * sin(t * 9.3f) + 0.4f * sin(t * 14.7f + 1f))
        val w = (4.5f + 5.5f * eo) * wob
        val hh = 0.9f + 6.5f * eo
        scratch.mouth.reset()
        scratch.mouth.moveTo(-w, 0f)
        scratch.mouth.quadraticBezierTo(0f, -hh * 0.9f, w, 0f)
        scratch.mouth.quadraticBezierTo(0f, hh * 2.1f, -w, 0f)
        scratch.mouth.close()
        drawPath(scratch.mouth, MOUTH_IN.copy(alpha = fadeIn))
        clipPath(scratch.mouth) {
            if (hh > 2.2f) drawOval(TONGUE.copy(alpha = fadeIn), Offset(-w * 0.55f, hh * 0.30f), Size(w * 1.1f, hh * 1.3f))
            if (hh > 3.0f) drawRect(Color.White.copy(alpha = 0.92f * fadeIn), Offset(-w * 0.7f, -hh * 0.42f), Size(w * 1.4f, 1.5f))
        }
        drawPath(scratch.mouth, MOUTH_EDGE.copy(alpha = fadeIn), style = Stroke(width = 1.1f, cap = StrokeCap.Round))
    }
}

private const val F_BROW = 0
private const val F_BTILT = 1
private const val F_BY = 2
private const val F_LID = 3
private const val F_LTILT = 4
private const val F_CURVE = 5
private const val F_MW = 6
private const val F_OPEN = 7
private const val F_TEETH = 8
private const val F_WAVY = 9
private const val F_CAT = 10
private const val F_BLUSH = 11
private const val F_HEART = 12
private const val F_SPARK = 13
private const val F_TEAR = 14
private const val F_ANGER = 15
private const val F_SWEAT = 16
private const val F_WINK = 17
private const val F_GLOOM = 18
private const val F_SHAPE = 19
private const val F_N = 20

private fun fillFaceTarget(emotion: String, f: FloatArray) {
    for (i in 0 until F_N) f[i] = 0f
    when (emotion.trim().lowercase()) {
        "happy" -> { f[F_LID] = 0.12f; f[F_CURVE] = 6f; f[F_MW] = 11f; f[F_BLUSH] = 0.45f; f[F_SPARK] = 0.5f; f[F_SHAPE] = 1f }
        "excited" -> {
            f[F_BROW] = 0.5f; f[F_BY] = -6f; f[F_BTILT] = 2f; f[F_LID] = 0.04f; f[F_CURVE] = 7f; f[F_MW] = 12f
            f[F_OPEN] = 0.45f; f[F_BLUSH] = 0.55f; f[F_SPARK] = 1f; f[F_SHAPE] = 1f
        }
        "sad" -> {
            f[F_BROW] = 0.85f; f[F_BTILT] = 7f; f[F_LID] = 0.30f; f[F_LTILT] = -10f; f[F_CURVE] = -5f; f[F_MW] = 7f
            f[F_TEAR] = 1f; f[F_SHAPE] = 1f
        }
        "love" -> { f[F_LID] = 0.08f; f[F_CURVE] = 6f; f[F_MW] = 9f; f[F_BLUSH] = 1f; f[F_HEART] = 1f; f[F_SHAPE] = 1f }
        "surprised" -> { f[F_BROW] = 0.8f; f[F_BY] = -9f; f[F_BTILT] = 1f; f[F_OPEN] = 0.55f; f[F_SPARK] = 0.3f; f[F_SHAPE] = 1f }
        "angry" -> {
            f[F_BROW] = 1f; f[F_BTILT] = -7f; f[F_LID] = 0.32f; f[F_LTILT] = 13f; f[F_CURVE] = -1.5f; f[F_MW] = 9f
            f[F_TEETH] = 1f; f[F_ANGER] = 1f; f[F_BLUSH] = 0.15f; f[F_SHAPE] = 1f
        }
        "jealous" -> {
            f[F_BROW] = 0.8f; f[F_BTILT] = -3f; f[F_LID] = 0.30f; f[F_LTILT] = 4f; f[F_CURVE] = -2f; f[F_MW] = 7f
            f[F_WAVY] = 0.3f; f[F_BLUSH] = 0.45f; f[F_GLOOM] = 1f; f[F_SHAPE] = 1f
        }
        "sulky" -> {
            f[F_BROW] = 0.7f; f[F_BTILT] = -2f; f[F_LID] = 0.20f; f[F_LTILT] = 5f; f[F_CURVE] = -3f; f[F_MW] = 5f
            f[F_BLUSH] = 0.8f; f[F_SHAPE] = 1f
        }
        "playful" -> { f[F_CURVE] = 3f; f[F_MW] = 9f; f[F_CAT] = 1f; f[F_WINK] = 1f; f[F_BLUSH] = 0.4f; f[F_SPARK] = 0.4f; f[F_SHAPE] = 1f }
        "worried" -> {
            f[F_BROW] = 0.9f; f[F_BTILT] = 8f; f[F_LID] = 0.10f; f[F_CURVE] = -2f; f[F_MW] = 7f; f[F_WAVY] = 1f
            f[F_SWEAT] = 1f; f[F_SHAPE] = 1f
        }
        else -> Unit
    }
}

private fun DrawScope.drawBrow(sx: Float, sy: Float, s: Float, left: Boolean, tilt: Float, alpha: Float, browCol: Color, scratch: RenderScratch) {
    if (alpha <= 0.03f) return
    val dir = if (left) 1f else -1f
    val innerX = sx + dir * 13f * s
    val innerY = sy - tilt * s
    val outerX = sx - dir * 13f * s
    val outerY = sy + tilt * 0.45f * s
    scratch.path.reset()
    scratch.path.moveTo(outerX, outerY)
    scratch.path.quadraticBezierTo(sx, sy - tilt * 0.2f * s - 2.2f * s, innerX, innerY)
    drawPath(scratch.path, browCol.copy(alpha = alpha * 0.7f), style = Stroke(width = 2.8f * s, cap = StrokeCap.Round))
}

private fun DrawScope.drawBlush(cx: Float, cy: Float, s: Float, a: Float, col: Color) {
    if (a <= 0.03f) return
    val rx = 23f * s
    withTransform({ scale(1f, 0.55f, Offset(cx, cy)) }) {
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a * 0.55f), Color.Transparent), center = Offset(cx, cy), radius = rx), rx, Offset(cx, cy))
    }
}

private fun DrawScope.drawHeartEye(cx: Float, cy: Float, s: Float, a: Float, t: Float, scratch: RenderScratch) {
    if (a <= 0.03f) return
    val pulse = 1f + 0.10f * sin(t * 6f)
    val r = 7.5f * s * pulse
    drawCircle(Color(0xFFFFD9E6).copy(alpha = 0.80f * a), 12.5f * s, Offset(cx, cy))
    scratch.heart.reset()
    scratch.heart.moveTo(cx, cy + r * 0.9f)
    scratch.heart.cubicTo(cx - r * 1.6f, cy - r * 0.2f, cx - r * 0.7f, cy - r * 1.4f, cx, cy - r * 0.45f)
    scratch.heart.cubicTo(cx + r * 0.7f, cy - r * 1.4f, cx + r * 1.6f, cy - r * 0.2f, cx, cy + r * 0.9f)
    scratch.heart.close()
    drawPath(scratch.heart, Color(0xFFFF3D87).copy(alpha = a))
    drawCircle(Color.White.copy(alpha = 0.9f * a), 1.6f * s, Offset(cx - 3f * s, cy - 3f * s))
}

private fun DrawScope.drawStar(cx: Float, cy: Float, r: Float, a: Float) {
    if (a <= 0.03f) return
    val col = Color.White.copy(alpha = a)
    drawLine(col, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = r * 0.45f, cap = StrokeCap.Round)
    drawLine(col, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = r * 0.45f, cap = StrokeCap.Round)
    drawCircle(col, r * 0.42f, Offset(cx, cy))
}

private fun DrawScope.drawTear(sx: Float, sy: Float, s: Float, t: Float, a: Float, phase: Float) {
    if (a <= 0.03f) return
    val prog = ((t + phase) % 2.4f) / 2.4f
    val y = sy + prog * 46f * s
    val al = a * sm(0f, 0.1f, prog) * (1f - sm(0.7f, 1f, prog))
    if (al <= 0.02f) return
    drawLine(Color(0xFFBFE3FF).copy(alpha = 0.35f * al), Offset(sx, sy), Offset(sx, y), strokeWidth = 1.6f * s, cap = StrokeCap.Round)
    drawCircle(Color(0xFFBFE3FF).copy(alpha = 0.85f * al), 2.8f * s, Offset(sx, y))
    drawCircle(Color.White.copy(alpha = 0.9f * al), 1.0f * s, Offset(sx - 0.8f * s, y - 0.8f * s))
}

private fun DrawScope.drawSweat(cx: Float, cy0: Float, s: Float, t: Float, a: Float, scratch: RenderScratch) {
    if (a <= 0.03f) return
    val cy = cy0 + sin(t * 3f) * 2f * s
    scratch.sweat.reset()
    scratch.sweat.moveTo(cx, cy - 9f * s)
    scratch.sweat.cubicTo(cx + 7f * s, cy - 1f * s, cx + 6f * s, cy + 6f * s, cx, cy + 6f * s)
    scratch.sweat.cubicTo(cx - 6f * s, cy + 6f * s, cx - 7f * s, cy - 1f * s, cx, cy - 9f * s)
    scratch.sweat.close()
    drawPath(scratch.sweat, Color(0xFFB7E4FF).copy(alpha = 0.88f * a))
    drawPath(scratch.sweat, Color(0xFF6AAAD8).copy(alpha = a), style = Stroke(width = 1.1f * s))
    drawCircle(Color.White.copy(alpha = 0.9f * a), 1.4f * s, Offset(cx - 2f * s, cy + 1f * s))
}

private val ANGER_SIGNS = floatArrayOf(-1f, 1f)

private fun DrawScope.drawAnger(cx: Float, cy: Float, s: Float, t: Float, a: Float, scratch: RenderScratch) {
    if (a <= 0.03f) return
    val r = 7f * s * (1f + 0.12f * sin(t * 9f))
    val col = Color(0xFFE53935).copy(alpha = a)
    for (qx in ANGER_SIGNS) {
        for (qy in ANGER_SIGNS) {
            scratch.path.reset()
            scratch.path.moveTo(cx + qx * r, cy + qy * r * 0.2f)
            scratch.path.quadraticBezierTo(cx + qx * r * 0.25f, cy + qy * r * 0.25f, cx + qx * r * 0.2f, cy + qy * r)
            drawPath(scratch.path, col, style = Stroke(width = 2.3f * s, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.drawGloom(sx: Float, sy: Float, s: Float, a: Float) {
    if (a <= 0.03f) return
    val col = Color(0xFF5B3A8C).copy(alpha = 0.38f * a)
    for (i in 0 until 7) {
        val x = sx + i * 15f * s
        val len = (14f + (i % 3) * 7f) * s
        drawLine(col, Offset(x, sy), Offset(x + 1.5f * s, sy + len), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
    }
}

private val SPARKS = arrayOf(
    floatArrayOf(0.12f, 0.30f), floatArrayOf(0.22f, 0.17f), floatArrayOf(0.86f, 0.44f), floatArrayOf(0.78f, 0.28f),
    floatArrayOf(0.09f, 0.60f), floatArrayOf(0.91f, 0.68f), floatArrayOf(0.34f, 0.09f), floatArrayOf(0.66f, 0.10f)
)

/** Legacy single-resource overload preserved for backwards compatibility. */
@Composable
fun AnimeCharacter(
    resId: Int,
    modifier: Modifier = Modifier,
    speech: () -> Float = { 0f },
    emotion: String = "calm",
    fade: Color = YasminUi.Bg
) {
    val fallback = remember(resId) {
        CharacterCatalog.ALL.firstOrNull { it.bodyArtRes == resId } ?: CharacterCatalog.ALL.first()
    }
    AnimeCharacter(
        character = fallback,
        outfit = fallback.outfits.first(),
        modifier = modifier,
        speech = speech,
        emotion = emotion,
        fade = fade
    )
}

/**
 * Multi-character + outfit Live2D-style composable.
 * Draws the character body mesh, optional outfit overlay on the same mesh, background color drift mask,
 * animated face (blink, lip-sync, 11 emotions), falling petals, and sparkles.
 */
@Composable
fun AnimeCharacter(
    character: CharacterSpec,
    outfit: OutfitSpec,
    modifier: Modifier = Modifier,
    speech: () -> Float = { 0f },
    emotion: String = character.defaultMood,
    fade: Color = YasminUi.Bg,
    sampleSize: Int = 1,
    showParticles: Boolean = true
) {
    val ctx = LocalContext.current
    val bodyRes = if (outfit.fullArtRes != 0) outfit.fullArtRes else character.bodyArtRes
    val overlayRes = if (outfit.fullArtRes == 0) outfit.overlayRes else 0
    val maskRes = character.bgMaskRes

    val bmp = remember(bodyRes, sampleSize) { CharacterBitmapCache.acquire(ctx, bodyRes, sampleSize) }
    val overlayBmp = remember(overlayRes, sampleSize) {
        if (overlayRes != 0) CharacterBitmapCache.acquire(ctx, overlayRes, sampleSize) else null
    }
    val maskBmp = remember(maskRes, sampleSize) {
        if (maskRes != 0) CharacterBitmapCache.acquire(ctx, maskRes, sampleSize) else null
    }

    DisposableEffect(bodyRes, overlayRes, maskRes, sampleSize) {
        onDispose {
            CharacterBitmapCache.release(bodyRes, sampleSize)
            if (overlayRes != 0) CharacterBitmapCache.release(overlayRes, sampleSize)
            if (maskRes != 0) CharacterBitmapCache.release(maskRes, sampleSize)
        }
    }

    if (bmp == null) return

    val hairW = remember(character.id, bmp) { CharacterBitmapCache.hairWeights(ctx, character, bmp) }
    val pose = remember { Pose() }
    val face = remember { FloatArray(F_N) }
    val goal = remember { FloatArray(F_N) }
    val verts = remember { FloatArray((COLS + 1) * (ROWS + 1) * 2) }
    val paint = remember { Paint().apply { isFilterBitmap = true; isAntiAlias = true } }
    val tintPaint = remember { Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) } }
    val scratch = remember { RenderScratch() }

    val skinTop = remember(character.id) { Color(character.anchors.skinTopArgb) }
    val skinBot = remember(character.id) { Color(character.anchors.skinBotArgb) }
    val skinMouth = remember(character.id) { Color(character.anchors.skinMouthArgb) }
    val lashCol = remember(character.id) { Color(character.anchors.lashArgb) }
    val browCol = remember(character.id) { Color(character.anchors.browArgb) }
    val mouthLineCol = remember(character.id) { Color(character.anchors.mouthLineArgb) }
    val petalCol = remember(character.id) { Color(character.petalColor) }

    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withFrameNanos { n ->
                if (start < 0L) start = n
                tick = n - start
            }
        }
    }

    Canvas(modifier.clipToBounds()) {
        if (bmp.isRecycled) return@Canvas
        val time = tick / 1_000_000_000f
        pose.update(time, speech())
        fillFaceTarget(emotion, goal)
        for (q in 0 until F_N) face[q] += (goal[q] - face[q]) * 0.10f

        val s = minOf(size.width / IMG_W, size.height / IMG_H)
        val offX = (size.width - IMG_W * s) / 2f
        val offY = (size.height - IMG_H * s) / 2f

        var k = 0
        for (r in 0..ROWS) {
            val y = IMG_H * r / ROWS
            for (c in 0..COLS) {
                val x = IMG_W * c / COLS
                pose.warp(x, y, character, hairW[r * (COLS + 1) + c])
                verts[k++] = offX + (x + pose.dx) * s
                verts[k++] = offY + (y + pose.dy) * s
            }
        }

        val nc = drawContext.canvas.nativeCanvas
        nc.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, paint)

        if (overlayBmp != null && !overlayBmp.isRecycled) {
            nc.drawBitmapMesh(overlayBmp, COLS, ROWS, verts, 0, null, 0, paint)
        }

        if (maskBmp != null && !maskBmp.isRecycled) {
            val layer = nc.saveLayerAlpha(null, 84)
            nc.drawBitmapMesh(maskBmp, COLS, ROWS, verts, 0, null, 0, paint)
            tintPaint.color = tintAt(time).toArgb()
            nc.drawRect(0f, 0f, size.width, size.height, tintPaint)
            nc.restoreToCount(layer)
        }

        fun scrX(px: Float, py: Float): Float {
            pose.warp(px, py, character)
            return offX + (px + pose.dx) * s
        }

        fun scrY(px: Float, py: Float): Float {
            return offY + (py + pose.dy) * s
        }

        val a = character.anchors
        val bl = blink(time)
        val lidBase = face[F_LID] + (1f - face[F_LID]) * bl
        val lidL = lidBase
        val wp = time % 3.4f
        val lidR = max(lidBase, face[F_WINK] * sm(0f, 0.15f, wp) * (1f - sm(0.9f, 1.1f, wp)))

        val eyeLX = scrX(a.eyeL.x, a.eyeL.y)
        val eyeLY = scrY(a.eyeL.x, a.eyeL.y)
        val eyeRX = scrX(a.eyeR.x, a.eyeR.y)
        val eyeRY = scrY(a.eyeR.x, a.eyeR.y)

        val glX = scrX(a.gloom.x, a.gloom.y)
        val glY = scrY(a.gloom.x, a.gloom.y)
        drawGloom(glX, glY, s, face[F_GLOOM])

        val warm = Color(0xFFFF6F8F)
        val angerBlush = Color(0xFFFF5470)
        val blushCol = if (face[F_ANGER] > 0.5f) angerBlush else warm
        val chLX = scrX(a.cheekL.x, a.cheekL.y)
        val chLY = scrY(a.cheekL.x, a.cheekL.y)
        val chRX = scrX(a.cheekR.x, a.cheekR.y)
        val chRY = scrY(a.cheekR.x, a.cheekR.y)
        drawBlush(chLX, chLY, s, face[F_BLUSH], blushCol)
        drawBlush(chRX, chRY, s, face[F_BLUSH], blushCol)

        val eyeOpen = (1f - lidBase * 1.2f).coerceIn(0f, 1f)
        if (face[F_HEART] > 0.03f) {
            drawHeartEye(eyeLX, eyeLY, s, face[F_HEART] * eyeOpen, time, scratch)
            drawHeartEye(eyeRX, eyeRY, s, face[F_HEART] * eyeOpen, time, scratch)
        }
        if (face[F_SPARK] > 0.03f) {
            val pk = face[F_SPARK] * eyeOpen
            val r1 = (3.6f + 1.6f * sin(time * 5f)) * s
            val r2 = (3.6f + 1.6f * sin(time * 5f + 1.7f)) * s
            drawStar(eyeLX + 5f * s, eyeLY - 5f * s, r1, pk)
            drawStar(eyeRX + 5f * s, eyeRY - 5f * s, r2, pk)
        }

        drawEyelid(eyeLX, eyeLY, a.eyeLW * s, a.eyeLH * s, a.eyeLTilt + face[F_LTILT], lidL, skinTop, skinBot, lashCol, scratch)
        drawEyelid(eyeRX, eyeRY, a.eyeRW * s, a.eyeRH * s, a.eyeRTilt - face[F_LTILT], lidR, skinTop, skinBot, lashCol, scratch)

        val bLX = scrX(a.browL.x, a.browL.y + face[F_BY])
        val bLY = scrY(a.browL.x, a.browL.y + face[F_BY])
        val bRX = scrX(a.browR.x, a.browR.y + face[F_BY])
        val bRY = scrY(a.browR.x, a.browR.y + face[F_BY])
        drawBrow(bLX, bLY, s, true, face[F_BTILT], face[F_BROW], browCol, scratch)
        drawBrow(bRX, bRY, s, false, face[F_BTILT], face[F_BROW], browCol, scratch)

        val mX = scrX(a.mouth.x, a.mouth.y)
        val mY = scrY(a.mouth.x, a.mouth.y)
        drawMouth(mX, mY, s, a.mouthTiltDeg, pose.mouth, time, face, skinMouth, mouthLineCol, scratch)

        val tLX = scrX(a.tearL.x, a.tearL.y)
        val tLY = scrY(a.tearL.x, a.tearL.y)
        val tRX = scrX(a.tearR.x, a.tearR.y)
        val tRY = scrY(a.tearR.x, a.tearR.y)
        drawTear(tLX, tLY, s, time, face[F_TEAR], 0f)
        drawTear(tRX, tRY, s, time, face[F_TEAR], 1.1f)

        val swX = scrX(a.sweat.x, a.sweat.y)
        val swY = scrY(a.sweat.x, a.sweat.y)
        drawSweat(swX, swY, s, time, face[F_SWEAT], scratch)

        val anX = scrX(a.anger.x, a.anger.y)
        val anY = scrY(a.anger.x, a.anger.y)
        drawAnger(anX, anY, s, time, face[F_ANGER], scratch)

        val imgW = IMG_W * s
        val imgH = IMG_H * s
        val fv = imgH * 0.10f
        drawRect(Brush.verticalGradient(listOf(fade, Color.Transparent), startY = offY, endY = offY + fv), Offset(0f, offY), Size(size.width, fv))
        val fb = imgH * 0.10f
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, fade), startY = offY + imgH - fb, endY = offY + imgH), Offset(0f, offY + imgH - fb), Size(size.width, fb))
        if (offX > 1f) {
            val fh = imgW * 0.06f
            drawRect(Brush.horizontalGradient(listOf(fade, Color.Transparent), startX = offX, endX = offX + fh), Offset(offX, offY), Size(fh, imgH))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, fade), startX = offX + imgW - fh, endX = offX + imgW), Offset(offX + imgW - fh, offY), Size(fh, imgH))
            drawRect(fade, Offset(0f, 0f), Size(offX, size.height))
            drawRect(fade, Offset(offX + imgW, 0f), Size(size.width - offX - imgW, size.height))
        }

        if (showParticles) {
            for (i in 0 until 16) {
                val fx = (i * 0.6180339f) % 1f
                val speed = 0.030f + 0.028f * ((i * 7) % 5) / 5f
                val prog = (time * speed + fx * 1.7f) % 1.15f
                val py = (prog - 0.08f) * size.height * 0.9f
                val px = size.width * (0.42f + 0.62f * fx) - prog * size.width * 0.24f + sin(time * 0.9f + i) * 14.dp.toPx()
                val al = sm(0f, 0.08f, prog) * sm(1.15f, 1.0f, prog)
                val rr = (3.4f + (i % 3) * 1.4f).dp.toPx()
                withTransform({ translate(px, py); rotate(time * 38f * (1 + i % 3) + i * 40f, Offset.Zero) }) {
                    drawOval(petalCol.copy(alpha = al * 0.85f), Offset(-rr, -rr * 0.55f), Size(2f * rr, 1.1f * rr))
                    drawOval(Color.White.copy(alpha = al * 0.45f), Offset(-rr * 0.5f, -rr * 0.3f), Size(rr, rr * 0.45f))
                }
            }

            for (i in SPARKS.indices) {
                val v = sin(time * 1.6f + i * 1.3f)
                val al = if (v > 0f) v * v else 0f
                if (al < 0.02f) continue
                val cx = size.width * SPARKS[i][0]
                val cy = size.height * SPARKS[i][1]
                val rr = (3f + 5f * al).dp.toPx()
                val col = Color.White.copy(alpha = al * 0.9f)
                drawLine(col, Offset(cx - rr, cy), Offset(cx + rr, cy), strokeWidth = 1.4f.dp.toPx(), cap = StrokeCap.Round)
                drawLine(col, Offset(cx, cy - rr), Offset(cx, cy + rr), strokeWidth = 1.4f.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Color(0xFFBFE6FF).copy(alpha = al * 0.5f), rr * 0.45f, Offset(cx, cy))
            }
        }
    }
}
