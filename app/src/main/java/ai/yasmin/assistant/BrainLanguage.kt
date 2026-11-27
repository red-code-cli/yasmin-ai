package ai.yasmin.assistant

import java.util.Locale

/*
 * Main Brain language layer (v2.2).
 *
 * Part of the existing Main Brain, NOT a separate server or AI. [MainBrainReplyWriter] calls it before every reply:
 *   1. [BrainLanguage.detect] decides the writing style of the incoming message (Bengali script, Bengali typed in English
 *      letters = Banglish, English, or a mix) with a small offline word check, so Banglish is never mistaken for English.
 *   2. The result is handed to the Main Brain's own model as an explicit instruction ("reply in Bengali script"), so the model
 *      writes the reply itself. The model is still the one that understands the message.
 *   3. The two FIXED texts the task asks for (first-message introduction, voice-message apology) are chosen here by language,
 *      because they must be exact and must work without any AI call.
 *
 * Nothing here logs, stores or sends message text. Detection looks at the one message (and, only when the message itself has no
 * recognisable language such as an emoji, at earlier saved lines of the same conversation).
 */

enum class WritingStyle { BENGALI_SCRIPT, BANGLISH, ENGLISH, HINDI, HINGLISH, MIXED, UNKNOWN }

/** HINGLISH = Hindi typed in English letters mixed with simple English. It is the DEFAULT when the sender's language is not known. */
enum class ReplyLanguage { BENGALI, ENGLISH, HINDI, HINGLISH, UNKNOWN }

/** No message text inside: only what was decided. */
class LanguageInfo(val style: WritingStyle, val reply: ReplyLanguage) {
    override fun toString(): String = "LanguageInfo(style=${style.name}, reply=${reply.name})"
}

internal object BrainLanguage {

    // ------------------------------------------------------------------ the fixed texts (single line, so the notification Reply field takes them as they are)

    const val GREETING_BN = "আসসালামু আলাইকুম! 😊"
    const val GREETING_EN = "Hello! 😊"
    const val GREETING_HI = "नमस्ते! 😊"
    const val INTRO_BN = GREETING_BN + " আমি মেহেদীর AI অ্যাসিস্ট্যান্ট Yasmin। আপনি চাইলে আমার সঙ্গে কথা বলতে পারেন। আপনার কোনো প্রশ্ন থাকলে বা কোনো সাহায্যের প্রয়োজন হলে আমাকে জানাতে পারেন।"
    const val INTRO_EN = GREETING_EN + " I'm Yasmin, Mehdi's AI assistant. 😊 Feel free to talk to me. If you have any questions or need help with anything, I'm always happy to help."
    // the greeting already says "नमस्ते!", so the introduction sentence continues from it instead of saying it twice
    const val INTRO_HI = GREETING_HI + " मैं मेहदी की AI असिस्टेंट Yasmin हूँ। 😊 आप मुझसे बेझिझक बात कर सकते हैं। अगर आपको कोई सवाल पूछना है या किसी मदद की ज़रूरत है, तो मुझे बताइए।"
    const val GREETING_HG = "Namaste! 😊"
    const val INTRO_HG = GREETING_HG + " Main Yasmin hoon, Mehdi ki AI assistant. 😊 Aap mujhse befikar baat kar sakte hain. Agar aapko koi sawaal ho ya kisi cheez mein madad chahiye, to main khushi se help karungi."
    const val VOICE_HG = "Maaf kijiye, main voice message nahi sun sakti. Please mujhe text message bhejiye."
    const val VOICE_BN = "দুঃখিত, আমি ভয়েস মেসেজ শুনতে পারি না। আপনি দয়া করে লিখে মেসেজ পাঠান।"
    const val VOICE_HI = "क्षमा करें, मैं वॉइस मैसेज नहीं सुन सकती। कृपया मुझे टेक्स्ट मैसेज भेजिए।"
    const val VOICE_EN = "Sorry, I can't listen to voice messages. Please send me a text message instead."

    /** UNKNOWN uses Hinglish (the default language). */
    fun introText(lang: ReplyLanguage): String = when (lang) {
        ReplyLanguage.ENGLISH -> INTRO_EN
        ReplyLanguage.HINDI -> INTRO_HI
        ReplyLanguage.BENGALI -> INTRO_BN
        else -> INTRO_HG
    }

