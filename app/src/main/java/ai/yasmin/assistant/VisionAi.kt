package ai.yasmin.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * v2.41: ONE place for the text/vision model names (used by Camera Vision pictures from the gallery, Chat and the one-shot button).
 * Checked 20 Oct 2026 against the Gemini API docs:
 *  - gemini-3.8-flash is a current model on the Gemini API generateContent endpoint (ai.google.dev "What's new in Gemini 3.8 Flash").
 *  - Its thinking control is `thinkingLevel` (LOW / MEDIUM / HIGH, default MEDIUM; "minimal" is NOT supported). The old integer `thinkingBudget`
 *    is deprecated for it, and `temperature` is listed as deprecated by the Vertex guide, so neither is sent for 3.x models.
 *  - gemini-2.0-flash was shut down on 1 Jun 2026 and gemini-2.5-flash was scheduled for 16 Oct 2026, so both were REMOVED from the fallback list.
 *  - gemini-3.5-flash is the fallback (released 19 May 2026, no shutdown date announced on the deprecations page).
 * LIVE voice + video never goes through this object: it uses the Live model chosen in the selector (see LiveModels / GeminiLive).
 */
object VisionModels {
    val PRIMARY: String get() = Prefs.DEFAULT_CODE_MODEL
    const val FALLBACK = "gemini-3.5-flash"

    /** At most two models are ever tried: the chosen one, then the fallback (only on "model not found"). */
    fun order(chosen: String): List<String> = listOf(chosen, FALLBACK).distinct()

    /** Only models that are known to accept the parameter get it. */
    fun temperatureFor(model: String): Double? = if (model.startsWith("gemini-2.")) 0.5 else null

    fun thinkingFor(model: String, fast: Boolean): JSONObject? = when {
        model.startsWith("gemini-3.8-flash") -> if (fast) JSONObject().put("thinkingLevel", "low") else null
        model.startsWith("gemini-2.5-flash") -> if (fast) JSONObject().put("thinkingBudget", 0) else null   // only for a custom 2.5 name typed in Settings
        else -> null
    }
}

/**
 * v2.36: Camera Vision + text Chat backend. One plain Gemini request (same key and HTTP client as the rest of the app) that can carry a
 * photo. Nothing here touches the voice assistant, phone control or automation. Blocking: call it from a background thread.
 */
object VisionAi {
    /** One message of a conversation. [jpeg] is an optional photo that belongs to this message. */
    class Turn(val fromUser: Boolean, val text: String, val jpeg: ByteArray? = null)

    private const val MAX_SIDE = 1280

    /** What the live camera answers: short, because it is spoken. */
    const val PROMPT_LIVE = "This is a frame from my phone camera. Tell me in 2 to 4 short sentences what is in front of the camera: the main objects, what they are, " +
        "their colours, any readable text, and what is happening. Be specific and honest. If something is unclear, say so. Do not try to name real people from their faces; describe them instead."

    /** What an image from the gallery gets: a full report. */
    const val PROMPT_REPORT = "Analyse this image completely and give me a clear report with these parts (skip a part that does not apply): " +
        "1) Overview: what the image shows in one or two sentences. 2) Objects: every object you can see, with colours and where they are. " +
        "3) People and animals: how many, what they look like, what they wear, what they do (never name a real person from the face). " +
        "4) Text: write down all readable text exactly (also logos, signs, labels). 5) Setting: place, light, time of day, weather. " +
        "6) Details: anything unusual, damage, brands, numbers, food, screens. 7) Quality: if the photo is blurry or dark, say what you could not see. Be accurate; never invent what is not visible."

    private fun system(ctx: Context): String {
        val owner = Prefs.get(ctx, Prefs.NAME, "").trim().ifBlank { "Mehdi" }
        return "You are Yasmin, the friendly AI assistant of $owner. Answer in the language the user writes in: English gets English, Hindi gets Hindi, " +
            "Bengali gets Bengali, Hinglish (Hindi in English letters) gets Hinglish. When there is no user text, or the language is unclear, answer in simple Hinglish. " +
            "When a photo is attached, look at it carefully and read every word of text in it; answer the question about it, or give a full report if there is no question. Be clear and natural, and keep it as short as the task allows. Never use markdown symbols like ** or #; write plain sentences and simple lists with a dash."
    }

