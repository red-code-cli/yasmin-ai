package ai.yasmin.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Normalized / picture-space (768 x 1376) anchor & deformation zones for one character model. */
data class Point2f(val x: Float, val y: Float)

data class EllZone(
    val pivotX: Float,
    val pivotY: Float,
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val amp: Float = 0.17f
)

data class HairZone(
    val topY: Float,
    val midY: Float,
    val fadeStartY: Float,
    val fadeEndY: Float,
    val leftInnerX: Float,
    val leftOuterX: Float,
    val rightInnerX: Float,
    val rightOuterX: Float,
    val rightCutoffStartX: Float = 630f,
    val rightCutoffEndX: Float = 690f
)

data class FaceAnchors(
    val centerX: Float = 384f,
    val hipY: Float = 1180f,
    val headBaseY: Float = 520f,
    val chestY: Float = 560f,
    val eyeL: Point2f,
    val eyeR: Point2f,
    val eyeLW: Float,
    val eyeLH: Float,
    val eyeLTilt: Float,
    val eyeRW: Float,
    val eyeRH: Float,
    val eyeRTilt: Float,
    val browL: Point2f,
    val browR: Point2f,
    val mouth: Point2f,
    val mouthTiltDeg: Float,
    val cheekL: Point2f,
    val cheekR: Point2f,
    val tearL: Point2f,
    val tearR: Point2f,
    val sweat: Point2f,
    val anger: Point2f,
    val gloom: Point2f,
    val skinTopArgb: Long,
    val skinBotArgb: Long,
    val skinMouthArgb: Long,
    val lashArgb: Long = 0xFF5A2A34,
    val browArgb: Long = 0xFF7B424E,
    val mouthLineArgb: Long = 0xFF8E4651
)

data class OutfitSpec(
    val id: String,
    val label: String,
    val note: String,
    val previewColor: Long,
    /** Optional transparent RGBA overlay drawn on the same deformed body mesh (keeps face/hair/animation intact). */
    val overlayRes: Int = 0,
    /** Optional full-art replacement drawable (768x1376) if an outfit swaps the entire picture. */
    val fullArtRes: Int = 0,
    /** Expected drawable file name (for custom art replacement instructions). */
    val assetFileName: String
)

data class CharacterSpec(
    val id: String,
    val name: String,
    val styleTag: String,
    val defaultMood: String,
    val accentColor: Long,
    val petalColor: Long,
    val bodyArtRes: Int,
    val hairMaskRes: Int,
    val bgMaskRes: Int,
    val anchors: FaceAnchors,
    val hairZone: HairZone,
    val earL: EllZone? = null,
    val earR: EllZone? = null,
    val ahoge: EllZone? = null,
    val outfits: List<OutfitSpec>
) {
    fun outfit(outfitId: String): OutfitSpec =
        outfits.firstOrNull { it.id == outfitId } ?: outfits.first()
}

data class CharacterSelection(
    val characterId: String,
    val outfitId: String
)

/**
 * v2.42: Multi-character + outfit catalog.
 * Yasmin stays the first and default character with her exact original artwork, masks, and anchor coordinates.
 */
object CharacterCatalog {
    const val DEFAULT_CHARACTER_ID = "yasmin"
    const val DEFAULT_OUTFIT_ID = "casual"

