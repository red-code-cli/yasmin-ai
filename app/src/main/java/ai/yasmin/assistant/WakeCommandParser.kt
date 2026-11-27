package ai.yasmin.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * v2.21: strict wake / sleep phrase parser. No Android classes: it is unit-tested on the JVM.
 *
 * Rules (deliberately narrow):
 *  - The WHOLE utterance, after normalisation, must be exactly one of the phrases built from the explicit lists below.
 *  - No substring, prefix, fuzzy, phonetic or edit-distance matching. "hey yasmin please call mom" is NOT a wake phrase,
 *    "ইয়াসমিন" alone is NOT, "হে ইয়াসমা" is NOT.
 *  - Wake: "Hey Yasmin" / "Hey Yasmin" (or "Okay ..."). Sleep: "Sleep Yasmin" / "Sleep Yasmin" / "Yasmin Sleep" / "Yasmin Sleep".
 *  - A new spelling is accepted only by adding it to the lists below AND to WakeCommandParserTest.
 */
object WakeCommandParser {

    enum class Command { WAKE, SLEEP, NONE }

    // Accepted transcriptions of the name. "ইয়াসমা" (a different word) and bare "ইয়াসমিন" are not wake phrases by themselves.
    private val NAMES = setOf("ইয়াসমিন", "yasmin")

    // "হে ইয়াসমিন" / "Hey Yasmin"
    private val HEY = setOf("হে", "হেই", "hey")

    // "ওকে ইয়াসমিন" / "Okay Yasmin" ("o.k." and "ও কে" normalise to "o k" / "ও কে")
    private val OKAY = setOf("ওকে", "ও কে", "okay", "ok", "o k")

    // "ইয়াসমিন স্লিপ" / "Yasmin Sleep"
    private val SLEEP_WORD = setOf("স্লিপ", "স্লীপ", "শ্লিপ", "sleep")

    // "ইয়াসমিন চুপ করো" / "Yasmin Chup Koro"
    private val QUIET_WORD = setOf("চুপ করো", "chup koro")

    private val WAKE_PHRASES: Set<String> =
        (HEY + OKAY).flatMap { p -> NAMES.map { n -> "$p $n" } }.toSet()

    // v2.23: the name can also come second: "Sleep Yasmin" / "স্লিপ ইয়াসমিন" (only the sleep word itself, not "chup koro")
    private val SLEEP_PHRASES: Set<String> =
        (NAMES.flatMap { n -> (SLEEP_WORD + QUIET_WORD).map { w -> "$n $w" } } +
            SLEEP_WORD.flatMap { w -> NAMES.map { n -> "$w $n" } }).toSet()

    /** The single-word sleep spellings (used to tell "sleep" from a real "lock the screen" request). */
    val sleepWords: Set<String> get() = SLEEP_WORD

    // ---- v2.31: tolerant matching, used ONLY by the service (the strict functions above and their tests are unchanged).
    // Speech recognisers spell the name in many ways; a wake / sleep phrase that is clearly meant must not be lost to a spelling.
    // v2.40: many more spellings and a tolerant sleep command (see isSleepLoose), and the wake phrase may be followed by a filler word.
    private val LOOSE_NAMES = NAMES + setOf(
        "yesmin", "yasmeen", "yasmine", "yazmin", "yashmin", "jasmin", "jasmine", "yasmen", "yasmeen", "yaasmin", "iasmin", "ইয়াসমীন", "ইয়াসমিন", "ইয়াসমিণ", "ইয়াসমিনে",
        "যাসমিন", "ইয়াস্মিন", "ইয়াসমিম", "ইয়াসমিন", "यास्मिन", "यास्मीन", "यासमीन", "यासमिन", "यस्मिन", "जैस्मिन", "जास्मिन"
    )
    private val LOOSE_HEY = HEY + OKAY + setOf("hay", "hei", "hi", "হাই", "hello", "helo", "hallo", "yo", "হ্যালো", "হেলো", "ও", "এই", "ওগো", "hae", "হে হে")

