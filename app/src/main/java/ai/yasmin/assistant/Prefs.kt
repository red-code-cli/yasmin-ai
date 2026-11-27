package ai.yasmin.assistant

import android.content.Context
import android.content.Intent

object Prefs {
    const val KEY = "api_key"
    const val MODEL = "model"
    const val CODE_MODEL = "code_model"
    const val VOICE = "voice"
    const val LANG = "lang"
    const val NAME = "name"
    const val WATCH = "watch"
    const val COUNTRY = "country_code"      // e.g. 880 or 91: used to turn local numbers into WhatsApp links
    const val ANNOUNCE = "announce_msgs"    // tell me out loud when a new WhatsApp/Messenger message arrives
    const val WA_DRAFTS = "wa_agent_drafts"  // WhatsApp Master Agent may send the current chat's recent text to Gemini to write a reply draft (default on)
    const val MSGR_DRAFTS = "msgr_agent_drafts"  // same switch for the Messenger Master Agent (own key, own default on)
    const val SEND_DELAY_MS = "send_delay_ms"    // pause after each CONFIRMED send, 3000..5000 ms (TaskLimits)
    const val AUTO_REPLY = "auto_reply_on"       // step 10: master switch for automatic replies. v2.0: default ON (a stored OFF from the Automation screen still wins)
    const val AUTO_REPLY_DEFAULT = true
    const val WA_AUTO = "wa_auto_reply"          // step 10: automatic replies on WhatsApp (default on, but only matters while AUTO_REPLY is on)
    const val MSGR_AUTO = "msgr_auto_reply"      // step 10: automatic replies on Messenger (same)
    const val WAKE_SLEEP = "wake_sleep_on"       // v2.21: strict wake word + sleep commands. v2.23: default ON (a stored choice from the Preferences switch still wins); off = Yasmin behaves like v2.20
    const val WAKE_SLEEP_DEFAULT = true
    const val ENGINE = "voice_engine"            // "gemini" or "eleven": which voice speaks
    const val ELEVEN_KEY = "eleven_api_key"
    const val ELEVEN_VOICE = "eleven_voice_id"
    const val ELEVEN_VOICE_NAME = "eleven_voice_name"
    const val ELEVEN_MODEL = "eleven_model"
    const val PERSONA = "persona_mode"          // v2.25: care (girlfriend) / boss (FRIDAY-style) / friend
    const val PITCH = "voice_pitch_pct"          // v2.17: voice pitch in percent, 70..130, 100 = normal (applies to Gemini and ElevenLabs voice)
    const val AUTO_PAUSED = "auto_reply_paused"  // step 13: owner pressed "Pause automation" (default off). While true nothing is scheduled or sent, whatever the switches say
    const val CHARACTER_ID = "character_id"      // v2.42: selected home character id (default "yasmin")
    const val CHARACTER_OUTFIT = "character_outfit"  // v2.42: selected outfit id for the active character (default "casual")

    /** v2.33: the default Live model is Gemini 3.1 Flash Live (the owner's choice). 3.8 Live and 3.8 Thinking can still be picked in the selector. */
    const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
    /** Text model that writes the source code of the apps Yasmin builds. */
    const val DEFAULT_CODE_MODEL = "gemini-3.8-flash"

    /** Older Live models that are shut down or being retired. They are replaced by DEFAULT_MODEL.
     *  (gemini-3.1-flash-live-preview is NOT here any more: it is a documented Live model and can be picked in the selector.) */
    private val LEGACY = setOf(
        "gemini-2.5-flash-native-audio-preview-12-2025",
        "gemini-2.5-flash-native-audio-preview-09-2025",
        "gemini-2.5-flash-native-audio-latest",
        "gemini-live-2.5-flash-preview",
        "gemini-2.0-flash-live-001"
    )

