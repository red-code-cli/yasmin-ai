package ai.yasmin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCommandParserTest {

    private fun wake(s: String) = assertEquals("should WAKE: '$s'", WakeCommandParser.Command.WAKE, WakeCommandParser.classify(s))
    private fun sleep(s: String) = assertEquals("should SLEEP: '$s'", WakeCommandParser.Command.SLEEP, WakeCommandParser.classify(s))
    private fun none(s: String) = assertEquals("should be NONE: '$s'", WakeCommandParser.Command.NONE, WakeCommandParser.classify(s))

    // ---- every allowed wake phrase
    @Test fun wakeBengali() { wake("হে ইয়াসমিন"); wake("ওকে ইয়াসমিন") }
    @Test fun wakeEnglish() { wake("Hey Yasmin"); wake("Okay Yasmin") }
    @Test fun wakeAcceptedTranscriptionVariants() {
        wake("হেই ইয়াসমিন"); wake("হে ইয়াসমিন"); wake("ওকে ইয়াসমিন"); wake("ও কে ইয়াসমিন")
        wake("hey yasmin"); wake("ok yasmin"); wake("ok yasmin"); wake("o.k. yasmin")
        wake("hey ইয়াসমিন"); wake("ওকে yasmin"); wake("Okay Yasmin")
    }
    @Test fun everyDefinedWakePhraseIsAccepted() {
        assertTrue(WakeCommandParser.wakePhrases.isNotEmpty())
        for (p in WakeCommandParser.wakePhrases) wake(p)
    }

    // ---- every allowed sleep command
    @Test fun sleepBengali() { sleep("ইয়াসমিন স্লিপ"); sleep("ইয়াসমিন চুপ করো") }
    @Test fun sleepEnglish() { sleep("Yasmin Sleep"); sleep("Yasmin Chup Koro") }
    @Test fun sleepVariants() { sleep("ইয়াসমিন স্লিপ"); sleep("yasmin sleep"); sleep("ইয়াসমিন চুপ করো"); sleep("yasmin chup koro") }
    @Test fun everyDefinedSleepPhraseIsAccepted() {
        assertTrue(WakeCommandParser.sleepPhrases.isNotEmpty())
        for (p in WakeCommandParser.sleepPhrases) sleep(p)
    }

    // ---- v2.23: name second
    @Test fun sleepNameSecond() {
        sleep("Sleep Yasmin"); sleep("sleep yasmin"); sleep("স্লিপ ইয়াসমিন"); sleep("স্লিপ ইয়াসমিন"); sleep("Sleep, Yasmin!")
        none("sleep yasma"); none("sleep yasmin now"); none("please sleep yasmin"); none("sleep sleep yasmin")
        assertFalse(WakeCommandParser.isWake("sleep yasmin"))
    }
    @Test fun heyYasminWakes() { wake("Hey Yasmin"); wake("hey yasmin"); wake("হে ইয়াসমিন") }

    // ---- wrong names
    @Test fun wrongNamesRejected() {
        none("হে ইয়াসমা"); none("ওকে ইয়াসমা"); none("হে ইয়াসমীন"); none("hey yasma"); none("okay yasma"); none("hey yatu")
        none("ইয়াসমা স্লিপ"); none("yasma sleep"); none("ইয়াসমা চুপ করো")
        none("hey yasmins"); none("hey ayasmin"); none("okay yasmin2")
    }

    // ---- partial phrases
    @Test fun partialPhrasesRejected() {
        none("হে"); none("ইয়াসমিন"); none("ওকে"); none("Hey"); none("Okay"); none("Yasmin"); none("yasmin"); none("ok")
        none("স্লিপ"); none("sleep"); none("চুপ করো"); none("ইয়াসমিন চুপ"); none("yasmin chup"); none("ইয়াসমিন স্লি")
        none("")
        none("   ")
    }

    // ---- no loose / substring / fuzzy matching
    @Test fun extraWordsRejected() {
        none("hey yasmin what is the weather")
        none("well hey yasmin")
        none("hey hey yasmin")
        none("হে ইয়াসমিন আজকের আবহাওয়া কেমন")
        none("ওকে ইয়াসমিন ওকে")
        none("yasmin sleep now")
        none("please yasmin sleep")
        none("hey yasmin yasmin")
        none("ইয়াসমিন স্লিপ করো")
    }
    @Test fun wakePhraseWordsInWrongOrderRejected() {
        none("ইয়াসমিন হে"); none("yasmin hey"); none("yasmin okay"); none("ইয়াসমিন ওকে"); none("yasmin hey"); none("sleep hey yasmin")
    }
    @Test fun wakeAndSleepAreNotMixedUp() {
        assertFalse(WakeCommandParser.isSleep("হে ইয়াসমিন"))
        assertFalse(WakeCommandParser.isWake("ইয়াসমিন স্লিপ"))
        assertFalse(WakeCommandParser.isWake("Yasmin Chup Koro"))
        assertFalse(WakeCommandParser.isSleep("Okay Yasmin"))
    }

    // ---- unrelated speech / background noise
    @Test fun unrelatedSpeechRejected() {
        none("what time is it")
        none("আজ আবহাওয়া কেমন")
        none("the quick brown fox jumps over the lazy dog")
        none("hello hello")
        none("hey there")
        none("okay then")
        none("good night")
        none("ইয়াসমিনের বাড়ি কোথায়")                 // contains the name only as a prefix of another word
        none("this is a hey yasmin in the middle of a sentence")
        none("TV: tonight yasmin sleep deprived")
    }

    // ---- normalisation
    @Test fun capitalisationAndWhitespace() {
        wake("HEY YASMIN"); wake("hEy   YaSmIn"); wake("  hey yasmin  "); wake("hey\tyasmin"); wake("hey\nyasmin")
        sleep("YASMIN SLEEP"); sleep("  Yasmin   Chup   Koro "); sleep("yasmin\u00A0sleep")
    }
    @Test fun punctuationIsIgnored() {
        wake("Hey, Yasmin!"); wake("Hey Yasmin."); wake("\"Okay, Yasmin?\""); wake("হে, ইয়াসমিন!"); wake("ওকে ইয়াসমিন।"); wake("হে-ইয়াসমিন")
        sleep("Yasmin, sleep!"); sleep("Yasmin... sleep."); sleep("ইয়াসমিন, স্লিপ।"); sleep("ইয়াসমিন চুপ করো।")
    }
    @Test fun zeroWidthCharactersAreIgnored() {
        wake("হে\u200C ইয়াসমিন"); wake("হে ইয়াসমিন\u200B"); wake("ওকে\u200D ইয়াসমিন"); sleep("ইয়াসমিন স্লি\u200Cপ")
        wake("\uFEFFhey yasmin")
    }
    @Test fun normalizeKeepsBengaliCombiningMarks() {
        assertEquals("ইয়াসমিন স্লিপ", WakeCommandParser.normalize("  ইয়াসমিন,   স্লিপ। "))
        assertEquals("hey yasmin", WakeCommandParser.normalize("Hey, YASMIN!!"))
        assertEquals("", WakeCommandParser.normalize("!!! ... ???"))
    }
    @Test fun decomposedUnicodeIsNormalised() {
        // NFD form of a precomposed Bengali letter must still match after NFC normalisation
        val nfd = java.text.Normalizer.normalize("হে ইয়াসমিন", java.text.Normalizer.Form.NFD)
        wake(nfd)
    }

    // ---- recogniser alternatives and confidence
    @Test fun alternativesAreOnlyAcceptedWhenOneIsExact() {
        assertTrue(WakeCommandParser.findWake(listOf("hey yasma", "hey yasmin")))
        assertFalse(WakeCommandParser.findWake(listOf("hey yasma", "hey yatu", "hey")))
        assertFalse(WakeCommandParser.findWake(emptyList()))
    }
    @Test fun onlyTheFirstThreeAlternativesAreLooked() {
        assertFalse(WakeCommandParser.findWake(listOf("a", "b", "c", "hey yasmin")))
    }
    @Test fun lowConfidenceAlternativeIgnored() {
        assertFalse(WakeCommandParser.findWake(listOf("hey yasmin"), floatArrayOf(0.1f)))
        assertTrue(WakeCommandParser.findWake(listOf("hey yasmin"), floatArrayOf(0.9f)))
        assertTrue(WakeCommandParser.findWake(listOf("hey yasmin"), floatArrayOf(-1f)))   // unknown confidence
        assertTrue(WakeCommandParser.findWake(listOf("hey yasmin"), null))
    }
    @Test fun sleepPhraseIsNeverAWakeAlternative() {
        assertFalse(WakeCommandParser.findWake(listOf("yasmin sleep")))
    }
}