    fun voiceText(lang: ReplyLanguage): String = when (lang) {
        ReplyLanguage.ENGLISH -> VOICE_EN
        ReplyLanguage.HINDI -> VOICE_HI
        ReplyLanguage.BENGALI -> VOICE_BN
        else -> VOICE_HG
    }

    /**
     * The reply to a voice message / audio file. The audio itself is never available, so nothing is made up about it: this is always
     * the fixed apology. When it is the first reply to this person the introduction comes first.
     */
    fun voiceReply(history: List<StoredMessage>, isPlaceholder: (String) -> Boolean, introduce: Boolean): String {
        val lang = conversationLanguage(history, isPlaceholder)
        val apology = voiceText(lang)
        return if (introduce) introText(lang) + " " + apology else apology
    }

    // ------------------------------------------------------------------ detection

    private val WORD = Regex("[\\p{L}\\p{M}]+")

    /** Distinctive Bengali words typed in English letters. Words that are also ordinary English words ("to", "me", "a", "or") are left out on purpose. */
    private val BANGLISH: Set<String> = setOf(
        "ami", "tumi", "tui", "apni", "amra", "tomra", "apnara", "se", "tara", "amar", "tomar", "tor", "apnar", "tar", "tader", "amader", "tomader",
        "amake", "tomake", "apnake", "tokey", "amay", "tomay", "ke", "kake", "kar", "ki", "kii", "kemon", "kemone", "kemni", "kothay", "kothai", "kotha",
        "koi", "kobe", "keno", "kon", "koto", "kokhon", "kibhabe", "kivabe", "khobor", "khabar", "khub", "khubi",
        "acho", "achho", "achi", "ache", "achen", "asho", "aso", "ashbo", "asbo", "ashi", "jabo", "jabe", "jao", "jaw", "jacchi", "jachhi", "jai", "jete",
        "korchi", "korcho", "korchho", "korchen", "korsi", "korsos", "koro", "kor", "kora", "korbo", "korbe", "korte", "kori", "korechi", "korecho", "korlam",
        "bolo", "bolbo", "bolchi", "bolcho", "bolte", "bolen", "bollam", "bolechi", "bhalo", "valo", "bhalobasha", "bhalobashi", "bhalobasi", "valobasha",
        "valobashi", "valobasi", "bhalobaso", "naki", "nai", "nei", "na", "nao", "dao", "dibo", "nibo", "hya", "hyan", "hae", "jani", "janina", "jano", "janen",
        "chai", "chaina", "chao", "chacchi", "cheno", "chini", "chinina", "lagbe", "lage", "hobe", "hoy", "hoye", "hoyeche", "hoyechhe", "holo", "hoyni", "thako",
        "thaki", "thakbo", "thake", "thakben", "ekhon", "ekhono", "ekhane", "okhane", "ekhanei", "tahole", "tarpor", "tarpore", "dhonnobad", "dhonyobad", "kintu",
        "ebong", "abar", "tai", "tobe", "ei", "oi", "eta", "ota", "ekta", "ekdom", "onek", "aro", "ektu", "bondhu", "bhai", "apu", "didi", "kaj", "kaaj", "nam",
        "naam", "bari", "bashay", "shokal", "bikel", "shondha", "raat", "shuvo", "subho", "moner", "pagol", "sundor", "porashona", "pori", "porchi",
        "khelbo", "khai", "khaichi", "khabo", "khaiso", "ghum", "ghumai", "ghumao", "ghumaichho", "parbo", "parbe", "parbena", "pari", "parlam", "dekho", "dekhi",
        "dekhbo", "dekha", "shono", "shuno", "sunbo", "bujhi", "bujhte", "bujhlam", "bujhchi", "boloto", "rakho", "rekho", "jaan", "babu", "keu", "kew",
        "keo", "kono", "ekjon", "shob", "sobai", "theke", "jonno", "sathe", "shathe", "kore", "kora", "hoyto", "hoito", "oshubidha", "bhul", "thik",
        "thikache", "thikase", "ashole", "asole", "mane", "mone", "mathay", "dhonno", "alhamdulillah", "inshallah", "bolbe", "bolechho", "likho", "likhbo",
        "pathao", "pathabo", "pathiyechho", "pathaiso", "pathaisi", "dhoro", "dhorbo", "shundor", "choto", "boro", "notun", "purono", "ager", "pore", "tokhon",
        "jokhon", "ekhonei", "ekhn", "tmi", "ami", "amr", "tmr", "kmn", "kmne", "khbr", "ase", "asen", "acen"
    )