    val ALL: List<CharacterSpec> by lazy {
        listOf(
            // 1. YASMIN (default, exact v2.31-2.41 look & coordinates preserved)
            CharacterSpec(
                id = "yasmin",
                name = "Yasmin",
                styleTag = "Pink Twin-Tails • Neko AI",
                defaultMood = "calm",
                accentColor = 0xFF38B6FF,
                petalColor = 0xFFFFB3CE,
                bodyArtRes = R.drawable.yasmin_character,
                hairMaskRes = R.drawable.yasmin_hair_mask,
                bgMaskRes = R.drawable.yasmin_bg_mask,
                anchors = FaceAnchors(
                    centerX = 384f,
                    hipY = 1180f,
                    headBaseY = 520f,
                    chestY = 560f,
                    eyeL = Point2f(351f, 260f),
                    eyeR = Point2f(420f, 251f),
                    eyeLW = 17.5f,
                    eyeLH = 13.5f,
                    eyeLTilt = 4f,
                    eyeRW = 18.5f,
                    eyeRH = 14.5f,
                    eyeRTilt = -8f,
                    browL = Point2f(350f, 232f),
                    browR = Point2f(423f, 224f),
                    mouth = Point2f(390f, 298f),
                    mouthTiltDeg = -7f,
                    cheekL = Point2f(335f, 282f),
                    cheekR = Point2f(432f, 273f),
                    tearL = Point2f(349f, 275f),
                    tearR = Point2f(421f, 267f),
                    sweat = Point2f(455f, 232f),
                    anger = Point2f(452f, 198f),
                    gloom = Point2f(333f, 202f),
                    skinTopArgb = 0xFFFFE8D7,
                    skinBotArgb = 0xFFFED6C6,
                    skinMouthArgb = 0xFFFFE6D6,
                    lashArgb = 0xFF6B3036,
                    browArgb = 0xFF8C4A58,
                    mouthLineArgb = 0xFF8E4651
                ),
                hairZone = HairZone(
                    topY = 180f,
                    midY = 600f,
                    fadeStartY = 640f,
                    fadeEndY = 780f,
                    leftInnerX = 345f,
                    leftOuterX = 255f,
                    rightInnerX = 440f,
                    rightOuterX = 530f,
                    rightCutoffStartX = 630f,
                    rightCutoffEndX = 690f
                ),
                earL = EllZone(330f, 170f, 308f, 158f, 28f, 30f, 0.17f),
                earR = EllZone(425f, 165f, 435f, 138f, 26f, 32f, -0.17f),
                ahoge = EllZone(366f, 136f, 368f, 112f, 26f, 28f, 0.12f),
                outfits = listOf(
                    OutfitSpec("casual", "Cyber Casual", "Original cream jacket & black mini skirt", 0xFFFF9EC8, 0, 0, "yasmin_character.webp"),
                    OutfitSpec("party", "Midnight Velvet", "Magenta party dress & star shimmer", 0xFFC43080, R.drawable.outfit_yasmin_party, 0, "outfit_yasmin_party.png"),
                    OutfitSpec("traditional", "Rose Lehenga", "Crimson & gold embroidered lehenga-choli", 0xFFD62A56, R.drawable.outfit_yasmin_traditional, 0, "outfit_yasmin_traditional.png"),
                    OutfitSpec("winter", "Cozy Snow Coat", "Cream wool trench coat & knit scarf", 0xFFF2E8DA, R.drawable.outfit_yasmin_winter, 0, "outfit_yasmin_winter.png")
                )
            ),

            // 2. AANYA (Midnight-violet wavy hair, emerald eyes, warm & cheerful)
            CharacterSpec(
                id = "aanya",
                name = "Aanya",
                styleTag = "Violet Waves • Warm & Bright",
                defaultMood = "happy",
                accentColor = 0xFF3FD5B6,
                petalColor = 0xFFC9B6FF,
                bodyArtRes = R.drawable.char_aanya_casual,
                hairMaskRes = R.drawable.char_aanya_hair_mask,
                bgMaskRes = R.drawable.char_aanya_bg_mask,
                anchors = FaceAnchors(
                    centerX = 384f,
                    hipY = 1220f,
                    headBaseY = 440f,
                    chestY = 530f,
                    eyeL = Point2f(347f, 345f),
                    eyeR = Point2f(420f, 345f),
                    eyeLW = 19.0f,
                    eyeLH = 15.5f,
                    eyeLTilt = 0f,
                    eyeRW = 19.0f,
                    eyeRH = 15.5f,
                    eyeRTilt = 0f,
                    browL = Point2f(347f, 319f),
                    browR = Point2f(420f, 319f),
                    mouth = Point2f(384f, 383f),
                    mouthTiltDeg = 0f,
                    cheekL = Point2f(333f, 368f),
                    cheekR = Point2f(433f, 368f),
                    tearL = Point2f(347f, 360f),
                    tearR = Point2f(420f, 360f),
                    sweat = Point2f(448f, 312f),
                    anger = Point2f(448f, 282f),
                    gloom = Point2f(328f, 288f),
                    skinTopArgb = 0xFFFEE7DB,
                    skinBotArgb = 0xFFFCDBCE,
                    skinMouthArgb = 0xFFFEE7DB,
                    lashArgb = 0xFF33263F,
                    browArgb = 0xFF42325A,
                    mouthLineArgb = 0xFF8C4C52
                ),
                hairZone = HairZone(
                    topY = 260f,
                    midY = 560f,
                    fadeStartY = 610f,
                    fadeEndY = 700f,
                    leftInnerX = 345f,
                    leftOuterX = 250f,
                    rightInnerX = 425f,
                    rightOuterX = 520f
                ),
                ahoge = EllZone(379f, 232f, 381f, 212f, 24f, 24f, 0.13f),
                outfits = listOf(
                    OutfitSpec("casual", "Mustard Hoodie", "Cropped mustard hoodie & denim skirt", 0xFFE5B44C, 0, 0, "char_aanya_casual.webp"),
                    OutfitSpec("party", "Emerald Satin", "Emerald evening dress & gold belt", 0xFF1A9886, R.drawable.outfit_aanya_party, 0, "outfit_aanya_party.png"),
                    OutfitSpec("traditional", "Peacock Saree", "Peacock-blue & gold silk saree drape", 0xFF1684A6, R.drawable.outfit_aanya_traditional, 0, "outfit_aanya_traditional.png"),
                    OutfitSpec("school", "Academy Blazer", "Navy blazer, ribbon tie & pleated skirt", 0xFF243666, R.drawable.outfit_aanya_school, 0, "outfit_aanya_school.png")
                )
            ),

            // 3. ZOYA (Silver-lavender layered bob, amethyst eyes, cool & composed)
            CharacterSpec(
                id = "zoya",
                name = "Zoya",
                styleTag = "Silver Bob • Cool & Sharp",
                defaultMood = "calm",
                accentColor = 0xFFB68EFF,
                petalColor = 0xFFD9C8FF,
                bodyArtRes = R.drawable.char_zoya_casual,
                hairMaskRes = R.drawable.char_zoya_hair_mask,
                bgMaskRes = R.drawable.char_zoya_bg_mask,
                anchors = FaceAnchors(
                    centerX = 384f,
                    hipY = 1240f,
                    headBaseY = 340f,
                    chestY = 450f,
                    eyeL = Point2f(348f, 226f),
                    eyeR = Point2f(419f, 226f),
                    eyeLW = 19.0f,
                    eyeLH = 14.5f,
                    eyeLTilt = 0f,
                    eyeRW = 19.0f,
                    eyeRH = 14.5f,
                    eyeRTilt = 0f,
                    browL = Point2f(348f, 200f),
                    browR = Point2f(419f, 200f),
                    mouth = Point2f(384f, 267f),
                    mouthTiltDeg = 0f,
                    cheekL = Point2f(334f, 250f),
                    cheekR = Point2f(433f, 250f),
                    tearL = Point2f(348f, 241f),
                    tearR = Point2f(419f, 241f),
                    sweat = Point2f(446f, 195f),
                    anger = Point2f(446f, 165f),
                    gloom = Point2f(328f, 168f),
                    skinTopArgb = 0xFFFDEAE2,
                    skinBotArgb = 0xFFFDE0D7,
                    skinMouthArgb = 0xFFF9E5DC,
                    lashArgb = 0xFF3E2D4C,
                    browArgb = 0xFF7B6B8D,
                    mouthLineArgb = 0xFF8C5361
                ),
                hairZone = HairZone(
                    topY = 175f,
                    midY = 350f,
                    fadeStartY = 380f,
                    fadeEndY = 440f,
                    leftInnerX = 345f,
                    leftOuterX = 275f,
                    rightInnerX = 425f,
                    rightOuterX = 495f
                ),
                outfits = listOf(
                    OutfitSpec("casual", "Urban Charcoal", "Charcoal jacket, white tee & black skirt", 0xFF6A7080, 0, 0, "char_zoya_casual.webp"),
                    OutfitSpec("party", "Ruby Velvet", "Deep ruby party dress & silver trim", 0xFF9E2044, R.drawable.outfit_zoya_party, 0, "outfit_zoya_party.png"),
                    OutfitSpec("traditional", "Ivory Anarkali", "Ivory & gold embroidered Anarkali", 0xFFF4ECDE, R.drawable.outfit_zoya_traditional, 0, "outfit_zoya_traditional.png"),
                    OutfitSpec("office", "Executive Slate", "Tailored slate blazer & pencil skirt", 0xFF545C70, R.drawable.outfit_zoya_office, 0, "outfit_zoya_office.png")
                )
            ),

            // 4. MEERA (Honey-chestnut twin braids, hazel-gold eyes, sweet & affectionate)
            CharacterSpec(
                id = "meera",
                name = "Meera",
                styleTag = "Honey Braids • Sweet & Gentle",
                defaultMood = "love",
                accentColor = 0xFFF3B768,
                petalColor = 0xFFFFD1BA,
                bodyArtRes = R.drawable.char_meera_casual,
                hairMaskRes = R.drawable.char_meera_hair_mask,
                bgMaskRes = R.drawable.char_meera_bg_mask,
                anchors = FaceAnchors(
                    centerX = 384f,
                    hipY = 1240f,
                    headBaseY = 365f,
                    chestY = 480f,
                    eyeL = Point2f(348f, 260f),
                    eyeR = Point2f(420f, 260f),
                    eyeLW = 19.0f,
                    eyeLH = 15.5f,
                    eyeLTilt = 0f,
                    eyeRW = 19.0f,
                    eyeRH = 15.5f,
                    eyeRTilt = 0f,
                    browL = Point2f(348f, 234f),
                    browR = Point2f(420f, 234f),
                    mouth = Point2f(384f, 300f),
                    mouthTiltDeg = 0f,
                    cheekL = Point2f(335f, 282f),
                    cheekR = Point2f(433f, 282f),
                    tearL = Point2f(348f, 275f),
                    tearR = Point2f(420f, 275f),
                    sweat = Point2f(448f, 224f),
                    anger = Point2f(448f, 194f),
                    gloom = Point2f(328f, 198f),
                    skinTopArgb = 0xFFFBE8DB,
                    skinBotArgb = 0xFFFAD2BE,
                    skinMouthArgb = 0xFFFDE6D7,
                    lashArgb = 0xFF4F3426,
                    browArgb = 0xFF7D5036,
                    mouthLineArgb = 0xFF94514C
                ),
                hairZone = HairZone(
                    topY = 225f,
                    midY = 460f,
                    fadeStartY = 500f,
                    fadeEndY = 560f,
                    leftInnerX = 340f,
                    leftOuterX = 250f,
                    rightInnerX = 428f,
                    rightOuterX = 520f
                ),
                outfits = listOf(
                    OutfitSpec("casual", "Sage Cardigan", "Pastel sage cardigan & cream sundress", 0xFFB8D4B2, 0, 0, "char_meera_casual.webp"),
                    OutfitSpec("party", "Champagne Gold", "Champagne shimmer dress & tulle skirt", 0xFFEECA84, R.drawable.outfit_meera_party, 0, "outfit_meera_party.png"),
                    OutfitSpec("traditional", "Marigold Festive", "Marigold & coral festive salwar-dupatta", 0xFFF2A62C, R.drawable.outfit_meera_traditional, 0, "outfit_meera_traditional.png"),
                    OutfitSpec("winter", "Lavender Puffer", "Pastel lavender winter coat & warm scarf", 0xFFC0AEE4, R.drawable.outfit_meera_winter, 0, "outfit_meera_winter.png")
                )
            ),

            // 5. KAIRA (Ice-cyan twin-tails, coral-amber eyes, playful cyber-pop)
            CharacterSpec(
                id = "kaira",
                name = "Kaira",
                styleTag = "Cyan Twin-Tails • Playful Pop",
                defaultMood = "playful",
                accentColor = 0xFF4AE8FF,
                petalColor = 0xFFBFE9FF,
                bodyArtRes = R.drawable.char_kaira_casual,
                hairMaskRes = R.drawable.char_kaira_hair_mask,
                bgMaskRes = R.drawable.char_kaira_bg_mask,
                anchors = FaceAnchors(
                    centerX = 384f,
                    hipY = 1240f,
                    headBaseY = 395f,
                    chestY = 500f,
                    eyeL = Point2f(348f, 289f),
                    eyeR = Point2f(420f, 289f),
                    eyeLW = 19.0f,
                    eyeLH = 15.5f,
                    eyeLTilt = 0f,
                    eyeRW = 19.0f,
                    eyeRH = 15.5f,
                    eyeRTilt = 0f,
                    browL = Point2f(348f, 264f),
                    browR = Point2f(420f, 264f),
                    mouth = Point2f(384f, 331f),
                    mouthTiltDeg = 0f,
                    cheekL = Point2f(335f, 312f),
                    cheekR = Point2f(433f, 312f),
                    tearL = Point2f(348f, 304f),
                    tearR = Point2f(420f, 304f),
                    sweat = Point2f(448f, 254f),
                    anger = Point2f(448f, 224f),
                    gloom = Point2f(328f, 228f),
                    skinTopArgb = 0xFFFEECDE,
                    skinBotArgb = 0xFFFDD9CC,
                    skinMouthArgb = 0xFFFEECDE,
                    lashArgb = 0xFF4A2528,
                    browArgb = 0xFF287C99,
                    mouthLineArgb = 0xFF8C494E
                ),
                hairZone = HairZone(
                    topY = 200f,
                    midY = 520f,
                    fadeStartY = 560f,
                    fadeEndY = 635f,
                    leftInnerX = 335f,
                    leftOuterX = 210f,
                    rightInnerX = 435f,
                    rightOuterX = 560f
                ),
                ahoge = EllZone(381f, 152f, 390f, 128f, 28f, 26f, 0.14f),
                outfits = listOf(
                    OutfitSpec("casual", "Cyber Bomber", "White & cyan bomber jacket & navy skirt", 0xFF4AE8FF, 0, 0, "char_kaira_casual.webp"),
                    OutfitSpec("party", "Starry Indigo", "Midnight-indigo & neon-cyan party dress", 0xFF2A449E, R.drawable.outfit_kaira_party, 0, "outfit_kaira_party.png"),
                    OutfitSpec("traditional", "Indigo Lehenga", "Royal indigo & silver Indo-Western lehenga", 0xFF3A48A8, R.drawable.outfit_kaira_traditional, 0, "outfit_kaira_traditional.png"),
                    OutfitSpec("school", "Sailor Uniform", "White sailor uniform & cyan ribbon bow", 0xFFF4F8FF, R.drawable.outfit_kaira_school, 0, "outfit_kaira_school.png")
                )
            )
        )
    }