class WakeCommandParserLooseTest {
    @org.junit.Test fun looseSleep() {
        for (s in listOf("Yasmin sleep", "go to sleep yasmin", "hey yasmin sleep", "yasmin, sleep.", "sleep", "ইয়াসমিন ঘুমাও", "yasmeen sleep", "ok yasmin chup koro"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun looseSleepRejectsLongOrUnrelated() {
        for (s in listOf("I cannot sleep tonight because of the noise yasmin is loud", "what is the weather", "hey yasmin", "good night", ""))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun looseWake() {
        for (s in listOf("Hey Yasmin", "hey yasmeen", "hay yasmin", "ও কে ইয়াসমিন", "হাই ইয়াসমিন"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isWakeLoose(s))
        for (s in listOf("hey yasma", "yasmin", "hey there", "well hey yasmin please", "sleep yasmin"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isWakeLoose(s))
    }
}

class WakeCommandParserV240Test {
    @org.junit.Test fun moreSleepWordings() {
        for (s in listOf("ইয়াসমিন স্লিপ", "Sleep Yasmin", "ইয়াসমিন স্লীপ।", "I said go to sleep yasmin", "ঘুমাও ইয়াসমিন", "yasmin ghumao", "yasma sleep", "sleep now", "go to sleep", "hey yasmin please sleep"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun sleepStillRejectsSentences() {
        for (s in listOf("I want to sleep", "my baby cannot sleep yasmin please tell me why", "good night yasmin", "what is the sleep mode of the phone"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun moreWakeWordings() {
        for (s in listOf("hey yasmin wake up", "hello yasmin", "হ্যালো ইয়াসমিন", "ও ইয়াসমিন", "hey yazmin", "hey yasmin are you there"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isWakeLoose(s))
        for (s in listOf("hey yasma", "yasmin", "hey yasmin call my mother", "yasmin hey"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isWakeLoose(s))
    }
}

class WakeCommandParserHindiSleepTest {
    @org.junit.Test fun hindiAndHinglishSleep() {
        for (s in listOf("Yasmin so jao", "yasmin so ja", "यास्मिन सो जाओ", "ইয়াসমিন so jao", "Yasmin sleep mode mein chale jao", "sleep mode mein chale jao", "yasmin ab so jao please",
            "yasmin chup ho jao", "yasmin aaram karo", "यास्मिन स्लीप मोड में चले जाओ", "sleep mode mein jao", "yasmin neend lo"))
            org.junit.Assert.assertTrue(s, WakeCommandParser.isSleepLoose(s))
    }
    @org.junit.Test fun hindiSleepStillRejectsNormalSentences() {
        for (s in listOf("so what is the weather", "mujhe so jana hai yasmin kal subah", "main so raha tha", "so jao"))
            org.junit.Assert.assertFalse(s, WakeCommandParser.isSleepLoose(s))
    }
}