    /**
     * Distinctive Hindi words typed in English letters (Hinglish). Words that are also English ("me", "to", "the", "hi") or also Banglish
     * ("ami", "ki", "na", "kor") are left out on purpose; Banglish is checked first anyway.
     */
    private val HINGLISH: Set<String> = setOf(
        "hai", "hain", "hoon", "hu", "tha", "thi", "thay", "hota", "hoti", "hote", "raha", "rahi", "rahe", "rha", "rhi", "rhe", "gaya", "gayi", "gaye",
        "kya", "kyun", "kyu", "kyon", "kaise", "kaisa", "kaisi", "kaun", "kahan", "kab", "kitna", "kitni", "kitne", "kidhar", "kaha",
        "nahi", "nahin", "nhi", "nhin", "haan", "ji", "jee", "achha", "accha", "acha", "theek", "bilkul", "zaroor", "shukriya", "dhanyavad", "maaf",
        "aap", "aapko", "aapka", "aapki", "aapke", "tum", "tumhe", "tumhara", "tumhari", "tumhare", "tera", "teri", "tere", "mera", "meri", "mere", "mujhe", "mujhko",
        "hum", "humko", "hamara", "hamari", "unka", "unki", "uska", "uski", "iska", "iski", "apna", "apni", "apne", "yeh", "ye", "woh", "wo", "yahan", "yaha", "wahan", "waha",
        "mai", "mein", "mei", "abhi", "kal", "aaj", "parso", "subah", "shaam", "baad", "pehle", "phir", "fir", "lekin", "magar", "aur", "toh",
        "bhi", "sirf", "bahut", "bohot", "bahot", "thoda", "thodi", "zyada", "jyada", "sab", "sabhi", "kuch", "koi", "kisi", "kuchh",
        "karo", "kar", "karna", "karta", "karti", "karte", "kiya", "kiye", "karunga", "karungi", "kardo", "kardena", "bol", "bolo", "bolna", "bata", "batao", "bataiye", "batana",
        "chahiye", "chahta", "chahti", "chahte", "milna", "mila", "milega", "milegi", "aana", "aao", "aaja", "aaiye", "jana", "jao", "jaana", "chalo", "chal", "chalta",
        "dekho", "dekh", "dekhna", "suno", "suniye", "bhej", "bhejo", "bhejna", "bheja", "bhejiye", "likh", "likho", "padh", "padhai", "samajh", "samjha", "samjhe", "samajhna",
        "wala", "wali", "wale", "kaam", "ghar", "paisa", "paise", "yaar", "yar", "dost", "bhaiya", "beta", "behen", "bhabhi", "chacha", "papa", "mummy",
        "kripya", "kripa", "namaste", "namaskar", "salaam", "pata", "malum", "maloom", "zindagi", "pyaar", "pyar", "dil", "mohabbat", "sach", "jhooth", "galat", "sahi",
        "kyunki", "isliye", "toh", "matlab", "wahi", "yahi", "aisa", "aisi", "waisa", "jaisa", "kaisi", "bss", "arre", "oye", "ruk", "ruko", "rukiye", "intezaar"
    )

