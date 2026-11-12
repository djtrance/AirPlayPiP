package io.github.jqssun.airplay

import org.junit.Assert.*
import org.junit.Test

class DualAudioPolicyTest {
    @Test fun singleReceiverAlwaysOwnsAudio() {
        assertEquals(DualAudioRoute(true, false), DualAudioPolicy.route(false, "mute", "secondary"))
    }

    @Test fun activeScreenSelectionRoutesOnlySelectedSource() {
        assertEquals(DualAudioRoute(true, false), DualAudioPolicy.route(true, "active", "primary"))
        assertEquals(DualAudioRoute(false, true), DualAudioPolicy.route(true, "active", "secondary"))
    }

    @Test fun explicitPrimarySecondaryAndMuteModesAreExclusive() {
        assertEquals(DualAudioRoute(true, false), DualAudioPolicy.route(true, "primary", "secondary"))
        assertEquals(DualAudioRoute(false, true), DualAudioPolicy.route(true, "secondary", "primary"))
        assertEquals(DualAudioRoute(false, false), DualAudioPolicy.route(true, "mute", "primary"))
    }
}
