package ai.yasmin.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class BrainLanguageTest {
    private fun lang(t: String) = BrainLanguage.detect(t).reply

    @Test fun englishGetsEnglish() { assertEquals(ReplyLanguage.ENGLISH, lang("Where are you? Please call me today")) }
    @Test fun devanagariGetsHindi() { assertEquals(ReplyLanguage.HINDI, lang("आप कैसे हैं")) }
    @Test fun romanHindiGetsHinglish() {
        assertEquals(ReplyLanguage.HINGLISH, lang("Kya haal hai bhai, kal milte hain"))
        assertEquals(ReplyLanguage.HINGLISH, lang("aap kaise ho? mujhe madad chahiye"))
    }
    @Test fun bengaliScriptAndBanglishGetBengali() {
        assertEquals(ReplyLanguage.BENGALI, lang("আপনি কেমন আছেন"))
        assertEquals(ReplyLanguage.BENGALI, lang("Tumi kemon acho?"))
    }
    @Test fun unknownDefaultsToHinglish() {
        assertEquals(ReplyLanguage.UNKNOWN, lang("👍"))
        assertEquals(BrainLanguage.INTRO_HG, BrainLanguage.introText(ReplyLanguage.UNKNOWN))
        assertEquals(BrainLanguage.VOICE_HG, BrainLanguage.voiceText(ReplyLanguage.UNKNOWN))
    }
}

class LicenseTest {
    @Test fun freeToolsAreOnlyTalkingTools() {
        assertEquals(setOf("set_emotion", "set_mode", "go_to_sleep"), License.FREE_TOOLS)
    }
}
