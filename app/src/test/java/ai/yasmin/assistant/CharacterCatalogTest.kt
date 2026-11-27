package ai.yasmin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.42: validates the multi-character + outfit catalog integrity without needing an Android UI runtime. */
class CharacterCatalogTest {

    private val allowedMoods = setOf(
        "happy", "excited", "calm", "sad", "love",
        "surprised", "angry", "jealous", "sulky", "playful", "worried"
    )

    @Test fun hasAtLeastFiveUniqueCharactersAndYasminIsDefault() {
        assertTrue(CharacterCatalog.ALL.size >= 5)
        assertEquals("yasmin", CharacterCatalog.DEFAULT_CHARACTER_ID)
        assertEquals("yasmin", CharacterCatalog.ALL.first().id)
        val ids = CharacterCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun everyCharacterHasFourOutfitsMasksAndValidAnchors() {
        for (c in CharacterCatalog.ALL) {
            assertTrue("${c.id} outfits >= 4", c.outfits.size >= 4)
            assertTrue("${c.id} defaultMood valid", c.defaultMood in allowedMoods)
            assertTrue("${c.id} bodyArtRes != 0", c.bodyArtRes != 0)
            assertTrue("${c.id} hairMaskRes != 0", c.hairMaskRes != 0)
            assertTrue("${c.id} bgMaskRes != 0", c.bgMaskRes != 0)

            val outfitIds = c.outfits.map { it.id }
            assertEquals("${c.id} unique outfit ids", outfitIds.size, outfitIds.distinct().size)
            assertEquals("${c.id} first outfit is casual", "casual", c.outfits.first().id)

            // Every non-casual outfit must provide either an overlay layer or a full-art variant
            for (o in c.outfits.drop(1)) {
                assertTrue(
                    "${c.id}/${o.id} needs overlayRes or fullArtRes",
                    o.overlayRes != 0 || o.fullArtRes != 0
                )
            }

            // Face anchor points must sit inside the 768x1376 picture bounds
            val a = c.anchors
            for (pt in listOf(a.eyeL, a.eyeR, a.browL, a.browR, a.mouth, a.cheekL, a.cheekR, a.tearL, a.tearR, a.sweat, a.anger, a.gloom)) {
                assertTrue("${c.id} anchor x in 0..768", pt.x in 0f..768f)
                assertTrue("${c.id} anchor y in 0..1376", pt.y in 0f..1376f)
            }
            assertTrue("${c.id} eyeL left of eyeR", a.eyeL.x < a.eyeR.x)
        }
    }

    @Test fun unknownCharacterAndOutfitFallBackSafely() {
        val fallback = CharacterCatalog.find("unknown_girl")
        assertEquals("yasmin", fallback.id)

        val (spec, outfit) = CharacterCatalog.resolve("MEERA", "nonexistent_dress")
        assertEquals("meera", spec.id)
        assertNotNull(outfit)
        assertEquals(spec.outfits.first().id, outfit.id)
    }
}