    // words that may follow "Hey Yasmin" without changing its meaning ("hey yasmin wake up", "hey yasmin are you there")
    private val WAKE_FILLER = setOf("please", "plz", "pls", "wake", "up", "are", "you", "there", "now", "come", "back", "জাগো", "ওঠো", "উঠো", "উঠ", "শুনছো", "শুনো", "এসো", "এখন", "প্লিজ", "আছো", "জাগ")

    // v2.40: sleep command. SLEEP_ANY needs no name when it is (nearly) the only thing said; SLEEP_NAMED only together with the name.
    private val SLEEP_NAMES = LOOSE_NAMES + setOf("yasma", "ইয়াসমা", "yasu", "ইয়াসু")
    private val SLEEP_ANY = SLEEP_WORD + setOf("slip", "sleeping", "slap", "স্লিপ", "স্লীপ", "স্লিপিং", "সলিপ", "শ্লিপ", "স্লিপ্", "स्लीप", "स्लिप", "स्लीपिंग")
    private val SLEEP_NAMED = SLEEP_ANY + setOf(
        "ঘুমাও", "ঘুমা", "ঘুমাই", "ঘুমান", "ঘুমিয়ে", "ঘুমাতে", "ঘুম", "ghumao", "ghumau", "ghuma", "ghum", "ghumaw", "gumao",
        "চুপ", "chup", "chhup", "চুপচাপ", "বন্ধ", "bondho", "bandho",
        // Hindi / Hinglish: "Yasmin so jao", "सो जाओ", "so ja", "chup ho jao", "aaram karo"
        "so", "soja", "sojao", "sojaao", "soo", "सो", "सोजा", "सोजाओ", "सोना", "sona", "neend", "नींद", "निंद", "चुप", "शांत", "shant", "shaant",
        "आराम", "aaram", "aram", "rest", "band", "बंद", "बन्द", "ruk", "ruko", "रुको", "रुक"
    )
    private val SLEEP_FILLER = setOf(
        "go", "to", "please", "pls", "plz", "now", "ok", "okay", "hey", "hay", "hi", "hei", "hello", "right", "just", "mode", "ab", "tum", "tumi",
        "koro", "kor", "করো", "কর", "করুন", "যাও", "জাও", "jao", "যা", "চলো", "এখন", "এবার", "এখনই", "প্লিজ", "তুমি", "তুই", "একটু", "ekhon", "ebar", "ekta", "একটা", "দাও", "dao", "হও", "হয়ে", "পড়ো", "পরো", "মোড", "ওকে", "হে", "হেই", "হাই", "ও", "এই",
        // Hindi / Hinglish filler: "so jao", "sleep mode mein chale jao", "ab so ja", "चुप हो जाओ"
        "jao", "jaao", "ja", "jaiye", "jaye", "chale", "chali", "chalo", "chal", "mein", "me", "mai", "main", "kar", "karo", "kardo", "do", "dena", "ho", "hojao", "hona", "abhi", "ek", "thodi", "der", "bas",
        "zara", "ji", "jee", "tu", "yaar", "yar", "na", "naa", "aap", "tum", "mere", "liye", "ke", "lo", "le", "lena", "padh", "padho", "mode",
        "जाओ", "जा", "जाइए", "जाइये", "चले", "चली", "चलो", "चल", "में", "मैं", "मै", "कर", "करो", "कर दो", "दो", "हो", "होजा", "होजाओ", "अब", "अभी", "एक", "थोड़ी", "देर", "बस",
        "ज़रा", "जरा", "जी", "तू", "तुम", "आप", "यार", "ना", "मेरे", "लिए", "के", "ले", "लो", "प्लीज", "मोड", "मोड में", "स्लीप"
    )

    /** "Hey Yasmin" with common spelling variants (and an optional filler word after it). The greeting and the name are still required. */
    fun isWakeLoose(text: String): Boolean {
        val n = normalize(text)
        if (n in WAKE_PHRASES) return true
        val t = n.split(' ')
        if (t.size < 2 || t.size > 5) return false
        val idx = when {
            t[0] in LOOSE_HEY -> 1
            t.size >= 3 && (t[0] + " " + t[1]) in LOOSE_HEY -> 2
            else -> return false
        }
        if (t[idx] !in LOOSE_NAMES) return false
        return t.drop(idx + 1).all { it in WAKE_FILLER }
    }