    fun find(id: String?): CharacterSpec {
        val clean = id?.trim()?.lowercase().orEmpty()
        return ALL.firstOrNull { it.id == clean } ?: ALL.first()
    }

    fun resolve(characterId: String?, outfitId: String?): Pair<CharacterSpec, OutfitSpec> {
        val c = find(characterId)
        val o = c.outfit(outfitId?.trim()?.lowercase().orEmpty())
        return c to o
    }
}

/**
 * Reactive character selection store backed by Prefs (does NOT restart AssistantService / voice session).
 */
object CharacterStore {
    private val _selection = MutableStateFlow(
        CharacterSelection(CharacterCatalog.DEFAULT_CHARACTER_ID, CharacterCatalog.DEFAULT_OUTFIT_ID)
    )
    val selection: StateFlow<CharacterSelection> = _selection.asStateFlow()

    @Volatile private var loaded = false

    fun load(ctx: Context): CharacterSelection {
        val cid = Prefs.get(ctx, Prefs.CHARACTER_ID, CharacterCatalog.DEFAULT_CHARACTER_ID)
        val oid = Prefs.get(ctx, Prefs.CHARACTER_OUTFIT, CharacterCatalog.DEFAULT_OUTFIT_ID)
        val (spec, outfit) = CharacterCatalog.resolve(cid, oid)
        val sel = CharacterSelection(spec.id, outfit.id)
        _selection.value = sel
        loaded = true
        return sel
    }

    fun ensureLoaded(ctx: Context): CharacterSelection =
        if (loaded) _selection.value else load(ctx)

    fun selectCharacter(ctx: Context, characterId: String) {
        val spec = CharacterCatalog.find(characterId)
        val curOutfit = _selection.value.outfitId
        val outfit = spec.outfit(curOutfit)
        Prefs.put(ctx, Prefs.CHARACTER_ID, spec.id)
        Prefs.put(ctx, Prefs.CHARACTER_OUTFIT, outfit.id)
        _selection.value = CharacterSelection(spec.id, outfit.id)
        loaded = true
    }

    fun selectOutfit(ctx: Context, outfitId: String) {
        val cur = ensureLoaded(ctx)
        val spec = CharacterCatalog.find(cur.characterId)
        val outfit = spec.outfit(outfitId)
        Prefs.put(ctx, Prefs.CHARACTER_ID, spec.id)
        Prefs.put(ctx, Prefs.CHARACTER_OUTFIT, outfit.id)
        _selection.value = CharacterSelection(spec.id, outfit.id)
    }
}
