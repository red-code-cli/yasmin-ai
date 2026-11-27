package ai.yasmin.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Asks a strong text model (default gemini-3.8-flash) to write, change or repair the source files of an Android app. */
object CodeGen {
    class Result(val files: List<Pair<String, String>>, val notes: String, val refused: String?)

    /** Shared with the Main Brain's text AI (one connection pool, no second client configuration). */
    internal val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(280, TimeUnit.SECONDS)
        .build()

    private val RULES = """
You are a senior Android engineer. You write a COMPLETE, COMPILABLE small Android app that is built on a phone (Termux) with Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17, compileSdk 35, minSdk 26.

INPUT: a JSON object with app_name, package, template ("webview" or "native"), task (what the user wants, or what to change), current_files (path -> text, the project as it is now) and, when repairing, build_errors (compiler output).

OUTPUT: ONLY one JSON object, no markdown, no commentary: {"files":[{"path":"...","content":"..."}],"notes":"one short sentence"}
Return every file you create or change IN FULL (never partial files, never "..." or "rest unchanged"). Files you do not return stay as they are.

ALLOWED PATHS ONLY: app/src/main/java/<package as folders>/*.kt or *.java, app/src/main/res/** (xml only), app/src/main/assets/** (text only), app/src/main/AndroidManifest.xml, and app/build.gradle.kts (only to add dependencies). Never change namespace or applicationId, never touch other Gradle files, never add Gradle tasks or scripts.

RULES:
- MainActivity extends android.app.Activity (NOT AppCompatActivity). Use only Android framework APIs (android.*) and the Kotlin standard library. Do not use Compose, AppCompat, Material Components, ViewBinding or any library, unless you add its exact Maven coordinates to app/build.gradle.kts dependencies (prefer none).
- The theme @style/AppTheme and the drawable @drawable/ic_launcher already exist. Do not reference any resource you do not define yourself. Keep the launcher activity .MainActivity exported with the MAIN/LAUNCHER intent filter and declare every permission you use in the manifest.
- Text files only: no images or binary files. Draw graphics with vector drawables, shape XML, Canvas, CSS/SVG or emoji.
- template webview: write the whole app as HTML, CSS and JavaScript in app/src/main/assets/index.html (plus optional .css/.js files there). It must work fully offline: no CDN, no external fonts, no remote images. Save data with localStorage. Keep MainActivity.kt as it is unless you need a native feature.
- template native: build the UI in Kotlin code or XML layouts. Handle back navigation and rotation gracefully. Add padding for system bars (the screen is edge to edge on new Android versions).
- Make the app genuinely useful and good looking: readable text size, sensible spacing, a clear color scheme, and every button working. Use the language the user asked for in the interface.
- Double-check imports, types, nullability and that every referenced id, string and layout exists. A compile error makes the whole build fail.
- When build_errors are given, fix exactly those errors with the smallest changes and re-check the rest of the code for the same mistake.
- Keep the whole app under about 60 KB.
- REFUSE anything meant to harm or deceive people: malware, spyware or stalkerware, hidden tracking or recording, credential or data theft, phishing or fake login screens, ransomware, fraud, or apps that hide from the user. In that case return {"files":[],"refused":"short reason"}.
""".trimIndent()

    fun run(
        key: String, model: String, pkg: String, name: String, template: String,
        task: String, current: Map<String, String>, buildErrors: String?
    ): Result {
        val user = JSONObject().put("app_name", name).put("package", pkg).put("template", template).put("task", task)
        if (current.isNotEmpty()) {
            val o = JSONObject()
            current.forEach { (k, v) -> o.put(k, v) }
            user.put("current_files", o)
        }
        if (!buildErrors.isNullOrBlank()) user.put("build_errors", buildErrors.take(6000))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", RULES))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user.toString())))))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.4)
                .put("maxOutputTokens", 30000))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Code model error HTTP ${resp.code}: ${txt.take(300)}")
            val cand = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0)
                ?: throw RuntimeException("The code model returned nothing (it may have blocked the request).")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
            }
            var out = sb.toString().trim()
            if (out.startsWith("```")) out = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val o = try { JSONObject(out) } catch (e: Exception) {
                throw RuntimeException(
                    if (cand.optString("finishReason") == "MAX_TOKENS") "The app was too big for one answer. Ask for a simpler app."
                    else "The code model did not return valid JSON."
                )
            }
            val arr = o.optJSONArray("files") ?: JSONArray()
            val files = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                files.add(f.optString("path") to f.optString("content"))
            }
            Result(files, o.optString("notes", ""), o.optString("refused", "").ifBlank { null })
        }
    }
}