    /** Ordinary English words, enough to recognise an English sentence. */
    private val ENGLISH: Set<String> = setOf(
        "i", "am", "is", "are", "was", "were", "be", "been", "the", "this", "that", "these", "those", "what", "who", "whom", "whose", "why", "when", "where", "which",
        "how", "you", "your", "yours", "he", "she", "it", "we", "they", "them", "his", "her", "its", "our", "their", "my", "me", "mine", "us", "do", "does", "did",
        "doing", "done", "have", "has", "had", "having", "will", "would", "can", "could", "should", "shall", "may", "might", "must", "not", "no", "yes", "yeah",
        "and", "but", "or", "if", "so", "because", "then", "than", "of", "in", "on", "at", "for", "with", "from", "to", "by", "about", "into", "over", "after",
        "before", "up", "down", "out", "off", "here", "there", "now", "just", "also", "very", "too", "really", "please", "thanks", "thank", "sorry", "hello", "hi",
        "hey", "good", "morning", "afternoon", "evening", "night", "fine", "well", "great", "nice", "old", "new", "age", "name", "live", "work", "working", "help",
        "tell", "say", "said", "know", "want", "need", "like", "love", "see", "look", "come", "go", "going", "get", "got", "give", "take", "make", "call", "calling",
        "send", "message", "text", "reply", "answer", "ask", "talk", "speak", "chat", "free", "busy", "online", "offline", "today", "tomorrow", "yesterday", "time",
        "any", "anything", "something", "someone", "anyone", "everything", "nothing", "all", "some", "more", "much", "many", "what's", "whats", "an", "a",
        "assistant", "ai", "robot", "real", "human", "person", "friend", "number", "phone", "meet", "meeting", "available", "unavailable", "where's", "who's", "again"
    )

    /** Spelling shapes that only Banglish has ("korchi", "bolcho", "bhalobasha..."). Short words are never judged by shape. */
    private fun looksBanglish(w: String): Boolean {
        if (w.length < 5) return false
        return w.startsWith("bhalo") || w.startsWith("valo") || w.startsWith("kemon") || w.startsWith("tomar") || w.startsWith("khobor") ||
            w.endsWith("chhi") || w.endsWith("chho") || w.endsWith("cchi") || w.endsWith("ccho") || w.endsWith("chhen") || w.endsWith("cchen")
    }