    /**
     * A sleep command at the END of what was said (\"... yasmin sleep\", \"go to sleep yasmin\", \"yasmin ghumao\", \"ইয়াসমিন স্লিপ\", \"yasmin chup koro\", or just \"sleep\").
     * Only the last sentence counts, and only if it consists of the name, a sleep word and harmless filler words (\"go to\", \"please\", \"now\" ...),
     * so a long sentence that merely mentions sleep never puts Yasmin to sleep. v2.40: works on the end of a long transcript too.
     */
    fun isSleepLoose(text: String): Boolean {
        val segs = text.split(Regex("[.?!।\\n]+")).map { normalize(it) }.filter { it.isNotEmpty() }
        val last = segs.lastOrNull() ?: return false
        if (last in SLEEP_PHRASES) return true
        val all = last.split(' ')
        return sleepTokens(all) || (all.size > 4 && sleepTokens(all.takeLast(4)))   // an unpunctuated transcript: the very end counts
    }

    private fun sleepTokens(t: List<String>): Boolean {
        if (t.isEmpty() || t.size > 7) return false
        val hasName = t.any { it in SLEEP_NAMES }
        val named = t.any { it in SLEEP_NAMED }
        val any = t.any { it in SLEEP_ANY }
        val rest = t.filter { it !in SLEEP_NAMES && it !in SLEEP_NAMED && it !in SLEEP_FILLER }
        if (rest.isNotEmpty()) return false
        return if (hasName) named else (any && t.size <= 5)
    }

    /** Exposed for tests / README generation. */
    val wakePhrases: Set<String> get() = WAKE_PHRASES
    val sleepPhrases: Set<String> get() = SLEEP_PHRASES

    /** Hints for the recogniser (biasing only; matching is still done by [classify]). */
    val wakeBiasingStrings: List<String> = listOf("হে ইয়াসমিন", "ওকে ইয়াসমিন", "Hey Yasmin", "Okay Yasmin")

    /**
     * Unicode NFC, lower case, zero-width characters removed, every character that is not a letter / digit /
     * combining mark (Bengali vowel signs and the virama are combining marks) turned into a space, whitespace collapsed.
     */
    fun normalize(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val sb = StringBuilder(nfc.length)
        for (ch in nfc) {
            when {
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\u200E' || ch == '\u200F' || ch == '\uFEFF' -> {}
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                isMark(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun isMark(ch: Char): Boolean {
        val t = Character.getType(ch)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() ||
            t == Character.ENCLOSING_MARK.toInt()
    }

    fun isWake(text: String): Boolean = normalize(text) in WAKE_PHRASES
    fun isSleep(text: String): Boolean = normalize(text) in SLEEP_PHRASES

    fun classify(text: String): Command {
        val n = normalize(text)
        return when {
            n in WAKE_PHRASES -> Command.WAKE
            n in SLEEP_PHRASES -> Command.SLEEP
            else -> Command.NONE
        }
    }

    /** Alternatives the recogniser may return that are looked at (best first). Everything after is ignored. */
    const val MAX_ALTERNATIVES = 3

    /** If the recogniser reports a confidence (0..1) lower than this for an alternative, that alternative is ignored. Unknown (-1 / missing) is accepted. */
    const val MIN_CONFIDENCE = 0.3f

    /** True if one of the first [MAX_ALTERNATIVES] alternatives is exactly an allowed wake phrase. */
    fun findWake(alternatives: List<String>, confidences: FloatArray? = null): Boolean {
        for (i in 0 until minOf(alternatives.size, MAX_ALTERNATIVES)) {
            val c = confidences?.getOrNull(i) ?: -1f
            if (c in 0f..MIN_CONFIDENCE && c < MIN_CONFIDENCE) continue
            if (isWake(alternatives[i])) return true
        }
        return false
    }
}