    /** Sends the conversation and returns the model's answer. Throws RuntimeException with a short readable message on failure. */
    fun ask(ctx: Context, turns: List<Turn>, fast: Boolean = false): String {
        val key = Prefs.get(ctx, Prefs.KEY, "").trim()
        if (key.isBlank()) throw RuntimeException("Paste your Gemini API key first (menu > API Key).")
        val chosen = Prefs.get(ctx, Prefs.CODE_MODEL, VisionModels.PRIMARY).trim().ifBlank { VisionModels.PRIMARY }
        val models = VisionModels.order(chosen)
        // a one-shot picture request must never hang: the shared client waits up to 280 s, so this call gets its own limit
        val http = CodeGen.http.newBuilder().callTimeout(if (fast) 25L else 90L, TimeUnit.SECONDS).build()
        val contents = JSONArray()
        for (t in turns) {
            val parts = JSONArray()
            if (t.jpeg != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(t.jpeg, Base64.NO_WRAP))))
            parts.put(JSONObject().put("text", t.text.ifBlank { "." }))
            contents.put(JSONObject().put("role", if (t.fromUser) "user" else "model").put("parts", parts))
        }
        fun bodyFor(model: String): JSONObject {
            // thinking tokens count against maxOutputTokens, so the fast path gets room for them
            val gen = JSONObject().put("maxOutputTokens", if (fast) 1500 else 4000)
            VisionModels.temperatureFor(model)?.let { gen.put("temperature", it) }
            VisionModels.thinkingFor(model, fast)?.let { gen.put("thinkingConfig", it) }
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(ctx)))))
                .put("contents", contents)
                .put("generationConfig", gen)
        }
        var lastError = "AI error"
        for (model in models) {
            var attempt = 0
            while (true) {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key)      // the key travels only in this header and is never logged or put into an error text
                    .post(bodyFor(model).toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val reply: Pair<Int, String> = try {
                    http.newCall(req).execute().use { resp -> Pair(resp.code, resp.body?.string().orEmpty()) }
                } catch (e: IOException) {
                    throw RuntimeException("Network problem: " + (e.message ?: "no connection") + ". Check the internet and try again.")
                }
                val code = reply.first
                val txt = reply.second
                if (code in 200..299) return parseAnswer(txt)
                lastError = "AI error HTTP $code. " + (if (code == 400 || code == 403) "Check the API key (menu > API Key). " else "") + txt.take(160)
                if ((code == 429 || code >= 500) && attempt == 0) {      // rate limit / server busy: one polite retry on the SAME model
                    attempt++
                    try { Thread.sleep(1200) } catch (_: InterruptedException) { throw RuntimeException("Cancelled") }
                    continue
                }
                if (code == 404) break                                   // unknown or retired model name: try the fallback (max 2 models in total)
                throw RuntimeException(lastError)
            }
        }
        throw RuntimeException(lastError)
    }

    private fun parseAnswer(txt: String): String {
        val cand = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
            ?: throw RuntimeException("The AI returned nothing (it may have blocked the picture).")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw RuntimeException("The AI gave an empty answer. Try again.")
        return out
    }

    // ---------------------------------------------------------------- pictures

    /** Scales a bitmap down (longest side [maxSide]) and encodes it as JPEG. The source bitmap is left untouched (the caller owns it). */
    fun toJpeg(src: Bitmap, maxSide: Int = MAX_SIDE, quality: Int = 85): ByteArray {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (bmp !== src) bmp.recycle()
        return out.toByteArray()
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Reads a picture the user picked from the gallery (correctly rotated, already scaled). Null if it cannot be read. */
    fun loadUri(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > MAX_SIDE * 2) sample *= 2
            val real = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) } ?: return null
            val deg = try {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    when (androidx.exifinterface.media.ExifInterface(s).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (_: Throwable) { 0 }
            rotate(bmp, deg)
        } catch (_: Throwable) { null }
    }
}