    /** Writing style and reply language of ONE message. Never throws. */
    fun detect(text: String): LanguageInfo {
        var script = 0      // words with Bengali script letters
        var banglish = 0    // words typed in English letters that are Bengali
        var english = 0     // ordinary English words
        var hindi = 0       // words with Devanagari letters (Hindi)
        var hinglish = 0    // Hindi words typed in English letters
        for (m in WORD.findAll(text)) {
            val w = m.value
            if (w.any { it.code in 0x0980..0x09FF }) { script++; continue }
            if (w.any { it.code in 0x0900..0x097F }) { hindi++; continue }
            if (!w.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }) continue   // another script: no vote
            val l = w.lowercase(Locale.ROOT)
            when {
                l in BANGLISH || looksBanglish(l) -> banglish++
                l in HINGLISH -> hinglish++
                l in ENGLISH -> english++
            }
        }
        val bangla = script + banglish
        if (bangla == 0 && hindi == 0 && hinglish == 0 && english == 0) return LanguageInfo(WritingStyle.UNKNOWN, ReplyLanguage.UNKNOWN)
        // the dominant language wins; a tie goes to Bengali, then Hindi (Devanagari), then Hinglish, then English
        val reply = when {
            bangla > 0 && bangla >= hindi && bangla >= hinglish && bangla >= english -> ReplyLanguage.BENGALI
            hindi > 0 && hindi >= hinglish && hindi >= english -> ReplyLanguage.HINDI
            hinglish > 0 && hinglish >= english -> ReplyLanguage.HINGLISH
            else -> ReplyLanguage.ENGLISH
        }
        val hindiGroup = hindi + hinglish
        val kinds = (if (bangla > 0) 1 else 0) + (if (hindiGroup > 0) 1 else 0) + (if (english > 0) 1 else 0)
        val style = when {
            // Hindi in English letters with a few plain English words is simply Hinglish, not a "mix" to worry about
            reply == ReplyLanguage.HINGLISH && bangla == 0 && hindi == 0 -> WritingStyle.HINGLISH
            kinds > 1 -> WritingStyle.MIXED
            hindi > 0 -> WritingStyle.HINDI
            english > 0 -> WritingStyle.ENGLISH
            script > 0 && banglish == 0 -> WritingStyle.BENGALI_SCRIPT
            banglish > 0 && script == 0 -> WritingStyle.BANGLISH
            else -> WritingStyle.MIXED
        }
        return LanguageInfo(style, reply)
    }

    /**
     * The conversation's language when the new message itself says nothing (an emoji, "ok", a name): the newest earlier line that has a
     * recognisable language. [isPlaceholder] says that a saved line is a photo / voice message / notice, which carries no language.
     * Nothing found = [ReplyLanguage.UNKNOWN].
     */
    fun conversationLanguage(history: List<StoredMessage>, isPlaceholder: (String) -> Boolean): ReplyLanguage {
        for (line in history.asReversed()) {
            if (isPlaceholder(line.text)) continue
            val r = detect(line.text).reply
            if (r != ReplyLanguage.UNKNOWN) return r
        }
        return ReplyLanguage.UNKNOWN
    }

    /** The reply language for [text]: its own language, else the conversation's. */
    fun replyLanguage(text: String, history: List<StoredMessage>, isPlaceholder: (String) -> Boolean): LanguageInfo {
        val own = detect(text)
        if (own.reply != ReplyLanguage.UNKNOWN) return own
        return LanguageInfo(WritingStyle.UNKNOWN, conversationLanguage(history, isPlaceholder))
    }

    /** The instruction line for the Main Brain's model. Fixed wording, no message text. */
    fun instruction(info: LanguageInfo): String = when (info.style) {
        WritingStyle.BANGLISH ->
            "LANGUAGE (decided by the Main Brain, follow it): the sender wrote Bengali (Bangla) using English letters (Banglish). " +
                "Understand it as Bengali and reply in natural Bengali script (বাংলা). Do NOT reply in English and do NOT reply in English letters."
        WritingStyle.BENGALI_SCRIPT ->
            "LANGUAGE (decided by the Main Brain, follow it): the sender wrote Bengali in Bengali script. Reply in natural Bengali script (বাংলা)."
        WritingStyle.ENGLISH ->
            "LANGUAGE (decided by the Main Brain, follow it): the sender wrote plain English. Reply in natural English only."
        WritingStyle.HINDI ->
            "LANGUAGE (decided by the Main Brain, follow it): the sender wrote Hindi in Devanagari script. Reply in natural Hindi in Devanagari script (हिन्दी)."
        WritingStyle.HINGLISH ->
            "LANGUAGE (decided by the Main Brain, follow it): the sender wrote Hindi in English letters (Hinglish). Reply in natural Hinglish: Hindi written in English letters mixed with simple English words, exactly like the sender. Do not use Devanagari and do not reply in pure English."
        WritingStyle.MIXED -> when (info.reply) {
            ReplyLanguage.HINGLISH ->
                "LANGUAGE (decided by the Main Brain, follow it): the sender mixed languages and Hinglish dominates. Understand the whole meaning and reply in natural Hinglish (Hindi in English letters mixed with simple English)."
            ReplyLanguage.ENGLISH ->
                "LANGUAGE (decided by the Main Brain, follow it): the sender mixed languages and English dominates. Understand the whole meaning and reply in natural English."
            ReplyLanguage.HINDI ->
                "LANGUAGE (decided by the Main Brain, follow it): the sender mixed languages and Hindi dominates. Understand the whole meaning and reply in natural Hindi in Devanagari script (हिन्दी)."
            else ->
                "LANGUAGE (decided by the Main Brain, follow it): the sender mixed languages and Bengali dominates. Understand the whole meaning and reply in natural Bengali script (বাংলা); " +
                    "a common English word such as \"online\" may stay in English letters."
        }
        WritingStyle.UNKNOWN -> when (info.reply) {
            ReplyLanguage.ENGLISH -> "LANGUAGE (decided by the Main Brain, follow it): this message has no clear language; the earlier messages are English, so reply in natural English."
            ReplyLanguage.HINDI -> "LANGUAGE (decided by the Main Brain, follow it): this message has no clear language; the earlier messages are Hindi, so reply in natural Hindi in Devanagari script (हिन्दी)."
            ReplyLanguage.BENGALI -> "LANGUAGE (decided by the Main Brain, follow it): this message has no clear language; the earlier messages are Bengali, so reply in natural Bengali script (বাংলা)."
            else -> "LANGUAGE (decided by the Main Brain, follow it): the sender's language is not known (no clear language in this message or the earlier ones). Reply in natural Hinglish: Hindi written in English letters mixed with simple English words. This is the default language."
        }
    }
}
