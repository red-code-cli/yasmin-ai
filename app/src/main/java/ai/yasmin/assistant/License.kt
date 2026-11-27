package ai.yasmin.assistant

import android.content.Context

/** This build has NO license lock: every feature is always on. (The locked build is a separate zip.) */
object License {
    val FREE_TOOLS: Set<String> = setOf("set_emotion", "set_mode", "go_to_sleep")
    @Suppress("UNUSED_PARAMETER")
    fun isUnlocked(ctx: Context): Boolean = true
    const val LOCKED_MESSAGE = ""
}
