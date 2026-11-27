package ai.yasmin.assistant

import ai.yasmin.assistant.ModeController.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeControllerTest {

    @Test fun startsInRequestedMode() {
        assertTrue(ModeController().isActive)
        assertTrue(ModeController(AssistantMode.SLEEP).isSleeping)
    }

    // ---- SLEEP
    @Test fun sleepingWakesOnlyOnExactWakePhrase() {
        val m = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, m.onTranscript("হে ইয়াসমিন"))
        assertTrue(m.isActive)
        val m2 = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, m2.onTranscript("Okay Yasmin"))
        assertTrue(m2.isActive)
    }
    @Test fun sleepingDiscardsEverythingElse() {
        val m = ModeController(AssistantMode.SLEEP)
        for (t in listOf("ইয়াসমিন", "হে", "হে ইয়াসমা", "ওকে ইয়াসমা", "hey", "okay", "yasmin", "what is the weather",
            "call mom", "hey yasmin call mom", "music music music", "")) {
            assertEquals("'$t'", Decision.DISCARD, m.onTranscript(t))
            assertTrue("still asleep after '$t'", m.isSleeping)
        }
    }
    @Test fun sleepCommandWhileSleepingIsDiscardedNotForwarded() {   // command received in the wrong state
        val m = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.DISCARD, m.onTranscript("Yasmin Sleep"))
        assertEquals(Decision.DISCARD, m.onTranscript("ইয়াসমিন চুপ করো"))
        assertTrue(m.isSleeping)
    }
    @Test fun sleepingNeverForwards() {
        val m = ModeController(AssistantMode.SLEEP)
        assertFalse(listOf("hello", "ইয়াসমিন", "hey", "sleep").any { m.onTranscript(it) == Decision.FORWARD })
    }

    // ---- ACTIVE
    @Test fun activeSleepsOnBothSleepCommands() {
        for (t in listOf("ইয়াসমিন স্লিপ", "Yasmin Sleep", "ইয়াসমিন চুপ করো", "Yasmin Chup Koro", "Yasmin, sleep!")) {
            val m = ModeController(AssistantMode.ACTIVE)
            assertEquals(t, Decision.SLEEP, m.onTranscript(t))
            assertTrue(m.isSleeping)
        }
    }
    @Test fun activeForwardsOrdinarySpeech() {
        val m = ModeController(AssistantMode.ACTIVE)
        for (t in listOf("what is the weather", "ইয়াসমিন", "yasmin sleep now", "please yasmin sleep", "sleep", "ইয়াসমা স্লিপ"))
            assertEquals("'$t'", Decision.FORWARD, m.onTranscript(t))
        assertTrue(m.isActive)
    }
    @Test fun wakePhraseWhileActiveIsOrdinarySpeech() {   // wrong state: no transition, not discarded
        val m = ModeController(AssistantMode.ACTIVE)
        val e = m.epoch
        assertEquals(Decision.FORWARD, m.onTranscript("Hey Yasmin"))
        assertTrue(m.isActive)
        assertEquals(e, m.epoch)
    }

    // ---- cycle
    @Test fun fullCycle() {
        val m = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, m.onTranscript("hey yasmin"))
        assertEquals(Decision.FORWARD, m.onTranscript("tell me a joke"))
        assertEquals(Decision.SLEEP, m.onTranscript("yasmin sleep"))
        assertEquals(Decision.DISCARD, m.onTranscript("tell me a joke"))
        assertEquals(Decision.WAKE, m.onTranscript("ওকে ইয়াসমিন"))
        assertTrue(m.isActive)
    }

    // ---- wake alternatives path used by the recogniser
    @Test fun wakeAlternativesOnlyWorkWhileSleeping() {
        val sleeping = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.WAKE, sleeping.onWakeAlternatives(listOf("hey yasma", "hey yasmin")))
        assertTrue(sleeping.isActive)
        assertEquals(Decision.DISCARD, sleeping.onWakeAlternatives(listOf("hey yasmin")))   // already active
        val s2 = ModeController(AssistantMode.SLEEP)
        assertEquals(Decision.DISCARD, s2.onWakeAlternatives(listOf("hey yasma", "yasmin", "hey")))
        assertTrue(s2.isSleeping)
        assertEquals(Decision.DISCARD, s2.onWakeAlternatives(listOf("hey yasmin"), floatArrayOf(0.05f)))
        assertTrue(s2.isSleeping)
    }

    // ---- stale work must not survive a sleep
    @Test fun epochInvalidatesPendingWork() {
        val m = ModeController(AssistantMode.ACTIVE)
        val e = m.epoch
        assertTrue(m.isCurrent(e))
        m.onTranscript("yasmin sleep")
        assertFalse("work started before sleep is stale", m.isCurrent(e))
        m.onTranscript("hey yasmin")
        assertTrue(m.isActive)
        assertFalse("work started before sleep stays stale after waking", m.isCurrent(e))
        assertTrue(m.isCurrent(m.epoch))
    }
    @Test fun nothingIsCurrentWhileSleeping() {
        val m = ModeController(AssistantMode.SLEEP)
        assertFalse(m.isCurrent(m.epoch))
    }

    // ---- force() and listener
    @Test fun forceReportsRealChangesOnly() {
        val seen = ArrayList<AssistantMode>()
        val m = ModeController(AssistantMode.ACTIVE) { mode, _ -> seen.add(mode) }
        assertFalse(m.force(AssistantMode.ACTIVE))
        assertTrue(m.force(AssistantMode.SLEEP))
        assertFalse(m.force(AssistantMode.SLEEP))
        assertTrue(m.force(AssistantMode.ACTIVE))
        assertEquals(listOf(AssistantMode.SLEEP, AssistantMode.ACTIVE), seen)
    }
    @Test fun listenerFiresOnSpeechTransitions() {
        val seen = ArrayList<AssistantMode>()
        val m = ModeController(AssistantMode.ACTIVE) { mode, _ -> seen.add(mode) }
        m.onTranscript("yasmin sleep"); m.onTranscript("random"); m.onTranscript("hey yasmin")
        assertEquals(listOf(AssistantMode.SLEEP, AssistantMode.ACTIVE), seen)
    }
}
