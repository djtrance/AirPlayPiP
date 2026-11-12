package io.github.jqssun.airplay

import io.github.jqssun.airplay.renderer.VideoDecoderCandidate
import io.github.jqssun.airplay.renderer.VideoDecoderPolicy
import org.junit.Assert.*
import org.junit.Test

class VideoDecoderPolicyTest {
    @Test fun onlyAutomaticModeSwitchesDecoderForPipPresentation() {
        assertTrue(VideoDecoderPolicy.useSoftwareInPip("auto", inPip = true, enabled = true))
        assertFalse(VideoDecoderPolicy.useSoftwareInPip("auto", inPip = false, enabled = true))
        assertFalse(VideoDecoderPolicy.useSoftwareInPip("auto", inPip = true, enabled = false))
        assertFalse(VideoDecoderPolicy.useSoftwareInPip("hardware_compat", inPip = true, enabled = true))
        assertFalse(VideoDecoderPolicy.useSoftwareInPip("software", inPip = false, enabled = true))
    }

    @Test fun hardwareModeCannotSelectSoftwareOrUnsupportedSize() {
        val result = VideoDecoderPolicy.chooseHardware(listOf(
            VideoDecoderCandidate("c2.android.avc.decoder", true, false),
            VideoDecoderCandidate("vendor.small.hw", false, true, supportsSize = false),
            VideoDecoderCandidate("OMX.realtek.video.decoder", false, true)))
        assertEquals("OMX.realtek.video.decoder", result?.name)
    }

    @Test fun absentHardwareCannotFallBackToSoftware() {
        assertNull(VideoDecoderPolicy.chooseHardware(listOf(VideoDecoderCandidate("OMX.google.h264.decoder", true, false))))
    }

    @Test fun realtekGetsPlainConfigurationEvenInAutomaticMode() {
        assertTrue(VideoDecoderPolicy.conservativeHardware("auto", "OMX.realtek.video.decoder"))
        assertTrue(VideoDecoderPolicy.conservativeHardware("auto", "C2.REALTEK.AVC.DECODER"))
    }

    @Test fun explicitHardwareCompatibilityIsPlainWithoutChangingOtherAutoDevices() {
        assertTrue(VideoDecoderPolicy.conservativeHardware("hardware_compat", "OMX.qcom.video.decoder.avc"))
        assertFalse(VideoDecoderPolicy.conservativeHardware("auto", "OMX.qcom.video.decoder.avc"))
    }

    @Test fun prefersAospC2OverOtherSoftwareAndRejectsVendorVpu() {
        val result = VideoDecoderPolicy.chooseSoftware(listOf(
            VideoDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC", false, true),
            VideoDecoderCandidate("OMX.google.h264.decoder", true, false),
            VideoDecoderCandidate("c2.android.avc.decoder", true, false)))
        assertEquals("c2.android.avc.decoder", result?.name)
    }

    @Test fun strictSoftwareSelectionNeverReturnsHardwareOnlyDecoder() {
        assertNull(VideoDecoderPolicy.chooseSoftware(listOf(
            VideoDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC", false, true),
            VideoDecoderCandidate("c2.mtk.avc.decoder", false, true))))
    }

    @Test fun firstUnsupportedSoftwareCandidateDoesNotHideCompatibleAlternative() {
        val result = VideoDecoderPolicy.chooseSoftware(listOf(
            VideoDecoderCandidate("c2.android.avc.decoder", true, false, supportsSize = false),
            VideoDecoderCandidate("OMX.google.h264.decoder", true, false)))
        assertEquals("OMX.google.h264.decoder", result?.name)
    }

    @Test fun oldAndroidGoogleCodecNamesAreRecognizedWithoutModernFlags() {
        assertTrue(VideoDecoderPolicy.isSoftware(VideoDecoderCandidate("OMX.google.h264.decoder", false, false)))
    }

    @Test fun unknownComponentsNeedExplicitSoftwareOnlyAndNoHardwareAcceleration() {
        assertNull(VideoDecoderPolicy.chooseSoftware(listOf(VideoDecoderCandidate("vendor.decoder", false, false))))
        assertNull(VideoDecoderPolicy.chooseSoftware(listOf(VideoDecoderCandidate("vendor.decoder", true, true))))
        assertEquals("vendor.decoder", VideoDecoderPolicy.chooseSoftware(listOf(
            VideoDecoderCandidate("vendor.decoder", true, false)))?.name)
    }

    @Test fun unsupportedSoftwareSizeReturnsUnavailableNotHardwareFallback() {
        assertNull(VideoDecoderPolicy.chooseSoftware(listOf(
            VideoDecoderCandidate("c2.android.avc.decoder", true, false, supportsSize = false),
            VideoDecoderCandidate("vendor.hardware.avc", false, true))))
    }
}
