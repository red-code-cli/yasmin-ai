package ai.yasmin.assistant

import android.app.Application

/** Application class. Its only job is to give the custom lock a place to start in every process start (also when Android starts a service at boot). */
class YasminApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.migrateDefaults(this)   // v2.33: Gemini 3.1 Flash Live + Leda voice become the active choice once
        ReplySettings.init(this)
        YasminLockManager.init(this)
        EdgeGlow.init(this)   // v2.38: the glow around the screen edge while Yasmin is on
    }
}
