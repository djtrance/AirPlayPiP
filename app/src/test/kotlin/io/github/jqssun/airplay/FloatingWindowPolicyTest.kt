package io.github.jqssun.airplay

import io.github.jqssun.airplay.FloatingWindowPolicy.Direction.*
import org.junit.Assert.*
import org.junit.Test

class FloatingWindowPolicyTest {
    @Test fun remoteMovesOnlyTheRequestedCoordinate() {
        assertEquals("top_right", FloatingWindowPolicy.move("bottom_right", UP))
        assertEquals("bottom_left", FloatingWindowPolicy.move("top_left", DOWN))
        assertEquals("top_left", FloatingWindowPolicy.move("top_right", LEFT))
        assertEquals("bottom_right", FloatingWindowPolicy.move("bottom_left", RIGHT))
    }

    @Test fun pressingTheSameDirectionAtTheEdgeIsIdempotent() {
        assertEquals("top_left", FloatingWindowPolicy.move("top_left", UP))
        assertEquals("bottom_right", FloatingWindowPolicy.move("bottom_right", RIGHT))
    }

    @Test fun notificationCyclesAllFourCorners() {
        var corner = "top_left"
        val visited = mutableSetOf<String>()
        repeat(4) {
            visited.add(corner)
            corner = FloatingWindowPolicy.nextCorner(corner)
        }
        assertEquals(4, visited.size)
        assertEquals("top_left", corner)
    }

    @Test fun stillSenderDoesNotTriggerSurfaceRecreation() {
        val policy = FloatingRecoveryPolicy()
        assertFalse(policy.shouldRecreate(0, 0, 0))
        assertFalse(policy.shouldRecreate(5_000, 0, 0))
        assertFalse(policy.shouldRecreate(20_000, 0, 0))
    }

    @Test fun arrivingFramesWithoutPresentationTriggerRecoveryAfterGracePeriod() {
        val policy = FloatingRecoveryPolicy()
        assertFalse(policy.shouldRecreate(0, 10, 5))
        assertFalse(policy.shouldRecreate(3_000, 20, 5))
        assertTrue(policy.shouldRecreate(4_000, 30, 5))
        assertFalse(policy.shouldRecreate(5_000, 40, 5))
    }

    @Test fun repeatedRecoveryIsBoundedUntilOutputResumes() {
        val policy = FloatingRecoveryPolicy()
        policy.shouldRecreate(0, 1, 0)
        assertTrue(policy.shouldRecreate(4_000, 2, 0))
        assertTrue(policy.shouldRecreate(8_000, 3, 0))
        assertTrue(policy.shouldRecreate(12_000, 4, 0))
        assertFalse(policy.shouldRecreate(16_000, 5, 0))
        assertFalse(policy.shouldRecreate(20_000, 6, 1)) // Real output progress resets the budget.
        assertTrue(policy.shouldRecreate(24_000, 7, 1))
    }

    @Test fun noRecoveryAfterSenderStopsSending() {
        val policy = FloatingRecoveryPolicy()
        policy.shouldRecreate(0, 1, 0)
        assertFalse(policy.shouldRecreate(9_000, 1, 0))
    }
}
