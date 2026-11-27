package ai.yasmin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.41: the pure parts of Live Vision (throttle and camera generations). No Android classes are touched. */
class LiveVisionTest {
    @Test fun firstFrameIsDueAtOnce() {
        assertTrue(LiveVision.due(5000L, 0L, 1000L))
    }

    @Test fun nextFrameWaitsForTheInterval() {
        assertFalse(LiveVision.due(1500L, 1000L, 1000L))
        assertTrue(LiveVision.due(2000L, 1000L, 1000L))
    }

    @Test fun intervalIsClamped() {
        assertEquals(LiveVision.MIN_INTERVAL_MS, LiveVision.clampInterval(10L))
        assertEquals(LiveVision.MAX_INTERVAL_MS, LiveVision.clampInterval(99_999L))
        assertEquals(3000L, LiveVision.clampInterval(3000L))
    }

    @Test fun staleGenerationNeverMakesTheCameraReady() {
        val old = LiveVision.newGeneration()
        val current = LiveVision.newGeneration()
        assertFalse(LiveVision.isCurrent(old))
        LiveVision.markActive(old, true)                       // a late frame of the previous lens
        assertEquals(LiveVision.Cam.STARTING, LiveVision.cam.value)
        LiveVision.markActive(current, false)                  // the first frame of the new lens
        assertEquals(LiveVision.Cam.ACTIVE, LiveVision.cam.value)
        assertFalse(LiveVision.facingBack.value)
        LiveVision.invalidate()
        assertFalse(LiveVision.isCurrent(current))
        LiveVision.release()
        assertEquals(LiveVision.Cam.OFF, LiveVision.cam.value)
    }

    @Test fun failureOfAnOldBindingIsIgnored() {
        val old = LiveVision.newGeneration()
        val current = LiveVision.newGeneration()
        LiveVision.fail(old, "old camera failed")
        assertEquals(LiveVision.Cam.STARTING, LiveVision.cam.value)
        LiveVision.fail(current, "no front camera")
        assertEquals(LiveVision.Cam.ERROR, LiveVision.cam.value)
        LiveVision.release()
    }

    @Test fun noFramesAreCountedWhenNoSessionIsConnected() {
        VisionBridge.sendFrame = null
        LiveVision.startStreaming(announce = false)
        assertFalse(LiveVision.deliverFrame(ByteArray(10)))
        assertEquals(0, LiveVision.framesSent.value)
        LiveVision.stopStreaming()
        assertFalse(LiveVision.streaming.value)
    }
}
