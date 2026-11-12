package io.github.jqssun.airplay

import org.junit.Assert.*
import org.junit.Test

class PresentationPolicyTest {
    @Test fun portraitAndWideSendersRespectAndroidPipLimits() {
        assertEquals(1f / 2.39f, PresentationPolicy.pipAspect(0.2f), 0.00001f)
        assertEquals(2.39f, PresentationPolicy.pipAspect(4f), 0.00001f)
        assertEquals(9f / 16f, PresentationPolicy.pipAspect(9f / 16f), 0.00001f)
    }

    @Test fun invalidAspectFallsBackToLandscape() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach {
            assertEquals(16f / 9f, PresentationPolicy.pipAspect(it), 0.00001f)
        }
    }

    @Test fun autoPipRequiresVisualSessionAndMustNotCompeteWithOverlay() {
        assertTrue(PresentationPolicy.canAutoPip(true, true, true, false))
        assertFalse(PresentationPolicy.canAutoPip(false, true, true, false))
        assertFalse(PresentationPolicy.canAutoPip(true, false, true, false))
        assertFalse(PresentationPolicy.canAutoPip(true, true, false, false))
        assertFalse(PresentationPolicy.canAutoPip(true, true, true, true))
    }
}