    private fun sp(c: Context) = c.getSharedPreferences("yasmin", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun getBool(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun getInt(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun putInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()

    /**
     * v2.33, runs once per install (Application.onCreate): makes Gemini 3.1 Flash Live + the Leda voice the active choice, also for people who
     * already had another model / voice saved. After that the selector and Settings work as before and the choice is never overwritten again.
     */
    fun migrateDefaults(c: Context) {
        if (getBool(c, "defaults_v233", false)) return
        put(c, MODEL, DEFAULT_MODEL)
        put(c, VOICE, Voice.DEFAULT)
        putBool(c, "defaults_v233", true)
    }

    /** The Live model to connect with: saved value, but blank or legacy names are upgraded to the current default. */
    fun liveModel(c: Context): String {
        val m = get(c, MODEL, DEFAULT_MODEL).trim().removePrefix("models/")
        return if (m.isBlank() || m in LEGACY) DEFAULT_MODEL else m
    }
}

/**
 * The ONE place that decides which voice Yasmin speaks with. Every Live model receives the same prebuilt voice name
 * (generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName); there is no per-model voice.
 * The service reads it once when a session starts and keeps it for every reconnect and model switch of that session.
 */
object Voice {
    const val DEFAULT = "Leda"
    /** Voices offered in Settings. */
    val ALL = listOf("Leda", "Aoede", "Kore", "Zephyr", "Despina", "Callirrhoe")
    /** The voice the user selected; blank falls back to DEFAULT (never to a random voice). */
    fun saved(c: Context): String = Prefs.get(c, Prefs.VOICE, DEFAULT).trim().ifBlank { DEFAULT }
}

/** Which voice speaks: Gemini's own voice, or ElevenLabs (Gemini still does the thinking, ElevenLabs only voices the text). */
object VoiceEngine {
    const val GEMINI = "gemini"
    const val ELEVEN = "eleven"
    /** Eleven v3 is the ElevenLabs model that speaks Bengali. Flash v2.5 is faster but has no Bengali. */
    const val DEFAULT_ELEVEN_MODEL = "eleven_v3"
    /** Used if the chosen model is refused by the account. */
    const val FALLBACK_ELEVEN_MODEL = "eleven_multilingual_v2"
    /** The engine the user chose (saved). */
    fun saved(c: Context): String = if (Prefs.get(c, Prefs.ENGINE, GEMINI) == ELEVEN) ELEVEN else GEMINI
    /** Built-in female ElevenLabs voice (Sarah) used when no voice was picked: a key alone is enough to start. */
    const val DEFAULT_ELEVEN_VOICE = "EXAVITQu4vr4xnSDxMaL"
    const val DEFAULT_ELEVEN_VOICE_NAME = "Sarah"
    /** The voice id that will be spoken: the picked/typed one, else the default female voice. */
    fun elevenVoice(c: Context): String = Prefs.get(c, Prefs.ELEVEN_VOICE, "").trim().ifBlank { DEFAULT_ELEVEN_VOICE }
    fun elevenVoiceName(c: Context): String {
        val id = Prefs.get(c, Prefs.ELEVEN_VOICE, "").trim()
        return if (id.isBlank()) DEFAULT_ELEVEN_VOICE_NAME else Prefs.get(c, Prefs.ELEVEN_VOICE_NAME, "").ifBlank { id }
    }
    /** ElevenLabs can be used as soon as a key is saved (the voice falls back to the default female voice). */
    fun elevenReady(c: Context): Boolean = Prefs.get(c, Prefs.ELEVEN_KEY, "").isNotBlank()
    /** The engine that will really be used: ElevenLabs falls back to Gemini when it is not set up. */
    fun effective(c: Context): String = if (saved(c) == ELEVEN && elevenReady(c)) ELEVEN else GEMINI
}

/**
 * Gemini models that officially support the Live API (voice in / voice out) on the Gemini Developer API.
 * Source: https://ai.google.dev/gemini-api/docs/models and .../live-api/capabilities (checked 1 Oct 2026).
 * Only these are offered in the selector; a custom name typed in Settings still works and uses the 3.8 Live rules.
 */
object LiveModels {
    /** How function declarations must be marked for this model. */
    enum class ToolMode(val wire: String?) {
        BLOCKING("BLOCKING"),          // 3.8 Live: supports BLOCKING and NON_BLOCKING; BLOCKING keeps phone control step-by-step
        NON_BLOCKING("NON_BLOCKING"),  // 3.8 Live Extended Thinking: async only, blocking returns a hard error
        SEQUENTIAL(null)               // 3.1 Flash Live: no async support; always sequential, so send no "behavior" field
    }

    class Model(
        val id: String,
        val label: String,
        val note: String,
        val tools: ToolMode,
        /** Sent as generationConfig.thinkingConfig.thinkingLevel. null = must be omitted (3.8 Live rejects it). */
        val thinkingLevel: String?,
        /** Raw-WebSocket API version used in the URL. */
        val apiVersion: String = "v1beta"
    )

    val ALL: List<Model> = listOf(
        // v2.33: 3.1 is the default. Its built-in thinking level is "minimal" (fastest, but it often misunderstands and answers a
        // different question); "LOW" gives it a little time to understand the sentence and costs only a short extra pause.
        Model("gemini-3.1-flash-live-preview", "3.1 Flash Live", "Default. Thinks a little before answering", ToolMode.SEQUENTIAL, "LOW"),
        Model("gemini-3.8-live", "3.8 Live", "Fast voice", ToolMode.BLOCKING, null),
        Model("gemini-3.8-live-extended-thinking", "3.8 Thinking", "Deeper reasoning, async tools", ToolMode.NON_BLOCKING, "LOW", "v1alpha")
    )

    fun find(id: String): Model? = ALL.firstOrNull { it.id == id.trim().removePrefix("models/") }

    /** Rules for any model name, including custom ones typed in Settings (treated like the default model). */
    fun rules(id: String): Model = find(id) ?: Model(id, id, "Custom", ToolMode.BLOCKING, null)
}

/** Voice pitch slider (v2.17). Percent: 100 = normal, lower = deeper voice, higher = thinner voice. Applied on the speaker, so it works for every engine. */
object VoicePitch {
    const val MIN = 70
    const val MAX = 130
    const val DEFAULT = 100
    fun saved(c: Context): Int = Prefs.getInt(c, Prefs.PITCH, DEFAULT).coerceIn(MIN, MAX)
    fun save(c: Context, pct: Int) {
        Prefs.putInt(c, Prefs.PITCH, pct.coerceIn(MIN, MAX))
        AudioIO.pitchChanged()   // a running conversation changes at once
    }
}

/** v2.25: the three personality modes. The mode is read when a session starts; changing it while running reconnects the session (memory is kept). */
object PersonaMode {
    const val CARE = "care"
    const val BOSS = "boss"
    const val FRIEND = "friend"
    val ALL = listOf(CARE, BOSS, FRIEND)
    fun label(m: String) = when (m) { BOSS -> "Boss mode"; FRIEND -> "Friend mode"; else -> "Care mode" }
    fun note(m: String) = when (m) {
        BOSS -> "FRIDAY-style: short, precise, no extras. Calls you Boss."
        FRIEND -> "A best friend with all the feelings: happy, sad, angry, jealous. No romance."
        else -> "A real girlfriend: caring, with all the feelings: love, anger, jealousy, sadness."
    }
    fun normalize(s: String): String? = when (s.trim().lowercase()) { CARE, "girlfriend" -> CARE; BOSS, "friday" -> BOSS; FRIEND -> FRIEND; else -> null }
    fun saved(c: Context): String = normalize(Prefs.get(c, Prefs.PERSONA, CARE)) ?: CARE
    /** Saves the mode; a running conversation reconnects with the new personality. */
    fun apply(c: Context, m: String) {
        if (m == saved(c)) return
        Prefs.put(c, Prefs.PERSONA, m)
        if (AppState.running.value)
            c.startService(Intent(c, AssistantService::class.java).setAction(AssistantService.ACTION_PERSONA))
    }
}
